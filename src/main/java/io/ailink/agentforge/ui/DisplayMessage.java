package io.ailink.agentforge.ui;

/**
 * 显示消息数据对象
 *
 * 用于在终端界面中显示的聊天消息。
 * 包含消息角色、内容和时间信息。
 */
public class DisplayMessage {

    /**
     * 消息角色：user(用户) 或 assistant(助手)
     */
    private final String role;

    /**
     * 消息内容
     */
    private final String content;

    /**
     * 消息时间（字符串格式）
     */
    private final String time;

    /**
     * 创建显示消息
     *
     * @param role    消息角色
     * @param content 消息内容
     * @param time    消息时间
     */
    public DisplayMessage(String role, String content, String time) {
        this.role = role;
        this.content = content;
        this.time = time;
    }

    /**
     * 判断是否为用户消息
     *
     * @return true 如果是用户消息
     */
    public boolean isUser() {
        return "user".equals(role);
    }

    /**
     * 判断是否为助手消息
     *
     * @return true 如果是助手消息
     */
    public boolean isAssistant() {
        return "assistant".equals(role);
    }

    // ==================== Getter ====================

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getTime() {
        return time;
    }
}
