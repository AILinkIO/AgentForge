package io.ailink.agentforge.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.AbstractLlmProvider;
import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.openai.dto.OpenAiRequest;
import io.ailink.agentforge.llm.openai.dto.OpenAiResponse;
import io.ailink.agentforge.llm.openai.dto.OpenAiStreamEvent;
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
import java.util.List;

@Component
@Qualifier("openai")
public class OpenAiProvider extends AbstractLlmProvider<OpenAiRequest, OpenAiResponse> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private final OpenAiProperties properties;

    public OpenAiProvider(WebClient webClient, OpenAiProperties properties, ObjectMapper objectMapper) {
        super(webClient, objectMapper, log);
        this.properties = properties;
    }

    @Override
    protected OpenAiRequest convertRequest(ChatRequest request, boolean stream) {
        List<OpenAiRequest.Message> messages = new ArrayList<>();

        if (request.system() != null) {
            messages.add(OpenAiRequest.Message.text("system", request.system()));
        }

        for (ChatMessage m : request.messages()) {
            OpenAiRequest.Message openAiMsg = switch (m) {
                case ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId)
                        when "tool".equals(role) ->
                    OpenAiRequest.Message.toolResult(toolCallId, content);
                case ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId)
                        when toolCalls != null && !toolCalls.isEmpty() -> {
                    List<OpenAiRequest.ToolCall> openAiToolCalls = new ArrayList<>();
                    for (var tc : toolCalls) {
                        String argsJson;
                        try {
                            argsJson = objectMapper.writeValueAsString(tc.arguments());
                        } catch (JsonProcessingException e) {
                            argsJson = "{}";
                        }
                        openAiToolCalls.add(new OpenAiRequest.ToolCall(tc.id(), tc.name(), argsJson));
                    }
                    yield OpenAiRequest.Message.assistantWithTools(content, openAiToolCalls);
                }
                default -> OpenAiRequest.Message.text(m.role(), m.content());
            };
            messages.add(openAiMsg);
        }

        String model = request.model() != null ? request.model() : properties.getDefaultModel();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.getDefaultMaxTokens();

        List<OpenAiRequest.Tool> tools = null;
        if (request.hasTools()) {
            tools = request.tools().stream()
                    .map(td -> new OpenAiRequest.Tool(
                            td.name(),
                            td.description(),
                            toJsonMap(td.inputSchema())))
                    .toList();
        }

        return new OpenAiRequest(
                model,
                messages,
                maxTokens,
                request.temperature(),
                stream ? true : null,
                tools
        );
    }

    @Override
    protected ChatResponse<OpenAiResponse> convertResponse(OpenAiResponse rawResponse) {
        return new OpenAiChatResponse(rawResponse);
    }

    @Override
    protected Mono<OpenAiResponse> executeRequest(OpenAiRequest providerRequest) {
        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getApiPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "OpenAI API error " + response.statusCode() + ": " + body))))
                .bodyToMono(OpenAiResponse.class);
    }

    @Override
    protected Flux<ServerSentEvent<String>> executeStreamRequest(OpenAiRequest providerRequest) {
        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getApiPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "OpenAI API error " + response.statusCode() + ": " + body))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                });
    }

    @Override
    protected boolean shouldProcessEvent(ServerSentEvent<String> sse) {
        String data = sse.data();
        return data != null && !"[DONE]".equals(data);
    }

    @Override
    protected String extractStreamContent(ServerSentEvent<String> sse) {
        String data = sse.data();
        if (data == null || "[DONE]".equals(data)) {
            return null;
        }
        try {
            OpenAiStreamEvent event = objectMapper.readValue(data, OpenAiStreamEvent.class);
            if (event.choices() != null && !event.choices().isEmpty()) {
                var delta = event.choices().getFirst().delta();
                if (delta != null) {
                    return delta.content();
                }
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
