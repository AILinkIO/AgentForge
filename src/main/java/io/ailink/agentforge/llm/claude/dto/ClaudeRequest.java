package io.ailink.agentforge.llm.claude.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaudeRequest(
        String model,
        @JsonProperty("max_tokens") int maxTokens,
        List<Message> messages,
        String system,
        Double temperature,
        Boolean stream,
        List<Tool> tools
) {

    public record Message(String role, Object content) {
        public static Message text(String role, String text) {
            return new Message(role, text);
        }

        public static Message toolResult(String toolUseId, String content) {
            return new Message("user", List.of(
                    new ToolResultContent(toolUseId, content)
            ));
        }
    }

    public record ToolResultContent(
            String type,
            @JsonProperty("tool_use_id") String toolUseId,
            String content
    ) {
        public ToolResultContent(String toolUseId, String content) {
            this("tool_result", toolUseId, content);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tool(
            String name,
            String description,
            @JsonProperty("input_schema") Map<String, Object> inputSchema
    ) {}
}
