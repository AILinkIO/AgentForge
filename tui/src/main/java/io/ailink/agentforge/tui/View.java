package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.text.GapText;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayList;
import java.util.List;

/**
 * 折行、视觉行、让 point 可见、滚动、屏幕坐标 ⇔ buffer 位置。移植自 coxswain {@code coxn.view}，多了不可见文字（折叠）：
 *
 * <p><b>显示行</b>（DLine）= 从一个可见的换行之后到下一个可见的换行之间的文字（中间被隐藏的换行不算，所以折起来的
 * 块并进它前面那一行）；隐藏的那段若有替代文字（{@link Overlay#display}），就在原位画那串字。
 * 一条显示行按窗口宽折成若干<b>视觉行</b>（Row）。视觉行用它的起始位置标识；窗口的 start 是某条视觉行的起点
 * （可以落在一行中间 —— 一行比窗口还高时也滚得动）。
 *
 * <p>折法：默认按字折（最后一列放 \，Emacs 默认）；buffer 局部 {@code word-wrap} 开着时按词折（visual-line-mode：
 * 断在空白后、宽字前后，不画 \，整列都用）；{@code truncate-lines} 开着时不折，横向靠 hscroll。
 * 续行前缀：行首落在带 {@link Overlay#wrapPrefix} 的覆盖层里时，每条续行先画那串字（字形的 pos 记 -1，不对应 buffer 位置）。
 * 布局按 [buffer 的 displayTick、宽度、模式] 缓存在窗口上。
 */
final class View {
    /** 一个字形：pos = 它来自 buffer 的哪个位置（替代文字的字都记成隐藏段的起点）。 */
    record Glyph(int pos, int cp, int width, boolean tab, Overlay display, String face) {
        Glyph(int pos, int cp, int width, boolean tab, Overlay display) { this(pos, cp, width, tab, display, null); }
    }

