package io.ailink.agentforge.sandbox.linux;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.policy.AccessMode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/sandboxing/src/bwrap_tests.rs 与 linux_run_main_tests.rs（detects_proc_mount_*）。 */
class BwrapDiscoveryTest {
    private static String fakeBwrap(String dir, String contents) throws Exception {
        String path = join(dir, "fake-bwrap");
        write(path, contents);
        chmod(path, "rwx------");
        return path;
    }

    private static String namedFakeBwrapIn(String dir) throws Exception {
        String path = dir + "/bwrap";
        write(path, "#!/bin/sh\n");
        chmod(path, "rwx------");
        return LinuxFs.realPath(path);
    }

    private static String find(List<String> paths, String cwd, SandboxPolicy pol) {
        return BwrapDiscovery.findExecutable("bwrap", paths, cwd, pol, ctx(cwd));
    }

    // ── 探测 ─────────────────────────────────────────────────────────

    @Test
    void probeReportsMissingSystemBwrap() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            SandboxCapabilities missing = new BwrapSandbox(BwrapSandbox.Options.DEFAULT.withSearchPaths(List.of(d[0]))).probe();
            SandboxCapabilities explicit = new BwrapSandbox(BwrapSandbox.Options.DEFAULT.withBwrapPath("/nonexistent/bwrap")).probe();
            assertFalse(missing.available());
            assertEquals("sandbox/bwrap-missing", missing.unavailableReason().code());
            assertFalse(explicit.available());
            assertEquals("sandbox/bwrap-missing", explicit.unavailableReason().code());
            assertTrue(explicit.unavailableReason().text().contains("/nonexistent/bwrap"));
        });
    }

    @Test
    void probeReportsUserNamespaceFailures() throws Exception {
        assumeTrue(linux());
        for (String failure : BwrapDiscovery.USER_NAMESPACE_FAILURES) {
            withTempDirs(1, d -> {
                String fake = fakeBwrap(d[0], "#!/bin/sh\necho '" + failure + "' >&2\nexit 1\n");
                BwrapDiscovery.UserNamespaceAccess r = BwrapDiscovery.userNamespaceAccess(fake, 5000);
                SandboxCapabilities caps = new BwrapSandbox(BwrapSandbox.Options.DEFAULT.withBwrapPath(fake)).probe();
                assertFalse(r.available(), failure);
                assertTrue(r.failure().contains(failure));
                assertFalse(caps.available());
                assertEquals("sandbox/bwrap-userns-blocked", caps.unavailableReason().code());
            });
        }
    }

    @Test
    void probeSkipsUnrelatedBwrapFailures() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String fake = fakeBwrap(d[0], "#!/bin/sh\necho 'bwrap: Unknown option --argv0' >&2\nexit 1\n");
            assertTrue(BwrapDiscovery.userNamespaceAccess(fake, 5000).available());
            assertTrue(new BwrapSandbox(BwrapSandbox.Options.DEFAULT.withBwrapPath(fake)).probe().available());
        });
    }

    @Test
    void probeTimesOutWithoutReportingAFailure() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String fake = fakeBwrap(d[0], "#!/bin/sh\nsleep 1\nexit 0\n");
            long t0 = System.nanoTime();
            BwrapDiscovery.UserNamespaceAccess r = BwrapDiscovery.userNamespaceAccess(fake, 10);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(r.available());
            assertTrue(ms < 500, ms + " ms");
        });
    }

    @Test
    void probeDoesNotWaitForDescendantsHoldingStderrOpen() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String fake = fakeBwrap(d[0], "#!/bin/sh\necho 'No permissions to create a new namespace' >&2\nsleep 1 &\nexit 1\n");
            long t0 = System.nanoTime();
            BwrapDiscovery.UserNamespaceAccess r = BwrapDiscovery.userNamespaceAccess(fake, 100);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertFalse(r.available());
            assertTrue(ms < 500, ms + " ms");
        });
    }

    @Test
    void detectsWsl1ProcVersionFormats() {
        assertTrue(BwrapDiscovery.procVersionIndicatesWsl1("Linux version 4.4.0-22621-Microsoft"));
        assertTrue(BwrapDiscovery.procVersionIndicatesWsl1("Linux version 5.15.0-microsoft-standard-WSL1"));
        assertTrue(BwrapDiscovery.procVersionIndicatesWsl1("Linux version 5.15.0-wsl-microsoft-standard-WSL1"));
    }

    @Test
    void doesNotTreatWsl2OrNativeLinuxAsWsl1() {
        for (String v : List.of("Linux version 6.6.87.2-microsoft-standard-WSL2", "Linux version 6.6.87.2-wsl-microsoft-standard-WSL2",
                "Linux version 4.19.104-microsoft-standard", "Linux version 6.6.87.2-microsoft-standard-WSL3", "Linux version 6.8.0"))
            assertFalse(BwrapDiscovery.procVersionIndicatesWsl1(v), v);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "bwrap: Can't mount proc on /newroot/proc: Invalid argument|true",
            "bwrap: Can't mount proc on /newroot/proc: Operation not permitted|true",
            "bwrap: Can't mount proc on /newroot/proc: Permission denied|true",
            "bwrap: Can't bind mount /dev/null: Operation not permitted|false"})
    void detectsProcMountFailures(String stderr, boolean expected) {
        assertEquals(expected, BwrapDiscovery.isProcMountFailure(stderr));
    }

    // ── find_executable_in_search_paths ──────────────────────────────

    @Test
    void findsFirstExecutableBwrapInJoinedSearchPath() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String cwd = join(d[0], "cwd"), first = join(d[0], "first"), second = join(d[0], "second");
            mkdirs(cwd, first, second);
            write(join(first, "bwrap"), "not executable");
            String expected = namedFakeBwrapIn(second);
            assertEquals(expected, find(List.of(first, second), cwd, SandboxPolicy.readOnly()));
        });
    }

    @Test
    void skipsWorkspaceLocalBwrapInJoinedSearchPath() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String cwd = join(d[0], "cwd"), trusted = join(d[0], "trusted");
            mkdirs(cwd, trusted);
            namedFakeBwrapIn(cwd);
            String expected = namedFakeBwrapIn(trusted);
            assertEquals(expected, find(List.of(cwd, trusted), cwd, SandboxPolicy.readOnly()));
        });
    }

    @Test
    void rootCwdDoesNotHideSystemBwrapCandidates() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String bin = join(d[0], "bin");
            mkdirs(bin);
            String expected = namedFakeBwrapIn(bin);
            assertEquals(expected, find(List.of(bin), "/", SandboxPolicy.readOnly()));
            assertNull(find(List.of(bin), "/", SandboxPolicy.dangerFullAccess()));
        });
    }

    @Test
    void skipsBwrapInWritableRootsOutsideCommandCwd() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String w = join(d[0], "workspace"), cwd = w + "/service", wbin = w + "/bin", extra = join(d[0], "extra"), trusted = join(d[0], "trusted");
            mkdirs(cwd, wbin, extra, trusted);
            namedFakeBwrapIn(wbin);
            namedFakeBwrapIn(extra);
            String expected = namedFakeBwrapIn(trusted);
            SandboxPolicy pol = SandboxPolicy.readOnly().plusEntries(e(w, WRITE), e(extra, WRITE));
            assertNull(find(List.of(wbin, extra), cwd, pol));
            assertEquals(expected, find(List.of(wbin, extra, trusted), cwd, pol));
        });
    }

    @Test
    void skipsBwrapInSymlinkedWritableRoot() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String w = join(d[0], "workspace"), cwd = w + "/service", wbin = w + "/bin", alias = join(d[0], "alias");
            mkdirs(cwd, wbin);
            symlink(alias, w);
            namedFakeBwrapIn(wbin);
            assertNull(find(List.of(wbin), cwd, SandboxPolicy.readOnly().plusEntries(e(alias, WRITE))));
        });
    }

    @Test
    void fullDiskWriteRejectsOwnedBwrapEvenWhenChmodReadOnly() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String bin = join(d[0], "bin");
            mkdirs(bin);
            String b = namedFakeBwrapIn(bin);
            chmod(b, "r-xr-xr-x");
            assertNull(find(List.of(bin), "/", SandboxPolicy.dangerFullAccess()));
        });
    }

    @Test
    void readOnlyBwrapCarveoutDoesNotTrustAReplaceableParent() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            String bin = join(d[0], "bin");
            mkdirs(bin);
            String b = namedFakeBwrapIn(bin);
            assertNull(find(List.of(bin), "/", restricted(e(d[0], WRITE), e(b, READ))));
        });
    }

    @Test
    void rootWritePreservesAReadOnlySystemInstallation() throws Exception {
        assumeTrue(linux());
        withTempDirs(1, d -> {
            // 系统里的可执行文件只当发现候选，⛔ 执行
            String systemBinary = LinuxFs.realPath("/bin/true");
            String protectedRoot = "/" + systemBinary.split("/")[1];
            symlink(join(d[0], "bwrap"), systemBinary);
            for (SandboxPolicy pol : List.of(restricted(e(ROOT, WRITE), e(protectedRoot, READ)), SandboxPolicy.dangerFullAccess())) {
                boolean canModify = pol.fullDiskWriteAccess() && LinuxFs.effectiveUid() == 0;
                assertEquals(canModify ? null : systemBinary, find(List.of(d[0]), "/", pol));
            }
        });
    }
}
