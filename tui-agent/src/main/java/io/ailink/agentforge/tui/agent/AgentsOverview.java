package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.transcript.SubStep;
import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.ModeLine;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayList;
import java.util.List;

/**
 * agent 总览（{@code /agents}；Codex 的 agents overview / command center）：占住记录区那块，列出主会话和这段对话里的
 * <b>每一趟</b>子 agent（在跑 / 完成 / 失败 / 等你，含后台的、子 agent 再派的），活的读数一直在刷。
 * <pre>
 *   Agents  主会话与子 agent                                            Tab 筛选
 *    全部 3   等你 1   在跑 1   完成 1   失败 0
 *   ──────────────────────────────────────────────────────────────────────────
 *   › ● main          主对话                    在跑   12s  │ junior #1 · 找出所有调用 greet 的地方
 *       ● junior #1   找出所有调用 greet 的地方  在跑    4s  │ ● 在跑 · 4s · 2 步
 *       ✔ deep #2     查架构                     完成  8.4s  │ …
 * </pre>
 * 宽（≥ 100 列）时右边是选中那趟的详情（类型、模型、正在做什么、等你什么、最近几步、最后一句、任务），窄时详情排在列表下面。
 * ↑↓ 选、RET 走进去看它的记录（主会话 = 回主对话）、Tab / S-Tab 换筛选、s 换主会话的 agent、Esc / q 返回。
 */
final class AgentsOverview {
    static final Mode MODE = new Mode("agent-overview-mode", "Agents", null);
    /** 宽到这么多列才把详情放右边。 */
    static final int SIDE_BY_SIDE = 100;

    private final AgentWorkbench wb;
    final Buffer buf;
    private boolean open;
    private String selKey = "main";
    private int filter;
    private List<List<Seg>> lastRows;
    private int selLine;

    /** 筛选：null = 全部。 */
    private static final AgentRoster.State[][] FILTERS = {null, {AgentRoster.State.NEEDS_YOU}, {AgentRoster.State.WORKING, AgentRoster.State.READY},
            {AgentRoster.State.DONE}, {AgentRoster.State.FAILED}};
    private static final String[] FILTER_NAMES = {"All", "Needs you", "Working", "Done", "Failed"};

    AgentsOverview(AgentWorkbench wb, Editor ed) {
        this.wb = wb;
        buf = ed.getOrCreateBuffer(" *agents*");
        buf.setMode(MODE);
        buf.setReadOnly(true);
        buf.undo().setEnabled(false);
        buf.put("word-wrap", false);
        buf.setModeLine(ModeLine.NONE);
        ed.defineInternalCommand("agent-overview-next", "总览：下一趟。", e -> move(1));
        ed.defineInternalCommand("agent-overview-previous", "总览：上一趟。", e -> move(-1));
        ed.defineInternalCommand("agent-overview-open", "总览：走进选中的那趟（主会话 = 回主对话）。", e -> openSelected());
        ed.defineInternalCommand("agent-overview-close", "总览：返回。", e -> wb.closeOverview());
        ed.defineInternalCommand("agent-overview-filter-next", "总览：下一个筛选。", e -> { filter = (filter + 1) % FILTERS.length; render(); });
        ed.defineInternalCommand("agent-overview-filter-previous", "总览：上一个筛选。", e -> { filter = Math.floorMod(filter - 1, FILTERS.length); render(); });
        ed.defineInternalCommand("agent-overview-switch-agent", "总览：换主会话的 agent。", e -> { wb.closeOverview(); wb.pickAgentDef(); });
        MODE.keymap().define("<down>", "agent-overview-next").define("C-n", "agent-overview-next").define("j", "agent-overview-next")
                .define("<up>", "agent-overview-previous").define("C-p", "agent-overview-previous").define("k", "agent-overview-previous")
                .define("RET", "agent-overview-open").define("ESC", "agent-overview-close").define("q", "agent-overview-close")
                .define("C-g", "agent-overview-close").define("TAB", "agent-overview-filter-next").define("<backtab>", "agent-overview-filter-previous")
                .define("s", "agent-overview-switch-agent").define("M-ESC", "agent-escape-double").define("C-x o", "agent-overview-close");
    }

    boolean active() { return open; }

    /** 选中的那趟（"main" = 主会话）。 */
    String selected() { return selKey; }

    void open(String select) {
        open = true;
        filter = 0;
        selKey = select == null ? "main" : select;
        lastRows = null;
        render();
    }

    void close() {
        open = false;
        lastRows = null;
    }

    /** 筛过之后的行。 */
    private List<AgentRoster.Row> shown(List<AgentRoster.Row> all) {
        AgentRoster.State[] f = FILTERS[filter];
        if (f == null) return all;
        List<AgentRoster.Row> out = new ArrayList<>();
        for (AgentRoster.Row r : all) for (AgentRoster.State s : f) if (r.state() == s) { out.add(r); break; }
        return out;
    }

