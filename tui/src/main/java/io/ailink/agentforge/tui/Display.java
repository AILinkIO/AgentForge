package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.Face;
import io.ailink.agentforge.tui.term.Grid;
import io.ailink.agentforge.tui.text.Width;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 编辑器状态 ⇒ 期望矩阵 + 光标（coxswain {@code coxn.display}）。层次（后画的压在上面）：
 * 各窗口（header line、正文、mode line、竖分隔线）→ echo area / minibuffer 输入行 → helm 候选（贴底）或 minibuffer 浮框
 * → 补全框 → 用户浮框 → which-key。
 */
final class Display {
    private final Editor ed;
    int[] cursor;
    private final Map<Window, Boolean> eob = new IdentityHashMap<>();

    Display(Editor ed) { this.ed = ed; }

    boolean eobVisible(Window w) { return eob.getOrDefault(w, true); }

    private Face f(String name) { return ed.resolveFace(name); }

    Grid render() {
        Grid g = new Grid(ed.cols, ed.rows, f("default"));
        Map<Window, Rect> rects = ed.windowRects();
        int[] selCursor = null;
        for (Map.Entry<Window, Rect> e : rects.entrySet()) {
            Window w = e.getKey();
            Rect r = e.getValue();
            ed.view.ensurePointVisible(w);
            int[] c = drawWindow(g, w, r);
            if (w == ed.selected) selCursor = c;
        }
        int[] miniCursor = drawEcho(g);
        boolean popupMini = ed.mb != null && miniPopup();
        if (ed.mb != null && !popupMini) drawHelm(g);
        drawCompletionPopup(g, selCursor);
        for (Popup p : ed.popups) drawPopup(g, p, selCursor);
        drawWhichKey(g);
        if (popupMini) miniCursor = drawMinibufferPopup(g);
        cursor = ed.mb != null ? miniCursor : selCursor;
        return g;
    }

    // ── 窗口 ──────────────────────────────────────────────────────────

    private record Span(int a, int b, Object face, boolean extend) {
        Span(int a, int b, Object face) { this(a, b, face, false); }
    }

    /** 窗口 w 在 [a, b) 里要上色的区间，低 ⇒ 高（后面的压前面的）。 */
    private List<Span> spans(Window w, int a, int b) {
        Buffer buf = w.buffer;
        List<Span> out = new ArrayList<>();
        List<Overlay> ovs = buf.overlaysIn(a, b + 1);
        for (int i = ovs.size() - 1; i >= 0; i--) {
            Overlay o = ovs.get(i);
            Object face = o.faceSpec != null ? o.faceSpec : o.face;
            if (face != null) out.add(new Span(o.start(), o.end(), face, o.extend));
        }
        boolean sel = w == ed.selected;
        if (sel && buf.markActive() && ed.isearch == null) {
            int m = buf.mark, p = w.point();
            out.add(new Span(Math.min(m, p), Math.max(m, p), "region"));
        }
        if (sel && ed.isearch != null) {
            for (int[] mt : ed.isearch.lazyMatches(buf, a, b)) out.add(new Span(mt[0], mt[1], "lazy-highlight"));
            int[] cur = ed.isearch.match();
            if (cur != null && !ed.isearch.failing()) out.add(new Span(cur[0], cur[1], "isearch"));
        }
        return out;
    }

    private Face faceAt(List<Span> spans, int pos, List<Object> stack) {
        stack.clear();
        for (Span s : spans) if (s.a <= pos && pos < s.b) stack.add(s.face);
        return ed.resolveStack(stack);
    }

