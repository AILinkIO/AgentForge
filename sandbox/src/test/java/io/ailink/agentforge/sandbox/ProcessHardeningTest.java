package io.ailink.agentforge.sandbox;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.exec.ExecRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/process-hardening/src/lib.rs 的 env_keys_with_prefix_filters_only_matching_keys。 */
class ProcessHardeningTest {
    @Test
    void envKeysWithPrefixFiltersOnlyMatchingKeys() {
        assertEquals(List.of("LD_TEST"), ProcessHardening.keysWithPrefix(List.of("PATH", "LD_TEST", "DYLD_FOO"), List.of("LD_")));
    }

    // JVM 改不了自己的环境 ⇒ 继承来的那份经第二个参数注入
    @Test
    void loaderVariablesAreRemovedFromChildEnvironmentIncludingInheritedOnes() {
        ExecRequest req = ExecRequest.of("true").withEnvironment(Map.of("DYLD_INSERT_LIBRARIES", "/evil.dylib", "KEEP", "1"));
        Set<String> inherited = Set.of("PATH", "LD_KS_HARDENING_TEST");
        ExecRequest hardened = ProcessHardening.withoutLoaderVariables(req, inherited);
        assertNull(hardened.environment().get("DYLD_INSERT_LIBRARIES"));
        assertTrue(hardened.environment().containsKey("DYLD_INSERT_LIBRARIES"));
        assertNull(hardened.environment().get("LD_KS_HARDENING_TEST"));
        assertTrue(hardened.environment().containsKey("LD_KS_HARDENING_TEST"));
        assertEquals("1", hardened.environment().get("KEEP"));
        assertFalse(ProcessHardening.withoutLoaderVariables(req.withInheritEnvironment(false), inherited).environment().containsKey("LD_KS_HARDENING_TEST"));
    }

    // 子 JVM 带着 LD_KS_HARDENING_PROBE 起来，在里面加固请求再起 sh：变量真被去掉了
    @Test
    void hardenedRequestReallyDropsInheritedLoaderVariables() throws Exception {
        if (Platform.isWindows()) return;
        String java = System.getProperty("java.home") + "/bin/java";
        ProcessBuilder pb = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                HardeningProbeMain.class.getName()).redirectErrorStream(true);
        pb.environment().put("LD_KS_HARDENING_PROBE", "x");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        List<String> ls = out.strip().lines().toList();
        assertEquals("[]", ls.getLast().strip(), out);
    }

    @Test
    void coreDumpsAreDisabledForTheCurrentProcess() {
        if (Platform.isWindows()) {
            assertEquals(ErrorKind.PLATFORM_NOT_SUPPORTED, kind(thrown(ProcessHardening::disableCoreDumps)));
            return;
        }
        ProcessHardening.disableCoreDumps();
        assertArrayEquals(new long[] {0, 0}, ProcessHardening.coreLimit());
    }

    @Test
    void dumpableFlagIsClearedOnLinux() {
        if (!Platform.isLinux()) return;
        assertTrue(ProcessHardening.isDumpable());
        try {
            ProcessHardening.disableDebuggerAttach();
            assertFalse(ProcessHardening.isDumpable());
        } finally {
            // 复原：非 dump 状态下 /proc/self 的文件归 root，别影响同进程的其他测试
            ProcessHardening.setDumpable(true);
        }
        assertTrue(ProcessHardening.isDumpable());
    }
}
