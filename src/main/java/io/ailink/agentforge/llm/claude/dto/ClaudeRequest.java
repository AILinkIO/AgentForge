package io.ailink.agentforge.llm.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeRequest(
        String model,
        @JsonProperty("max_tokens") int maxTokens,
        List<Message> messages,
        String system,
        Double temperature,
        Boolean stream
) {

    public record Message(String role, String content) {
    }
}
