package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Matcher;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 选择器（Codex 的 list selection view）：摆出来时顶替输入框 —— 标题、说明、一行筛选输入、编号的选项（当前项标「当前」、
 * 选中行整行高亮）、底下一行键位提示。模型 / 思考档 / 权限预设 / agent / 对话 / 主题都走它。
 * <ul>
 *   <li>打字 = 筛选（buffer 里那一行就是筛选词，编辑键照常）；筛选词空着时数字 1–9 直接选；</li>
 *   <li>↑↓ / C-p C-n / TAB 挪，PgUp / PgDn 翻页，RET 选定，Esc / C-g 返回（不改什么）。</li>
 * </ul>
 */
final class ListPanel {
    static final Mode MODE = new Mode("agent-list-mode", "List", null);
    static final int MAX_ROWS = 10;

    /** 一项：显示的字、右边的说明（可 null）、是不是当前值、带回去的值。 */
    record Item(String label, String detail, boolean current, Object value) {
        static Item of(String label, String detail, boolean current) { return new Item(label, detail, current, label); }
    }

    private final AgentWorkbench wb;
    final Buffer buf;
    private String title, subtitle;
    private List<Item> items = List.of();
    private List<Item> shown = List.of();
    private int sel, top;
    private Consumer<Item> onPick, onMove;
    private Runnable onCancel;
    private Consumer<String> onCustom;
    private boolean open;

    ListPanel(AgentWorkbench wb, Editor ed) {
        this.wb = wb;
        buf = ed.getOrCreateBuffer(" *agent-list*");
        buf.setMode(MODE);
        buf.undo().setEnabled(false);
        buf.put("word-wrap", false);
        buf.put("first-line-prefix", "  " + Msg.t("Filter: "));
        buf.put("line-prefix-face", "agent-dim");
        buf.put("placeholder", Msg.t("type to filter"));
        buf.put("placeholder-face", "agent-placeholder");
        buf.put("mode-line-face", "default");
        buf.setTopRows(w -> {
            List<List<Seg>> rows = new ArrayList<>();
            rows.add(List.of());
            rows.add(List.of(Seg.of("  "), Seg.of(title == null ? "" : title, "agent-title")));
            if (subtitle != null && !subtitle.isEmpty())
                for (String l : Blocks.wrap(subtitle, wb.panelWidth() - 4)) rows.add(List.of(Seg.of("  " + l, "agent-dim")));
            rows.add(List.of());
            return rows;
        });
        buf.setFooter(w -> rows());
        buf.setModeLine(new ModeLine().add("hint", 10, false, c -> wb.footer(wb.panelWidth(), new String[][]{
                {"↑↓", Msg.t("select")}, {"⏎", Msg.t("confirm")}, {"1-9", Msg.t("pick")}, {Msg.t("type"), Msg.t("filter")}, {"Esc", Msg.t("back")}})));
        buf.addChangeListener((b, a, oe, ne) -> { if (open) { refilter(); sel = 0; top = 0; wb.resizeInput(); } });
        ed.defineInternalCommand("agent-list-next", "选择器：下一项。", e -> move(1));
        ed.defineInternalCommand("agent-list-previous", "选择器：上一项。", e -> move(-1));
        ed.defineInternalCommand("agent-list-page-down", "选择器：下一页。", e -> move(MAX_ROWS));
        ed.defineInternalCommand("agent-list-page-up", "选择器：上一页。", e -> move(-MAX_ROWS));
        ed.defineInternalCommand("agent-list-confirm", "选择器：选定当前项。", e -> pick(sel));
        ed.defineInternalCommand("agent-list-cancel", "选择器：返回（不改什么）。", e -> cancel());
        ed.defineInternalCommand("agent-list-digit", "选择器：筛选词空着时数字直接选，否则当字打进筛选。", e -> {
            String k = e.thisKey();
            if (buf.length() == 0 && k != null && k.matches("[1-9]")) {
                int i = Integer.parseInt(k) - 1;
                if (i < shown.size()) { pick(i); return; }
            }
            e.runCommand("self-insert-command", k);
        });
        for (int d = 1; d <= 9; d++) MODE.keymap().define(String.valueOf(d), "agent-list-digit");
        MODE.keymap().define("<down>", "agent-list-next").define("C-n", "agent-list-next").define("TAB", "agent-list-next")
                .define("<up>", "agent-list-previous").define("C-p", "agent-list-previous").define("<backtab>", "agent-list-previous")
                .define("<next>", "agent-list-page-down").define("<prior>", "agent-list-page-up")
                .define("RET", "agent-list-confirm").define("ESC", "agent-list-cancel").define("C-g", "agent-list-cancel");
    }

    boolean active() { return open; }

    /** 现在列着的（筛过的）各项的字（测试用）。 */
    List<String> labels() { return shown.stream().map(Item::label).toList(); }

    String title() { return title; }

