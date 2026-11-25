package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.Message;

import java.util.Objects;

/** 写文件类操作的安全评估结果（Codex SafetyCheck）。 */
public sealed interface SafetyCheck {
    /** 都在可写范围里：直接放。 */
    record AutoApprove() implements SafetyCheck {
    }

    /** 要问用户。 */
    record AskUser() implements SafetyCheck {
    }

    /** 拒（审批策略不许问）。 */
    record Reject(Message reason) implements SafetyCheck {
        public Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }

    SafetyCheck AUTO_APPROVE = new AutoApprove();
    SafetyCheck ASK_USER = new AskUser();
}
