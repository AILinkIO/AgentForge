package io.ailink.agentforge.persistence.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日总结实体
 *
 * 用于存储每日对话的总结信息。需要手动触发生成。
 *
 * 字段说明：
 * - id: 总结唯一标识符
 * - summaryDate: 总结对应的日期（唯一索引，每天一条）
 * - summary: LLM生成的对话总结
 * - messageCount: 当天的消息数量
 * - createdAt: 总结首次创建时间
 * - updatedAt: 总结最后更新时间
 */
@Entity
@Table(name = "daily_summaries")
public class DailySummaryEntity {

    /**
     * 总结唯一标识符
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 总结对应的日期（唯一）
     */
    @Column(nullable = false, unique = true)
    private LocalDate summaryDate;

    /**
     * LLM生成的对话总结
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    /**
     * 当天的消息数量
     */
    @Column(nullable = false)
    private Integer messageCount;

    /**
     * 总结首次创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 总结最后更新时间
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 默认构造函数，JPA Required
     */
    public DailySummaryEntity() {
    }

    /**
     * 创建每日总结
     *
     * @param summaryDate   总结日期
     * @param summary       对话总结内容
     * @param messageCount  消息数量
     */
    public DailySummaryEntity(LocalDate summaryDate, String summary, Integer messageCount) {
        this.summaryDate = summaryDate;
        this.summary = summary;
        this.messageCount = messageCount;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // ==================== Getter/Setter ====================

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public LocalDate getSummaryDate() {
        return summaryDate;
    }

    public void setSummaryDate(LocalDate summaryDate) {
        this.summaryDate = summaryDate;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
