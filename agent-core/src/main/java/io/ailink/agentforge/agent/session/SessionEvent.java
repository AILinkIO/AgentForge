package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.loop.AgentResult;
import io.ailink.agentforge.agent.loop.AgentStatus;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.transcript.SubRun;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * 会话发给 UI 的事件（coxswain {@code emit!} 那一族，收成一个封闭类型）。UI 拿事件驱动重画，细节从会话的读口取。
 */
public sealed interface SessionEvent {

    /** 状态变了（每次都发；UI 只想知道"该不该重画状态条"时听这一个就够）。 */
    record StatusChanged(SessionStatus status) implements SessionEvent { }

    /** 一轮开始了（用户那句话已进记录区）。 */
    record Started(String text) implements SessionEvent { }

    /** 流式增量；kind 是 {@code text} 或 {@code thinking}。 */
    record Delta(String kind, String text) implements SessionEvent { }

    /** 模型的一次回答落地（每轮工具循环里可能有好几次）。 */
    record Assistant(String text, int toolCalls) implements SessionEvent { }

    /** 要调一件工具了（审批之前发：UI 先画出"它想干什么"，闸再弹）。 */
    record ToolCall(String toolUseId, String name, ObjectNode input) implements SessionEvent { }

    /** 工具结果（含被拒、参数不合法、未知工具）；kind 见 {@code loop.ToolResult}。 */
    record ToolResult(String toolUseId, String name, List<Block> content, boolean error, String kind) implements SessionEvent { }

    /** 一道审批摆出来了，等 {@link AgentSession#resolveApproval}。 */
    record ApprovalRequested(io.ailink.agentforge.agent.approval.ApprovalRequest request) implements SessionEvent { }

    /** 审批答了。 */
    record ApprovalResolved(io.ailink.agentforge.agent.approval.ApprovalRequest request,
                            io.ailink.agentforge.agent.approval.ReviewDecision decision) implements SessionEvent { }

    /** 没问人就定了（本会话批过 / 规则 / 以后都放行的工具）；{@code why} 给人看。 */
    record AutoApproval(String toolName, String summary, boolean allowed, String why) implements SessionEvent { }

    /** 写进了一条修订（规则文件里的 prefix_rule / network_rule，或配置里的免审批工具）。 */
    record PolicyAmended(String description) implements SessionEvent { }

    /** 整轮攒下的改动审完了（采用 / 撤销）。 */
    record Review(io.ailink.agentforge.agent.approval.ReviewDecision decision, int files) implements SessionEvent { }

    /** clarify 摆出了一道题，等 {@link AgentSession#answerQuestion}。 */
    record Question(ClarifyService.Request request) implements SessionEvent { }

    /** 题答了。 */
    record QuestionAnswered(ClarifyService.Answer answer) implements SessionEvent { }

    /** 问模型失败、正在自动重试（不是报错：后面没跟着 Error 就是兜住了）。 */
    record Retry(int attempt, int max, long waitMs, String message) implements SessionEvent { }

    /** 用量更新：这一次 + 这段对话累计 + 估价（null = 一笔都没算出来）+ 窗口占用（null = 不知道）。 */
    record UsageUpdated(Usage last, Usage total, Double cost, boolean costPartial, Double contextRatio) implements SessionEvent { }

    /** 忙时发的话排上队了。 */
    record Queued(String text) implements SessionEvent { }

    /** 排着的话在下一轮开头并进去了（中途 steer）。 */
    record Steered(String text) implements SessionEvent { }

    /** 出错 / 按停：排着的话还回草稿（{@link AgentSession#draft()}），不替用户重发。 */
    record Requeued(String text) implements SessionEvent { }

    record SubStart(SubRun run) implements SessionEvent { }

    record SubStep(SubRun run, String toolName) implements SessionEvent { }

    record SubFinish(SubRun run) implements SessionEvent { }

    /** 发请求前裁掉了几条（只是这一次请求，历史一条没少）。 */
    record Trimmed(int dropped, int total) implements SessionEvent { }

    record Compacting() implements SessionEvent { }

    /** 压完了：压掉几条、摘要多长。 */
    record Compacted(int headMessages, int summaryChars) implements SessionEvent { }

    /** 连着几次大请求都没命中 prompt cache（多半是端点不支持 / 前缀每轮在变）。 */
    record CacheInactive(int rounds) implements SessionEvent { }

    /** 一轮正常落地（status 决定是不是做完了）。 */
    record Finished(AgentStatus status, AgentResult result) implements SessionEvent { }

    /**
     * 落地了但没做完：{@code reason} 是 {@code max-iterations} / {@code stop-when} / {@code stopped} / {@code truncated}；
     * {@code limit} 是对应的上限（轮数 / max_tokens），可能为 null。
     */
    record Incomplete(String reason, Integer limit) implements SessionEvent { }

    /** 被按停（已经跑完的工具留在历史里）。 */
    record Stopped() implements SessionEvent { }

    /** 出错（重试用尽）；{@code line} 是说得出病灶的那一行。 */
    record Error(Throwable error, String line) implements SessionEvent { }

    /** worktree-enter 在这一轮结束后生效了。 */
    record RootChanged(Path root) implements SessionEvent { }
}
