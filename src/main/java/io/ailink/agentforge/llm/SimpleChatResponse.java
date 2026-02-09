package io.ailink.agentforge.llm;

public record SimpleChatResponse(
        String id, String content, String model,
        String stopReason, TokenUsage usage
) implements ChatResponse<Void> {
    @Override
    public Void rawResponse() {
        return null;
    }
}
