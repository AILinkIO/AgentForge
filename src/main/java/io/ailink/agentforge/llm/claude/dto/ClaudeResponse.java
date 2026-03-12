package io.ailink.agentforge.llm.claude.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaudeResponse(
        String id,
        String model,
        @JsonProperty("stop_reason") String stopReason,
        List<ContentBlock> content,
        Usage usage
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContentBlock(
            String type,
            String text,
            @JsonProperty("tool_use_id") String toolUseId,
            String id,
            String name,
            Map<String, Object> input
    ) {
        public static ContentBlock text(String text) {
            return new ContentBlock("text", text, null, null, null, null);
        }

        public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
            return new ContentBlock("tool_use", null, null, id, name, input);
        }

        public static ContentBlock toolResult(String toolUseId, String content) {
            return new ContentBlock("tool_result", content, toolUseId, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) {
    }
}
