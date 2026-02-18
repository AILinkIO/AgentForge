package io.ailink.agentforge.llm;

import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.dto.TokenUsage;

/**
 * 简单聊天响应实现
 *
 * 提供基本的响应数据封装。
 */
public record SimpleChatResponse(
        String id,
        String content,
        String model,
        String stopReason,
        TokenUsage usage
) implements ChatResponse<Void> {

    /**
     * 获取原始响应
     *
     * @return null（简单实现不保留原始响应）
     */
    @Override
    public Void rawResponse() {
        return null;
    }
}
