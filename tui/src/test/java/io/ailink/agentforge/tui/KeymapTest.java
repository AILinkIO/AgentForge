package io.ailink.agentforge.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KeymapTest {
    @Test
    void kbdAndLookup() {
        assertEquals(List.of("C-x", "C-f"), Keymap.kbd("C-x C-f"));
        assertEquals(List.of("<left>", "S-<left>", "<prior>"), Keymap.kbd("left S-left pageup"));
        assertEquals("C-M-x", Keymap.normalizeKey("M-C-x"));
        Keymap m = new Keymap().define("C-x C-f", "find-file").define("C-x b", "switch-to-buffer");
        assertEquals("find-file", m.lookup(Keymap.kbd("C-x C-f")));
        assertInstanceOf(Keymap.class, m.lookup(Keymap.kbd("C-x")));
        assertNull(m.lookup(Keymap.kbd("C-x z")));
        assertEquals("C-x C-f", m.whereIs("find-file"));
        Keymap m2 = new Keymap().define("C-x 9", "foo");
        Keymap merged = (Keymap) Keymap.lookup(List.of(m2, m), Keymap.kbd("C-x"));
        assertEquals("foo", merged.get("9"), "前缀跨表合并");
        assertEquals("switch-to-buffer", merged.get("b"));
        assertEquals("foo", Keymap.lookup(List.of(m2, m), Keymap.kbd("C-x 9")));
        assertTrue(Keymap.printable("a"));
        assertTrue(Keymap.printable("中"));
        assertFalse(Keymap.printable("C-a"));
        assertFalse(Keymap.printable("<up>"));
    }

    @Test
    void parentsPrefixNamesAndUnbind() {
        Keymap parent = new Keymap().define("q", "quit-window").define("C-c a", "a");
        Keymap child = new Keymap().setParent(parent).define("C-c b", "b");
        assertEquals("quit-window", child.lookup(List.of("q")));
        Keymap cc = (Keymap) child.lookup(List.of("C-c"));
        assertEquals("a", cc.get("a"), "父子表的同一前缀合并");
        assertEquals("b", cc.get("b"));
        Keymap g = new Keymap().define("C-c w d", "delete-window").prefixName("C-c w", "windows");
        List<String[]> under = ((Keymap) g.lookup(List.of("C-c"))).bindingsUnder();
        assertEquals("w", under.getFirst()[0]);
        assertEquals("+windows", under.getFirst()[1]);
        g.undefine("C-c w d");
        assertNull(g.lookup(Keymap.kbd("C-c w d")));
    }

    @Test
    void escIsMetaPrefixAndShiftTranslation() {
        Editor ed = TestSupport.fresh("");
        ed.feed("ESC x");
        assertTrue(ed.minibufferActive(), "ESC 然后 x（分开到达）= M-x");
        ed.feed("ESC ESC ESC");
        assertFalse(ed.minibufferActive(), "minibuffer 里 ESC ESC ESC 退出");
        Editor e2 = TestSupport.fresh("abc", 0);
        e2.feed("C-S-f");
        assertEquals(1, e2.point(), "C-S-f 没绑 ⇒ 去掉 Shift 当 C-f");
    }

    @Test
    void undefinedKeyAndPrefixQuit() {
        Editor ed = TestSupport.fresh("");
        ed.feed("C-x C-z");
        assertEquals("C-x C-z is undefined", ed.currentMessage());
        ed.feed("C-x C-g");
        assertEquals("Quit", ed.currentMessage());
        assertTrue(ed.pendingKeys().isEmpty());
    }

    @Test
    void escPrefixOptionMakesEscAPlainKey() {
        Editor ed = new Editor(40, 10);
        int[] hits = {0};
        ed.globalMap().define("C-c x", (Command) e -> { });
        ed.currentBuffer().setLocalKeymap(new Keymap().define("ESC", (Command) e -> hits[0]++));
        ed.feed("ESC x");
        assertEquals(0, hits[0], "缺省：ESC 是 Meta 前缀（ESC x = M-x）");
        ed.feed("C-g C-g");
        ed.options().set("esc-prefix", false);
        ed.feed("ESC");
        assertEquals(1, hits[0], "关掉 esc-prefix：单按 ESC 当普通键派发");
    }
}
