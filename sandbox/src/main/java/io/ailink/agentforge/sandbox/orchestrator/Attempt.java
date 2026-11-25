package io.ailink.agentforge.sandbox.orchestrator;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

/**
 * 一次尝试：用 {@code backend} 执行。
 *
 * @param backend  执行后端（沙盒包装过的，或不带沙盒的内层）
 * @param sandbox  这次用的沙盒能力；{@code null} ⇒ 不带沙盒
 * @param policy   这次生效的策略
 * @param override 第一次尝试的取舍
 * @param retry    是不是拒判后的重跑
 */
public record Attempt(ExecBackend backend, SandboxCapabilities sandbox, SandboxPolicy policy, FirstAttemptOverride override,
                      boolean retry) {
    public boolean sandboxed() {
        return sandbox != null;
    }
}
