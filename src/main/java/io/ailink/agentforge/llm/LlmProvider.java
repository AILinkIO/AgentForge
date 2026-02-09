package io.ailink.agentforge.llm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LlmProvider {

    ChatResponse chat(ChatRequest request);

    Mono<ChatResponse> chatAsync(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);
}
