package io.ailink.agentforge.sandbox.agent;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PathDecision;
import io.ailink.agentforge.sandbox.policy.SandboxPathPolicy;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

/**
 * 进程内文件工具（read_file / write_file / apply_patch …）的路径守卫：与子进程沙盒同一份策略 + 上下文。
 * {@link #check} 回 {@code null} = 放行，否则是带码的拒绝理由（直接回给模型）。
 */
public final class PathGuard {
    private final SandboxPathPolicy policy;

    public PathGuard(SandboxPolicy policy, PathContext context) {
        this.policy = new SandboxPathPolicy(policy, context);
    }

    public Message check(String fullPath, SandboxPathPolicy.Access access) {
        PathDecision d = policy.check(fullPath, access);
        return d.allowed() ? null : d.reason();
    }

    public SandboxPathPolicy policy() {
        return policy;
    }
}
