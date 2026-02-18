package io.ailink.agentforge.llm.dto;

/**
 * 聊天消息数据传输对象
 *
 * 用于在 LLM 请求和响应中传递消息。
 *
 * @param role    消息角色：user(用户), assistant(助手), system(系统)
 * @param content 消息内容
 */
public record ChatMessage(String role, String content) {

    /**
     * 创建用户消息
     *
     * @param content 消息内容
     * @return 用户消息
     */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /**
     * 创建助手消息
     *
     * @param content 消息内容
     * @return 助手消息
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /**
     * 创建系统消息
     *
     * @param content 消息内容
     * @return 系统消息
     */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}
