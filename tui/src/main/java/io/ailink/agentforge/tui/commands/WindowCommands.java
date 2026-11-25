package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.LayoutNode;
import io.ailink.agentforge.tui.Rect;
import io.ailink.agentforge.tui.Split;
import io.ailink.agentforge.tui.UserError;
import io.ailink.agentforge.tui.Window;

import java.util.Map;

/** 窗口命令（分窗、关窗、换窗口 …，coxswain {@code coxn.commands.windows}；键只剩 C-x o，其余走 M-x）。原子组当一个窗口处理。 */
public final class WindowCommands {
    private WindowCommands() {}

    public static void install(Editor ed) {
        ed.defineCommand("split-window-below", "上下分（原子组整个分）。", e -> split(e, Split.Dir.BELOW));
        ed.defineCommand("split-window-right", "左右分。", e -> split(e, Split.Dir.RIGHT));
        ed.defineCommand("delete-window", "关掉这个窗口（原子组整个关）。", e -> e.deleteWindow(e.selectedWindow()));
        ed.defineCommand("delete-other-windows", "只留这个窗口（或它所在的原子组）。", e -> e.deleteOtherWindows(e.selectedWindow()));
        ed.defineCommand("other-window", "C-x o：下一个窗口（跳过 no-other-window 的）。", e ->
                e.selectWindow(e.nextWindow(e.selectedWindow(), e.countArg())));
        ed.defineCommand("balance-windows", "各窗口一样大（不动原子组内部）。", e -> balance(e.layoutRoot()));
        ed.defineCommand("enlarge-window", "高一行。", e -> resize(e, Split.Dir.BELOW, e.countArg()));
        ed.defineCommand("shrink-window", "矮一行。", e -> resize(e, Split.Dir.BELOW, -e.countArg()));
        ed.defineCommand("enlarge-window-horizontally", "宽一列。", e -> resize(e, Split.Dir.RIGHT, e.countArg()));
        ed.defineCommand("shrink-window-horizontally", "窄一列。", e -> resize(e, Split.Dir.RIGHT, -e.countArg()));
        ed.defineCommand("windmove-left", "左边的窗口。", e -> windmove(e, -1, 0));
        ed.defineCommand("windmove-right", "右边的窗口。", e -> windmove(e, 1, 0));
        ed.defineCommand("windmove-up", "上面的窗口。", e -> windmove(e, 0, -1));
        ed.defineCommand("windmove-down", "下面的窗口。", e -> windmove(e, 0, 1));
        ed.defineCommand("quit-window", "q：关掉这个弹出来的窗口（或换回原来的 buffer）。", e -> {
            Window w = e.selectedWindow();
            Object qr = w.param("quit-restore");
            w.setParam("quit-restore", null);
            if (qr instanceof Buffer b && e.buffers().contains(b)) e.showBuffer(w, b);
            else if (e.windows().size() > 1) e.deleteWindow(w);
        });
        ed.defineCommand("save-buffers-kill-terminal", "C-x C-c：退出。", Editor::quit);
    }

    static void split(Editor e, Split.Dir dir) {
        Window w = e.selectedWindow();
        Rect r = e.windowRects().get(w);
        if (dir == Split.Dir.BELOW ? r.h() < 4 : r.w() < 10) throw new UserError("Window too small for splitting");
        e.splitWindow(w, dir, 0.5, null);
    }

    static void balance(LayoutNode n) {
        if (n instanceof Split s) {
            if (s.atomic() != null) return;
            if (s.fixedSize() < 0) s.setRatio(0.5);
            balance(s.first());
            balance(s.second());
        }
    }

    /** 找包含窗口的、方向为 dir 的最近父节点，把窗口那边加 n 格。 */
    static void resize(Editor e, Split.Dir dir, int n) {
        Window w = e.selectedWindow();
        Map<Window, Rect> rects = e.windowRects();
        LayoutNode child = w;
        for (Split s = w.parent(); s != null; child = s, s = s.parent()) {
            if (s.dir() != dir) continue;
            int total = 0;
            int lo = Integer.MAX_VALUE, hi = 0;
            for (Window x : s.windows()) {
                Rect r = rects.get(x);
                lo = Math.min(lo, dir == Split.Dir.BELOW ? r.y() : r.x());
                hi = Math.max(hi, dir == Split.Dir.BELOW ? r.y() + r.h() : r.x() + r.w());
            }
            total = hi - lo;
            boolean inFirst = s.first() == child;
            if (s.fixedSize() >= 0) {
                boolean fixedIsMine = (s.fixedChild() == 0) == inFirst;
                s.setFixedSize(s.fixedChild(), Math.max(1, Math.min(total - 1, s.fixedSize() + (fixedIsMine ? n : -n))));
            } else {
                s.setRatio(s.ratio() + (inFirst ? n : -n) / (double) Math.max(1, total));
            }
            return;
        }
        throw new UserError("No window to resize in that direction");
    }

    static void windmove(Editor e, int dx, int dy) {
        Map<Window, Rect> rects = e.windowRects();
        Rect cur = rects.get(e.selectedWindow());
        int cx = cur.x() + cur.w() / 2, cy = cur.y() + cur.h() / 2;
        Window best = null;
        int bestD = Integer.MAX_VALUE;
        for (Map.Entry<Window, Rect> en : rects.entrySet()) {
            if (en.getKey() == e.selectedWindow()) continue;
            Rect r = en.getValue();
            boolean ok = dx > 0 ? r.x() >= cur.x() + cur.w() : dx < 0 ? r.x() + r.w() <= cur.x()
                    : dy > 0 ? r.y() >= cur.y() + cur.h() : r.y() + r.h() <= cur.y();
            boolean overlap = dx == 0 ? r.x() < cur.x() + cur.w() && r.x() + r.w() > cur.x()
                    : r.y() < cur.y() + cur.h() && r.y() + r.h() > cur.y();
            if (!ok || !overlap) continue;
            int d = Math.abs(cx - (r.x() + r.w() / 2)) + Math.abs(cy - (r.y() + r.h() / 2));
            if (d < bestD) { bestD = d; best = en.getKey(); }
        }
        if (best == null) throw new UserError("No window in that direction");
        e.selectWindow(best);
    }
}
