package io.ailink.agentforge.llm;

import java.util.List;

public record ChatRequest(
        List<ChatMessage> messages,
        String model,
        String system,
        Integer maxTokens,
        Double temperature
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

        public ChatRequest build() {
            return new ChatRequest(messages, model, system, maxTokens, temperature);
        }
    }
}
