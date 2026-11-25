package io.ailink.agentforge.sandbox.windows;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Windows 后端的平台无关部分（C# 的 WindowsSandboxUnitTests）。真机用例（TCP 归因、ACL 授权 / 去重 / deny 优先 / 快照还原、重解析点）
 * 与 Windows 集成测试没有搬：本机不是 Windows，跑不了。
 */
class WindowsSandboxUnitTest {
    @Test
    void commandLineQuotesLikeMsvcrt() {
        assertEquals("a b", WindowsSupport.commandLine(List.of("a", "b")));
        assertEquals("\"a b\" c", WindowsSupport.commandLine(List.of("a b", "c")));
        assertEquals("\"say \\\"hi\\\"\" x", WindowsSupport.commandLine(List.of("say \"hi\"", "x")));
        // 结尾反斜杠成对转义，防止把收尾引号转掉
        assertEquals("dir\\ y", WindowsSupport.commandLine(List.of("dir\\", "y")));
        assertEquals("\"\" z", WindowsSupport.commandLine(List.of("", "z")));
        assertNull(WindowsSupport.commandLine(List.of("a\u0000b")));
        assertNull(WindowsSupport.commandLine(List.of("x".repeat(WindowsSupport.MAX_COMMAND_LINE + 1))));
    }

