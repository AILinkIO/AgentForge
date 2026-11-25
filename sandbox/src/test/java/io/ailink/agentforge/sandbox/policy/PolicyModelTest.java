package io.ailink.agentforge.sandbox.policy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.policy.AccessMode.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.orchestrator.SandboxPreference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：protocol.rs / permissions.rs / policy_transforms_tests.rs / manager_tests.rs 的测试。 */
class PolicyModelTest {

    private static List<String> sorted(List<String> xs) {
        List<String> l = new ArrayList<>(xs);
        Collections.sort(l);
        return l;
    }

    @Test
    void externalSandboxReportsFullAccessFlags() {
        SandboxPolicy r = SandboxPolicy.externalSandbox(false), en = SandboxPolicy.externalSandbox(true);
        assertTrue(r.fullDiskWriteAccess());
        assertFalse(r.fullNetworkAccess());
        assertTrue(en.fullDiskWriteAccess());
        assertTrue(en.fullNetworkAccess());
    }

    @Test
    void readOnlyReportsNetworkAccessFlags() {
        assertFalse(SandboxPolicy.readOnly().fullNetworkAccess());
        assertTrue(SandboxPolicy.readOnly(true).fullNetworkAccess());
    }

    @Test
    void restrictedPolicyReportsFullAccessFromRootEntries() {
        SandboxPolicy ro = restricted(e(ROOT, READ)), w = restricted(e(ROOT, WRITE));
        assertTrue(ro.fullDiskReadAccess());
        assertFalse(ro.fullDiskWriteAccess());
        assertFalse(ro.includePlatformDefaults());
        assertTrue(w.fullDiskReadAccess());
        assertTrue(w.fullDiskWriteAccess());
    }

