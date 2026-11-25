package io.ailink.agentforge.sandbox.orchestrator;

import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.Objects;

/**
 * 一次编排的请求。
 *
 * @param policy            沙盒策略
 * @param context           路径上下文
 * @param approvalPolicy    审批策略（缺省 {@link ApprovalPolicy#ON_REQUEST}）
 * @param requirement       审批要求；{@code null} ⇒ 按审批策略算缺省
 * @param permissions       权限要求
 * @param preference        沙盒偏好
 * @param escalateOnFailure 沙盒拒了许不许升级（问「不带沙盒重跑」）
 * @param approvalKey       审批键（会话缓存用；可空）
 * @param timeoutMs         缺省执行时限（执行请求自己没给时用；可空）
 */
public record OrchestrationRequest(SandboxPolicy policy, PathContext context, ApprovalPolicy approvalPolicy, ApprovalRequirement requirement,
                                   Permissions permissions, SandboxPreference preference, boolean escalateOnFailure, String approvalKey,
                                   Long timeoutMs) {
    public OrchestrationRequest {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        if (approvalPolicy == null) approvalPolicy = ApprovalPolicy.ON_REQUEST;
        if (permissions == null) permissions = Permissions.USE_DEFAULT;
        if (preference == null) preference = SandboxPreference.AUTO;
    }

    /** 缺省：ON_REQUEST、缺省要求、USE_DEFAULT、AUTO、许升级。 */
    public static OrchestrationRequest of(SandboxPolicy policy, PathContext context) {
        return new OrchestrationRequest(policy, context, null, null, null, null, true, null, null);
    }

    public OrchestrationRequest withPolicy(SandboxPolicy v) {
        return new OrchestrationRequest(v, context, approvalPolicy, requirement, permissions, preference, escalateOnFailure, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withApprovalPolicy(ApprovalPolicy v) {
        return new OrchestrationRequest(policy, context, v, requirement, permissions, preference, escalateOnFailure, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withRequirement(ApprovalRequirement v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, v, permissions, preference, escalateOnFailure, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withPermissions(Permissions v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, requirement, v, preference, escalateOnFailure, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withPreference(SandboxPreference v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, requirement, permissions, v, escalateOnFailure, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withEscalateOnFailure(boolean v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, requirement, permissions, preference, v, approvalKey, timeoutMs);
    }

    public OrchestrationRequest withApprovalKey(String v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, requirement, permissions, preference, escalateOnFailure, v, timeoutMs);
    }

    public OrchestrationRequest withTimeoutMs(Long v) {
        return new OrchestrationRequest(policy, context, approvalPolicy, requirement, permissions, preference, escalateOnFailure, approvalKey, v);
    }
}
