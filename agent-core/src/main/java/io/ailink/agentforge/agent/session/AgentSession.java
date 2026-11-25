package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.attach.Attach;
import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.context.Backfill;
import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.context.ContextMath;
import io.ailink.agentforge.agent.context.Nudge;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.context.TokenBudgetCompressor;
import io.ailink.agentforge.agent.cost.Cost;
import io.ailink.agentforge.agent.loop.AgentPartialException;
import io.ailink.agentforge.agent.loop.AgentResult;
import io.ailink.agentforge.agent.loop.AgentRunner;
import io.ailink.agentforge.agent.loop.AgentStatus;
import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.loop.DeltaListener;
import io.ailink.agentforge.agent.loop.Filter;
import io.ailink.agentforge.agent.loop.FilterCtx;
import io.ailink.agentforge.agent.loop.Filters;
import io.ailink.agentforge.agent.loop.GateDecision;
import io.ailink.agentforge.agent.loop.ModelCallException;
import io.ailink.agentforge.agent.loop.ModelCaller;
import io.ailink.agentforge.agent.loop.ModelTurn;
import io.ailink.agentforge.agent.loop.ReactLoop;
import io.ailink.agentforge.agent.loop.RequestOptions;
import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.loop.ToolRequest;
import io.ailink.agentforge.agent.loop.ToolResult;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.PromptCache;
import io.ailink.agentforge.agent.models.ThinkingLevels;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.approval.SessionPermissions;
import io.ailink.agentforge.agent.approval.ToolApprovals;
import io.ailink.agentforge.agent.sandbox.ExecApprovalHost;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.ArgsChecker;
import io.ailink.agentforge.agent.tools.Diff;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.agent.tools.Seen;
import io.ailink.agentforge.agent.tools.Staging;
import io.ailink.agentforge.agent.tools.file.FileTools;
import io.ailink.agentforge.agent.tools.shell.ShellSession;
import io.ailink.agentforge.agent.tools.worktree.WorktreeEnter;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.agent.transcript.SubStep;
import io.ailink.agentforge.agent.transcript.Transcript;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * agent 会话：一段对话的轮次协调层（coxswain {@code coxn/agent/session.ss} + {@code session/*.ss}）。
 *
 * <h2>为什么存在</h2>
 * 工具循环（{@link AgentRunner}）是同步阻塞的：一口气跑完"问 → 调 → 回灌 → 再问"。UI 直接调会冻住。这一层把一轮放到
 * 一条虚拟线程上跑（{@link #send}），回调经共享状态 + {@link SessionEvent} 交给 UI；审批 / 提问用 future 把工具线程
 * 挂起，等 UI 线程 {@link #resolveApproval} / {@link #answerQuestion}。审批怎么判见 {@link ToolApprovals}。{@link #runTurn} 是阻塞版（子 agent 在工具线程上跑它）。
 *
 * <h2>世代号</h2>
 * 每一轮开始 / 每次按停都把 gen 加一；旧那一轮的回调看到 gen 不对就不再写状态（它自然燃尽，结果丢弃）—— 只有一处例外：
 * 被按停那一轮已经跑完的工具**要**落进历史（那些工具真的改过文件，记忆丢了模型下一轮会重做一遍）。
 *
 * <h2>出错 / 按停之后那一轮不消失</h2>
 * 想过的、说了一半的、那一行错，封存进记录区并钉在发生的位置（{@code failLog}）；已经跑完的工具与用户那句话留在历史里
 * （打一句"继续"接得上）；出错那一半的轮（tool_use 没有结果）丢掉 —— 半截历史送回去是 400；排着的话还回草稿。
 *
 * <h2>按停是协作式的</h2>
 * {@link #interrupt} 翻令牌：循环在每次问模型之前 / 每件工具前后看它，流式在每个 chunk 看它，HTTP 与子进程登记了回调会被掐掉。
 * ⚠️ 一段没有检查点的代码停不下来。
 *
 * <p>线程安全：状态都在 {@code lock} 下；事件在调用方线程上同步发（见 {@link SessionListener}）。
 */
public final class AgentSession {

    /** {@link #send} 的结果。 */
    public enum SendResult { SENT, QUEUED, IGNORED }

    /** 用户直接派活（{@code @deep 找一下…}）：解析器认出来就不问模型，直接调那件工具。 */
    public record DirectCall(String tool, ObjectNode args) { }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final int GALLERY_MAX = 16;
    private static final AtomicInteger GALLERY_IDS = new AtomicInteger();

    // ── 身份与协作者 ─────────────────────────────────────────────────────
    private final String id;
    private final AgentSession parent;
    private final int depth;
    private final ModelBackend backend;
    private final Workspace workspace;
    private final SessionConfig config;
    private final SessionPermissions permissions;
    private final boolean approvalViaParent;
    private final ShellSession shell;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final Map<Class<?>, Object> extraServices = new ConcurrentHashMap<>();
    private final Semaphore askSlot = new Semaphore(1, true);
    private final Object lock = new Object();

    // ── 设置（运行时可调）────────────────────────────────────────────────
    private volatile String title;
    private volatile String model;
    private volatile String thinking;
    private volatile Integer maxTokens;
    private volatile Long contextLimitOverride;
    private volatile String system;
    private volatile List<AgentTool> tools;
    private volatile List<String> activeTools;
    private volatile String agent;
    private volatile Function<String, DirectCall> direct;
    private volatile BooleanSupplier toolsSettling;
    private volatile Consumer<Path> onRootChanged;

    // ── 状态（lock 下）───────────────────────────────────────────────────
    private SessionStatus status = SessionStatus.IDLE;
    private List<Message> history = List.of();
    private List<Compaction.Pointer> compactions = List.of();
    private List<Entry> entries = List.of();
    private final List<Entry> turnLog = new ArrayList<>();
    private final List<Transcript.Sealed> failLog = new ArrayList<>();
    private final List<String> queue = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private boolean wakePending;
    private String draft = "";
    private final List<Map.Entry<String, Block>> attachments = new ArrayList<>();
    private final List<GalleryImage> gallery = new ArrayList<>();
    private int gen;
    private int inFlight;          // 还没收完尾的轮数（含被按停、还在燃尽的旧一轮）
    private Turn turn;
    private Thread worker;
    private ApprovalRequest pendingApproval;
    private CompletableFuture<ReviewDecision> approvalFuture;
    private ClarifyService.Request pendingQuestion;
    private CompletableFuture<ClarifyService.Answer> questionFuture;
    private Throwable error;
    private AgentStatus resultStatus;
    private Usage usage = Usage.ZERO;
    private Usage lastUsage;
    private Double cost;
    private boolean costPartial;
    private Long lastInput;
    private Long lastOutput;
    private int cacheMisses;
    private boolean cacheSettled;
    private final StringBuilder streaming = new StringBuilder();
    private String streamingKind = "text";
    private long streamingEpoch;
    private Long thinkStart;
    private Long thinkMs;
    private final List<Entry> thinkLog = new ArrayList<>();
    private final List<SubRun> subLog = new ArrayList<>();
    private SubRun subCurrent;
    private final LinkedHashMap<String, AgentSession> children = new LinkedHashMap<>();
    private String lastCallId;
    /** 改文件的调用动手前记下的 diff（tool-use-id ⇒ 预览格式的 diff）：没过审批的也能画真 diff；只留最近 {@link #CHANGE_DIFFS_MAX} 份。 */
    private final LinkedHashMap<String, String> changeDiffs = new LinkedHashMap<>();
    private static final int CHANGE_DIFFS_MAX = 200;
    private static final java.util.Set<String> CHANGE_TOOLS = java.util.Set.of("write-file", "edit-file", "apply-patch");
    private int turnBase;
    private Long turnStart;
    private Long turnDur;
    private Path pendingEnter;

    /** 一轮的上下文。 */
    private final class Turn {
        final int gen;
        final CancelToken cancel;
        final List<Message> messages;
        final String text;
        volatile List<AgentTool> tools;
        final Map<Class<?>, Object> services = new ConcurrentHashMap<>();
        /** 工具内部审批（run-command）的拒绝 / 停下：toolUseId → 决定；工具跑完由 aftermath 过滤器取走。 */
        final Map<String, ReviewDecision> outcomes = new ConcurrentHashMap<>();

        Turn(int gen, CancelToken cancel, List<Message> messages, String text, List<AgentTool> tools) {
            this.gen = gen;
            this.cancel = cancel;
            this.messages = messages;
            this.text = text;
            this.tools = tools;
        }

        ToolEnv env(String toolUseId) { return new ToolEnv(workspace, cancel, toolUseId, id, services); }
    }

    private AgentSession(Builder b) {
        this.parent = b.parent;
        this.depth = b.parent == null ? 0 : b.parent.depth + 1;
        this.id = b.id != null ? b.id : "s" + COUNTER.incrementAndGet();
        this.backend = Objects.requireNonNull(b.backend, "backend");
        this.workspace = Objects.requireNonNull(b.workspace, "workspace");
        this.config = b.config != null ? b.config : SessionConfig.defaults();
        this.permissions = b.permissions != null ? b.permissions : b.parent != null ? b.parent.permissions : new SessionPermissions();
        this.approvalViaParent = b.approvalViaParent && b.parent != null;
        this.shell = new ShellSession(workspace.root());
        this.title = b.title;
        this.model = b.model;
        this.thinking = b.thinking;
        this.maxTokens = b.maxTokens;
        this.contextLimitOverride = b.contextLimit;
        this.system = b.system;
        this.tools = List.copyOf(b.tools);
        this.agent = b.agent;
        clampThinking();
    }

    public static Builder builder(ModelBackend backend, Workspace workspace) { return new Builder(backend, workspace); }

    /** 建会话的参数。 */
    public static final class Builder {
        private final ModelBackend backend;
        private final Workspace workspace;
        private String id, title, model, thinking, system, agent;
        private Integer maxTokens;
        private Long contextLimit;
        private boolean approvalViaParent = true;
        private List<AgentTool> tools = List.of();
        private SessionConfig config;
        private SessionPermissions permissions;
        private AgentSession parent;

        private Builder(ModelBackend backend, Workspace workspace) {
            this.backend = backend;
            this.workspace = workspace;
        }

        public Builder id(String v) { this.id = v; return this; }

        public Builder title(String v) { this.title = v; return this; }

        public Builder model(String v) { this.model = v; return this; }

        public Builder thinking(String v) { this.thinking = v; return this; }

        public Builder system(String v) { this.system = v; return this; }

        public Builder agent(String v) { this.agent = v; return this; }

        public Builder maxTokens(Integer v) { this.maxTokens = v; return this; }

        /** 覆盖这条模型的窗口（token）；null = 查模型表。 */
        public Builder contextLimit(Long v) { this.contextLimit = v; return this; }

        public Builder tools(List<AgentTool> v) { this.tools = v == null ? List.of() : v; return this; }

        public Builder config(SessionConfig v) { this.config = v; return this; }

        /** 权限状态（审批策略、沙盒、规则、本会话的「不再问」）；子会话缺省共用父会话的。 */
        public Builder permissions(SessionPermissions v) { this.permissions = v; return this; }

        /** 子会话：认爹（id、深度、按停连坐都从这里来）。 */
        public Builder parent(AgentSession v) { this.parent = v; return this; }

        /**
         * 子会话的审批摆到父会话上（缺省开：子 agent 没有界面，审批要摆到有人答的地方）。
         */
        public Builder approvalViaParent(boolean v) { this.approvalViaParent = v; return this; }

        public AgentSession build() { return new AgentSession(this); }
    }

    // ══ 读口 ═════════════════════════════════════════════════════════════

    public String id() { return id; }

    /** 父会话的 id；null = 这是一段主对话。 */
    public String parentId() { return parent == null ? null : parent.id; }

    public AgentSession parent() { return parent; }

    /** 子 agent 的层数：主会话 0。 */
    public int depth() { return depth; }

    public String title() { return title; }

    public void setTitle(String t) { this.title = t == null || t.isEmpty() ? null : t; }

    public Workspace workspace() { return workspace; }

    public ModelBackend backend() { return backend; }

    public SessionConfig config() { return config; }

    /** 权限状态（审批策略 × 沙盒、执行规则、本会话的「不再问」）。改 {@code permissions().setSettings(…)} 下一次工具调用生效。 */
    public SessionPermissions permissions() { return permissions; }

    public ShellSession shell() { return shell; }

    public SessionStatus status() { synchronized (lock) { return status; } }

    public boolean busy() { synchronized (lock) { return status.busy(); } }

    /** 模型看到的那份完整历史（只增不减；压缩是追加 + 指针）。存盘存它。 */
    public List<Message> history() { synchronized (lock) { return history; } }

    /** 恢复一段历史（从库里读回来时）。⚠️ 只在空闲时用；显示列表按它重投影。 */
    public void setHistory(List<Message> h) {
        synchronized (lock) {
            history = List.copyOf(h);
            reproject();
        }
    }

    public List<Compaction.Pointer> compactions() { synchronized (lock) { return compactions; } }

    /** 恢复压缩指针（与 {@link #setHistory} 一起；否则恢复之后第一轮会把整份历史都发出去）。 */
    public void setCompactions(List<Compaction.Pointer> cs) { synchronized (lock) { compactions = List.copyOf(cs); } }

    /** 这一刻按压缩指针重建出来的那份（不含 prune 与裁剪）。 */
    public List<Message> effectiveHistory() { synchronized (lock) { return Compaction.apply(history, compactions); } }

    /** 真正会发出去的那份（重建 + prune）；最后那道"裁"只在真发请求时才有。 */
    public List<Message> sendableHistory() { return Compaction.prune(effectiveHistory(), imageDropper()); }

    /** 存盘用的那份：一律不带图（图只随那一轮发一次，库里存 base64 只会让库膨胀）。 */
    public List<Message> storableHistory() { return Images.dropAll(history(), false); }

    /** 记录区（已落地的部分）。跑着的时候再加上 {@link #turnLog()} 与 {@link #streamingText()}。 */
    public List<Entry> transcript() { synchronized (lock) { return entries; } }

    /** 这一轮的时间线（想完的段、说完的正文、工具调用与结果），按发生顺序；按轮清。 */
    public List<Entry> turnLog() { synchronized (lock) { return List.copyOf(turnLog); } }

    public String streamingText() { synchronized (lock) { return streaming.toString(); } }

    /** {@code text} / {@code thinking}。 */
    public String streamingKind() { synchronized (lock) { return streamingKind; } }

    /** 流式缓冲的世代号：每清空一次 +1（渲染缓存靠它判断"新的这一份是不是旧的加了几个字"）。 */
    public long streamingEpoch() { synchronized (lock) { return streamingEpoch; } }

    /** 这一轮想了多久（毫秒）；null = 还没想过。 */
    public Long thinkMs() { synchronized (lock) { return thinkMs; } }

    public List<Entry> thinkLog() { synchronized (lock) { return List.copyOf(thinkLog); } }

    /** 摆着等答的审批；没有 ⇒ null。 */
    public ApprovalRequest pendingApproval() { synchronized (lock) { return pendingApproval; } }

    public ClarifyService.Request pendingQuestion() { synchronized (lock) { return pendingQuestion; } }

    public Throwable error() { synchronized (lock) { return error; } }

    public String errorLine() { Throwable e = error(); return e == null ? null : Retry.errorLine(e); }

    /** 上一轮循环怎么结束的；没跑过 null。 */
    public AgentStatus resultStatus() { synchronized (lock) { return resultStatus; } }

    /** 这段对话累计的用量（含子 agent 与压缩那几趟）。 */
    public Usage usage() { synchronized (lock) { return usage; } }

    /** 累计估价（美元）；null = 一笔都没算出来。⚠️ 估算不是账单。 */
    public Double cost() { synchronized (lock) { return cost; } }

    /** 有过算不出来的（那条模型没单价）→ 显示"至少"。 */
    public boolean costPartial() { synchronized (lock) { return costPartial; } }

    /** 最近一次请求发出去的全部输入（含缓存读回的那部分）。 */
    public Long lastInput() { synchronized (lock) { return lastInput; } }

    public Long lastOutput() { synchronized (lock) { return lastOutput; } }

    /** 测试 / 恢复用：直接设最近一次的输入输出（压缩判据读它们）。 */
    public void noteLastUsage(Long input, Long output) { synchronized (lock) { lastInput = input; lastOutput = output; } }

    public Long contextLimit() {
        Long o = contextLimitOverride;
        if (o != null) return o;
        ModelMeta m = meta();
        return m == null ? null : m.contextWindow();
    }

    /** 那一次占了窗口的几成（0..1）；null = 不知道。 */
    public Double contextRatio() {
        Long used = lastInput(), lim = contextLimit();
        return used == null || lim == null || lim <= 0 ? null : (double) used / lim;
    }

    /** 留给历史的 token 预算；null = 不知道窗口（不压缩、不裁）。 */
    public Long inputBudget() {
        Long ctx = contextLimit();
        if (ctx == null) return null;
        Integer eff = effectiveMaxTokens();
        long reserve = eff != null ? eff : ThinkingLevels.ANSWER_HEADROOM;
        long left = ctx - reserve;
        long margin = Math.min(8192, (long) Math.floor(0.5 * Math.max(left, 0)));
        long budget = left - margin;
        return budget > 0 ? budget : null;
    }

    public List<String> queue() { synchronized (lock) { return List.copyOf(queue); } }

    public List<String> notes() { synchronized (lock) { return List.copyOf(notes); } }

    /** 草稿：出错 / 按停时排着的话还回这里（不替用户重发）。 */
    public String draft() { synchronized (lock) { return draft; } }

    public void setDraft(String s) { synchronized (lock) { draft = s == null ? "" : s; } }

    public List<GalleryImage> gallery() { synchronized (lock) { return List.copyOf(gallery); } }

    public Long turnDurationMs() { synchronized (lock) { return turnDur; } }

    // ── 设置 ─────────────────────────────────────────────────────────────

    public String model() { return model; }

    /** 换模型：思考档位不在新模型的档位表里就清掉；历史里的图摘掉（另一家未必收）。 */
    public void setModel(String ref) {
        if (ref != null && ref.isEmpty()) throw new IllegalArgumentException("model 要是非空字符串或 null");
        this.model = ref;
        clampThinking();
        forgetAllImages();
    }

    public String thinking() { return thinking; }

    public List<String> thinkingLevels() {
        ModelMeta m = meta();
        return m == null || m.thinkingLevels() == null ? List.of() : m.thinkingLevels();
    }

    /** 档位名（必须在 {@link #thinkingLevels()} 里）或 null = 不发、用服务端默认。 */
    public void setThinking(String level) {
        List<String> lv = thinkingLevels();
        if (level != null && !lv.contains(level)) {
            throw new IllegalArgumentException(lv.isEmpty() ? "这条模型没有可选的思考档位（只能是 null）"
                    : "这条模型只有这几档：" + lv + "（或者 null = 不发）");
        }
        this.thinking = level;
    }

    private void clampThinking() {
        String lv = thinking;
        if (lv != null && !thinkingLevels().contains(lv)) thinking = null;
    }

    public Integer maxTokens() { return maxTokens; }

    public void setMaxTokens(Integer n) {
        if (n != null && n <= 0) throw new IllegalArgumentException("max-tokens 要是正整数或 null");
        this.maxTokens = n;
    }

    /**
     * 这次请求的 max_tokens：显式配的 → 按模型推（输出上限封顶 32000、再不超窗口一半）；思考档位是 token 预算时
     * 至少 预算 + 正文余量（预算是从 max_tokens 里切出来的）。null = 交给 provider 的默认。
     */
    public Integer effectiveMaxTokens() {
        String lv = thinking;
        Long budget = ThinkingLevels.isBudget(lv) ? Long.parseLong(lv) : null;
        Integer configured = maxTokens != null ? maxTokens : derivedMaxTokens();
        if (budget != null) return (int) Math.max(budget + ThinkingLevels.ANSWER_HEADROOM, configured == null ? 0 : configured);
        return configured;
    }

    private Integer derivedMaxTokens() {
        ModelMeta m = meta();
        return m == null ? null : defaultMaxTokens(m.maxOutput(), contextLimit());
    }

    /** 没显式配时的默认 max_tokens：min(输出上限, 32000)，再不超窗口一半。 */
    public static Integer defaultMaxTokens(Long out, Long ctx) {
        if (out == null || out <= 0) return null;
        long n = Math.min(out, 32000);
        if (ctx != null && ctx > 0) n = Math.min(n, ctx / 2);
        return n > 0 ? (int) n : null;
    }

    public String system() { return system; }

    /** 换系统提示（切项目要换掉里面那句"当前目录 X"）。下一轮生效。 */
    public void setSystem(String s) { this.system = s; }

    /** 工具表（下一轮生效；MCP 目录变了宿主推新表进来）。 */
    public List<AgentTool> tools() { return tools; }

    public void setTools(List<AgentTool> ts) { this.tools = List.copyOf(Objects.requireNonNull(ts, "工具表要是一张表")); }

    /** 这一轮只让模型看见哪几件（切 agent 要换工具集）；null = 全给。 */
    public List<String> activeTools() { return activeTools; }

    public void setActiveTools(List<String> names) { this.activeTools = names == null ? null : List.copyOf(names); }

    public String agent() { return agent; }

    public void setAgent(String who) { this.agent = who; }

    public void setDirect(Function<String, DirectCall> f) { this.direct = f; }

    /** 第一轮等一下还在连的工具（宿主装 MCP 的 {@code connecting()}）。 */
    public void setToolsSettling(BooleanSupplier f) { this.toolsSettling = f; }

    /** worktree-enter 生效之后的回调（宿主据此重算系统提示 / 工具表）。 */
    public void setOnRootChanged(Consumer<Path> f) { this.onRootChanged = f; }

    /** 宿主额外挂给工具的服务（{@code ToolEnv.service} 取得到）。 */
    public <T> void putService(Class<T> type, T impl) { extraServices.put(type, impl); }

    public void addListener(SessionListener l) { listeners.add(l); }

    public void removeListener(SessionListener l) { listeners.remove(l); }

    private ModelMeta meta() {
        String m = model;
        try { return m == null ? null : backend.meta(m); } catch (RuntimeException e) { return null; }
    }

    // ══ 发消息 ═══════════════════════════════════════════════════════════

    /**
     * 发一句话：空闲就在一条虚拟线程上起一轮（立刻返回 SENT）；忙着就排队（QUEUED，中途在下一次问模型前并进去）；
     * 忙着又是空串 → IGNORED。
     */
    public SendResult send(String text) {
        String txt = text == null ? "" : text;
        boolean first = history().isEmpty();
        Turn t = beginTurn(txt, List.of(), null, true);
        if (t == null) {
            if (txt.isEmpty()) return SendResult.IGNORED;
            emit(new SessionEvent.Queued(txt));
            return SendResult.QUEUED;
        }
        Thread th = Thread.ofVirtual().name("agent-" + id).unstarted(() -> {
            settleTools(t, first);
            runBody(t, false);
        });
        synchronized (lock) { worker = th; }
        th.start();
        return SendResult.SENT;
    }

    /**
     * 在**调用线程**上同步跑一轮（子 agent 的跑法）。payload 是 String，或块表（第一块是文本时当那句话）。
     * 取消 / 出错落完状态后原样上抛（取消抛 {@link CancelToken.CancelledException}）。
     */
    public AgentResult runTurn(Object payload) { return runTurn(payload, null); }

    /** 同上；{@code parentCancel} 翻了这一轮也停（子 agent 跟着父会话的按停走）。 */
    public AgentResult runTurn(Object payload, CancelToken parentCancel) {
        String text;
        List<Block> blocks;
        if (payload instanceof String s) { text = s; blocks = List.of(); }
        else if (payload instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof Block.Text t0) {
            text = t0.text();
            blocks = castBlocks(l.subList(1, l.size()));
        } else if (payload instanceof List<?> l) { text = ""; blocks = castBlocks(l); }
        else { text = String.valueOf(payload); blocks = List.of(); }
        Turn t = beginTurn(text, blocks, parentCancel, false);
        if (t == null) throw new IllegalStateException("这段会话正在跑，不能同时再跑一轮");
        synchronized (lock) { worker = Thread.currentThread(); }
        return runBody(t, true);
    }

    @SuppressWarnings("unchecked")
    private static List<Block> castBlocks(List<?> l) { return (List<Block>) l; }

    /**
     * 等到不忙、而且每一轮都收完了尾（事件发完、被按停的旧一轮把历史落完、排队消息引出的后续几轮也跑完）。超时回 false。
     */
    public boolean awaitIdle(Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (lock) {
            while (status.busy() || inFlight > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, left);
            }
            return true;
        }
    }

    /** 忙着时：{@code queueIfBusy} 就排队（空串不排）并回 null；否则直接回 null。 */
    private Turn beginTurn(String text, List<Block> payloadBlocks, CancelToken parentCancel, boolean queueIfBusy) {
        DirectCall dc = directFor(text);
        String scan = dc != null ? AtMentions.stripLeading(text) : text;
        // @ 提及：在锁外读盘
        List<String> mentions = new ArrayList<>();
        for (String p : AtMentions.paths(scan)) {
            if (p.contains("/") || p.contains(".") || java.nio.file.Files.exists(workspace.root().resolve(p))) mentions.add(p);
        }
        int[] overflow = mentions.size() > AtMentions.MAX ? new int[]{mentions.size() - AtMentions.MAX, AtMentions.MAX} : null;
        List<Block> atText = new ArrayList<>(), atImages = new ArrayList<>();
        Map<String, Boolean> files = new LinkedHashMap<>();
        List<String> imageNames = new ArrayList<>();
        List<Prompts.AttachFail> fails = new ArrayList<>();
        for (String p : mentions.subList(0, Math.min(mentions.size(), AtMentions.MAX))) {
            Attach.Result r = Attach.attachFile(workspace, p);
            switch (r.kind()) {
                case TEXT, DIRECTORY -> { atText.add(r.block()); files.put(p, r.kind() == Attach.Kind.DIRECTORY); }
                case IMAGE -> { atImages.add(r.block()); imageNames.add(p); }
                case FAILED -> fails.add(new Prompts.AttachFail(p, r.reason()));
            }
        }
        Turn t;
        synchronized (lock) {
            if (status.busy()) {
                if (queueIfBusy && !text.isEmpty()) queue.add(text);
                return null;
            }
            int myGen = ++gen;
            inFlight++;
            List<Map.Entry<String, Block>> atts = new ArrayList<>(attachments);
            attachments.clear();
            List<String> allImages = new ArrayList<>(imageNames);
            for (var a : atts) allImages.add(a.getKey());
            String line = Prompts.attachedLine(files, allImages, fails, overflow);
            String shown = line.isEmpty() ? text : text + line;
            List<Entry> es = new ArrayList<>(entries);
            es.add(Entry.user(shown));
            entries = List.copyOf(es);
            turnBase = humanCount(history);
            resetStreaming();
            error = null;
            thinkStart = null;
            thinkMs = null;
            thinkLog.clear();
            turnLog.clear();
            pendingApproval = null;
            resultStatus = null;
            turnStart = System.currentTimeMillis();
            List<Message> msgs = new ArrayList<>(history);
            for (String n : notes) msgs.add(Message.user(n));
            notes.clear();
            wakePending = false;
            Message um;
            if (shown.equals(text) && atText.isEmpty() && atImages.isEmpty() && atts.isEmpty() && payloadBlocks.isEmpty()) {
                um = Message.user(text);
            } else {
                List<Block> bs = new ArrayList<>();
                bs.add(new Block.Text(shown));
                bs.addAll(atText);
                bs.addAll(atImages);
                for (var a : atts) bs.add(a.getValue());
                bs.addAll(payloadBlocks);
                um = Message.user(bs);
            }
            msgs.add(um);
            CancelToken c = parentCancel != null ? parentCancel.child() : new CancelToken();
            t = new Turn(myGen, c, List.copyOf(msgs), text, turnTools());
            turn = t;
            setStatus(SessionStatus.THINKING);
        }
        installServices(t);
        if (config.staging()) Staging.begin(workspace);
        emit(new SessionEvent.Started(text));
        return t;
    }

    private DirectCall directFor(String text) {
        Function<String, DirectCall> f = direct;
        if (f == null || text == null) return null;
        try {
            DirectCall d = f.apply(text);
            return d != null && d.tool() != null ? d : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<AgentTool> turnTools() {
        List<AgentTool> all = tools;
        List<String> names = activeTools;
        if (names == null) return all;
        List<AgentTool> out = new ArrayList<>();
        for (AgentTool t : all) if (names.contains(t.name())) out.add(t);
        return out;
    }

    private void installServices(Turn t) {
        t.services.putAll(extraServices);
        t.services.put(AgentSession.class, this);
        t.services.put(ShellSession.class, shell);
        t.services.put(ClarifyService.class, (ClarifyService) this::askQuestion);
        t.services.put(WorktreeEnter.class, (WorktreeEnter) this::requestEnter);
        t.services.put(ToolEnv.Attacher.class, (ToolEnv.Attacher) (name, block) -> stashImage(name, block, "tool"));
        boolean docs = backend.anthropic(model);
        t.services.put(FileTools.DocumentSupport.class, (FileTools.DocumentSupport) () -> docs);
    }

    private void settleTools(Turn t, boolean first) {
        BooleanSupplier f = toolsSettling;
        if (!first || f == null || !safe(f)) return;
        long waited = 0;
        while (waited < config.toolsSettleMs() && safe(f) && current(t)) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            waited += 50;
        }
        t.tools = turnTools();
    }

    private static boolean safe(BooleanSupplier f) {
        try { return f.getAsBoolean(); } catch (RuntimeException e) { return false; }
    }

    private boolean current(Turn t) { synchronized (lock) { return gen == t.gen; } }

    /** 用量还能不能记到这段会话上：当前那一轮，或者刚被按停、还没起新一轮的那一轮（那一趟对面已经扣过钱了）。 */
    private boolean usageWritable(Turn t) {
        synchronized (lock) { return gen == t.gen || (gen == t.gen + 1 && !status.busy()); }
    }

    // ══ 跑一轮 ═══════════════════════════════════════════════════════════

    private AgentResult runBody(Turn t, boolean rethrow) {
        try {
            return runBody0(t, rethrow);
        } finally {
            synchronized (lock) {
                inFlight--;
                lock.notifyAll();
            }
        }
    }

    private AgentResult runBody0(Turn t, boolean rethrow) {
        try {
            List<Message> msgs = directDispatch(t);
            AgentResult r = callWithCompaction(t, msgs);
            if (!current(t)) {
                landStopped(t, r);
                return r;
            }
            synchronized (lock) {
                resultStatus = r.status();
                flushSegment();
                land(r.messages());
                subCurrent = null;
            }
            review(t);
            setStatusAndNotify(SessionStatus.DONE);
            emit(new SessionEvent.Finished(r.status(), r));
            sayIfIncomplete(r);
            applyPendingEnter();
            maybeCompact(t);
            flushQueue();
            return r;
        } catch (CancelToken.CancelledException | AgentPartialException e) {
            boolean cancelled = e instanceof CancelToken.CancelledException
                    || ((AgentPartialException) e).cancelled() || t.cancel.isCancelled();
            List<Message> partial = e instanceof AgentPartialException ap ? ap.messages() : null;
            if (cancelled) {
                onCancelled(t, partial);
                if (rethrow) throw e instanceof CancelToken.CancelledException ce ? ce : new CancelToken.CancelledException();
                return null;
            }
            onFailed(t, e.getCause() != null ? e.getCause() : e, partial);
            if (rethrow) throw e;
            return null;
        } catch (RuntimeException e) {
            onFailed(t, e, null);
            if (rethrow) throw e;
            return null;
        }
    }

    private void onCancelled(Turn t, List<Message> partial) {
        boolean cur;
        synchronized (lock) { cur = gen == t.gen; }
        if (cur) {
            // 不是 interrupt() 翻的（父会话的令牌连坐）：按停那几件事在这里做
            synchronized (lock) {
                resultStatus = AgentStatus.STOPPED;
                sealTurn("⏹ 这一轮被停下了", false);
                setStatus(SessionStatus.IDLE);
            }
            requeueToDraft();
            emit(new SessionEvent.Stopped());
        }
        synchronized (lock) {
            if (partial != null && (gen == t.gen || (gen == t.gen + 1 && !status.busy()))) {
                history = forget(repairTail(partial));
                failLogLanded();
                reproject();
            }
        }
        applyPendingEnter();
        if (config.staging()) reviewAfterAbort();
    }

    private void onFailed(Turn t, Throwable e, List<Message> partial) {
        synchronized (lock) {
            if (gen != t.gen) return;
            history = forget(repairTail(partial != null ? partial : t.messages));
            sealTurn(Retry.errorLine(e), true);
            error = e;
            setStatus(SessionStatus.ERROR);
        }
        requeueToDraft();
        emit(new SessionEvent.Error(e, Retry.errorLine(e)));
        applyPendingEnter();
        if (config.staging()) reviewAfterAbort();
    }

    private void landStopped(Turn t, AgentResult r) {
        synchronized (lock) {
            if (gen == t.gen + 1 && !status.busy()) {
                history = forget(r.messages());
                failLogLanded();
                reproject();
            }
        }
    }

    /** 出错那一半的轮丢掉：末尾一条带 tool_use 而没有结果的 assistant（半截历史送回模型那边是 400）。 */
    static List<Message> repairTail(List<Message> ms) {
        if (ms.isEmpty()) return ms;
        Message last = ms.getLast();
        if (last.isAssistant() && !last.toolUses().isEmpty()) return List.copyOf(ms.subList(0, ms.size() - 1));
        return ms;
    }

    private List<Message> directDispatch(Turn t) {
        DirectCall d = directFor(t.text);
        if (d == null) return t.messages;
        AgentTool tool = null;
        for (AgentTool x : t.tools) if (x.name().equals(d.tool())) tool = x;
        if (tool == null) return t.messages;
        String callId = "direct-" + t.gen + "-" + System.currentTimeMillis();
        ObjectNode args = d.args() == null ? AgentTool.JSON.createObjectNode() : d.args();
        Block.ToolUse tu = new Block.ToolUse(callId, tool.name(), args);
        noteToolCall(t, tu);
        ToolOutput out = tool.execute(t.env(callId), args);
        noteToolResult(t, tu, out.content(), out.error(), out.error() ? "error" : "ok");
        List<Message> ms = new ArrayList<>(t.messages);
        ms.add(new Message(io.ailink.agentforge.agent.model.Role.ASSISTANT, List.of(tu)));
        ms.add(Message.user(List.of(new Block.ToolResult(callId, out.content(), out.error()))));
        ms.add(Message.user(Prompts.DIRECT_DISPATCH_PROMPT));
        return ms;
    }

    /** 上下文满了（或服务端说太长了）→ 压一次接着跑，最多 {@link Compaction#RESUMES} 次。 */
    private AgentResult callWithCompaction(Turn t, List<Message> initial) {
        List<Message> msgs = initial;
        int left = Compaction.RESUMES;
        while (true) {
            AgentResult r;
            try {
                r = runner(t).run(msgs);
            } catch (AgentPartialException e) {
                if (!e.cancelled() && e.getCause() instanceof ModelCallException mc && mc.contextOverflow()
                        && left > 0 && current(t)) {
                    synchronized (lock) { history = forget(repairTail(e.messages())); }
                    if (compact()) {
                        left--;
                        msgs = history();
                        continue;
                    }
                }
                throw e;
            }
            if (r.status() == AgentStatus.STOP_WHEN && "context-full".equals(r.stopReason()) && left > 0 && current(t)) {
                synchronized (lock) { history = forget(r.messages()); }
                if (compact()) {
                    left--;
                    msgs = history();
                    continue;
                }
            }
            return r;
        }
    }

    private ModelCaller caller(Turn t) {
        ModelCaller base = backend.caller(model, thinking);
        return Retry.wrap(base, config.retryMax(), config.retryInitialDelayMs(), config.retryCapMs(),
                (n, max, ms, e) -> {
                    if (t == null || current(t)) emit(new SessionEvent.Retry(n, max, ms, Retry.errorLine(e) + "（等 " + ms + " ms）"));
                });
    }

    private RequestOptions requestOptions(boolean withThinking) {
        PromptCache pc = config.promptCache();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("promptCache", pc);
        if (model != null) extra.put("modelRef", model);
        return new RequestOptions(backend.wireModel(model), effectiveMaxTokens(), withThinking ? thinking : null,
                pc != PromptCache.OFF, extra);
    }

    private AgentRunner runner(Turn t) {
        Integer mi = config.maxIterations();
        String finalNudge = parent == null ? Prompts.AGENT_FINAL_NUDGE_PROMPT : Prompts.FINAL_NUDGE_PROMPT;
        ReactLoop loop = new ReactLoop(t.tools, t.cancel, mi == null ? Integer.MAX_VALUE : mi,
                List.of(contextFullStop()), finalNudge, Nudge.ROUND_NUDGE, true);
        List<Filter> filters = List.of(steerFilter(t), tapFilter(t), argsFilter(), Filters.gate(gateFor(t)), aftermathFilter(t),
                Filters.chatAfter((req, turn) -> onAssistant(t, turn)), prepareFilter(t));
        DeltaListener dl = (kind, text) -> {
            t.cancel.throwIfCancelled();   // chunk 粒度：模型正在说话时按停唯一还管用的那一手
            if (current(t)) {
                noteDelta(kind, text);
                emit(new SessionEvent.Delta(kind, text));
            }
        };
        return new AgentRunner(caller(t), system, requestOptions(true), filters, loop, t::env, dl);
    }

    private ReactLoop.StopWhen contextFullStop() {
        return (turn, h) -> shouldCompact(h) ? "context-full" : null;
    }

    // ── 过滤器 ───────────────────────────────────────────────────────────

    /** 排队的话：工具跑完把它推进下一轮开头；真进了请求再确认（从队里拿掉、进记录区、发 Steered）。 */
    private Filter steerFilter(Turn t) {
        return new Filter() {
            private Message pending;
            private int pendingCount;

            @Override public String name() { return "steer"; }

            @Override public ModelTurn chat(ChatRequest req, ChatChain chain) {
                String steered = null;
                synchronized (this) {
                    if (pending != null && current(t) && containsSame(req.messages(), pending)) {
                        steered = pending.text();
                        synchronized (lock) {
                            for (int i = 0; i < pendingCount && !queue.isEmpty(); i++) queue.removeFirst();
                            List<Entry> es = new ArrayList<>(entries);
                            es.add(Entry.user(steered));
                            entries = List.copyOf(es);
                        }
                        pending = null;
                    }
                }
                if (steered != null) emit(new SessionEvent.Steered(steered));
                return chain.next(req);
            }

            @Override public ToolResult tool(ToolRequest req, ToolChain chain) {
                ToolResult r = chain.next(req);
                synchronized (this) {
                    if (pending == null && current(t)) {
                        List<String> q = queue();
                        if (!q.isEmpty()) {
                            pending = Message.user(String.join("\n", q));
                            pendingCount = q.size();
                            req.ctx().pushMessage(pending);
                        }
                    }
                }
                return r;
            }
        };
    }

    private static boolean containsSame(List<Message> ms, Message m) {
        for (Message x : ms) if (x == m) return true;
        return false;
    }

    /** 工具调用 / 结果进时间线并发事件。⚠️ 放在审批闸**外面**：先画出"它想干什么"，闸再弹；被拒的结果也进记录。 */
    private Filter tapFilter(Turn t) {
        return Filters.toolAround("tap", (req, chain) -> {
            noteToolCall(t, req.toolUse());
            ToolResult r = chain.next(req);
            noteToolResult(t, req.toolUse(), r.output().content(), r.error(), r.kind());
            return r;
        });
    }

    private void noteToolCall(Turn t, Block.ToolUse tu) {
        if (!current(t)) return;
        // 改文件的调用：趁还没动手把改动 diff 记下（before 只有现在拿得到）
        String cd = CHANGE_TOOLS.contains(tu.name()) && tu.input() != null ? Preview.changeDiff(workspace, tu.name(), tu.input()) : null;
        synchronized (lock) {
            if (cd != null) {
                changeDiffs.put(tu.id(), cd);
                while (changeDiffs.size() > CHANGE_DIFFS_MAX) changeDiffs.remove(changeDiffs.firstEntry().getKey());
            }
            lastCallId = tu.id();
            flushSegment();
            turnLog.add(Transcript.toolCall(tu.name(), tu.id(), tu.input(), null));
        }
        emit(new SessionEvent.ToolCall(tu.id(), tu.name(), tu.input()));
    }

    private void noteToolResult(Turn t, Block.ToolUse tu, List<Block> content, boolean err, String kind) {
        if (!current(t)) return;
        synchronized (lock) {
            subCurrent = null;
            Block.ToolResult res = new Block.ToolResult(tu.id(), content, err);
            for (int i = 0; i < turnLog.size(); i++) {
                Entry e = turnLog.get(i);
                if (e.isCall() && tu.id().equals(e.toolUseId())) turnLog.set(i, Transcript.toolCall(tu.name(), tu.id(), tu.input(), res));
            }
            turnLog.add(Transcript.toolResult(content, err, tu.id()));
        }
        emit(new SessionEvent.ToolResult(tu.id(), tu.name(), content, err, kind));
    }

    private static Filter argsFilter() {
        return Filters.toolAround("args-check", (req, chain) -> {
            if (req.tool() != null) {
                String why;
                try { why = ArgsChecker.check(req.tool(), req.toolUse().input()); } catch (RuntimeException e) { why = null; }
                if (why != null) return ToolResult.error(why, "bad-args");
            }
            return chain.next(req);
        });
    }

    private void onAssistant(Turn t, ModelTurn turn) {
        Usage u = turn.usage() == null ? Usage.ZERO : turn.usage();
        if (usageWritable(t)) addUsage0(u, model);
        boolean cur = current(t);
        boolean fireCache = false;
        if (cur) {
            synchronized (lock) {
                lastUsage = u;
                if (u.totalInput() > 0) lastInput = u.totalInput();
                if (u.output() > 0) lastOutput = u.output();
                fireCache = cacheWatch(u);
                if (status == SessionStatus.STREAMING) setStatus(SessionStatus.THINKING);
            }
        }
        if (fireCache) emit(new SessionEvent.CacheInactive(3));
        if (cur) emit(new SessionEvent.Assistant(turn.message().text(), turn.message().toolUses().size()));
        emitUsage(u);
    }

    /** 连着三次大请求（≥ 4096 token）一点缓存都没命中 → 报一次（之后不再报）。只对 Anthropic 形有意义。 */
    private boolean cacheWatch(Usage u) {
        if (config.promptCache() == PromptCache.OFF || cacheSettled || !backend.anthropic(model)) return false;
        long hit = u.cacheRead() + u.cacheWrite();
        if (hit > 0) { cacheSettled = true; return false; }
        if (u.input() + hit < 4096) return false;
        cacheMisses++;
        if (cacheMisses >= 3) { cacheSettled = true; return true; }
        return false;
    }

    /** 发请求前：按压缩指针重建 → prune 老工具结果 → 超预算再裁。 */
    private Filter prepareFilter(Turn t) {
        Long budget = inputBudget();
        TokenBudgetCompressor comp = budget == null ? null : new TokenBudgetCompressor(budget, 1, (dropped, total) -> {
            if (current(t)) emit(new SessionEvent.Trimmed(dropped, total));
        });
        return Filters.chatRewrite("context-prepare", req -> {
            List<Message> h;
            Usage lu;
            synchronized (lock) { h = Compaction.apply(req.messages(), compactions); lu = lastUsage; }
            h = Compaction.prune(h, imageDropper());
            if (comp != null) h = comp.apply(h, lu);
            return req.withMessages(h);
        });
    }

    private java.util.function.BiFunction<Integer, List<Message>, List<Message>> imageDropper() {
        return config.imageRetention() == SessionConfig.ImageRetention.PRUNE ? Images::dropThrough : null;
    }

    // ── 审批 ─────────────────────────────────────────────────────────────

    private Filters.Gate gateFor(Turn t) {
        return (call, tool, req) -> {
            if (!current(t)) return GateDecision.STOP;                          // 陈旧的一轮：让循环退出
            return ToolApprovals.decide(hostFor(t), permissions, workspace, config, call, tool);
        };
    }

    /** 工具内部审批的余波：用户在 run-command 的审批上拒绝时附上他的话，停下时结束这一轮。 */
    private Filter aftermathFilter(Turn t) {
        return Filters.toolAround("approval-aftermath", (req, chain) -> {
            io.ailink.agentforge.agent.loop.ToolResult r = chain.next(req);
            ReviewDecision d = req.toolUse() == null ? null : t.outcomes.remove(req.toolUse().id());
            if (d == null) return r;
            String text = r.output().text();
            if (d.kind() == ReviewDecision.Kind.ABORT)
                return io.ailink.agentforge.agent.loop.ToolResult.error(text + "\n用户停下了这一轮，等他的下一句话。", "stopped");
            if (d.feedback() != null)
                return io.ailink.agentforge.agent.loop.ToolResult.error(text + "\n用户拒绝了这条命令，并说：" + d.feedback(), "denied");
            return r;
        });
    }

    private ExecApprovalHost hostFor(Turn t) {
        return new ExecApprovalHost() {
            @Override public ReviewDecision ask(ApprovalRequest r) { return parkApproval(r.withSession(id), t == null ? currentGen() : t.gen); }

            @Override public void auto(String toolName, String summary, boolean allowed, String why) {
                emit(new SessionEvent.AutoApproval(toolName, summary, allowed, why));
            }

            @Override public void amended(String description) { emit(new SessionEvent.PolicyAmended(description)); }

            @Override public String sessionId() { return id; }

            @Override public void toolOutcome(String toolUseId, ReviewDecision d) {
                if (toolUseId != null && t != null) t.outcomes.put(toolUseId, d);
            }
        };
    }

    /**
     * 工具线程（执行器）用的审批入口：问到这段会话当前那一轮上（子 agent 的摆到父会话）。不在一轮里 ⇒ 每次都回 ABORT。
     */
    public ExecApprovalHost approvalHost() {
        return new ExecApprovalHost() {
            private Turn turnNow() { synchronized (lock) { return turn; } }

            @Override public ReviewDecision ask(ApprovalRequest r) { return requestApproval(r); }

            @Override public void auto(String toolName, String summary, boolean allowed, String why) {
                emit(new SessionEvent.AutoApproval(toolName, summary, allowed, why));
            }

            @Override public void amended(String description) { emit(new SessionEvent.PolicyAmended(description)); }

            @Override public String sessionId() { return id; }

            @Override public void toolOutcome(String toolUseId, ReviewDecision d) {
                Turn t = turnNow();
                if (toolUseId != null && t != null) t.outcomes.put(toolUseId, d);
            }
        };
    }

    /**
     * 工具线程上摆一道审批（工具自己发起的，例如命令要审批、沙盒拦下后问"出沙盒重跑吗"），阻塞到用户答 / 按停。
     * 子 agent 的摆到父会话上；没有一轮在跑、或这一轮被按停 ⇒ ABORT。
     */
    public ReviewDecision requestApproval(ApprovalRequest req) {
        Objects.requireNonNull(req, "req");
        if (!busy()) return ReviewDecision.abort();                 // 不在一轮里：没有人会来答
        return parkApproval(req.session() == null ? req.withSession(id) : req, currentGen());
    }

    /**
     * 摆一道审批并挂起调用线程，等答案。子 agent 的摆到父会话上。⚠️ 同一时刻只摆一道（审批与提问共用一个槽）：
     * 并发的几件要问的调用在这里排队，而不是互相盖掉。答案不在请求的选项里 ⇒ 按「不做」处理。
     */
    private ReviewDecision parkApproval(ApprovalRequest req, int myGen) {
        if (approvalViaParent) return parent.parkApproval(req, parent.currentGen());
        if (!acquireAsk(myGen)) return ReviewDecision.abort();
        CompletableFuture<ReviewDecision> f = new CompletableFuture<>();
        try {
            synchronized (lock) {
                if (gen != myGen) return ReviewDecision.abort();
                pendingApproval = req;
                approvalFuture = f;
                setStatus(SessionStatus.AWAITING_APPROVAL);
            }
            emit(new SessionEvent.ApprovalRequested(req));
            ReviewDecision a;
            try {
                a = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                a = ReviewDecision.abort();
            } catch (ExecutionException e) {
                a = ReviewDecision.abort();
            }
            if (a.kind() != ReviewDecision.Kind.ABORT && !req.allows(a.kind())) a = new ReviewDecision(ReviewDecision.Kind.DENIED, a.feedback());
            emit(new SessionEvent.ApprovalResolved(req, a));
            return a;
        } finally {
            synchronized (lock) {
                if (approvalFuture == f) { pendingApproval = null; approvalFuture = null; }
                if (status == SessionStatus.AWAITING_APPROVAL) setStatus(SessionStatus.THINKING);
            }
            askSlot.release();
        }
    }

    private boolean acquireAsk(int myGen) {
        try {
            while (!askSlot.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                if (currentGen() != myGen) return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    int currentGen() { synchronized (lock) { return gen; } }

    /** 回答摆着的那道审批；没有审批挂着时静默（回 false）。 */
    public boolean resolveApproval(ReviewDecision answer) {
        CompletableFuture<ReviewDecision> f;
        synchronized (lock) {
            f = approvalFuture;
            if (f == null) return false;
            setStatus(SessionStatus.THINKING);
        }
        return f.complete(Objects.requireNonNull(answer));
    }

    // ── 提问（clarify）──────────────────────────────────────────────────

    /** clarify 工具的 {@link ClarifyService}：摆出题目、挂起工具线程直到用户答 / 关掉 / 按停。 */
    public ClarifyService.Answer askQuestion(ClarifyService.Request req) {
        int myGen = currentGen();
        if (!acquireAsk(myGen)) return new ClarifyService.Stopped();
        CompletableFuture<ClarifyService.Answer> f = new CompletableFuture<>();
        try {
            synchronized (lock) {
                if (gen != myGen) return new ClarifyService.Stopped();
                pendingQuestion = req;
                questionFuture = f;
                setStatus(SessionStatus.AWAITING_QUESTION);
            }
            emit(new SessionEvent.Question(req));
            ClarifyService.Answer a;
            try {
                a = f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                a = new ClarifyService.Stopped();
            } catch (ExecutionException e) {
                a = new ClarifyService.Stopped();
            }
            emit(new SessionEvent.QuestionAnswered(a));
            return a;
        } finally {
            synchronized (lock) {
                if (questionFuture == f) { pendingQuestion = null; questionFuture = null; }
                if (status == SessionStatus.AWAITING_QUESTION) setStatus(SessionStatus.THINKING);
            }
            askSlot.release();
        }
    }

    /** 回答摆着的那道题；没有题挂着时静默（回 false）。 */
    public boolean answerQuestion(ClarifyService.Answer answer) {
        CompletableFuture<ClarifyService.Answer> f;
        synchronized (lock) { f = questionFuture; }
        return f != null && f.complete(Objects.requireNonNull(answer));
    }

    // ── 批量审阅（staging）──────────────────────────────────────────────

    private void review(Turn t) {
        if (!config.staging()) return;
        Staging st = Staging.current(workspace);
        if (st == null) return;
        try {
            if (st.isEmpty()) return;
            List<Staging.Change> pend = st.pending();
            List<String> paths = new ArrayList<>();
            for (Staging.Change c : pend) paths.add(c.path());
            ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.REVIEW, id, null, "apply-changes",
                    "整轮攒下的改动（" + pend.size() + " 个文件）", null, workspace.root().toString(),
                    new Preview.Result(Preview.Kind.DIFF, stagedDiff(pend)), String.join("、", paths), null, null, null, null, null);
            ReviewDecision a = parkApproval(req, t == null ? currentGen() : t.gen);
            applyReview(st, a, pend.size());
        } finally {
            Staging.end(workspace);
        }
    }

    private void applyReview(Staging st, ReviewDecision a, int n) {
        if (a.isApproved()) st.commit(null);
        else {
            st.discard(null);
            // 拒绝发生在 agent 已经停下之后：不告诉它的话，它下一轮的推理建立在一个被撤销的前提上。排队，不当场发一轮
            queueNote("（系统）用户没有采用上一轮攒下的 " + n + " 处文件改动，它们**没有**写进盘。别假定那些改动已经生效。"
                    + (a.feedback() == null ? "" : "用户说：" + a.feedback()));
        }
        emit(new SessionEvent.Review(a, n));
    }

    private static String stagedDiff(List<Staging.Change> pend) {
        StringBuilder sb = new StringBuilder();
        for (Staging.Change c : pend) {
            sb.append(Diff.unified(c.before(), c.after(), c.existed() ? c.path() : "/dev/null", c.path(), 3));
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        }
        return sb.toString();
    }

    /** 出错 / 按停之后，攒下的改动照样摆出来过目（不在工作线程上等）。 */
    private void reviewAfterAbort() {
        Staging st = Staging.current(workspace);
        if (st == null) return;
        if (st.isEmpty()) { Staging.end(workspace); return; }
        Thread.ofVirtual().name("agent-review-" + id).start(() -> {
            SessionStatus back = status();
            review(null);
            synchronized (lock) {
                if (status == SessionStatus.AWAITING_APPROVAL || status == SessionStatus.THINKING) setStatus(back);
            }
        });
    }

    // ══ 按停 / 重置 ══════════════════════════════════════════════════════

    /**
     * 按停：翻世代号与令牌（旧那一轮的回调从此不写状态），摆着的闸 / 题按"停"答掉，子会话一起停；忙着的话那一轮封存为
     * "被停下了"，排着的话还回草稿。⚠️ 协作式：一次已经在飞的、没有检查点的调用会跑完才停得下来（结果照样落进记忆）。
     */
    public void interrupt() {
        CompletableFuture<ReviewDecision> gf;
        CompletableFuture<ClarifyService.Answer> qf;
        Turn t;
        List<AgentSession> kids;
        boolean wasBusy;
        synchronized (lock) {
            gen++;
            gf = approvalFuture;
            qf = questionFuture;
            pendingApproval = null;
            pendingQuestion = null;
            t = turn;
            kids = new ArrayList<>(children.values());
            wasBusy = status.busy();
            if (wasBusy) {
                resultStatus = AgentStatus.STOPPED;
                sealTurn("⏹ 这一轮被停下了", false);
                setStatus(SessionStatus.IDLE);
            }
        }
        if (gf != null) gf.complete(ReviewDecision.abort());
        if (qf != null) qf.complete(new ClarifyService.Stopped());
        if (t != null) t.cancel.cancel();
        for (AgentSession c : kids) if (c.busy()) {
            try { c.interrupt(); } catch (RuntimeException ignored) { }
        }
        if (wasBusy) {
            requeueToDraft();
            emit(new SessionEvent.Stopped());
        }
    }

    /** 出错确认：ERROR → IDLE（记录区里那条错留着）。 */
    public void clearError() {
        synchronized (lock) {
            if (status == SessionStatus.ERROR) { error = null; setStatus(SessionStatus.IDLE); }
        }
    }

    /** 开新对话：按停、清历史 / 记录 / 用量 / 压缩指针 / 子 agent 记录。⚠️ 不碰存盘（换一个对话 id 是宿主的事）。 */
    public void reset() {
        interrupt();
        synchronized (lock) {
            history = List.of();
            entries = List.of();
            compactions = List.of();
            resetStreaming();
            error = null;
            pendingApproval = null;
            usage = Usage.ZERO;
            cost = null;
            costPartial = false;
            lastInput = null;
            lastOutput = null;
            lastUsage = null;
            resultStatus = null;
            subLog.clear();
            subCurrent = null;
            changeDiffs.clear();
            failLog.clear();
            turnLog.clear();
            thinkLog.clear();
            lastCallId = null;
            setStatus(SessionStatus.IDLE);
        }
        emit(new SessionEvent.UsageUpdated(Usage.ZERO, Usage.ZERO, null, false, null));
    }

    // ══ 压缩 ═════════════════════════════════════════════════════════════

    /** 现在该不该压（自动压缩开着、上一次输入 + 输出已到预算、而且切得出一个值得压的头）。 */
    public boolean shouldCompact() { return shouldCompact(history()); }

    private boolean shouldCompact(List<Message> h) {
        if (!config.compaction()) return false;
        Long in, out;
        List<Compaction.Pointer> cs;
        synchronized (lock) { in = lastInput; out = lastOutput; cs = compactions; }
        Long budget = inputBudget();
        if (in == null || budget == null) return false;
        long used = in + (out == null ? 0 : out);
        return used >= budget && Compaction.split(Compaction.apply(h, cs), Compaction.preserveBudget(budget)) != null;
    }

    private void maybeCompact(Turn t) {
        if (current(t) && shouldCompact()) compact();
    }

    /**
     * 压一次：前半段压成一份交接材料，追加"通告 + 摘要"两条并记一条指针；尾巴（最近几轮）原样留着。
     * 先试**接在原对话末尾**那一问（同样的 system + 工具 + 历史 ⇒ 整段前缀命中 prompt cache），不成再摊平重问。
     * 手动调用不受"自动压缩"开关影响。切不出值得压的头 / 摘要拿不到 → false，历史不动。
     */
    public boolean compact() {
        Compaction.Indexed em;
        Compaction.Split split;
        SessionStatus back;
        synchronized (lock) {
            em = Compaction.applyIndexed(history, compactions);
            split = Compaction.split(em.messages(), Compaction.preserveBudget(inputBudget()));
            if (split == null) return false;
            back = status;
            setStatus(SessionStatus.COMPACTING);
        }
        emit(new SessionEvent.Compacting());
        try {
            int tailIdx = em.index().get(split.head().size());
            CancelToken cancel;
            synchronized (lock) { cancel = turn != null && status.busy() ? turn.cancel : new CancelToken(); }
            String summary = null;
            try { summary = summaryInPlace(em.messages(), split.tail(), cancel); } catch (CancelToken.CancelledException e) { throw e; }
            catch (RuntimeException ignored) { }
            if (summary == null) summary = summarySerialized(split.head(), cancel);
            if (summary == null || summary.isBlank()) return false;
            String bf = null;
            try {
                bf = Backfill.compactionBackfill(split.head(), split.tail(),
                        new ToolEnv(workspace, cancel, "compaction", id, Map.of()));
            } catch (RuntimeException ignored) { }
            List<Block> nb = new ArrayList<>();
            nb.add(new Block.Text("（上下文快满了，系统自动压缩了上面的对话）"));
            if (bf != null && !bf.isEmpty()) nb.add(Reminder.block(bf));
            Message notice = Message.user(nb).withMeta("compaction", "notice");
            Message sum = Message.assistant(Prompts.COMPACTION_HEADING + summary).withMeta("compaction", "summary");
            synchronized (lock) {
                int at = history.size();
                List<Message> h = new ArrayList<>(history);
                h.add(notice);
                h.add(sum);
                history = List.copyOf(h);
                List<Compaction.Pointer> cs = new ArrayList<>(compactions);
                cs.add(new Compaction.Pointer(at, tailIdx));
                compactions = List.copyOf(cs);
                reproject();
            }
            emit(new SessionEvent.Compacted(split.head().size(), summary.length()));
            return true;
        } catch (CancelToken.CancelledException e) {
            return false;
        } finally {
            synchronized (lock) { if (status == SessionStatus.COMPACTING) setStatus(back); }
        }
    }

    private String summaryInPlace(List<Message> eff, List<Message> tail, CancelToken cancel) {
        List<Message> msgs = new ArrayList<>(Compaction.prune(eff, imageDropper()));
        msgs.add(Message.user(Prompts.compactionInPlacePrompt(Compaction.tailAnchor(tail))));
        List<AgentTool> ts = turnTools();
        ModelTurn r = caller(null).call(new ChatRequest(system, msgs, ts, requestOptions(true), new FilterCtx(ts)),
                DeltaListener.NONE, cancel);
        addUsage0(r.usage(), model);
        if (!r.message().toolUses().isEmpty()) return null;
        String text = r.message().text();
        return text == null || text.isEmpty() ? null : text;
    }

    private String summarySerialized(List<Message> head, CancelToken cancel) {
        List<Message> msgs = List.of(Message.user(Prompts.COMPACTION_PROMPT + "\n\n以下是要压缩的那段对话：\n\n"
                + Compaction.serialize(head)));
        try {
            ModelTurn r = caller(null).call(new ChatRequest(null, msgs, List.of(), requestOptions(false), new FilterCtx(List.of())),
                    DeltaListener.NONE, cancel);
            addUsage0(r.usage(), model);
            return r.message().text();
        } catch (CancelToken.CancelledException e) {
            throw e;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ══ 附件 / 画廊 ══════════════════════════════════════════════════════

    /** 挂一张图（已经读好的块），随下一次 send 带走。回这一次一共挂了几张。 */
    public int attach(String name, Block image) {
        if (!(image instanceof Block.Image) && !(image instanceof Block.Document)) {
            throw new IllegalArgumentException("要一个图片 / 文档块");
        }
        synchronized (lock) {
            attachments.add(Map.entry(name, image));
            return attachments.size();
        }
    }

    public List<String> attachments() {
        synchronized (lock) {
            List<String> out = new ArrayList<>();
            for (var a : attachments) out.add(a.getKey());
            return out;
        }
    }

    public void clearAttachments() { synchronized (lock) { attachments.clear(); } }

    private void stashImage(String name, Block b, String ownerKind) {
        if (!(b instanceof Block.Image img)) return;
        synchronized (lock) {
            for (GalleryImage g : gallery) if (g.data().equals(img.data())) return;
            int gid = GALLERY_IDS.incrementAndGet();
            gallery.addFirst(new GalleryImage(gid, name != null ? name : "图片 " + gid, img.mediaType(), img.data(),
                    Images.bytes(img.data()), ownerKind));
            while (gallery.size() > GALLERY_MAX) gallery.removeLast();
        }
    }

    private void stashImages(List<Message> msgs) {
        int k = 0;
        for (Message m : msgs) {
            boolean human = ContextMath.human(m);
            if (human) k++;
            if (!Images.hasMedia(m)) continue;
            for (Block b : m.blocks()) {
                if (b instanceof Block.Image && human) stashImage(null, b, "user:" + k);
                else if (b instanceof Block.ToolResult r) {
                    for (Block c : r.content()) if (c instanceof Block.Image) stashImage(null, c, "tool:" + r.toolUseId());
                }
            }
        }
    }

    private List<Message> forget(List<Message> msgs) {
        try { stashImages(msgs); } catch (RuntimeException ignored) { }
        return List.copyOf(Images.forget(msgs, config.imageRetention()));
    }

    private void forgetAllImages() {
        synchronized (lock) {
            boolean any = false;
            for (Message m : history) if (Images.hasMedia(m)) { any = true; break; }
            if (any) history = List.copyOf(Images.dropAll(history, false));
        }
    }

    // ══ 排队 / 唤醒 ══════════════════════════════════════════════════════

    /**
     * 把一句话排给**下一轮**的模型（批量审阅被拒、后台结论…）。⚠️ 排队，不是当场发一轮 —— 当场发等于替用户发起一次
     * 他没要求的调用。
     */
    public void queueNote(String text) { synchronized (lock) { notes.add(text); } }

    /** 唤醒型 note（后台子 agent 的结论）：空闲当场起一轮；忙着排着，这一轮落地即自动起一轮。 */
    public SendResult queueWake(String text) {
        synchronized (lock) {
            if (status.busy()) {
                notes.add(text);
                wakePending = true;
                return SendResult.QUEUED;
            }
        }
        return send(text);
    }

    public boolean wakePending() { synchronized (lock) { return wakePending; } }

    private void flushQueue() {
        String next = null;
        synchronized (lock) {
            if (queue.isEmpty() && wakePending) {
                wakePending = false;
                if (!notes.isEmpty()) {
                    next = String.join("\n", notes);
                    notes.clear();
                }
            } else if (!queue.isEmpty()) {
                next = String.join("\n", queue);
                queue.clear();
            }
        }
        if (next != null) send(next);
    }

    private void requeueToDraft() {
        String txt;
        synchronized (lock) {
            if (queue.isEmpty()) return;
            txt = String.join("\n", queue);
            queue.clear();
            draft = draft.isEmpty() ? txt : draft + "\n" + txt;
        }
        emit(new SessionEvent.Requeued(txt));
    }

    // ══ worktree-enter ═══════════════════════════════════════════════════

    /** worktree-enter 的宿主回调：排到这一轮跑完之后再切（这一轮里剩下的工具调用仍在原项目）。 */
    public void requestEnter(Path dir) { synchronized (lock) { pendingEnter = dir; } }

    private void applyPendingEnter() {
        Path p;
        synchronized (lock) { p = pendingEnter; pendingEnter = null; }
        if (p == null) return;
        workspace.setRoot(p);
        Seen.of(workspace).reset();      // 换了项目：旧根下读过的 TAG 一条都不算数
        shell.cwd(p);
        Consumer<Path> f = onRootChanged;
        if (f != null) {
            try { f.accept(p); } catch (RuntimeException ignored) { }
        }
        emit(new SessionEvent.RootChanged(p));
    }

    // ══ 用量 ═════════════════════════════════════════════════════════════

    /**
     * 把别处花的钱也算进这段对话（子 agent 那几趟不在主会话这一轮的用量里）。⚠️ 是**加**不是覆盖。
     * {@code modelRef} 用来查单价；查不到 → 记成"至少"。
     */
    public void addUsage(Usage u, String modelRef) {
        addUsage0(u, modelRef);
        emitUsage(u);
    }

    private void addUsage0(Usage u, String modelRef) {
        if (u == null) return;
        ModelMeta m = null;
        try { m = modelRef == null ? null : backend.meta(modelRef); } catch (RuntimeException ignored) { }
        Double c = m == null || m.facts() == null ? null : Cost.usageCost(u, m.facts());
        synchronized (lock) {
            usage = usage.plus(u);
            if (c != null) cost = (cost == null ? 0 : cost) + c;
            else if (!u.isZero()) costPartial = true;
        }
    }

    private void emitUsage(Usage last) {
        Usage tot;
        Double c;
        boolean part;
        synchronized (lock) { tot = usage; c = cost; part = costPartial; }
        emit(new SessionEvent.UsageUpdated(last, tot, c, part, contextRatio()));
    }

    // ══ 子 agent 记录 / 子会话 ═══════════════════════════════════════════

    public List<SubRun> subLog() { synchronized (lock) { return List.copyOf(subLog); } }

    public SubRun subCurrent() { synchronized (lock) { return subCurrent; } }

    /**
     * 某次改文件的调用（write-file / edit-file / apply-patch）动手前算下的改动 diff（格式同审批预览：每个文件一行标题 + unified diff，
     * 带真行号）；不是改文件的调用、旧对话里的、或者算不出 ⇒ null。
     */
    public String changeDiff(String toolUseId) { synchronized (lock) { return toolUseId == null ? null : changeDiffs.get(toolUseId); } }

    /** 开一趟：认 {@code toolUseId}（null = 最近那次工具调用），登记进 subLog，发 SubStart。 */
    public SubRun subBegin(String desc, String who, String toolUseId) {
        SubRun r;
        synchronized (lock) {
            r = new SubRun(toolUseId != null ? toolUseId : lastCallId, desc);
            if (who != null) r.who(who);
            subLog.add(r);
            subCurrent = r;
        }
        emit(new SessionEvent.SubStart(r));
        return r;
    }

    public void subStep(SubRun run, String toolName, ObjectNode input) {
        SubRun r = run != null ? run : subCurrent();
        if (r == null) r = subBegin("", null, null);
        r.step(new SubStep(toolName, Transcript.argsOneLine(input, Transcript.SUMMARY_LIMIT), false, null));
        emit(new SessionEvent.SubStep(r, toolName));
    }

    public void subEnd(SubRun run, String status, List<Step> steps, String why) {
        SubRun r = run != null ? run : subCurrent();
        if (r == null) return;
        List<SubStep> ss = new ArrayList<>();
        if (steps != null) for (Step s : steps) ss.add(Transcript.subStep(s));
        if (r.finish(status, ss, why)) emit(new SessionEvent.SubFinish(r));
    }

    /** 恢复对话时把 sub-run 记录整批装回去。 */
    public void restoreSubLog(List<SubRun> runs) { synchronized (lock) { subLog.clear(); subLog.addAll(runs); } }

    /** 挂在这段对话底下的子会话（按注册顺序）。 */
    public List<AgentSession> children() { synchronized (lock) { return List.copyOf(children.values()); } }

    public AgentSession child(String childId) { synchronized (lock) { return children.get(childId); } }

    public AgentSession addChild(AgentSession child) {
        synchronized (lock) { children.put(child.id(), child); }
        return child;
    }

    /** 后台还在跑的子会话。 */
    public List<AgentSession> backgroundJobs() {
        List<AgentSession> out = new ArrayList<>();
        for (AgentSession c : children()) if (c.busy()) out.add(c);
        return out;
    }

    /** 最近一条 assistant 正文（子会话的结论）。 */
    public String lastAssistantText() {
        List<Message> h = history();
        for (int i = h.size() - 1; i >= 0; i--) {
            Message m = h.get(i);
            if (m.isAssistant() && !m.text().isEmpty()) return m.text();
        }
        return null;
    }

    // ══ 内部：状态 / 流式 / 记录 ═════════════════════════════════════════

    private void setStatus(SessionStatus st) {
        // lock 已持有
        if (status == st) return;
        status = st;
        lock.notifyAll();
        emit(new SessionEvent.StatusChanged(st));
    }

    private void setStatusAndNotify(SessionStatus st) { synchronized (lock) { setStatus(st); } }

    private void resetStreaming() {
        streaming.setLength(0);
        streamingKind = "text";
        streamingEpoch++;
    }

    private void noteDelta(String kind, String text) {
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if ("thinking".equals(kind)) {
                if (thinkStart == null) thinkStart = now;
            } else if (thinkStart != null) {
                long ms = now - thinkStart;
                thinkMs = ms;
                thinkStart = null;
                if (!streaming.isEmpty()) thinkLog.add(Entry.thinking(streaming.toString(), ms));
            }
            if (!kind.equals(streamingKind)) flushSegment();
            streaming.append(text);
            streamingKind = kind;
            if (status != SessionStatus.AWAITING_APPROVAL && status != SessionStatus.AWAITING_QUESTION) setStatus(SessionStatus.STREAMING);
        }
    }

    /** 把流式缓冲里已经说完的那一段收进时间线并清缓冲（lock 已持有）。 */
    private void flushSegment() {
        if (!streaming.isEmpty()) {
            turnLog.add("thinking".equals(streamingKind) ? Entry.thinking(streaming.toString(), thinkMs)
                    : Entry.assistant(streaming.toString()));
        }
        streaming.setLength(0);
        streamingEpoch++;
    }

    private void land(List<Message> msgs) {
        history = forget(msgs);
        reproject();
        turnDur = turnStart == null ? null : Math.max(0, System.currentTimeMillis() - turnStart);
    }

    /** 按历史重投影记录区（lock 已持有）。 */
    private void reproject() {
        List<Entry> fresh = Transcript.project(history);
        fresh = Transcript.spliceThinking(fresh, thinkLog);
        fresh = Transcript.spliceSubagents(fresh, subLog);
        fresh = Transcript.spliceFailures(fresh, failLog);
        entries = List.copyOf(Transcript.graft(entries, fresh));
    }

    /** 那一轮已经产生的东西封存进记录区（lock 已持有）：时间线 + 半截正文 + 那一行错 / 停。 */
    private void sealTurn(String line, boolean isError) {
        List<Entry> es = new ArrayList<>(turnLog);
        if (!streaming.isEmpty()) {
            es.add("thinking".equals(streamingKind) ? Entry.thinking(streaming.toString(), null) : Entry.assistant(streaming.toString()));
        }
        es.add(Entry.system(line, isError));
        int anchor = humanCount(history);
        boolean landed = anchor > turnBase;
        Entry q = landed ? null : lastUserEntry(entries);
        List<Entry> logged = new ArrayList<>();
        if (q != null) logged.add(q);
        logged.addAll(es);
        failLog.add(new Transcript.Sealed(anchor, List.copyOf(logged)));
        List<Entry> all = new ArrayList<>(entries);
        all.addAll(es);
        entries = List.copyOf(all);
        turnLog.clear();
        resetStreaming();
    }

    /** 按停之后历史才落进来：把那一块的锚往后挪一句，只留那一行（其余的由历史投影出来）。 */
    private void failLogLanded() {
        if (failLog.isEmpty()) return;
        Transcript.Sealed last = failLog.getLast();
        if (last.anchor() == turnBase && humanCount(history) > turnBase) {
            failLog.set(failLog.size() - 1, new Transcript.Sealed(last.anchor() + 1, List.of(last.entries().getLast())));
        }
    }

    private static Entry lastUserEntry(List<Entry> es) {
        Entry hit = null;
        for (Entry e : es) if (e.role() == Entry.Role.USER) hit = e;
        return hit;
    }

    private static int humanCount(List<Message> h) {
        int n = 0;
        for (Message m : h) if (ContextMath.human(m)) n++;
        return n;
    }

    private void sayIfIncomplete(AgentResult r) {
        AgentStatus st = r.status();
        if (st != AgentStatus.COMPLETED && st != AgentStatus.RETURN_DIRECT) {
            emit(new SessionEvent.Incomplete(st.wire(), st == AgentStatus.MAX_ITERATIONS ? config.maxIterations() : null));
        } else if (r.response() != null && r.response().truncated()) {
            emit(new SessionEvent.Incomplete("truncated", effectiveMaxTokens()));
        }
    }

    private void emit(SessionEvent e) {
        for (SessionListener l : listeners) {
            try { l.onEvent(this, e); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    public String toString() { return "AgentSession[" + id + ", " + status() + ", " + model + "]"; }
}
