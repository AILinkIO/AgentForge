package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.Grid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinibufferTest {
    @Test
    void matcherRanking() {
        List<String> items = List.of("forward-char", "char-after", "backward-char", "fc", "find-char-x");
        assertEquals(List.of("char-after", "forward-char", "backward-char", "find-char-x"), Matcher.match("char", items));
        assertEquals("fc", Matcher.match("fc", items).getFirst(), "完全相同排第一");
        assertTrue(Matcher.match("fwc", items).contains("forward-char"), "flex");
        assertEquals(List.of("backward-char"), Matcher.match("char back", items), "orderless：词序不限");
        assertTrue(Matcher.match("Char", items).isEmpty(), "有大写就区分大小写");
    }

    @Test
    void mxRunsCommandWithHelmDisplay() {
        Editor ed = TestSupport.fresh("abc");
        ed.feed("M-x");
        assertTrue(ed.minibufferActive());
        ed.typeText("end-of-buf");
        Grid g = ed.render();
        String dump = g.dump();
        assertTrue(dump.contains("Emacs Commands"), dump);
        assertTrue(dump.contains("*helm M-x*"));
        assertTrue(dump.contains("[1 Candidate]"), dump);
        assertTrue(dump.contains("M-x end-of-buf"));
        assertTrue(dump.contains("M->"), "注释是键");
        ed.feed("RET");
        assertFalse(ed.minibufferActive());
        assertEquals(3, ed.point());
    }

    @Test
    void mxKeepsPrefixArgAndHistory() {
        Editor ed = TestSupport.fresh("x".repeat(10));
        ed.feed("C-u M-x").typeText("forward-char").feed("RET");
        assertEquals(4, ed.point());
        ed.feed("M-x M-p");
        assertEquals("forward-char", ed.minibufferInput());
        ed.feed("C-g");
        assertFalse(ed.minibufferActive());
        assertEquals("Quit", ed.currentMessage());
    }

    @Test
    void completingReadSelectionAndRequireMatch() {
        Editor ed = TestSupport.fresh("");
        List<String> got = new ArrayList<>();
        ed.completingRead("Pick: ", List.of("apple", "banana", "cherry"), got::add);
        ed.feed("C-n C-n RET");
        assertEquals(List.of("cherry"), got);
        ed.completingRead("Pick: ", List.of("apple"), got::add);
        ed.typeText("zzz").feed("RET");
        assertTrue(ed.minibufferActive(), "必须选中候选");
        assertEquals("[No match]", ed.currentMessage());
        ed.feed("C-g");
        ed.readString("Name: ", "ab", got::add);
        ed.typeText("c").feed("RET");
        assertEquals("abc", got.getLast());
    }

    @Test
    void popupStyleMinibuffer() {
        Editor ed = TestSupport.fresh("");
        ed.options().set("minibuffer-display", "popup");
        ed.completingRead("Pick: ", List.of("apple", "banana"), s -> {});
        String dump = ed.render().dump();
        assertTrue(dump.contains("╭"), dump);
        assertTrue(dump.contains("Pick: "));
        assertTrue(dump.contains("banana"));
    }

    @Test
    void yesOrNoAndYOrN() {
        Editor ed = TestSupport.fresh("");
        boolean[] yes = {false};
        ed.yesOrNo("Really?", () -> yes[0] = true);
        ed.typeText("yes").feed("RET");
        assertTrue(yes[0]);
        List<Boolean> ans = new ArrayList<>();
        ed.yOrN("Delete?", ans::add);
        assertTrue(ed.render().dump().contains("Delete? (y or n)"));
        ed.feed("x");
        assertTrue(ans.isEmpty(), "别的键：再问一遍");
        ed.feed("y");
        ed.yOrN("Again?", ans::add);
        ed.feed("C-g");
        assertEquals(List.of(true, false), ans);
    }

    @Test
    void popupMenu() {
        Editor ed = TestSupport.fresh("");
        int[] chosen = {-1};
        Popup p = ed.popupMenu("Choose", List.of("one", "two", "three"), i -> chosen[0] = i);
        String dump = ed.render().dump();
        assertTrue(dump.contains("Choose") && dump.contains("three"), dump);
        ed.feed("C-n C-n RET");
        assertEquals(2, chosen[0]);
        assertFalse(ed.popups().contains(p));
    }
}
