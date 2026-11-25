package io.ailink.agentforge.sandbox.orchestrator;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static io.ailink.agentforge.sandbox.approval.ApprovalDecision.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.approval.EnvironmentMarkers;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 编排流程（Codex ToolOrchestrator::run）用假沙盒测：沙盒里一律被拒（「Operation not permitted」），沙盒外成功。 */
class OrchestratorTest {
    static final SandboxCapabilities FAKE_CAPS = SandboxCapabilities.available("fake");

    /** 记下尝试；沙盒里回拒判，沙盒外成功。 */
    static final class Recorder implements SandboxOrchestrator.AttemptRunner<ExecResult> {
        final List<Attempt> attempts = new ArrayList<>();

        @Override
        public ExecResult run(Attempt attempt) {
            attempts.add(attempt);
            return attempt.sandboxed() ? ExecResult.of(1, "", "touch: Operation not permitted", "") : ExecResult.of(0, "ok", "", "ok");
        }
    }

    static OrchestrationRequest req(SandboxPolicy pol) {
        return OrchestrationRequest.of(pol == null ? SandboxPolicy.workspaceWrite() : pol, PathContext.of(System.getProperty("java.io.tmpdir")))
                .withApprovalKey("shell\u0000touch /etc/x");
    }

    static OrchestrationRequest req() {
        return req(null);
    }

    static SandboxPolicy workspaceWithEnvDeny() {
        return SandboxPolicy.workspaceWrite().plusEntries(denyGlob("**/*.env"));
    }

    static SandboxOrchestrator orch(FakeSandbox sb, FakeApprover approver) {
        return new SandboxOrchestrator(new FakeBackend("local"), sb, approver);
    }

