package io.ailink.agentforge.llm.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.claude.dto.ClaudeRequest;
import io.ailink.agentforge.llm.claude.dto.ClaudeResponse;
import io.ailink.agentforge.llm.claude.dto.ClaudeStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@Component
@Qualifier("claude")
public class ClaudeProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final ClaudeProperties properties;
    private final ObjectMapper objectMapper;

    public ClaudeProvider(WebClient webClient, ClaudeProperties properties, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse<?> chat(ChatRequest request) {
        return chatAsync(request).block();
    }

    @Override
    public Mono<ClaudeChatResponse> chatAsync(ChatRequest request) {
        ClaudeRequest claudeRequest = toClaudeRequest(request, false);

        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getMessagesPath())
                .header("x-api-key", properties.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(claudeRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Claude API error " + response.statusCode() + ": " + body))))
                .bodyToMono(ClaudeResponse.class)
                .map(ClaudeChatResponse::new);
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        ClaudeRequest claudeRequest = toClaudeRequest(request, true);

        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getMessagesPath())
                .header("x-api-key", properties.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(claudeRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Claude API error " + response.statusCode() + ": " + body))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .filter(sse -> "content_block_delta".equals(sse.event()))
                .mapNotNull(sse -> {
                    String data = sse.data();
                    if (data == null) {
                        return null;
                    }
                    try {
                        ClaudeStreamEvent event = objectMapper.readValue(data, ClaudeStreamEvent.class);
                        if (event.delta() != null) {
                            return event.delta().text();
                        }
                        return null;
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse stream event: {}", data, e);
                        return null;
                    }
                });
    }

    private ClaudeRequest toClaudeRequest(ChatRequest request, boolean stream) {
        var messages = request.messages().stream()
                .map(m -> new ClaudeRequest.Message(m.role(), m.content()))
                .collect(Collectors.toList());

        String model = request.model() != null ? request.model() : properties.getDefaultModel();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.getDefaultMaxTokens();

        return new ClaudeRequest(
                model,
                maxTokens,
                messages,
                request.system(),
                request.temperature(),
                stream ? true : null
        );
    }
}
