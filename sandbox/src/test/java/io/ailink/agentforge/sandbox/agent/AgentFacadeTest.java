package io.ailink.agentforge.sandbox.agent;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.orchestrator.OrchestrationRequest;
import io.ailink.agentforge.sandbox.orchestrator.OrchestratorOptions;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.PolicyPath;
import io.ailink.agentforge.sandbox.policy.SandboxPathPolicy;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** keelson clj-sandbox-agent 的 approval / path_guard / runner 测试。 */
class AgentFacadeTest {
    private static PathContext tmpCtx() {
        return PathContext.of(System.getProperty("java.io.tmpdir"));
    }

    private static OrchestrationRequest baseRequest() {
        return OrchestrationRequest.of(SandboxPolicy.workspaceWrite(), tmpCtx()).withApprovalPolicy(ApprovalPolicy.NEVER).withTimeoutMs(100L);
    }

    private static SandboxOrchestrator unsandboxedOrch() {
        return new SandboxOrchestrator(Exec.localBackend(), null, new FakeApprover(), OrchestratorOptions.DEFAULT.withRunUnsandboxedWhenUnavailable(true));
    }

    // ── approval ─────────────────────────────────────────────────────

    @Test
    void agentApproverMapsDecisionsAndFillsTheKey() {
        List<ApprovalRequest> seen = new ArrayList<>();
        AgentApprover approver = new AgentApprover(req -> {
            seen.add(req);
            return ApprovalDecision.APPROVE;
        }, "shell", List.of("ls"));
        assertEquals(ApprovalDecision.APPROVE, approver.decide(new ApprovalRequest(null, new Message("sandbox/retry-without-sandbox", "重跑"), null, null)));
        assertEquals(1, seen.size());
        assertNotNull(seen.getFirst().key());
        assertNotNull(seen.getFirst().retryReason());
    }

    @Test
    void agentApproverCoercesNullDecisionsToDeny() {
        assertEquals(ApprovalDecision.DENY, new AgentApprover(req -> null, null, null).decide(new ApprovalRequest(null, null, null, null)));
    }

    // ── path guard ───────────────────────────────────────────────────

    @Test
    void guardAllowsWritesInsideRoots() {
        String inside = Path.of(System.getProperty("java.io.tmpdir")).resolve("work/a.txt").toString();
        PathGuard g = new PathGuard(SandboxPolicy.workspaceWrite(), tmpCtx());
        assertNull(g.check(inside, SandboxPathPolicy.Access.WRITE));
        assertNull(g.check(inside, SandboxPathPolicy.Access.READ));
    }

    @Test
    void guardRejectsDeniedAndOutsideWrites() {
        String denied = Platform.isWindows() ? "C:\\denied" : "/etc";
        SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of(PolicyPath.literal(denied), AccessMode.READ), PolicyEntry.denyGlob("**/*.env")));
        PathGuard g = new PathGuard(pol, tmpCtx());
        Message r = g.check(denied + (Platform.isWindows() ? "\\passwd" : "/passwd"), SandboxPathPolicy.Access.WRITE);
        assertNotNull(r);
        assertEquals("sandbox/write-denied", r.code());
        // 相对 glob 按 cwd 解析：cwd 之外的 .env 是「不在可读范围」，cwd 之下的才命中 deny
        Message r2 = g.check(Platform.isWindows() ? "C:\\outside\\a.env" : "/x/a.env", SandboxPathPolicy.Access.READ);
        assertNotNull(r2);
        assertEquals("sandbox/read-outside-roots", r2.code());
        Message r3 = g.check(Path.of(System.getProperty("java.io.tmpdir")).resolve("app/a.env").toString(), SandboxPathPolicy.Access.READ);
        assertEquals("sandbox/read-denied", r3.code());
    }

    // ── runner ───────────────────────────────────────────────────────

    @Test
    void runnerRunsACommandAndCapsOutput() throws Exception {
        if (Platform.isWindows()) return;
        SandboxCommandRunner run = new SandboxCommandRunner(unsandboxedOrch(), baseRequest(), 16);
        CommandResult r = run.run(CommandRequest.of(List.of("/bin/sh", "-c", "printf 01234567890123456789")).withTimeoutMs(1000L));
        assertEquals(0, r.exitCode());
        assertFalse(r.rejected());
        assertTrue(r.omitted());
        assertEquals(16, r.output().length);
        assertEquals("01234567890123456789", r.stdout());
    }

    @Test
    void runnerKeepsHeadAndTailWhenCapping() {
        byte[] big = "HEAD".repeat(20).concat("TAIL".repeat(20)).getBytes();
        byte[][] c = SandboxCommandRunner.capOutput(big, 64);
        String s = new String(c[0]);
        assertTrue(s.startsWith("HEAD") && s.endsWith("TAIL") && s.contains("...[output truncated]..."), s);
        assertNull(SandboxCommandRunner.capOutput(new byte[3], 64)[1]);
    }

    @Test
    void runnerTimesOutAndBridgesRejections() throws Exception {
        if (Platform.isWindows()) return;
        SandboxCommandRunner run = new SandboxCommandRunner(unsandboxedOrch(), baseRequest());
        CommandResult timeout = run.run(CommandRequest.of(List.of("/bin/sh", "-c", "printf before; sleep 30")));
        CommandResult ok = run.run(CommandRequest.of(List.of("/bin/true")).withTimeoutMs(1000L));
        assertTrue(timeout.timedOut());
        assertEquals(124, timeout.exitCode());
        assertEquals("before", timeout.stdout());
        assertFalse(ok.rejected());
    }

    @Test
    void runnerTranslatesAnOrchestratorRejection() throws Exception {
        OrchestrationRequest request = baseRequest().withPolicy(SandboxPolicy.restricted(List.of(PolicyEntry.denyGlob("**/*.env"))));
        SandboxCommandRunner run = new SandboxCommandRunner(unsandboxedOrch(), request);
        CommandResult r = run.run(CommandRequest.of(List.of("/bin/sh", "-c", "cat .env")).withTimeoutMs(1000L));
        assertTrue(r.rejected());
        assertEquals(126, r.exitCode());
        assertEquals("sandbox/unavailable-deny-read", r.reason().code());
    }

    @Test
    void runnerUsesThePlatformSandboxWhenAvailable() throws Exception {
        if (!linux()) return;
        withTempDirs(2, d -> {
            String w = d[0], outside = d[1];
            OrchestrationRequest req = OrchestrationRequest.of(SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP),
                    PathContext.of(w)).withApprovalPolicy(ApprovalPolicy.NEVER);
            SandboxCommandRunner run = SandboxCommandRunner.create(req, r -> ApprovalDecision.DENY, null, OrchestratorOptions.DEFAULT);
            org.junit.jupiter.api.Assumptions.assumeTrue(run.orchestrator().sandbox() != null, "本机没有可用的平台沙盒");
            CommandResult inside = run.run(new CommandRequest(List.of("/bin/sh", "-c", "echo hi > f && cat f"), w, null, 5000L, false, null));
            assertEquals(0, inside.exitCode(), inside.stderr());
            assertEquals("bwrap", inside.sandbox());
            CommandResult escape = run.run(new CommandRequest(List.of("/bin/sh", "-c", "echo x > '" + outside + "/e'"), w, null, 5000L, false, null));
            assertNotEquals(0, escape.exitCode());
            assertFalse(exists(outside + "/e"));
            assertEquals(io.ailink.agentforge.sandbox.orchestrator.OutcomeStatus.DENIED, escape.status());
        });
    }
}
