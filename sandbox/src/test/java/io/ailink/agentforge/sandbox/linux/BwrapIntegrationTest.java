package io.ailink.agentforge.sandbox.linux;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.approval.DenialClassifier;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.orchestrator.OrchestrationRequest;
import io.ailink.agentforge.sandbox.orchestrator.Outcome;
import io.ailink.agentforge.sandbox.orchestrator.OutcomeStatus;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 本机真跑系统 bwrap。不是 Linux / 没有可用的 bwrap ⇒ 跳过（assumption）并给出原因。 */
class BwrapIntegrationTest {
    private static final BwrapSandbox SHARED = new BwrapSandbox();

    static BwrapSandbox requireBwrap() {
        assumeTrue(linux(), "bwrap 只在 Linux 上");
        SandboxCapabilities caps = SHARED.probe();
        assumeTrue(caps.available(), () -> "本机 bwrap 不可用：" + caps.unavailableReason());
        return SHARED;
    }

    private static SandboxPolicy workspace(NetworkMode network) {
        return SandboxPolicy.workspaceWrite(List.of(), SandboxPolicy.WorkspaceWriteOptions.NO_TEMP).withNetwork(network);
    }

    private static SandboxPolicy workspace() {
        return workspace(NetworkMode.RESTRICTED);
    }

    private static ExecBackend wrap(BwrapSandbox s, SandboxPolicy pol, String cwd) {
        return s.wrap(Exec.localBackend(), pol, PathContext.of(cwd));
    }

    @Test
    void probeFindsTheSystemBwrap() {
        BwrapSandbox s = requireBwrap();
        assertEquals("bwrap", s.probe().name());
        assertNotNull(s.bwrapPath());
    }