    /** 前缀串 ⇒ 字形（pos = -1）。 */
    static List<Glyph> prefixGlyphs(String s, String face) {
        List<Glyph> out = new ArrayList<>();
        if (s == null) return out;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            out.add(new Glyph(-1, cp, Width.cpWidth(cp), false, null, face));
        }
        return out;
    }

    static List<Glyph> prefixGlyphs(List<Seg> segs) {
        List<Glyph> out = new ArrayList<>();
        for (Seg sg : segs) out.addAll(prefixGlyphs(sg.text(), sg.face()));
        return out;
    }

    static int width(List<Glyph> gs) { int w = 0; for (Glyph g : gs) w += g.width; return w; }

    static final class Row {
        int start;
        List<Glyph> glyphs = new ArrayList<>();
    }

    static final class DLine {
        int start, end, next;      // end = 结尾换行的位置（或文本末尾）；next = 下一条显示行的起点，没有 -1
        List<Row> rows = new ArrayList<>();
    }

    /** 视觉行：所在显示行的起点 + 第几段。 */
    record VLine(int dls, int idx) {}

    private final Editor ed;

    View(Editor ed) { this.ed = ed; }

    // ── 几何参数 ──────────────────────────────────────────────────────

    static boolean truncate(Buffer b) { return b.flag("truncate-lines"); }
    static boolean wordWrap(Buffer b) { return b.flag("word-wrap"); }

    int tabWidth(Buffer b) {
        Object t = b.get("tab-width");
        return t instanceof Integer i ? i : ed.options.getInt("tab-width");
    }

    /** 每段最多几列：按字折时最后一列留给续行标记 \。 */
    int wrapWidth(Window w) {
        int tw = ed.textSize(w)[0];
        Buffer b = w.buffer;
        return truncate(b) || wordWrap(b) ? tw : Math.max(1, tw - 1);
    }

    // ── 布局 ──────────────────────────────────────────────────────────

    /** pos 所在显示行的起点：往回越过被隐藏的换行。 */
    static int displayLineStart(Buffer b, int pos) {
        GapText t = b.text;
        pos = Math.max(0, Math.min(pos, t.length()));
        int ls = t.lineStart(t.lineOf(pos));
        while (ls > 0) {
            int[] iv = b.invisibleAt(ls - 1);
            if (iv == null) break;
            ls = t.lineStart(t.lineOf(iv[0]));
        }
        return ls;
    }

    DLine layout(Window w, int dls) {
        Buffer b = w.buffer;
        int ww = wrapWidth(w), tab = tabWidth(b);
        boolean tr = truncate(b), wwrap = wordWrap(b);
        long key = b.displayTick() * 31 + ww * 7L + (tr ? 1 : 0) + (wwrap ? 2 : 0) + tab * 1_000_000_007L;
        if (w.layoutKey != key) {
            w.layoutCache.clear();
            w.layoutKey = key;
        }
        DLine d = w.layoutCache.get(dls);
        if (d == null) {
            d = layoutLine(b, dls, ww, tr, wwrap, tab);
            if (w.layoutCache.size() > 4096) w.layoutCache.clear();
            w.layoutCache.put(dls, d);
        }
        return d;
    }

    static DLine layoutLine(Buffer b, int dls, int wrapW, boolean trunc, boolean wordWrap, int tab) {
        GapText t = b.text;
        int len = t.length();
        List<Glyph> gs = new ArrayList<>();
        DLine d = new DLine();
        d.start = dls;
        d.next = -1;
        int pos = dls;
        while (true) {
            if (pos >= len) { d.end = len; break; }
            int[] iv = b.invisibleAt(pos);
            if (iv != null) {
                Overlay ov = b.displayOverlayAt(iv[0]);
                if (ov != null && iv[0] >= dls) {
                    if (ov.displaySegs != null) {
                        for (Seg sg : ov.displaySegs) {
                            String s = sg.text();
                            for (int i = 0; i < s.length(); ) {
                                int cp = s.codePointAt(i);
                                i += Character.charCount(cp);
                                gs.add(new Glyph(iv[0], cp, Width.cpWidth(cp), false, ov, sg.face()));
                            }
                        }
                    } else {
                        String s = ov.display;
                        for (int i = 0; i < s.length(); ) {
                            int cp = s.codePointAt(i);
                            i += Character.charCount(cp);
                            gs.add(new Glyph(iv[0], cp, Width.cpWidth(cp), false, ov));
                        }
                    }
                }
                pos = iv[1];
                continue;
            }
            int cp = Character.codePointAt(t, pos);
            if (cp == '\n') { d.end = pos; d.next = pos + 1; break; }
            boolean isTab = cp == '\t';
            gs.add(new Glyph(pos, cp, isTab ? 1 : Width.cpWidth(cp), isTab, null));
            pos += Character.charCount(cp);
        }
        // 续行前缀（wrap-prefix）：看行首那个字落在哪个覆盖层；前缀太宽（≥ 半行）就不要，免得一行只剩前缀
        //（覆盖层优先；没有就用 buffer 局部的 wrap-prefix）。行前缀（line-prefix，buffer 局部）画在每条显示行的第一段前面
        List<Glyph> prefix = List.of();
        if (!trunc) {
            Overlay po = len > dls ? b.wrapPrefixAt(dls) : null;
            if (po != null) prefix = prefixGlyphs(po.wrapPrefix);
            else if (b.get("wrap-prefix") instanceof String s) prefix = prefixGlyphs(s, b.get("line-prefix-face") instanceof String f ? f : null);
        }
        int prefixW = width(prefix);
        if (prefixW * 2 > wrapW) { prefix = List.of(); prefixW = 0; }
        // 第一行可以有自己的行前缀（first-line-prefix：输入框的「› 」，后面的行只垫空白）
        Object lpv = dls == 0 && b.get("first-line-prefix") instanceof String fl ? fl : b.get("line-prefix");
        List<Glyph> lp = lpv instanceof String s ? prefixGlyphs(s, b.get("line-prefix-face") instanceof String f ? f : null) : List.of();
        if (width(lp) * 2 > wrapW) lp = List.of();
        // 折成视觉行
        Row row = new Row();
        row.start = dls;
        d.rows.add(row);
        row.glyphs.addAll(lp);
        int col = width(lp), lastBreak = -1;      // lastBreak：当前行里可以断开的字形下标（新行从它开始）
        int minCol = col;                 // 行开头的前缀宽：前缀后面至少放一个字才断
        for (int i = 0; i < gs.size(); i++) {
            Glyph g = gs.get(i);
            int w = g.tab ? tab - col % tab : g.width;
            if (g.tab && w != g.width) { g = new Glyph(g.pos, g.cp, w, true, null); gs.set(i, g); }
            boolean canBreakHere = !row.glyphs.isEmpty() && g.pos != row.glyphs.getLast().pos;
            if (!trunc && col + w > wrapW && col > minCol && canBreakHere) {
                Row nr = new Row();
                nr.glyphs.addAll(prefix);
                if (wordWrap && lastBreak > 0 && lastBreak < row.glyphs.size()) {
                    List<Glyph> moved = new ArrayList<>(row.glyphs.subList(lastBreak, row.glyphs.size()));
                    row.glyphs.subList(lastBreak, row.glyphs.size()).clear();
                    nr.glyphs.addAll(moved);
                }
                nr.start = nr.glyphs.size() == prefix.size() ? g.pos : nr.glyphs.get(prefix.size()).pos;
                d.rows.add(nr);
                row = nr;
                col = 0;
                for (Glyph m : row.glyphs) col += m.width;   // 挪过来的字形（tab 宽度不重算，近似）
                minCol = prefixW;
                lastBreak = -1;
            }
            row.glyphs.add(g);
            col += g.width;
            if (wordWrap) {
                int n = row.glyphs.size();
                // 空白之后、宽字之后可以断（宽字之前：放不下时本来就按字折）；替代文字里不断
                if (g.display == null && g.pos >= 0 && (g.cp == ' ' || g.tab || g.width == 2)) lastBreak = n;
            }
        }
        return d;
    }

    // ── 视觉行 ────────────────────────────────────────────────────────

    VLine vlineAt(Window w, int pos) {
        Buffer b = w.buffer;
        pos = Math.max(0, Math.min(pos, b.length()));
        int dls = displayLineStart(b, pos);
        DLine d = layout(w, dls);
        int idx = 0;
        for (int i = 1; i < d.rows.size(); i++) if (d.rows.get(i).start <= pos) idx = i; else break;
        return new VLine(dls, idx);
    }

    Row row(Window w, VLine v) { return layout(w, v.dls).rows.get(v.idx); }

    int vstart(Window w, VLine v) { return row(w, v).start; }

    /** 视觉行的结束位置（不含）：下一段的起点，最后一段是行尾。 */
    int vend(Window w, VLine v) {
        DLine d = layout(w, v.dls);
        return v.idx + 1 < d.rows.size() ? d.rows.get(v.idx + 1).start : d.end;
    }

    boolean lastRow(Window w, VLine v) { return v.idx == layout(w, v.dls).rows.size() - 1; }

    VLine next(Window w, VLine v) {
        DLine d = layout(w, v.dls);
        if (v.idx + 1 < d.rows.size()) return new VLine(v.dls, v.idx + 1);
        if (d.next >= 0) return new VLine(d.next, 0);
        return null;
    }

    VLine prev(Window w, VLine v) {
        if (v.idx > 0) return new VLine(v.dls, v.idx - 1);
        if (v.dls <= 0) return null;
        int p = displayLineStart(w.buffer, v.dls - 1);
        return new VLine(p, layout(w, p).rows.size() - 1);
    }

    /** 从 v 走 n 条视觉行（n &lt; 0 往前），撞头就停。 */
    VLine step(Window w, VLine v, int n) {
        while (n > 0) { VLine x = next(w, v); if (x == null) break; v = x; n--; }
        while (n < 0) { VLine x = prev(w, v); if (x == null) break; v = x; n++; }
        return v;
    }

    static int cmp(VLine a, VLine b) {
        return a.dls != b.dls ? Integer.compare(a.dls, b.dls) : Integer.compare(a.idx, b.idx);
    }

    /** a 到 b（a ≤ b）隔几条视觉行，数到 cap 为止。 */
    int distance(Window w, VLine a, VLine b, int cap) {
        int n = 0;
        while (n < cap && cmp(a, b) < 0) {
            VLine x = next(w, a);
            if (x == null) break;
            a = x;
            n++;
        }
        return n;
    }

    // ── 让 point 可见 ─────────────────────────────────────────────────

    /** 调 start 让 point 落在窗口里：离得近就挪最少的视觉行，远就居中（照 coxswain ensure-point-visible）。 */
    void ensurePointVisible(Window w) {
        int h = ed.textSize(w)[1];
        VLine pv = vlineAt(w, w.point());
        VLine sv = vlineAt(w, w.start());
        if (cmp(pv, sv) < 0) {
            if (distance(w, pv, sv, h / 3 + 1) <= Math.max(1, h / 3)) w.setStart(vstart(w, pv));
            else w.setStart(vstart(w, step(w, pv, -(h / 2))));
        } else {
            int d = distance(w, sv, pv, 2 * h);
            // 跟着末尾的窗口：末尾总贴着窗口底（窗口变高了也往上多露几行，不在底下留空白）
            if (w.followTail && w.point() >= w.buffer.length()) w.setStart(vstart(w, step(w, pv, -(h - 1))));
            else if (d < h) w.setStart(vstart(w, sv));
            // 跟随末尾的窗口一次来了一大段：末尾贴窗口底，不居中（不留半屏空白）
            else if (w.followTail && w.point() >= w.buffer.length()) w.setStart(vstart(w, step(w, pv, -(h - 1))));
            else if (d - h < Math.max(1, h / 3)) w.setStart(vstart(w, step(w, pv, -(h - 1))));
            else w.setStart(vstart(w, step(w, pv, -(h / 2))));
        }
        ensureHscroll(w);
    }

    /** 截断模式：point 的列跑出窗口就横向滚（滚到居中）。 */
    private void ensureHscroll(Window w) {
        if (!truncate(w.buffer)) { w.hscroll = 0; return; }
        int col = columnInRow(w, w.point());
        int tw = ed.textSize(w)[0];
        int hs = w.hscroll;
        if (hs > 0 && col <= hs || col >= hs + tw - 1) w.hscroll = Math.max(0, col - tw / 2);
    }

    /** point 在它那条视觉行里的显示列。 */
    int columnInRow(Window w, int pos) {
        Row r = row(w, vlineAt(w, pos));
        int col = 0;
        for (Glyph g : r.glyphs) {
            if (g.pos >= pos) break;
            col += g.width;
        }
        return col;
    }

    /** 视觉行里显示列 ≥ col 的位置（竖向移动、鼠标点击用）。不是一行的最后一段时不停在段尾（那属于下一段）。 */
    int posAtCol(Window w, VLine v, int col) {
        Row r = row(w, v);
        int c = 0;
        for (Glyph g : r.glyphs) {
            if (c + g.width > col) return g.pos < 0 ? r.start : g.pos;
            c += g.width;
        }
        if (!lastRow(w, v)) {
            return r.glyphs.isEmpty() || r.glyphs.getLast().pos < 0 ? r.start : r.glyphs.getLast().pos;
        }
        return layout(w, v.dls).end;
    }

    // ── 滚动 ──────────────────────────────────────────────────────────

    /** start 往下（n&gt;0）/ 往上挪 n 条视觉行，point 跟着进窗口。到头了抛 UserError。 */
    void scroll(Window w, int n) {
        int h = ed.textSize(w)[1];
        VLine sv = vlineAt(w, w.start());
        VLine nv = step(w, sv, n);
        if (n > 0 && nv.equals(sv)) throw new UserError("End of buffer");
        if (n < 0 && nv.equals(sv)) throw new UserError("Beginning of buffer");
        w.setStart(vstart(w, nv));
        VLine last = step(w, nv, h - 1);
        VLine pv = vlineAt(w, w.point());
        if (cmp(pv, nv) < 0) w.setPoint(vstart(w, nv));
        else if (cmp(pv, last) > 0) w.setPoint(vstart(w, last));
        // 跟随末尾的窗口滚回到底：point 回到末尾，接着跟
        if (w.followTail && n > 0 && next(w, last) == null) w.setPoint(w.buffer.length());
    }

    /** 窗口正文区第 sy 行、第 sx 列（相对正文区左上角）对应的位置。 */
    int posAtScreen(Window w, int sy, int sx) {
        VLine sv = vlineAt(w, w.start());
        VLine v = step(w, sv, sy);
        if (distance(w, sv, v, sy + 1) < sy) return w.buffer.length();
        return posAtCol(w, v, Math.max(0, sx) + w.hscroll);
    }
}
