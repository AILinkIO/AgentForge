package io.ailink.agentforge.sandbox.exec;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.orchestrator.OrchestrationRequest;
import io.ailink.agentforge.sandbox.orchestrator.Outcome;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 执行时限：部分输出保留、进程树收尾、超时回 124 并标记；编排器顶层时限传给执行请求。 */
class TimeoutTest {
    @Test
    void runExecTimesOutAndKeepsPartialOutput() throws Exception {
        if (Platform.isWindows()) return;
        long started = System.nanoTime();
        ExecResult r = Exec.run(Exec.localBackend(), ExecRequest.of("/bin/sh", "-c", "printf before; sleep 30").withTimeoutMs(100L));
        assertTrue(System.nanoTime() - started < 5_000_000_000L);
        assertTrue(r.timedOut());
        assertEquals(124, r.exitCode());
        assertEquals("before", r.stdout());
        assertEquals("before", r.output());
    }

    @Test
    void runExecRejectsANonPositiveTimeout() {
        List<String> command = Platform.isWindows() ? List.of("cmd", "/c", "exit", "0") : List.of("/bin/true");
        Throwable r = thrown(() -> Exec.run(Exec.localBackend(), ExecRequest.of(command, null).withTimeoutMs(0L)));
        assertEquals("sandbox/exec-bad-timeout", code(r));
    }

    @Test
    void orchestratorAppliesTheRequestTimeoutToEachAttempt() throws Exception {
        FakeBackend inner = new FakeBackend("local");
        FakeBackend sandboxed = new FakeBackend("fake-sandboxed", 1, "Operation not permitted");
        SandboxOrchestrator o = new SandboxOrchestrator(inner, new FakeSandbox(SandboxCapabilities.available("fake"), sandboxed),
                new FakeApprover(ApprovalDecision.APPROVE));
        Outcome<ExecResult> r = o.runExec(OrchestrationRequest.of(SandboxPolicy.workspaceWrite(), PathContext.of(System.getProperty("java.io.tmpdir")))
                .withApprovalKey("shell\u0000timeout").withTimeoutMs(250L), ExecRequest.of("sleep", "30"));
        assertEquals(1, r.output().exitCode());
        assertEquals(250L, sandboxed.requests.getFirst().timeoutMs());
    }

    @Test
    void localBackendCapturesInterleavedOutputAndStdin() throws Exception {
        if (Platform.isWindows()) return;
        ExecResult r = Exec.run(Exec.localBackend(), ExecRequest.of("/bin/sh", "-c", "cat; echo err >&2"),
                "piped".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, r.exitCode());
        assertEquals("piped", r.stdout());
        assertEquals("err\n", r.stderr());
        assertTrue(r.output().contains("piped") && r.output().contains("err"));
    }

    @Test
    void environmentOverridesAndRemovals() throws Exception {
        if (Platform.isWindows()) return;
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put("KS_A", "1");
        env.put("HOME", null);
        ExecResult r = Exec.run(Exec.localBackend(), ExecRequest.of("/bin/sh", "-c", "echo \"$KS_A[${HOME:-none}]\"").withEnvironment(env));
        assertEquals("1[none]\n", r.stdout());
    }
}
