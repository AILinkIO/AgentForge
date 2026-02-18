package io.ailink.agentforge.service;

import io.ailink.agentforge.entity.ChatMessageEntity;
import io.ailink.agentforge.entity.DailySummaryEntity;
import io.ailink.agentforge.llm.ChatMessage;
import io.ailink.agentforge.llm.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.repository.ChatMessageRepository;
import io.ailink.agentforge.repository.DailySummaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private final ChatMessageRepository chatMessageRepository;
    private final DailySummaryRepository dailySummaryRepository;
    private final LlmProvider llmProvider;

    public ChatHistoryService(ChatMessageRepository chatMessageRepository,
                               DailySummaryRepository dailySummaryRepository,
                               LlmProvider llmProvider) {
        this.chatMessageRepository = chatMessageRepository;
        this.dailySummaryRepository = dailySummaryRepository;
        this.llmProvider = llmProvider;
    }

    public ChatMessageEntity saveUserMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("user", content);
        return chatMessageRepository.save(message);
    }

    public ChatMessageEntity saveAssistantMessage(String content) {
        ChatMessageEntity message = new ChatMessageEntity("assistant", content);
        return chatMessageRepository.save(message);
    }

    public List<ChatMessageEntity> getAllMessages() {
        return chatMessageRepository.findAllByOrderByCreatedAtAsc();
    }

    public List<ChatMessageEntity> getRecentChatMessages(int limit) {
        List<ChatMessageEntity> allMessages = chatMessageRepository.findAllByOrderByCreatedAtDesc();
        List<ChatMessageEntity> reversed = allMessages.reversed();
        int start = Math.max(0, reversed.size() - limit);
        return reversed.subList(start, reversed.size());
    }

    public List<ChatMessageEntity> getMessagesByDate(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        return chatMessageRepository.findByDateRange(startOfDay, endOfDay);
    }

    public long getMessageCountByDate(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
        return chatMessageRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(startOfDay, endOfDay);
    }

    public long getTotalMessageCount() {
        return chatMessageRepository.count();
    }

    public DailySummaryEntity generateDailySummary(LocalDate date) {
        List<ChatMessageEntity> messages = getMessagesByDate(date);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("No messages found for date: " + date);
        }

        String conversationText = messages.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n\n"));

        String summarizationPrompt = String.format(
                "你是一个对话总结专家。请简洁地总结以下对话的要点：\n1. 用户主要询问了什么问题？\n2. AI给出了什么关键回答？\n请用2-3句话总结。\n\n对话内容：\n%s",
                conversationText);

        ChatRequest request = ChatRequest.builder()
                .system("你是一个对话总结专家。")
                .messages(List.of(ChatMessage.user(summarizationPrompt)))
                .build();

        StringBuilder summaryBuilder = new StringBuilder();
        llmProvider.chatStream(request)
                .doOnNext(chunk -> summaryBuilder.append(chunk))
                .blockLast();

        String summaryText = summaryBuilder.toString().trim();

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

    public DailySummaryEntity getDailySummary(LocalDate date) {
        return dailySummaryRepository.findBySummaryDate(date).orElse(null);
    }

    public List<DailySummaryEntity> getAllDailySummaries() {
        return dailySummaryRepository.findAllByOrderBySummaryDateDesc();
    }
}
