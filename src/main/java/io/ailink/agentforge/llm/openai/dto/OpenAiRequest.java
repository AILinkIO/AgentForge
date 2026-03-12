package io.ailink.agentforge.llm.openai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiRequest(
        String model,
        List<Message> messages,
        @JsonProperty("max_tokens") Integer maxTokens,
        Double temperature,
        Boolean stream,
        List<Tool> tools
) {

    public record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public static Message text(String role, String content) {
            return new Message(role, content, null, null);
        }

        public static Message toolResult(String toolCallId, String content) {
            return new Message("tool", content, toolCallId, null);
        }

        public static Message assistantWithTools(String content, List<ToolCall> toolCalls) {
            return new Message("assistant", content, null, toolCalls);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(
            String id,
            String type,
            Function function
    ) {
        public ToolCall(String id, String name, String arguments) {
            this(id, "function", new Function(name, arguments));
        }
    }

    public record Function(String name, String arguments) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Tool(
            String type,
            FunctionDef function
    ) {
        public Tool(String name, String description, Map<String, Object> parameters) {
            this("function", new FunctionDef(name, description, parameters));
        }
    }

    public record FunctionDef(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}
}
