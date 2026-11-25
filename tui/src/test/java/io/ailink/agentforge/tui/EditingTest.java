package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.commands.EditingCommands;
import org.junit.jupiter.api.Test;

import static io.ailink.agentforge.tui.TestSupport.fresh;
import static io.ailink.agentforge.tui.TestSupport.text;
import static org.junit.jupiter.api.Assertions.*;

/** 输入框的编辑命令（移植 coxswain basic_test / editor_test 里还适用的部分）。 */
class EditingTest {
    @Test
    void whitespaceAndLines() {
        Editor e = fresh("ab", 1).feed("C-o");
        assertEquals("a\nb", text(e));
        assertEquals(1, e.point());
        assertEquals("a\n\n\nb", text(fresh("ab", 1).feed("C-3 C-o")));
        assertEquals("ab", text(fresh("a  \t b", 2).feed("M-\\")));
        assertEquals("a b", text(fresh("a  \t b", 2).feed("M-SPC")));
        assertEquals("End of buffer", fresh("ab", 2).feed("C-d").currentMessage());
        assertEquals("Beginning of buffer", fresh("ab", 0).feed("DEL").currentMessage());
        assertEquals("b", text(fresh("ab", 1).feed("DEL")));
        assertEquals("d", text(fresh("abcd").feed("C-SPC C-f C-f C-f DEL")), "活动区域时 DEL 删整个区域");
    }

    @Test
    void transposeAndCase() {
        Editor e = fresh("abc", 1).feed("C-t");
        assertEquals("bac", text(e));
        assertEquals(2, e.point());
        assertEquals("acb", text(fresh("abc", 3).feed("C-t")));
        e = fresh("hello world").feed("M-u");
        assertEquals("HELLO world", text(e));
        assertEquals(5, e.point());
        assertEquals("hello WORLD", text(fresh("HELLO WORLD").feed("M-l")).replace("hello WORLD", "hello WORLD"));
        assertEquals("Hello World", text(fresh("hELLO wORLD").feed("M-c M-c")));
        assertEquals("HELLO WORLD", text(fresh("hello world").feed("C-2 M-u")));
        assertEquals("Foo-Bar 1st", EditingCommands.capitalize("foo-BAR 1ST"));
    }

    @Test
    void movement() {
        Editor e = fresh("foo bar\nbaz qux");
        e.feed("M-f");
        assertEquals(3, e.point());
        e.feed("C-n");
        assertEquals(11, e.point(), "保持目标列");
        e.feed("C-e");
        assertEquals(15, e.point());
        e.feed("M-b C-a");
        assertEquals(8, e.point());
        e.feed("M->");
        assertEquals(15, e.point());
        e.feed("M-<");
        assertEquals(0, e.point());
        e = fresh("😀ab", 0).feed("C-f");
        assertEquals(2, e.point(), "C-f 不拆代理对");
    }

    @Test
    void markCommands() {
        Editor e = fresh("abcdef").feed("C-SPC C-f C-f C-f");
        e.runCommand("exchange-point-and-mark", null);
        assertEquals(0, e.point());
        assertEquals(3, e.currentBuffer().mark());
        e = fresh("abc\ndef", 3);
        e.runCommand("mark-whole-buffer", null);
        assertEquals(0, e.point());
        assertEquals(7, e.currentBuffer().mark());
        assertEquals("Mark set", e.currentMessage());
        e = fresh("abc");
        e.runCommand("exchange-point-and-mark", null);
        assertEquals("No mark set in this buffer", e.currentMessage());
    }

