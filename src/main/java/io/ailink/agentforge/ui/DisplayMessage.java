package io.ailink.agentforge.ui;

/**
 * 显示消息数据对象
 *
 * 用于在终端界面中显示的聊天消息。
 * 包含消息角色、内容和时间信息。
 */
public record DisplayMessage(
        String role,
        String content,
        String time
) {

    /**
     * 判断是否为用户消息
     *
     * @return true 如果是用户消息
     */
    public boolean isUser() {
        return "user".equals(role());
    }

    /**
     * 判断是否为助手消息
     *
     * @return true 如果是助手消息
     */
    public boolean isAssistant() {
        return "assistant".equals(role());
    }
}
