package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.Message;

import java.util.Objects;

/** 一次调用的审批要求（Codex ExecApprovalRequirement）。工具 / 规则引擎可以给，缺省由 {@link ApprovalPolicy#defaultRequirement} 算。 */
public sealed interface ApprovalRequirement {
    /** 不用问；{@code bypassSandbox} ⇒ 规则明确放行，第一次就不带沙盒。 */
    record Skip(boolean bypassSandbox) implements ApprovalRequirement {
    }

    /** 要问；{@code reason} 可空。 */
    record NeedsApproval(Message reason) implements ApprovalRequirement {
    }

    /** 禁止，带理由。 */
    record Forbidden(Message reason) implements ApprovalRequirement {
        public Forbidden {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static ApprovalRequirement skip() {
        return new Skip(false);
    }

    static ApprovalRequirement skip(boolean bypassSandbox) {
        return new Skip(bypassSandbox);
    }

    static ApprovalRequirement needsApproval() {
        return new NeedsApproval(null);
    }

    static ApprovalRequirement needsApproval(Message reason) {
        return new NeedsApproval(reason);
    }

    static ApprovalRequirement forbidden(Message reason) {
        return new Forbidden(reason);
    }
}