    /** 画一个窗口；⇒ 光标 {row, col}（point 不在可见区 ⇒ null）。 */
    private int[] drawWindow(Grid g, Window w, Rect r) {
        View v = ed.view;
        Buffer buf = w.buffer;
        boolean selected = w == ed.selected;
        int y0 = r.y();
        String bufFace = buf.get("buffer-face") instanceof String bf ? bf : null;
        int xEnd = r.x() + r.w() - (r.divider() ? 1 : 0);
        if (buf.topRows != null) {
            String tf = buf.get("top-rows-face") instanceof String s ? s : "default";
            for (List<Seg> row : buf.topRows.apply(w)) {
                g.fill(y0, r.x(), xEnd, f(tf));
                drawSegs(g, y0, r.x(), xEnd, row, tf);
                y0++;
            }
        }
        if (ed.hasHeaderLine(w)) {
            List<Seg> segs = buf.headerLine.apply(w);
            String hf = buf.get("header-line-face") instanceof String s ? s : "header-line";
            g.fill(y0, r.x(), xEnd, f(hf));
            drawSegs(g, y0, r.x(), xEnd, segs, hf);
            y0++;
        }
        int[] ts = ed.textSize(w);
        int tw = ts[0], th = ts[1];
        if (bufFace != null) for (int i = 0; i < th; i++) g.fill(y0 + i, r.x(), xEnd, f(bufFace));
        List<List<Seg>> footer = buf.footer != null ? buf.footer.apply(w) : List.of();
        for (int i = 0; i < footer.size(); i++) {
            int fy = y0 + th + i;
            String ff = bufFace != null ? bufFace : "default";
            g.fill(fy, r.x(), xEnd, f(ff));
            drawSegs(g, fy, r.x(), xEnd, footer.get(i), ff);
        }
        int gw = ed.gutterWidth(w);
        int tx = r.x() + gw;
        boolean trunc = View.truncate(buf), wrapByWord = View.wordWrap(buf);
        int hs = trunc ? w.hscroll : 0;
        int p = w.point();
        // 先排出看得见的视觉行
        List<View.VLine> vis = new ArrayList<>();
        View.VLine cur = v.vlineAt(w, w.start());
        boolean atEnd = false;
        while (vis.size() < th) {
            vis.add(cur);
            View.VLine n = v.next(w, cur);
            if (n == null) { atEnd = true; break; }
            cur = n;
        }
        eob.put(w, atEnd);
        int a = v.vstart(w, vis.getFirst());
        int b = v.vend(w, vis.getLast());
        List<Span> spans = spans(w, a, b);
        if (bufFace != null) spans.addFirst(new Span(0, buf.length() + 1, bufFace, true));
        List<Object> stack = new ArrayList<>();
        int[] cursorAt = null;
        int curLine = buf.text.lineOf(p);
        for (int sy = 0; sy < vis.size(); sy++) {
            View.VLine vl = vis.get(sy);
            View.Row row = v.row(w, vl);
            View.DLine dl = v.layout(w, vl.dls());
            boolean last = vl.idx() == dl.rows.size() - 1;
            int sr = y0 + sy;
            if (gw > 0) {
                int line = buf.text.lineOf(row.start);
                Face lf = f(line == curLine ? "line-number-current-line" : "line-number");
                g.fill(sr, r.x(), tx, lf);
                if (vl.idx() == 0) {
                    String num = String.valueOf(line + 1);
                    g.putString(sr, r.x() + gw - 1 - num.length(), num, lf, tx);
                }
            }
            boolean hasPoint = row.start <= p && (last ? p <= dl.end : p < v.vend(w, vl));
            int col = 0;
            int limit = tx + tw;
            for (int i = 0; i < row.glyphs.size(); i++) {
                View.Glyph gl = row.glyphs.get(i);
                if (hasPoint && cursorAt == null && gl.pos() >= p) cursorAt = new int[]{sr, tx + Math.max(0, Math.min(col - hs, tw - 1))};
                Face face;
                if (gl.pos() < 0) {
                    // 续行前缀：只用前缀自己的 face
                    face = gl.face() != null ? f(gl.face()) : bufFace != null ? f(bufFace) : f("default");
                } else if (gl.display() != null) {
                    stack.clear();
                    for (Span s : spans) if (s.a <= gl.pos() && gl.pos() < s.b) stack.add(s.face);
                    if (gl.display().displayFace != null) stack.add(gl.display().displayFace);
                    if (gl.face() != null) stack.add(gl.face());
                    face = ed.resolveStack(stack);
                } else face = faceAt(spans, gl.pos(), stack);
                int sc = col - hs;
                if (trunc && sc + gl.width() > tw - 1 && i < row.glyphs.size() - 1) {
                    if (sc < tw - 1) g.fill(sr, tx + Math.max(0, sc), tx + tw - 1, face);
                    g.set(sr, tx + tw - 1, "$", f("fringe"));
                    break;
                }
                if (sc >= 0) {
                    if (gl.tab()) g.fill(sr, tx + sc, Math.min(limit, tx + sc + gl.width()), face);
                    else g.putChar(sr, tx + sc, gl.cp(), face, limit);
                }
                col += gl.width();
            }
            if (hasPoint && cursorAt == null) cursorAt = new int[]{sr, tx + Math.max(0, Math.min(col - hs, tw - 1))};
            // :extend —— 行尾那个换行（续行看下一段的起点）落在铺到行尾的 face 里：剩下的格子整片涂上
            int eolPos = last ? dl.end : v.vend(w, vl);
            Face ext = null;
            if (!trunc && col - hs < tw) {
                stack.clear();
                for (Span s : spans) if (s.extend && s.a <= eolPos && eolPos < s.b) stack.add(s.face);
                if (!stack.isEmpty()) ext = ed.resolveStack(stack);
            }
            if (ext != null) {
                g.fill(sr, tx + Math.max(0, col - hs), tx + tw, ext);
            } else if (last && col - hs < tw && col - hs >= 0 && dl.end < buf.length()) {
                // 区域跨过换行时把行尾一格也涂上
                Face ef = faceAt(spans, dl.end, stack);
                if (!ef.equals(f("default"))) g.set(sr, tx + col - hs, " ", ef);
            }
            if (hs > 0 && !row.glyphs.isEmpty()) g.set(sr, tx, "$", f("fringe"));
            if (!last && !trunc && !wrapByWord) g.set(sr, tx + tw - 1, "\\", f("fringe"));
        }
        // 空 buffer 的占位提示（输入框里那句暗色的「问点什么…」）：画在第一行的行前缀后面，光标不动
        if (buf.length() == 0 && buf.get("placeholder") instanceof String ph && !ph.isEmpty() && !vis.isEmpty()) {
            Object lpv = buf.get("first-line-prefix") instanceof String fl ? fl : buf.get("line-prefix");
            int px = tx + (lpv instanceof String lp ? Width.stringWidth(lp) : 0);
            String pf = buf.get("placeholder-face") instanceof String s ? s : "shadow";
            Face base = bufFace != null ? f(bufFace) : f("default");
            g.putString(y0, px, ph, base.merge(ed.faceSpec(pf)), tx + tw);
        }
        if (ed.hasModeLine(w)) drawModeLine(g, w, r, selected);
        if (r.divider()) {
            int cx = r.x() + r.w() - 1;
            for (int i = 0; i < r.h(); i++) g.set(r.y() + i, cx, "│", f(i == r.h() - 1 && ed.hasModeLine(w) ? "mode-line-inactive" : "vertical-border"));
        }
        return cursorAt;
    }

