package io.ailink.agentforge.service;

import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.entity.DailySummaryEntity;
import io.ailink.agentforge.persistence.repository.ChatMessageRepository;
import io.ailink.agentforge.persistence.repository.DailySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

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
 * - ChatMessageRepository: 消息数据访问
 * - DailySummaryRepository: 总结数据访问
 * - LlmProvider: LLM服务（用于生成总结）
 */
@Service
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);

    private final ChatMessageRepository chatMessageRepository;
    private final DailySummaryRepository dailySummaryRepository;
    private final LlmProvider llmProvider;

    /**
     * 构造函数，Spring 自动注入依赖
     *
     * @param chatMessageRepository    消息数据访问接口
     * @param dailySummaryRepository   总结数据访问接口
     * @param llmProvider            LLM服务接口
     */
    public ChatHistoryServiceImpl(ChatMessageRepository chatMessageRepository,
                                  DailySummaryRepository dailySummaryRepository,
                                  LlmProvider llmProvider) {
        this.chatMessageRepository = chatMessageRepository;
        this.dailySummaryRepository = dailySummaryRepository;
        this.llmProvider = llmProvider;
    }

    /**
     * 保存用户消息
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    @Override
    @Transactional
    public ChatMessageEntity saveUserMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("user", content);
        return chatMessageRepository.save(message);
    }

    /**
     * 保存助手回复
     *
     * @param content 消息内容
     * @return 保存后的消息实体
     */
    @Override
    @Transactional
    public ChatMessageEntity saveAssistantMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("assistant", content);
        return chatMessageRepository.save(message);
    }

    /**
     * 获取所有消息
     *
     * @return 按创建时间升序排列的消息列表
     */
    @Override
    public List<ChatMessageEntity> getAllMessages() {
        return chatMessageRepository.findAllByOrderByCreatedAtAsc();
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
        // 获取所有消息并按时间倒序
        List<ChatMessageEntity> allMessages = chatMessageRepository.findAllByOrderByCreatedAtDesc();
        // 翻转成正序
        List<ChatMessageEntity> reversed = allMessages.reversed();
        // 取最后 limit 条
        int start = Math.max(0, reversed.size() - limit);
        return reversed.subList(start, reversed.size());
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
        return chatMessageRepository.findByDateRange(startOfDay, endOfDay);
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
        return chatMessageRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(startOfDay, endOfDay);
    }

    /**
     * 获取总消息数量
     *
     * @return 数据库中所有消息的数量
     */
    @Override
    public long getTotalMessageCount() {
        return chatMessageRepository.count();
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
    @Transactional
    public DailySummaryEntity generateDailySummary(LocalDate date) {
        // 获取当天的所有消息
        List<ChatMessageEntity> messages = getMessagesByDate(date);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages found for date: " + date);
        }

        // 将对话格式化为文本
        String conversationText = messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n\n"));

        // 构建总结提示词
        String summarizationPrompt = String.format(
                "你是一个对话总结专家。请简洁地总结以下对话的要点：\n1. 用户主要询问了什么问题？\n2. AI给出了什么关键回答？\n请用2-3句话总结。\n\n对话内容：\n%s",
                conversationText);

        // 调用 LLM 生成总结
        ChatRequest request = ChatRequest.builder()
                .system("你是一个对话总结专家。")
                .messages(List.of(ChatMessage.user(summarizationPrompt)))
                .build();

        StringBuilder summaryBuilder = new StringBuilder();
        llmProvider.chatStream(request)
                .doOnNext(chunk -> summaryBuilder.append(chunk))
                .blockLast();

        String summaryText = summaryBuilder.toString().trim();

        // 检查是否已存在总结，存在则更新，不存在则创建
        DailySummaryEntity existing = dailySummaryRepository.findBySummaryDate(date).orElse(null);
        if (existing != null) {
            existing.setSummary(summaryText);
            existing.setMessageCount(messages.size());
            existing.setUpdatedAt(LocalDateTime.now());
            return dailySummaryRepository.save(existing);
        } else {
            DailySummaryEntity newSummary = new DailySummaryEntity(date, summaryText, messages.size());
            return dailySummaryRepository.save(newSummary);
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
        return dailySummaryRepository.findBySummaryDate(date).orElse(null);
    }

    /**
     * 获取所有每日总结
     *
     * @return 按日期倒序排列的总结列表
     */
    @Override
    public List<DailySummaryEntity> getAllDailySummaries() {
        return dailySummaryRepository.findAllByOrderBySummaryDateDesc();
    }
}
