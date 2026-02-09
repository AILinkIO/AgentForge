package io.ailink.agentforge.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.*;
import io.ailink.agentforge.llm.openai.dto.OpenAiRequest;
import io.ailink.agentforge.llm.openai.dto.OpenAiResponse;
import io.ailink.agentforge.llm.openai.dto.OpenAiStreamEvent;
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
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private final WebClient webClient;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;

    public OpenAiProvider(WebClient webClient, OpenAiProperties properties, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return chatAsync(request).block();
    }

    @Override
    public Mono<ChatResponse> chatAsync(ChatRequest request) {
        OpenAiRequest openAiRequest = toOpenAiRequest(request, false);

        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getChatPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(openAiRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "OpenAI API error " + response.statusCode() + ": " + body))))
                .bodyToMono(OpenAiResponse.class)
                .map(this::toChatResponse);
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        OpenAiRequest openAiRequest = toOpenAiRequest(request, true);

        return webClient.post()
                .uri(properties.getBaseUrl() + properties.getChatPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(openAiRequest)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> Mono.error(new RuntimeException(
                                        "OpenAI API error " + response.statusCode() + ": " + body))))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .mapNotNull(sse -> {
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
                });
    }

    private OpenAiRequest toOpenAiRequest(ChatRequest request, boolean stream) {
        List<OpenAiRequest.Message> messages = new ArrayList<>();

        if (request.system() != null) {
            messages.add(new OpenAiRequest.Message("system", request.system()));
        }
        for (ChatMessage m : request.messages()) {
            messages.add(new OpenAiRequest.Message(m.role(), m.content()));
        }

        String model = request.model() != null ? request.model() : properties.getDefaultModel();
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : properties.getDefaultMaxTokens();

        return new OpenAiRequest(
                model,
                messages,
                maxTokens,
                request.temperature(),
                stream ? true : null
        );
    }

    private ChatResponse toChatResponse(OpenAiResponse response) {
        String content = "";
        String finishReason = null;

        if (response.choices() != null && !response.choices().isEmpty()) {
            var choice = response.choices().getFirst();
            content = choice.message() != null ? choice.message().content() : "";
            finishReason = choice.finishReason();
        }

        TokenUsage usage = response.usage() != null
                ? new TokenUsage(response.usage().promptTokens(), response.usage().completionTokens())
                : null;

        return new ChatResponse(
                response.id(),
                content,
                response.model(),
                finishReason,
                usage
        );
    }
}