    private void drawModeLine(Grid g, Window w, Rect r, boolean selected) {
        String face = w.buffer.get("mode-line-face") instanceof String mf ? mf : selected ? "mode-line" : "mode-line-inactive";
        int sr = r.y() + r.h() - 1;
        int x0 = r.x(), x1 = r.x() + r.w() - (r.divider() ? 1 : 0);
        g.fill(sr, x0, x1, f(face));
        ModeLine ml = w.buffer.modeLine != null ? w.buffer.modeLine : ed.modeLine();
        ModeLine.Ctx ctx = new ModeLine.Ctx(ed, w, selected);
        List<Seg> left = ml.render(ctx, false), right = ml.render(ctx, true);
        int c = drawSegs(g, sr, x0, x1, left, face);
        int rw = Seg.width(right);
        drawSegs(g, sr, Math.max(c + 1, x1 - rw - 1), x1, right, face);
    }

    /** 画一串段，没写 face 的用 dflt；⇒ 下一列。 */
    private int drawSegs(Grid g, int row, int x, int limit, List<Seg> segs, String dflt) {
        int c = x;
        for (Seg s : segs) {
            if (c >= limit) break;
            // 段自己的 face 压在这一行的底色上（没写底色的段沿用行的底色）
            Face face = s.face() == null || s.face().equals(dflt) ? f(dflt) : ed.resolveFace(dflt).merge(ed.faceSpec(s.face()));
            c = g.putString(row, c, s.text(), face, limit);
        }
        return c;
    }

    // ── echo area / minibuffer ───────────────────────────────────────

    private boolean miniPopup() {
        return "popup".equals(ed.options.getString("minibuffer-display")) && ed.cols >= 40 && ed.rows >= 12;
    }

    private String prefixArgDesc() {
        Object pa = ed.prefixArg;
        if (pa instanceof int[] a) return a[0] == 4 ? "C-u" : "C-u " + a[0];
        if (pa == Editor.MINUS) return "C-u -";
        if (pa instanceof Integer i) return "C-u " + i;
        return null;
    }

    /** 提示符 + 输入：输入太长从左边裁掉，保证光标看得见。⇒ {prompt, input, cursorOff}。 */
    private Object[] miniInputLine(int width) {
        String prompt = ed.mb.req.prompt;
        String in = ed.minibufferInput();
        int pt = Math.min(ed.miniWindow.point(), in.length());
        int pw = Width.stringWidth(prompt);
        int before = Width.stringWidth(in.substring(0, pt));
        int avail = width - pw - 1;
        int skip = Math.max(0, before - avail);
        int shown = 0, acc = 0;
        while (shown < in.length() && acc < skip) { acc += Width.cpWidth(in.codePointAt(shown)); shown += Character.charCount(in.codePointAt(shown)); }
        return new Object[]{prompt, in.substring(shown), pw + before - acc};
    }

