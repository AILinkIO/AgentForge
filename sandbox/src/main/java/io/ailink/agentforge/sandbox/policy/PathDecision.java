package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.Message;

import java.util.Objects;

/**
 * 路径守卫的结论：放行，或拒绝并带理由（带错误码，理由给模型看）。
 *
 * @param allowed 放行
 * @param reason  拒绝理由；放行 ⇒ {@code null}
 */
public record PathDecision(boolean allowed, Message reason) {
    public static final PathDecision ALLOW = new PathDecision(true, null);

    public static PathDecision deny(Message reason) {
        return new PathDecision(false, Objects.requireNonNull(reason, "reason"));
    }
}
