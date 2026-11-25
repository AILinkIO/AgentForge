package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 拒判：这次失败是不是沙盒挡的（移植自 Codex sandboxing/src/denial.rs，Apache-2.0）。编排器靠它决定要不要问「不带沙盒重跑」。
 */
@FunctionalInterface
public interface DenialClassifier {
    /** {@code sandbox} 为 {@code null} ⇒ 这次没上沙盒。 */
    boolean isDenied(SandboxCapabilities sandbox, ExecResult result);

    /** 与 Codex 一致的关键字（数据，不是文案）。 */
    List<String> DENIAL_KEYWORDS = List.of("operation not permitted", "permission denied", "read-only file system", "seccomp",
            "sandbox", "landlock", "failed to write file");

    /** 缺省实现：{@link #likelyDenied}。 */
    DenialClassifier DEFAULT = DenialClassifier::likelyDenied;

    private static boolean keywordIn(String s) {
        String lower = s == null ? "" : s.toLowerCase(Locale.ROOT);
        for (String k : DENIAL_KEYWORDS) if (lower.contains(k)) return true;
        return false;
    }

    /** 后端未知（执行端自管沙盒）时只看关键字（Codex is_likely_executor_managed_sandbox_denied）。 */
    static boolean hasDenialKeywords(ExecResult r) {
        return r.exitCode() != 0 && (keywordIn(r.stderr()) || keywordIn(r.stdout()) || keywordIn(r.output()));
    }

    /**
     * 缺省拒判：没上沙盒或退出码 0 不算；有沙盒关键字算；否则 2 / 126 / 127 不算（参数错 / 不可执行 / 找不到）；
     * 装了 seccomp 的后端下 128+SIGSYS 算。
     */
    static boolean likelyDenied(SandboxCapabilities sandbox, ExecResult r) {
        if (sandbox == null || r.exitCode() == 0) return false;
        if (hasDenialKeywords(r)) return true;
        if (Set.of(2, 126, 127).contains(r.exitCode())) return false;
        return sandbox.usesSeccomp() && !LexicalPaths.WINDOWS && r.exitCode() == 128 + 31;
    }
}
