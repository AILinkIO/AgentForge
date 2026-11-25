package io.ailink.agentforge.tui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    @Test
    void splitDeleteOthersCycle() {
        Editor ed = TestSupport.fresh("hello");
        Window w1 = ed.selectedWindow();
        ed.runCommand("split-window-below", null);
        assertEquals(2, ed.windows().size());
        ed.runCommand("split-window-right", null);
        assertEquals(3, ed.windows().size());
        Map<Window, Rect> r = ed.windowRects();
        assertEquals(new Rect(0, 0, 40, 12, true), r.get(w1));
        ed.feed("C-x o");
        assertNotSame(w1, ed.selectedWindow());
        ed.feed("C-x o C-x o");
        assertSame(w1, ed.selectedWindow());
        ed.runCommand("delete-window", null);
        assertEquals(2, ed.windows().size());
        ed.runCommand("delete-other-windows", null);
        assertEquals(1, ed.windows().size());
        ed.runCommand("delete-window", null);
        assertTrue(ed.currentMessage().startsWith("Attempt to delete"));
        assertNull(ed.globalMap().lookup(Keymap.kbd("C-x 2")), "编辑器的分窗键已经去掉（命令走 M-x）");
    }

    @Test
    void windowPointsAreIndependentAndShift() {
        Editor ed = TestSupport.fresh("abcdef", 3);
        ed.runCommand("split-window-below", null);
        Window a = ed.windows().get(0), b = ed.windows().get(1);
        ed.selectWindow(b);
        ed.feed("C-e");
        assertEquals(3, a.point());
        assertEquals(6, b.point());
        ed.selectWindow(a);
        ed.feed("C-a");
        ed.typeText("XY");
        assertEquals(8, b.point(), "别的窗口的 point 跟着平移");
    }

    @Test
    void atomicGroupActsAsOneWindow() {
        Editor ed = TestSupport.fresh("");
        Buffer t = ed.getOrCreateBuffer("*t*"), in = ed.getOrCreateBuffer("*in*");
        Window tw = ed.selectedWindow();
        ed.showBuffer(tw, t);
        Window iw = ed.splitWindow(tw, Split.Dir.BELOW, 0.5, in);
        Split g = ed.makeAtomic(tw, iw, "agent").setFixedSize(1, 4);
        assertEquals("agent", iw.group());
        Map<Window, Rect> r = ed.windowRects();
        assertEquals(4, r.get(iw).h(), "输入框固定 4 行");
        assertEquals(19, r.get(tw).h());
        // 在组里左右分：整个组分出去
        ed.selectWindow(iw);
        ed.runCommand("split-window-right", null);
        assertEquals(3, ed.windows().size());
        Window other = ed.windows().get(2);
        assertInstanceOf(Split.class, ed.layoutRoot());
        assertSame(g, ((Split) ed.layoutRoot()).first());
        assertNull(other.group());
        // 在组里只留一个：留整个组
        ed.runCommand("delete-other-windows", null);
        assertEquals(2, ed.windows().size());
        assertSame(g, ed.layoutRoot());
        // 再分一个，在组里关窗：整个组删掉
        ed.runCommand("split-window-below", null);
        ed.selectWindow(tw);
        ed.runCommand("delete-window", null);
        assertEquals(1, ed.windows().size());
        assertNull(ed.windows().getFirst().group());
    }

    @Test
    void noOtherWindowAndBalanceAndResize() {
        Editor ed = TestSupport.fresh("");
        Window a = ed.selectedWindow();
        Window b = ed.splitWindow(a, Split.Dir.RIGHT, 0.3, null);
        Window c = ed.splitWindow(b, Split.Dir.BELOW, 0.5, null);
        b.setParam(Window.NO_OTHER_WINDOW, true);
        ed.feed("C-x o");
        assertSame(c, ed.selectedWindow(), "跳过 no-other-window");
        ed.runCommand("balance-windows", null);
        assertEquals(40, ed.windowRects().get(a).w());
        ed.selectWindow(a);
        ed.feed("C-u 5 M-x").typeText("enlarge-window-horizontally").feed("RET");
        assertEquals(45, ed.windowRects().get(a).w());
    }

    @Test
    void displayBufferOtherWindowAndQuit() {
        Editor ed = TestSupport.fresh("");
        ed.feed("<f1> f");
        ed.typeText("forward-char").feed("RET");
        assertEquals(2, ed.windows().size());
        Window help = ed.windows().get(1);
        assertEquals("*Help*", help.buffer().name());
        assertTrue(help.buffer().contents().contains("forward-char"));
        ed.selectWindow(help);
        ed.feed("q");
        assertEquals(1, ed.windows().size());
    }
}
