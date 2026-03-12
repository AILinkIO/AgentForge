package io.ailink.agentforge.llm.dto;

import io.ailink.agentforge.tool.ToolCall;

import java.util.List;

public interface ChatResponse<R> {

    String id();

    String content();

    String model();

    String stopReason();

    TokenUsage usage();

    List<ToolCall> toolCalls();

    R rawResponse();

    boolean hasToolCalls();

    static ChatResponse<Void> of(String id, String content, String model,
                                  String stopReason, TokenUsage usage) {
        return new io.ailink.agentforge.llm.SimpleChatResponse(id, content, model, stopReason, usage);
    }
}
