package io.ailink.agentforge.llm;

import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.dto.TokenUsage;
import io.ailink.agentforge.tool.ToolCall;

import java.util.Collections;
import java.util.List;

public record SimpleChatResponse(
        String id,
        String content,
        String model,
        String stopReason,
        TokenUsage usage,
        List<ToolCall> toolCalls
) implements ChatResponse<Void> {

    public SimpleChatResponse(String id, String content, String model, String stopReason, TokenUsage usage) {
        this(id, content, model, stopReason, usage, Collections.emptyList());
    }

    @Override
    public Void rawResponse() {
        return null;
    }

    @Override
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
