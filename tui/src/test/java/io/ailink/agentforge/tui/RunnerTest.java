package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.demo.Main;
import io.ailink.agentforge.tui.term.VirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 端到端：TuiRunner + 虚拟终端（输入线程、事件循环、post、diff 输出全走一遍）。 */
class RunnerTest {
    static void await(BooleanSupplier c, String what, VirtualTerminal vt) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("timeout: " + what + "\n" + vt.screen());
            Thread.sleep(20);
        }
    }

    @Test
    void demoEndToEnd() throws Exception {
        I18n.setLanguage("en");
        VirtualTerminal vt = new VirtualTerminal(80, 24);
        Editor ed = Main.build(80, 24);
        Thread ui = new Thread(() -> {
            try { new TuiRunner(ed, vt).run(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        ui.start();
        await(() -> vt.screen().contains("AgentForge"), "header", vt);
        vt.type("hello 你好\r");
        await(() -> vt.screen().contains("结论：一切正常"), "streamed reply", vt);
        String s = vt.screen();
        assertTrue(s.contains("▍你"), s);
        assertTrue(s.contains("hello 你好"));
        assertTrue(s.contains("12 行输出已折叠"), s);
        // 斜杠补全
        vt.type("/th");
        await(() -> vt.screen().contains("/theme"), "slash popup", vt);
        vt.type("\t");
        await(() -> !vt.screen().contains("╭"), "popup closed", vt);
        // 尺寸变化
        vt.resize(100, 30);
        await(() -> vt.screen().lines().count() == 30 && vt.line(28).contains("everforest-dark"), "resize", vt);
        vt.type("\u0018\u0003");      // C-x C-c
        ui.join(5000);
        assertFalse(ui.isAlive());
    }
}
