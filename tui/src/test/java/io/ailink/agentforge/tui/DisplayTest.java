package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.commands.DocumentCommands;
import io.ailink.agentforge.tui.term.Grid;
import io.ailink.agentforge.tui.term.InputEvent;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

class DisplayTest {
    static String row(Grid g, int r) { return g.rowString(r).stripTrailing(); }

    @Test
    void modeLineAndEcho() {
        Editor ed = TestSupport.fresh("hello\nworld", 7);
        Grid g = ed.render();
        assertEquals("hello", row(g, 0));
        assertTrue(row(g, 22).contains("*scratch*"), row(g, 22));
        assertTrue(row(g, 22).contains("(2,1)"), row(g, 22));
        assertTrue(row(g, 22).contains("All"));
        assertArrayEquals(new int[]{1, 1}, ed.cursor());
        ed.message("hi %s", "there");
        assertEquals("hi there", row(ed.render(), 23));
    }

    @Test
    void charWrapAndWordWrapAndWideChars() {
        Editor ed = new Editor(20, 10);
        Buffer b = ed.currentBuffer();
        b.setContents("aaaa bbbb cccc dddd eeee");
        Grid g = ed.render();
        assertEquals("aaaa bbbb cccc dddd\\", row(g, 0));
        assertEquals(" eeee", row(g, 1), "按字折：空格也折到下一行");
        b.put("word-wrap", true);
        g = ed.render();
        assertEquals("aaaa bbbb cccc dddd", row(g, 0));
        b.setContents("aaaa bbbb cccc ddddd eeee");
        g = ed.render();
        assertEquals("aaaa bbbb cccc", row(g, 0), "按词折：断在空白后");
        assertEquals("ddddd eeee", row(g, 1));
        b.put("word-wrap", false);
        b.setContents("中文中文中文中文中文中");     // 11 个宽字：一行放 9 个（19 列，最后一列放 \）
        g = ed.render();
        assertEquals("中文中文中文中文中 \\", row(g, 0).replace("\u0000", ""));
        assertEquals("文中", row(g, 1).replace(" ", "").replace("", ""));
    }

    @Test
    void overlayFacesMergeAndPriority() {
        Editor ed = new Editor(30, 6);
        Buffer b = ed.currentBuffer();
        b.setContents("hello world");
        b.addOverlay(0, 5).face("error");
        b.addOverlay(0, 11).faceSpec(io.ailink.agentforge.tui.term.Face.EMPTY.withBold(true)).priority(-1);
        Grid g = ed.render();
        assertEquals(ed.resolveFace("error").fg(), g.face(0, 0).fg());
        assertTrue(g.face(0, 0).isBold(), "低优先级的粗体和上面的颜色合成");
        assertTrue(g.face(0, 7).isBold());
        assertEquals(ed.resolveFace("default").fg(), g.face(0, 7).fg());
    }

