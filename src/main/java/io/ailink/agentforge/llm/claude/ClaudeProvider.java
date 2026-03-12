package io.ailink.agentforge.llm.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.AbstractLlmProvider;
import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.claude.dto.ClaudeRequest;
import io.ailink.agentforge.llm.claude.dto.ClaudeResponse;
import io.ailink.agentforge.llm.claude.dto.ClaudeStreamEvent;
import io.ailink.agentforge.tool.ToolCall;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Qualifier("claude")
public class ClaudeProvider extends AbstractLlmProvider<ClaudeRequest, ClaudeResponse> {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProvider.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ClaudeProperties properties;

    public ClaudeProvider(WebClient webClient, ClaudeProperties properties, ObjectMapper objectMapper) {
        super(webClient, objectMapper, log);
        this.properties = properties;
    }

    @Override
    protected ClaudeRequest convertRequest(ChatRequest request, boolean stream) {
        List<ClaudeRequest.Message> messages = new ArrayList<>();

        for (var m : request.messages()) {
            ClaudeRequest.Message claudeMsg = switch (m) {
                case ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) 
                        when "tool".equals(role) -> 
                    ClaudeRequest.Message.toolResult(toolCallId, content);
                case ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) 
                        when toolCalls != null && !toolCalls.isEmpty() -> {
                    List<Map<String, Object>> contentList = new ArrayList<>();
                    if (content != null && !content.isBlank()) {
                        contentList.add(Map.of("type", "text", "text", content));
                    }
                    for (var tc : toolCalls) {
                        Map<String, Object> toolUse = new HashMap<>();
                        toolUse.put("type", "tool_use");
                        toolUse.put("id", tc.id());
                        toolUse.put("name", tc.name());
                        toolUse.put("input", toJsonMap(tc.arguments()));
                        contentList.add(toolUse);
                    }
                    yield new ClaudeRequest.Message("assistant", contentList);
                }
                default -> ClaudeRequest.Message.text(m.role(), m.content());
            };
            messages.add(claudeMsg);
        }

        String model = request.model() != null ? request.model() : properties.getDefaultModel();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.getDefaultMaxTokens();

        List<ClaudeRequest.Tool> tools = null;
        if (request.hasTools()) {
            tools = request.tools().stream()
                    .map(td -> new ClaudeRequest.Tool(
                            td.name(),
                            td.description(),
                            toJsonMap(td.inputSchema())))
                    .toList();
        }

        return new ClaudeRequest(
                model,
                maxTokens,
                messages,
                request.system(),
                request.temperature(),
                stream ? true : null,
                tools
        );
    }

    @Override
    protected ChatResponse<ClaudeResponse> convertResponse(ClaudeResponse rawResponse) {
        return new ClaudeChatResponse(rawResponse);
    }

    @Override
    protected Mono<ClaudeResponse> executeRequest(ClaudeRequest providerRequest) {
        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getApiPath())
                .header("x-api-key", properties.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Claude API error " + response.statusCode() + ": " + body))))
                .bodyToMono(ClaudeResponse.class);
    }

    @Override
    protected Flux<ServerSentEvent<String>> executeStreamRequest(ClaudeRequest providerRequest) {
        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getApiPath())
                .header("x-api-key", properties.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "Claude API error " + response.statusCode() + ": " + body))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
    }

    @Override
    protected boolean shouldProcessEvent(ServerSentEvent<String> sse) {
        return "content_block_delta".equals(sse.event());
    }

    @Override
    protected String extractStreamContent(ServerSentEvent<String> sse) {
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
    }

    @Override
    protected String getEndpoint() {
        return properties.getBaseUrl() + properties.getApiPath();
    }
}