    private int[] drawEcho(Grid g) {
        int sr = ed.rows - 1;
        // echo-area = overlay：没东西要说就不画（那一行归窗口）；有话说时整行盖上
        if (ed.echoOverlay() && ed.mb == null && ed.isearch == null && !ed.escPrefix && ed.pending.isEmpty()
                && prefixArgDesc() == null && (ed.message == null || ed.message.isEmpty())) return null;
        if (!(ed.echoOverlay() && ed.mb == null && ed.isearch == null && !ed.escPrefix && ed.pending.isEmpty() && prefixArgDesc() == null))
            g.fill(sr, 0, ed.cols, f("default"));
        if (ed.mb != null) {
            if (miniPopup()) return null;
            Object[] l = miniInputLine(ed.cols);
            int c = g.putString(sr, 0, (String) l[0], f("minibuffer-prompt"));
            g.putString(sr, c, (String) l[1], f("default"));
            return new int[]{sr, Math.min(ed.cols - 1, (Integer) l[2])};
        }
        if (ed.isearch != null) {
            g.putString(sr, 0, ed.isearch.prompt(), f(ed.isearch.failing() ? "error" : "minibuffer-prompt"));
            return null;
        }
        if (ed.escPrefix) { g.putString(sr, 0, "ESC-", f("default")); return null; }
        String pa = prefixArgDesc();
        if (!ed.pending.isEmpty() || pa != null) {
            List<String> parts = new ArrayList<>();
            if (pa != null) parts.add(pa);
            parts.addAll(ed.pending);
            g.putString(sr, 0, String.join(" ", parts) + "-", f("default"));
            return null;
        }
        if (ed.message != null) {
            String m = ed.message;
            int nl = m.indexOf('\n');
            String line = nl >= 0 ? m.substring(0, nl) : m;
            if (ed.echoOverlay()) {
                // 叠在窗口最后一行上：只盖住消息那一截（右边的读数照样看得见）
                int w = Math.min(ed.cols, Width.stringWidth(line) + 3);
                g.fill(sr, 0, w, f("default"));
                g.putString(sr, 1, line, f("echo-message"), w);
            } else g.putString(sr, 0, line, f("default"));
        }
        return null;
    }

    /** helm 的来源名和 buffer 名（M-x ⇒ Emacs Commands / *helm M-x*）。 */
    private String[] helmSource(MinibufferRequest r) {
        String p = r.prompt.replaceAll("\\s*\\((default|默认)[^)]*\\)", "").replaceAll("[:：]\\s*$", "").trim();
        if (r.source != null) return new String[]{r.source, "*helm " + r.source.toLowerCase() + "*"};
        if (p.contains("M-x")) return new String[]{"Emacs Commands", "*helm M-x*"};
        return new String[]{p, "*helm " + p.toLowerCase() + "*"};
    }

