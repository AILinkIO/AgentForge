package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;

/**
 * 模型的一次回答。
 *
 * @param stopReason 归一后的停止原因：{@code end_turn} / {@code tool_use} / {@code max_tokens} / {@code stop} / 其它原样
 * @param model      服务端报回来的模型名（可能与请求的不同）
 */
public record ModelTurn(Message message, Usage usage, String stopReason, String model) {
    public boolean truncated() { return "max_tokens".equals(stopReason) || "length".equals(stopReason); }
}
