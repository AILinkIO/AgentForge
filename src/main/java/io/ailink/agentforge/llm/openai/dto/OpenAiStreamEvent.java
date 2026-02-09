package io.ailink.agentforge.llm.openai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiStreamEvent(
        List<Choice> choices
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Delta delta) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(String content) {
    }
}
