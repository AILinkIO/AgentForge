package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;

import java.util.List;

/**
 * 一趟循环的结果。{@code messages} 是**完整**历史（传进来的 + 这一趟新长出来的），
 * {@code response} 是最后一次模型回答，{@code stopReason} 只在 STOP_WHEN 时有值。
 */
public record AgentResult(AgentStatus status, List<Message> messages, ModelTurn response, Usage usage,
                          List<Step> steps, int rounds, String stopReason) {
    public String text() { return response == null ? "" : response.message().text(); }
}