    /**
     * minibuffer 有候选时照 spacemacs 的 helm 画在底部：来源标题、候选（固定高度；输入的词高亮；选中行整行高亮；右边注释）、
     * helm 的 mode line（*helm M-x*  L3  [27 Candidate(s)]  键的提示），最下面一行是提示符和输入。
     */
    private void drawHelm(Grid g) {
        List<String> items = ed.minibufferCandidates();
        if (items == null) return;
        int cols = ed.cols, rows = ed.rows;
        int n = Math.max(1, Math.min(15, rows * 2 / 5 - 2));       // helm 那个窗口大约四成屏高
        int total = items.size();
        int sel = Math.min(ed.mb.sel, Math.max(0, total - 1));
        int top = Math.max(0, sel - (Math.min(n, Math.max(1, total)) - 1));
        String input = ed.minibufferInput();
        String[] src = helmSource(ed.mb.req);
        int yHead = rows - 3 - n, yMl = rows - 2;
        if (yHead < 0) return;
        g.fill(yHead, 0, cols, f("helm-source-header"));
        g.putString(yHead, 1, src[0], f("helm-source-header"));
        String cnt = total + " ";
        g.putString(yHead, Math.max(1, cols - Width.stringWidth(cnt) - 1), cnt, f("helm-source-header"));
        for (int i = 0; i < n; i++) {
            int idx = top + i, sr = yHead + 1 + i;
            if (idx >= total) { g.fill(sr, 0, cols, f("default")); continue; }
            String item = items.get(idx);
            boolean s = idx == sel;
            Face base = f(s ? "helm-selection" : "default");
            g.fill(sr, 0, cols, base);
            // 选中行：左边一道竖条 + 整行高亮
            if (s) g.set(sr, 0, "▌", base.merge(ed.faceSpec("helm-match")));
            g.putString(sr, 2, item, base, cols - 1);
            for (int[] sp : Matcher.spans(item, input))
                g.putString(sr, 2 + Width.stringWidth(item.substring(0, sp[0])), item.substring(sp[0], sp[1]),
                        f(s ? "helm-match-selected" : "helm-match"), cols - 1);
            if (ed.mb.req.annotate != null) {
                String an = ed.mb.req.annotate.apply(ed, item);
                if (an != null && !an.isEmpty()) {
                    int aw = Width.stringWidth(an);
                    g.putString(sr, Math.max(0, cols - aw - 1), an, f(s ? "helm-selection" : "shadow"));
                }
            }
        }
        if (total == 0) g.putString(yHead + 1, 2, I18n.t("[No match]"), f("shadow"));
        g.fill(yMl, 0, cols, f("mode-line"));
        int c = g.putString(yMl, 0, " " + src[1], f("mode-line-buffer-id"));
        c = g.putString(yMl, c + 3, "L" + (total == 0 ? 0 : sel + 1), f("mode-line"));
        c = g.putString(yMl, c + 3, "[" + total + " Candidate" + (total != 1 ? "(s)" : "") + "]", f("mode-line"));
        String hint = I18n.t("C-n/C-p select · RET confirm · TAB complete · C-g quit ");
        if (cols - c > 4 + Width.stringWidth(hint)) g.putString(yMl, cols - Width.stringWidth(hint), hint, f("shadow-mode-line"));
    }

    /** minibuffer 浮框（vertico-posframe）：屏幕上方居中，第一行提示 + 输入，横线下面是候选。⇒ 光标。 */
    private int[] drawMinibufferPopup(Grid g) {
        int cols = ed.cols, rows = ed.rows;
        int inner = Math.min(110, Math.min(cols - 4, Math.max(50, cols * 2 / 3)));
        List<String> items = ed.minibufferCandidates();
        int total = items == null ? 0 : items.size();
        int maxn = Math.min(10, Math.max(1, rows - 8));
        int n = Math.min(maxn, total);
        int sel = Math.min(ed.mb.sel, Math.max(0, total - 1));
        int top = Math.max(0, sel - (n - 1));
        Object[] l = miniInputLine(inner - 2);
        List<List<Seg>> rs = new ArrayList<>();
        rs.add(List.of(Seg.of(" ", "popup"), Seg.of((String) l[0], "popup-prompt"), Seg.of((String) l[1], "popup")));
        if (items != null) rs.add(List.of(Seg.of("─".repeat(inner), "popup-border")));
        if (items != null && total == 0) rs.add(List.of(Seg.of("  " + I18n.t("[No match]"), "popup-detail")));
        for (int i = 0; i < n; i++) {
            String item = items.get(top + i);
            String an = ed.mb.req.annotate != null ? ed.mb.req.annotate.apply(ed, item) : null;
            int aw = an == null ? 0 : Width.stringWidth(an);
            String label = Width.ellipsize(item, Math.max(4, inner - 3 - (an != null ? aw + 2 : 0)));
            List<Seg> row = new ArrayList<>(List.of(Seg.of(" ", "popup"), Seg.of(label, "popup")));
            if (an != null) {
                row.add(Seg.of(" ".repeat(Math.max(1, inner - 1 - Width.stringWidth(label) - aw - 1)), "popup"));
                row.add(Seg.of(an, "popup-detail"));
            }
            rs.add(row);
        }
        int h = 2 + rs.size();
        int x = (cols - inner - 2) / 2;
        int y = Math.max(0, Math.min(rows / 4, rows - 1 - h));
        int topRow = top;
        drawBox(g, x, y, inner, rs, i -> items != null && total > 0 && i >= 2 && topRow + i - 2 == sel ? "popup-selected" : null,
                total > 0 ? new int[]{top, total, 2} : null, null);
        return new int[]{y + 1, Math.min(x + inner, x + 2 + (Integer) l[2])};
    }

    // ── 浮框 ──────────────────────────────────────────────────────────

