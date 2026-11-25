package io.ailink.agentforge.sandbox.policy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.policy.AccessMode.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/protocol/src/permissions.rs 的 deny-read 测试。 */
class DenyReadTest {
    private static SandboxPolicy withGlob(String pattern) {
        return restricted(e(ROOT, READ), denyGlob(pattern));
    }

    private static boolean denied(SandboxPolicy pol, PathContext c, String path) {
        return pol.matcher(c).readDenied(path);
    }

    @Test
    void exactPathAndDescendantsAreDenied() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String denied = join(temp, "denied"), nested = denied + "/nested.txt";
            mkdirs(denied);
            write(nested, "secret");
            SandboxPolicy pol = restricted(e(denied, DENY));
            assertTrue(denied(pol, ctx(temp), denied));
            assertTrue(denied(pol, ctx(temp), nested));
            assertFalse(denied(pol, ctx(temp), join(temp, "other.txt")));
        });
    }

    @Test
    void literalPatternsAndGlobsAreDenied() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            String literal = join(temp, "private"), other = join(temp, "notes.txt");
            mkdirs(literal);
            write(other, "notes");
            SandboxPolicy pol = restricted(e(literal, DENY), denyGlob(temp + "/**/*.txt"));
            assertTrue(denied(pol, ctx(temp), literal));
            assertTrue(denied(pol, ctx(temp), other));
        });
    }

    @Test
    void globPatternsDenyMatchingPaths() throws Exception {
        withTempDirs(1, d -> assertTrue(denied(withGlob(d[0] + "/private/secret?.txt"), ctx(d[0]), join(d[0], "private", "secret1.txt"))));
    }

    @Test
    void globPatternsDoNotCrossPathSeparators() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            SandboxPolicy pol = withGlob(temp + "/*/file[0-9]?.txt");
            assertTrue(denied(pol, ctx(temp), join(temp, "app", "file42.txt")));
            assertFalse(denied(pol, ctx(temp), join(temp, "app", "nested", "file42.txt")));
            assertFalse(denied(pol, ctx(temp), join(temp, "app", "file4.txt")));
            assertFalse(denied(pol, ctx(temp), join(temp, "app", "fileab.txt")));
        });
    }

    @Test
    void globstarPatternsDenyRootAndNestedMatches() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            SandboxPolicy pol = withGlob(temp + "/**/*.env");
            assertTrue(denied(pol, ctx(temp), join(temp, ".env")));
            assertTrue(denied(pol, ctx(temp), join(temp, "app", ".env")));
            assertFalse(denied(pol, ctx(temp), join(temp, "app", "notes.txt")));
        });
    }

    @Test
    void unclosedCharacterClassesMatchLiteralBrackets() throws Exception {
        withTempDirs(1, d -> {
            String temp = d[0];
            SandboxPolicy pol = withGlob(temp + "/[");
            assertTrue(denied(pol, ctx(temp), join(temp, "[")));
            assertFalse(denied(pol, ctx(temp), join(temp, "notes.txt")));
        });
    }

    @Test
    void alternationAndNegatedClassesWork() {
        Glob g = Glob.compile("/w/{a,b}/[!x]*.txt", false);
        assertTrue(g.matches("/w/a/y1.txt"));
        assertTrue(g.matches("/w/b/z.txt"));
        assertFalse(g.matches("/w/c/z.txt"));
        assertFalse(g.matches("/w/a/x1.txt"));
    }

    @Test
    void relativeAndHomeGlobsResolveAgainstCwdAndHome() throws Exception {
        withTempDirs(2, d -> {
            String cwd = d[0], home = d[1];
            SandboxPolicy pol = restricted(e(ROOT, READ), denyGlob("**/*.pem"), denyGlob("~/.ssh/**"));
            PathContext c = new PathContext(cwd, null, List.of(), home);
            assertTrue(denied(pol, c, join(cwd, "certs", "a.pem")));
            assertTrue(denied(pol, c, join(home, ".ssh", "id_ed25519")));
            assertFalse(denied(pol, c, join(cwd, "a.txt")));
            List<String> expected = new ArrayList<>(List.of(cwd + SEP + "**/*.pem", home + SEP + ".ssh/**"));
            Collections.sort(expected);
            assertEquals(expected, pol.matcher(c).unreadableGlobs());
            // 没有家目录 ⇒ 这种 glob 失败即拒（Codex InvalidDenyReadGlobBehavior::FailClosed）
            assertTrue(denied(pol, ctx(cwd), join(cwd, "a.txt")));
        });
    }

    @Test
    void denyEntriesMakeUnsandboxedExecutionDisallowed() {
        assertTrue(SandboxPolicy.workspaceWrite().unsandboxedExecutionAllowed());
        assertFalse(restricted(e(ROOT, READ), denyGlob("**/*.env")).unsandboxedExecutionAllowed());
        // 不受限的策略没有 deny 语义
        assertTrue(SandboxPolicy.dangerFullAccess().withEntries(List.of(denyGlob("**/*.env"))).unsandboxedExecutionAllowed());
    }
}
