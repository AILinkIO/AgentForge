package io.ailink.agentforge.sandbox.policy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.ErrorKind;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolicyJsonTest {
    private static final String EXTRA_ROOT = LexicalPaths.WINDOWS ? "C:\\opt\\extra" : "/opt/extra";
    private static final String SRV_SECRET = LexicalPaths.WINDOWS ? "C:\\srv\\secret" : "/srv/secret";
    private static final String EXTRA_ROOT_JSON = LexicalPaths.WINDOWS ? "C:\\\\opt\\\\extra" : "/opt/extra";

    private static SandboxPolicy make(String n) {
        return switch (n) {
            case "danger" -> SandboxPolicy.dangerFullAccess();
            case "read-only" -> SandboxPolicy.readOnly();
            case "read-only-net" -> SandboxPolicy.readOnly(true);
            case "external" -> SandboxPolicy.externalSandbox(true);
            case "workspace" -> SandboxPolicy.workspaceWrite(List.of(EXTRA_ROOT), SandboxPolicy.WorkspaceWriteOptions.DEFAULT);
            case "workspace-excluded" -> SandboxPolicy.workspaceWrite(List.of(), new SandboxPolicy.WorkspaceWriteOptions(true, true, true));
            default -> restricted(e(MINIMAL, AccessMode.READ), e(PolicyPath.special(SpecialKind.PROJECT_ROOTS, "docs"), AccessMode.WRITE),
                    e(SRV_SECRET, AccessMode.DENY), denyGlob("**/*.env")).withNetwork(NetworkMode.PROXIED);
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"danger", "read-only", "read-only-net", "external", "workspace", "workspace-excluded", "entries"})
    void policiesRoundTripThroughJson(String n) {
        SandboxPolicy pol = make(n);
        String json = PolicyJson.write(pol);
        SandboxPolicy back = PolicyJson.read(json);
        assertEquals(pol, back);
        assertEquals(json, PolicyJson.write(back));
    }

    @Test
    void jsonShapeIsSnakeCaseWithTypedPaths() {
        assertEquals("{\"file_system\":\"restricted\",\"entries\":[{\"path\":{\"type\":\"special\",\"kind\":\"root\"},\"access\":\"read\"},"
                        + "{\"path\":{\"type\":\"special\",\"kind\":\"project_roots\"},\"access\":\"write\"},{\"path\":{\"type\":\"path\",\"path\":\""
                        + EXTRA_ROOT_JSON + "\"},"
                        + "\"access\":\"write\"}],\"network\":\"restricted\",\"protected_names\":[\".git\",\".agents\",\".aws\"],\"host_metadata_names\":[]}",
                PolicyJson.write(SandboxPolicy.workspaceWrite(List.of(EXTRA_ROOT), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP)));
        SandboxPolicy parsed = PolicyJson.read(
                "{\"file_system\":\"restricted\",\"entries\":[{\"path\":{\"type\":\"glob_pattern\",\"pattern\":\"**/*.env\"},\"access\":\"deny\"}]}");
        assertEquals(List.of(denyGlob("**/*.env")), parsed.entries());
        assertEquals(NetworkMode.RESTRICTED, parsed.network());
        assertEquals(SandboxPolicy.DEFAULT_PROTECTED_NAMES, parsed.protectedNames());
    }

    @Test
    void escapingFollowsSystemTextJson() {
        SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of("/a\"b<c>&'+`é\n", AccessMode.READ)));
        String json = PolicyJson.write(pol);
        assertTrue(json.contains("/a\\u0022b\\u003Cc\\u003E\\u0026\\u0027\\u002B\\u0060\\u00E9\\n"), json);
        assertEquals(pol, PolicyJson.read(json));
    }

    @Test
    void enumNamesAreCaseInsensitiveAndAcceptOrdinals() {
        SandboxPolicy p = PolicyJson.read("{\"file_system\":\"UNRESTRICTED\",\"network\":1,\"unknown\":{\"x\":1}}");
        assertEquals(FileSystemMode.UNRESTRICTED, p.fileSystem());
        assertEquals(NetworkMode.ENABLED, p.network());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"file_system\":\"bogus\"}", "{\"entries\":[{\"path\":{\"type\":\"nope\"},\"access\":\"read\"}]}", "null"})
    void invalidJsonThrowsWithCode(String json) {
        Throwable ex = thrown(() -> PolicyJson.read(json));
        assertEquals("sandbox/invalid-policy-json", code(ex));
        assertEquals(ErrorKind.JSON, kind(ex));
    }
}
