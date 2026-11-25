package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalChoice;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.MinibufferRequest;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批面板（Codex 的 approval overlay）：一道审批摆出来时顶替输入框 —— 粗体问句、理由（斜体）、命令 {@code $ …} / 文件 / 主机，
 * 下面编号的选项 {@code › 1. 是，执行 (y)}，最底下一行提示。改文件的 diff 画在记录区底部（提议的改动那格）。
 * <ul>
 *   <li>请求上的快捷键直接选（y 放行 · p 以后都 · a 本会话 · d 不做接着干 · b 以后都拦 · n 停下来）；数字 / ↑↓ + RET 同；</li>
 *   <li>d / n 选中后在 minibuffer 里问一句给模型的话（可空，RET 跳过）：d 的话附给模型、它接着干；n 停下后把这句话当你的下一句发出去；</li>
 *   <li>Esc = 拒绝这一次（不停这一轮）；快速双击 Esc / {@code C-c C-c} = 停这一轮。</li>
 * </ul>
 */
final class ApprovalPanel {
    static final Mode MODE = new Mode("agent-approval-mode", "Approval", null);

    private final AgentWorkbench wb;
    final Buffer buf;
    private ApprovalRequest req;
    private int sel;
    /** 矮屏上把收起的详情展开了（? 键）。 */
    private boolean expanded;
    /** 屏幕矮于这么多行时面板收紧（Codex 的 approval overlay：标题一行、命令一行、选项一项一行，详情按 ? 展开）。 */
    static final int COMPACT_BELOW_ROWS = 30;