    @Test
    void environmentBlockSortsCaseInsensitivelyAndKeepsFirstSpelling() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("Path", "p");
        env.put("PATH2", "q");
        String text = new String(WindowsSupport.unicodeBlock(env), StandardCharsets.UTF_16LE);
        assertTrue(text.endsWith("\u0000\u0000"));
        assertTrue(text.contains("Path=p"));
        assertFalse(text.contains("PATH=p"));
    }

    @Test
    void restrictedNetworkGetsPoisonedAndTempRedirect() {
        TreeMap<String, String> env = WindowsSupport.environment(ExecRequest.of("cmd").withEnvironment(Map.of("EXTRA", "1")), true, "C:\\run-temp");
        assertEquals("C:\\run-temp", env.get("TEMP"));
        assertEquals("http://127.0.0.1:9", env.get("HTTP_PROXY"));
        assertEquals("1", env.get("EXTRA"));
        assertFalse(env.containsKey("NO_PROXY_UNUSED"));
    }

    @Test
    void enabledNetworkIsNotPoisoned() {
        TreeMap<String, String> env = WindowsSupport.environment(ExecRequest.of("cmd").withInheritEnvironment(false), false, null);
        assertNotEquals("http://127.0.0.1:9", env.get("HTTP_PROXY"));
        assertNull(env.get("TEMP"));
    }

    @Test
    void capabilitySidIsARandomVersion1DomainSid() {
        String sid = WindowsSupport.randomCapabilitySid();
        assertTrue(sid.matches("^S-1-5-21-\\d+-\\d+-\\d+-\\d+$"), sid);
        assertNotEquals(sid, WindowsSupport.randomCapabilitySid());
    }

    @Test
    void ledgerRoundTripsEntriesWithEscaping() throws Exception {
        withTempDirs(1, d -> {
            String path = join(d[0], "ledger.log");
            List<LedgerEntry> entries = List.of(new LedgerEntry(join(d[0], "a|b"), "S-1-5-21-1-2-3-4", "grant", 0x1301FF, 3),
                    new LedgerEntry(join(d[0], "s"), "S-1-5-21-5-6-7-8", "synthetic", 0x130116, 3),
                    new LedgerEntry(join(d[0], "r"), "S-1-5-21-9-9-9-9", "deny-read", 0x1301BF, 0, "D:(A;;FA;;;SY)|x"));
            for (LedgerEntry e : entries) WindowsSupport.appendLedger(path, e);
            assertEquals(entries, WindowsSupport.readLedger(path));
        });
    }

    @Test
    void releaseCountsSharesAndHandsSnapshotToTheLastHolder() {
        LedgerEntry a = new LedgerEntry("/p", "S-1", "deny-read", 1, 0, "SNAP");
        LedgerEntry b = new LedgerEntry("/p", "S-1", "deny-read", 1, 0, null);
        WindowsSupport.Release r1 = WindowsSupport.release(List.of(a, b), a);
        assertFalse(r1.last());
        assertEquals(List.of(b.withSddl("SNAP")), r1.remaining());
        WindowsSupport.Release r2 = WindowsSupport.release(r1.remaining(), b);
        assertTrue(r2.last());
        assertEquals("SNAP", r2.snapshot());
        assertTrue(WindowsSupport.release(List.of(), a).last());
    }

    @Test
    void ledgerLockIsReentrantAcrossCallsAndSerializes() throws Exception {
        withTempDirs(1, d -> {
            String ledger = join(d[0], "acl-ledger.log");
            assertEquals("x", WindowsSupport.withLedgerLock(ledger, () -> "x"));
            assertEquals("y", WindowsSupport.withLedgerLock(ledger, () -> "y"));
        });
    }

    @Test
    void appContainerProfileNameIsStableAndWorkspaceSpecific() throws Exception {
        withTempDirs(2, d -> {
            String a = d[0], b = d[1];
            // Windows 路径不分大小写；Unix 上大写后是另一个路径 ⇒ 只在 Windows 上比
            if (Platform.isWindows()) assertEquals(AppContainerSandbox.profileName(a), AppContainerSandbox.profileName(a.toUpperCase(Locale.ROOT)));
            assertEquals(AppContainerSandbox.profileName(a), AppContainerSandbox.profileName(a));
            assertNotEquals(AppContainerSandbox.profileName(a), AppContainerSandbox.profileName(b));
            assertTrue(AppContainerSandbox.profileName(a).matches("^agentforge-ws-[0-9a-f]{32}$"));
        });
    }

    @Test
    void appContainerReadPathsAreAbsoluteAndIncludeHostRoots() throws Exception {
        withTempDirs(1, d -> {
            String host = d[0];
            List<String> paths = new AppContainerSandbox(host, List.of(host, host), false).effectiveToolchainReadPaths();
            assertTrue(paths.stream().allMatch(LexicalPaths::isAbsolute));
            assertTrue(paths.stream().anyMatch(p -> p.equalsIgnoreCase(host)));
            assertEquals(paths.size(), new HashSet<>(paths.stream().map(p -> p.toUpperCase(Locale.ROOT)).toList()).size());
        });
    }

    @Test
    void windowsBackendsRefuseToWrapOffWindows() throws Exception {
        if (Platform.isWindows()) return;
        withTempDirs(1, d -> {
            String ws = d[0];
            PathContext c = PathContext.of(ws);
            for (SandboxPolicy pol : List.of(SandboxPolicy.dangerFullAccess(), SandboxPolicy.readOnly(),
                    SandboxPolicy.workspaceWrite().withNetwork(NetworkMode.PROXIED))) {
                assertEquals(ErrorKind.INVALID_OPERATION, kind(thrown(() -> new AppContainerSandbox(ws).wrap(Exec.localBackend(), pol, c))));
                assertEquals(ErrorKind.INVALID_OPERATION, kind(thrown(() -> new RestrictedTokenSandbox(ws, true).wrap(Exec.localBackend(), pol, c))));
            }
            assertEquals("sandbox/win-not-windows", new AppContainerSandbox(ws).probe().unavailableReason().code());
        });
    }

    @Test
    void pathGuardRejectsAdsAndUnc() throws Exception {
        withTempDirs(1, d -> {
            String f = join(d[0], "a.txt");
            write(f, "x");
            assertEquals(ErrorKind.INVALID_OPERATION, kind(thrown(() -> WindowsSupport.canonicalizeRoot(f + ":stream"))));
            assertEquals(ErrorKind.INVALID_OPERATION, kind(thrown(() -> WindowsSupport.canonicalizeRoot("\\\\server\\share\\dir"))));
        });
    }

    @Test
    void requestChecksRefuseTtyEmptyAndBatch() {
        assertEquals(ErrorKind.NOT_SUPPORTED, kind(thrown(() -> WindowsSupport.checkRequest(ExecRequest.of("cmd").withTty(true)))));
        assertEquals(ErrorKind.ARGUMENT, kind(thrown(() -> WindowsSupport.checkRequest(ExecRequest.of(List.of(), null)))));
        assertEquals("sandbox/win-batch-refused", code(thrown(() -> WindowsSupport.checkRequest(ExecRequest.of("run.CMD")))));
        assertEquals("run.cmd", WindowsSupport.checkRequest(ExecRequest.of("run.cmd").withAllowBatchFile(true)));
    }
}
