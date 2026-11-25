package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.SandboxPathPolicy;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.List;

/**
 * 写文件 / 打补丁 / 删路径的安全评估（移植自 Codex core/src/safety.rs 的 assess_patch_safety，Apache-2.0）：
 * <ol>
 * <li>没有目标 ⇒ 拒（空补丁）；untrusted ⇒ 一律问；</li>
 * <li>全部目标都在可写范围内（全盘可写，或每个路径按沙盒策略可写——可写根之内、不碰 {@code .git} 等受保护名、没被 read / deny 条目收窄）⇒ 直接放；</li>
 * <li>否则：审批策略不许问（never，或 granular 关了沙盒审批）⇒ 拒（只读沙盒 / 越界两种理由）；否则问。</li>
 * </ol>
 * 与上游的差别：上游要求「有平台沙盒可兜底」才自动放（apply_patch 是子进程，硬链接可能指到根外）；这里的文件工具是进程内的，
 * 路径先经工作根限定与 realpath 复核，所以不看沙盒在不在。on-failure 与 on-request 同样处理（上游旧版 on-failure 先放、靠沙盒兜底）。
 */
public final class PatchSafety {
    private PatchSafety() {
    }

    /**
     * @param paths    要写 / 建 / 删 / 移到的绝对路径
     * @param approval 审批策略
     * @param policy   沙盒策略
     * @param context  路径上下文
     */
    public static SafetyCheck assess(List<String> paths, ApprovalPolicy approval, SandboxPolicy policy, PathContext context) {
        if (paths == null || paths.isEmpty()) return new SafetyCheck.Reject(Message.of("sandbox/patch-empty"));
        if (approval.mode() == ApprovalMode.UNLESS_TRUSTED) return SafetyCheck.ASK_USER;
        boolean rejectsSandboxApproval = approval.mode() == ApprovalMode.NEVER
                || (approval.mode() == ApprovalMode.GRANULAR && !approval.allowsSandboxApproval());
        if (constrainedToWritablePaths(paths, policy, context)) return SafetyCheck.AUTO_APPROVE;
        if (rejectsSandboxApproval) return new SafetyCheck.Reject(rejectionReason(policy));
        return SafetyCheck.ASK_USER;
    }

    /** 全盘可写，或每个路径都可写（Codex is_write_patch_constrained_to_writable_paths）。 */
    public static boolean constrainedToWritablePaths(List<String> paths, SandboxPolicy policy, PathContext context) {
        if (policy.fullDiskWriteAccess()) return true;
        SandboxPathPolicy guard = new SandboxPathPolicy(policy, context);
        for (String p : paths) if (!guard.check(p, SandboxPathPolicy.Access.WRITE).allowed()) return false;
        return true;
    }

    /** 受限且一个可写条目都没有 ⇒ 只读沙盒的理由；否则越界的理由。 */
    static Message rejectionReason(SandboxPolicy policy) {
        boolean noWritableRoots = policy.entries().stream().map(PolicyEntry::access).noneMatch(a -> a == AccessMode.WRITE);
        if (policy.isRestricted() && !policy.fullDiskWriteAccess() && noWritableRoots) return Message.of("sandbox/patch-read-only");
        return Message.of("sandbox/patch-outside-project");
    }
}
