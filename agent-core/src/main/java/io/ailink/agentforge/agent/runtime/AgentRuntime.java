package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.clarify.Clarify;
import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.context.ContextMath;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.mcp.McpEvent;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.models.ApiKind;
import io.ailink.agentforge.agent.models.ModelClients;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.ProvidersConfig;
import io.ailink.agentforge.agent.models.Problem;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.approval.SessionPermissions;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.registry.AgentRegistry;
import io.ailink.agentforge.agent.route.Router;
import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.ModelBackend;
import io.ailink.agentforge.agent.session.SessionConfig;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.session.SessionListener;
import io.ailink.agentforge.agent.skill.Skills;
import io.ailink.agentforge.agent.sandbox.CommandSandbox;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.agent.store.BitcaskSessionStore;
import io.ailink.agentforge.agent.store.InMemorySessionStore;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.subagent.SubagentHost;
import io.ailink.agentforge.agent.subagent.TaskTool;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Deletion;
import io.ailink.agentforge.agent.tools.Seen;
import io.ailink.agentforge.agent.tools.shell.ShellSettings;
import io.ailink.agentforge.agent.models.EnvVars;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.sandbox.SandboxPlatform;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.execpolicy.ExecPolicyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无界面的 agent 运行时门面：CLI / Compose GUI / Web 后端都嵌它（coxswain 编辑器 {@code editor/agent/*.ss} 里与界面无关的那半）。
 *
 * <p>装配：配置目录、工作根、{@link Models} / {@link ModelClients}、带工具目录的 {@link AgentRegistry}、{@link Toolset}、
 * （可选）{@link McpManager}、{@link SessionStore}、路由表。对外：建 / 开会话、列 / 搜对话、provider / key / 模型管理的直通。
 *
 * <h2>会话怎么装出来</h2>
 * <ul>
 *   <li>工具表：内置（固定顺序）+ 宿主加的 + clarify（主 agent）+ task（开着子 agent 时）+ MCP 工具 + execute；
 *       MCP 目录变了（CATALOG 事件）推新表给每段活着的会话（下一轮生效）。</li>
 *   <li>系统提示：主提示词（按当前目录现算）+ skills 那一节 + MCP 服务器的说明 + 当前 agent 自己那一段。
 *       worktree-enter 生效后按新目录重算。</li>
 *   <li>每段会话一份派生工作区（seen 表、删除确认格各自独立 —— 见 {@link Workspace#fork}）。</li>
 *   <li>权限：审批策略 × 沙盒策略（{@link PermissionSettings}，显式给的 → {@code $AGENTFORGE_APPROVAL} / {@code $AGENTFORGE_SANDBOX}
 *       → prefs → 预设 auto），改了记进 prefs、活着的会话跟着换；执行规则（{@code .rules}）按项目根装：内建 → 用户
 *       {@code <配置目录>/rules} → 项目 {@code <根>/.agentforge/rules}（只收紧），「以后不再问」追加到用户的 {@code rules/default.rules}。</li>
 *   <li>自动存盘：每轮落地 / 出错 / 按停 / 压缩之后把历史同步进库（只追加）；落库失败绝不拦住对话，只记 {@link #lastStoreError()}。</li>
 *   <li>库：没给就用 {@code AgentPaths.dataDir()/sessions} 上的 {@link BitcaskSessionStore}；libbitcask 装不上 / 库被占着 ⇒
 *       退回内存库并在 {@link #storeWarning()} 说清楚（对话这次不会留下来 —— 这件事必须让人知道）。</li>
 *   <li>run-command 的执行器：execpolicy + 平台沙盒（每段会话一份，审批摆在那段会话上）；没有可用后端 ⇒ 受限策略下逐条审批后裸跑，
 *       {@link #sandboxStatus()} 说原因。</li>
 * </ul>
 */
public final class AgentRuntime implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);

    private final Path configDir;
    private final Workspace workspace;
    private final Models models;
    private final ModelClients clients;
    private final ModelBackend backend;
    private final AgentRegistry registry;
    private final Toolset toolset;
    private final McpManager mcp;
    private final SessionStore store;
    private final Router router;
    private final Prompts.Host host;
    private final SessionConfig configTemplate;
    private final boolean autosave;
    private final int historyKeep;
    private volatile PermissionSettings permissionDefault;
    private volatile boolean subagentsEnabled;
    private volatile int subagentDepth;
    private volatile Integer subagentMaxIterations;
    private volatile String lastStoreError;
    private final String storeWarning;
    private final CommandSandbox sandbox;

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> convs = new ConcurrentHashMap<>();
    private final Map<String, ExecPolicyManager> policiesByRoot = new ConcurrentHashMap<>();
    private final SubagentHost subagentHost = new Host();
    private final McpEvent.Listener mcpListener;
    private final List<String> configProblems;

    private AgentRuntime(Builder b) {
        if (b.configDir != null) AgentPaths.override(b.configDir);
        this.configDir = AgentPaths.configDir();
        this.workspace = new Workspace(b.workspaceRoot != null ? b.workspaceRoot : Path.of(System.getProperty("user.dir")));
        this.models = b.models != null ? b.models : Models.instance();
        // 用户自己的 provider / 模型 / 默认模型（<configDir>/providers.json）；写错的那家跳过，原因留给宿主说
        this.configProblems = b.models != null ? List.of() : ProvidersConfig.apply(models, configDir);
        this.clients = b.clients != null ? b.clients : new ModelClients(models);
        this.backend = b.backend != null ? b.backend
                : new ModelClientsBackend(models, clients, b.callerPolicy, b.connectTimeout);
        this.host = b.host != null ? b.host : Prompts.Host.CLI;
        this.registry = b.registry != null ? b.registry : new AgentRegistry(host);
        this.toolset = b.toolset != null ? b.toolset : Toolset.standard();
        for (AgentTool t : b.extraTools) toolset.add(t);
        this.registry.toolCatalog(toolset.catalog());     // ⚠️ 先装目录再读配置 / 建会话
        this.mcp = b.mcp;
        String warn = null;
        SessionStore st = b.store;
        if (st == null) {
            String why = BitcaskRuntime.unavailableReason();
            if (why == null) {
                Path dir = AgentPaths.dataDir().resolve("sessions");
                try {
                    st = BitcaskSessionStore.open(dir);
                } catch (RuntimeException e) {
                    warn = "会话库 " + dir + " 打不开（" + (e.getMessage() == null ? e : e.getMessage()) + "），本次对话只存在内存里";
                }
            } else {
                warn = "libbitcask 装不上（" + why + "），本次对话只存在内存里（设 AGENTFORGE_BITCASK_LIB 指向 libbitcask.so）";
            }
            if (st == null) {
                st = new InMemorySessionStore();
                LOG.warn(warn);
            }
        }
        this.store = st;
        this.storeWarning = warn;
        this.sandbox = b.commandSandbox != null ? b.commandSandbox : CommandSandbox.select(b.sandboxOptions);
        this.router = b.router != null ? b.router : new Router();
        this.configTemplate = b.config != null ? b.config : SessionConfig.defaults();
        this.autosave = b.autosave;
        this.historyKeep = b.historyKeep;
        this.subagentsEnabled = b.subagents;
        this.subagentDepth = b.subagentDepth;
        this.subagentMaxIterations = b.subagentMaxIterations;
        this.permissionDefault = resolvePermissions(b, models);
        if (mcp != null) {
            toolset.mcp(mcp.toolsSupplier());
            mcpListener = e -> { if (e.kind() == McpEvent.Kind.CATALOG) onMcpCatalog(); };
            mcp.addListener(mcpListener);
            if (b.startMcp) mcp.start();
        } else mcpListener = null;
    }

    public static Builder builder() { return new Builder(); }

    /** 装配参数。全都可选：什么都不给就是"当前目录 + 默认配置目录 + 内存库 + 不连 MCP"。 */
    public static final class Builder {
        private Path configDir, workspaceRoot;
        private Models models;
        private ModelClients clients;
        private ModelBackend backend;
        private CallerPolicy callerPolicy;
        private Duration connectTimeout;
        private AgentRegistry registry;
        private Toolset toolset;
        private final List<AgentTool> extraTools = new ArrayList<>();
        private McpManager mcp;
        private boolean startMcp = true;
        private SessionStore store;
        private Router router;
        private Prompts.Host host;
        private SessionConfig config;
        private PermissionSettings permissions;
        private ApprovalPolicy approval;
        private Boolean networkAccess;
        private List<String> writableRoots;
        private CommandSandbox commandSandbox;
        private boolean autosave = true;
        private int historyKeep = 100;
        private boolean subagents = true;
        private int subagentDepth = 1;
        private Integer subagentMaxIterations;
        private SandboxMode sandbox;
        private SandboxPlatform.Options sandboxOptions;

        /** 配置目录（⚠️ 进程级：会 {@code AgentPaths.override}）。 */
        public Builder configDir(Path p) { this.configDir = p; return this; }

        public Builder workspaceRoot(Path p) { this.workspaceRoot = p; return this; }

        public Builder models(Models m) { this.models = m; return this; }

        public Builder clients(ModelClients c) { this.clients = c; return this; }

        /** 直接给一个后端（测试用假 caller）；给了它 models / clients / callerPolicy 就不参与问模型。 */
        public Builder backend(ModelBackend b) { this.backend = b; return this; }

        public Builder callerPolicy(CallerPolicy p) { this.callerPolicy = p; return this; }

        /** 建连那一步的死线（原生 Anthropic caller 用；默认 10 s）。 */
        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }

        public Builder registry(AgentRegistry r) { this.registry = r; return this; }

        public Builder toolset(Toolset t) { this.toolset = t; return this; }

        /** 宿主自己的工具（接在内置之后）。 */
        public Builder tool(AgentTool t) { this.extraTools.add(t); return this; }

        public Builder mcp(McpManager m) { this.mcp = m; return this; }

        /** 建好时要不要顺手 {@code mcp.start()}（默认要）。 */
        public Builder startMcp(boolean on) { this.startMcp = on; return this; }

        public Builder store(SessionStore s) { this.store = s; return this; }

        public Builder router(Router r) { this.router = r; return this; }

        public Builder host(Prompts.Host h) { this.host = h; return this; }

        /** 新会话的可调项模板（每段会话拿一份拷贝）。 */
        public Builder config(SessionConfig c) { this.config = c; return this; }

        /** 整套权限设置（给了就不看 prefs / 环境变量，也不理会下面几个单项）。 */
        public Builder permissions(PermissionSettings p) { this.permissions = p; return this; }

        /** 审批策略；null = 看配置（{@code $AGENTFORGE_APPROVAL} → prefs → on-request）。 */
        public Builder approvalPolicy(ApprovalPolicy p) { this.approval = p; return this; }

        /** 沙盒里能不能出网；null = 看 prefs（默认断网）。 */
        public Builder networkAccess(Boolean on) { this.networkAccess = on; return this; }

        /** workspace-write 下另加的可写根；null = 看 prefs。 */
        public Builder writableRoots(List<String> roots) { this.writableRoots = roots; return this; }

        /** 直接给平台沙盒（测试用 {@link CommandSandbox#none}）；null = 探测本机。 */
        public Builder commandSandbox(CommandSandbox c) { this.commandSandbox = c; return this; }

        public Builder autosave(boolean on) { this.autosave = on; return this; }

        /** 库里最多留几段主对话（新建对话时把更老的删掉）；≤0 = 不限。默认 100。 */
        public Builder historyKeep(int n) { this.historyKeep = n; return this; }

        /** 开不开子 agent（task 工具）。 */
        public Builder subagents(boolean on) { this.subagents = on; return this; }

        /** 子 agent 能套几层（默认 1）。打错的值（≤0）回到 1。 */
        public Builder subagentDepth(int n) { this.subagentDepth = n > 0 ? n : 1; return this; }

        public Builder subagentMaxIterations(Integer n) { this.subagentMaxIterations = n == null || n <= 0 ? null : n; return this; }

        /** 沙盒三档；null = 看配置（{@code $AGENTFORGE_SANDBOX} → prefs 的 {@code sandbox} → workspace-write）。 */
        public Builder sandbox(SandboxMode m) { this.sandbox = m; return this; }

        /** 平台沙箱的选项（bwrap 路径、Landlock 原生件目录、Windows 账本目录…）；null = 缺省。 */
        public Builder sandboxOptions(SandboxPlatform.Options o) { this.sandboxOptions = o; return this; }

        public AgentRuntime build() { return new AgentRuntime(this); }
    }

    // ══ 读口 ═════════════════════════════════════════════════════════════

    public Path configDir() { return configDir; }

    /** 运行时的基准工作区（每段会话在它上面派生一份）。 */
    public Workspace workspace() { return workspace; }

    public Path workspaceRoot() { return workspace.root(); }

    public Models models() { return models; }

    public ModelClients clients() { return clients; }

    public ModelBackend backend() { return backend; }

    public AgentRegistry registry() { return registry; }

    public Toolset toolset() { return toolset; }

    /** null = 没接 MCP。 */
    public McpManager mcp() { return mcp; }

    public SessionStore store() { return store; }

    public Router router() { return router; }

    public Prompts.Host host() { return host; }

    public SubagentHost subagentHost() { return subagentHost; }

    /** 库退回了内存（libbitcask 装不上 / 库打不开）时的那句话；null = 在用给定的库或 bitcask 库。 */
    public String storeWarning() { return storeWarning; }

    /** run-command 的沙箱（策略、用没用上、为什么）。 */
    public CommandSandbox sandbox() { return sandbox; }

    /** 给人看的沙箱状态（启动时打出来）。 */
    public String sandboxStatus() { return sandbox.status(); }

    /**
     * 配置定的权限：builder 的整套设置 → 否则 prefs 打底，环境变量 {@code $AGENTFORGE_APPROVAL} / {@code $AGENTFORGE_SANDBOX} 盖上，
     * builder 的单项再盖上；写错的值退回默认并记一句。
     */
    static PermissionSettings resolvePermissions(Builder b, Models models) {
        if (b.permissions != null) return b.permissions;
        PermissionSettings ps = PermissionSettings.DEFAULT;
        String ap = null, sb = null;
        Boolean net = null;
        List<String> roots = List.of();
        try {
            ap = models.prefs().approval();
            sb = models.prefs().sandbox();
            net = models.prefs().network();
            roots = models.prefs().writableRoots();
        } catch (RuntimeException ignored) { }
        String envAp = EnvVars.get("AGENTFORGE_APPROVAL"), envSb = EnvVars.get("AGENTFORGE_SANDBOX");
        if (envAp != null) ap = envAp;
        if (envSb != null) sb = envSb;
        if (ap != null) {
            try { ps = ps.withApproval(ApprovalPolicy.parse(ap)); } catch (IllegalArgumentException e) { LOG.warn(e.getMessage() + "，按 on-request"); }
        }
        if (sb != null) {
            try { ps = ps.withSandbox(SandboxMode.parse(sb)); } catch (IllegalArgumentException e) { LOG.warn(e.getMessage() + "，按 workspace-write"); }
        }
        if (net != null) ps = ps.withNetworkAccess(net);
        if (roots != null && !roots.isEmpty()) ps = ps.withWritableRoots(roots);
        if (b.approval != null) ps = ps.withApproval(b.approval);
        if (b.sandbox != null) ps = ps.withSandbox(b.sandbox);
        if (b.networkAccess != null) ps = ps.withNetworkAccess(b.networkAccess);
        if (b.writableRoots != null) ps = ps.withWritableRoots(b.writableRoots);
        return ps;
    }

    /** 最近一次落库失败的原因；null = 没失败过。 */
    public String lastStoreError() { return lastStoreError; }

    // ══ 会话 ═════════════════════════════════════════════════════════════

    /** 用默认 agent 开一段新对话。 */
    public AgentSession newSession() { return newSession(null); }

    /** 用 {@code agent} 开一段新对话（null = 注册表的默认）。 */
    public AgentSession newSession(String agent) {
        String who = agent != null ? agent : registry.defaultAgent();
        if (who == null) who = "main";
        Workspace sws = workspace.fork(Seen.class, Deletion.class);
        Path root = sws.root();
        String model = null;
        try { model = models.defaultModel(); } catch (RuntimeException ignored) { }
        AgentSession s = AgentSession.builder(backend, sws)
                .model(model)
                .system(systemPrompt(who, root))
                .config(configTemplate.copy())
                .permissions(sessionPermissions(root))
                .agent(who)
                .build();
        try {
            String th = models.prefs().thinking();
            if (th != null) s.setThinking(th);
        } catch (RuntimeException ignored) { }
        installRunner(s);
        s.setTools(toolsFor(s));
        s.setActiveTools(agentToolNames(who));
        if (mcp != null) s.setToolsSettling(mcp::connecting);
        s.setOnRootChanged(p -> {
            s.permissions().setExecPolicy(execPolicy(p));
            s.setSystem(systemPrompt(s.agent(), p));
            s.setTools(toolsFor(s));
        });
        if (autosave) s.addListener(saver);
        sessions.put(s.id(), s);
        return s;
    }

    /** 从库里打开一段对话（历史 + 压缩指针装回去；接着聊）。 */
    public AgentSession openSession(long conv) {
        SessionStore.Conversation c = store.conversation(conv)
                .orElseThrow(() -> new IllegalArgumentException("没有这段对话：" + conv));
        List<Message> msgs = store.messages(conv);
        List<Compaction.Pointer> cs = store.compactions(conv);
        AgentSession s = newSession();
        s.setHistory(msgs);
        s.setCompactions(cs);
        s.setTitle(c.title());
        convs.put(s.id(), conv);
        return s;
    }

    /**
     * 给这段会话一份自己的 {@link ShellSettings}（超时照抄运行时那份），执行器带审批与沙盒：审批要摆到**这段**会话上，
     * 所以执行器不能在会话之间共用。子 agent 派生自它的工作区，共用这一份 —— 审批照样问到用户眼前的父会话。
     */
    private void installRunner(AgentSession s) {
        ShellSettings base = ShellSettings.of(workspace);
        ShellSettings mine = new ShellSettings().timeoutDefaultMs(base.timeoutDefaultMs()).timeoutCapMs(base.timeoutCapMs())
                .afterGit(base.afterGit());
        mine.runner(sandbox.runnerFor(s));
        s.workspace().put(ShellSettings.class, mine);
    }

    /** 活着的主会话。 */
    public List<AgentSession> sessions() { return List.copyOf(sessions.values()); }

    /** 不再管这段会话（按停、摘掉监听）。 */
    public void closeSession(AgentSession s) {
        s.interrupt();
        s.removeListener(saver);
        sessions.remove(s.id());
    }

    /** 这段会话在库里的对话 id；还没存过 null。 */
    public Long conversationOf(AgentSession s) { return convs.get(s.id()); }

    /** 换 agent：系统提示接上它那一段、工具集换成它那套（main = 全给）。 */
    public void selectAgent(AgentSession s, String who) {
        AgentDef d = registry.def(who);
        if (d == null) throw new IllegalArgumentException("没有叫 \"" + who + "\" 的 agent（有这些：" + registry.visibleDefs().stream().map(AgentDef::name).toList() + "）");
        s.setAgent(who);
        s.setSystem(systemPrompt(who, s.workspace().root()));
        s.setActiveTools(agentToolNames(who));
    }

    private List<String> agentToolNames(String who) {
        if (who == null || who.equals("main")) return null;
        AgentDef d = registry.def(who);
        if (d == null) return null;
        List<String> out = new ArrayList<>();
        for (AgentTool t : d.toolList()) out.add(t.name());
        return out;
    }

    /** 一段主会话的整张工具表（clarify 绑到这段会话上）。 */
    public List<AgentTool> toolsFor(AgentSession s) {
        AgentTool clarify = Clarify.tool(s::askQuestion);
        AgentTool task = subagentsEnabled && !registry.subagentDefs().isEmpty() ? TaskTool.create(subagentHost) : null;
        return toolset.forSession(s.workspace(), clarify, task);
    }

    /**
     * 系统提示：主提示词（当前目录）+ skills 那一节 + MCP 服务器的说明 + {@code agent} 自己那一段（main 没有）。
     */
    public String systemPrompt(String agent, Path root) {
        StringBuilder sb = new StringBuilder(Prompts.agentSystemPrompt(root.toString(), host));
        try { sb.append(Skills.promptSection(root)); } catch (RuntimeException ignored) { }
        if (mcp != null) {
            Map<String, String> ins = mcp.instructions();
            if (!ins.isEmpty()) {
                sb.append("\n\n## MCP 服务器的说明\n");
                ins.forEach((name, text) -> sb.append("\n### ").append(name).append("\n").append(text).append("\n"));
            }
        }
        AgentDef d = agent == null || agent.equals("main") ? null : registry.def(agent);
        String extra = d == null ? null : d.promptFor(root.toString());
        if (extra != null && !extra.isEmpty()) sb.append("\n\n").append(extra);
        return sb.toString();
    }

    private void onMcpCatalog() {
        toolset.refreshMcp();
        for (AgentSession s : sessions.values()) {
            s.setTools(toolsFor(s));
            s.setSystem(systemPrompt(s.agent(), s.workspace().root()));
        }
    }

    // ══ 存盘 ═════════════════════════════════════════════════════════════

    private final SessionListener saver = (s, e) -> {
        if (e instanceof SessionEvent.Finished || e instanceof SessionEvent.Error || e instanceof SessionEvent.Stopped
                || e instanceof SessionEvent.Compacted) {
            save(s);
        }
    };

    /**
     * 把这段会话同步进库（只追加；第一次存时建对话，标题取第一句话的前 30 个字）。子会话挂在父对话底下。
     * 历史变短（reset 过）→ 开一段新对话接着存。失败只记 {@link #lastStoreError()}，绝不抛。
     */
    public synchronized void save(AgentSession s) {
        try {
            List<Message> h = s.storableHistory();
            if (h.isEmpty()) return;
            Long conv = convs.get(s.id());
            if (conv != null) {
                SessionStore.Conversation c = store.conversation(conv).orElse(null);
                if (c == null || c.messages() > h.size()) conv = null;    // reset 过 / 被删了：换一段
            }
            if (conv == null) {
                Long parent = s.parent() == null ? null : convs.get(s.parent().id());
                if (s.parent() != null && parent == null) { save(s.parent()); parent = convs.get(s.parent().id()); }
                String title = s.title() != null ? s.title() : titleOf(h);
                conv = store.newConversation(title, parent, s.id());
                convs.put(s.id(), conv);
                if (parent == null && historyKeep > 0) store.truncate(historyKeep);
            }
            store.sync(conv, h);
            List<Compaction.Pointer> have = store.compactions(conv);
            for (Compaction.Pointer p : s.compactions()) if (!have.contains(p)) store.addCompaction(conv, p);
            for (AgentSession c : s.children()) if (!c.history().isEmpty()) save(c);
            lastStoreError = null;
        } catch (RuntimeException ex) {
            lastStoreError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
    }

    /** 标题：第一句人话的前 30 个字（单行化）。 */
    static String titleOf(List<Message> h) {
        for (Message m : h) {
            if (!ContextMath.human(m)) continue;
            String one = Reminder.shownText(m).replaceAll("\\s+", " ").strip();
            if (one.isEmpty()) continue;
            return one.length() > 30 ? one.substring(0, 30) + "…" : one;
        }
        return "";
    }

    public List<SessionStore.Conversation> conversations(int limit) { return store.conversations(limit); }

    public List<SessionStore.SearchHit> search(String query, int limit) { return store.search(query, limit); }

    public void deleteConversation(long conv) {
        store.deleteConversation(conv);
        convs.values().removeIf(v -> v == conv);
    }

    public void setTitle(long conv, String title) { store.setTitle(conv, title); }

    // ══ 权限 ═════════════════════════════════════════════════════════════

    /** 内建规则（{@code builtin.rules} 资源）；用户规则目录里放一份同名文件就盖掉它（空文件 = 不要内建规则）。 */
    static final String BUILTIN_RULES_FILE = "builtin.rules";

    /** 新会话的权限设置。 */
    public PermissionSettings permissionSettings() { return permissionDefault; }

    /** 改默认并记进 prefs（跨重启）；活着的主会话（连同子 agent）一起改。 */
    public void setPermissionSettings(PermissionSettings ps) {
        permissionDefault = Objects.requireNonNull(ps);
        try {
            models.prefs().setApproval(ps.approval().wire());
            models.prefs().setSandbox(ps.sandbox().wire());
            models.prefs().setNetwork(ps.networkAccess());
            models.prefs().setWritableRoots(ps.writableRoots());
        } catch (RuntimeException ignored) { }
        for (AgentSession s : sessions.values()) s.permissions().setSettings(ps);
    }

    /** 换成某个预设（保留可写根之外的一切都按预设来）。 */
    public void applyPreset(ApprovalPreset p) { setPermissionSettings(p.settings()); }

    /** 用户规则目录 {@code <配置目录>/rules}（「以后不再问」也写在这里的 {@code default.rules}）。 */
    public Path userRulesDir() { return configDir.resolve("rules"); }

    /** 项目规则目录 {@code <根>/.agentforge/rules}。 */
    public static Path projectRulesDir(Path root) { return root.resolve(PermissionSettings.HOST_METADATA_DIR).resolve("rules"); }

    /** 某个项目根的执行规则（第一次取时读盘；内建 → 用户 → 项目。项目规则随仓库而来、不可信：只能收紧，其中的 allow 不生效）。 */
    public ExecPolicyManager execPolicy(Path root) {
        String key = root.toAbsolutePath().normalize().toString();
        return policiesByRoot.computeIfAbsent(key, k -> {
            Path user = userRulesDir();
            String builtin = java.nio.file.Files.exists(user.resolve(BUILTIN_RULES_FILE)) ? null : builtinRules();
            return new ExecPolicyManager(BUILTIN_RULES_FILE, builtin, List.of(user), List.of(projectRulesDir(Path.of(k))),
                    user.resolve(ExecPolicyManager.DEFAULT_POLICY_FILE));
        });
    }

    /** 重读所有已装的规则（改了 .rules 文件之后）。回读规则时的警告。 */
    public List<String> reloadRules() {
        List<String> warn = new ArrayList<>();
        for (ExecPolicyManager m : policiesByRoot.values()) {
            m.reload();
            warn.addAll(m.warnings());
        }
        return warn;
    }

    static String builtinRules() {
        try (var in = AgentRuntime.class.getResourceAsStream("/io/ailink/agentforge/agent/approval/builtin.rules")) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** 一段主会话的权限状态（子 agent 共用它）。 */
    private SessionPermissions sessionPermissions(Path root) {
        SessionPermissions p = new SessionPermissions(permissionDefault, execPolicy(root), sandbox.active());
        try { p.loadAlwaysTools(models.prefs().approvedTools()); } catch (RuntimeException ignored) { }
        p.onAlwaysTool(name -> {
            try { models.prefs().addApprovedTool(name); } catch (RuntimeException ignored) { }
        });
        return p;
    }

    // ══ 子 agent ════════════════════════════════════════════════════════

    public boolean subagentsEnabled() { return subagentsEnabled; }

    /** 开关子 agent（关掉时 task 工具还在表里，调用被挡回去 —— 为一个开关重建会话等于扔掉对话历史）。 */
    public void setSubagentsEnabled(boolean on) { subagentsEnabled = on; }

    public int subagentDepth() { return subagentDepth; }

    public void setSubagentDepth(int n) { subagentDepth = n > 0 ? n : 1; }

    public Integer subagentMaxIterations() { return subagentMaxIterations; }

    public void setSubagentMaxIterations(Integer n) { subagentMaxIterations = n == null || n <= 0 ? null : n; }

    private final class Host implements SubagentHost {
        @Override public boolean enabled() { return subagentsEnabled; }

        @Override public int maxDepth() { return subagentDepth; }

        @Override public Integer maxIterations() { return subagentMaxIterations; }

        @Override public List<AgentDef> defs() { return registry.subagentDefs(); }

        @Override public AgentSession newChild(AgentSession parent, AgentDef def, String desc, String id) {
            Workspace cws = parent.workspace().fork(Seen.class, Deletion.class);
            List<AgentTool> tools = new ArrayList<>();
            for (AgentTool t : def.toolList()) if (t.enabled(cws)) tools.add(t);
            String sys = def.promptFor(cws.root().toString());
            SessionConfig cfg = parent.config().copy().staging(false).maxIterations(subagentMaxIterations);
            AgentSession child = AgentSession.builder(backend, cws)
                    .parent(parent)
                    .id(id)
                    .title(desc)
                    .model(parent.model())
                    .system(sys != null ? sys : Prompts.SUBAGENT_SYSTEM_PROMPT)
                    .tools(tools)
                    .agent(def.name())
                    .config(cfg)
                    .build();
            // 子 agent 自己一份执行器：命令审批照样摆到父会话上，但认得出是哪一趟子 agent 问的（请求上的 session = 子会话 id）
            installRunner(child);
            return child;
        }

        @Override public Choice chooseModel(AgentSession parent, AgentDef def, TaskKind kind) {
            for (String ref : def.models()) {
                try { if (models.meta(ref) != null) return new Choice(ref, "钉的模型"); } catch (RuntimeException ignored) { }
            }
            String main = parent.model();
            if (main == null) return new Choice(null, "没有主模型");
            try {
                if (router.table().isEmpty()) router.build(models);
                Router.Decision d = router.model(kind, main);
                return new Choice(d.model() != null ? d.model() : main, d.why());
            } catch (RuntimeException e) {
                return new Choice(main, "路由表建不出来，用主模型");
            }
        }
    }

    // ══ 模型 / provider / key（直通 Models）═════════════════════════════

    /** 当前默认模型（null = 还没选）。 */
    public String currentModel() { return models.defaultModel(); }

    /** 缺哪一步（null = 配好了）。 */
    public Problem problem() { return models.problem(); }

    public List<ModelMeta> modelMetas() { return models.modelMetas(); }

    public List<String> modelRefs() { return models.modelRefs(); }

    /** 选模型（记进 prefs）；活着的主会话跟着换。 */
    public void selectModel(String ref) {
        models.selectModel(ref);
        for (AgentSession s : sessions.values()) s.setModel(ref);
    }

    /**
     * 改默认模型但**不动开着的会话**（网页端：好几段会话各用各的）。默认模型的优先级是 环境变量 → providers.json 的
     * {@code model} → 上次点的：配置里写了默认的话，只改 prefs 等于没改，所以连那一项一起改写。
     */
    public synchronized void setDefaultModel(String ref) {
        models.selectModel(ref);
        if (models.configDefault() != null) {
            ProvidersConfig.setDefaultModel(configDir, ref);
            models.setConfigDefault(ref);
        }
    }

    /** providers.json 里没用上的那几条（每条一句）；宿主启动时该说出来。 */
    public List<String> configProblems() { return configProblems; }

    public List<Models.ProviderCandidate> providerCandidates() { return models.providerCandidates(); }

    public void selectProvider(String id, String baseUrl, ApiKind api) {
        models.selectProvider(id, baseUrl, api);
        refreshModels();
    }

    public void unselectProvider(String id) {
        models.unselectProvider(id);
        refreshModels();
    }

    public void setKey(String providerId, String key) {
        models.setKey(providerId, key);
        refreshModels();
    }

    public void removeKey(String providerId) {
        models.removeKey(providerId);
        refreshModels();
    }

    /**
     * 重读 {@code providers.json}（网页上加 / 改 / 删了一家之后）：先撤掉配置定义的那几家与它们手写的模型，再整份应用一遍。
     * 回这次读出来的问题（每条一句）。界面里选中的（models.dev 那几家）不受影响。
     */
    public synchronized List<String> reloadProvidersConfig() {
        for (String id : models.providerIds()) {
            var p = models.provider(id);
            if (p == null || p.origin() != io.ailink.agentforge.agent.models.ProviderInfo.Origin.DEFINED) continue;
            for (ModelMeta m : models.modelMetas())
                if (!m.builtin() && m.ref().providerId().equals(id)) models.unregisterModel(m.ref().toString());
            models.undefineProvider(id);
        }
        List<String> problems = ProvidersConfig.apply(models, configDir);
        refreshModels();
        return problems;
    }

    /** 装一份手里的 models.dev api.json（服务器连不上外网时）；回装上了没有。 */
    public boolean importCatalog(byte[] body) {
        boolean ok = models.catalog().importJson(body);
        refreshModels();
        return ok;
    }

    /** 同步模型目录（联网；{@code url} null = models.dev），带进度与失败原因。同步完重注册模型。 */
    public io.ailink.agentforge.agent.models.Catalog.SyncResult syncCatalog(String url, java.util.function.LongConsumer progress) {
        var r = models.catalog().sync(url, progress);
        refreshModels();
        return r;
    }

    /** 刷新 models.dev 目录（联网）；回刷到了没有。 */
    public boolean refreshCatalog() {
        boolean ok = models.catalog().refresh();
        refreshModels();
        return ok;
    }

    /** 池子变了：重注册模型、清客户端缓存、路由表作废（下次派活时重建）。 */
    public void refreshModels() {
        try { models.registerModels(); } catch (RuntimeException ignored) { }
        clients.invalidate();
        router.clear();
    }

    // ══ 收尾 ═════════════════════════════════════════════════════════════

    @Override
    public void close() {
        for (AgentSession s : sessions.values()) {
            try { s.interrupt(); } catch (RuntimeException ignored) { }
        }
        sessions.clear();
        if (mcp != null) {
            mcp.removeListener(mcpListener);
            try { mcp.stop(); } catch (RuntimeException ignored) { }
        }
        try { store.close(); } catch (RuntimeException ignored) { }
    }

    @Override
    public String toString() { return "AgentRuntime[" + workspace.root() + ", " + Objects.toString(currentModel(), "未选模型") + "]"; }
}
