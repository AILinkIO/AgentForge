package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.MinibufferRequest;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Seg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * clarify 提问面板（coxswain {@code designs/agent-clarify-tool.md}；样子照 Codex 的 request-user-input 视图）：一道题摆出来时
 * 顶替输入框 —— 粗体问题、编号的选项（选中行高亮）、最后一项「自己写」，逐题作答。
 * <ul>
 *   <li>单选：数字直接选中并确认；↑↓ 挪、RET 确认；</li>
 *   <li>多选：数字 / SPC 勾选，RET 确认（一个都没勾 ⇒ 取光标那项）；</li>
 *   <li>{@code e}（或选最后那项「自己写」）：在 minibuffer 里写自己的答案；{@code x}：跳过本题；</li>
 *   <li>Esc：整道题不答（工具报 dismissed，模型接着干，不停这一轮）；双击 Esc / {@code C-c C-c}：停这一轮。</li>
 * </ul>
 */
final class QuestionPanel {
    static final Mode MODE = new Mode("agent-question-mode", "Question", null);

    private final AgentWorkbench wb;
    final Buffer buf;
    private ClarifyService.Request req;
    private int qi, sel;
    private final Set<Integer> picked = new LinkedHashSet<>();
    private final List<List<String>> answers = new ArrayList<>();

    QuestionPanel(AgentWorkbench wb, Editor ed) {
        this.wb = wb;
        buf = ed.getOrCreateBuffer(" *agent-question*");
        buf.setMode(MODE);
        buf.setReadOnly(true);
        buf.undo().setEnabled(false);
        buf.put("word-wrap", true);
        buf.put("wrap-prefix", "     ");
        buf.put("mode-line-face", "default");
        buf.setTopRows(w -> {
            if (req == null) return List.of();
            ClarifyService.Question q = q();
            List<List<Seg>> rows = new ArrayList<>();
            rows.add(List.of());
            String head = (q.header() == null || q.header().isBlank() ? "" : "[" + q.header() + "] ") + q.question();
            List<String> ls = Blocks.wrap(head, wb.panelWidth() - 4);
            for (int i = 0; i < ls.size(); i++) rows.add(List.of(Seg.of("  "), Seg.of(ls.get(i), "agent-title")));
            if (req.questions().size() > 1) rows.add(List.of(Seg.of("  " + Msg.f("question %d/%d", qi + 1, req.questions().size()), "agent-dim")));
            rows.add(List.of());
            return rows;
        });
        buf.setFooter(w -> List.of(List.of()));
        buf.setModeLine(new ModeLine().add("hint", 10, false, c -> wb.footer(wb.panelWidth(), multiple()
                ? new String[][]{{"1-9 SPC", Msg.t("tick")}, {"⏎", Msg.t("confirm")}, {"e", Msg.t("write my own")}, {"x", Msg.t("skip")}, {"Esc", Msg.t("dismiss")}}
                : new String[][]{{"1-9", Msg.t("pick")}, {"↑↓ ⏎", Msg.t("confirm")}, {"e", Msg.t("write my own")}, {"x", Msg.t("skip")}, {"Esc", Msg.t("dismiss")}})));
        ed.defineInternalCommand("agent-question-digit", "提问面板：数字选 / 勾。", e -> {
            String k = e.thisKey();
            if (req == null || k == null || !k.matches("[1-9]")) return;
            int i = Integer.parseInt(k) - 1;
            if (i > options().size()) return;
            if (i == options().size()) { writeOwn(e); return; }
            sel = i;
            if (multiple()) { toggle(i); render(); } else commit(List.of(options().get(i).label()));
        });
        ed.defineInternalCommand("agent-question-toggle", "提问面板：勾 / 取消光标那项（多选）。", e -> {
            if (req == null) return;
            if (sel == options().size()) { writeOwn(e); return; }
            if (multiple()) { toggle(sel); render(); } else commit(List.of(options().get(sel).label()));
        });
        ed.defineInternalCommand("agent-question-next", "提问面板：下一项。", e -> move(1));
        ed.defineInternalCommand("agent-question-previous", "提问面板：上一项。", e -> move(-1));
        ed.defineInternalCommand("agent-question-confirm", "提问面板：确认。", e -> {
            if (req == null) return;
            if (sel == options().size()) { writeOwn(e); return; }
            if (multiple() && !picked.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (int i : picked) out.add(options().get(i).label());
                commit(out);
            } else commit(List.of(options().get(sel).label()));
        });
        ed.defineInternalCommand("agent-question-own", "提问面板：自己写答案。", this::writeOwn);
        ed.defineInternalCommand("agent-question-skip", "提问面板：跳过本题。", e -> { if (req != null) commit(List.of()); });
        ed.defineInternalCommand("agent-question-dismiss", "提问面板 Esc：整道题不答（模型接着干）。", e -> {
            if (req == null) return;
            hide();
            wb.answerQuestion(new ClarifyService.Dismissed());
        });
        for (int d = 1; d <= 9; d++) MODE.keymap().define(String.valueOf(d), "agent-question-digit");
        MODE.keymap().define("SPC", "agent-question-toggle").define("C-n", "agent-question-next").define("<down>", "agent-question-next")
                .define("TAB", "agent-question-next").define("C-p", "agent-question-previous").define("<up>", "agent-question-previous")
                .define("<backtab>", "agent-question-previous").define("RET", "agent-question-confirm").define("e", "agent-question-own")
                .define("x", "agent-question-skip").define("ESC", "agent-question-dismiss").define("M-ESC", "agent-stop")
                .define("C-c C-c", "agent-stop");
    }

