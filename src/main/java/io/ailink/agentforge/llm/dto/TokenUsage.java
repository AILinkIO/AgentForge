package io.ailink.agentforge.llm.dto;

/**
 * Token 使用统计
 *
 * 记录单次请求的 Token 消耗。
 *
 * @param inputTokens  输入 Token 数量
 * @param outputTokens 输出 Token 数量
 * @param totalTokens 总 Token 数量
 */
public record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {

    /**
     * 创建 Token 使用统计
     *
     * @param inputTokens  输入 Token
     * @param outputTokens 输出 Token
     * @return 统计对象
     */
    public static TokenUsage of(int inputTokens, int outputTokens) {
        return new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens);
    }
}
