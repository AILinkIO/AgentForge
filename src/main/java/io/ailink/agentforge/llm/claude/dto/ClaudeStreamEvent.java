package io.ailink.agentforge.llm.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeStreamEvent(
        String type,
        Delta delta
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String type, String text) {
    }
}
