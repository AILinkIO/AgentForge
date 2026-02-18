package io.ailink.agentforge.service;

import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.entity.DailySummaryEntity;

import java.time.LocalDate;
import java.util.List;

/**
 * 聊天历史服务接口
 *
 * 定义聊天历史管理的业务操作。
 */
public interface ChatHistoryService {

    /**
     * 保存用户消息
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    ChatMessageEntity saveUserMessage(String content);

    /**
     * 保存助手回复
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    ChatMessageEntity saveAssistantMessage(String content);

    /**
     * 获取所有消息
     *
     * @return 按创建时间升序排列的消息列表
     */
    List<ChatMessageEntity> getAllMessages();

    /**
     * 获取最近的聊天消息
     *
     * @param limit 返回的消息数量限制
     * @return 最近的消息列表（按时间升序）
     */
    List<ChatMessageEntity> getRecentChatMessages(int limit);

    /**
     * 按日期查询消息
     *
     * @param date 查询日期
     * @return 当天的消息列表
     */
    List<ChatMessageEntity> getMessagesByDate(LocalDate date);

    /**
     * 统计指定日期的消息数量
     *
     * @param date 查询日期
     * @return 当天的消息数量
     */
    long getMessageCountByDate(LocalDate date);

    /**
     * 获取总消息数量
     *
     * @return 数据库中所有消息的数量
     */
    long getTotalMessageCount();

    /**
     * 生成每日总结
     *
     * @param date 要总结的日期
     * @return 生成的总结实体
     */
    DailySummaryEntity generateDailySummary(LocalDate date);

    /**
     * 获取指定日期的总结
     *
     * @param date 查询日期
     * @return 找到的总结实体，不存在则返回 null
     */
    DailySummaryEntity getDailySummary(LocalDate date);

    /**
     * 获取所有每日总结
     *
     * @return 按日期倒序排列的总结列表
     */
    List<DailySummaryEntity> getAllDailySummaries();
}