    private void move(int d) {
        List<AgentRoster.Row> rs = shown(wb.roster.rows());
        if (rs.isEmpty()) return;
        int i = indexOf(rs);
        i = Math.floorMod((i < 0 ? 0 : i) + d, rs.size());
        selKey = rs.get(i).key();
        render();
    }

    private int indexOf(List<AgentRoster.Row> rs) {
        for (int i = 0; i < rs.size(); i++) if (rs.get(i).key().equals(selKey)) return i;
        return -1;
    }

    private void openSelected() {
        List<AgentRoster.Row> rs = shown(wb.roster.rows());
        int i = indexOf(rs);
        if (i < 0) return;
        AgentRoster.Row r = rs.get(i);
        wb.closeOverview();
        if (r.main()) wb.leaveChild();
        else wb.enterRun(r.id());
    }

    /** 按现状重画（没变就不动 buffer）。 */
    void render() {
        if (!open) return;
        List<AgentRoster.Row> all = wb.roster.rows();
        List<AgentRoster.Row> rs = shown(all);
        if (!rs.isEmpty() && indexOf(rs) < 0) selKey = rs.getFirst().key();
        int[] ts = wb.overviewSize();
        List<List<Seg>> rows = layout(all, rs, ts[0], ts[1]);
        if (rows.equals(lastRows)) return;
        lastRows = rows;
        buf.clear();
        int selPos = 0;
        for (int k = 0; k < rows.size(); k++) {
            if (k == selLine) selPos = buf.length();
            for (Seg s : rows.get(k)) buf.append(s.text(), s.face());
            if (k < rows.size() - 1) buf.append("\n");
        }
        wb.overviewPoint(selPos);
    }

    /** 整屏的行（宽 w、高 h）。 */
    List<List<Seg>> layout(List<AgentRoster.Row> all, List<AgentRoster.Row> rs, int w, int h) {
        List<List<Seg>> out = new ArrayList<>();
        // 标题 + 筛选
        List<Seg> title = new ArrayList<>(List.of(Seg.of("  "), Seg.of(Msg.t("Agents overview"), "agent-title"),
                Seg.of("  " + Msg.t("main conversation and every sub-agent run"), "agent-dim")));
        out.add(fit(title, w, Msg.t("Tab filter"), "agent-dim"));
        List<Seg> tabs = new ArrayList<>(List.of(Seg.of(" ")));
        for (int i = 0; i < FILTERS.length; i++) {
            int n = 0;
            for (AgentRoster.Row r : all) {
                if (FILTERS[i] == null) n++;
                else for (AgentRoster.State s : FILTERS[i]) if (r.state() == s) n++;
            }
            String t = " " + Msg.t(FILTER_NAMES[i]) + " " + n + " ";
            tabs.add(Seg.of(t, i == filter ? "completion-list-selected" : i == 1 && n > 0 ? "agent-warn" : "agent-dim"));
            tabs.add(Seg.of(" "));
        }
        out.add(clipRow(tabs, w));
        out.add(List.of(Seg.of("  " + "─".repeat(Math.max(0, w - 4)), "agent-box")));
        int bodyH = Math.max(1, h - out.size());
        boolean side = w >= SIDE_BY_SIDE;
        int listW = side ? Math.max(44, w * 52 / 100) : w;
        List<List<Seg>> list = listRows(rs, listW);
        AgentRoster.Row sel = null;
        for (AgentRoster.Row r : rs) if (r.key().equals(selKey)) sel = r;
        List<List<Seg>> det = sel == null ? List.of() : details(sel, side ? w - listW - 3 : w - 4);
        if (side) {
            // 列表在左，详情在右（中间一道竖线）
            int top = scrollTop(rs, bodyH);
            for (int k = 0; k < bodyH; k++) {
                List<Seg> l = k + top < list.size() ? list.get(k + top) : List.of();
                List<Seg> row = new ArrayList<>(pad(l, listW));
                row.add(Seg.of(" │ ", "agent-box"));
                if (k < det.size()) row.addAll(det.get(k));
                out.add(row);
            }
            selLine = 3 + indexOf(rs) - top;
        } else {
            // 列表在上（最多占一半），详情在下
            int listH = Math.min(list.size(), Math.max(3, det.isEmpty() ? bodyH : bodyH / 2));
            int top = scrollTop(rs, listH);
            for (int k = 0; k < listH; k++) out.add(k + top < list.size() ? list.get(k + top) : List.of());
            selLine = 3 + indexOf(rs) - top;
            if (!det.isEmpty() && bodyH - listH > 2) {
                out.add(List.of(Seg.of("  " + "─".repeat(Math.max(0, w - 4)), "agent-box")));
                for (int k = 0; k < det.size() && out.size() < h; k++) {
                    List<Seg> row = new ArrayList<>(List.of(Seg.of("  ")));
                    row.addAll(det.get(k));
                    out.add(row);
                }
            }
        }
        if (rs.isEmpty()) {
            selLine = 3;
            if (out.size() > 3) out.set(3, List.of(Seg.of("  " + Msg.t("(nothing here)"), "agent-dim")));
            else out.add(List.of(Seg.of("  " + Msg.t("(nothing here)"), "agent-dim")));
        }
        while (out.size() > h) out.removeLast();
        return out;
    }

