package io.ailink.agentforge.llm.dto;

import java.util.List;

/**
 * 聊天请求数据传输对象
 *
 * 封装发送给 LLM 的请求参数。
 *
 * @param messages   消息列表
 * @param model      模型名称
 * @param system    系统提示词
 * @param maxTokens 最大生成 token 数量
 * @param temperature 温度参数（0-2），越高越随机
 */
public record ChatRequest(
        List<ChatMessage> messages,
        String model,
        String system,
        Integer maxTokens,
        Double temperature
) {

    /**
     * 创建请求构建器
     *
     * @return 请求构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 请求构建器
     */
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
