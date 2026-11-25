package io.ailink.agentforge.agent.sandbox;

import io.ailink.agentforge.agent.approval.Amendment;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.approval.SessionPermissions;
import io.ailink.agentforge.agent.approval.ToolApprovals;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.Approver;
import io.ailink.agentforge.sandbox.exec.ExecResult;

import java.util.List;
import java.util.Objects;

/**
 * 沙盒编排器的审批者 → 会话的审批（Codex 的 exec 审批）：事先审批摆 {@link ApprovalRequest.Kind#EXEC}，沙盒拦下后摆
 * {@link ApprovalRequest.Kind#EXEC_RETRY}（预览给沙盒里那一次的输出——用户要看见它为什么被挡）。决定的映射：
 * 放行 ⇒ APPROVE；本会话 ⇒ APPROVE_FOR_SESSION（编排器按命令缓存）；以后都 ⇒ 追加 {@code prefix_rule} 再 APPROVE；
 * 拒绝 ⇒ DENY（用户的话经 {@link ExecApprovalHost#toolOutcome} 附进工具结果）；停下 ⇒ ABORT（会话结束这一轮）。
 */
final class SessionExecApprover implements Approver {
    private final ExecApprovalHost host;
    private final SessionPermissions perms;
    private final String command;
    private final List<String> policyArgv;
    private final String cwd;
    private final String justification;
    private final List<String> amendment;
    private final String toolUseId;

    SessionExecApprover(ExecApprovalHost host, SessionPermissions perms, String command, List<String> policyArgv, String cwd, String justification,
                        List<String> amendment, String toolUseId) {
        this.host = Objects.requireNonNull(host);
        this.perms = perms;
        this.command = command;
        this.policyArgv = policyArgv;
        this.cwd = cwd;
        this.justification = justification;
        this.amendment = amendment;
        this.toolUseId = toolUseId;
    }

    /** 不经编排器、直接问一次（后台任务起之前用）。 */
    ReviewDecision askUpfront(String reason) {
        return host.ask(request(false, reason, null));
    }

    private ApprovalRequest request(boolean retry, String reason, ExecResult denial) {
        Preview.Result pv = null;
        if (denial != null) {
            String out = denial.output() == null ? "" : denial.output();
            pv = new Preview.Result(Preview.Kind.TEXT, "沙盒里那一次：退出码 " + denial.exitCode() + "\n"
                    + (out.length() > 2000 ? out.substring(out.length() - 2000) : out));
        }
        Amendment am = amendment == null ? null : new Amendment.PrefixRule(amendment);
        return new ApprovalRequest(null, retry ? ApprovalRequest.Kind.EXEC_RETRY : ApprovalRequest.Kind.EXEC, host.sessionId(), toolUseId,
                "run-command", command, policyArgv, cwd, pv, reason != null ? reason : justification,
                retry ? "沙盒拦下了这条命令；要不带沙盒重跑吗？" : null, null, am,
                AgentTool.JSON.createObjectNode().put("command", command), null);
    }

    /** 决定 → 编排器的决定（副作用：写规则、记下拒绝 / 停下）。 */
    ApprovalDecision apply(ReviewDecision d) {
        switch (d.kind()) {
            case APPROVED -> {
                return ApprovalDecision.APPROVE;
            }
            case APPROVED_FOR_SESSION -> {
                return ApprovalDecision.APPROVE_FOR_SESSION;
            }
            case APPROVED_ALWAYS -> {
                if (amendment != null) ToolApprovals.amendPrefix(host, perms, amendment);
                return ApprovalDecision.APPROVE;
            }
            case ABORT -> {
                host.toolOutcome(toolUseId, d);
                return ApprovalDecision.ABORT;
            }
            default -> {
                host.toolOutcome(toolUseId, d);
                return ApprovalDecision.DENY;
            }
        }
    }

    @Override
    public ApprovalDecision decide(io.ailink.agentforge.sandbox.approval.ApprovalRequest req) {
        boolean retry = req.isRetry();
        String reason = !retry && req.reason() != null ? req.reason().text() : null;
        return apply(host.ask(request(retry, reason, retry ? req.denial() : null)));
    }
}