    private int scrollTop(List<AgentRoster.Row> rs, int height) {
        int i = Math.max(0, indexOf(rs));
        return Math.max(0, Math.min(i - height + 1 + Math.min(2, height / 3), rs.size() - height));
    }

    /** 列表：{@code › ● junior #1  描述 … 状态  用时  步数}；子 agent 按层缩进。 */
    private List<List<Seg>> listRows(List<AgentRoster.Row> rs, int w) {
        List<List<Seg>> out = new ArrayList<>();
        int lw = 6;
        for (AgentRoster.Row r : rs) lw = Math.max(lw, Math.min(16, Width.stringWidth(r.label()) + 2 * r.depth()));
        boolean cols = w >= 56;
        for (AgentRoster.Row r : rs) {
            boolean s = r.key().equals(selKey);
            String bg = s ? "completion-list-selected" : null;
            List<Seg> row = new ArrayList<>();
            row.add(Seg.of(s ? "› " : "  ", bg));
            row.add(Seg.of(" ".repeat(2 * r.depth()), bg));
            String[] g = glyph(r.state());
            row.add(Seg.of(g[0] + " ", s ? bg : g[1]));
            String label = Width.ellipsize(r.label(), Math.max(4, lw - 2 * r.depth()));
            row.add(Seg.of(label, s ? bg : r.face()));
            row.add(Seg.of(" ".repeat(Math.max(1, lw - 2 * r.depth() - Width.stringWidth(label) + 2)), bg));
            String right = cols ? lpad(r.state().label(), 6) + "  " + lpad(r.elapsedMs() > 0 ? AgentWorkbench.elapsed(r.elapsedMs() / 1000) : "", 7)
                    + (r.main() ? "       " : "  " + lpad(Msg.f("%d steps", r.steps()), 5)) : "";
            int room = w - Seg.width(row) - Width.stringWidth(right) - 1;
            if (r.background() && room > 8) {
                String tag = "[" + Msg.t("background") + "] ";
                row.add(Seg.of(tag, s ? bg : "agent-dim"));
                room -= Width.stringWidth(tag);
            }
            String desc = room > 3 ? Width.ellipsize(r.desc() == null ? "" : r.desc().replace('\n', ' '), room) : "";
            row.add(Seg.of(desc, bg));
            int gap = w - Seg.width(row) - Width.stringWidth(right);
            if (gap > 0) row.add(Seg.of(" ".repeat(gap), bg));
            if (!right.isEmpty()) row.add(Seg.of(right, s ? bg : r.state() == AgentRoster.State.NEEDS_YOU ? "agent-warn"
                    : r.state() == AgentRoster.State.FAILED ? "agent-err" : "agent-dim"));
            out.add(clipRow(row, w));
        }
        return out;
    }

    /** 状态的记号 + face：等你 ● 黄、在跑 ● 强调色、闲着 ○、完成 ✔ 绿、失败 ✗ 红。 */
    static String[] glyph(AgentRoster.State st) {
        return switch (st) {
            case NEEDS_YOU -> new String[]{"●", "agent-warn"};
            case WORKING -> new String[]{"●", "agent-bullet-run"};
            case READY -> new String[]{"○", "agent-dim"};
            case DONE -> new String[]{"✔", "agent-ok"};
            case FAILED -> new String[]{"✗", "agent-err"};
        };
    }