    @Test
    void foldsBlocksAndButtons() {
        Editor ed = new Editor(40, 12);
        Buffer t = ed.getOrCreateBuffer("*t*");
        t.setMode(DocumentCommands.DOCUMENT_MODE);
        ed.switchToBuffer(t);
        int a = t.length();
        t.append("header one\n", "block-title");
        int body = t.length();
        t.append("line1\nline2\nline3\n");
        Overlay fold = Fold.create(t, body, t.length(), "  … 3 lines", true);
        Fold.markBlock(t, a, t.length(), 1);
        int b2 = t.length();
        t.append("second block\n");
        Fold.markBlock(t, b2, t.length(), 2);
        int[] clicks = {0};
        Overlay btn = t.append("[ok]", "button").action(e -> clicks[0]++);
        t.setReadOnly(true);
        Grid g = ed.render();
        assertEquals("header one", row(g, 0));
        assertEquals("  … 3 lines", row(g, 1), "折起来：原位画摘要");
        assertEquals("second block", row(g, 2));
        // TAB 在标题行上：展开
        ed.goTo(0);
        ed.feed("TAB");
        assertFalse(fold.invisible());
        g = ed.render();
        assertEquals("line1", row(g, 1));
        assertEquals("line3", row(g, 3));
        // n / p 块级导航
        ed.goTo(0);
        ed.feed("n");
        assertEquals(b2, ed.point());
        ed.feed("p");
        assertEquals(a, ed.point());
        // point 不停在隐藏文字里
        ed.feed("TAB");
        ed.goTo(0);
        ed.feed("C-n C-f");
        assertTrue(ed.point() <= body || ed.point() >= fold.end(), "point=" + ed.point());
        // RET 点按钮；鼠标点按钮
        ed.goTo(btn.start() + 1);
        ed.feed("RET");
        assertEquals(1, clicks[0]);
        g = ed.render();
        int btnRow = -1;
        for (int r = 0; r < 10; r++) if (row(g, r).startsWith("[ok]")) btnRow = r;
        assertTrue(btnRow >= 0);
        ed.handleEvent(new InputEvent.Mouse(0, 1, btnRow, false));
        ed.handleEvent(new InputEvent.Mouse(0, 1, btnRow, true));
        assertEquals(2, clicks[0]);
        assertTrue(g.face(btnRow, 0).isUnderline(), "button face");
    }

    @Test
    void followTailAndNoAutoScrollWhenScrolledUp() throws Exception {
        Editor ed = new Editor(40, 8);       // 正文 6 行
        Buffer t = ed.getOrCreateBuffer("*t*");
        t.setReadOnly(true);
        Window w = ed.selectedWindow();
        ed.showBuffer(w, t);
        w.setFollowTail(true);
        CountDownLatch done = new CountDownLatch(1);
        Thread th = new Thread(() -> {
            for (int i = 1; i <= 30; i++) { int n = i; ed.post(() -> t.append("line " + n + "\n")); }
            done.countDown();
        });
        th.start();
        done.await();
        ed.drainPosted();
        Grid g = ed.render();
        assertEquals(t.length(), w.point(), "跟着末尾");
        assertEquals("line 30", row(g, 4), g.dump());
        assertEquals("", row(g, 5));
        // 用户往上翻：不再自动滚
        ed.feed("M-v");
        ed.render();
        int start = w.start();
        ed.post(() -> t.append("line 31\n"));
        ed.drainPosted();
        ed.render();
        assertEquals(start, w.start(), "往上翻过就不自动滚");
        assertTrue(w.point() < t.length());
        // 回到末尾接着跟
        ed.feed("M->");
        ed.post(() -> t.append("line 32\n"));
        ed.drainPosted();
        g = ed.render();
        assertEquals(t.length(), w.point());
        assertTrue(g.dump().contains("line 32"));
    }

    @Test
    void headerLineAndHiddenModeLine() {
        Editor ed = new Editor(30, 6);
        Buffer b = ed.currentBuffer();
        b.setContents("body");
        b.setHeaderLine(w -> java.util.List.of(Seg.of(" HEADER")));
        b.setModeLine(ModeLine.NONE);
        Grid g = ed.render();
        assertEquals(" HEADER", row(g, 0));
        assertEquals("body", row(g, 1));
        assertEquals(4, ed.textSize(ed.selectedWindow())[1], "6 行 - echo - header = 4");
    }

    @Test
    void echoAreaHostLeavesPlainMessagesToTheHostButDrawsKeyPrompts() {
        Editor ed = new Editor(30, 6);
        ed.options().set("echo-area", "host");
        Buffer b = ed.currentBuffer();
        b.setModeLine(new ModeLine().add("hint", 10, false, c -> java.util.List.of(Seg.of("hint line"))));
        assertEquals("hint line", row(ed.render(), 5));
        ed.message("saved");
        assertEquals("hint line", row(ed.render(), 5), "普通消息不盖提示行（宿主自己摆）");
        assertFalse(ed.awaitingKey());
        ed.yOrN("Quit?", y -> { });
        assertTrue(ed.awaitingKey());
        assertTrue(row(ed.render(), 5).startsWith(" Quit?"), "读键提问照常整行盖上：" + row(ed.render(), 5));
        ed.feed("n");
        assertFalse(ed.awaitingKey());
        assertEquals("hint line", row(ed.render(), 5));
    }

