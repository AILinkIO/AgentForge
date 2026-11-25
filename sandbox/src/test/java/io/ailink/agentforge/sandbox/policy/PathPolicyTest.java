package io.ailink.agentforge.sandbox.policy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.policy.SandboxPathPolicy.Access.READ;
import static io.ailink.agentforge.sandbox.policy.SandboxPathPolicy.Access.WRITE;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.ErrorKind;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 沙盒策略当进程内的文件守卫用。 */
class PathPolicyTest {
    private static String code(PathDecision d) {
        return d.reason() == null ? null : d.reason().code();
    }

    @Test
    void writesAreAllowedOnlyUnderWritableRootsMinusProtectedNames() throws Exception {
        withTempDirs(2, d -> {
            String cwd = d[0], outside = d[1];
            mkdirs(join(cwd, ".git"));
            SandboxPathPolicy guard = new SandboxPathPolicy(SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP), ctx(cwd));
            assertTrue(guard.check(join(cwd, "src", "a.cs"), WRITE).allowed());
            assertTrue(guard.check(join(outside, "x"), READ).allowed());
            assertEquals("sandbox/write-denied", code(guard.check(join(outside, "x"), WRITE)));
            assertEquals("sandbox/protected-path", code(guard.check(join(cwd, ".git", "hooks", "pre-commit"), WRITE)));
            // 还不存在的受保护名也不许建
            assertEquals("sandbox/protected-path", code(guard.check(join(cwd, ".agents", "skills.md"), WRITE)));
            assertTrue(guard.check(join(cwd, ".git", "config"), READ).allowed());
        });
    }

    @Test
    void denyEntriesBlockReadsAndWrites() throws Exception {
        withTempDirs(1, d -> {
            String cwd = d[0];
            SandboxPolicy pol = SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP)
                    .plusEntries(denyGlob("**/*.env"), e(join(cwd, "secrets"), AccessMode.DENY));
            SandboxPathPolicy guard = new SandboxPathPolicy(pol, ctx(cwd));
            assertEquals("sandbox/read-denied", code(guard.check(join(cwd, "app", ".env"), READ)));
            assertEquals("sandbox/read-denied", code(guard.check(join(cwd, "app", ".env"), WRITE)));
            assertEquals("sandbox/read-denied", code(guard.check(join(cwd, "secrets", "k"), READ)));
            assertTrue(guard.check(join(cwd, "app", "main.cs"), WRITE).allowed());
        });
    }

    @Test
    void readsOutsideReadableRootsAreDeniedForScopedReadPolicies() throws Exception {
        withTempDirs(2, d -> {
            String cwd = d[0], other = d[1];
            SandboxPathPolicy guard = new SandboxPathPolicy(restricted(e(PROJECT_ROOTS, AccessMode.WRITE)), ctx(cwd));
            assertTrue(guard.check(join(cwd, "a"), READ).allowed());
            assertEquals("sandbox/read-outside-roots", code(guard.check(join(other, "a"), READ)));
            String text = english(() -> guard.check(join(other, "a"), READ).reason().text());
            assertTrue(text.startsWith("This path is outside the sandbox policy's readable roots"), text);
        });
    }

    @Test
    void unrestrictedPolicyAllowsEverything() {
        String root = LexicalPaths.WINDOWS ? "C:\\" : "/";
        assertTrue(new SandboxPathPolicy(SandboxPolicy.dangerFullAccess(), ctx(root))
                .check(LexicalPaths.WINDOWS ? "C:\\etc\\passwd" : "/etc/passwd", WRITE).allowed());
    }

    @Test
    void contextRequiresAbsoluteCwd() {
        Throwable ex = thrown(() -> PathContext.of("relative"));
        assertEquals("sandbox/path-not-absolute", io.ailink.agentforge.sandbox.TestSupport.code(ex));
        assertEquals(ErrorKind.ARGUMENT, kind(ex));
    }
}
