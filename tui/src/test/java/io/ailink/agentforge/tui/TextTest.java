package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.text.GapText;
import io.ailink.agentforge.tui.text.Width;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TextTest {
    @Test
    void widths() {
        assertEquals(1, Width.cpWidth('a'));
        assertEquals(2, Width.cpWidth('中'));
        assertEquals(2, Width.cpWidth("😀".codePointAt(0)));
        assertEquals(0, Width.cpWidth(0x301));
        assertEquals(2, Width.cpWidth(1), "控制字符画成 ^A");
        assertEquals(6, Width.stringWidth("ab中文"));
        assertEquals("ab中", Width.truncate("ab中文", 5));
        assertEquals("ab中 ", Width.padRight("ab中文", 5));
        assertEquals("ab…", Width.ellipsize("abcdef", 3));
        assertEquals(2, Width.nextPos("😀x", 0));
        assertEquals(0, Width.prevPos("😀x", 2));
        assertEquals(8, Width.displayCol("\tx", 1, 8));
        assertEquals(1, Width.posAtCol("中x", 2, 8));
    }

    @Test
    void gapTextMatchesStringBuilder() {
        Random r = new Random(42);
        GapText g = new GapText("hello\nworld");
        StringBuilder sb = new StringBuilder("hello\nworld");
        for (int i = 0; i < 2000; i++) {
            int a = r.nextInt(sb.length() + 1), b = Math.min(sb.length(), a + r.nextInt(4));
            String s = switch (r.nextInt(4)) { case 0 -> "\n"; case 1 -> "中x"; case 2 -> ""; default -> "ab\ncd"; };
            g.replace(a, b, s);
            sb.replace(a, b, s);
        }
        assertEquals(sb.toString(), g.toString());
        String str = sb.toString();
        String[] lines = str.split("\n", -1);
        assertEquals(lines.length, g.lineCount());
        int pos = 0;
        for (int row = 0; row < lines.length; row++) {
            assertEquals(pos, g.lineStart(row));
            assertEquals(lines[row], g.line(row));
            assertEquals(row, g.lineOf(pos));
            pos += lines[row].length() + 1;
        }
        assertEquals(str.substring(3, 17), g.substring(3, 17));
    }

    @Test
    void markersAndOverlaysFollowEdits() {
        Buffer b = new Buffer("x", "0123456789");
        Marker m = b.marker(5, false), adv = b.marker(5, true);
        Overlay o = b.addOverlay(2, 6);
        b.insert(5, "ab");
        assertEquals(5, m.pos());
        assertEquals(7, adv.pos());
        assertEquals(2, o.start());
        assertEquals(8, o.end(), "插在覆盖层里面：跟着变长");
        b.insert(8, "zz");
        assertEquals(8, o.end(), "插在 end（rear-advance nil）不算进来");
        b.delete(0, 3);
        assertEquals(0, o.start());
        Overlay ev = b.addOverlay(0, 2).evaporate(true);
        b.delete(0, 2);
        assertTrue(ev.deleted(), "evaporate：空了就删");
    }

    @Test
    void undoRedo() {
        Buffer b = new Buffer("x", "");
        b.insert(0, "abc");
        b.undo().boundary();
        b.replace(1, 2, "XY");
        b.undo().boundary();
        assertEquals("aXYc", b.contents());
        assertTrue(b.undo().undo(b, 0) >= 0);
        assertEquals("abc", b.contents());
        assertTrue(b.undo().redo(b, 0) >= 0);
        assertEquals("aXYc", b.contents());
        b.undo().undo(b, 0);
        b.undo().undo(b, 0);
        assertEquals("", b.contents());
        assertEquals(-1, b.undo().undo(b, 0));
    }
}
