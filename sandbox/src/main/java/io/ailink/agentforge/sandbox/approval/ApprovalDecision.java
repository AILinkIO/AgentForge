package io.ailink.agentforge.sandbox.approval;

/** 审批者的决定。 */
public enum ApprovalDecision {
    /** 这一次放行。 */
    APPROVE,
    /** 放行，并在本会话内记住同一审批键（之后不再问）。 */
    APPROVE_FOR_SESSION,
    /** 拒绝（命令不跑 / 不重跑）。 */
    DENY,
    /** 中止整个调用。 */
    ABORT
}
