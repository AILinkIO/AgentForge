package io.ailink.agentforge.sandbox.linux;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.policy.AccessMode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/linux-sandbox/src/bwrap.rs 的 tests 与 linux_run_main_tests.rs。
 * 差别：文件遮罩是 {@code --ro-bind <000 空文件> <path>}（Codex 是 {@code --perms 000 --ro-bind-data <fd> <path>}）。
 */
class BwrapArgumentsTest {
    private String maskDir;
    private String mask;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(linux(), "Linux only");
        maskDir = tempDir();
        mask = join(maskDir, "masked");
        write(mask, "");
    }

    @AfterEach
    void tearDown() {
        if (maskDir != null) deleteTree(maskDir);
    }

    private BwrapArgs.Options opts() {
        return opts(NetworkMode.ENABLED);
    }

    private BwrapArgs.Options opts(NetworkMode network) {
        return BwrapArgs.Options.DEFAULT.withMaskFile(mask).withNetwork(network);
    }

    private List<String> fs(SandboxPolicy pol, String cwd) {
        return BwrapArgs.filesystemArgs(pol, ctx(cwd), opts()).args();
    }

    static int indexOf(List<String> xs, String... window) {
        return Collections.indexOfSubList(xs, List.of(window));
    }

    static int lastIndexOf(List<String> xs, String... window) {
        return Collections.lastIndexOfSubList(xs, List.of(window));
    }

    static int count(List<String> xs, String... window) {
        int n = 0;
        for (int i = 0; i + window.length <= xs.size(); i++) if (xs.subList(i, i + window.length).equals(List.of(window))) n++;
        return n;
    }

    private boolean masked(List<String> xs, String path) {
        return indexOf(xs, "--ro-bind", mask, path) >= 0;
    }

    static String s(List<String> xs) {
        return String.join(" ", xs);
    }

    @Test
    void pidInheritanceAndProcModes() {
        for (SandboxPolicy pol : List.of(SandboxPolicy.readOnly(), SandboxPolicy.dangerFullAccess().withNetwork(NetworkMode.RESTRICTED))) {
            for (boolean[] mode : new boolean[][] {{true, false}, {false, false}, {false, true}}) {
                boolean proc = mode[0], inherit = mode[1];
                List<String> a = BwrapArgs.build(List.of("/bin/true"), pol, ctx("/"), "/",
                        opts(NetworkMode.RESTRICTED).withMountProc(proc).withInheritPid(inherit)).args();
                assertEquals(!inherit, a.contains("--unshare-pid"));
                assertEquals(proc, a.contains("--proc"));
                assertTrue(a.contains("--unshare-user"));
                assertTrue(a.contains("--unshare-ipc"));
                assertTrue(a.contains("--unshare-net"));
                assertTrue(indexOf(a, "--cap-drop", "ALL") >= 0);
            }
        }
    }

    @Test
    void fullDiskWriteFullNetworkReturnsUnwrappedCommand() {
        assertEquals(List.of("/bin/true"), BwrapArgs.build(List.of("/bin/true"), SandboxPolicy.dangerFullAccess(), ctx("/"), "/", opts()).args());
    }

    @Test
    void fullDiskWriteProxyOnlyKeepsFullFilesystemButUnsharesNetwork() {
        assertEquals(List.of("--new-session", "--die-with-parent", "--bind", "/", "/", "--dev", "/dev", "--bind-try", "/dev/shm", "/dev/shm",
                        "--unshare-user", "--unshare-ipc", "--unshare-pid", "--unshare-net", "--proc", "/proc", "--cap-drop", "ALL", "--", "/bin/true"),
                BwrapArgs.build(List.of("/bin/true"), SandboxPolicy.dangerFullAccess(), ctx("/"), "/", opts(NetworkMode.PROXIED)).args());
    }

    @Test
    void proxiedBridgeBindsControlSocketAndNativeLibrary() {
        List<String> a = BwrapArgs.build(List.of("/bin/true"), SandboxPolicy.dangerFullAccess(), ctx("/"), "/",
                opts(NetworkMode.PROXIED).withProxyBridge("/tmp/ks.sock", "/host/lib.so")).args();
        assertTrue(indexOf(a, "--ro-bind", "/tmp/ks.sock", "/tmp/ks.sock") >= 0);
        assertTrue(indexOf(a, "--ro-bind", "/host/lib.so", BwrapArgs.PROXY_BRIDGE_LIBRARY_TARGET) >= 0);
    }

    @Test
    void fullDiskWriteWithUnreadableGlobStillWrapsAndMasksMatch() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String rootEnv = join(temp, ".env"), alias = join(temp, "root-alias");
            write(rootEnv, "secret");
            symlink(alias, "/");
            SandboxPolicy pol = restricted(e(ROOT, WRITE), denyGlob(temp + "/**/*.env"), e(alias, WRITE), e("/dev/zero", DENY));
            List<String> a = BwrapArgs.build(List.of("/bin/true"), pol, ctx(temp), temp, opts()).args();
            int wr = indexOf(a, "--bind", "/", "/");
            int dev = lastIndexOf(a, "--dev", "/dev", "--bind-try", "/dev/shm", "/dev/shm");
            int dm = indexOf(a, "--ro-bind", mask, "/dev/zero");
            assertNotEquals(List.of("/bin/true"), a);
            assertTrue(masked(a, rootEnv), s(a));
            assertTrue(masked(a, "/dev/zero"), s(a));
            assertEquals(1, count(a, "--bind", "/", "/"));
            assertTrue(wr < dev && dev < dm, s(a));
        });
    }

    @Test
    void restrictedPolicyChdirsToCanonicalCommandCwd() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String realRoot = join(temp, "real"), linkRoot = join(temp, "link"), commandCwd = linkRoot + "/subdir";
            mkdirs(realRoot + "/subdir");
            symlink(linkRoot, realRoot);
            List<String> a = BwrapArgs.build(List.of("/bin/true"), restricted(e(MINIMAL, READ), e(PROJECT_ROOTS, WRITE)), ctx(linkRoot), commandCwd,
                    opts()).args();
            String rt = LinuxFs.realPath(temp);
            assertTrue(indexOf(a, "--chdir", rt + "/real/subdir") >= 0, s(a));
            assertTrue(indexOf(a, "--ro-bind", rt + "/real", rt + "/real") >= 0, s(a));
            assertTrue(indexOf(a, "--chdir", commandCwd) < 0);
            assertTrue(indexOf(a, "--ro-bind", linkRoot, linkRoot) < 0);
        });
    }

    @Test
    void symlinkedWritableRootsBindRealTargetAndRemapCarveouts() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String realRoot = join(temp, "real"), linkRoot = join(temp, "link");
            mkdirs(realRoot + "/blocked");
            symlink(linkRoot, realRoot);
            List<String> a = fs(restricted(e(linkRoot, WRITE), e(linkRoot + "/blocked", DENY)), temp);
            String real = LinuxFs.realPath(realRoot);
            assertTrue(indexOf(a, "--bind", real, real) >= 0, s(a));
            assertTrue(indexOf(a, "--perms", "000", "--tmpfs", real + "/blocked", "--remount-ro", real + "/blocked") >= 0, s(a));
        });
    }

    @Test
    void writableRootsUnderSymlinkedAncestorsBindRealTarget() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String home = join(temp, "home"), realCodex = join(temp, "real-codex"), logicalCodex = home + "/.codex";
            mkdirs(home, realCodex + "/memories");
            symlink(logicalCodex, realCodex);
            String logical = logicalCodex + "/memories";
            List<String> a = fs(restricted(e(logical, WRITE)), temp);
            String real = LinuxFs.realPath(realCodex + "/memories");
            assertTrue(indexOf(a, "--bind", real, real) >= 0, s(a));
            assertTrue(indexOf(a, "--bind", logical, logical) < 0);
        });
    }

    @Test
    void protectedSymlinkedDirectorySubpathsFailClosed() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String root = join(temp, "root"), target = root + "/agents-target", link = root + "/.agents";
            mkdirs(target);
            symlink(link, target);
            Throwable ex = thrown(() -> fs(restricted(e(root, WRITE)), temp));
            assertEquals("sandbox/bwrap-read-only-symlink", code(ex));
            assertEquals(ErrorKind.INVALID_OPERATION, kind(ex));
            assertTrue(ex.getMessage().contains(link));
            assertTrue(english(() -> Message.of("sandbox/bwrap-read-only-symlink", link, link).text()).contains("cannot enforce sandbox read-only path"));
        });
    }

    @Test
    void symlinkedWritableRootsNestedSymlinkEscapePathsFailClosed() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String realRoot = join(temp, "real"), linkRoot = join(temp, "link"), outside = join(temp, "outside-private");
            mkdirs(realRoot, outside);
            symlink(linkRoot, realRoot);
            symlink(realRoot + "/linked-private", outside);
            Throwable ex = thrown(() -> fs(restricted(e(linkRoot, WRITE), e(linkRoot + "/linked-private", DENY)), temp));
            assertEquals("sandbox/bwrap-deny-symlink", code(ex));
            assertTrue(ex.getMessage().contains(LinuxFs.realPath(realRoot) + "/linked-private"), ex.getMessage());
        });
    }

    @Test
    void ignoresMissingWritableRoots() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String existing = join(temp, "existing"), missing = join(temp, "missing");
            mkdirs(existing);
            List<String> a = fs(SandboxPolicy.workspaceWrite(List.of(existing, missing), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP), temp);
            assertTrue(indexOf(a, "--bind", existing, existing) >= 0);
            assertFalse(a.contains(missing));
        });
    }

    @Test
    void mountsDevBeforeWritableDevBinds() {
        List<String> a = fs(SandboxPolicy.workspaceWrite(List.of("/dev"), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP), "/");
        int initial = indexOf(a, "--dev", "/dev"), restored = lastIndexOf(a, "--dev", "/dev");
        int rootBind = indexOf(a, "--bind", "/", "/"), devBind = indexOf(a, "--bind", "/dev", "/dev");
        assertEquals(2, count(a, "--dev", "/dev"));
        assertTrue(initial < rootBind && rootBind < restored, s(a));
        assertTrue(restored < devBind, s(a));
    }

    @Test
    void restrictedReadOnlyUsesScopedReadRootsInsteadOfErroring() throws Exception {
        withTempDirs(1, d -> {
            String readable = join(d[0], "readable");
            mkdirs(readable);
            List<String> a = fs(restricted(e(readable, READ)), d[0]);
            assertEquals(List.of("--tmpfs", "/", "--dev", "/dev"), a.subList(0, 4));
            assertTrue(indexOf(a, "--ro-bind", readable, readable) >= 0);
        });
    }

    @Test
    void restrictedReadOnlyWithPlatformDefaultsIncludesUsrWhenPresent() throws Exception {
        withTempDirs(1, d -> {
            List<String> a = fs(restricted(e(MINIMAL, READ)), d[0]);
            assertEquals(List.of("--tmpfs", "/"), a.subList(0, 2));
            if (isDir("/usr")) assertTrue(indexOf(a, "--ro-bind", "/usr", "/usr") >= 0);
        });
    }

    @Test
    void splitPolicyReappliesUnreadableCarveoutsAfterWritableBinds() throws Exception {
        withTempDirs(1, d -> {
            String w = join(d[0], "workspace"), blocked = w + "/blocked";
            mkdirs(blocked);
            List<String> a = fs(restricted(e(w, WRITE), e(blocked, DENY)), d[0]);
            int bind = indexOf(a, "--bind", w, w), m = indexOf(a, "--perms", "000", "--tmpfs", blocked, "--remount-ro", blocked);
            assertTrue(bind >= 0 && m >= 0 && bind < m, s(a));
        });
    }

    @Test
    void splitPolicyReenablesNestedWritableSubpathsAfterReadOnlyParent() throws Exception {
        withTempDirs(1, d -> {
            String w = join(d[0], "workspace"), docs = w + "/docs", pub = docs + "/public";
            List<String> names = List.of(".git", ".agents", ".codex", ".aws");
            mkdirs(pub);
            List<PolicyEntry> es = new ArrayList<>(List.of(e(w, WRITE), e(docs, READ), e(pub, WRITE)));
            for (String n : names) es.add(e(pub + "/" + n, READ));
            List<String> a = fs(restricted(es.toArray(new PolicyEntry[0])), d[0]);
            int ro = indexOf(a, "--ro-bind", docs, docs), rw = indexOf(a, "--bind", pub, pub);
            assertTrue(ro >= 0 && ro < rw, s(a));
            for (String n : names) {
                String m = pub + "/" + n;
                assertEquals(1, count(a, "--tmpfs", m), s(a));
                assertTrue(rw < indexOf(a, "--tmpfs", m), s(a));
            }
        });
    }

    @Test
    void splitPolicyReenablesWritableSubpathsAfterUnreadableParent() throws Exception {
        withTempDirs(1, d -> {
            String blocked = join(d[0], "blocked"), allowed = blocked + "/allowed";
            mkdirs(allowed);
            List<String> a = fs(restricted(e(ROOT, READ), e(blocked, DENY), e(allowed, WRITE)), d[0]);
            int m = indexOf(a, "--perms", "111", "--tmpfs", blocked), dir = indexOf(a, "--dir", allowed);
            int remount = indexOf(a, "--remount-ro", blocked), bind = indexOf(a, "--bind", allowed, allowed);
            assertTrue(m >= 0 && m < dir && dir < remount && remount < bind, s(a));
        });
    }

    @Test
    void splitPolicyReenablesWritableFilesAfterUnreadableParent() throws Exception {
        withTempDirs(1, d -> {
            String blocked = join(d[0], "blocked"), allowedDir = blocked + "/allowed", allowedFile = allowedDir + "/note.txt";
            mkdirs(allowedDir);
            write(allowedFile, "ok");
            List<String> a = fs(restricted(e(ROOT, READ), e(blocked, DENY), e(allowedFile, WRITE)), d[0]);
            int m = indexOf(a, "--perms", "111", "--tmpfs", blocked), bind = indexOf(a, "--bind", allowedFile, allowedFile);
            assertTrue(indexOf(a, "--dir", allowedDir) >= 0, s(a));
            assertTrue(indexOf(a, "--dir", allowedFile) < 0, s(a));
            assertTrue(m >= 0 && m < bind, s(a));
        });
    }

    @Test
    void splitPolicyReenablesNestedWritableRootsAfterUnreadableParent() throws Exception {
        withTempDirs(1, d -> {
            String w = join(d[0], "workspace"), blocked = w + "/blocked", allowed = blocked + "/allowed";
            mkdirs(allowed);
            List<String> a = fs(restricted(e(w, WRITE), e(blocked, DENY), e(allowed, WRITE)), d[0]);
            int m = indexOf(a, "--perms", "111", "--tmpfs", blocked), dir = indexOf(a, "--dir", allowed), bind = indexOf(a, "--bind", allowed, allowed);
            assertTrue(m >= 0 && m < dir && dir < bind, s(a));
        });
    }

    @Test
    void splitPolicyMasksRootReadDirectoryCarveouts() throws Exception {
        withTempDirs(1, d -> {
            String blocked = join(d[0], "blocked");
            mkdirs(blocked);
            List<String> a = fs(restricted(e(ROOT, READ), e(blocked, DENY)), d[0]);
            assertTrue(indexOf(a, "--ro-bind", "/", "/") >= 0);
            assertTrue(indexOf(a, "--perms", "000", "--tmpfs", blocked) >= 0);
            assertTrue(indexOf(a, "--remount-ro", blocked) >= 0);
        });
    }

    @Test
    void splitPolicyMasksRootReadFileCarveouts() throws Exception {
        withTempDirs(1, d -> {
            String f1 = join(d[0], "blocked.txt"), f2 = join(d[0], "second-blocked.txt");
            write(f1, "secret");
            write(f2, "dummy secret");
            List<String> a = fs(restricted(e(ROOT, READ), e(f1, DENY), e(f2, DENY)), d[0]);
            assertTrue(masked(a, f1), s(a));
            assertTrue(masked(a, f2), s(a));
        });
    }

    @Test
    void fileMasksWithoutAMaskFileFailClosed() throws Exception {
        withTempDirs(1, d -> {
            String f = join(d[0], "blocked.txt");
            write(f, "secret");
            assertEquals("sandbox/bwrap-cannot-mask",
                    code(thrown(() -> BwrapArgs.filesystemArgs(restricted(e(ROOT, READ), e(f, DENY)), ctx(d[0]), BwrapArgs.Options.DEFAULT))));
        });
    }

    @Test
    void unreadableGlobsExpandExistingMatches() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String rootEnv = join(temp, ".env"), nested = join(temp, "app", ".env");
            mkdirs(join(temp, "app"));
            write(rootEnv, "secret");
            write(nested, "secret");
            write(join(temp, "app", "notes.txt"), "notes");
            List<String> a = fs(restricted(e(ROOT, READ), denyGlob(temp + "/**/*.env")), temp);
            assertTrue(masked(a, rootEnv), s(a));
            assertTrue(masked(a, nested), s(a));
            assertFalse(a.contains(join(temp, "app", "notes.txt")));
        });
    }

    @Test
    void unreadableGlobsAddCanonicalTargetsForSymlinkMatches() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String realRoot = join(temp, "real"), linkRoot = join(temp, "link"), secret = realRoot + "/secret.env";
            mkdirs(realRoot);
            write(secret, "secret");
            symlink(linkRoot, realRoot);
            List<String> a = fs(restricted(e(ROOT, READ), denyGlob(linkRoot + "/**/*.env")), temp);
            assertTrue(masked(a, LinuxFs.realPath(secret)), s(a));
        });
    }

    @Test
    void rootPrefixUnreadableGlobsFailClosedOnLinux() {
        Throwable ex = thrown(() -> BwrapArgs.build(List.of("/bin/true"), restricted(e(ROOT, READ), denyGlob("/**/*.env")), ctx("/tmp"), "/tmp", opts()));
        assertEquals("sandbox/bwrap-glob-too-broad", code(ex));
        assertEquals("unreadable glob `/**/*.env` cannot be safely expanded; use a pattern with a non-root directory prefix",
                english(() -> Message.of("sandbox/bwrap-glob-too-broad", "/**/*.env").text()));
    }

    @Test
    void procMountPreflightDoesNotBindTheFullFilesystem() {
        List<String> argv = BwrapSandbox.preflightArguments(true);
        assertTrue(indexOf(argv, "--tmpfs", "/") >= 0);
        assertTrue(indexOf(argv, "--proc", "/proc") >= 0);
        assertTrue(indexOf(argv, "--ro-bind", "/", "/") < 0);
    }

    @Test
    void missingProtectedNamesAreMountedAsReadOnlyEmptyDirectories() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            SandboxPolicy pol = SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP);
            BwrapArgs.Built built = BwrapArgs.build(List.of("/bin/true"), pol, ctx(temp), temp, opts());
            for (String n : SandboxPolicy.DEFAULT_PROTECTED_NAMES) {
                String pp = join(temp, n);
                assertTrue(indexOf(built.args(), "--perms", "555", "--tmpfs", pp, "--remount-ro", pp) >= 0, s(built.args()));
            }
            List<String> expected = new ArrayList<>(SandboxPolicy.DEFAULT_PROTECTED_NAMES.stream().map(n -> join(temp, n)).toList());
            Collections.sort(expected);
            List<String> actual = new ArrayList<>(built.syntheticTargets());
            Collections.sort(actual);
            assertEquals(expected, actual);
            assertTrue(BwrapArgs.build(List.of("/bin/true"), pol, ctx(temp), temp, opts().withProtectMissingMetadataNames(false)).syntheticTargets().isEmpty());
        });
    }
}
