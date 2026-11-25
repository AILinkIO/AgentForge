package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.policy.FileSystemMode;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 审批策略（移植自 Codex protocol.rs AskForApproval / tools/sandboxing.rs / exec_policy.rs，Apache-2.0）。
 *
 * @param mode                  模式
 * @param allowsSandboxApproval {@link ApprovalMode#GRANULAR} 下许不许发起沙盒审批（出沙盒、越界写）
 * @param allowsRulesApproval   {@link ApprovalMode#GRANULAR} 下许不许发起规则审批（execpolicy 的 {@code prompt} 规则）
 */
public record ApprovalPolicy(ApprovalMode mode, boolean allowsSandboxApproval, boolean allowsRulesApproval) {
    public static final ApprovalPolicy UNLESS_TRUSTED = new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED, false, false);
    public static final ApprovalPolicy ON_FAILURE = new ApprovalPolicy(ApprovalMode.ON_FAILURE, false, false);
    public static final ApprovalPolicy ON_REQUEST = new ApprovalPolicy(ApprovalMode.ON_REQUEST, false, false);
    public static final ApprovalPolicy NEVER = new ApprovalPolicy(ApprovalMode.NEVER, false, false);

    public ApprovalPolicy {
        Objects.requireNonNull(mode, "mode");
        if (mode != ApprovalMode.GRANULAR) {
            allowsSandboxApproval = false;
            allowsRulesApproval = false;
        }
    }

    /** 细粒度：规则审批照常，沙盒审批按参数。 */
    public static ApprovalPolicy granular(boolean sandboxApproval) {
        return granular(sandboxApproval, true);
    }

    public static ApprovalPolicy granular(boolean sandboxApproval, boolean rulesApproval) {
        return new ApprovalPolicy(ApprovalMode.GRANULAR, sandboxApproval, rulesApproval);
    }

    public static ApprovalPolicy of(ApprovalMode mode) {
        return mode == ApprovalMode.GRANULAR ? granular(true, true) : new ApprovalPolicy(mode, false, false);
    }

    /**
     * 认 {@code untrusted / on-failure / on-request / never / granular[:sandbox,rules]}（granular 后面列出<b>许</b>发起的类；
     * 只写 {@code granular} = 两类都许）。认不出 ⇒ {@link IllegalArgumentException}。
     */
    public static ApprovalPolicy parse(String s) {
        String k = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
        int colon = k.indexOf(':');
        if (colon < 0) return of(ApprovalMode.parse(k));
        if (ApprovalMode.parse(k.substring(0, colon)) != ApprovalMode.GRANULAR)
            throw new IllegalArgumentException("只有 granular 能带开关：" + s);
        boolean sandbox = false, rules = false;
        for (String part : k.substring(colon + 1).split(",")) {
            switch (part.strip()) {
                case "sandbox" -> sandbox = true;
                case "rules" -> rules = true;
                case "" -> { }
                default -> throw new IllegalArgumentException("granular 只认 sandbox / rules：" + s);
            }
        }
        return granular(sandbox, rules);
    }

    /** 配置里写的名字（{@link #parse} 的逆）。 */
    public String wire() {
        if (mode != ApprovalMode.GRANULAR) return mode.wire();
        if (allowsSandboxApproval && allowsRulesApproval) return "granular";
        List<String> on = new ArrayList<>();
        if (allowsSandboxApproval) on.add("sandbox");
        if (allowsRulesApproval) on.add("rules");
        return "granular:" + String.join(",", on);
    }

    /** 沙盒拒了，能不能问「不带沙盒重跑」（Codex wants_no_sandbox_approval；on-failure 照旧版）。 */
    public boolean wantsNoSandboxApproval() {
        return switch (mode) {
            case UNLESS_TRUSTED, ON_FAILURE -> true;
            case GRANULAR -> allowsSandboxApproval;
            default -> false;
        };
    }

    /** 重跑前的再审批可免（本次已批过，或从不问）。 */
    public boolean shouldBypassApproval(boolean alreadyApproved) {
        return alreadyApproved || mode == ApprovalMode.NEVER;
    }

    /**
     * 这类提示能不能摆给用户（Codex prompt_is_rejected_by_policy）：回 {@code null} = 能；否则是自动拒的理由。
     * {@code isRule} 区分「规则要求的提示」与「沙盒 / 升级提示」，GRANULAR 两类分别看开关。
     */
    public Message promptRejection(boolean isRule) {
        return switch (mode) {
            case NEVER -> Message.of("sandbox/execpolicy-prompt-conflict");
            case GRANULAR -> isRule ? (allowsRulesApproval ? null : Message.of("sandbox/execpolicy-rules-disallowed"))
                    : (allowsSandboxApproval ? null : Message.of("sandbox/execpolicy-sandbox-disallowed"));
            default -> null;
        };
    }

    /**
     * 缺省要求（Codex default_exec_approval_requirement）：NEVER / ON_FAILURE ⇒ 不问；ON_REQUEST / GRANULAR ⇒ 受限文件系统才问；
     * UNLESS_TRUSTED ⇒ 都问；GRANULAR 不许沙盒审批而又要问 ⇒ 禁止（{@code sandbox/approval-disallowed}）。
     */
    public ApprovalRequirement defaultRequirement(SandboxPolicy policy) {
        boolean needs = switch (mode) {
            case NEVER, ON_FAILURE -> false;
            case ON_REQUEST, GRANULAR -> policy.fileSystem() == FileSystemMode.RESTRICTED;
            case UNLESS_TRUSTED -> true;
        };
        if (needs && mode == ApprovalMode.GRANULAR && !allowsSandboxApproval)
            return new ApprovalRequirement.Forbidden(Message.of("sandbox/approval-disallowed"));
        return needs ? new ApprovalRequirement.NeedsApproval(null) : new ApprovalRequirement.Skip(false);
    }
}
