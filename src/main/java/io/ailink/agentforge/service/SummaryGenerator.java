package io.ailink.agentforge.service;

import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.persistence.entity.ChatMessageEntity;
import io.ailink.agentforge.persistence.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(SummaryGenerator.class);

    private final LlmProvider llmProvider;
    private final ChatMessageRepository chatMessageRepository;

    public SummaryGenerator(LlmProvider llmProvider,
                            ChatMessageRepository chatMessageRepository) {
        this.llmProvider = llmProvider;
        this.chatMessageRepository = chatMessageRepository;
    }

    public String generateSummary(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("Messages list cannot be empty");
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

        return summaryBuilder.toString().trim();
    }
}
