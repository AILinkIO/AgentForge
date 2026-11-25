package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.mcp.McpServerInfo;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.ModelRef;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.Problem;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.GalleryImage;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.session.SessionListener;
import io.ailink.agentforge.agent.session.SessionStatus;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.todo.Todo;
import io.ailink.agentforge.agent.todo.TodoStore;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.agent.transcript.Transcript;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.CompletionItem;
import io.ailink.agentforge.tui.CompletionSource;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Hooks;
import io.ailink.agentforge.tui.Keymap;
import io.ailink.agentforge.tui.MinibufferRequest;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Rect;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.Split;
import io.ailink.agentforge.tui.Timers;
import io.ailink.agentforge.tui.Window;
import io.ailink.agentforge.tui.commands.DocumentCommands;
import io.ailink.agentforge.tui.text.Width;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全屏 agent 工作台：建在 {@code :tui} 的 {@link Editor} 与 agent-core 的 {@link AgentRuntime} 之上。
 * <b>只做 agent</b>：没有打开 / 编辑文件、没有项目树；唯一能编辑的是底部的输入框。样子照 OpenAI Codex 的 TUI。
 *
 * <h2>布局</h2>
 * 上面 {@code *agent*} 记录区（只读文档：一条一格、折叠、按钮、跟尾；开头一张会话头卡片），没有 header line / mode line；
 * 下面是「底栏」（输入框那个窗口，按内容伸缩）：顶上几行是状态行 {@code • 思考中 (12s • Esc Esc 中断)}（流光）、排队的话、
 * 子 agent 提示；中间是输入框（淡衬底、{@code › } 打头、空着时一句暗色占位）；最底一行键位提示，右边模型 · 上下文剩余 · 用量。
 * 审批 / 提问 / 选择器摆出来时顶替输入框（{@link ApprovalPanel} / {@link QuestionPanel} / {@link ListPanel}）；
 * echo area 叠在最后一行上（有话说时才盖住提示行）。侧栏默认不开，{@code /side} 开。
 *
 * <h2>线程</h2>
 * 会话事件在 agent 线程上到：只往 {@link Editor#post} 投（重画合并成一次 {@link #refresh()}）。
 *
 * <h2>键（精简过）</h2>
 * {@code C-x C-c} 退出、{@code C-x o} 记录区 ↔ 输入框；{@code C-c C-c} 停这一轮；{@code C-c m / k / p} 选模型 / 思考档 / 权限，
 * {@code C-c l} 对话列表（第一项开新的）。其余都走 {@code /} 命令与 {@code M-x}。输入框：RET 发送、C-j 换行、↑↓ 到边才翻历史、
 * Esc 闲时清空 / 跑时按两次停、{@code /} 命令表、{@code @} 文件。
 */
public final class AgentWorkbench {

    /** 启动选项。 */
    public static final class Options {
        boolean requireModel = true;
        List<String> notes = List.of();
        String agent;
        Boolean sidePanel;
        boolean persistHistory = true;

        public Options requireModel(boolean v) { requireModel = v; return this; }
        /** 启动时额外要说的话（命令行那层的 bootstrap 结果）。 */
        public Options notes(List<String> v) { notes = v == null ? List.of() : List.copyOf(v); return this; }
        public Options agent(String v) { agent = v; return this; }
        /** 侧栏（待办 / 子 agent / MCP / 权限）：true = 一开始就开；默认不开（Codex 没有侧栏，要看用 /side）。 */
        public Options sidePanel(Boolean v) { sidePanel = v; return this; }
        public Options persistHistory(boolean v) { persistHistory = v; return this; }
    }

    static final String LEADER = "C-c";
    static final String VERSION = readVersion();
    static final long ESC_WINDOW_MS = 5000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    final Editor ed;
    final AgentRuntime rt;
    final Options opts;
    final Buffer transcript, prompt, side;
    final Window tw, iw;
    Window sw;
    final Split group;
    final TranscriptView view;
    final ApprovalPanel approval;
    final QuestionPanel question;
    final SlashCommands slash;
    final ListPanel list;
    final Mode transcriptMode = new Mode("agent-transcript-mode", "Agent", DocumentCommands.DOCUMENT_MODE);
    final Mode promptMode = new Mode("agent-input-mode", "Input", null);

    volatile AgentSession root;
    AgentSession viewing;
    String viewingRun;
    private final SessionListener listener = this::onEvent;
    private final SessionListener childListener = (s, e) -> requestRefresh();
    private final List<Note> notes = new ArrayList<>();
    final Map<String, String> fullResults = new ConcurrentHashMap<>();
    /** 改文件的调用审批时看过的 diff（tool-use-id ⇒ 预览全文）：落地后那格照它画真 diff。 */
    final Map<String, String> previews = new ConcurrentHashMap<>();
    /** 审批的结果（tool-use-id ⇒ 「✔ 你批准了…」那一行）：画在那次调用的格子后面。 */
    final Map<String, String> decisions = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    long escArmedAt = -1;
    String errorLine;
    long turnStartedAt = -1;
    /** 库里那段对话的标题（自动按第一句起的；缓存，一轮落地 / 换会话时作废）。 */
    private String storeTitle;
    private Timers.Timer ticker;
    private int histIdx = -1;
    private Path historyFile;

    private record Note(int anchor, Entry entry) { }

    private AgentWorkbench(Editor ed, AgentRuntime rt, Options opts) {
        this.ed = ed;
        this.rt = rt;
        this.opts = opts;
        Msg.install();
        ed.options().set("esc-prefix", false);
        ed.options().set("echo-area", "overlay");
        ed.options().set("completion-display", "above-window");

        transcript = ed.getOrCreateBuffer("*agent*");
        transcript.setMode(transcriptMode);
        transcript.undo().setEnabled(false);
        transcript.put("word-wrap", true);
        transcript.setReadOnly(true);
        transcript.setModeLine(ModeLine.NONE);

        prompt = ed.getOrCreateBuffer("*agent-input*");
        prompt.setMode(promptMode);
        prompt.put("word-wrap", true);
        // 输入框（Codex composer）：一整块淡衬底，上下各垫一行；第一行 › 打头，之后的行垫两格；空着时一句暗色占位
        prompt.put("buffer-face", "agent-input");
        prompt.put("first-line-prefix", "› ");
        prompt.put("line-prefix", "  ");
        prompt.put("wrap-prefix", "  ");
        prompt.put("line-prefix-face", "agent-input-bar");
        prompt.put("header-line-face", "agent-input");
        prompt.put("placeholder-face", "agent-input-dim");
        prompt.setHeaderLine(w -> List.of());
        prompt.setFooter(w -> List.of(List.of()));
        prompt.setTopRows(this::bottomRows);
        prompt.setModeLine(new ModeLine().add("hint", 10, false, this::hintSegs).add("usage", 20, true, this::usageSegs));
        prompt.put("mode-line-face", "default");

        side = ed.getOrCreateBuffer("*agent-side*");
        side.setMode(DocumentCommands.DOCUMENT_MODE);
        side.setReadOnly(true);
        side.undo().setEnabled(false);
        side.put("word-wrap", true);
        side.put("buffer-face", "agent-side");
        side.put("wrap-prefix", "   ");
        side.put("header-line-face", "agent-side");
        side.setHeaderLine(w -> List.of(Seg.of(" " + Msg.t("Overview"), "agent-side-dim")));
        side.setModeLine(ModeLine.NONE);

        tw = ed.selectedWindow();
        ed.showBuffer(tw, transcript);
        tw.setFollowTail(true);
        tw.setParam(Window.DEDICATED, true);
        iw = ed.splitWindow(tw, Split.Dir.BELOW, 0.8, prompt);
        iw.setParam(Window.DEDICATED, true);
        group = ed.makeAtomic(tw, iw, "agent").setFixedSize(1, 5);
        prompt.addChangeListener((b, a, oe, ne) -> resizeInput());
        ed.selectWindow(iw);
        Buffer scratch = ed.buffer("*scratch*");
        if (scratch != null) ed.killBuffer(scratch);

        view = new TranscriptView(transcript, new Blocks.Ctx() {
            @Override public String fullResult(String id) { return fullResults.get(id); }
            @Override public SubRun subRun(String id) { return findRun(id); }
            @Override public List<GalleryImage> images(String owner) { return imagesFor(owner); }
            @Override public void enterChild(Editor e, String runId) { enterRun(runId); }
            @Override public void openImage(Editor e, GalleryImage g) { AgentWorkbench.this.openImage(g); }
            @Override public int width() { return Math.max(20, ed.textSize(tw)[0]); }
            @Override public void openLink(Editor e, String url) { AgentWorkbench.this.openLink(url); }
            @Override public Long toolMs(String id) { return AgentWorkbench.this.toolMs(id); }
            @Override public String patchPreview(String id) { return id == null ? null : previews.get(id); }
            @Override public String rel(String path) { return relPath(path); }
        });
        approval = new ApprovalPanel(this, ed);
        question = new QuestionPanel(this, ed);
        list = new ListPanel(this, ed);
        slash = new SlashCommands(this);

        installCommands();
        installKeys();
        installCompletion();
        ed.hooks().add(Hooks.POST_COMMAND, x -> afterCommand());
        ed.hooks().add(Hooks.RESIZE, x -> { resizeInput(); if (view != null) requestRefresh(); });
        if (opts.persistHistory) loadHistory();

        if (Boolean.TRUE.equals(opts.sidePanel)) toggleSide();
        root = rt.newSession(opts.agent);
        viewing = root;
        root.addListener(listener);
        banner();
        refresh();
    }

    /** 在 ed 上搭好工作台（开一段新会话）。 */
    public static AgentWorkbench install(Editor ed, AgentRuntime rt, Options opts) {
        return new AgentWorkbench(ed, rt, opts == null ? new Options() : opts);
    }

    /** 当前主会话。 */
    public AgentSession session() { return root; }

    /** 记录区正在显示的会话（主会话或走进去的子会话）。 */
    public AgentSession viewing() { return viewing; }

    public Editor editor() { return ed; }

    String rootId() { return root.id(); }

    /** 退出前：存盘、放下会话。 */
    public void shutdown() {
        AgentSession s = root;
        if (s == null) return;
        s.removeListener(listener);
        try { rt.save(s); } catch (RuntimeException ignored) { }
        try { rt.closeSession(s); } catch (RuntimeException ignored) { }
        saveHistory();
    }

    // ══ 事件 ═════════════════════════════════════════════════════════════

    private void onEvent(AgentSession s, SessionEvent e) {
        if (e instanceof SessionEvent.ToolResult r && r.toolUseId() != null) fullResults.put(r.toolUseId(), Block.joinText(r.content()));
        switch (e) {
            case SessionEvent.Started st -> ed.post(() -> { errorLine = null; turnStartedAt = System.currentTimeMillis(); startTicker(); });
            case SessionEvent.ApprovalRequested g -> ed.post(() -> approval.show(g.request()));
            case SessionEvent.ApprovalResolved g -> ed.post(() -> { if (approval.request() != null && approval.request().id().equals(g.request().id())) approval.hide(); });
            case SessionEvent.Question q -> ed.post(() -> question.show(q.request()));
            case SessionEvent.QuestionAnswered q -> ed.post(question::hide);
            case SessionEvent.Retry r -> ed.post(() -> note(Msg.f("⚠ retry %d/%d (in %d ms): %s", r.attempt(), r.max(), r.waitMs(), r.message()), "warning"));
            case SessionEvent.Requeued q -> ed.post(() -> requeue(q.text()));
            case SessionEvent.Error er -> ed.post(() -> {
                errorLine = er.line() != null ? er.line() : String.valueOf(er.error());
                endOfTurn();
            });
            case SessionEvent.Finished f -> ed.post(this::endOfTurn);
            case SessionEvent.Stopped st -> ed.post(this::endOfTurn);
            case SessionEvent.Incomplete i -> ed.post(() -> note(Msg.f("⚠ not finished: %s", i.reason())
                    + (i.limit() == null ? "" : Msg.f(" (limit %d)", i.limit())), "warning"));
            case SessionEvent.PolicyAmended p -> ed.post(() -> note(Msg.f("remembered: %s", p.description()), null));
            case SessionEvent.AutoApproval a -> ed.post(() -> note(Msg.f("(%s: %s %s — %s)", a.allowed() ? Msg.t("allowed") : Msg.t("denied"),
                    a.toolName(), a.summary(), a.why()), null));
            case SessionEvent.Compacting c -> ed.post(() -> note(Msg.t("compacting the context…"), null));
            case SessionEvent.Compacted c -> ed.post(() -> note(Msg.f("compacted: %d messages folded into a %d-char summary", c.headMessages(), c.summaryChars()), null));
            case SessionEvent.Trimmed t -> ed.post(() -> note(Msg.f("(this request dropped %d/%d messages)", t.dropped(), t.total()), null));
            case SessionEvent.CacheInactive ci -> ed.post(() -> note(Msg.f("(prompt cache missed %d times in a row)", ci.rounds()), null));
            case SessionEvent.RootChanged rc -> ed.post(() -> note(Msg.f("workspace root is now %s", rc.root()), null));
            case SessionEvent.Review r -> ed.post(() -> note(Msg.f("changes %s (%d files)", r.decision().isApproved() ? Msg.t("kept") : Msg.t("reverted"), r.files()), null));
            default -> { }
        }
        requestRefresh();
    }

    private void endOfTurn() {
        storeTitle = null;
        escArmedAt = -1;
        turnStartedAt = -1;
        if (ticker != null && !root.busy()) { ticker.cancel(); ticker = null; }
    }

    private void startTicker() {
        if (ticker != null) return;
        ticker = ed.runEvery(100, 100, () -> {
            if (!root.busy() && (viewing == root || !viewing.busy())) { ticker.cancel(); ticker = null; }
            refresh();
        });
    }

    /** 线程安全：合并成 UI 线程上的一次 refresh。 */
    void requestRefresh() {
        if (refreshQueued.compareAndSet(false, true)) ed.post(() -> { refreshQueued.set(false); refresh(); });
    }

    /** 按会话现状重贴记录区（只改尾巴）、刷侧栏、对齐面板。 */
    public void refresh() {
        view.update(desired());
        {
            ApprovalRequest r = root.pendingApproval();
            if (r != null && (approval.request() == null || !approval.request().id().equals(r.id()))) approval.show(r);
            if (r == null && approval.active()) approval.hide();
            ClarifyService.Request q = root.pendingQuestion();
            if (q != null && !question.active()) question.show(q);
            if (q == null && question.active()) question.hide();
        }
        if (sw != null) renderSide();
        String bf = barFace();
        if (!bf.equals(prompt.get("line-prefix-face"))) prompt.put("line-prefix-face", bf);
        String ph = inChild() ? Msg.t("Message the main conversation…") : root.busy() ? Msg.t("Add to the conversation (⏎ queues, /steer sends now)")
                : Msg.t("Ask AgentForge to do anything, or type / for commands");
        if (!ph.equals(prompt.get("placeholder"))) prompt.put("placeholder", ph);
        resizeInput();
    }

    // ══ 记录区要显示什么 ═════════════════════════════════════════════════

    List<Entry> desired() {
        AgentSession s = viewing;
        List<Entry> out = new ArrayList<>();
        if (viewingRun != null && s == root) {
            // 打开的旧对话里的那一趟：会话对象没留下，只摆它的步骤
            SubRun run = findRun(viewingRun);
            if (run != null) out.addAll(Transcript.subSteps(run));
            if (out.isEmpty()) out.add(Blocks.Live.note(Msg.t("(this run left no record)"), null));
            return out;
        }
        List<Entry> base = s.transcript();
        trackTools(base);
        if (s == root) {
            out.add(headerCard());
            boolean fresh = base.stream().noneMatch(e -> e.role() == Entry.Role.USER) && !s.busy();
            if (fresh) out.add(tips());
            int k = 0;
            for (int i = 0; i <= base.size(); i++) {
                while (k < notes.size() && notes.get(k).anchor() <= i) out.add(notes.get(k++).entry());
                if (i < base.size()) out.add(base.get(i));
            }
            while (k < notes.size()) out.add(notes.get(k++).entry());
        } else out.addAll(base);
        SessionStatus st = s.status();
        boolean busy = st.busy();
        if (busy) {
            List<Entry> tl = s.turnLog();
            trackTools(tl);
            out.addAll(Transcript.spliceSubagents(tl, s.subLog()));
            String txt = s.streamingText();
            if (!txt.isEmpty()) out.add("thinking".equals(s.streamingKind()) ? Blocks.Live.thinking(txt) : Entry.assistant(txt));
        }
        if (s == root) {
            ApprovalRequest r = root.pendingApproval();
            if (r != null && r.toolUseId() != null && r.preview() != null && r.preview().kind() == io.ailink.agentforge.agent.tools.Preview.Kind.DIFF
                    && r.preview().text() != null) previews.put(r.toolUseId(), r.preview().text());
            if (r != null) out.add(Blocks.Live.approval(r, r.session() != null && !r.session().equals(root.id()) ? childName(r.session()) : null));
        }
        return withDecisions(Blocks.cells(pairResults(out)));
    }

    /** 审批结果那一行插在对应调用那格的后面。 */
    private List<Entry> withDecisions(List<Entry> in) {
        if (decisions.isEmpty()) return in;
        List<Entry> out = new ArrayList<>(in.size() + 4);
        for (Entry e : in) {
            out.add(e);
            List<Entry> calls = new ArrayList<>();
            if (e.meta("call") instanceof Entry c) calls.add(c);
            if (e.meta("calls") instanceof List<?> cs) for (Object o : cs) if (o instanceof Entry c) calls.add(c);
            for (Entry c : calls) {
                String d = c.toolUseId() == null ? null : decisions.get(c.toolUseId());
                if (d != null) out.add(Blocks.Live.note(d, null));
            }
        }
        return out;
    }

    /**
     * 并排的几个调用（一次回答里叫了好几件工具）在记录里是「调用、调用、结果、结果」；显示时把每个结果挪到自己的调用下面，
     * 一眼看得出哪个结果是谁的。只挪显示，不动会话。
     */
    static List<Entry> pairResults(List<Entry> in) {
        java.util.Map<String, Entry> results = new java.util.HashMap<>();
        java.util.Set<String> calls = new java.util.HashSet<>();
        for (Entry e : in) if (e.isCall() && e.toolUseId() != null && e.meta("group") == null && !e.flag("sub-step")) calls.add(e.toolUseId());
        for (Entry e : in) {
            if (e.role() == Entry.Role.TOOL && !e.isCall() && !e.flag("sub-step") && e.toolUseId() != null && calls.contains(e.toolUseId()))
                results.putIfAbsent(e.toolUseId(), e);
        }
        if (results.isEmpty()) return in;
        List<Entry> out = new ArrayList<>(in.size());
        for (Entry e : in) {
            if (e.role() == Entry.Role.TOOL && !e.isCall() && e.toolUseId() != null && results.get(e.toolUseId()) == e) continue;
            out.add(e);
            if (e.isCall() && e.toolUseId() != null && e.meta("group") == null && results.containsKey(e.toolUseId())) out.add(results.get(e.toolUseId()));
        }
        return out;
    }

    /** 工具调用的起止时刻（id ⇒ {开始, 结束}；结束 0 = 还在跑）：看着条目从「没摘要」变成「有摘要」记的，只是个大概。 */
    private final Map<String, long[]> toolTimes = new java.util.HashMap<>();

    Long toolMs(String id) {
        long[] t = id == null ? null : toolTimes.get(id);
        return t == null || t[1] == 0 ? null : t[1] - t[0];
    }

    private void trackTools(List<Entry> es) {
        long now = System.currentTimeMillis();
        for (Entry e : es) {
            if (!e.isCall() || e.toolUseId() == null || e.meta("group") != null) continue;
            if (e.meta("summary") == null) toolTimes.putIfAbsent(e.toolUseId(), new long[]{now, 0});
            else {
                long[] t = toolTimes.get(e.toolUseId());
                if (t != null && t[1] == 0) t[1] = Math.max(now, t[0] + 1);
            }
        }
    }

    List<Todo.Item> todoItems() {
        try { return TodoStore.of(root.workspace()).items(root.id()); } catch (RuntimeException e) { return List.of(); }
    }

    /** 状态行在干什么：思考 / 写 / 跑某件工具 / 等审批 …（Codex 状态行的 header）。 */
    String activity(AgentSession s) {
        return switch (s.status()) {
            case AWAITING_APPROVAL -> Msg.t("Waiting for your approval");
            case AWAITING_QUESTION -> Msg.t("Waiting for your answer");
            case COMPACTING -> Msg.t("Compacting the context");
            default -> {
                List<String> running = new ArrayList<>();
                for (Entry e : s.turnLog()) if (e.isCall() && e.meta("summary") == null) running.add(String.valueOf(e.meta("tool-name")));
                if (running.size() == 1) yield Msg.f("Running %s", running.getFirst());
                if (running.size() > 1) yield Msg.f("Waiting for %d tools", running.size());
                if (s.status() == SessionStatus.STREAMING && "text".equals(s.streamingKind())) yield Msg.t("Writing");
                yield Msg.t("Thinking");
            }
        };
    }

    /** 状态行（底栏顶上）：{@code • 思考中 (12s • Esc Esc 中断)}；动词带流光，圆点一闪一闪（Codex status indicator）。 */
    List<Seg> statusLine(AgentSession s) {
        List<Seg> out = new ArrayList<>();
        long now = ed.now();
        out.add(Seg.of(now / 600 % 2 == 0 ? "• " : "◦ ", "agent-bullet-run"));
        out.addAll(shimmer(activity(s), now));
        long secs = turnStartedAt > 0 && s == root ? Math.max(0, (System.currentTimeMillis() - turnStartedAt) / 1000) : -1;
        String hint = escArmed() ? Msg.t("Esc again to stop") : Msg.t("Esc Esc to interrupt");
        out.add(Seg.of(" (" + (secs >= 0 ? elapsed(secs) + " • " : "") + hint + ")", escArmed() ? "agent-warn" : "agent-dim"));
        return out;
    }

    /** Codex 的流光：一道亮带从左扫到右（2 秒一趟），离亮带越近的字越亮（5 档 face）。 */
    static List<Seg> shimmer(String text, long now) {
        int[] cps = text.codePoints().toArray();
        int pad = 10, period = cps.length + pad * 2;
        double pos = (now % 2000) / 2000.0 * period;
        List<Seg> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int runLevel = -1;
        for (int i = 0; i < cps.length; i++) {
            double d = Math.abs(i + pad - pos);
            double t = d <= 5 ? 0.5 * (1 + Math.cos(Math.PI * d / 5)) : 0;
            int lv = (int) Math.round(t * 4);
            if (lv != runLevel && !run.isEmpty()) { out.add(Seg.of(run.toString(), "agent-shimmer-" + runLevel)); run.setLength(0); }
            runLevel = lv;
            run.appendCodePoint(cps[i]);
        }
        if (!run.isEmpty()) out.add(Seg.of(run.toString(), "agent-shimmer-" + runLevel));
        return out;
    }

    /** 12s / 1m 05s / 1h 02m 03s（Codex fmt_elapsed_compact）。 */
    static String elapsed(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return String.format(java.util.Locale.ROOT, "%dm %02ds", secs / 60, secs % 60);
        return String.format(java.util.Locale.ROOT, "%dh %02dm %02ds", secs / 3600, secs % 3600 / 60, secs % 60);
    }

    void note(String text, String face) {
        notes.add(new Note(root.transcript().size(), Blocks.Live.note(text, face)));
        requestRefresh();
    }

    /** 启动时要说的：命令行那层的提示、库的警告、没 key / 没模型时怎么配（会话头卡片和提示每次现拼，见 {@link #desired()}）。 */
    private void banner() {
        for (String n : opts.notes) notes.add(new Note(0, Blocks.Live.note(n, null)));
        if (rt.storeWarning() != null) notes.add(new Note(0, Blocks.Live.note("⚠ " + rt.storeWarning(), "warning")));
        Problem p = rt.problem();
        if (p != null) notes.add(new Note(0, Blocks.Live.note(problemGuide(p), "warning")));
    }

    /**
     * 会话头卡片（Codex 的 session header）：圆角框里 {@code >_ AgentForge (v…)}、模型 · 思考档（{@code /model 切换}）、目录、权限、沙盒、
     * 非默认 agent。现拼（模型 / 权限变了就跟着变）。
     */
    Entry headerCard() {
        int width = Math.max(30, ed.textSize(tw)[0]);
        List<List<Seg>> body = new ArrayList<>();
        body.add(List.of(Seg.of(">_ ", "agent-dim"), Seg.of("AgentForge", "agent-title"), Seg.of(" (v" + VERSION + ")", "agent-dim")));
        body.add(List.of());
        String[] labels = {Msg.t("model:"), Msg.t("directory:"), Msg.t("permissions:"), Msg.t("sandbox:"), Msg.t("agent:")};
        int lw = 0;
        for (String l : labels) lw = Math.max(lw, Width.stringWidth(l));
        String m = root == null ? rt.currentModel() : root.model() != null ? root.model() : rt.currentModel();
        List<Seg> model = new ArrayList<>(List.of(Seg.of(Width.padRight(labels[0], lw) + " ", "agent-dim")));
        if (m == null) model.add(Seg.of(Msg.t("(none selected)"), "agent-warn"));
        else {
            model.add(Seg.of(m));
            String th = root == null ? null : root.thinking();
            if (th != null) model.add(Seg.of(" " + th, "agent-accent"));
        }
        model.add(Seg.of("   " + Msg.t("/model to change"), "agent-dim"));
        body.add(model);
        body.add(List.of(Seg.of(Width.padRight(labels[1], lw) + " ", "agent-dim"), Seg.of(homeRelative(String.valueOf(rt.workspaceRoot())))));
        body.add(List.of(Seg.of(Width.padRight(labels[2], lw) + " ", "agent-dim"), Seg.of(rt.permissionSettings().describe())));
        String sb = rt.sandboxStatus();
        if (sb != null) body.add(List.of(Seg.of(Width.padRight(labels[3], lw) + " ", "agent-dim"),
                Seg.of(sb.replaceFirst("^[^:：]{1,16}[:：]\\s*", ""), rt.sandbox().active() ? null : "agent-warn")));
        String agent = root == null ? opts.agent : root.agent();
        if (agent != null && !agent.equals("main"))
            body.add(List.of(Seg.of(Width.padRight(labels[4], lw) + " ", "agent-dim"), Seg.of(agent, "agent-magenta")));
        int inner = 0;
        for (List<Seg> r : body) inner = Math.max(inner, Seg.width(r));
        inner = Math.min(inner + 2, width - 2);
        List<List<Seg>> rows = new ArrayList<>();
        rows.add(List.of(Seg.of("╭" + "─".repeat(inner) + "╮", "agent-box")));
        StringBuilder raw = new StringBuilder();
        for (List<Seg> r : body) {
            List<Seg> line = new ArrayList<>();
            line.add(Seg.of("│ ", "agent-box"));
            int used = 0;
            for (Seg x : r) {
                String t = x.text();
                int room = inner - 2 - used;
                if (room <= 0) break;
                if (Width.stringWidth(t) > room) t = Width.ellipsize(t, room);
                line.add(Seg.of(t, x.face()));
                used += Width.stringWidth(t);
                raw.append(t);
            }
            raw.append('\n');
            line.add(Seg.of(" ".repeat(Math.max(0, inner - 1 - used)) + "│", "agent-box"));
            rows.add(line);
        }
        rows.add(List.of(Seg.of("╰" + "─".repeat(inner) + "╯", "agent-box")));
        return Blocks.Live.lines(rows, raw.toString().stripTrailing());
    }

    /** 还没说过话时卡片下面那几行：怎么开始、几条常用命令、上次的对话。 */
    Entry tips() {
        List<List<Seg>> rows = new ArrayList<>();
        rows.add(List.of(Seg.of("  " + Msg.t("To get started, describe a task or try one of these commands:"), "agent-dim")));
        rows.add(List.of());
        String[][] cmds = {{"/model", Msg.t("choose the model and thinking level")}, {"/permissions", Msg.t("choose what AgentForge may do")},
                           {"/sessions", Msg.t("pick up an earlier conversation")}, {"/help", Msg.t("all commands and keys")}};
        for (String[] c : cmds) rows.add(List.of(Seg.of("  "), Seg.of(c[0], "agent-title"), Seg.of(" - " + c[1], "agent-dim")));
        try {
            List<SessionStore.Conversation> recent = rt.conversations(1);
            if (!recent.isEmpty()) {
                SessionStore.Conversation c = recent.getFirst();
                rows.add(List.of());
                rows.add(List.of(Seg.of("  " + Msg.t("Last conversation: "), "agent-dim"), Seg.of("#" + c.id() + " " + (c.title().isEmpty() ? Msg.t("(untitled)") : c.title())),
                        Seg.of("  " + TIME.format(Instant.ofEpochMilli(c.updated())) + " · C-c l", "agent-dim")));
            }
        } catch (RuntimeException ignored) { }
        StringBuilder raw = new StringBuilder();
        for (List<Seg> r : rows) { for (Seg x : r) raw.append(x.text()); raw.append('\n'); }
        return Blocks.Live.lines(rows, raw.toString().stripTrailing());
    }

    /** 工作区里的路径写成相对的（不在里面就照 ~ 缩写）。 */
    String relPath(String path) {
        if (path == null) return "";
        try {
            String rootDir = String.valueOf(root.workspace().root());
            if (path.startsWith(rootDir + "/")) return path.substring(rootDir.length() + 1);
        } catch (RuntimeException ignored) { }
        return homeRelative(path);
    }

    static String homeRelative(String path) {
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty() && (path.equals(home) || path.startsWith(home + "/"))) return "~" + path.substring(home.length());
        return path;
    }

    private static String readVersion() {
        try (var in = AgentWorkbench.class.getResourceAsStream("version.txt")) {
            String v = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            return v.isEmpty() || v.startsWith("$") ? "dev" : v;
        } catch (IOException e) {
            return "dev";
        }
    }

    String problemGuide(Problem p) {
        StringBuilder sb = new StringBuilder("⚠ ").append(p.message());
        switch (p.code()) {
            case NO_PROVIDERS, NO_KEYS -> sb.append("\n  ").append(Msg.t(
                    "Set ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN, plus ANTHROPIC_BASE_URL for a gateway) / OPENAI_API_KEY and restart, "
                    + "or M-x agent-set-key to store a key; then C-c m (or /model) to pick a model."));
            case NO_DEFAULT, UNKNOWN_DEFAULT -> sb.append("\n  ").append(Msg.t("C-c m (or /model) to pick a model."));
            case NO_CATALOG, NO_MODELS -> sb.append("\n  ").append(Msg.t("M-x agent-refresh-catalog to fetch the model catalog (needs network)."));
            default -> { }
        }
        return sb.toString();
    }

    // ══ 发送 / 输入框 ════════════════════════════════════════════════════

    void send() {
        String text = prompt.contents().strip();
        if (text.isEmpty()) { errorLine = null; return; }
        if (text.startsWith("/") && !text.startsWith("//")) {
            addHistory(text);
            clearPrompt();
            slash.run(text);
            return;
        }
        if (text.startsWith("//")) text = text.substring(1);
        sendText(text, true);
    }

    /** 发一句话给主会话（fromPrompt：成功后清输入框、记历史）。 */
    void sendText(String text, boolean fromPrompt) {
        leaveChild();
        if (opts.requireModel && root.model() == null) {
            Problem p = rt.problem();
            note(p != null ? problemGuide(p) : Msg.t("⚠ no model selected — C-c m to pick one"), "warning");
            return;
        }
        if (fromPrompt) { addHistory(text); clearPrompt(); }
        errorLine = null;
        AgentSession.SendResult r = root.send(text);
        if (r == AgentSession.SendResult.SENT && turnStartedAt < 0) { turnStartedAt = System.currentTimeMillis(); startTicker(); }
        toEnd();
        requestRefresh();
    }

    void clearPrompt() {
        prompt.setContents("");
        prompt.undo().clear();
        histIdx = -1;
    }

    private void requeue(String text) {
        if (text == null || text.isBlank()) return;
        String cur = prompt.contents();
        prompt.setContents(cur.isBlank() ? text : cur.stripTrailing() + "\n" + text);
        iw.setPoint(prompt.length());
    }

    boolean escArmed() { return escArmedAt > 0 && System.currentTimeMillis() - escArmedAt <= ESC_WINDOW_MS; }

    /** Esc（coxswain M318）：提问挂着 = 不答；审批挂着 = 拒绝；选择器开着 = 收起；跑着 = 第一次上膛、5 秒内第二次停；闲着 = 清空输入。 */
    void escape(boolean fromTranscript) {
        if (question.active()) { ed.runCommand("agent-question-dismiss", null); return; }
        if (approval.active()) { ed.runCommand("agent-approval-deny", null); return; }
        if (list.active()) { list.close(); return; }
        if (root.busy()) {
            if (escArmed()) { stop(); return; }
            escArmedAt = System.currentTimeMillis();
            ed.message(Msg.t("Esc again within 5 seconds to stop this turn"));
            return;
        }
        if (fromTranscript) { focusInput(); return; }
        if (!prompt.contents().isEmpty()) clearPrompt();
        errorLine = null;
    }

    void stop() {
        escArmedAt = -1;
        AgentSession s = root;
        if (s.busy() || s.pendingApproval() != null || s.pendingQuestion() != null) {
            s.interrupt();
            ed.message(Msg.t("stopped this turn"));
        } else if (viewing != root && viewing.busy()) {
            viewing.interrupt();
        } else ed.message(Msg.t("nothing is running"));
        requestRefresh();
    }

    /** 插话：停掉这一轮，把输入框里的话（连同排着的）立刻发出去。 */
    void steerNow() {
        String text = prompt.contents().strip();
        if (!root.busy()) { if (!text.isEmpty()) send(); return; }
        clearPrompt();
        AgentSession s = root;
        s.interrupt();
        Thread.ofVirtual().name("agent-steer").start(() -> {
            try {
                if (!s.awaitIdle(Duration.ofSeconds(30))) return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ed.post(() -> {
                if (root != s) return;
                String back = prompt.contents().strip();
                String all = back.isEmpty() ? text : text.isEmpty() ? back : back + "\n" + text;
                clearPrompt();
                if (!all.isEmpty()) sendText(all, false);
            });
        });
    }

    // ══ 审批 / 提问 ══════════════════════════════════════════════════════

    void resolveApproval(ApprovalRequest r, ReviewDecision d, io.ailink.agentforge.agent.approval.ApprovalChoice choice) {
        AgentSession s = root;
        boolean ok = s.pendingApproval() != null && s.pendingApproval().id().equals(r.id()) && s.resolveApproval(d);
        if (!ok && viewing != root && viewing.pendingApproval() != null) ok = viewing.resolveApproval(d);
        if (!ok) ed.message(Msg.t("that approval is no longer pending"));
        else {
            // 审批结果留一行在记录里，紧跟在那次调用下面（Codex：✔ You approved … / ✗ You denied …）
            String what = switch (r.kind()) {
                case EXEC, EXEC_RETRY -> r.title() != null ? r.title() : String.valueOf(r.toolName());
                case PATCH -> {
                    String t = r.preview() != null && r.preview().text() != null ? Blocks.lines(r.preview().text()).stream().findFirst().orElse("") : "";
                    yield t.contains(" ") ? relPath(t.substring(t.lastIndexOf(' ') + 1)) : String.valueOf(r.toolName());
                }
                case NETWORK -> r.host() != null ? r.host() : String.valueOf(r.title());
                default -> r.toolName() != null ? r.toolName() : String.valueOf(r.title());
            };
            what = Blocks.clip(what, 80);
            String how = choice == null || choice.label() == null || choice.label().isEmpty() ? "" : "（" + choice.label() + "）";
            String line = d.kind().approved() ? "✔ " + Msg.f("You approved: %s", what) + how : "✗ " + Msg.f("You denied: %s", what) + how;
            if (r.toolUseId() != null) { decisions.put(r.toolUseId(), line); requestRefresh(); }
            else note(line, null);
        }
        if (d.kind() == ReviewDecision.Kind.ABORT && d.feedback() != null) {
            String fb = d.feedback();
            Thread.ofVirtual().name("approval-feedback").start(() -> {
                try {
                    if (s.awaitIdle(Duration.ofSeconds(30))) ed.post(() -> { if (root == s) sendText(fb, false); });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        requestRefresh();
    }

    void answerQuestion(ClarifyService.Answer a) {
        if (!root.answerQuestion(a) && viewing != root) viewing.answerQuestion(a);
        requestRefresh();
    }

    /** 面板顶替输入框。 */
    void showPanel(Buffer b, int lines) {
        if (iw.buffer() != b) ed.showBuffer(iw, b);
        if (b.get("panel-point") instanceof Integer p) iw.setPoint(p);
        ed.selectWindow(iw);
        resizeInput();
    }

    void hidePanel(Buffer b) {
        if (iw.buffer() != b) return;
        if (approval.active()) ed.showBuffer(iw, approval.buf);
        else if (question.active()) ed.showBuffer(iw, question.buf);
        else if (list.active()) ed.showBuffer(iw, list.buf);
        else ed.showBuffer(iw, prompt);
        resizeInput();
    }

    void panelPoint(Buffer b, int pos) {
        b.put("panel-point", pos);
        if (iw.buffer() == b) iw.setPoint(pos);
    }

    // ══ 布局 ═════════════════════════════════════════════════════════════

    /** 底栏高度跟内容走：输入框 1–8 行（面板按自己的行数）+ 顶上的状态 / 排队几行 + 上下衬底 + 提示行；记录区至少留 3 行。 */
    void resizeInput() {
        Buffer b = iw.buffer();
        int width = Math.max(10, ed.textSize(iw)[0] - (b == prompt ? 2 : 0));
        int rows = 0;
        for (String line : b.contents().split("\n", -1)) rows += Math.max(1, (Width.stringWidth(line) + width - 1) / width);
        int max = b == prompt ? 8 : 16;
        rows = Math.max(1, Math.min(max, rows));
        int chrome = (b.headerLine() != null ? 1 : 0) + (b.modeLine() != ModeLine.NONE ? 1 : 0) + ed.footerRows(iw) + ed.topRows(iw);
        int total = rows + chrome;
        int avail = ed.rows() - (ed.minibufferActive() ? 1 : 0);
        total = Math.min(total, Math.max(4, avail - 3));
        if (group.fixedSize() != total) group.setFixedSize(1, total);
    }

    private void afterCommand() {
        tw.setFollowTail(ed.selectedWindow() != tw);
        if (iw.buffer() == prompt && ed.selectedWindow() == iw) histIdxReset();
        resizeInput();
    }

    private String lastPromptText;

    private void histIdxReset() {
        String c = prompt.contents();
        if (lastPromptText != null && !c.equals(lastPromptText) && !"agent-history-previous".equals(ed.lastCommand())
                && !"agent-history-next".equals(ed.lastCommand()) && !"agent-previous-line".equals(ed.lastCommand())
                && !"agent-next-line".equals(ed.lastCommand())) histIdx = -1;
        lastPromptText = c;
    }

    void focusInput() {
        ed.selectWindow(iw);
        tw.setFollowTail(true);
    }

    void toEnd() {
        tw.setPoint(transcript.length());
        tw.setFollowTail(ed.selectedWindow() != tw);
    }

    void toggleSide() {
        if (sw != null) {
            Window w = sw;
            sw = null;
            ed.deleteWindow(w);
            ed.selectWindow(iw);
            if (root != null) requestRefresh();
            return;
        }
        sw = ed.splitWindow(tw, Split.Dir.RIGHT, 0.7, side);
        sw.setParam(Window.NO_OTHER_WINDOW, true).setParam(Window.DEDICATED, true);
        ((Split) sw.parent()).setFixedSize(1, Math.max(24, Math.min(40, ed.cols() / 4)));
        ed.selectWindow(iw);
        if (root != null) { renderSide(); requestRefresh(); }
    }

    private void renderSide() {
        List<List<Seg>> rows = new ArrayList<>();
        rows.add(List.of());
        List<Todo.Item> todo = todoItems();
        if (!todo.isEmpty()) {
            long done = todo.stream().filter(t -> t.status() == Todo.Status.DONE).count();
            rows.add(List.of(Seg.of(" " + Msg.f("Todo %d/%d", done, todo.size()), "agent-side-title")));
            for (Todo.Item t : todo) {
                String[] lk = Blocks.todoLook(t.status());
                String tf = switch (t.status()) {
                    case DONE -> "agent-side-strike";
                    case DOING -> "agent-side-bold";
                    case PENDING -> "agent-side";
                };
                String mf = switch (t.status()) {
                    case DONE -> "agent-side-ok";
                    case DOING -> "agent-side-warn";
                    case PENDING -> "agent-side-dim";
                };
                rows.add(List.of(Seg.of(" " + lk[0], mf), Seg.of(t.text(), tf)));
            }
            rows.add(List.of());
        }
        List<SubRun> runs = root.subLog();
        if (!runs.isEmpty()) {
            rows.add(List.of(Seg.of(" " + Msg.t("Sub-agents"), "agent-side-title")));
            for (SubRun r : runs) {
                String sym = r.running() ? "◌" : r.ok() ? "⊛" : "✗";
                rows.add(List.of(Seg.of(" " + sym + " ", r.running() ? "agent-side-warn" : r.ok() ? "agent-side-ok" : "agent-side-err"),
                        Seg.of((r.who() == null ? "" : r.who() + " "), "agent-side-bold"), Seg.of(Blocks.clip(r.desc(), 40), "agent-side"),
                        Seg.of(" · " + Msg.f("%d steps", r.allSteps().size()), "agent-side-dim")));
            }
            rows.add(List.of());
        }
        McpManager m = rt.mcp();
        if (m != null) {
            List<McpServerInfo> ss = m.servers();
            if (!ss.isEmpty()) {
                rows.add(List.of(Seg.of(" MCP", "agent-side-title")));
                for (McpServerInfo i : ss) rows.add(List.of(Seg.of(" " + i.dot() + " ", i.error() == null ? "agent-side-ok" : "agent-side-err"),
                        Seg.of(i.name(), "agent-side-bold"), Seg.of(" · " + i.statusWord(), "agent-side-dim")));
                rows.add(List.of());
            }
        }
        rows.add(List.of(Seg.of(" " + Msg.t("Permissions"), "agent-side-title")));
        rows.add(List.of(Seg.of(" " + rt.permissionSettings().describe(), "agent-side")));
        String sbs = rt.sandboxStatus();
        if (sbs != null) rows.add(List.of(Seg.of(" " + sbs, rt.sandbox().active() ? "agent-side-dim" : "agent-side-warn")));
        StringBuilder want = new StringBuilder();
        for (List<Seg> r : rows) { for (Seg x : r) want.append(x.text()); want.append('\n'); }
        if (want.toString().equals(side.contents()) && rows.equals(sideRows)) return;
        sideRows = rows;
        side.clear();
        for (List<Seg> r : rows) {
            for (Seg x : r) side.append(x.text(), x.face());
            side.append("\n");
        }
    }

    private List<List<Seg>> sideRows;

    // ══ 子会话 ═══════════════════════════════════════════════════════════

    SubRun findRun(String id) {
        if (id == null) return null;
        for (SubRun r : root.subLog()) if (id.equals(r.id())) return r;
        return null;
    }

    private String childName(String sessionId) {
        AgentSession c = root.child(sessionId);
        return c == null ? sessionId : (c.agent() == null ? "" : c.agent() + " ") + (c.title() == null ? sessionId : c.title());
    }

    void enterRun(String runId) {
        SubRun run = findRun(runId);
        if (run == null) { ed.message(Msg.t("no such sub-agent run")); return; }
        AgentSession child = root.child(runId);
        if (viewing != root && viewing != child) viewing.removeListener(childListener);
        viewingRun = runId;
        if (child != null) {
            if (viewing != child) child.addListener(childListener);
            viewing = child;
            for (Message msg : child.history()) for (Block.ToolResult r : msg.toolResults()) fullResults.putIfAbsent(r.toolUseId(), Block.joinText(r.content()));
        } else viewing = root;
        view.reset();
        refresh();
        tw.setPoint(transcript.length());
        if (child != null && child.busy()) startTicker();
    }

    boolean inChild() { return viewingRun != null; }

    void leaveChild() {
        if (viewingRun == null) return;
        if (viewing != root) viewing.removeListener(childListener);
        viewing = root;
        viewingRun = null;
        view.reset();
        refresh();
        toEnd();
    }

    /** 兄弟之间走（d = ±1）。 */
    void siblingRun(int d) {
        List<SubRun> runs = root.subLog();
        if (runs.isEmpty()) { ed.message(Msg.t("no sub-agent runs in this conversation")); return; }
        int i = -1;
        for (int k = 0; k < runs.size(); k++) if (runs.get(k).id() != null && runs.get(k).id().equals(viewingRun)) i = k;
        if (i < 0) { enterLatestRun(); return; }
        int j = Math.floorMod(i + d, runs.size());
        enterRun(runs.get(j).id());
    }

    /** 进一趟：光标在组头上就进那一趟，否则进最近（优先正在跑）的那趟。 */
    void enterLatestRun() {
        if (ed.selectedWindow() == tw && "group".equals(view.blockKindAt(tw.point()))) {
            io.ailink.agentforge.tui.Overlay o = transcript.overlayAt(tw.point(), x -> x.action() != null);
            if (o != null) { o.action().accept(ed); return; }
        }
        List<SubRun> runs = root.subLog();
        if (runs.isEmpty()) { ed.message(Msg.t("no sub-agent runs in this conversation")); return; }
        SubRun pick = runs.getLast();
        for (SubRun r : runs) if (r.running()) pick = r;
        enterRun(pick.id());
    }

    // ══ 会话切换 ═════════════════════════════════════════════════════════

    void switchTo(AgentSession s, String why) {
        AgentSession old = root;
        leaveChild();
        old.removeListener(listener);
        try { rt.save(old); } catch (RuntimeException ignored) { }
        rt.closeSession(old);
        root = s;
        viewing = s;
        viewingRun = null;
        notes.clear();
        storeTitle = null;
        fullResults.clear();
        previews.clear();
        decisions.clear();
        for (Message m : s.history()) for (Block.ToolResult r : m.toolResults()) fullResults.put(r.toolUseId(), Block.joinText(r.content()));
        approval.hide();
        question.hide();
        errorLine = null;
        escArmedAt = -1;
        turnStartedAt = -1;
        s.addListener(listener);
        view.reset();
        if (why != null) notes.add(new Note(0, Blocks.Live.note(why, null)));
        refresh();
        toEnd();
    }

    void newConversation() {
        if (root.busy()) { ed.message(Msg.t("this turn is still running — Esc Esc to stop it first")); return; }
        switchTo(rt.newSession(root.agent()), Msg.t("new conversation"));
    }

    void openConversation(long id) {
        if (root.busy()) { ed.message(Msg.t("this turn is still running — Esc Esc to stop it first")); return; }
        AgentSession n = rt.openSession(id);
        switchTo(n, Msg.f("opened conversation #%d (%d messages)", id, n.history().size()));
    }

    // ══ 选择器 ═══════════════════════════════════════════════════════════

    void pickModel() {
        List<String> refs = rt.modelRefs();
        if (refs.isEmpty()) {
            Problem p = rt.problem();
            note(p != null ? problemGuide(p) : Msg.t("no models available"), "warning");
            return;
        }
        String cur = root.model();
        List<ListPanel.Item> items = new ArrayList<>();
        for (String ref : refs) {
            ModelMeta m = rt.models().meta(ref);
            StringBuilder sb = new StringBuilder();
            if (m != null && m.contextWindow() != null) sb.append(UsageReadout.human(m.contextWindow())).append(" ctx");
            if (m != null && m.thinkingLevels() != null && !m.thinkingLevels().isEmpty()) sb.append(sb.isEmpty() ? "" : " · ").append(String.join("/", m.thinkingLevels()));
            items.add(new ListPanel.Item(ref, sb.toString(), ref.equals(cur), ref));
        }
        list.open(Msg.t("Select a model"), Msg.t("Type to filter; an unknown provider/model is registered as a custom model."), items,
                it -> selectModel((String) it.value()), null, null);
        list.allowCustom(this::selectModel);
    }

    void selectModel(String ref) {
        try {
            ModelRef mr = ModelRef.parse(ref);
            String extra = "";
            if (!rt.modelRefs().contains(mr.toString())) {
                if (!rt.models().providerEnabled(mr.providerId()))
                    throw new IllegalArgumentException(Msg.f("provider \"%s\" is not enabled (or has no key)", mr.providerId()));
                rt.models().providerModel(mr.providerId(), mr.modelId(), mr.modelId(), null, null, List.of());
                extra = Msg.t(" (not in the catalog: registered as a custom model)");
            }
            rt.selectModel(mr.toString());
            if (root.model() == null) root.setModel(mr.toString());
            ed.message(Msg.f("model: %s", mr) + extra);
        } catch (RuntimeException e) {
            note("✗ " + e.getMessage(), "error");
        }
        requestRefresh();
    }

    void pickThinking() {
        List<String> lv = new ArrayList<>(root.thinkingLevels());
        if (lv.isEmpty()) { ed.message(Msg.t("this model has no thinking levels")); return; }
        String cur = root.thinking();
        List<ListPanel.Item> items = new ArrayList<>();
        items.add(new ListPanel.Item(Msg.t("default"), Msg.t("whatever the model does by default"), cur == null, null));
        for (String x : lv) items.add(new ListPanel.Item(x, null, x.equals(cur), x));
        list.open(Msg.f("Select the thinking level for %s", root.model() == null ? "" : shortModel(root.model())), null, items,
                it -> setThinking((String) it.value()), null, null);
    }

    void setThinking(String lv) {
        try {
            root.setThinking(lv);
            try { rt.models().prefs().setThinking(lv); } catch (RuntimeException ignored) { }
            ed.message(Msg.f("thinking: %s", lv == null ? Msg.t("default") : lv));
        } catch (RuntimeException e) {
            ed.messageRaw(e.getMessage());
        }
    }

    /** /agents：回主对话（在子 agent 里时）、这段对话里的各趟子 agent（RET 走进去看）、换主会话的 agent。 */
    void pickAgents() {
        List<ListPanel.Item> items = new ArrayList<>();
        if (inChild()) items.add(new ListPanel.Item(Msg.t("← back to the main conversation"), null, false, "main"));
        List<SubRun> runs = root.subLog();
        for (int i = 0; i < runs.size(); i++) {
            SubRun r = runs.get(i);
            if (r.id() == null) continue;
            String who = r.who() == null ? "" : r.who() + " · ";
            String state = r.running() ? Msg.t("running") : r.ok() ? Msg.t("done") : Msg.t("error");
            items.add(new ListPanel.Item(Msg.f("View sub-agent: %s", who + Blocks.clip(r.desc(), 50)), Msg.f("%d steps", r.allSteps().size()) + " · " + state,
                    r.id().equals(viewingRun), "run:" + r.id()));
        }
        for (AgentDef d : rt.registry().visibleDefs()) {
            String desc = d.description() == null ? "" : Blocks.clip(d.description().lines().findFirst().orElse("").replace("**", "").replace("`", ""), 60);
            String pin = d.models() != null && !d.models().isEmpty() ? "  " + Msg.f("dispatch ⇢ %s", d.models().getFirst()) : "";
            items.add(new ListPanel.Item(Msg.f("Switch agent: %s", d.name()), desc + pin, d.name().equals(root.agent()), "def:" + d.name()));
        }
        list.open(Msg.t("Agents"), inChild() ? Msg.t("You are viewing a sub-agent run.") : null, items, it -> {
            String v = (String) it.value();
            if (v.equals("main")) leaveChild();
            else if (v.startsWith("run:")) enterRun(v.substring(4));
            else selectAgent(v.substring(4));
        }, null, null);
    }

    void selectAgent(String name) {
        try {
            rt.selectAgent(root, name);
            ed.message(Msg.f("agent: %s", name));
        } catch (RuntimeException e) {
            ed.messageRaw(e.getMessage());
        }
    }

    void pickSession() {
        List<SessionStore.Conversation> cs = rt.conversations(50);
        List<ListPanel.Item> items = new ArrayList<>();
        items.add(new ListPanel.Item(Msg.t("+ start a new conversation"), null, false, -1L));
        Long cur = rt.conversationOf(root);
        for (SessionStore.Conversation c : cs)
            items.add(new ListPanel.Item("#" + c.id() + "  " + (c.title().isEmpty() ? Msg.t("(untitled)") : c.title()),
                    TIME.format(Instant.ofEpochMilli(c.updated())) + " · " + Msg.f("%d msgs", c.messages()), cur != null && cur == c.id(), c.id()));
        list.open(Msg.t("Conversations"), null, items, it -> {
            long id = (Long) it.value();
            if (id < 0) { newConversation(); return; }
            try { openConversation(id); } catch (RuntimeException e) { ed.messageRaw(String.valueOf(e.getMessage())); }
        }, null, null);
    }


    void pickPreset() {
        List<ListPanel.Item> items = new ArrayList<>();
        for (ApprovalPreset p : ApprovalPreset.values())
            items.add(new ListPanel.Item(p.id(), p.label() + " — " + p.description(), p == rt.permissionSettings().preset(), p.id()));
        list.open(Msg.t("Select a permissions preset"), Msg.f("Now: %s", rt.permissionSettings().describe()), items, it -> {
            rt.applyPreset(ApprovalPreset.parse((String) it.value()));
            ed.message(Msg.f("permissions: %s", rt.permissionSettings().describe()));
            requestRefresh();
        }, null, null);
    }

    /** /theme：主题选择器（挪到哪套就先换上看看，Esc 换回原来的）。 */
    void pickTheme() {
        String orig = ed.theme();
        List<ListPanel.Item> items = new ArrayList<>();
        for (String n : io.ailink.agentforge.tui.theme.Themes.names()) {
            var t = io.ailink.agentforge.tui.theme.Themes.find(n);
            items.add(new ListPanel.Item(n, t == null ? null : t.label(), n.equals(orig), n));
        }
        list.open(Msg.t("Select a theme"), null, items, it -> {
            ed.loadTheme((String) it.value());
            ed.message(Msg.f("theme: %s", it.value()));
        }, it -> ed.loadTheme((String) it.value()), () -> ed.loadTheme(orig));
    }

    void setKeyFlow() {
        List<Models.ProviderCandidate> cs = rt.providerCandidates();
        List<String> ids = cs.stream().map(Models.ProviderCandidate::id).toList();
        ed.readFromMinibuffer(new MinibufferRequest(Msg.t("Provider: ")).candidates(ids).requireMatch(true)
                .annotate((e, id) -> {
                    for (Models.ProviderCandidate c : cs) if (c.id().equals(id))
                        return (c.enabled() ? Msg.t("enabled") : "") + (c.hasKey() ? " · " + Msg.t("has key") : "") + "  " + c.label();
                    return "";
                })
                .onDone(id -> ed.readFromMinibuffer(new MinibufferRequest(Msg.f("API key for %s (shown while typing): ", id)).onDone(key -> {
                    if (key.isBlank()) return;
                    try {
                        if (!rt.models().providerEnabled(id)) rt.selectProvider(id, null, null);
                        rt.setKey(id, key.strip());
                        ed.message(Msg.f("key stored for %s", id));
                        if (rt.currentModel() == null) ed.runAfterCommand("agent-select-model", null);
                    } catch (RuntimeException e) {
                        note("✗ " + e.getMessage(), "error");
                    }
                    requestRefresh();
                }))));
    }

    // ══ 图片 ═════════════════════════════════════════════════════════════

    List<GalleryImage> imagesFor(String owner) {
        List<GalleryImage> out = new ArrayList<>();
        for (GalleryImage g : root.gallery()) if (owner.equals(g.owner())) out.add(g);
        return out;
    }

    /** 打开链接：有图形界面就交给 xdg-open，否则拷进 kill ring。 */
    void openLink(String url) {
        if (url == null || url.isBlank()) return;
        boolean gui = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        if (gui && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:"))) {
            try {
                new ProcessBuilder("xdg-open", url).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                ed.message(Msg.f("opened %s", url));
                return;
            } catch (IOException ignored) { }
        }
        ed.killNew(url);
        ed.message(Msg.f("link copied: %s", url));
    }

    void openImage(GalleryImage g) {
        try {
            String ext = g.mediaType() != null && g.mediaType().contains("/") ? g.mediaType().substring(g.mediaType().indexOf('/') + 1) : "bin";
            Path dir = rt.configDir().resolve("state").resolve("agent-images");
            Files.createDirectories(dir);
            Path f = dir.resolve(g.id() + "." + ext.replace("jpeg", "jpg"));
            Files.write(f, Base64.getDecoder().decode(g.data()));
            boolean opened = false;
            if (System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null) {
                try { new ProcessBuilder("xdg-open", f.toString()).redirectErrorStream(true).start(); opened = true; } catch (IOException ignored) { }
            }
            ed.message(opened ? Msg.f("opened %s", f) : Msg.f("saved to %s", f));
        } catch (IOException | IllegalArgumentException e) {
            ed.messageRaw(String.valueOf(e.getMessage()));
        }
    }

    // ══ mode line / header line ══════════════════════════════════════════

    private int width(Window w) {
        Rect r = ed.windowRects().get(w);
        return r == null ? ed.cols() : r.w() - (r.divider() ? 1 : 0);
    }

    /** 记录区往上翻了时下面还有几行（跟着尾巴 ⇒ 0）。 */
    int linesBelow() {
        int len = transcript.length();
        if (tw.point() >= len) return 0;
        int below = transcript.text().lineOf(len) - transcript.text().lineOf(tw.start()) - ed.windowHeight(tw) + 1;
        return Math.max(0, below);
    }

    /**
     * 底栏顶上那几行（输入框上面，Codex 的 status indicator + pending input preview）：和记录区之间空一行（往上翻了就写还剩几行）、
     * 跑着时的状态行、排队的话（{@code ↳ …}）、正在看子 agent 的提示；有东西时和输入框之间再空一行。
     */
    private List<List<Seg>> bottomRows(Window w) {
        List<List<Seg>> rows = new ArrayList<>();
        if (root == null) return rows;
        int below = linesBelow();
        rows.add(below > 0 ? List.of(Seg.of("  ↓ " + Msg.f("%d more lines below · PgDn", below), "agent-dim")) : List.of());
        AgentSession s = viewing != null && viewing != root && viewing.busy() ? viewing : root;
        boolean any = false;
        if (s.busy()) { rows.add(statusLine(s)); any = true; }
        List<String> q = root.queue();
        if (!q.isEmpty()) {
            if (any) rows.add(List.of());
            rows.add(List.of(Seg.of("• ", "agent-bullet"), Seg.of(Msg.t("Queued — merged in before the next model call"), "agent-title"),
                    Seg.of("  /steer " + Msg.t("sends now"), "agent-dim")));
            int width = width(w) - 6;
            for (String t : q) {
                List<String> ls = Blocks.lines(t);
                rows.add(List.of(Seg.of("  ↳ ", "agent-dim"), Seg.of(Width.ellipsize(ls.isEmpty() ? "" : ls.getFirst(), Math.max(8, width)), "agent-reasoning"),
                        Seg.of(ls.size() > 1 ? Msg.f(" (+%d lines)", ls.size() - 1) : "", "agent-dim")));
            }
            any = true;
        }
        if (inChild()) {
            if (any) rows.add(List.of());
            List<SubRun> runs = root.subLog();
            int i = 0;
            for (int k = 0; k < runs.size(); k++) if (viewingRun.equals(runs.get(k).id())) i = k + 1;
            SubRun r = findRun(viewingRun);
            List<Seg> h = new ArrayList<>(List.of(Seg.of("• ", "agent-magenta"), Seg.of(Msg.f("Viewing sub-agent %d/%d", i, runs.size()), "agent-title")));
            StringBuilder sb = new StringBuilder();
            if (r != null && r.who() != null) sb.append(" · ").append(r.who());
            String model = viewing != root && viewing.model() != null ? viewing.model() : r != null ? r.model() : null;
            if (model != null) sb.append(" · ").append(shortModel(model));
            if (r != null && !r.desc().isEmpty()) sb.append(" · ").append(Blocks.clip(r.desc(), 40));
            h.add(Seg.of(sb.toString(), "agent-dim"));
            h.add(Seg.of("   ← → " + Msg.t("siblings") + " · /agents " + Msg.t("back to main"), "agent-dim"));
            rows.add(h);
            any = true;
        }
        if (any) rows.add(List.of());
        return rows;
    }

    /** 输入框竖条（› 那格）的样子：跑着时压暗。 */
    private String barFace() { return root != null && root.busy() ? "agent-input-busy-bar" : "agent-input-bar"; }

    /** 底栏最后一行左边：看场合的键位提示（键正常色、说明暗色）。 */
    private List<Seg> hintSegs(ModeLine.Ctx c) {
        String[][] ks;
        if (escArmed()) return List.of(Seg.of("  "), Seg.of("Esc", "agent-footer-key"), Seg.of(" " + Msg.t("again within 5 seconds to stop this turn"), "agent-warn"));
        if (ed.selectedWindow() == tw) ks = new String[][]{{"TAB", Msg.t("fold")}, {"n/p", Msg.t("blocks")}, {"⏎", Msg.t("enter / open")},
                {"M-w", Msg.t("copy")}, {"C-s", Msg.t("search")}, {"q", Msg.t("back to input")}};
        else if (inChild()) ks = new String[][]{{"← →", Msg.t("siblings")}, {"/agents", Msg.t("back to main")}, {"⏎", Msg.t("sends to main")}};
        else if (root.busy()) ks = new String[][]{{"⏎", Msg.t("queue")}, {"/steer", Msg.t("interrupt & send")}, {"Esc Esc", Msg.t("interrupt")},
                {"PgUp/PgDn", Msg.t("scroll")}};
        else ks = new String[][]{{"⏎", Msg.t("send")}, {"⌃J", Msg.t("newline")}, {"/", Msg.t("commands")}, {"@", Msg.t("files")},
                {"↑", Msg.t("history")}, {"⌃X o", Msg.t("transcript")}};
        int room = width(c.window()) - Seg.width(usageSegs(c)) - 4;
        return keyHints(room, ks);
    }

    /** 底栏最后一行右边：模型 · 思考档 · agent（非默认）· 上下文剩余 · 用量（窄了从后往前丢）。 */
    private List<Seg> usageSegs(ModeLine.Ctx c) {
        if (root == null) return List.of();
        AgentSession s = viewing;
        List<Seg> out = new ArrayList<>();
        String m = s.model();
        out.add(Seg.of(m == null ? Msg.t("no model") : shortModel(m), m == null ? "agent-warn" : "agent-footer"));
        if (s.thinking() != null) out.add(Seg.of(" " + s.thinking(), "agent-footer"));
        String agent = root.agent();
        if (agent != null && !agent.equals("main")) out.add(Seg.of(" · " + agent, "agent-magenta"));
        List<String> att = root.attachments();
        if (!att.isEmpty()) out.add(Seg.of(" · ⧉ " + (att.size() == 1 ? att.getFirst() : Msg.f("%d attachments", att.size())), "link"));
        int room = Math.max(0, width(c.window()) / 2 - Seg.width(out) - 3);
        UsageReadout.Parts p = UsageReadout.parts(s.usage(), s.lastInput(), s.contextRatio(), s.cost(), s.costPartial());
        List<Seg> u = p.fit(room);
        if (!u.isEmpty()) {
            out.add(Seg.of(" · ", "agent-footer"));
            for (Seg x : u) out.add(Seg.of(x.text(), x.face() == null ? "agent-footer" : ctxFace(x.face())));
        }
        out.add(Seg.of(" "));
        return out;
    }

    private static String ctxFace(String f) {
        return switch (f) {
            case "success" -> "agent-ctx-ok";
            case "warning" -> "agent-ctx-warn";
            case "error" -> "agent-ctx-err";
            default -> f;
        };
    }

    /** 一行键位提示：键正常色、说明暗色；放不下的从后往前丢。 */
    static List<Seg> keyHints(int width, String[][] ks) {
        List<Seg> out = new ArrayList<>();
        out.add(Seg.of("  "));
        for (String[] k : ks) {
            List<Seg> one = List.of(Seg.of(k[0], "agent-footer-key"), Seg.of(" " + k[1] + "   ", "agent-footer"));
            if (Seg.width(out) + Seg.width(one) - 3 > width) break;
            out.addAll(one);
        }
        return out;
    }

    int panelWidth() { return width(iw); }

    static String shortModel(String m) {
        int i = m.indexOf('/');
        return i < 0 ? m : m.substring(i + 1);
    }

    // ══ 命令 / 键 ════════════════════════════════════════════════════════

    private void cmd(String name, String doc, io.ailink.agentforge.tui.Command fn) { ed.defineCommand(name, doc, fn); }

    private void installCommands() {
        cmd("agent-send", "发送输入框里的话（忙时排队；/ 开头是命令）。", e -> send());
        cmd("agent-stop", "停掉这一轮（摆着的审批 / 提问一起作废）。", e -> stop());
        cmd("agent-escape", "Esc：提问 = 不答、审批 = 拒绝、跑着 = 按两次停、闲着 = 清空输入。", e -> escape(e.selectedWindow() == tw));
        cmd("agent-escape-double", "快速双击 Esc：跑着就停，闲着清空输入。", e -> {
            if (root.busy() || approval.active() || question.active()) stop(); else escape(e.selectedWindow() == tw);
        });
        cmd("agent-interrupt", "C-c C-c：跑着就停这一轮；闲着提示怎么退出。", e -> {
            if (root.busy() || approval.active() || question.active()) stop();
            else e.message(Msg.t("C-x C-c (or /quit) to quit"));
        });
        cmd("agent-steer-now", "插话（/steer）：停掉这一轮，把输入框里的话（连同排着的）立刻发出去。", e -> steerNow());
        cmd("agent-select-model", "选模型（C-c m、/model）。", e -> pickModel());
        cmd("agent-select-thinking", "选思考档位（C-c k、/thinking）。", e -> pickThinking());
        cmd("agent-select-agent", "agent 选择器（/agents）：换 agent、看子 agent、回主对话。", e -> pickAgents());
        cmd("agent-sessions", "对话列表（C-c l、/sessions；第一项是开一段新的）。", e -> pickSession());
        cmd("agent-new-session", "开一段新对话（/new）。", e -> newConversation());
        cmd("agent-permissions", "选权限预设（C-c p、/permissions；审批 × 沙盒）。", e -> pickPreset());
        cmd("agent-set-key", "给某家 provider 存一个 API key（存进加密凭据库）。", e -> setKeyFlow());
        cmd("agent-refresh-catalog", "从 models.dev 刷新模型目录（联网）。", e -> {
            ed.message(Msg.t("refreshing the model catalog…"));
            Thread.ofVirtual().start(() -> {
                boolean ok = rt.refreshCatalog();
                ed.post(() -> { ed.message(ok ? Msg.t("catalog refreshed") : Msg.t("catalog refresh failed")); requestRefresh(); });
            });
        });
        cmd("agent-compact", "现在压缩上下文（/compact）。", e -> slash.run("/compact"));
        cmd("agent-toggle-focus", "C-x o：记录区 ↔ 输入框（回输入框时记录区回到最新）。", e -> {
            if (e.selectedWindow() == tw) { focusInput(); toEnd(); }
            else { e.selectWindow(tw); tw.setFollowTail(false); }
        });
        cmd("agent-goto-latest", "记录区回到最新（重新跟着新内容走）。", e -> toEnd());
        cmd("agent-focus-input", "回到输入框。", e -> focusInput());
        cmd("agent-toggle-side", "收展侧栏（/side：待办 / 子 agent / MCP / 权限）。", e -> toggleSide());
        cmd("agent-sub-up", "回主对话。", e -> leaveChild());
        cmd("agent-sub-down", "走进子会话（光标所在的组，或最近那趟）。", e -> enterLatestRun());
        cmd("agent-sub-prev", "上一趟子 agent。", e -> siblingRun(-1));
        cmd("agent-sub-next", "下一趟子 agent。", e -> siblingRun(1));
        cmd("agent-copy-block", "M-w：有选区复制选区，否则复制光标所在那一块的原文（输入框里 = 最后一段回答）。", e -> copyBlock(e));
        cmd("agent-slash-menu", "弹出 / 命令表。", e -> {
            focusInput();
            if (iw.buffer() != prompt) return;
            clearPrompt();
            e.insert("/");
            e.startCompletion(false);
        });
        cmd("agent-help", "agent 键位与命令一览（/help）。", e -> slash.run("/help"));
        cmd("agent-history-previous", "上一条发过的话。", e -> history(1));
        cmd("agent-history-next", "下一条发过的话。", e -> history(-1));
        cmd("agent-previous-line", "↑：上一行；已经在首行就翻历史。", e -> {
            int p = e.point();
            if (e.lineMove(-1)) { e.goTo(p); history(1); }
        });
        cmd("agent-next-line", "↓：下一行；已经在尾行就翻历史。", e -> {
            int p = e.point();
            if (e.lineMove(1)) { e.goTo(p); history(-1); }
        });
        cmd("agent-scroll-up", "PgUp：记录区往上翻一屏。", e -> scrollTranscript(-1));
        cmd("agent-scroll-down", "PgDn：记录区往下翻一屏（到底就重新跟尾）。", e -> scrollTranscript(1));
        cmd("agent-left", "←：子会话里、输入框空着时 = 上一趟；否则移光标。", e -> {
            if (inChild() && prompt.contents().isEmpty()) siblingRun(-1); else e.runCommand("backward-char", null);
        });
        cmd("agent-right", "→：子会话里、输入框空着时 = 下一趟；否则移光标。", e -> {
            if (inChild() && prompt.contents().isEmpty()) siblingRun(1); else e.runCommand("forward-char", null);
        });
        cmd("agent-quit", "退出（这一轮在跑时先问一句）。", e -> quit());
        cmd("save-buffers-kill-terminal", "C-x C-c：退出 AgentForge。", e -> quit());
    }

    void quit() {
        if (root.busy()) ed.yOrN(Msg.t("This turn is still running. Quit anyway?"), y -> { if (y) ed.quit(); });
        else ed.quit();
    }

    private void copyBlock(Editor e) {
        if (e.regionActive()) { e.runCommand("kill-ring-save", null); return; }
        String raw = e.currentBuffer() == transcript ? view.rawAt(e.point()) : view.lastAssistantRaw();
        if (raw == null) { e.message(Msg.t("no block here")); return; }
        e.killNew(raw);
        e.message(Msg.f("copied %d chars", raw.length()));
    }

    private void scrollTranscript(int dir) {
        int h = Math.max(1, ed.windowHeight(tw) - 2);
        ed.scrollWindow(tw, dir * h);
        if (dir > 0 && tw.start() + 1 >= transcript.length() - 1) toEnd();
        else if (dir > 0) {
            int lines = transcript.text().lineOf(transcript.length()) - transcript.text().lineOf(tw.start());
            if (lines < ed.windowHeight(tw)) toEnd();
        }
    }

    /**
     * 键（精简过，Codex 那样大半事情交给 / 命令）：全局只留 {@code C-x C-c} 退出、{@code C-x o} 换焦点、{@code C-c C-c} 停、
     * {@code C-c m / k / p / l} 四个选择器；其余前缀键一概不要。
     */
    private void installKeys() {
        Keymap g = ed.globalMap();
        g.define("C-x o", "agent-toggle-focus");
        String[][] leader = {{"m", "agent-select-model"}, {"k", "agent-select-thinking"}, {"p", "agent-permissions"}, {"l", "agent-sessions"}};
        for (String[] b : leader) g.define(LEADER + " " + b[0], b[1]);
        g.prefixName(LEADER, "agent");
        g.define("C-c C-c", "agent-interrupt");
        ed.minibufferMap().define("ESC", "abort-minibuffers");

        promptMode.keymap()
                .define("RET", "agent-send").define("C-j", "newline").define("M-RET", "newline")
                .define("M-p", "agent-history-previous").define("M-n", "agent-history-next")
                .define("<up>", "agent-previous-line").define("<down>", "agent-next-line")
                .define("C-p", "agent-previous-line").define("C-n", "agent-next-line")
                .define("<left>", "agent-left").define("<right>", "agent-right")
                .define("<prior>", "agent-scroll-up").define("<next>", "agent-scroll-down")
                .define("M-v", "agent-scroll-up").define("C-v", "agent-scroll-down")
                .define("ESC", "agent-escape").define("M-ESC", "agent-escape-double")
                .define("TAB", "completion-at-point").define("M-w", "agent-copy-block");

        transcriptMode.keymap()
                .define("q", "agent-focus-input").define("M-w", "agent-copy-block")
                .define("ESC", "agent-escape").define("M-ESC", "agent-escape-double")
                .define("M-n", "next-block").define("M-p", "previous-block")
                .define("<left>", "agent-left").define("<right>", "agent-right");
    }

    // ══ 历史 ═════════════════════════════════════════════════════════════

    private void addHistory(String text) {
        List<String> h = ed.history("agent-prompt");
        h.remove(text);
        h.addFirst(text);
        while (h.size() > 500) h.removeLast();
        histIdx = -1;
    }

    private void history(int d) {
        List<String> h = ed.history("agent-prompt");
        if (h.isEmpty()) return;
        int i = Math.max(-1, Math.min(h.size() - 1, histIdx + d));
        if (i == histIdx) return;
        histIdx = i;
        prompt.setContents(i < 0 ? "" : h.get(i));
        iw.setPoint(prompt.length());
        lastPromptText = prompt.contents();
    }

    private void loadHistory() {
        historyFile = rt.configDir().resolve("state").resolve("tui-history");
        try {
            if (!Files.isRegularFile(historyFile)) return;
            List<String> h = ed.history("agent-prompt");
            for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
                if (!line.isEmpty()) h.add(line.replace("\\n", "\n").replace("\\\\", "\\"));
            }
        } catch (IOException | RuntimeException ignored) { }
    }

    private void saveHistory() {
        if (historyFile == null) return;
        try {
            Files.createDirectories(historyFile.getParent());
            List<String> out = new ArrayList<>();
            for (String s : ed.history("agent-prompt")) out.add(s.replace("\\", "\\\\").replace("\n", "\\n"));
            Files.write(historyFile, out, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) { }
    }

    // ══ 补全：/ 命令、@ 文件 ═════════════════════════════════════════════

    private List<CompletionItem> fileCache = List.of();
    private long fileCacheAt;

    private void installCompletion() {
        ed.addCompletionSource(new CompletionSource() {
            final CompletionSource base = CompletionSource.triggered('/', true, e -> e.currentBuffer() == prompt, p -> slash.items());
            @Override public boolean applies(Editor e) { return base.applies(e); }
            @Override public int start(Editor e) { return base.start(e); }
            @Override public java.util.concurrent.CompletableFuture<List<CompletionItem>> candidates(Editor e, String prefix) { return base.candidates(e, prefix); }
            @Override public String triggerChars() { return "/"; }
            @Override public int order() { return 10; }
        });
        ed.addCompletionSource(new CompletionSource() {
            final CompletionSource base = CompletionSource.triggered('@', false, e -> e.currentBuffer() == prompt, p -> files());
            @Override public boolean applies(Editor e) { return base.applies(e); }
            @Override public int start(Editor e) { return base.start(e); }
            @Override public java.util.concurrent.CompletableFuture<List<CompletionItem>> candidates(Editor e, String prefix) { return base.candidates(e, prefix); }
            @Override public String triggerChars() { return "@"; }
            @Override public int order() { return 11; }
            @Override public void accept(Editor e, CompletionItem item, int start) {
                e.replaceRegion(start, e.point(), item.insertText());
                if (item.insertText().endsWith("/")) e.startCompletion(false);
            }
        });
    }

    List<CompletionItem> files() {
        long now = System.currentTimeMillis();
        if (now - fileCacheAt < 10_000 && !fileCache.isEmpty()) return fileCache;
        Path rootDir = root.workspace().root();
        List<CompletionItem> out = new ArrayList<>();
        try (var s = Files.walk(rootDir, 8)) {
            s.filter(p -> !p.equals(rootDir)).filter(p -> {
                for (Path part : rootDir.relativize(p)) {
                    String n = part.toString();
                    if (n.startsWith(".") || n.equals("build") || n.equals("node_modules") || n.equals("target") || n.equals("out")) return false;
                }
                return true;
            }).limit(5000).forEach(p -> {
                String rel = rootDir.relativize(p).toString().replace('\\', '/');
                boolean dir = Files.isDirectory(p);
                out.add(new CompletionItem("@" + rel + (dir ? "/" : ""), null, dir ? Msg.t("dir") : null, "@", null, p));
            });
        } catch (IOException | RuntimeException ignored) { }
        out.sort(java.util.Comparator.comparingInt((CompletionItem c) -> c.label().split("/").length).thenComparing(CompletionItem::label));
        fileCache = out;
        fileCacheAt = now;
        return out;
    }

    // ══ 真终端入口 ═══════════════════════════════════════════════════════

    /**
     * 在真终端里跑（阻塞到退出）。退出时存盘、放下会话；终端已恢复。调用方之后关运行时并 {@code Runtime.halt}
     * （JLine 的输入线程阻塞在 read 上）。
     */
    public static void run(AgentRuntime rt, Options opts) throws IOException {
        Editor ed = new Editor(100, 30);
        Path log = rt.configDir().resolve("state").resolve("tui.log");
        try { Files.createDirectories(log.getParent()); } catch (IOException ignored) { }
        java.io.PrintStream oldErr = System.err, oldOut = System.out;
        try (java.io.PrintStream err = new java.io.PrintStream(Files.newOutputStream(log), true, StandardCharsets.UTF_8)) {
            // 日志（logback 的控制台输出走 System.out）一律进 tui.log：终端画面由 JLine 直接写 fd，不经 System.out
            System.setErr(err);
            System.setOut(err);
            ed.setErrorLogger(t -> t.printStackTrace(err));
            io.ailink.agentforge.tui.term.JLineTerminal term = new io.ailink.agentforge.tui.term.JLineTerminal();
            AgentWorkbench wb = install(ed, rt, opts);
            try {
                new io.ailink.agentforge.tui.TuiRunner(ed, term).run();
            } finally {
                wb.shutdown();
            }
        } finally {
            System.setErr(oldErr);
            System.setOut(oldOut);
        }
    }

    // 测试用读口
    TranscriptView transcriptView() { return view; }
    ApprovalPanel approvalPanel() { return approval; }
    QuestionPanel questionPanel() { return question; }
    ListPanel listPanel() { return list; }
    Window transcriptWindow() { return tw; }
    Window inputWindow() { return iw; }
    Buffer promptBuffer() { return prompt; }
}
