package io.ailink.agentforge.cli.chat;

/**
 * 聊天事件监听器
 *
 * 用于在聊天处理过程中接收工具调用和流式输出事件的回调。
 */
public interface ChatEventListener {

    /**
     * 工具开始执行
     *
     * @param toolName 工具名称
     */
    void onToolExecuting(String toolName);

    /**
     * 工具执行完成
     *
     * @param toolName 工具名称
     * @param result   执行结果
     */
    void onToolResult(String toolName, String result);

    /**
     * 流式输出开始
     */
    void onStreamingStart();

    /**
     * 收到流式输出的文本片段
     *
     * @param token 文本片段
     */
    void onStreamingToken(String token);

    /**
     * 流式输出结束
     */
    void onStreamingEnd();
}