    @Test
    void restrictedPolicyTreatsRootWithCarveoutsAsScopedAccess() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            String fsRoot = LexicalPaths.fileSystemRoot(cwd);
            String blocked = join(cwd, "blocked");
            SandboxPolicy pol = restricted(e(ROOT, WRITE), e(blocked, DENY));
            PolicyMatcher m = pol.matcher(ctx(cwd));
            List<WritableRoot> roots = m.writableRoots();
            assertFalse(pol.fullDiskReadAccess());
            assertFalse(pol.fullDiskWriteAccess());
            assertEquals(List.of(fsRoot), m.readableRoots());
            assertEquals(List.of(blocked), m.unreadableRoots());
            assertEquals(1, roots.size());
            assertEquals(fsRoot, roots.getFirst().root());
            assertTrue(roots.getFirst().readOnlySubpaths().contains(blocked));
        });
    }

    @Test
    void restrictedPolicyDerivesEffectivePaths() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            mkdirs(join(cwd, ".agents"), join(cwd, ".codex"));
            String secret = join(cwd, "secret");
            SandboxPolicy pol = restricted(e(MINIMAL, READ), e(PROJECT_ROOTS, WRITE), e(secret, DENY));
            PolicyMatcher m = pol.matcher(ctx(cwd));
            List<WritableRoot> roots = m.writableRoots();
            assertFalse(pol.fullDiskReadAccess());
            assertFalse(pol.fullDiskWriteAccess());
            assertTrue(pol.includePlatformDefaults());
            assertEquals(List.of(cwd), m.readableRoots());
            assertEquals(List.of(secret), m.unreadableRoots());
            assertEquals(1, roots.size());
            assertEquals(cwd, roots.getFirst().root());
            for (String x : List.of(secret, join(cwd, ".agents"), join(cwd, ".codex")))
                assertTrue(roots.getFirst().readOnlySubpaths().contains(x), x);
        });
    }

    @Test
    void restrictedPolicyTreatsReadEntriesAsReadOnlySubpaths() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            String docs = join(cwd, "docs"), pub = join(cwd, "docs", "public");
            SandboxPolicy pol = restricted(e(PROJECT_ROOTS, WRITE), e(docs, READ), e(pub, WRITE));
            var roots = sortedRoots(pol.matcher(ctx(cwd)).writableRoots());
            assertFalse(pol.fullDiskWriteAccess());
            assertEquals(List.of(cwd, pub), roots.stream().map(r -> r.get(0)).toList());
            assertEquals(List.of(join(cwd, ".codex"), docs), roots.get(0).get(1));
            assertEquals(List.of(), roots.get(1).get(1));
        });
    }

    @Test
    void writableRootsProactivelyProtectMissingDotCodex() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            List<WritableRoot> roots = restricted(e(PROJECT_ROOTS, WRITE)).matcher(ctx(cwd)).writableRoots();
            assertEquals(1, roots.size());
            assertEquals(cwd, roots.getFirst().root());
            assertTrue(roots.getFirst().readOnlySubpaths().contains(join(cwd, ".codex")));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {".codex", ".aws"})
    void writableRootsSkipDefaultMetadataWhenExplicitUserRuleExists(String name) throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            String explicit = join(cwd, name);
            mkdirs(explicit);
            SandboxPolicy pol = restricted(e(PROJECT_ROOTS, WRITE), e(explicit, WRITE));
            PolicyMatcher m = pol.matcher(ctx(cwd));
            WritableRoot ws = m.writableRoots().stream().filter(r -> r.root().equals(cwd)).findFirst().orElseThrow();
            assertFalse(ws.protectedMetadataNames().contains(name));
            assertFalse(ws.readOnlySubpaths().contains(explicit));
            assertTrue(m.canWrite(explicit + "/config.toml"));
        });
    }

    @Test
    void filesystemPolicyBlocksProtectedMetadataPathWritesByDefault() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            List<String> ps = List.of(join(cwd, ".git", "config"), join(cwd, ".agents", "config"), join(cwd, ".codex", "config.toml"),
                    join(cwd, ".aws", "config"));
            PolicyMatcher m = restricted(e(cwd, WRITE)).matcher(ctx(cwd));
            List<WritableRoot> roots = m.writableRoots();
            for (String x : ps) assertFalse(m.canWrite(x), x);
            assertEquals(1, roots.size());
            assertEquals(List.of(".git", ".agents", ".codex", ".aws"), roots.getFirst().protectedMetadataNames());
            for (String x : ps) assertFalse(roots.getFirst().isWritable(x), x);
        });
    }

    @Test
    void resolveAccessUsesMostSpecificEntry() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            String docs = join(cwd, "docs"), priv = join(cwd, "docs", "private"), pub = join(cwd, "docs", "private", "public");
            PolicyMatcher m = restricted(e(PROJECT_ROOTS, WRITE), e(docs, READ), e(priv, DENY), e(pub, WRITE)).matcher(ctx(cwd));
            assertEquals(WRITE, m.resolveAccess(cwd));
            assertEquals(READ, m.resolveAccess(docs));
            assertEquals(DENY, m.resolveAccess(priv));
            assertEquals(WRITE, m.resolveAccess(pub));
        });
    }

    @Test
    void rootWriteWithReadOnlyChildIsNotFullDiskWrite() throws Exception {
        withTempDirs(1, d -> {
            String docs = join(d[0], "docs");
            SandboxPolicy pol = restricted(e(ROOT, WRITE), e(docs, READ));
            assertFalse(pol.fullDiskWriteAccess());
            assertEquals(READ, pol.matcher(ctx(d[0])).resolveAccess(docs));
        });
    }

    @Test
    void rootDenyDoesNotMaterializeAsUnreadableRoot() throws Exception {
        withTempDirs(1, d -> {
            String docs = join(d[0], "docs");
            PolicyMatcher m = restricted(e(ROOT, DENY), e(docs, READ)).matcher(ctx(d[0]));
            assertEquals(READ, m.resolveAccess(docs));
            assertEquals(List.of(docs), m.readableRoots());
            assertTrue(m.unreadableRoots().isEmpty());
        });
    }

    @Test
    void duplicateRootDenyPreventsFullDiskWriteAccess() throws Exception {
        withTempDirs(1, d -> {
            SandboxPolicy pol = restricted(e(ROOT, WRITE), e(ROOT, DENY));
            assertFalse(pol.fullDiskWriteAccess());
            assertEquals(DENY, pol.matcher(ctx(d[0])).resolveAccess("/"));
        });
    }

    @Test
    void sameSpecificityWriteOverrideKeepsFullDiskWriteAccess() throws Exception {
        withTempDirs(1, d -> {
            String docs = join(d[0], "docs");
            SandboxPolicy pol = restricted(e(ROOT, WRITE), e(docs, READ), e(docs, WRITE));
            assertTrue(pol.fullDiskWriteAccess());
            assertEquals(WRITE, pol.matcher(ctx(d[0])).resolveAccess(docs));
        });
    }

    @Test
    void fileSystemAccessModeOrdersByConflictPrecedence() {
        assertTrue(WRITE.rank() > READ.rank());
        assertTrue(DENY.rank() > WRITE.rank());
    }

    @Test
    void approvedCommandPolicyKeepsDenyEntriesWithRootWrite() {
        PolicyEntry deny = denyGlob("/tmp/project/**/*.env");
        SandboxPolicy approved = restricted(deny).forApprovedCommand();
        SandboxPolicy full = SandboxPolicy.dangerFullAccess();
        assertEquals(List.of(e(ROOT, WRITE), deny), approved.entries());
        assertFalse(approved.unsandboxedExecutionAllowed());
        assertSame(full, full.forApprovedCommand());
    }

    @Test
    void workspaceWriteRootsIncludeCwdSlashTmpTmpdirAndConfiguredRoots() throws Exception {
        withTempDirs(3, d -> {
            String cwd = d[0], extra = d[1], tmpdir = d[2];
            PathContext c = new PathContext(cwd, null, List.of(tmpdir), null);
            List<WritableRoot> all = withCodexNames(SandboxPolicy.workspaceWrite(List.of(extra), SandboxPolicy.WorkspaceWriteOptions.DEFAULT))
                    .matcher(c).writableRoots();
            List<String> expected = LexicalPaths.WINDOWS ? sorted(List.of(cwd, tmpdir, extra)) : sorted(List.of(cwd, "/tmp", tmpdir, extra));
            assertEquals(expected, sorted(all.stream().map(WritableRoot::root).toList()));
            WritableRoot cwdRoot = all.stream().filter(r -> r.root().equals(cwd)).findFirst().orElseThrow();
            WritableRoot extraRoot = all.stream().filter(r -> r.root().equals(extra)).findFirst().orElseThrow();
            assertTrue(cwdRoot.readOnlySubpaths().contains(join(cwd, ".codex")));
            assertFalse(extraRoot.readOnlySubpaths().contains(join(extra, ".codex")));
            List<WritableRoot> excluded = SandboxPolicy.workspaceWrite(List.of(extra), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).matcher(c).writableRoots();
            assertEquals(sorted(List.of(cwd, extra)), sorted(excluded.stream().map(WritableRoot::root).toList()));
            assertEquals(ErrorKind.ARGUMENT, kind(thrown(() -> SandboxPolicy.workspaceWrite(List.of("relative/dir"), SandboxPolicy.WorkspaceWriteOptions.DEFAULT))));
        });
    }

    @Test
    void gitdirPointerFileProtectsTheRealGitdir() throws Exception {
        withTempDirs(2, d -> {
            String cwd = d[0], gitdir = d[1];
            write(join(cwd, ".git"), "gitdir: " + gitdir + "\n");
            List<WritableRoot> roots = SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).matcher(ctx(cwd)).writableRoots();
            assertEquals(1, roots.size());
            assertTrue(roots.getFirst().readOnlySubpaths().contains(join(cwd, ".git")));
            assertTrue(roots.getFirst().readOnlySubpaths().contains(gitdir));
        });
    }

    // ── policy_transforms_tests.rs / manager_tests.rs ─────────────────────

    @Test
    void fullAccessRestrictedPolicySkipsPlatformSandboxWhenNetworkIsEnabled() {
        assertFalse(SandboxPolicy.restricted(List.of(e(ROOT, WRITE)), NetworkMode.ENABLED).requiresPlatformSandbox());
    }

    @Test
    void rootWritePolicyWithCarveoutsStillUsesPlatformSandbox() {
        String blocked = LexicalPaths.fullPath("blocked");
        assertTrue(SandboxPolicy.restricted(List.of(e(ROOT, WRITE), e(blocked, DENY)), NetworkMode.ENABLED).requiresPlatformSandbox());
    }

    @Test
    void fullAccessRestrictedPolicyStillUsesPlatformSandboxForRestrictedNetwork() {
        assertTrue(SandboxPolicy.restricted(List.of(e(ROOT, WRITE)), NetworkMode.RESTRICTED).requiresPlatformSandbox());
    }

    @Test
    void dangerFullAccessDefaultsToNoSandboxWithoutNetworkRequirements() {
        assertFalse(SandboxOrchestrator.shouldSandbox(SandboxPolicy.dangerFullAccess(), SandboxPreference.AUTO, false));
    }

    @Test
    void dangerFullAccessUsesPlatformSandboxWithNetworkRequirements() {
        assertTrue(SandboxOrchestrator.shouldSandbox(SandboxPolicy.dangerFullAccess(), SandboxPreference.AUTO, true));
    }

    @Test
    void restrictedFileSystemUsesPlatformSandboxWithoutManagedNetwork() {
        assertTrue(SandboxOrchestrator.shouldSandbox(SandboxPolicy.readOnly(true), SandboxPreference.AUTO, false));
    }

    @Test
    void proxiedNetworkCountsAsManagedNetworkRequirement() {
        assertTrue(SandboxPolicy.dangerFullAccess().withNetwork(NetworkMode.PROXIED).requiresPlatformSandbox());
    }

    @Test
    void defaultProtectedNamesAreHostNeutral() throws Exception {
        assertEquals(List.of(".git", ".agents", ".aws"), SandboxPolicy.DEFAULT_PROTECTED_NAMES);
        withTempDirs(1, d -> {
            List<WritableRoot> roots = SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).matcher(ctx(d[0])).writableRoots();
            assertEquals(1, roots.size());
            assertEquals(List.of(".git", ".agents", ".aws"), roots.getFirst().protectedMetadataNames());
            assertTrue(roots.getFirst().readOnlySubpaths().isEmpty());
        });
    }

    @Test
    void policiesAreValidData() {
        String extra = LexicalPaths.WINDOWS ? "C:\\opt\\x" : "/opt/x";
        for (SandboxPolicy pol : List.of(SandboxPolicy.dangerFullAccess(), SandboxPolicy.readOnly(),
                SandboxPolicy.workspaceWrite(List.of(extra), SandboxPolicy.WorkspaceWriteOptions.DEFAULT), restricted(denyGlob("**/*.env"))))
            assertSame(pol, pol.validate());
        assertEquals(ErrorKind.ARGUMENT, kind(thrown(() -> SandboxPolicy.DEFAULT.withProtectedNames(List.of("")).validate())));
        // 枚举 / 必填字段由类型保证
        assertThrows(NullPointerException.class, () -> new SandboxPolicy(null, List.of(), NetworkMode.RESTRICTED, List.of(), List.of()));
    }
}
