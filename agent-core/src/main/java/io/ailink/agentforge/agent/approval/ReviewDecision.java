package io.ailink.agentforge.agent.approval;

import java.util.Objects;

/**
 * 用户对一次审批请求的决定（Codex ReviewDecision）。
 *
 * @param kind     决定
 * @param feedback 拒绝时给模型的话（可空；只对 {@link Kind#DENIED} 有意义：模型照着换个做法）
 */
public record ReviewDecision(Kind kind, String feedback) {

    public enum Kind {
        /** 这一次放行。 */
        APPROVED,
        /** 放行，本会话同类请求不再问（同一条命令 / 同一批文件 / 同一主机 / 同一工具）。 */
        APPROVED_FOR_SESSION,
        /** 放行，并把请求附带的修订（{@link Amendment}）写进规则 / 配置：以后都不再问（跨会话）。 */
        APPROVED_ALWAYS,
        /** 不做这件事，这一轮继续（模型换个做法）。 */
        DENIED,
        /** 不做，并把修订写成「以后一律拒」（目前只用于网络主机）。 */
        DENIED_ALWAYS,
        /** 不做，并停下这一轮，等用户下一句话。 */
        ABORT;

        public boolean approved() {
            return this == APPROVED || this == APPROVED_FOR_SESSION || this == APPROVED_ALWAYS;
        }
    }

    public ReviewDecision {
        Objects.requireNonNull(kind, "kind");
        if (feedback != null && feedback.isBlank()) feedback = null;
    }

    public static ReviewDecision of(Kind k) {
        return new ReviewDecision(k, null);
    }

    public static ReviewDecision approved() {
        return of(Kind.APPROVED);
    }

    public static ReviewDecision approvedForSession() {
        return of(Kind.APPROVED_FOR_SESSION);
    }

    public static ReviewDecision approvedAlways() {
        return of(Kind.APPROVED_ALWAYS);
    }

    public static ReviewDecision denied() {
        return of(Kind.DENIED);
    }

    /** 拒绝并附一句给模型的话。 */
    public static ReviewDecision denied(String feedback) {
        return new ReviewDecision(Kind.DENIED, feedback);
    }

    public static ReviewDecision deniedAlways() {
        return of(Kind.DENIED_ALWAYS);
    }

    public static ReviewDecision abort() {
        return of(Kind.ABORT);
    }

    public boolean isApproved() {
        return kind.approved();
    }
}
