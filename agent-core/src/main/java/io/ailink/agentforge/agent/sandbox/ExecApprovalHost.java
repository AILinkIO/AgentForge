package io.ailink.agentforge.agent.sandbox;

import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.approval.ToolApprovals;

/** 执行器内部审批要的会话那一侧：摆审批 + 把工具内的「拒绝（带话）/ 停下」告诉会话，由会话改写这次工具结果。 */
public interface ExecApprovalHost extends ToolApprovals.Host {
    void toolOutcome(String toolUseId, ReviewDecision decision);
}
