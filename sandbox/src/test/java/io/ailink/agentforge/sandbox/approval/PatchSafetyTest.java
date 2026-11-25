package io.ailink.agentforge.sandbox.approval;

import static io.ailink.agentforge.sandbox.TestSupport.english;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.TestSupport;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.PolicyPath;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.SpecialKind;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/core/src/safety_tests.rs。 */
class PatchSafetyTest {
    private static final SandboxPolicy.WorkspaceWriteOptions NO_TEMP = SandboxPolicy.WorkspaceWriteOptions.NO_TEMP;

    @Test
    void writableRootsConstraint() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            String cwd = d[0];
            String parent = Path.of(cwd).getParent().toString();
            PathContext ctx = PathContext.of(cwd);
            SandboxPolicy workspaceOnly = SandboxPolicy.workspaceWrite(List.of(), NO_TEMP);
            assertTrue(PatchSafety.constrainedToWritablePaths(List.of(cwd + "/inner.txt"), workspaceOnly, ctx));
            assertFalse(PatchSafety.constrainedToWritablePaths(List.of(parent + "/outside.txt"), workspaceOnly, ctx));
            SandboxPolicy withParent = SandboxPolicy.workspaceWrite(List.of(parent), NO_TEMP);
            assertTrue(PatchSafety.constrainedToWritablePaths(List.of(parent + "/outside.txt"), withParent, ctx));
        });
    }

    @Test
    void externalSandboxAutoApprovesInOnRequest() throws Exception {
        TestSupport.withTempDirs(1, d -> assertEquals(SafetyCheck.AUTO_APPROVE,
                PatchSafety.assess(List.of(d[0] + "/inner.txt"), ApprovalPolicy.ON_REQUEST, SandboxPolicy.externalSandbox(true), PathContext.of(d[0]))));
    }

    @Test
    void granularFlagsForOutOfRootPatch() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            String outside = Path.of(d[0]).getParent() + "/outside.txt";
            SandboxPolicy pol = SandboxPolicy.workspaceWrite(List.of(), NO_TEMP);
            PathContext ctx = PathContext.of(d[0]);
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(List.of(outside), ApprovalPolicy.ON_REQUEST, pol, ctx));
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(List.of(outside), ApprovalPolicy.granular(true, true), pol, ctx));
            SafetyCheck r = english(() -> PatchSafety.assess(List.of(outside), ApprovalPolicy.granular(false, true), pol, ctx));
            assertEquals("writing outside of the project; rejected by user approval settings", ((SafetyCheck.Reject) r).reason().text());
        });
    }

    @Test
    void readOnlyPolicyRejectsPatchWithReadOnlyReason() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            List<String> inside = List.of(d[0] + "/inside.txt");
            PathContext ctx = PathContext.of(d[0]);
            assertFalse(PatchSafety.constrainedToWritablePaths(inside, SandboxPolicy.readOnly(), ctx));
            SafetyCheck r = english(() -> PatchSafety.assess(inside, ApprovalPolicy.NEVER, SandboxPolicy.readOnly(), ctx));
            assertEquals("writing is blocked by read-only sandbox; rejected by user approval settings", ((SafetyCheck.Reject) r).reason().text());
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(inside, ApprovalPolicy.ON_REQUEST, SandboxPolicy.readOnly(), ctx));
        });
    }

    @Test
    void explicitUnreadablePathsPreventAutoApproval() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            String blocked = d[0] + "/blocked.txt";
            SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of(PolicyPath.special(SpecialKind.ROOT), AccessMode.WRITE),
                    PolicyEntry.of(blocked, AccessMode.DENY)));
            PathContext ctx = PathContext.of(d[0]);
            assertFalse(PatchSafety.constrainedToWritablePaths(List.of(blocked), pol, ctx));
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(List.of(blocked), ApprovalPolicy.ON_REQUEST, pol, ctx));
        });
    }

    @Test
    void explicitReadOnlySubpathsPreventAutoApproval() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of(PolicyPath.special(SpecialKind.PROJECT_ROOTS), AccessMode.WRITE),
                    PolicyEntry.of(d[0] + "/docs", AccessMode.READ)));
            List<String> paths = List.of(d[0] + "/docs/blocked.txt");
            PathContext ctx = PathContext.of(d[0]);
            assertFalse(PatchSafety.constrainedToWritablePaths(paths, pol, ctx));
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(paths, ApprovalPolicy.ON_REQUEST, pol, ctx));
        });
    }

    @Test
    void missingHostMetadataConfigRequiresApproval() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            SandboxPolicy pol = SandboxPolicy.workspaceWrite(List.of(), NO_TEMP).withHostMetadataNames(List.of(".agentforge"));
            List<String> paths = List.of(d[0] + "/.agentforge/rules/x.rules");
            PathContext ctx = PathContext.of(d[0]);
            assertFalse(PatchSafety.constrainedToWritablePaths(paths, pol, ctx), "宿主元数据目录不存在也受保护");
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(paths, ApprovalPolicy.ON_REQUEST, pol, ctx));
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(List.of(d[0] + "/.git/config"), ApprovalPolicy.ON_REQUEST, pol, ctx));
        });
    }

    @Test
    void emptyPatchAndUntrusted() throws Exception {
        TestSupport.withTempDirs(1, d -> {
            PathContext ctx = PathContext.of(d[0]);
            assertInstanceOf(SafetyCheck.Reject.class, PatchSafety.assess(List.of(), ApprovalPolicy.ON_REQUEST, SandboxPolicy.workspaceWrite(), ctx));
            assertEquals(SafetyCheck.ASK_USER, PatchSafety.assess(List.of(d[0] + "/a"), ApprovalPolicy.UNLESS_TRUSTED, SandboxPolicy.dangerFullAccess(), ctx));
            assertEquals(SafetyCheck.AUTO_APPROVE, PatchSafety.assess(List.of("/etc/passwd"), ApprovalPolicy.NEVER, SandboxPolicy.dangerFullAccess(), ctx));
            assertEquals(SafetyCheck.AUTO_APPROVE, PatchSafety.assess(List.of(d[0] + "/a"), ApprovalPolicy.ON_FAILURE, SandboxPolicy.workspaceWrite(), ctx));
        });
    }
}
