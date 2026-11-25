package io.ailink.agentforge.cli.agent;

import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.mcp.McpServerInfo;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.models.ModelRef;
import io.ailink.agentforge.agent.models.Problem;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.approval.ApprovalChoice;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.execpolicy.ExecPolicyManager;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.session.SessionListener;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.transcript.Transcript;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * agent 命令的 JLine REPL（最小但扎实；下一阶段照 coxswain main 分支的 TUI 整个重写）。
 *
 * <h2>线程</h2>
 * 主线程只做一件事：读一行。会话事件在 agent 的工作线程上到，一律经 {@link LineReader#printAbove} 打在提示符**上方** ——
 * 于是忙的时候照样能打字（回车 = 排队，下一次问模型前并进去），输出不会把正在打的那行冲乱。
 * 流式增量按整行吐出（{@code printAbove} 只能整行地插），落地 / 调工具 / 结束时把半行冲掉。
 *
 * <h2>审批与提问</h2>
 * 审批 / 题挂起的是 agent 线程；REPL 把提示符换成"审批 [y/p/a/d/n]"或"回答"，**下一行**就当答案（{@code d 理由} 把理由交给模型）。
 * Ctrl-C = 按停这一轮（审批、题一起作废）。
 */
final class AgentRepl {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final String PROMPT = "› ";

    private final AgentRuntime rt;
    private final List<String> startupNotes;
    private final String initialAgent;
    private Terminal terminal;
    private LineReader reader;
    /** 管道 / dumb 终端：不在提示符上方插行、不重画（那两样在 dumb 上只会打出一串重复的提示符）。 */
    private boolean plain;
    private volatile AgentSession session;
    private final Object outLock = new Object();
    private final StringBuilder pending = new StringBuilder();
    private String pendingKind;
    private volatile SessionEvent.UsageUpdated lastUsage;
    // 挂起的题：逐题作答
    private volatile ClarifyService.Request question;
    private final List<List<String>> answers = new ArrayList<>();

    AgentRepl(AgentRuntime rt, String agent, List<String> notes) {
        this.rt = rt;
        this.initialAgent = agent;
        this.startupNotes = notes;
    }

    private final SessionListener listener = (s, e) -> onEvent(e);

    int run() throws IOException {
        terminal = TerminalBuilder.builder().name("agentforge").system(true).dumb(true).build();
        reader = LineReaderBuilder.builder().terminal(terminal).appName("agentforge")
                .variable(LineReader.HISTORY_FILE, rt.configDir().resolve("state").resolve("agent-history"))
                .build();
        plain = terminal.getType() == null || terminal.getType().startsWith(Terminal.TYPE_DUMB);
        try {
            banner();
            switchTo(rt.newSession(initialAgent));
            loop();
        } finally {
            AgentSession s = session;
            if (s != null) {
                s.removeListener(listener);
                rt.closeSession(s);
            }
            terminal.close();
        }
        return 0;
    }

    // ══ 主循环 ═══════════════════════════════════════════════════════════

    private void loop() {
        while (true) {
            String line;
            try {
                line = reader.readLine(promptNow());
            } catch (UserInterruptException e) {
                AgentSession s = session;
                if (s.busy()) {
                    s.interrupt();
                    say(dim("已按停这一轮"));
                } else say(dim("（/quit 或 Ctrl-D 退出）"));
                continue;
            } catch (EndOfFileException e) {
                drainBeforeExit();
                return;
            }
            if (line == null) continue;
            if (session.pendingApproval() != null) { answerApproval(line.strip()); continue; }
            if (question != null) { answerQuestion(line.strip()); continue; }
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("/")) {
                if (!command(t)) return;
                continue;
            }
            send(t);
        }
    }

    /** 输入流到头（管道喂完）：有闸 / 题挂着就按停，否则等这一轮跑完再走 —— 不能把正在跑的一轮扔在半路。 */
    private void drainBeforeExit() {
        AgentSession s = session;
        if (s.pendingApproval() != null || question != null) s.interrupt();
        try {
            while (!s.awaitIdle(Duration.ofSeconds(1))) {
                if (s.pendingApproval() != null || question != null) s.interrupt();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String promptNow() {
        AgentSession s = session;
        if (s != null && s.pendingApproval() != null) return "审批 [" + approvalKeys(s.pendingApproval()) + "] " + PROMPT;
        if (question != null) return "回答 " + PROMPT;
        return PROMPT;
    }

    /** 提示符跟着状态换（闸 / 题摆出来、答完），在别的线程上也能调。 */
    private void refreshPrompt() {
        if (plain) return;
        try {
            if (reader instanceof org.jline.reader.impl.LineReaderImpl li) li.setPrompt(promptNow());
            if (reader.isReading()) {
                reader.callWidget(LineReader.REDRAW_LINE);
                reader.callWidget(LineReader.REDISPLAY);
            }
        } catch (RuntimeException ignored) {
            // 终端不支持重画（dumb）就算了：下一次读行时提示符自然是新的
        }
    }

    private void send(String text) {
        AgentSession s = session;
        Problem p = rt.problem();
        if (p != null && s.model() == null) {
            say(err("还不能对话：" + p.message()) + dim("（/models 看可选的，/model <ref> 选一个；key 用环境变量 ANTHROPIC_API_KEY / OPENAI_API_KEY 等）"));
            return;
        }
        AgentSession.SendResult r = s.send(text);
        if (r == AgentSession.SendResult.QUEUED) say(dim("（已排队，下一次问模型前并进去）"));
    }

    // ══ 事件 → 屏幕 ══════════════════════════════════════════════════════

    private void onEvent(SessionEvent e) {
        switch (e) {
            case SessionEvent.Delta d -> delta(d.kind(), d.text());
            case SessionEvent.Assistant a -> flush();
            case SessionEvent.ToolCall c -> {
                flush();
                say(color("⏺ ", AttributedStyle.CYAN) + bold(c.name()) + " " + dim(Transcript.argsOneLine(c.input(), 100)));
            }
            case SessionEvent.ToolResult r -> say(toolResult(r));
            case SessionEvent.ApprovalRequested g -> { flush(); refreshPrompt(); showApproval(g.request()); }
            case SessionEvent.ApprovalResolved g -> refreshPrompt();
            case SessionEvent.AutoApproval g -> say(dim("  （" + (g.allowed() ? "放行" : "拒绝") + "：" + g.toolName() + " " + g.summary() + " — " + g.why() + "）"));
            case SessionEvent.PolicyAmended g -> say(dim("  记下了：" + g.description()));
            case SessionEvent.Question q -> { flush(); showQuestion(q.request()); }
            case SessionEvent.QuestionAnswered q -> { question = null; refreshPrompt(); }
            case SessionEvent.UsageUpdated u -> lastUsage = u;
            case SessionEvent.Retry r -> say(warn("  重试 " + r.attempt() + "/" + r.max() + "（" + r.waitMs() / 1000 + " 秒后）：" + r.message()));
            case SessionEvent.Queued q -> { }
            case SessionEvent.Steered q -> say(dim("  （排队的话已并进这一轮）"));
            case SessionEvent.Requeued q -> say(dim("  （排队的话没发出去：" + q.text() + "）"));
            case SessionEvent.SubStart sr -> say(dim("  ↳ 子 agent：" + sr.run().desc()));
            case SessionEvent.SubFinish sr -> say(dim("  ↳ 子 agent 结束：" + sr.run().desc() + "（" + sr.run().status() + "）"));
            case SessionEvent.Compacting c -> say(dim("  压缩上下文中…"));
            case SessionEvent.Compacted c -> say(dim("  压缩完成：压掉 " + c.headMessages() + " 条，摘要 " + c.summaryChars() + " 字"));
            case SessionEvent.Trimmed t -> say(dim("  （这次请求裁掉了 " + t.dropped() + "/" + t.total() + " 条）"));
            case SessionEvent.Incomplete i -> { flush(); say(warn("  没做完：" + i.reason() + (i.limit() == null ? "" : "（上限 " + i.limit() + "）"))); }
            case SessionEvent.Finished f -> { flush(); say(usageLine()); }
            case SessionEvent.Stopped s -> { flush(); say(dim("  已停")); }
            case SessionEvent.Error er -> { flush(); say(err("✗ " + (er.line() != null ? er.line() : String.valueOf(er.error())))); }
            case SessionEvent.Review r -> say(dim("  改动" + (r.decision().isApproved() ? "已采用" : "已撤销") + "（" + r.files() + " 个文件）"));
            case SessionEvent.RootChanged rc -> say(dim("  工作根换到 " + rc.root()));
            case SessionEvent.CacheInactive ci -> say(dim("  （连续 " + ci.rounds() + " 次没命中 prompt cache）"));
            default -> { }
        }
    }

    /** 流式增量：攒到整行再吐；类型换了（thinking ↔ text）先把前一段冲掉。 */
    private void delta(String kind, String text) {
        synchronized (outLock) {
            if (pendingKind != null && !pendingKind.equals(kind)) flushLocked();
            pendingKind = kind;
            pending.append(text);
            int nl;
            while ((nl = pending.indexOf("\n")) >= 0) {
                String one = pending.substring(0, nl);
                pending.delete(0, nl + 1);
                printLine(styleDelta(one));
            }
        }
    }

    private void flush() { synchronized (outLock) { flushLocked(); } }

    private void flushLocked() {
        if (!pending.isEmpty()) printLine(styleDelta(pending.toString()));
        pending.setLength(0);
        pendingKind = null;
    }

    private String styleDelta(String s) { return "thinking".equals(pendingKind) ? dim(s) : s; }

    private String toolResult(SessionEvent.ToolResult r) {
        StringBuilder sb = new StringBuilder();
        for (Block b : r.content()) if (b instanceof Block.Text t) sb.append(t.text());
        String all = sb.toString().strip();
        String[] lines = all.isEmpty() ? new String[0] : all.split("\n", -1);
        String first = lines.length == 0 ? "（空）" : lines[0];
        if (first.length() > 120) first = first.substring(0, 120) + "…";
        String more = lines.length > 1 ? dim("（共 " + lines.length + " 行）") : "";
        String body = "  ⎿ " + first + " " + more;
        return r.error() ? err(body) : dim("  ⎿ ") + first + " " + more;
    }

    private String usageLine() {
        SessionEvent.UsageUpdated u = lastUsage;
        if (u == null) return dim("  ─");
        Usage last = u.last() == null ? Usage.ZERO : u.last();
        StringBuilder sb = new StringBuilder("  I ").append(human(last.totalInput())).append(" / O ").append(human(last.output()));
        if (u.contextRatio() != null) sb.append(" · M ").append(Math.round(u.contextRatio() * 100)).append("%");
        sb.append(" · C ").append(human(last.cacheRead()));
        if (u.cost() != null) sb.append(" · $").append(String.format(Locale.ROOT, "%.4f", u.cost())).append(u.costPartial() ? "+" : "");
        return dim(sb.toString());
    }

    static String human(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1000) return String.format(Locale.ROOT, "%.1fk", n / 1e3);
        return Long.toString(n);
    }

    // ══ 审批 ═════════════════════════════════════════════════════════════

    /** 提示符里的快捷键（按请求上的选项）。 */
    static String approvalKeys(ApprovalRequest r) {
        List<String> ks = new ArrayList<>();
        for (ApprovalChoice c : r.choices()) ks.add(c.key());
        return String.join("/", ks);
    }

    static String approvalHead(ApprovalRequest r) {
        return switch (r.kind()) {
            case EXEC -> "要执行命令";
            case EXEC_RETRY -> "沙盒拦下了这条命令，要在沙盒外重跑吗？";
            case PATCH -> "要改文件";
            case NETWORK -> "要访问网络";
            case TOOL -> "要调用工具";
            case SECRET -> "要碰密钥文件（进了上下文就收不回来）";
            case REVIEW -> "这一轮攒下的改动，采用吗？";
        };
    }

    private void showApproval(ApprovalRequest r) {
        String who = r.session() != null && session != null && !r.session().equals(session.id()) ? dim("（子 agent " + r.session() + "）") : "";
        say(warn("⚠ " + approvalHead(r)) + " " + bold(r.toolName() == null ? "" : r.toolName()) + who);
        if (r.title() != null) say("  " + (r.command() != null ? bold("$ " + r.title()) : r.title()));
        if (r.cwd() != null && r.command() != null) say(dim("  目录：" + r.cwd()));
        if (r.reason() != null) say(dim("  理由：" + r.reason()));
        if (r.retryReason() != null) say(dim("  " + r.retryReason()));
        if (r.preview() != null && r.preview().text() != null) {
            String[] ls = r.preview().text().split("\n", -1);
            int n = Math.min(ls.length, 30);
            for (int i = 0; i < n; i++) say(dim("  │ ") + previewLine(ls[i], r.preview().kind() == io.ailink.agentforge.agent.tools.Preview.Kind.DIFF));
            if (ls.length > n) say(dim("  │ …（还有 " + (ls.length - n) + " 行）"));
        }
        for (ApprovalChoice c : r.choices()) say(dim("  " + c.key() + "  " + c.label()));
        say(dim("  （d / n 后面可以接一句话：d 的话附给模型、它接着干；n 停下后把这句话当作你的下一句发出去）"));
    }

    private String previewLine(String l, boolean diff) {
        if (!diff) return l;
        if (l.startsWith("+") && !l.startsWith("+++")) return color(l, AttributedStyle.GREEN);
        if (l.startsWith("-") && !l.startsWith("---")) return color(l, AttributedStyle.RED);
        return l;
    }

    /** 下一行就是答案：{@code 键 [给模型的话]}。认不出就再问一遍（不替用户猜）。 */
    private void answerApproval(String in) {
        AgentSession s = session;
        ApprovalRequest r = s.pendingApproval();
        if (r == null) return;
        ReviewDecision d = parseAnswer(r, in);
        if (d == null) { say(dim("  请输入 " + approvalKeys(r))); return; }
        s.resolveApproval(d);
        // 「停下来，我来说怎么做」后面接的话：等这一轮停稳了当作下一句发出去（Codex 是让用户接着打字）
        if (d.kind() == ReviewDecision.Kind.ABORT && d.feedback() != null) {
            String fb = d.feedback();
            Thread.ofVirtual().name("approval-feedback").start(() -> {
                try {
                    if (s.awaitIdle(Duration.ofSeconds(30)) && session == s) send(fb);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    /** {@code y / p / a / d [话] / b / n [话]}（也认 yes / no / 是 / 否 / stop）；不在选项里 ⇒ null。 */
    static ReviewDecision parseAnswer(ApprovalRequest r, String in) {
        String t = in == null ? "" : in.strip();
        int sp = t.indexOf(' ');
        String key = sp < 0 ? t : t.substring(0, sp);
        String rest = sp < 0 ? null : t.substring(sp + 1).strip();
        key = switch (key) {
            case "Y", "yes", "是", "好" -> "y";
            case "no", "否" -> "d";
            case "s", "stop", "停" -> "n";
            case "A" -> "a";
            case "P" -> "p";
            default -> key;
        };
        ApprovalChoice c = r.choice(key);
        if (c == null) return null;
        return new ReviewDecision(c.decision(), c.decision() == ReviewDecision.Kind.DENIED || c.decision() == ReviewDecision.Kind.ABORT ? rest : null);
    }

    // ══ 提问（clarify）══════════════════════════════════════════════════

    private void showQuestion(ClarifyService.Request req) {
        synchronized (answers) { answers.clear(); }
        question = req;
        refreshPrompt();
        showQuestionAt(0);
    }

    private void showQuestionAt(int i) {
        ClarifyService.Request req = question;
        if (req == null || i >= req.questions().size()) return;
        ClarifyService.Question q = req.questions().get(i);
        say(warn("? ") + (q.header() == null || q.header().isEmpty() ? "" : bold("[" + q.header() + "] ")) + q.question());
        for (int k = 0; k < q.options().size(); k++) {
            ClarifyService.Option o = q.options().get(k);
            say("  " + (k + 1) + ". " + o.label() + (o.description() == null || o.description().isEmpty() ? "" : dim(" — " + o.description())));
        }
        say(dim("  输入序号" + (q.multiple() ? "（可多选，逗号分隔）" : "") + "，或直接写答案；空行跳过本题，/skip 关掉"));
    }

    private void answerQuestion(String in) {
        AgentSession s = session;
        ClarifyService.Request req = question;
        if (req == null) return;
        if (in.equals("/skip")) {
            question = null;
            s.answerQuestion(new ClarifyService.Dismissed());
            refreshPrompt();
            return;
        }
        int i;
        synchronized (answers) {
            i = answers.size();
            ClarifyService.Question q = req.questions().get(i);
            answers.add(parseChoice(in, q));
        }
        if (i + 1 < req.questions().size()) { showQuestionAt(i + 1); return; }
        List<List<String>> all;
        synchronized (answers) { all = List.copyOf(answers); }
        question = null;
        s.answerQuestion(new ClarifyService.Answered(all));
        refreshPrompt();
    }

    /** "1,3" ⇒ 那几个选项的 label；别的当自己填的答案；空 = 没答。 */
    static List<String> parseChoice(String in, ClarifyService.Question q) {
        if (in.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : in.split("[,，\\s]+")) {
            try {
                int k = Integer.parseInt(part);
                if (k < 1 || k > q.options().size()) return List.of(in);
                out.add(q.options().get(k - 1).label());
            } catch (NumberFormatException e) {
                return List.of(in);
            }
        }
        if (!q.multiple() && out.size() > 1) return List.of(out.getFirst());
        return out;
    }

    // ══ 斜杠命令 ═════════════════════════════════════════════════════════

    static final String HELP = """
            /help                 这份说明
            /model [ref]          看 / 换当前模型
            /models               可选的模型
            /agents [name]        列出 agent / 换 agent
            /new                  开一段新对话
            /sessions             最近的对话
            /open <id>            打开库里的一段对话接着聊
            /search <词>          全文检索历史（至少 3 个字）
            /compact              现在压缩上下文
            /permissions [预设]   看权限设置 / 换预设（read-only / auto / full-access）
            /approvals <策略>     审批策略：untrusted / on-failure / on-request / never / granular
            /sandbox <档>         沙盒：read-only / workspace-write / danger-full-access
            /network on|off       沙盒里能不能出网
            /rules [reload]       执行规则文件（.rules）/ 重读
            /thinking [档位]      看 / 设思考档位
            /mcp                  MCP 服务器状态
            /quit                 退出
            忙的时候直接打字 = 排队；Ctrl-C 按停这一轮
            审批时：y 放行 · a 本会话不再问 · p 以后都不再问 · d 不做接着干（可接一句话）· n 停下来""";

    /** 回 false = 退出。 */
    private boolean command(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].strip() : "";
        AgentSession s = session;
        try {
            switch (cmd) {
                case "/quit", "/exit", "/q" -> { return false; }
                case "/help", "/?" -> HELP.lines().forEach(l -> say(l));
                case "/model" -> {
                    if (arg.isEmpty()) {
                        say("当前模型：" + (s.model() == null ? dim("（未选）") : s.model()));
                        Problem p = rt.problem();
                        if (p != null) say(dim("  " + p.message()));
                    } else say(selectModel(rt, arg));
                }
                case "/models" -> {
                    List<String> refs = rt.modelRefs();
                    if (refs.isEmpty()) {
                        Problem p = rt.problem();
                        say(dim("没有可选的模型" + (p == null ? "" : "：" + p.message())));
                    }
                    for (String r : refs) say((r.equals(s.model()) ? "* " : "  ") + r);
                }
                case "/agents" -> {
                    if (arg.isEmpty()) {
                        for (AgentDef d : rt.registry().visibleDefs()) {
                            say((d.name().equals(s.agent()) ? "* " : "  ") + bold(d.name()) + dim(" — " + firstLine(d.description())));
                        }
                    } else {
                        rt.selectAgent(s, arg);
                        say("已换到 agent " + arg);
                    }
                }
                case "/new" -> {
                    if (s.busy()) { say(dim("这一轮还在跑，先 Ctrl-C 按停")); break; }
                    AgentSession n = rt.newSession(s.agent());
                    retire(s);
                    switchTo(n);
                    say(dim("新对话"));
                }
                case "/sessions" -> {
                    List<SessionStore.Conversation> cs = rt.conversations(20);
                    if (cs.isEmpty()) say(dim("库里还没有对话"));
                    for (SessionStore.Conversation c : cs) {
                        say(String.format("%5d  %s  %4d 条  %s", c.id(), TIME.format(Instant.ofEpochMilli(c.updated())), c.messages(),
                                c.title().isEmpty() ? "（无标题）" : c.title()));
                    }
                }
                case "/open" -> {
                    if (s.busy()) { say(dim("这一轮还在跑，先 Ctrl-C 按停")); break; }
                    long id = Long.parseLong(arg);
                    AgentSession n = rt.openSession(id);
                    retire(s);
                    switchTo(n);
                    say(dim("打开了对话 " + id + "（" + n.history().size() + " 条）"));
                    replayTail(n.history());
                }
                case "/search" -> {
                    List<SessionStore.SearchHit> hs = rt.search(arg, 20);
                    if (hs.isEmpty()) say(dim("没搜到"));
                    for (SessionStore.SearchHit h : hs) {
                        say(String.format("%5d#%-4d %s  %s", h.conversation(), h.seq(), dim(h.role()), h.snippet()) + dim("  — " + h.title()));
                    }
                }
                case "/compact" -> {
                    if (s.busy()) { say(dim("这一轮还在跑")); break; }
                    say(dim("压缩中…"));
                    Thread.ofVirtual().start(() -> {
                        boolean ok = s.compact();
                        if (ok) rt.save(s);
                        say(dim(ok ? "压缩完成" : "没有值得压的（或摘要没拿到），历史没动"));
                    });
                }
                case "/permissions" -> {
                    if (!arg.isEmpty()) {
                        rt.applyPreset(ApprovalPreset.parse(arg));
                    } else {
                        for (ApprovalPreset p : ApprovalPreset.values()) {
                            boolean cur = p == rt.permissionSettings().preset();
                            say((cur ? "* " : "  ") + bold(p.id()) + "  " + p.label() + dim(" — " + p.description()));
                        }
                    }
                    say("权限：" + s.permissions().settings().describe());
                }
                case "/approvals" -> {
                    if (!arg.isEmpty()) rt.setPermissionSettings(rt.permissionSettings().withApproval(ApprovalPolicy.parse(arg)));
                    say("权限：" + s.permissions().settings().describe());
                }
                case "/sandbox" -> {
                    if (!arg.isEmpty()) rt.setPermissionSettings(rt.permissionSettings().withSandbox(SandboxMode.parse(arg)));
                    say("权限：" + s.permissions().settings().describe() + dim("  ·  " + rt.sandboxStatus()));
                }
                case "/network" -> {
                    switch (arg) {
                        case "on" -> rt.setPermissionSettings(rt.permissionSettings().withNetworkAccess(true));
                        case "off" -> rt.setPermissionSettings(rt.permissionSettings().withNetworkAccess(false));
                        case "" -> { }
                        default -> { say(dim("用法：/network on|off")); return true; }
                    }
                    say("网络：" + (s.permissions().settings().networkEnabled() ? "通" : "断") + dim("（danger-full-access 总是通）"));
                }
                case "/rules" -> {
                    if (arg.equals("reload")) {
                        for (String w : rt.reloadRules()) say(warn("⚠ " + w));
                        say(dim("规则已重读"));
                    }
                    ExecPolicyManager m = s.permissions().execPolicy();
                    say("规则目录：" + rt.userRulesDir() + dim("（用户）") + "  " + AgentRuntime.projectRulesDir(s.workspace().root()) + dim("（项目）"));
                    for (java.nio.file.Path f : m.files()) say("  " + f);
                    if (m.files().isEmpty()) say(dim("  （还没有规则文件；内建规则照常生效）"));
                    say(dim("「以后都不再问」写进 " + m.amendmentFile()));
                    for (String w : m.warnings()) say(warn("⚠ " + w));
                    for (List<String> p : m.current().allowedPrefixes()) say(dim("  allow " + String.join(" ", p)));
                }
                case "/thinking" -> {
                    if (arg.isEmpty()) {
                        say("思考档位：" + (s.thinking() == null ? "默认" : s.thinking()) + dim("  可选：" + s.thinkingLevels()));
                    } else {
                        String lv = arg.equals("default") || arg.equals("off") ? null : arg;
                        s.setThinking(lv);
                        try { rt.models().prefs().setThinking(lv); } catch (RuntimeException ignored) { }
                        say("思考档位：" + (s.thinking() == null ? "默认" : s.thinking()));
                    }
                }
                case "/mcp" -> {
                    McpManager m = rt.mcp();
                    if (m == null) { say(dim("没接 MCP（--no-mcp）")); break; }
                    List<McpServerInfo> ss = m.servers();
                    if (ss.isEmpty()) say(dim("没有配置 MCP 服务器"));
                    for (McpServerInfo i : ss) {
                        say(i.dot() + " " + bold(i.name()) + "  " + i.status().name().toLowerCase(Locale.ROOT) + "  " + i.toolCount() + " 件工具"
                                + (i.error() == null ? "" : err("  " + i.error())));
                    }
                }
                default -> say(dim("不认识的命令 " + cmd + "（/help 看全部）"));
            }
        } catch (RuntimeException e) {
            say(err(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return true;
    }

    /** 选模型：目录里没有、但那一家启用着 ⇒ 当自定义模型登记（网关 / 新模型常见）。 */
    static String selectModel(AgentRuntime rt, String ref) {
        ModelRef mr = ModelRef.parse(ref);
        String note = "";
        if (!rt.modelRefs().contains(mr.toString())) {
            if (!rt.models().providerEnabled(mr.providerId())) {
                throw new IllegalArgumentException("provider \"" + mr.providerId() + "\" 没启用，选不了 " + ref + "。\n"
                        + "  · models.dev 目录里有的：设好它的 key 环境变量即可自动启用，或在 TUI 里 M-x agent-set-key；\n"
                        + "  · 自己的网关 / 自托管：写进 " + io.ailink.agentforge.agent.models.ProvidersConfig.file(rt.configDir())
                        + "，例如\n"
                        + "    {\"providers\": {\"" + mr.providerId() + "\": {\"api\": \"anthropic\", \"baseUrl\": \"http://127.0.0.1:4000\",\n"
                        + "      \"models\": [\"" + mr.modelId() + "\"]}}}\n"
                        + "    key 放环境变量 " + io.ailink.agentforge.agent.models.EnvVars.idToEnv(mr.providerId()) + "_API_KEY。");
            }
            rt.models().providerModel(mr.providerId(), mr.modelId(), mr.modelId(), null, null, List.of());
            note = "（目录里没有，按自定义模型登记）";
        }
        rt.selectModel(mr.toString());
        return "模型：" + mr + note;
    }

    private void switchTo(AgentSession n) {
        session = n;
        lastUsage = null;
        question = null;
        n.addListener(listener);
    }

    private void retire(AgentSession old) {
        old.removeListener(listener);
        rt.save(old);
        rt.closeSession(old);
    }

    /** 打开旧对话时把最后几句人话 / 回答回显一下（知道接在哪儿）。 */
    private void replayTail(List<Message> h) {
        int from = Math.max(0, h.size() - 6);
        for (int i = from; i < h.size(); i++) {
            Message m = h.get(i);
            String t = io.ailink.agentforge.agent.context.Reminder.shownText(m).strip();
            if (t.isEmpty()) continue;
            String one = t.replace('\n', ' ');
            if (one.length() > 160) one = one.substring(0, 160) + "…";
            say(dim((m.role().wire().equals("user") ? "你：" : "它：") + one));
        }
    }

    private void banner() {
        say(bold("AgentForge agent") + dim("  ·  " + rt.workspaceRoot()));
        for (String n : startupNotes) say(dim(n));
        String m = rt.currentModel();
        Problem p = rt.problem();
        say("模型：" + (m == null ? dim("（未选）") : m) + (p == null ? "" : warn("  " + p.message())));
        if (p != null && (p.code() == Problem.Code.NO_PROVIDERS || p.code() == Problem.Code.NO_KEYS)) {
            say(dim("  命令行里：设环境变量 ANTHROPIC_API_KEY（或 ANTHROPIC_AUTH_TOKEN，网关加 ANTHROPIC_BASE_URL）/ OPENAI_API_KEY 后重启，"
                    + "首次会自动启用那一家；再用 --model 或 /model 选模型"));
        }
        say("权限：" + rt.permissionSettings().describe());
        say((rt.sandbox().active() ? dim(rt.sandboxStatus()) : warn("⚠ " + rt.sandboxStatus())));
        if (rt.storeWarning() != null) say(warn("⚠ " + rt.storeWarning()));
        say(dim("/help 看命令；Ctrl-C 按停；Ctrl-D 退出"));
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        String one = i < 0 ? s : s.substring(0, i);
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    // ══ 输出 ═════════════════════════════════════════════════════════════

    private void say(String s) {
        synchronized (outLock) { printLine(s); }
    }

    private void printLine(String s) {
        if (!plain && reader != null && reader.isReading()) reader.printAbove(s);
        else {
            terminal.writer().println(s);
            terminal.writer().flush();
        }
    }

    /** 按终端能力出样式（管道 / dumb 终端上就是纯文本，不夹 ANSI 码）。 */
    private String styled(String s, AttributedStyle st) {
        return new AttributedStringBuilder().style(st).append(s).toAnsi(terminal);
    }

    String dim(String s) { return styled(s, AttributedStyle.DEFAULT.faint()); }

    String bold(String s) { return styled(s, AttributedStyle.BOLD); }

    String color(String s, int c) { return styled(s, AttributedStyle.DEFAULT.foreground(c)); }

    String err(String s) { return color(s, AttributedStyle.RED); }

    String warn(String s) { return color(s, AttributedStyle.YELLOW); }
}
