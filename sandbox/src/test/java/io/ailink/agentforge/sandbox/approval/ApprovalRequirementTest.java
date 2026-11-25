package io.ailink.agentforge.sandbox.approval;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.orchestrator.FirstAttemptOverride;
import io.ailink.agentforge.sandbox.orchestrator.Permissions;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/core/src/tools/sandboxing_tests.rs。 */
class ApprovalRequirementTest {
    @Test
    void externalSandboxSkipsExecApprovalOnRequest() {
        assertEquals(ApprovalRequirement.skip(false), ApprovalPolicy.ON_REQUEST.defaultRequirement(SandboxPolicy.externalSandbox(false)));
    }

    @Test
    void restrictedSandboxRequiresExecApprovalOnRequest() {
        assertEquals(ApprovalRequirement.needsApproval(), ApprovalPolicy.ON_REQUEST.defaultRequirement(SandboxPolicy.readOnly()));
    }

    @Test
    void defaultRequirementRejectsSandboxPromptWhenGranularDisablesIt() {
        ApprovalRequirement r = ApprovalPolicy.granular(false).defaultRequirement(SandboxPolicy.readOnly());
        assertInstanceOf(ApprovalRequirement.Forbidden.class, r);
        assertEquals("sandbox/approval-disallowed", ((ApprovalRequirement.Forbidden) r).reason().code());
        assertEquals("approval policy disallowed sandbox approval prompt", english(() -> Message.of("sandbox/approval-disallowed").text()));
    }

    @Test
    void defaultRequirementKeepsPromptWhenGranularAllowsSandboxApproval() {
        assertEquals(ApprovalRequirement.needsApproval(), ApprovalPolicy.granular(true).defaultRequirement(SandboxPolicy.readOnly()));
    }

    @Test
    void neverAndUnlessTrustedDefaults() {
        assertEquals(ApprovalRequirement.skip(), ApprovalPolicy.NEVER.defaultRequirement(SandboxPolicy.readOnly()));
        assertEquals(ApprovalRequirement.needsApproval(), ApprovalPolicy.UNLESS_TRUSTED.defaultRequirement(SandboxPolicy.dangerFullAccess()));
    }

    @Test
    void execpolicySkipWithBypassBypassesSandboxFirstAttempt() {
        assertEquals(FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT,
                SandboxOrchestrator.firstAttemptOverride(Permissions.USE_DEFAULT, ApprovalRequirement.skip(true), SandboxPolicy.readOnly(), false));
    }

    @Test
    void explicitEscalationBypassesSandboxOnFirstAttempt() {
        assertEquals(FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT,
                SandboxOrchestrator.firstAttemptOverride(Permissions.REQUIRE_ESCALATED, ApprovalRequirement.skip(), SandboxPolicy.readOnly(), true));
    }

    @Test
    void denyReadPreservesTheSandboxForExplicitEscalationAndBlocksPolicyBypass() {
        SandboxPolicy pol = restricted(denyGlob("**/*.env"));
        assertEquals(FirstAttemptOverride.ESCALATED_SANDBOX_WITH_RESTRICTIONS,
                SandboxOrchestrator.firstAttemptOverride(Permissions.REQUIRE_ESCALATED, ApprovalRequirement.skip(), pol, true));
        assertFalse(pol.unsandboxedExecutionAllowed());
        assertEquals(FirstAttemptOverride.NO_OVERRIDE,
                SandboxOrchestrator.firstAttemptOverride(Permissions.USE_DEFAULT, ApprovalRequirement.skip(true), pol, true));
        // 没批准的显式升级 ⇒ 不宽写
        assertEquals(FirstAttemptOverride.NO_OVERRIDE,
                SandboxOrchestrator.firstAttemptOverride(Permissions.REQUIRE_ESCALATED, ApprovalRequirement.skip(), pol, false));
    }

    @Test
    void retryApprovalFlagsFollowCodex() {
        assertTrue(ApprovalPolicy.UNLESS_TRUSTED.wantsNoSandboxApproval());
        assertFalse(ApprovalPolicy.ON_REQUEST.wantsNoSandboxApproval());
        assertFalse(ApprovalPolicy.NEVER.wantsNoSandboxApproval());
        assertTrue(ApprovalPolicy.granular(true).wantsNoSandboxApproval());
        assertFalse(ApprovalPolicy.granular(false).wantsNoSandboxApproval());
        assertTrue(ApprovalPolicy.NEVER.shouldBypassApproval(false));
        assertTrue(ApprovalPolicy.UNLESS_TRUSTED.shouldBypassApproval(true));
        assertFalse(ApprovalPolicy.UNLESS_TRUSTED.shouldBypassApproval(false));
        // on-failure（旧版 Codex）：不事先问，沙盒拒了再问
        assertTrue(ApprovalPolicy.ON_FAILURE.wantsNoSandboxApproval());
        assertEquals(ApprovalRequirement.skip(), ApprovalPolicy.ON_FAILURE.defaultRequirement(SandboxPolicy.readOnly()));
    }

    @Test
    void wireNamesRoundTrip() {
        for (String w : java.util.List.of("untrusted", "on-failure", "on-request", "never", "granular", "granular:sandbox", "granular:rules", "granular:"))
            assertEquals(w, ApprovalPolicy.parse(w).wire());
        assertEquals(ApprovalPolicy.UNLESS_TRUSTED, ApprovalPolicy.parse("unless-trusted"));
        assertEquals(ApprovalPolicy.granular(false, true), ApprovalPolicy.parse("granular:rules"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalPolicy.parse("sometimes"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalPolicy.parse("never:sandbox"));
        assertNull(ApprovalPolicy.ON_REQUEST.promptRejection(true));
        assertEquals("sandbox/execpolicy-prompt-conflict", ApprovalPolicy.NEVER.promptRejection(false).code());
        assertEquals("sandbox/execpolicy-rules-disallowed", ApprovalPolicy.granular(true, false).promptRejection(true).code());
    }
}