    /**
     * 在 (x, y) 画框：rows 是内容行，innerW 是内容宽度（不含边框）。rowFace(i) ⇒ 第 i 行的底色 face（选中行）或 null；
     * scroll = {top, total, from}：内容是更长列表里的一段时右边框上画滚动条。⇒ {总宽, 总高}。
     */
    void drawBox(Grid g, int x, int y, int innerW, List<List<Seg>> rows, IntFunction<String> rowFace, int[] scroll, String title) {
        Face border = f("popup-border"), base = f("popup");
        int n = rows.size(), right = x + innerW + 1;
        int ta = -1, tb = -1;
        if (scroll != null) {
            int top = scroll[0], total = scroll[1], from = scroll[2], m = n - from;
            if (m > 0 && total > m) {
                int len = Math.max(1, m * m / total);
                int s0 = Math.min(top * m / total, m - len);
                ta = from + s0;
                tb = ta + len;
            }
        }
        g.set(y, x, "╭", border);
        g.putString(y, x + 1, "─".repeat(innerW), border, right);
        if (title != null) g.putString(y, x + 2, " " + title + " ", f("popup-prompt"), right);
        g.set(y, right, "╮", border);
        for (int i = 0; i < n; i++) {
            int sr = y + 1 + i;
            String rf = rowFace != null ? rowFace.apply(i) : null;
            Face rowBase = rf != null ? f(rf) : base;
            g.set(sr, x, "│", border);
            g.fill(sr, x + 1, right, rowBase);
            int c = x + 1;
            for (Seg s : rows.get(i)) {
                if (c >= right) break;
                Face sf = rf != null ? rowBase.merge(s.face() != null && s.face().contains("match") ? Face.EMPTY.withBold(true) : Face.EMPTY)
                        : s.face() != null ? base.merge(ed.faceSpec(s.face())) : base;
                c = g.putString(sr, c, s.text(), sf, right);
            }
            boolean thumb = ta >= 0 && i >= ta && i < tb;
            g.set(sr, right, thumb ? "┃" : "│", ta >= 0 ? f("popup-scrollbar") : border);
        }
        int br = y + 1 + n;
        g.set(br, x, "╰", border);
        g.putString(br, x + 1, "─".repeat(innerW), border, right);
        g.set(br, right, "╯", border);
    }

    /**
     * 框（总宽 bw、总高 bh）放哪：优先光标行 cr 的下方（above = 上方），放不下换另一边；x 挤回屏幕。⇒ {x, y} 或 null。
     */
    static int[] place(int cr, int xWant, int bw, int bh, int cols, int usableRows, boolean above) {
        boolean belowOk = cr + 1 + bh <= usableRows, aboveOk = cr - bh >= 0;
        Integer y = above ? (aboveOk ? Integer.valueOf(cr - bh) : belowOk ? Integer.valueOf(cr + 1) : null)
                : (belowOk ? Integer.valueOf(cr + 1) : aboveOk ? Integer.valueOf(cr - bh) : null);
        if (y == null || bw > cols) return null;
        return new int[]{Math.max(0, Math.min(cols - bw, xWant)), y};
    }

    private void drawPopup(Grid g, Popup p, int[] cur) {
        int n = Math.min(p.rows.size(), p.maxRows);
        if (n == 0) return;
        List<List<Seg>> shown = new ArrayList<>(p.rows.subList(p.top, Math.min(p.rows.size(), p.top + n)));
        int inner = Math.max(p.minWidth, Math.max(p.title == null ? 0 : Width.stringWidth(p.title) + 4, 2));
        for (List<Seg> r : shown) inner = Math.max(inner, Seg.width(r) + 2);
        inner = Math.min(inner, ed.cols - 2);
        List<List<Seg>> padded = new ArrayList<>();
        for (List<Seg> r : shown) { List<Seg> x = new ArrayList<>(); x.add(Seg.of(" ")); x.addAll(r); padded.add(x); }
        int bw = inner + 2, bh = n + 2;
        int x, y;
        switch (p.anchor) {
            case CENTER -> { x = (ed.cols - bw) / 2; y = Math.max(0, Math.min(ed.rows / 4, ed.rows - 1 - bh)); }
            case SCREEN -> { x = Math.max(0, Math.min(ed.cols - bw, p.x)); y = Math.max(0, Math.min(ed.rows - 1 - bh, p.y)); }
            default -> {
                int[] c = cur != null ? cur : new int[]{ed.rows - 2, 0};
                int[] pos = place(c[0], c[1] - 1, bw, bh, ed.cols, ed.rows - 1, p.preferAbove);
                if (pos == null) return;
                x = pos[0];
                y = pos[1];
            }
        }
        int top = p.top, sel = p.selected;
        drawBox(g, x, y, inner, padded, i -> sel >= 0 && top + i == sel ? "popup-selected" : null,
                p.rows.size() > n ? new int[]{p.top, p.rows.size(), 0} : null, p.title);
    }

