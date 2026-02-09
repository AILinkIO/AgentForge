package io.ailink.agentforge.llm;

public interface ChatResponse<R> {
    String id();
    String content();
    String model();
    String stopReason();
    TokenUsage usage();
    R rawResponse();

    static ChatResponse<Void> of(String id, String content, String model,
                                  String stopReason, TokenUsage usage) {
        return new SimpleChatResponse(id, content, model, stopReason, usage);
    }
}
