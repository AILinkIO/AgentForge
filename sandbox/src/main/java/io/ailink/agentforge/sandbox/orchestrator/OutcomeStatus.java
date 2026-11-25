package io.ailink.agentforge.sandbox.orchestrator;

/** 编排结果的状态。 */
public enum OutcomeStatus {
    /** 跑完了（沙盒内或无需沙盒）。 */
    COMPLETED,
    /** 沙盒拒了，且不升级（输出是沙盒里那次的）。 */
    DENIED,
    /** 沙盒拒了，经审批后不带沙盒重跑（输出是重跑那次的）。 */
    ESCALATED,
    /** 被拒：禁止 / 审批未过 / 沙盒不可用（可能带第一次的输出）。 */
    REJECTED,
    /** 审批者中止。 */
    ABORTED
}