    /** 摆出来。onMove（可 null）= 选中项变了时调（主题预览）；onCancel（可 null）= Esc 返回时调。 */
    void open(String title, String subtitle, List<Item> items, Consumer<Item> onPick, Consumer<Item> onMove, Runnable onCancel) {
        this.title = title;
        this.subtitle = subtitle;
        this.items = List.copyOf(items);
        this.onPick = onPick;
        this.onMove = onMove;
        this.onCancel = onCancel;
        this.onCustom = null;
        open = true;
        buf.setContents("");
        refilter();
        sel = 0;
        for (int i = 0; i < shown.size(); i++) if (shown.get(i).current()) sel = i;
        top = Math.max(0, sel - MAX_ROWS + 1);
        wb.showPanel(buf, 0);
    }

    /** 筛选词什么都没配上时 RET 把筛选词本身交给 f（模型选择器：手写一个目录外的 provider/model）。 */
    void allowCustom(Consumer<String> f) { onCustom = f; }

    void close() {
        if (!open) return;
        open = false;
        buf.setContents("");
        wb.hidePanel(buf);
    }

    private void refilter() {
        String q = buf.contents().strip();
        if (q.isEmpty()) shown = items;
        else {
            // 每个词都要命中（字或说明里，子串 / flex）；按第一个词在字上的命中等级排（helm 的手感）
            String[] terms = q.split("\\s+");
            record R(int rank, int idx, Item it) { }
            List<R> rs = new ArrayList<>();
            outer:
            for (int i = 0; i < items.size(); i++) {
                Item it = items.get(i);
                String all = it.label() + " " + (it.detail() == null ? "" : it.detail());
                for (String t : terms) if (Matcher.rank(t, all) < 0) continue outer;
                int r = Matcher.rank(terms[0], it.label());
                rs.add(new R(r < 0 ? 9 : r, i, it));
            }
            rs.sort(java.util.Comparator.comparingInt(R::rank).thenComparingInt(R::idx));
            shown = rs.stream().map(R::it).toList();
        }
        sel = Math.max(0, Math.min(sel, shown.size() - 1));
        top = Math.max(0, Math.min(top, sel));
    }

    private void move(int d) {
        if (shown.isEmpty()) return;
        int n = shown.size();
        sel = Math.abs(d) == 1 ? Math.floorMod(sel + d, n) : Math.max(0, Math.min(n - 1, sel + d));
        if (sel < top) top = sel;
        if (sel >= top + MAX_ROWS) top = sel - MAX_ROWS + 1;
        if (onMove != null) onMove.accept(shown.get(sel));
    }

    private void pick(int i) {
        if (shown.isEmpty() && onCustom != null && !buf.contents().isBlank()) {
            Consumer<String> f = onCustom;
            String text = buf.contents().strip();
            close();
            f.accept(text);
            return;
        }
        if (i < 0 || i >= shown.size()) return;
        Item it = shown.get(i);
        Consumer<Item> f = onPick;
        close();
        if (f != null) f.accept(it);
    }

    private void cancel() {
        Runnable f = onCancel;
        close();
        if (f != null) f.run();
    }

    /** 选项那几行（筛选行下面）：空一行、最多 MAX_ROWS 项、超出时右边写「第几 / 共几」。 */
    private List<List<Seg>> rows() {
        List<List<Seg>> out = new ArrayList<>();
        out.add(List.of());
        if (shown.isEmpty()) { out.add(List.of(Seg.of("  " + Msg.t("(no match)"), "agent-dim"))); out.add(List.of()); return out; }
        int w = wb.panelWidth();
        int lw = 0;
        int end = Math.min(shown.size(), top + MAX_ROWS);
        for (int i = top; i < end; i++) lw = Math.max(lw, Math.min(48, Width.stringWidth(shown.get(i).label()) + (shown.get(i).current() ? Width.stringWidth(Msg.t(" (current)")) : 0)));
        for (int i = top; i < end; i++) {
            Item it = shown.get(i);
            boolean s = i == sel;
            String face = s ? "completion-list-selected" : null;
            List<Seg> r = new ArrayList<>();
            r.add(Seg.of(s ? "› " : "  ", face));
            String num = i < 9 ? (i + 1) + ". " : "   ";
            r.add(Seg.of(num, s ? face : "agent-dim"));
            String label = Width.ellipsize(it.label(), 48);
            r.add(Seg.of(label, face));
            int used = Width.stringWidth(label);
            if (it.current()) { r.add(Seg.of(Msg.t(" (current)"), s ? face : "agent-dim")); used += Width.stringWidth(Msg.t(" (current)")); }
            if (it.detail() != null && !it.detail().isEmpty()) {
                r.add(Seg.of(" ".repeat(Math.max(2, lw - used + 2)), face));
                int room = w - 5 - Math.max(lw, used) - 2;
                if (room > 6) r.add(Seg.of(Width.ellipsize(it.detail(), room), s ? face : "agent-dim"));
            }
            if (s) {
                int pad = w - Seg.width(r);
                if (pad > 0) r.add(Seg.of(" ".repeat(pad), face));
            }
            out.add(r);
        }
        if (shown.size() > MAX_ROWS) out.add(List.of(Seg.of("  " + Msg.f("%d / %d", sel + 1, shown.size()), "agent-dim")));
        out.add(List.of());
        return out;
    }

    /** 这块要几行（筛选行之外：顶上几行 + 选项 + 提示）。 */
    int extraRows() { return rows().size(); }
}
