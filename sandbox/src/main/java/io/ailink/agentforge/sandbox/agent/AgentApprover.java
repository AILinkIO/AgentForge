package io.ailink.agentforge.sandbox.agent;

import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.approval.Approver;

import java.util.List;
import java.util.Objects;

/**
 * 智能体审批缝 ⇒ 编排器的 {@link Approver}：补全形状（缺省审批键、重跑理由），审批者回 {@code null} 按 DENY 收口（失败即拒）。
 */
public final class AgentApprover implements Approver {
    private final Approver delegate;
    private final String tool;
    private final List<String> arguments;

    /**
     * @param delegate  宿主的审批者（通常弹窗问用户）
     * @param tool      工具名（只用来生成缺省审批键）
     * @param arguments 工具参数（同上）
     */
    public AgentApprover(Approver delegate, String tool, List<String> arguments) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.tool = tool == null ? "tool" : tool;
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** 缺省审批键：工具名 NUL argv（同一条命令本会话只问一次）。 */
    public static String approvalKey(String tool, List<String> arguments) {
        return (tool == null ? "tool" : tool) + "\u0000" + String.join("\u0000", arguments == null ? List.of() : arguments);
    }

    @Override
    public ApprovalDecision decide(ApprovalRequest request) {
        ApprovalRequest req = request;
        if (req.key() == null) req = req.withKey(approvalKey(tool, arguments));
        if (req.retryReason() == null && req.reason() != null) req = req.withRetryReason(req.reason());
        ApprovalDecision d = delegate.decide(req);
        return d == null ? ApprovalDecision.DENY : d;
    }
}
