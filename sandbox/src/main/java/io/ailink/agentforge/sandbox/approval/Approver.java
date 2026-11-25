package io.ailink.agentforge.sandbox.approval;

/** 审批者（宿主给：通常是问用户）。阻塞调用；可能在任意线程上被调。 */
@FunctionalInterface
public interface Approver {
    ApprovalDecision decide(ApprovalRequest request);
}