    @Test
    void deniedInSandboxThenRetryApprovalThenUnsandboxedSuccess() throws Exception {
        Recorder rec = new Recorder();
        FakeApprover approver = new FakeApprover(APPROVE);
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), approver)
                .run(req().withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED).withRequirement(ApprovalRequirement.skip()), rec);
        ApprovalRequest ask = approver.requests.getFirst();
        assertEquals(OutcomeStatus.ESCALATED, r.status());
        assertEquals(2, r.attempts());
        assertEquals("ok", r.output().stdout());
        assertEquals(List.of(true, false), rec.attempts.stream().map(Attempt::sandboxed).toList());
        assertTrue(rec.attempts.get(1).retry());
        assertEquals(1, approver.requests.size());
        assertEquals("sandbox/retry-without-sandbox", ask.retryReason().code());
        assertEquals(1, ask.denial().exitCode());
    }

    @Test
    void sessionApprovedKeyIsNotAskedAgain() throws Exception {
        Recorder rec = new Recorder();
        ApprovalCache.InMemory cache = ApprovalCache.inMemory();
        FakeApprover approver = new FakeApprover(APPROVE_FOR_SESSION);
        SandboxOrchestrator o = new SandboxOrchestrator(new FakeBackend("local"), new FakeSandbox(FAKE_CAPS), approver,
                OrchestratorOptions.DEFAULT.withApprovalCache(cache));
        OrchestrationRequest request = req().withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED);
        assertEquals(OutcomeStatus.ESCALATED, o.run(request, rec).status());
        assertEquals(1, approver.requests.size());
        assertTrue(cache.keys().contains(request.approvalKey()));
        assertEquals(OutcomeStatus.ESCALATED, o.run(request, rec).status());
        assertEquals(1, approver.requests.size());
    }

    @Test
    void toolThatDoesNotEscalateReturnsTheDeniedResult() throws Exception {
        Recorder rec = new Recorder();
        FakeApprover approver = new FakeApprover(APPROVE);
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), approver).run(req().withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED)
                .withRequirement(ApprovalRequirement.skip()).withEscalateOnFailure(false), rec);
        assertEquals(OutcomeStatus.DENIED, r.status());
        assertEquals("sandbox/denied", r.reason().code());
        assertEquals(1, r.output().exitCode());
        assertEquals(1, rec.attempts.size());
        assertTrue(approver.requests.isEmpty());
    }

    @Test
    void denyReadPolicyNeverRunsUnsandboxed() throws Exception {
        Recorder rec = new Recorder();
        FakeApprover approver = new FakeApprover(APPROVE, APPROVE);
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), approver)
                .run(req(workspaceWithEnvDeny()).withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED).withRequirement(ApprovalRequirement.skip()), rec);
        assertEquals(OutcomeStatus.DENIED, r.status());
        assertTrue(rec.attempts.stream().allMatch(Attempt::sandboxed));
        assertTrue(approver.requests.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = SandboxPreference.class, names = {"AUTO", "FORBID"})
    void denyReadPolicyWithoutAnAvailableSandboxIsRejected(SandboxPreference preference) throws Exception {
        Recorder rec = new Recorder();
        SandboxPolicy pol = restricted(e(ROOT, AccessMode.WRITE), denyGlob("**/*.env"));
        SandboxOrchestrator o = new SandboxOrchestrator(new FakeBackend("local"),
                new FakeSandbox(SandboxCapabilities.unavailable("fake", Message.of("sandbox/no-backend"))), new FakeApprover(),
                OrchestratorOptions.DEFAULT.withRunUnsandboxedWhenUnavailable(true));
        Outcome<ExecResult> r = o.run(req(pol).withApprovalPolicy(ApprovalPolicy.NEVER).withPreference(preference), rec);
        assertEquals(OutcomeStatus.REJECTED, r.status());
        assertEquals("sandbox/unavailable-deny-read", r.reason().code());
        assertTrue(rec.attempts.isEmpty());
    }

    @Test
    void explicitEscalationWithDenyReadStaysSandboxedWithRootWrite() throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        FakeSandbox sb = new FakeSandbox(FAKE_CAPS, new FakeBackend("fake-sandboxed"));
        Outcome<ExecResult> r = orch(sb, new FakeApprover(APPROVE)).run(req(workspaceWithEnvDeny()).withPermissions(Permissions.REQUIRE_ESCALATED),
                att -> {
                    attempts.add(att);
                    return ExecResult.of(0);
                });
        Attempt att = attempts.getFirst();
        assertEquals(OutcomeStatus.COMPLETED, r.status());
        assertEquals(1, attempts.size());
        assertTrue(att.sandboxed());
        assertEquals(FirstAttemptOverride.ESCALATED_SANDBOX_WITH_RESTRICTIONS, att.override());
        assertEquals(e(ROOT, AccessMode.WRITE), att.policy().entries().getFirst());
        assertEquals(1, sb.wrapped.size());
        assertTrue(sb.wrapped.getFirst().entries().contains(denyGlob("**/*.env")));
    }

    @Test
    void onRequestModeDoesNotRetryWithoutSandbox() throws Exception {
        Recorder rec = new Recorder();
        FakeApprover approver = new FakeApprover(APPROVE);
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), approver).run(req(), rec);
        assertEquals(OutcomeStatus.DENIED, r.status());
        assertEquals(1, rec.attempts.size());
        assertEquals(1, approver.requests.size());
        assertNull(approver.requests.getFirst().retryReason());
    }

    @Test
    void neverModeSkipsApprovalAndReturnsDenial() throws Exception {
        Recorder rec = new Recorder();
        FakeApprover approver = new FakeApprover();
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), approver).run(req().withApprovalPolicy(ApprovalPolicy.NEVER), rec);
        assertEquals(OutcomeStatus.DENIED, r.status());
        assertTrue(approver.requests.isEmpty());
    }

    @Test
    void granularWithoutSandboxApprovalIsForbidden() throws Exception {
        Recorder rec = new Recorder();
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), new FakeApprover()).run(req().withApprovalPolicy(ApprovalPolicy.granular(false)), rec);
        assertEquals(OutcomeStatus.REJECTED, r.status());
        assertEquals("sandbox/approval-disallowed", r.reason().code());
        assertTrue(rec.attempts.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalDecision.class, names = {"DENY", "ABORT"})
    void initialApprovalRefusalDoesNotRun(ApprovalDecision decision) throws Exception {
        Recorder rec = new Recorder();
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), new FakeApprover(decision)).run(req(), rec);
        assertEquals(decision == DENY ? OutcomeStatus.REJECTED : OutcomeStatus.ABORTED, r.status());
        if (decision == DENY) assertEquals("sandbox/approval-denied", r.reason().code());
        assertTrue(rec.attempts.isEmpty());
    }

    @Test
    void retryApprovalRefusalKeepsTheDeniedOutput() throws Exception {
        Recorder rec = new Recorder();
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), new FakeApprover(DENY))
                .run(req().withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED).withRequirement(ApprovalRequirement.skip()), rec);
        assertEquals(OutcomeStatus.REJECTED, r.status());
        assertEquals(1, r.output().exitCode());
        assertEquals(1, rec.attempts.size());
    }

    @Test
    void approvedExplicitEscalationBypassesTheSandboxFirst() throws Exception {
        Recorder rec = new Recorder();
        Outcome<ExecResult> r = orch(new FakeSandbox(FAKE_CAPS), new FakeApprover(APPROVE)).run(req().withPermissions(Permissions.REQUIRE_ESCALATED), rec);
        assertEquals(OutcomeStatus.COMPLETED, r.status());
        assertEquals(1, rec.attempts.size());
        assertFalse(rec.attempts.getFirst().sandboxed());
        assertEquals(FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT, rec.attempts.getFirst().override());
    }

    @Test
    void unavailableSandboxIsRejectedUnlessTheHostOptsIntoUnsandboxedFallback() throws Exception {
        Recorder rec = new Recorder();
        Outcome<ExecResult> rejected = new SandboxOrchestrator(new FakeBackend("local"), null, new FakeApprover())
                .run(req().withApprovalPolicy(ApprovalPolicy.NEVER), rec);
        assertEquals(OutcomeStatus.REJECTED, rejected.status());
        assertEquals("sandbox/unavailable", rejected.reason().code());
        assertTrue(rec.attempts.isEmpty());
        Outcome<ExecResult> ran = new SandboxOrchestrator(new FakeBackend("local"), null, new FakeApprover(),
                OrchestratorOptions.DEFAULT.withRunUnsandboxedWhenUnavailable(true)).run(req().withApprovalPolicy(ApprovalPolicy.NEVER), rec);
        assertEquals(OutcomeStatus.COMPLETED, ran.status());
        assertEquals(1, rec.attempts.size());
        assertFalse(rec.attempts.getFirst().sandboxed());
    }

    @Test
    void unrestrictedPolicyWithNetworkRunsWithoutSandbox() throws Exception {
        Recorder rec = new Recorder();
        FakeSandbox sb = new FakeSandbox(FAKE_CAPS);
        Outcome<ExecResult> r = orch(sb, new FakeApprover()).run(req(SandboxPolicy.dangerFullAccess()).withApprovalPolicy(ApprovalPolicy.ON_REQUEST), rec);
        assertEquals(OutcomeStatus.COMPLETED, r.status());
        assertEquals(1, rec.attempts.size());
        assertFalse(rec.attempts.getFirst().sandboxed());
        assertTrue(sb.wrapped.isEmpty());
    }

    @Test
    void runExecMarksSandboxedChildrenOnlyWithHostGivenNames() throws Exception {
        FakeBackend inner = new FakeBackend("local");
        FakeBackend sandboxed = new FakeBackend("fake-sandboxed", 1, "mkdir: cannot create directory '/x': Read-only file system");
        SandboxOrchestrator o = new SandboxOrchestrator(inner, new FakeSandbox(FAKE_CAPS, sandboxed), new FakeApprover(APPROVE),
                OrchestratorOptions.DEFAULT.withEnvironmentMarkers(new EnvironmentMarkers("HOST_SANDBOX", "HOST_SANDBOX_NETWORK_DISABLED")));
        Outcome<ExecResult> r = o.runExec(req().withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED).withRequirement(ApprovalRequirement.skip()),
                ExecRequest.of(List.of("mkdir", "/x"), null).withEnvironment(Map.of("KEEP", "1")));
        ExecRequest inSandbox = sandboxed.requests.getFirst();
        assertEquals(OutcomeStatus.ESCALATED, r.status());
        assertEquals(0, r.output().exitCode());
        assertEquals(1, sandboxed.requests.size());
        assertEquals("fake", inSandbox.environment().get("HOST_SANDBOX"));
        assertEquals("1", inSandbox.environment().get("HOST_SANDBOX_NETWORK_DISABLED"));
        assertEquals("1", inSandbox.environment().get("KEEP"));
        assertEquals(1, inner.requests.size());
        assertFalse(inner.requests.getFirst().environment().containsKey("HOST_SANDBOX"));
    }
}