    /** 补全框（corfu 的手感）：光标下方（放不下就上方）；每行 [种类 名字（已打的高亮）  detail]；选中项有文档时旁边开文档框。 */
    private void drawCompletionPopup(Grid g, int[] cur) {
        if (cur == null || ed.completion == null) return;
        List<CompletionItem> cands = ed.completionCandidates();
        if (cands.isEmpty()) return;
        if ("above-window".equals(ed.options.getString("completion-display")) && drawCompletionAbove(g, cands)) return;
        int total = cands.size(), n = Math.min(10, total);
        int sel = Math.min(ed.completion.sel, total - 1);
        int top = Math.max(0, sel - (n - 1));
        String typed = ed.currentBuffer().substring(ed.completion.start, Math.max(ed.completion.start, ed.point()));
        int lw = 4, dw = 0;
        for (int i = top; i < top + n; i++) {
            lw = Math.max(lw, Math.min(40, Width.stringWidth(cands.get(i).label())));
            String d = cands.get(i).detail();
            if (d != null) dw = Math.max(dw, Math.min(30, Width.stringWidth(d.lines().findFirst().orElse(""))));
        }
        int inner = 3 + lw + (dw > 0 ? 2 + dw : 0) + 1;
        List<List<Seg>> rs = new ArrayList<>();
        for (int i = top; i < top + n; i++) {
            CompletionItem it = cands.get(i);
            String l = Width.ellipsize(it.label(), lw);
            List<Seg> row = new ArrayList<>();
            row.add(Seg.of(" "));
            row.add(Seg.of(it.kind() == null ? " " : it.kind().substring(0, Math.min(1, it.kind().length())), "popup-kind"));
            row.add(Seg.of(" "));
            int m = !typed.isEmpty() && l.toLowerCase().startsWith(typed.toLowerCase()) ? Math.min(typed.length(), l.length()) : 0;
            if (m > 0) row.add(Seg.of(l.substring(0, m), "popup-match"));
            row.add(Seg.of(l.substring(m)));
            row.add(Seg.of(" ".repeat(lw - Width.stringWidth(l))));
            if (it.detail() != null) row.add(Seg.of("  " + Width.ellipsize(it.detail().lines().findFirst().orElse(""), 30), "popup-detail"));
            rs.add(row);
        }
        int[] pos = place(cur[0], cur[1] - Width.stringWidth(typed) - 4, inner + 2, n + 2, ed.cols, ed.rows - 1, false);
        if (pos == null) return;
        int t = top;
        drawBox(g, pos[0], pos[1], inner, rs, i -> t + i == sel ? "popup-selected" : null, new int[]{top, total, 0}, null);
        String doc = cands.get(sel).doc();
        if (doc != null && !doc.isBlank()) {
            int bx = pos[0] + inner + 2;
            int room = ed.cols - bx - 2;
            if (room >= 16) {
                List<String> lines = wrap(doc, Math.min(60, room));
                if (lines.size() > 14) lines = lines.subList(0, 14);
                int dinner = 2;
                for (String s : lines) dinner = Math.max(dinner, Width.stringWidth(s) + 2);
                List<List<Seg>> drs = new ArrayList<>();
                for (String s : lines) drs.add(List.of(Seg.of(" " + s, "popup-doc")));
                drawBox(g, bx, Math.max(0, Math.min(pos[1], ed.rows - 1 - (lines.size() + 2))), dinner, drs, null, null, null);
            }
        }
    }