    @Test
    void killAndYank() {
        assertEquals("a\nb", text(fresh("a\n   \nb", 2).feed("C-k")));
        assertEquals("c", text(fresh("a\nb\nc").feed("C-2 C-k")));
        assertEquals("End of buffer", fresh("ab", 2).feed("C-k").currentMessage());
        Editor e = fresh("foo bar baz").feed("M-d");
        assertEquals(" bar baz", text(e));
        assertEquals("foo", e.currentKill());
        assertEquals(" baz", text(fresh("foo bar baz").feed("C-2 M-d")));
        assertEquals("foo baz", text(fresh("foo bar baz", 8).feed("M-DEL")));
        e = fresh("abc").feed("C-SPC C-f M-w C-e C-y");
        assertEquals("abca", text(e));
        assertEquals(3, e.currentBuffer().mark());
        assertEquals("Kill ring is empty", fresh("abc").feed("C-y").currentMessage());
        assertTrue(fresh("abc").feed("M-y").currentMessage().contains("not a yank"));
        e = fresh("one\ntwo\n").feed("C-k C-k C-k");
        assertEquals("one\ntwo", e.currentKill(), "连续的 kill 接在一起");
        e = fresh("aa bb").feed("M-d M-d");
        e.feed("C-e");
        e = fresh("x y z").feed("M-d C-f M-d C-f M-d");
        e.feed("C-y M-y");
        assertTrue(text(e).contains("y") || text(e).contains("x"), "M-y 换成更早的一条");
    }

    @Test
    void undoAmalgamationAndRedo() {
        Editor e = fresh("");
        e.typeText("abcdefghijklmnopqrstuvwxy");          // 25 个字：20 + 5 两组
        e.feed("C-/");
        assertEquals("abcdefghijklmnopqrst", text(e));
        e.feed("C-/");
        assertEquals("", text(e));
        assertEquals("No further undo information", e.feed("C-/").currentMessage());
        e.feed("C-?");
        assertEquals("abcdefghijklmnopqrst", text(e));
        e = fresh("hello").feed("M-f M-DEL");
        assertEquals("", text(e));
        e.feed("C-/");
        assertEquals("hello", text(e));
        assertEquals(5, e.point(), "undo 回到改动之前的 point");
    }

    @Test
    void prefixArguments() {
        String x = "x".repeat(20);
        assertEquals(4, fresh(x).feed("C-u C-f").point());
        assertEquals(16, fresh(x).feed("C-u C-u C-f").point());
        assertEquals(12, fresh(x).feed("C-1 C-2 C-f").point());
        assertEquals(12, fresh(x).feed("C-u 1 2 C-f").point(), "C-u 后面的数字从头算");
        assertEquals(7, fresh(x, 10).feed("M-- C-3 C-f").point());
        Editor e = fresh("abc").feed("C-SPC C-f C-g");
        assertFalse(e.regionActive());
        assertEquals("Quit", e.currentMessage());
    }

    @Test
    void readOnlyAndQuotedInsert() {
        Editor e = fresh("abc");
        e.runCommand("read-only-mode", null);
        assertTrue(e.currentBuffer().readOnly());
        e.typeText("x");
        assertEquals("abc", text(e));
        assertTrue(e.currentMessage().contains("read-only"));
        assertEquals("\u0001", text(fresh("").feed("C-q C-a")));
        assertEquals("\t", text(fresh("").feed("C-q TAB")));
    }

    @Test
    void pasteIsOneUndoGroup() {
        Editor e = fresh("");
        e.handleEvent(new io.ailink.agentforge.tui.term.InputEvent.Paste("line1\r\nline2"));
        assertEquals("line1\nline2", text(e));
        e.feed("C-/");
        assertEquals("", text(e));
    }

    @Test
    void keyboardMacroAndRepeat() {
        Editor e = fresh("");
        // 宏键已不在 C-x 下（命令走 M-x）；这里临时绑到 F5～F7 上试录制 / 回放
        e.globalMap().define("<f5>", "kmacro-start-macro").define("<f6>", "kmacro-end-macro").define("<f7>", "kmacro-end-and-call-macro");
        e.feed("<f5>").typeText("ab").feed("<f6>");
        e.feed("<f7>");
        assertEquals("abab", text(e));
        e.runCommand("repeat", null);
        assertEquals("abab", text(e).substring(0, 4));
    }
}