    @Test
    void writeInsideAWritableRootSucceedsAndLeavesNoSyntheticMounts() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            ExecBackend backend = wrap(s, workspace(), w);
            ExecResult r = sh(backend, w, "echo hi > inside.txt && mkdir -p sub/dir && echo ok > sub/dir/f && cat inside.txt");
            assertTrue(backend.capabilities().sandboxed());
            assertEquals(0, r.exitCode(), r.stderr());
            assertEquals("hi", r.stdout().strip());
            assertEquals("ok\n", read(join(w, "sub", "dir", "f")));
            // 不存在的受保护名挂成了临时空目录，进程退出后清掉
            for (String n : SandboxPolicy.DEFAULT_PROTECTED_NAMES) assertFalse(isDir(join(w, n)), n);
        });
    }

    @Test
    void writeOutsideWritableRootsFailsAndIsClassifiedAsDenial() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(2, d -> {
            String w = d[0], outside = d[1];
            ExecResult r = sh(wrap(s, workspace(), w), w, "echo x > '" + join(outside, "escape.txt") + "'");
            assertNotEquals(0, r.exitCode());
            assertFalse(exists(join(outside, "escape.txt")));
            assertTrue(DenialClassifier.likelyDenied(s.probe(), r), r.stderr());
        });
    }

    @Test
    void dotGitInsideTheWritableRootStaysReadOnly() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            mkdirs(join(w, ".git", "hooks"));
            write(join(w, ".git", "config"), "[core]\n");
            ExecBackend b = wrap(s, workspace(), w);
            ExecResult config = sh(b, w, "echo evil >> .git/config");
            ExecResult hook = sh(b, w, "echo 'rm -rf ~' > .git/hooks/pre-commit");
            ExecResult rd = sh(b, w, "cat .git/config");
            assertNotEquals(0, config.exitCode());
            assertTrue(DenialClassifier.likelyDenied(s.probe(), config), config.stderr());
            assertNotEquals(0, hook.exitCode());
            assertEquals("[core]\n", read(join(w, ".git", "config")));
            assertFalse(exists(join(w, ".git", "hooks", "pre-commit")));
            assertEquals(0, rd.exitCode());
            assertEquals("[core]", rd.stdout().strip());
        });
    }

    @Test
    void missingDotGitCannotBeCreatedInsideTheSandbox() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            ExecResult r = sh(wrap(s, workspace(), w), w, "mkdir .git/hooks; echo \"rc=$?\"; echo evil > .git/x; echo \"rc=$?\"; echo ran");
            List<String> l = lines(r.stdout());
            // 沙盒本身起来了（跑到了最后一句），只是 .git 下建不了东西
            assertEquals(3, l.size(), r.stdout() + r.stderr());
            assertNotEquals("rc=0", l.get(0));
            assertNotEquals("rc=0", l.get(1));
            assertEquals("ran", l.get(2));
            assertTrue(r.stderr().contains("Read-only file system"), r.stderr());
            assertFalse(isDir(join(w, ".git")));
        });
    }

    @Test
    void restrictedNetworkBlocksTcpConnections() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            ExecBackend b = wrap(s, workspace(), w);
            ExecResult ifaces = sh(b, w, "tail -n +3 /proc/net/dev | cut -d: -f1 | tr -d ' '");
            // 新的 network namespace 里只有 lo
            assertEquals(0, ifaces.exitCode(), ifaces.stderr());
            assertEquals(List.of("lo"), lines(ifaces.stdout()));
            if (exists("/usr/bin/curl")) assertNotEquals(0, sh(b, w, "curl -sS --max-time 5 http://1.1.1.1/").exitCode());
            ExecResult host = sh(wrap(s, workspace(NetworkMode.ENABLED), w), w, "tail -n +3 /proc/net/dev | cut -d: -f1 | tr -d ' '");
            assertTrue(lines(host.stdout()).contains("lo"));
            assertTrue(lines(host.stdout()).size() > 1, host.stdout());
        });
    }

    @Test
    void denyReadEntriesAreMasked() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            write(join(w, ".env"), "TOKEN=secret");
            mkdirs(join(w, "secrets"));
            write(join(w, "secrets", "key"), "k");
            SandboxPolicy pol = workspace().plusEntries(denyGlob("**/*.env"), e(join(w, "secrets"), AccessMode.DENY));
            ExecBackend b = wrap(s, pol, w);
            ExecResult env = sh(b, w, "cat .env");
            ExecResult dir = sh(b, w, "ls secrets");
            assertNotEquals(0, env.exitCode());
            assertFalse(env.stdout().contains("secret"));
            assertTrue(DenialClassifier.likelyDenied(s.probe(), env), env.stderr());
            assertNotEquals(0, dir.exitCode());
            assertFalse(dir.stdout().contains("key"));
        });
    }

    @Test
    void procAndDevAreUsableInsideTheSandbox() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(1, d -> {
            String w = d[0];
            ExecResult r = sh(wrap(s, SandboxPolicy.readOnly(), w), w,
                    "echo x > /dev/null && head -c 4 /dev/urandom | wc -c && grep -c ^Pid /proc/self/status && touch nope");
            assertEquals(List.of("4", "1"), lines(r.stdout()).stream().map(String::strip).toList());
            assertNotEquals(0, r.exitCode());
            assertTrue(r.stderr().contains("Read-only file system"), r.stderr());
        });
    }

    @Test
    void orchestratorEscalatesADeniedWriteAfterApproval() throws Exception {
        BwrapSandbox s = requireBwrap();
        withTempDirs(2, d -> {
            String w = d[0], outside = d[1];
            String target = join(outside, "approved.txt");
            FakeApprover approver = new FakeApprover(ApprovalDecision.APPROVE);
            SandboxOrchestrator orch = new SandboxOrchestrator(Exec.localBackend(), s, approver);
            OrchestrationRequest request = OrchestrationRequest.of(workspace(), PathContext.of(w)).withApprovalPolicy(ApprovalPolicy.UNLESS_TRUSTED)
                    .withRequirement(ApprovalRequirement.skip());
            ExecRequest execReq = ExecRequest.of(List.of("/bin/sh", "-c", "echo ok > '" + target + "'"), w);
            Outcome<ExecResult> r = orch.runExec(request, execReq);
            assertEquals(OutcomeStatus.ESCALATED, r.status());
            assertEquals(0, r.output().exitCode());
            assertEquals(1, approver.requests.size());
            assertNotNull(approver.requests.getFirst().retryReason());
            assertTrue(exists(target));
            // 不许升级 ⇒ 回拒判结果
            Files.delete(Path.of(target));
            Outcome<ExecResult> denied = orch.runExec(request.withEscalateOnFailure(false), execReq);
            assertEquals(OutcomeStatus.DENIED, denied.status());
            assertFalse(exists(target));
        });
    }
}