    /**
     * 补全列表贴在当前窗口正文的正上方（Codex 的 / 菜单、@ 文件菜单）：不画框，整宽；每行 {@code   名字  说明}，
     * 选中行 {@code › } 打头、整行反白；已打的字加粗。列表和正文之间空一行。⇒ 画了没有（放不下 ⇒ false，退回浮框）。
     */
    private boolean drawCompletionAbove(Grid g, List<CompletionItem> cands) {
        Window w = ed.selected;
        Rect r = ed.windowRects().get(w);
        if (r == null) return false;
        int textTop = r.y() + ed.topRows(w) + (ed.hasHeaderLine(w) ? 1 : 0);
        int total = cands.size();
        int n = Math.min(Math.min(8, total), textTop - 1);
        if (n < 1) return false;
        int sel = Math.min(ed.completion.sel, total - 1);
        int top = Math.max(0, sel - (n - 1));
        String typed = ed.currentBuffer().substring(ed.completion.start, Math.max(ed.completion.start, ed.point()));
        int x0 = r.x(), x1 = r.x() + r.w() - (r.divider() ? 1 : 0);
        int lw = 4;
        for (int i = top; i < top + n; i++) lw = Math.max(lw, Math.min(36, Width.stringWidth(cands.get(i).label())));
        int y0 = textTop - 1 - n;
        Face base = f("completion-list");
        Face selF = f("completion-list-selected");
        Face dim = base.merge(ed.faceSpec("completion-list-detail"));
        g.fill(textTop - 1, x0, x1, f("default"));
        for (int i = 0; i < n; i++) {
            CompletionItem it = cands.get(top + i);
            boolean s = top + i == sel;
            int sr = y0 + i;
            Face rowF = s ? selF : base;
            g.fill(sr, x0, x1, rowF);
            int c = g.putString(sr, x0, s ? "› " : "  ", rowF.merge(Face.EMPTY.withBold(true)), x1);
            String l = Width.ellipsize(it.label(), lw);
            int m = !typed.isEmpty() && l.toLowerCase().startsWith(typed.toLowerCase()) ? Math.min(typed.length(), l.length()) : 0;
            c = g.putString(sr, c, l.substring(0, m), rowF.merge(Face.EMPTY.withBold(true)), x1);
            c = g.putString(sr, c, l.substring(m), rowF, x1);
            c = g.putString(sr, c, " ".repeat(lw - Width.stringWidth(l) + 2), rowF, x1);
            if (it.detail() != null) g.putString(sr, c, it.detail().lines().findFirst().orElse(""), s ? rowF : dim, x1);
        }
        if (total > n) {
            String more = (sel + 1) + "/" + total + " ";
            g.putString(y0 + n - 1, Math.max(x0, x1 - Width.stringWidth(more)), more, dim, x1);
        }
        return true;
    }

    /** 文字按显示宽度折行（优先断在空白处）。 */
    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        width = Math.max(4, width);
        for (String line : s.split("\n", -1)) {
            String rest = line;
            while (Width.stringWidth(rest) > width) {
                String head = Width.truncate(rest, width);
                int sp = head.lastIndexOf(' ');
                if (sp > width / 3) head = head.substring(0, sp);
                out.add(head.stripTrailing());
                rest = rest.substring(head.length()).stripLeading();
            }
            out.add(rest);
        }
        return out;
    }

    private void drawWhichKey(Grid g) {
        if (!ed.whichKeyVisible()) return;
        Object m = Keymap.lookup(ed.activeKeymaps(), ed.pending);
        if (!(m instanceof Keymap km)) return;
        List<String[]> entries = km.bindingsUnder();
        if (entries.isEmpty()) return;
        int cols = ed.cols, rows = ed.rows;
        int colw = 10;
        for (String[] e : entries) colw = Math.max(colw, Width.stringWidth(e[0]) + 3 + Width.stringWidth(e[1]));
        colw = Math.min(40, colw + 2);
        int ncol = Math.max(1, cols / colw);
        int nrow = Math.min(Math.max(1, rows - 3), (entries.size() + ncol - 1) / ncol);
        int y0 = rows - 2 - nrow;
        // 浮框底色铺满这几行，和正文分开；标题行写按到一半的前缀与条数
        Face base = f("popup");
        g.fill(y0, 0, cols, f("popup-border"));
        String title = " " + Keymap.keyDesc(ed.pending) + "- ";
        int tc = g.putString(y0, 0, "──", f("popup-border"));
        tc = g.putString(y0, tc, title, f("popup-prompt"));
        String count = " " + entries.size() + " ";
        g.putString(y0, tc, "─".repeat(Math.max(0, cols - tc - count.length() - 2)), f("popup-border"));
        g.putString(y0, Math.max(tc, cols - count.length() - 2), count + "──", f("popup-border"));
        for (int r = 0; r < nrow; r++) g.fill(y0 + 1 + r, 0, cols, base);
        for (int i = 0; i < Math.min(entries.size(), nrow * ncol); i++) {
            int cl = i / nrow, rw = i % nrow, sx = cl * colw, sr = y0 + 1 + rw;
            String[] e = entries.get(i);
            int c1 = g.putString(sr, sx + 2, e[0], base.merge(ed.faceSpec("which-key-key")), sx + colw);
            int c2 = g.putString(sr, c1, " → ", base.merge(ed.faceSpec("shadow")), sx + colw);
            g.putString(sr, c2, e[1], base.merge(ed.faceSpec(e[1].startsWith("+") ? "which-key-prefix" : "which-key-cmd")), sx + colw - 1);
        }
    }
}