    @Test
    void topRowsPlaceholderFirstLinePrefixAndEchoOverlay() {
        Editor ed = new Editor(30, 8);
        ed.options().set("echo-area", "overlay");
        Buffer b = ed.currentBuffer();
        b.setModeLine(ModeLine.NONE);
        b.setTopRows(w -> java.util.List.of(java.util.List.of(), java.util.List.of(Seg.of("• Working"))));
        b.put("first-line-prefix", "› ");
        b.put("line-prefix", "  ");
        b.put("placeholder", "Ask anything");
        Grid g = ed.render();
        assertEquals("", row(g, 0));
        assertEquals("• Working", row(g, 1));
        assertEquals("› Ask anything", row(g, 2), "空 buffer 画占位，排在第一行的行前缀后面");
        assertArrayEquals(new int[]{2, 2}, ed.cursor(), "光标在占位字前面");
        assertEquals(6, ed.textSize(ed.selectedWindow())[1], "echo 叠在最后一行上：8 行 - 顶上 2 行 = 6");
        b.setContents("one\ntwo");
        g = ed.render();
        assertEquals("› one", row(g, 2));
        assertEquals("  two", row(g, 3), "后面的行只垫 line-prefix");
        ed.message("hi");
        assertEquals(" hi", row(ed.render(), 7), "有话说时才盖住最后一行");
    }

    @Test
    void completionListAboveWindow() {
        Editor ed = new Editor(40, 12);
        ed.options().set("completion-display", "above-window");
        Window top = ed.selectedWindow();
        Window bottom = ed.splitWindow(top, Split.Dir.BELOW, 0.7, ed.getOrCreateBuffer("*in*"));
        ed.selectWindow(bottom);
        ed.addCompletionSource(CompletionSource.triggered('/', true, null, p -> CompletionTest.CMDS));
        ed.options().set("completion-auto", false);
        ed.typeText("/").feed("M-TAB");
        assertTrue(ed.completionActive());
        Grid g = ed.render();
        int textTop = ed.windowRects().get(bottom).y();
        assertTrue(row(g, textTop - 4).startsWith("› /help"), g.dump());
        assertTrue(row(g, textTop - 2).startsWith("  /theme"), g.dump());
        assertEquals("", row(g, textTop - 1), "列表和输入之间空一行");
    }

    @Test
    void isearchInReadOnlyDocument() {
        Editor ed = TestSupport.fresh("alpha beta gamma beta");
        ed.currentBuffer().setReadOnly(true);
        ed.feed("C-s").typeText("beta");
        assertEquals(10, ed.point());
        assertTrue(row(ed.render(), 23).startsWith("I-search: beta"));
        ed.feed("C-s");
        assertEquals(21, ed.point());
        ed.feed("C-s");
        assertTrue(ed.isearch().failing());
        ed.feed("C-s");
        assertEquals(10, ed.point(), "撞墙后再按一次绕回");
        ed.feed("RET");
        assertNull(ed.isearch());
        assertEquals(0, ed.currentBuffer().mark());
        ed.feed("C-s").typeText("zz").feed("C-g C-g");
        assertEquals(10, ed.point(), "C-g 回原处");
    }

    @Test
    void lineNumbersAndTruncate() {
        Editor ed = new Editor(20, 6);
        Buffer b = ed.currentBuffer();
        b.setContents("a\nbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        b.put("display-line-numbers", true);
        b.put("truncate-lines", true);
        Grid g = ed.render();
        assertEquals("1 a", row(g, 0));
        assertTrue(row(g, 1).startsWith("2 bbbb"));
        assertTrue(row(g, 1).endsWith("$"));
    }
}
