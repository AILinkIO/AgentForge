package io.ailink.agentforge.sandbox.approval;

import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;

import org.junit.jupiter.api.Test;

/** 期望值搬自 OpenAI Codex CLI（Apache-2.0）：codex-rs/core/src/exec_tests.rs 的 sandbox_detection_* 测试。 */
class DenialClassifierTest {
    private static final SandboxCapabilities LINUX_SECCOMP = SandboxCapabilities.available("linux-seccomp").withSeccomp(true);
    private static final SandboxCapabilities MACOS_SEATBELT = SandboxCapabilities.available("seatbelt");

    private static ExecResult out(int code, String stdout, String stderr, String aggregated) {
        return ExecResult.of(code, stdout, stderr, aggregated);
    }

    @Test
    void sandboxDetectionRequiresKeywords() {
        assertFalse(DenialClassifier.likelyDenied(LINUX_SECCOMP, out(1, "", "", "")));
    }

    @Test
    void sandboxDetectionIdentifiesKeywordInStderr() {
        assertTrue(DenialClassifier.likelyDenied(LINUX_SECCOMP, out(1, "", "Operation not permitted", "")));
    }

    @Test
    void sandboxDetectionRespectsQuickRejectExitCodes() {
        assertFalse(DenialClassifier.likelyDenied(LINUX_SECCOMP, out(127, "", "command not found", "")));
    }

    @Test
    void sandboxDetectionIgnoresNonSandboxMode() {
        assertFalse(DenialClassifier.likelyDenied(null, out(1, "", "Operation not permitted", "")));
    }

    @Test
    void sandboxDetectionIgnoresNetworkPolicyTextInNonSandboxMode() {
        assertFalse(DenialClassifier.likelyDenied(null, out(0, "", "",
                "CODEX_NETWORK_POLICY_DECISION {\"decision\":\"ask\",\"reason\":\"not_allowed\",\"source\":\"decider\",\"protocol\":\"http\",\"host\":\"google.com\",\"port\":80}")));
    }

    @Test
    void sandboxDetectionUsesAggregatedOutput() {
        assertTrue(DenialClassifier.likelyDenied(MACOS_SEATBELT, out(101, "", "", "cargo failed: Read-only file system when writing target")));
    }

    @Test
    void sandboxDetectionIgnoresNetworkPolicyTextWithZeroExitCode() {
        assertFalse(DenialClassifier.likelyDenied(LINUX_SECCOMP, out(0, "", "",
                "CODEX_NETWORK_POLICY_DECISION {\"decision\":\"ask\",\"source\":\"decider\",\"protocol\":\"http\",\"host\":\"google.com\",\"port\":80}")));
    }

    @Test
    void sandboxDetectionFlagsSigsysExitCode() {
        if (LexicalPaths.WINDOWS) return;
        assertTrue(DenialClassifier.likelyDenied(LINUX_SECCOMP, out(128 + 31, "", "", "")));
        // 没装 seccomp 的后端（bwrap 单独用）⇒ SIGSYS 不算
        assertFalse(DenialClassifier.likelyDenied(MACOS_SEATBELT, out(128 + 31, "", "", "")));
    }

    @Test
    void executorManagedDetectionOnlyLooksAtKeywords() {
        assertTrue(DenialClassifier.hasDenialKeywords(out(2, "touch: cannot touch 'x': Permission denied", "", "")));
        assertFalse(DenialClassifier.hasDenialKeywords(out(0, "permission denied", "", "")));
    }
}
