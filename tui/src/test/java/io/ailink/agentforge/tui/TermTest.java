package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.Face;
import io.ailink.agentforge.tui.term.Grid;
import io.ailink.agentforge.tui.term.InputDecoder;
import io.ailink.agentforge.tui.term.InputEvent;
import io.ailink.agentforge.tui.term.ScreenDiff;
import io.ailink.agentforge.tui.term.VirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TermTest {
    static List<Object> keys(String s) {
        return InputDecoder.decodeAll(s).stream().map(e -> e instanceof InputEvent.Key k ? (Object) k.key() : e).toList();
    }

    @Test
    void decodeKeys() {
        assertEquals(List.of("a", "C-a", "TAB", "RET", "DEL", "C-SPC", "C-_", "SPC"), keys("a\u0001\t\r\u007f\u0000\u001f "));
        assertEquals(List.of("M-x", "C-M-f", "M-DEL"), keys("\u001bx\u001b\u0006\u001b\u007f"));
        assertEquals(List.of("<up>", "<down>", "<right>", "<left>", "<home>", "<end>"), keys("\u001b[A\u001b[B\u001b[C\u001b[D\u001b[H\u001b[F"));
        assertEquals(List.of("C-<right>", "S-<up>", "M-<left>"), keys("\u001b[1;5C\u001b[1;2A\u001b[1;3D"));
        assertEquals(List.of("<delete>", "<prior>", "<next>", "<f5>", "C-<delete>"), keys("\u001b[3~\u001b[5~\u001b[6~\u001b[15~\u001b[3;5~"));
        assertEquals(List.of("<f1>", "<f2>"), keys("\u001bOP\u001bOQ"));
        assertEquals(List.of("ESC"), keys("\u001b"));
        assertEquals(List.of("中", "😀"), keys("中😀"));
        assertEquals(List.of("C-?"), keys("\u001b[63;5u"), "kitty");
        assertEquals(List.of("C-M-a"), keys("\u001b[27;7;97~"), "modifyOtherKeys");
        assertEquals(List.of("A"), keys("\u001b[97;2u"));
        assertEquals(List.of("TAB"), keys("\u001b[105;5u"), "C-i 合回 TAB");
        assertEquals(List.of("<backtab>"), keys("\u001b[Z"));
    }

    @Test
    void decodePasteMouseFocus() {
        assertEquals(List.of(new InputEvent.Paste("hello\nworld"), "a"), keys("\u001b[200~hello\nworld\u001b[201~a"));
        assertEquals(List.of(new InputEvent.Mouse(0, 9, 4, false), new InputEvent.Mouse(0, 9, 4, true)), keys("\u001b[<0;10;5M\u001b[<0;10;5m"));
        assertEquals(List.of(new InputEvent.Mouse(65, 0, 0, false)), keys("\u001b[<65;1;1M"));
        assertEquals(List.of(new InputEvent.Focus(true), new InputEvent.Focus(false)), keys("\u001b[I\u001b[O"));
    }

    @Test
    void gridAndDiff() {
        Grid g = new Grid(10, 2, Face.EMPTY);
        g.putString(0, 0, "ab中文", Face.EMPTY);
        assertEquals("ab中文    ", g.rowString(0));
        g.putString(1, 0, "abcdefghi中", Face.EMPTY);
        assertEquals(" ", g.text(1, 9), "宽字放不下补空格");
        assertTrue(ScreenDiff.diff(null, g, new int[]{0, 0}, 24).contains("\u001b[2J"));
        Grid g2 = new Grid(10, 2, Face.EMPTY);
        g2.putString(0, 0, "ab中文", Face.EMPTY);
        g2.putString(1, 0, "abcdefghi中", Face.EMPTY);
        assertFalse(ScreenDiff.diff(g, g2, new int[]{0, 0}, 24).contains("ab"), "完全一样：只有光标");
        g2.putString(0, 0, "aX", Face.EMPTY);
        String out = ScreenDiff.diff(g, g2, null, 24);
        assertTrue(out.matches("(?s).*\u001b\\[1;2H(\u001b\\[[0-9;]*m)?X.*"), out);
        assertFalse(out.contains("中"));
    }

    @Test
    void sgr() {
        Face f = new Face(0xff0000, 0x000080, true, null, true, null, null);
        assertEquals("\u001b[0;1;4;38;2;255;0;0;48;2;0;0;128m", ScreenDiff.sgr(f, 24));
        assertEquals("\u001b[0;1;4;38;5;196;48;5;18m", ScreenDiff.sgr(f, 8));
    }

    /** diff 输出在虚拟终端上重放，屏幕 = 期望矩阵（随机改动多帧）。 */
    @Test
    void diffReplaysOnVirtualTerminal() {
        Random r = new Random(7);
        VirtualTerminal vt = new VirtualTerminal(20, 6);
        Grid prev = null;
        String[] words = {"hello", "中文", "😀", "x", "  ", "宽字符"};
        Face red = Face.fg(0xff0000);
        for (int frame = 0; frame < 50; frame++) {
            Grid g = new Grid(20, 6, Face.EMPTY);
            for (int i = 0; i < 8; i++) g.putString(r.nextInt(6), r.nextInt(20), words[r.nextInt(words.length)], r.nextBoolean() ? red : Face.EMPTY);
            vt.write(ScreenDiff.diff(prev, g, new int[]{0, 0}, 24));
            assertEquals(g.dump(), vt.screen(), "frame " + frame);
            prev = g;
        }
    }
}