    boolean active() { return req != null; }

    ClarifyService.Request request() { return req; }

    void show(ClarifyService.Request r) {
        if (r == null) { hide(); return; }
        if (req != null && req.equals(r)) return;
        req = r;
        qi = 0;
        sel = 0;
        picked.clear();
        answers.clear();
        render();
        wb.showPanel(buf, 0);
    }

    void hide() {
        if (req == null) return;
        req = null;
        buf.clear();
        wb.hidePanel(buf);
    }

    private ClarifyService.Question q() { return req.questions().get(qi); }

    private List<ClarifyService.Option> options() { return q().options(); }

    private boolean multiple() { return req != null && q().multiple(); }

    private void toggle(int i) { if (!picked.remove(i)) picked.add(i); }

    private void move(int d) {
        if (req == null) return;
        sel = Math.floorMod(sel + d, options().size() + 1);
        render();
    }

    private void writeOwn(Editor ed) {
        if (req == null) return;
        ClarifyService.Request r = req;
        int at = qi;
        ed.readFromMinibuffer(new MinibufferRequest(Msg.t("Your answer: ")).onDone(s -> {
            if (req != r || qi != at) return;
            commit(s.isBlank() ? List.of() : List.of(s.strip()));
        }));
    }

    /** 这一题答了：下一题，或者整道交上去。 */
    private void commit(List<String> answer) {
        answers.add(answer);
        if (qi + 1 < req.questions().size()) {
            qi++;
            sel = 0;
            picked.clear();
            render();
            wb.showPanel(buf, 0);
            return;
        }
        List<List<String>> all = List.copyOf(answers);
        hide();
        wb.answerQuestion(new ClarifyService.Answered(all));
    }

    private void render() {
        buf.clear();
        ClarifyService.Question q = q();
        int selStart = 0;
        List<ClarifyService.Option> os = q.options();
        for (int i = 0; i <= os.size(); i++) {
            int s = buf.length();
            if (i == sel) selStart = s;
            boolean on = i == sel;
            String box = q.multiple() && i < os.size() ? (picked.contains(i) ? "[x] " : "[ ] ") : "";
            String label = i < os.size() ? os.get(i).label() : Msg.t("✎ write my own answer…");
            buf.append(on ? "› " : "  ", on ? "completion-list-selected" : null);
            buf.append((i + 1) + ". ", on ? "completion-list-selected" : "agent-dim");
            buf.append(box + label, on ? "completion-list-selected" : null);
            if (i < os.size() && os.get(i).description() != null && !os.get(i).description().isBlank())
                buf.append("  — " + os.get(i).description(), on ? "completion-list-selected" : "agent-dim");
            buf.append(i < os.size() ? "\n" : "");
            if (on) buf.addOverlay(s, buf.length()).face("completion-list-selected").priority(-1).extend(true);
        }
        wb.panelPoint(buf, selStart);
    }
}