    /** 选中那趟的详情（Codex 的 task details）。 */
    private List<List<Seg>> details(AgentRoster.Row r, int w) {
        List<List<Seg>> out = new ArrayList<>();
        w = Math.max(16, w);
        out.add(clipRow(List.of(Seg.of(r.label(), r.main() ? "agent-title" : r.face()), Seg.of(" · " + (r.desc() == null ? "" : r.desc().replace('\n', ' ')), "agent-title")), w));
        String[] g = glyph(r.state());
        List<Seg> st = new ArrayList<>(List.of(Seg.of(g[0] + " " + r.state().label(), g[1])));
        StringBuilder sb = new StringBuilder();
        if (r.elapsedMs() > 0) sb.append(" · ").append(AgentWorkbench.elapsed(r.elapsedMs() / 1000));
        if (!r.main()) sb.append(" · ").append(Msg.f("%d steps", r.steps()));
        if (r.background()) sb.append(" · ").append(Msg.t("background"));
        st.add(Seg.of(sb.toString(), "agent-dim"));
        out.add(clipRow(st, w));
        out.add(List.of());
        List<Seg> meta = new ArrayList<>();
        meta.add(Seg.of(Msg.t("Agent:") + " ", "agent-dim"));
        meta.add(Seg.of(r.type() == null ? "—" : r.type()));
        meta.add(Seg.of("   " + Msg.t("Model:") + " ", "agent-dim"));
        meta.add(Seg.of(r.model() == null ? Msg.t("(none selected)") : r.model()));
        out.add(clipRow(meta, w));
        if (r.attention() != null) {
            out.add(List.of());
            out.add(List.of(Seg.of(Msg.t("Needs you"), "agent-warn")));
            for (String l : Blocks.wrap(r.attention(), w).stream().limit(3).toList()) out.add(List.of(Seg.of(l)));
        }
        if (r.activity() != null) {
            out.add(List.of());
            out.add(List.of(Seg.of(Msg.t("Now"), "agent-title")));
            out.add(clipRow(List.of(Seg.of(r.activity(), "agent-accent")), w));
        }
        if (!r.recent().isEmpty()) {
            out.add(List.of());
            out.add(List.of(Seg.of(Msg.t("Latest steps"), "agent-title")));
            for (SubStep s : r.recent()) {
                List<Seg> l = new ArrayList<>();
                l.add(Seg.of(s.summary() == null ? "◦ " : s.error() ? "✗ " : "· ", s.summary() == null ? "agent-bullet-run" : s.error() ? "agent-err" : "agent-dim"));
                l.add(Seg.of(s.name(), s.error() ? "agent-err" : "agent-accent"));
                if (s.args() != null && !s.args().isEmpty()) l.add(Seg.of(" " + s.args()));
                if (s.summary() != null && !s.summary().isEmpty()) l.add(Seg.of("  " + s.summary(), "agent-dim"));
                out.add(clipRow(l, w));
            }
        }
        if (r.lastMessage() != null && !r.lastMessage().isBlank()) {
            out.add(List.of());
            out.add(List.of(Seg.of(Msg.t("Last message"), "agent-title")));
            List<String> ls = new ArrayList<>();
            for (String l : Blocks.lines(r.lastMessage().strip())) if (!l.isBlank()) ls.addAll(Blocks.wrap(l, w));
            for (int i = 0; i < ls.size() && i < 4; i++) out.add(List.of(Seg.of(i == 3 && ls.size() > 4 ? "…" : ls.get(i))));
        }
        if (r.prompt() != null && !r.prompt().isBlank()) {
            out.add(List.of());
            out.add(List.of(Seg.of(Msg.t("Task"), "agent-title")));
            List<String> ls = Blocks.wrap(r.prompt().strip().replace('\n', ' '), w);
            for (int i = 0; i < ls.size() && i < 3; i++) out.add(List.of(Seg.of(i == 2 && ls.size() > 3 ? "…" : ls.get(i), "agent-dim")));
        }
        return out;
    }

    private static List<Seg> fit(List<Seg> left, int w, String right, String rf) {
        List<Seg> row = new ArrayList<>(left);
        int gap = w - Seg.width(left) - Width.stringWidth(right) - 2;
        if (gap >= 2) { row.add(Seg.of(" ".repeat(gap))); row.add(Seg.of(right, rf)); }
        return clipRow(row, w);
    }

    /** 一行按显示宽度截到 w（超出的段截掉、末段加 …）。 */
    static List<Seg> clipRow(List<Seg> row, int w) {
        if (Seg.width(row) <= w) return row;
        List<Seg> out = new ArrayList<>();
        int used = 0;
        for (Seg s : row) {
            int sw = Width.stringWidth(s.text());
            if (used + sw <= w) { out.add(s); used += sw; continue; }
            int room = w - used;
            if (room > 0) out.add(Seg.of(Width.ellipsize(s.text(), room), s.face()));
            break;
        }
        return out;
    }

    private static String lpad(String s, int w) {
        int n = w - Width.stringWidth(s);
        return n > 0 ? " ".repeat(n) + s : s;
    }

    private static List<Seg> pad(List<Seg> row, int w) {
        List<Seg> r = clipRow(row, w);
        int gap = w - Seg.width(r);
        if (gap <= 0) return r;
        List<Seg> out = new ArrayList<>(r);
        out.add(Seg.of(" ".repeat(gap)));
        return out;
    }
}