    ApprovalPanel(AgentWorkbench wb, Editor ed) {
        this.wb = wb;
        buf = ed.getOrCreateBuffer(" *agent-approval*");
        buf.setMode(MODE);
        buf.setReadOnly(true);
        buf.undo().setEnabled(false);
        buf.put("word-wrap", true);
        buf.put("wrap-prefix", "     ");
        buf.put("mode-line-face", "default");
        buf.setTopRows(w -> req == null ? List.of() : details(req, wb.panelWidth(), wb.rootId(), wb::agentTag, compact()));
        buf.setFooter(w -> compact() ? List.of() : List.of(List.of()));
        buf.setModeLine(new ModeLine().add("hint", 10, false, c -> wb.footer(wb.panelWidth(), compact() || expanded ? new String[][]{
                {"⏎", Msg.t("confirm")}, {keys(), Msg.t("choose")}, {"?", expanded ? Msg.t("less") : Msg.t("details")}, {"Esc", Msg.t("deny")},
                {"⌃C⌃C", Msg.t("stop the turn")}} : new String[][]{
                {"⏎", Msg.t("confirm")}, {keys(), Msg.t("choose")}, {"↑↓", Msg.t("select")}, {"Esc", Msg.t("deny")},
                {"⌃C⌃C", Msg.t("stop the turn")}})));
        ed.defineInternalCommand("agent-approval-key", "审批面板：按请求上的快捷键选。", e -> {
            String k = e.thisKey();
            if (req == null) return;
            ApprovalChoice c = req.choice(k);
            if (c == null && k != null && k.matches("[1-9]")) {
                int i = Integer.parseInt(k) - 1;
                if (i < req.choices().size()) c = req.choices().get(i);
            }
            if (c == null) { e.message("Not an option here: %s", k); return; }
            choose(e, c);
        });
        ed.defineInternalCommand("agent-approval-details", "审批面板 ?：矮屏上收起的详情（完整命令、理由、目录）展开 / 收起。", e -> {
            expanded = !expanded;
            if (req != null) { render(); wb.resizeInput(); }
        });
        ed.defineInternalCommand("agent-approval-next", "审批面板：下一项。", e -> move(1));
        ed.defineInternalCommand("agent-approval-previous", "审批面板：上一项。", e -> move(-1));
        ed.defineInternalCommand("agent-approval-confirm", "审批面板：选中当前项。", e -> {
            if (req != null) choose(e, req.choices().get(Math.min(sel, req.choices().size() - 1)));
        });
        ed.defineInternalCommand("agent-approval-deny", "审批面板 Esc：拒绝这一次（不停这一轮）。", e -> {
            if (req == null) return;
            ApprovalChoice deny = null;
            for (ApprovalChoice c : req.choices()) if (c.decision() == ReviewDecision.Kind.DENIED) deny = c;
            resolve(e, deny != null ? deny : new ApprovalChoice(ReviewDecision.Kind.DENIED, "d", ""), null);
        });
        for (String k : List.of("y", "p", "a", "d", "b", "n", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
            MODE.keymap().define(k, "agent-approval-key");
        MODE.keymap().define("C-n", "agent-approval-next").define("<down>", "agent-approval-next").define("TAB", "agent-approval-next")
                .define("C-p", "agent-approval-previous").define("<up>", "agent-approval-previous").define("<backtab>", "agent-approval-previous")
                .define("RET", "agent-approval-confirm").define("ESC", "agent-approval-deny").define("M-ESC", "agent-stop")
                .define("?", "agent-approval-details")
                .define("C-c C-c", "agent-stop");
    }

    ApprovalRequest request() { return req; }

    boolean active() { return req != null; }

    /** 收紧着没有：屏幕矮且没按 ? 展开。 */
    boolean compact() { return !expanded && wb.ed.rows() < COMPACT_BELOW_ROWS; }

    /** 这道审批的快捷键（y/p/a/d/b/n 里请求上真有的那几个）。 */
    private String keys() {
        if (req == null) return "y/n";
        List<String> ks = new ArrayList<>();
        for (ApprovalChoice c : req.choices()) if (c.key() != null && !c.key().isEmpty()) ks.add(c.key());
        return ks.isEmpty() ? "1-9" : String.join("/", ks);
    }

    void show(ApprovalRequest r) {
        if (r == null) { hide(); return; }
        boolean fresh = req == null || !req.id().equals(r.id());
        req = r;
        if (fresh) { sel = 0; expanded = false; }
        render();
        wb.showPanel(buf, 0);
    }

    void hide() {
        if (req == null) return;
        req = null;
        buf.clear();
        wb.hidePanel(buf);
    }

    private void move(int d) {
        if (req == null) return;
        sel = Math.floorMod(sel + d, req.choices().size());
        render();
    }

    /**
     * 选项上面那几行：空行、粗体问句（子 agent 问的写上是谁）、空行、理由 / 沙盒拦下的原因、命令（{@code $ …}，长的折行）/ 目录 /
     * 文件 / 主机 / 工具、空行。
     */
    static List<List<Seg>> details(ApprovalRequest r, int width, String rootId) { return details(r, width, rootId, null, false); }

    /**
     * who：子 agent 的会话 id ⇒ 它的带色称呼（null = 只写 id）。compact：矮屏收紧 —— 不垫空行，理由 / 命令 / 文件各收成一行
     * （截断处写「+N 行 · ? 详情」），不写目录；选项照样一项一行，一个都不少。
     */
    static List<List<Seg>> details(ApprovalRequest r, int width, String rootId, java.util.function.Function<String, List<Seg>> who, boolean compact) {
        List<List<Seg>> rows = new ArrayList<>();
        int w = Math.max(20, width - 4);
        if (!compact) rows.add(List.of());
        List<Seg> head = new ArrayList<>(List.of(Seg.of("  "), Seg.of(Blocks.approvalHead(r), "agent-title")));
        if (r.session() != null && rootId != null && !r.session().equals(rootId)) {
            List<Seg> tag = who == null ? null : who.apply(r.session());
            if (tag == null) head.add(Seg.of("  " + Msg.f("(asked by sub-agent %s)", r.session()), "agent-dim"));
            else {
                head.add(Seg.of("  ← ", "agent-dim"));
                head.addAll(tag);
                head.add(Seg.of(" " + Msg.t("asks"), "agent-dim"));
            }
        }
        rows.add(AgentsOverview.clipRow(head, width));
        if (compact) {
            rows.addAll(compactBody(r, w));
            rows.add(List.of());
            return rows;
        }
        List<List<Seg>> body = new ArrayList<>();
        if (r.reason() != null && !r.reason().isBlank()) {
            List<String> ls = Blocks.wrap(Msg.f("Reason: %s", r.reason()), w);
            for (String l : ls) body.add(List.of(Seg.of("  "), Seg.of(l, "agent-reasoning")));
        }
        if (r.retryReason() != null) for (String l : Blocks.wrap(r.retryReason(), w)) body.add(List.of(Seg.of("  "), Seg.of(l, "agent-warn")));
        List<List<Seg>> what = new ArrayList<>();
        if (r.command() != null && r.title() != null) {
            List<String> ls = Blocks.wrap(r.title(), w - 2);
            for (int i = 0; i < ls.size() && i < 8; i++) what.add(List.of(Seg.of(i == 0 ? "  $ " : "    ", "agent-magenta"), Seg.of(ls.get(i), "agent-title")));
            if (ls.size() > 8) what.add(List.of(Seg.of("    " + Msg.f("… +%d lines", ls.size() - 8), "agent-dim")));
            if (r.cwd() != null) what.add(List.of(Seg.of("  " + Msg.f("in %s", r.cwd()), "agent-dim")));
        } else if (r.kind() == ApprovalRequest.Kind.PATCH) {
            Preview.Result pv = r.preview();
            if (pv != null && pv.text() != null) {
                Blocks.PreviewDiff pd = Blocks.fromPreview(pv.text(), new Blocks.Ctx() {
                    @Override public String fullResult(String id) { return null; }
                    @Override public io.ailink.agentforge.agent.transcript.SubRun subRun(String id) { return null; }
                    @Override public List<io.ailink.agentforge.agent.session.GalleryImage> images(String o) { return List.of(); }
                    @Override public void enterChild(Editor ed, String id) { }
                    @Override public void openImage(Editor ed, io.ailink.agentforge.agent.session.GalleryImage g) { }
                });
                List<String> ps = pd.files().stream().map(Blocks.FileDiff::path).toList();
                if (!ps.isEmpty()) what.add(List.of(Seg.of("  " + Msg.f("Files: %s", Blocks.clip(String.join(", ", ps), Math.max(20, w - 8))), "agent-dim")));
            } else if (r.title() != null) what.add(List.of(Seg.of("  " + Msg.f("Files: %s", r.title()), "agent-dim")));
        } else if (r.kind() == ApprovalRequest.Kind.NETWORK && r.host() != null) {
            what.add(List.of(Seg.of("  " + Msg.f("Host: %s", r.host()), "agent-dim")));
            if (r.title() != null) for (String l : Blocks.wrap(r.title(), w)) what.add(List.of(Seg.of("  " + l)));
        } else if (r.title() != null) {
            List<Seg> first = new ArrayList<>(List.of(Seg.of("  ")));
            if (r.toolName() != null && r.kind() == ApprovalRequest.Kind.TOOL) first.add(Seg.of(r.toolName() + " ", "agent-accent"));
            List<String> ls = Blocks.wrap(r.title(), w);
            for (int i = 0; i < ls.size() && i < 6; i++) {
                if (i == 0) { first.add(Seg.of(ls.get(i))); what.add(first); }
                else what.add(List.of(Seg.of("  " + ls.get(i))));
            }
        }
        if (!body.isEmpty()) { rows.add(List.of()); rows.addAll(body); }
        if (!what.isEmpty()) { rows.add(List.of()); rows.addAll(what); }
        rows.add(List.of());
        return rows;
    }

    /** 收紧时问句下面那一两行：理由一行、命令 / 文件 / 主机 / 工具一行（多了写还有几行，? 看全）。 */
    private static List<List<Seg>> compactBody(ApprovalRequest r, int w) {
        List<List<Seg>> out = new ArrayList<>();
        String more = " · ? " + Msg.t("details");
        if (r.retryReason() != null) out.add(List.of(Seg.of("  "), Seg.of(Width.ellipsize(oneLine(r.retryReason()), w), "agent-warn")));
        else if (r.reason() != null && !r.reason().isBlank())
            out.add(List.of(Seg.of("  "), Seg.of(Width.ellipsize(Msg.f("Reason: %s", oneLine(r.reason())), w), "agent-reasoning")));
        String what = null, lead = "  ";
        String face = null;
        if (r.command() != null && r.title() != null) { what = r.title(); lead = "  $ "; face = "agent-title"; }
        else if (r.kind() == ApprovalRequest.Kind.PATCH) {
            Preview.Result pv = r.preview();
            String files = null;
            if (pv != null && pv.text() != null) {
                List<String> ps = Blocks.fromPreview(pv.text(), NO_CTX).files().stream().map(Blocks.FileDiff::path).toList();
                if (!ps.isEmpty()) files = String.join(", ", ps);
            }
            what = Msg.f("Files: %s", files != null ? files : r.title() == null ? "" : r.title());
            face = "agent-dim";
        } else if (r.kind() == ApprovalRequest.Kind.NETWORK && r.host() != null) { what = Msg.f("Host: %s", r.host()); face = "agent-dim"; }
        else if (r.title() != null) what = (r.toolName() != null && r.kind() == ApprovalRequest.Kind.TOOL ? r.toolName() + " " : "") + r.title();
        if (what != null) {
            List<String> ls = Blocks.lines(what.strip());
            String first = ls.isEmpty() ? "" : ls.getFirst();
            String tail = ls.size() > 1 ? " " + Msg.f("(+%d lines)", ls.size() - 1).strip() + more : "";
            int room = w - Width.stringWidth(lead) + 2 - Width.stringWidth(tail);
            boolean cut = Width.stringWidth(first) > room;
            if (cut && tail.isEmpty()) { tail = more; room = w - Width.stringWidth(lead) + 2 - Width.stringWidth(tail); }
            List<Seg> l = new ArrayList<>(List.of(Seg.of(lead, lead.contains("$") ? "agent-magenta" : null), Seg.of(Width.ellipsize(first, Math.max(8, room)), face)));
            if (!tail.isEmpty()) l.add(Seg.of(tail, "agent-dim"));
            out.add(l);
        }
        return out;
    }

    private static String oneLine(String s) { return s.strip().replace('\n', ' '); }

    private static final Blocks.Ctx NO_CTX = new Blocks.Ctx() {
        @Override public String fullResult(String id) { return null; }
        @Override public io.ailink.agentforge.agent.transcript.SubRun subRun(String id) { return null; }
        @Override public List<io.ailink.agentforge.agent.session.GalleryImage> images(String o) { return List.of(); }
        @Override public void enterChild(Editor ed, String id) { }
        @Override public void openImage(Editor ed, io.ailink.agentforge.agent.session.GalleryImage g) { }
    };

    private void render() {
        buf.clear();
        int selStart = 0;
        List<ApprovalChoice> cs = req.choices();
        int width = wb.panelWidth();
        for (int i = 0; i < cs.size(); i++) {
            ApprovalChoice c = cs.get(i);
            int a = buf.length();
            if (i == sel) {
                selStart = a;
                buf.append("› " + (i + 1) + ". " + c.label() + " (" + c.key() + ")\n", "completion-list-selected").extend(true);
            } else {
                buf.append("  " + (i + 1) + ". ", "agent-dim");
                buf.append(c.label(), null);
                buf.append(" (" + c.key() + ")\n", "agent-dim");
            }
        }
        if (buf.length() > 0) buf.delete(buf.length() - 1, buf.length());
        wb.panelPoint(buf, selStart);
    }

    /** 选了一项：拒绝类先问一句给模型的话。 */
    void choose(Editor ed, ApprovalChoice c) {
        ReviewDecision.Kind k = c.decision();
        if (k == ReviewDecision.Kind.DENIED || k == ReviewDecision.Kind.ABORT) {
            String prompt = k == ReviewDecision.Kind.DENIED
                    ? Msg.t("Note to the model (optional, RET to skip): ")
                    : Msg.t("What should it do instead? Sent as your next message (optional): ");
            ApprovalRequest r = req;
            ed.readFromMinibuffer(new MinibufferRequest(prompt).onDone(fb -> { if (req == r) resolve(ed, c, fb); }));
            return;
        }
        resolve(ed, c, null);
    }

    private void resolve(Editor ed, ApprovalChoice c, String feedback) {
        ApprovalRequest r = req;
        if (r == null) return;
        String fb = feedback == null || feedback.isBlank() ? null : feedback.strip();
        ReviewDecision d = new ReviewDecision(c.decision(), fb);
        hide();
        wb.resolveApproval(r, d, c);
    }
}
