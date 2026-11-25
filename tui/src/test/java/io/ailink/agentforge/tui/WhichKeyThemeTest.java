package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.Colors;
import io.ailink.agentforge.tui.term.Face;
import io.ailink.agentforge.tui.theme.Theme;
import io.ailink.agentforge.tui.theme.Themes;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WhichKeyThemeTest {
    @Test
    void whichKeyAfterDelayAndLeader() {
        Editor ed = TestSupport.fresh("");
        AtomicLong now = new AtomicLong(0);
        ed.setClock(now::get);
        Window first = ed.selectedWindow();
        ed.runCommand("split-window-right", null);
        ed.feed("C-x");
        assertFalse(ed.whichKeyVisible());
        assertTrue(ed.render().dump().contains("C-x-"), "echo area 显示按了一半的前缀");
        now.addAndGet(600);
        ed.runTimers();
        assertTrue(ed.whichKeyVisible());
        String dump = ed.render().dump();
        assertTrue(dump.contains("other-window"), dump);
        assertTrue(dump.contains("save-buffers-kill"), dump);
        assertFalse(dump.contains("split-window-right"), "编辑器的分窗键不在 C-x 下了：\n" + dump);
        ed.feed("o");
        assertNotSame(first, ed.selectedWindow(), "C-x o 换窗口");
        assertFalse(ed.whichKeyVisible());
    }

    @Test
    void describeKey() {
        Editor ed = TestSupport.fresh("");
        ed.feed("<f1> c C-f");
        assertEquals("C-f runs the command forward-char", ed.currentMessage());
        ed.feed("<f1> k C-x o");
        assertTrue(ed.buffer("*Help*").contents().contains("other-window"));
        ed.feed("C-h c C-x C-c");
        assertEquals("C-x C-c runs the command save-buffers-kill-terminal", ed.currentMessage(), "C-h 是帮助前缀");
    }

    @Test
    void themes() {
        assertEquals(60, Themes.slots().size());
        long palettes = Themes.names().stream().filter(n -> Themes.find(n).isPalette()).count();
        assertEquals(14, palettes, "内置 14 套调色板");
        assertTrue(Themes.names().contains("terminal"));
        for (String n : Themes.names()) {
            Map<String, Face> f = Themes.faces(n, true);
            assertNotNull(f.get("mode-line"), n);
            if (Themes.find(n).isPalette()) assertNotNull(f.get("default").bg(), n);
        }
        assertEquals(196, Colors.rgbTo256(0xff0000));
        assertEquals(16, Colors.rgbTo256(0x000000));
        assertEquals(231, Colors.rgbTo256(0xffffff));
        assertThrows(IllegalArgumentException.class, () -> Themes.register(Theme.ofPalette("bad", "bad", null, Map.of())));
        Themes.register(Theme.ofFaces("mine", "mine", Map.of("region", Face.bg(0x123456)), null));
        assertEquals(0x123456, Themes.faces("mine", true).get("region").bg());
        assertNotNull(Themes.faces("mine", true).get("mode-line"), "差异式回退到默认主题");
    }

    @Test
    void loadThemeViaMxAndFaceOverride() {
        Editor ed = TestSupport.fresh("x");
        assertEquals(Themes.defaultTheme(), ed.theme());
        Face before = ed.render().face(0, 0);
        ed.feed("M-x").typeText("load-theme").feed("RET").typeText("doom-light").feed("RET");
        assertEquals("doom-light", ed.theme());
        assertNotEquals(before, ed.render().face(0, 0), "换主题后格子 face 变了（整屏重画）");
        ed.setFace("default", Face.bg(0x010203));
        assertEquals(0x010203, ed.render().face(0, 0).bg());
        ed.loadTheme("space-dark");
        assertEquals(0x010203, ed.render().face(0, 0).bg(), "用户覆盖压在主题上面");
        assertEquals("Unknown theme: nope", assertThrows(UserError.class, () -> ed.loadTheme("nope")).getMessage());
    }
}
