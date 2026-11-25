package io.ailink.agentforge.persistence.entity;

import java.time.LocalDateTime;

/**
 * 聊天消息实体
 *
 * 用于存储对话中的每一条消息记录，包括用户消息和助手回复。
 * 消息永久保存，不删除。
 *
 * 字段说明：
 * - id: 消息唯一标识符，单调递增（bitcask 里的计数键）
 * - role: 消息角色，user(用户) 或 assistant(助手)
 * - content: 消息内容，TEXT类型支持长文本
 * - createdAt: 消息创建时间，精确到毫秒
 *
 * 存储：bitcask，键 msg/<20 位 id>，值为 JSON（见 BitcaskHistoryStore）。
 */
public class ChatMessageEntity {

    /**
     * 消息唯一标识符
     */

    private Long id;

    /**
     * 消息角色：user(用户) 或 assistant(助手)
     */
    private String role;

    /**
     * 消息内容，支持长文本
     */
    private String content;

    /**
     * 消息创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 默认构造函数，JPA Required
     */
    public ChatMessageEntity() {
    }

    /**
     * 创建聊天消息
     *
     * @param role    消息角色：user 或 assistant
     * @param content 消息内容
     */
    public ChatMessageEntity(String role, String content) {
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }

    // ==================== Getter/Setter ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
