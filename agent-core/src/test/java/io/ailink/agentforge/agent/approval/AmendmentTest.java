package io.ailink.agentforge.agent.approval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AmendmentTest {
    @Test
    void shellWrappedScriptIsDescribedAsExactCommand() {
        assertEquals("与这条完全相同的命令",
                new Amendment.PrefixRule(List.of("bash", "-lc", "printf 'a' > n.txt && ls")).describe());
        assertEquals("与这条完全相同的命令",
                new Amendment.PrefixRule(List.of("/bin/zsh", "-c", "echo hi > x")).describe());
        assertEquals("与这条完全相同的命令",
                new Amendment.PrefixRule(List.of("python3", "-c", "import sys; print(1)")).describe());
        assertEquals("以 `cargo build` 开头的命令", new Amendment.PrefixRule(List.of("cargo", "build")).describe());
    }
}
