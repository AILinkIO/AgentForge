package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.Message;

import java.util.Objects;

/**
 * 把沙盒策略当进程内的文件守卫用（参照 Codex safety.rs）：读写文件的工具碰路径前问它，受的是与子进程沙盒同一份策略。
 * <ul>
 * <li>deny 条目（路径及其下、glob）⇒ 读写都拒（{@code sandbox/read-denied}）；</li>
 * <li>读：覆盖它的最具体条目可读才放（{@code sandbox/read-outside-roots}）；</li>
 * <li>写：最具体条目是 write 且不碰受保护名才放（{@code sandbox/protected-path}），否则 {@code sandbox/write-denied}。</li>
 * </ul>
 * 条目在构造时解析一次；不可变 ⇒ 线程安全。路径要先规范化成完整路径（⛔ 跟符号链接 —— 那是调用方的事）。
 */
public final class SandboxPathPolicy {
    /** 访问种类：建、删、移都算写。 */
    public enum Access { READ, WRITE }

    private final PolicyMatcher matcher;

    public SandboxPathPolicy(SandboxPolicy policy, PathContext context) {
        this(policy.matcher(context));
    }

    public SandboxPathPolicy(PolicyMatcher matcher) {
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    public SandboxPolicy policy() {
        return matcher.policy();
    }

    public PathContext context() {
        return matcher.context();
    }

    public PathDecision check(String fullPath, Access access) {
        Objects.requireNonNull(fullPath, "fullPath");
        if (matcher.readDenied(fullPath)) return PathDecision.deny(Message.of("sandbox/read-denied", fullPath));
        if (access == Access.READ)
            return matcher.resolveAccess(fullPath) == AccessMode.DENY
                    ? PathDecision.deny(Message.of("sandbox/read-outside-roots", fullPath)) : PathDecision.ALLOW;
        if (matcher.canWrite(fullPath)) return PathDecision.ALLOW;
        String name = matcher.resolveAccess(fullPath) == AccessMode.WRITE ? matcher.metadataWriteDenial(fullPath) : null;
        return name != null ? PathDecision.deny(Message.of("sandbox/protected-path", fullPath, name))
                : PathDecision.deny(Message.of("sandbox/write-denied", fullPath));
    }
}
