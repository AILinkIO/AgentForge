package io.ailink.agentforge.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.llm.dto.TokenUsage;
import io.ailink.agentforge.llm.openai.dto.OpenAiResponse;
import io.ailink.agentforge.tool.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

public class OpenAiChatResponse implements ChatResponse<OpenAiResponse> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatResponse.class);

    private final OpenAiResponse raw;
    private final List<ToolCall> toolCalls;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiChatResponse(OpenAiResponse raw) {
        this.raw = raw;
        this.toolCalls = extractToolCalls(raw);
    }

    @Override
    public String id() {
        return raw.id();
    }

    @Override
    public String content() {
        if (raw.choices() != null && !raw.choices().isEmpty()) {
            var message = raw.choices().getFirst().message();
            return message != null ? message.content() : "";
        }
        return "";
    }

    @Override
    public String model() {
        return raw.model();
    }

    @Override
    public String stopReason() {
        if (raw.choices() != null && !raw.choices().isEmpty()) {
            return raw.choices().getFirst().finishReason();
        }
        return null;
    }

    @Override
    public TokenUsage usage() {
        return raw.usage() != null
                ? TokenUsage.of(raw.usage().promptTokens(), raw.usage().completionTokens())
                : null;
    }

    @Override
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    @Override
    public OpenAiResponse rawResponse() {
        return raw;
    }

    @Override
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    private List<ToolCall> extractToolCalls(OpenAiResponse response) {
        if (response.choices() == null || response.choices().isEmpty()) {
            return Collections.emptyList();
        }
        var message = response.choices().getFirst().message();
        if (message == null || message.toolCalls() == null) {
            return Collections.emptyList();
        }
        return message.toolCalls().stream()
                .map(tc -> {
                    JsonNode args = parseArguments(tc.function().arguments());
                    return new ToolCall(tc.id(), tc.function().name(), args);
                })
                .toList();
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool arguments: {}", arguments, e);
            return objectMapper.createObjectNode();
        }
    }
}
