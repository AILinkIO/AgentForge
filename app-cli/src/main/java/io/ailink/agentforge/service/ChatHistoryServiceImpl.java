package io.ailink.agentforge.service;

import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.entity.DailySummaryEntity;
import io.ailink.agentforge.persistence.store.BitcaskHistoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 聊天历史服务实现
 *
 * 核心业务逻辑模块，负责：
 * - 消息的持久化存储（保存用户和助手消息）
 * - 消息的查询（全部、最近、指定日期）
 * - 消息统计（总数、按日期统计）
 * - 每日总结的生成和查询
 *
 * 依赖组件：
 * - BitcaskHistoryStore: 消息与总结的 bitcask 存储
 * - SummaryGenerator: LLM总结生成（关注点分离）
 */
@Service
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);

    private final BitcaskHistoryStore store;
    private final SummaryGenerator summaryGenerator;

    /**
     * 构造函数，Spring 自动注入依赖
     *
     * @param store                 bitcask 存储
     * @param summaryGenerator      总结生成器
     */
    public ChatHistoryServiceImpl(BitcaskHistoryStore store, SummaryGenerator summaryGenerator) {
        this.store = store;
        this.summaryGenerator = summaryGenerator;
    }

    /**
     * 保存用户消息
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    @Override
    public ChatMessageEntity saveUserMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("user", content);
        return store.save(message);
    }

    /**
     * 保存助手回复
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    @Override
    public ChatMessageEntity saveAssistantMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("assistant", content);
        return store.save(message);
    }

    /**
     * 获取所有消息
     *
     * @return 按创建时间升序排列的消息列表
     */
    @Override
    public List<ChatMessageEntity> getAllMessages() {
        return store.findAll();
    }

    /**
     * 获取最近的聊天消息
     *
     * 用于构建对话上下文。返回最新的 limit 条消息。
     *
     * @param limit 返回的消息数量限制
     * @return 最近的消息列表（按时间升序）
     */
    @Override
    public List<ChatMessageEntity> getRecentChatMessages(int limit) {
        return store.findRecent(limit);
    }

    /**
     * 按日期查询消息
     *
     * @param date 查询日期
     * @return 当天的消息列表
     */
    @Override
    public List<ChatMessageEntity> getMessagesByDate(LocalDate date) {
        // 一天的开始：00:00:00
        LocalDateTime startOfDay = date.atStartOfDay();
        // 一天的结束：23:59:59.999999999
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        return store.findBetween(startOfDay, endOfDay);
    }

    /**
     * 统计指定日期的消息数量
     *
     * @param date 查询日期
     * @return 当天的消息数量
     */
    @Override
    public long getMessageCountByDate(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        return store.findBetween(startOfDay, endOfDay).size();
    }

    /**
     * 获取总消息数量
     *
     * @return 数据库中所有消息的数量
     */
    @Override
    public long getTotalMessageCount() {
        return store.count();
    }

    /**
     * 生成每日总结
     *
     * 使用 LLM 对指定日期的对话进行总结。
     * 如果当天已有总结，则更新现有总结。
     *
     * @param date 要总结的日期
     * @return 生成的总结实体
     */
    @Override
    public DailySummaryEntity generateDailySummary(LocalDate date) {
        List<ChatMessageEntity> messages = getMessagesByDate(date);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages found for date: " + date);
        }

        String summaryText = summaryGenerator.generateSummary(messages);

        DailySummaryEntity existing = store.findSummary(date).orElse(null);
        if (existing != null) {
            existing.setSummary(summaryText);
            existing.setMessageCount(messages.size());
            existing.setUpdatedAt(LocalDateTime.now());
            return store.saveSummary(existing);
        } else {
            DailySummaryEntity newSummary = new DailySummaryEntity(date, summaryText, messages.size());
            return store.saveSummary(newSummary);
        }
    }

    /**
     * 获取指定日期的总结
     *
     * @param date 查询日期
     * @return 找到的总结实体，不存在则返回 null
     */
    @Override
    public DailySummaryEntity getDailySummary(LocalDate date) {
        return store.findSummary(date).orElse(null);
    }

    /**
     * 获取所有每日总结
     *
     * @return 按日期倒序排列的总结列表
     */
    @Override
    public List<DailySummaryEntity> getAllDailySummaries() {
        return store.findAllSummaries();
    }
}
