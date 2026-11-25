package io.ailink.agentforge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 入口分流：不带子命令 / tui / 直接给开关 ⇒ 全屏工作台；其余照旧。 */
class EntryPointTest {
    @Test
    void routesToTui() {
        assertTrue(AgentForgeApplication.isTui(new String[0]));
        assertTrue(AgentForgeApplication.isTui(new String[]{"tui", "--yolo"}));
        assertTrue(AgentForgeApplication.isTui(new String[]{"--model", "anthropic/x"}));
        assertFalse(AgentForgeApplication.isTui(new String[]{"agent"}));
        assertFalse(AgentForgeApplication.isTui(new String[]{"chat"}));
        assertFalse(AgentForgeApplication.isTui(new String[]{"history"}));
        assertFalse(AgentForgeApplication.isTui(new String[]{"--spring.profiles.active=x"}));
    }
}
