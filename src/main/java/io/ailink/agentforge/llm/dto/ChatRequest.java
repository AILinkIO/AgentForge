package io.ailink.agentforge.llm.dto;

import io.ailink.agentforge.tool.ToolCall;
import io.ailink.agentforge.tool.ToolDefinition;

import java.util.List;

/**
 * 聊天请求数据传输对象
 */
public record ChatRequest(
        List<ChatMessage> messages,
        String model,
        String system,
        Integer maxTokens,
        Double temperature,
        List<ToolDefinition> tools
) {

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<ChatMessage> messages;
        private String model;
        private String system;
        private Integer maxTokens;
        private Double temperature;
        private List<ToolDefinition> tools;

        public Builder messages(List<ChatMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder system(String system) {
            this.system = system;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(messages, model, system, maxTokens, temperature, tools);
        }
    }

    public boolean hasTools() {
        return tools != null && !tools.isEmpty();
    }
}
