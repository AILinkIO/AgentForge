package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.InputEvent;

import java.util.Map;

/**
 * xterm 鼠标（SGR 1006，coxswain {@code coxn.mouse}）：点击定位 / 选窗口、点按钮（覆盖层的 action）、双击选词、
 * 拖动选区、滚轮（滚动不动 point 以外的东西 —— transcript 往上滚就不再自动跟随末尾）、拖 mode line / 竖线调窗口大小。
 */
final class Mouse {
    static final long DOUBLE_CLICK_MS = 400;
    static final int WHEEL_LINES = 3;

    private final Editor ed;
    private Window dragWindow, resizeWindow;
    private boolean resizeVertical;
    private int anchor;
    private long lastClickTime;
    private int lastClickPos = -1;

    Mouse(Editor ed) { this.ed = ed; }

    void handle(InputEvent.Mouse m) {
        int b = m.button(), base = b & 3;
        boolean motion = (b & 32) != 0, wheel = (b & 64) != 0;
        if (wheel) wheel(m.x(), m.y(), base == 0 ? -1 : 1);
        else if (base == 0 && motion) drag(m.x(), m.y());
        else if (base == 0 && m.release()) { dragWindow = null; resizeWindow = null; }
        else if (base == 0) press(m.x(), m.y());
    }

    private Map.Entry<Window, Rect> at(int x, int y) {
        for (Map.Entry<Window, Rect> e : ed.windowRects().entrySet()) if (e.getValue().contains(x, y)) return e;
        return null;
    }

    /** 屏幕坐标 ⇒ 窗口正文区里的位置（拖出窗口时夹回正文区）。 */
    private int textPos(Window w, Rect r, int x, int y) {
        int top = r.y() + ed.topRows(w) + (ed.hasHeaderLine(w) ? 1 : 0);
        int h = ed.textSize(w)[1];
        int sy = Math.max(0, Math.min(y - top, h - 1));
        return ed.view.posAtScreen(w, sy, x - r.x() - ed.gutterWidth(w));
    }

    private void press(int x, int y) {
        Map.Entry<Window, Rect> e = at(x, y);
        if (e == null) return;
        Window w = e.getKey();
        Rect r = e.getValue();
        if (r.divider() && x == r.x() + r.w() - 1) { resizeWindow = w; resizeVertical = false; return; }
        if (ed.hasModeLine(w) && y == r.y() + r.h() - 1) { resizeWindow = w; resizeVertical = true; return; }
        if (y < r.y() + ed.topRows(w) + (ed.hasHeaderLine(w) ? 1 : 0)) return;
        if (ed.minibufferActive()) return;
        if (w != ed.selected) ed.selectWindow(w);
        int pos = textPos(w, r, x, y);
        long now = ed.timers.now();
        boolean dbl = now - lastClickTime < DOUBLE_CLICK_MS && pos == lastClickPos;
        lastClickTime = now;
        lastClickPos = pos;
        w.setPoint(pos);
        w.buffer.deactivateMark();
        ed.lastCommand = "mouse-set-point";
        Overlay button = w.buffer.overlayAt(pos, o -> o.action != null);
        if (button != null) {
            dragWindow = null;
            ed.runCommand("push-button", (Command) x2 -> button.action.accept(x2), null);
            return;
        }
        if (dbl) {
            Buffer b = w.buffer;
            int a = pos, z = pos;
            while (a > 0 && CompletionSource.isWord(b.charAt(a - 1))) a--;
            while (z < b.length() && CompletionSource.isWord(b.charAt(z))) z++;
            if (a < z) { b.setMark(a, true); w.setPoint(z); }
            dragWindow = null;
            return;
        }
        dragWindow = w;
        anchor = pos;
    }

    private void drag(int x, int y) {
        if (resizeWindow != null) { resize(x, y); return; }
        if (dragWindow == null) return;
        Window w = dragWindow;
        Rect r = ed.windowRects().get(w);
        if (r == null) return;
        int top = r.y() + ed.topRows(w) + (ed.hasHeaderLine(w) ? 1 : 0);
        int h = ed.textSize(w)[1];
        try {
            if (y < top) ed.view.scroll(w, -1);
            else if (y >= top + h) ed.view.scroll(w, 1);
        } catch (UserError ignored) { }
        int pos = textPos(w, r, x, y);
        w.setPoint(pos);
        if (pos != anchor) w.buffer.setMark(anchor, true);
    }

    /** 拖边界：最近的、窗口在第一个孩子里的同向分割，第一个孩子的大小改成到鼠标处；两边至少留 2 行 / 4 列。 */
    private void resize(int x, int y) {
        Split.Dir dir = resizeVertical ? Split.Dir.BELOW : Split.Dir.RIGHT;
        Map<Window, Rect> rects = ed.windowRects();
        LayoutNode child = resizeWindow;
        for (Split s = child.parent; s != null; child = s, s = s.parent) {
            if (s.dir != dir || s.a != child) continue;
            int origin = Integer.MAX_VALUE, extent = 0;
            for (Window w : s.windows()) {
                Rect r = rects.get(w);
                origin = Math.min(origin, dir == Split.Dir.BELOW ? r.y() : r.x());
                extent = Math.max(extent, dir == Split.Dir.BELOW ? r.y() + r.h() : r.x() + r.w());
            }
            int total = extent - origin;
            int min = dir == Split.Dir.BELOW ? 2 : 4;
            int want = Math.max(min, Math.min(total - min, (dir == Split.Dir.BELOW ? y : x) - origin + 1));
            if (s.fixedSize >= 0) s.fixedSize = s.fixedChild == 0 ? want : total - want;
            else s.ratio = (double) want / total;
            ed.dirty = true;
            return;
        }
    }

    private void wheel(int x, int y, int dir) {
        Map.Entry<Window, Rect> e = at(x, y);
        if (e == null) return;
        try { ed.view.scroll(e.getKey(), dir * WHEEL_LINES); } catch (UserError ignored) { }
    }
}
