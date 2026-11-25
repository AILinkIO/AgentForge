package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.agent.tools.Preview;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 摆给用户的一次审批（Codex ExecApprovalRequest / ApplyPatchApprovalRequest / 网络与 MCP 审批收成一个形状）。界面只需要：
 * 按 {@link #kind} 选标题样式，显示 {@link #title} / {@link #command} / {@link #preview} / {@link #reason}，把 {@link #choices} 画成选项，
 * 用户选了哪项就 {@code AgentSession.resolveApproval(ReviewDecision)}。
 *
 * @param id          这次请求的 id
 * @param kind        种类
 * @param session     摆出它的会话 id（子 agent 的请求摆在父会话上，这一格说清是谁问的）
 * @param toolUseId   哪次工具调用（批量审阅那道是 null）
 * @param toolName    工具名
 * @param title       一行摘要（命令原文 / 「改 3 个文件」/ 主机名…）
 * @param command     要跑的命令（EXEC / EXEC_RETRY；其余 null）
 * @param cwd         命令的工作目录（可空）
 * @param preview     「批准之后会怎样」：diff / 命令原文 / 沙盒里那次的输出；{@link Preview.Kind#TEXT} 的不许按 diff 上色
 * @param reason      为什么要问：规则的理由、模型给的 justification、越界的路径…（可空）
 * @param retryReason 非空 ⇒ 「沙盒拦下了，要不带沙盒重跑吗」
 * @param host        网络审批的主机（可空）
 * @param amendment   选「以后都 …」时写进去的东西（可空 ⇒ 没有那一项）
 * @param input       工具的完整参数（可空）
 * @param choices     可选的决定（顺序即建议的显示顺序）
 */
public record ApprovalRequest(String id, Kind kind, String session, String toolUseId, String toolName, String title, List<String> command,
                              String cwd, Preview.Result preview, String reason, String retryReason, String host, Amendment amendment,
                              ObjectNode input, List<ApprovalChoice> choices) {

    public enum Kind {
        /** 跑一条命令（事先审批：规则要求 / 模型要出沙盒 / 危险命令 / 没有沙盒兜底）。 */
        EXEC,
        /** 命令在沙盒里被拦下了：要不要不带沙盒重跑。 */
        EXEC_RETRY,
        /** 写 / 改 / 删文件（落在可写范围之外，或审批策略要求逐次过目）。 */
        PATCH,
        /** 访问网络主机（沙盒网络关着）。 */
        NETWORK,
        /** 其他会改东西的工具（MCP、worktree、execute…）。 */
        TOOL,
        /** 碰到密钥文件（{@code .env}）：内容进了上下文就收不回来。 */
        SECRET,
        /** 整轮攒下的改动（staging）一次过目。 */
        REVIEW
    }

    public ApprovalRequest {
        Objects.requireNonNull(kind, "kind");
        if (id == null) id = UUID.randomUUID().toString();
        command = command == null ? null : List.copyOf(command);
        choices = choices == null || choices.isEmpty() ? defaultChoices(kind, amendment, host) : List.copyOf(choices);
    }

    /** 某个决定在不在这道请求的选项里。 */
    public boolean allows(ReviewDecision.Kind k) {
        return choices.stream().anyMatch(c -> c.decision() == k);
    }

    /** 按快捷键找选项；没有 ⇒ null。 */
    public ApprovalChoice choice(String key) {
        for (ApprovalChoice c : choices) if (c.key().equals(key)) return c;
        return null;
    }

    public boolean isRetry() {
        return retryReason != null;
    }

    /** 换一组选项。 */
    public ApprovalRequest withChoices(List<ApprovalChoice> cs) {
        return new ApprovalRequest(id, kind, session, toolUseId, toolName, title, command, cwd, preview, reason, retryReason, host, amendment, input, cs);
    }

    public ApprovalRequest withSession(String s) {
        return new ApprovalRequest(id, kind, s, toolUseId, toolName, title, command, cwd, preview, reason, retryReason, host, amendment, input, choices);
    }

    /** 各种类的缺省选项（文案照 Codex 审批面板）。 */
    public static List<ApprovalChoice> defaultChoices(Kind kind, Amendment amendment, String host) {
        List<ApprovalChoice> out = new ArrayList<>();
        switch (kind) {
            case EXEC, EXEC_RETRY -> {
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "是，执行"));
                if (amendment != null) out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_ALWAYS, "p", "是，以后" + amendment.describe() + "都不再问"));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_FOR_SESSION, "a", "是，本会话这条命令不再问"));
            }
            case PATCH -> {
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "是，照做"));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_FOR_SESSION, "a", "是，本会话这些文件不再问"));
            }
            case NETWORK -> {
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "是，只这一次"));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_FOR_SESSION, "a", "是，本会话放行 " + host));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_ALWAYS, "p", "是，以后都放行 " + host));
            }
            case TOOL -> {
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "是，调用"));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_FOR_SESSION, "a", "是，本会话这件工具不再问"));
                if (amendment != null) out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_ALWAYS, "p", "是，以后" + amendment.describe() + "都不再问"));
            }
            case SECRET -> {
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "是，读这一次"));
                out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED_FOR_SESSION, "a", "是，本会话这件调用不再问"));
            }
            case REVIEW -> out.add(new ApprovalChoice(ReviewDecision.Kind.APPROVED, "y", "采用这些改动"));
        }
        if (kind == Kind.NETWORK) out.add(new ApprovalChoice(ReviewDecision.Kind.DENIED_ALWAYS, "b", "否，以后都拦下 " + host));
        out.add(new ApprovalChoice(ReviewDecision.Kind.DENIED, "d", kind == Kind.REVIEW ? "撤销这些改动" : "否，不做，接着干别的"));
        out.add(new ApprovalChoice(ReviewDecision.Kind.ABORT, "n", "否，停下来，我来说怎么做"));
        return out;
    }
}
