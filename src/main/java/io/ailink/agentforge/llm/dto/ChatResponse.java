package io.ailink.agentforge.llm.dto;

/**
 * 聊天响应接口
 *
 * 定义 LLM 响应的基本结构。
 *
 * @param <R> 原始响应类型
 */
public interface ChatResponse<R> {

    /**
     * 获取响应 ID
     *
     * @return 响应 ID
     */
    String id();

    /**
     * 获取响应内容
     *
     * @return 文本内容
     */
    String content();

    /**
     * 获取使用的模型
     *
     * @return 模型名称
     */
    String model();

    /**
     * 获取停止原因
     *
     * @return 停止原因（如 end_of_turn, max_tokens 等）
     */
    String stopReason();

    /**
     * 获取 Token 使用统计
     *
     * @return Token 使用情况
     */
    TokenUsage usage();

    /**
     * 获取原始响应对象
     *
     * @return 原始响应
     */
    R rawResponse();

    /**
     * 创建简单响应
     *
     * @param id         响应 ID
     * @param content    文本内容
     * @param model      模型名称
     * @param stopReason 停止原因
     * @param usage      Token 使用统计
     * @return 响应对象
     */
    static ChatResponse<Void> of(String id, String content, String model,
                                  String stopReason, TokenUsage usage) {
        return new io.ailink.agentforge.llm.SimpleChatResponse(id, content, model, stopReason, usage);
    }
}
