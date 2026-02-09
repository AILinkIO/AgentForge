package io.ailink.agentforge.llm;

public record ChatResponse(
        String id,
        String content,
        String model,
        String stopReason,
        TokenUsage usage
) {
}
