package io.ailink.agentforge.cli.chat;

import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.tool.builtin.CalculatorTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息处理器
 *
 * 负责调用 LLM、处理响应的核心业务逻辑。
 * 使用 Spring AI ChatClient 自动处理工具调用。
 */
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final ChatClient chatClient;
    private final ChatHistoryService chatHistoryService;

    public MessageProcessor(ChatClient.Builder chatClientBuilder,
                          CalculatorTool calculatorTool,
                          ChatHistoryService chatHistoryService,
                          String systemPrompt) {
        this.chatClient = chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultTools(calculatorTool)
                .build();
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 处理用户消息（无事件监听）
     */
    public String processMessage(String input, ConversationState state) {
        return processMessage(input, state, null);
    }

    /**
     * 处理用户消息，支持事件监听（流式输出）
     *
     * ChatClient 自动处理工具调用（tool call loop），
     * 无需手动检测和执行工具。
     *
     * @param input    用户输入
     * @param state    对话状态
     * @param listener 事件监听器（可为 null）
     * @return 助手响应文本
     */
    public String processMessage(String input, ConversationState state, ChatEventListener listener) {
        // 保存用户消息到状态和历史
        state.addUserMessage(input);
        chatHistoryService.saveUserMessage(input);

        // 将 ConversationState 的 ChatMessage 列表转换为 Spring AI Message 列表
        List<Message> messages = convertToSpringAiMessages(state.getConversationHistory());

        if (listener != null) {
            // 流式输出
            return streamResponse(messages, state, listener);
        } else {
            // 同步调用
            return callResponse(messages, state);
        }
    }

    /**
     * 同步调用 ChatClient 获取响应
     */
    private String callResponse(List<Message> messages, ConversationState state) {
        String responseText = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

        state.addAssistantMessage(responseText);
        chatHistoryService.saveAssistantMessage(responseText);
        return responseText;
    }

    /**
     * 流式调用 ChatClient 获取响应
     */
    private String streamResponse(List<Message> messages, ConversationState state,
                                   ChatEventListener listener) {
        listener.onStreamingStart();
        StringBuilder fullResponse = new StringBuilder();

        try {
            chatClient.prompt()
                    .messages(messages)
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        listener.onStreamingToken(token);
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("流式响应失败，回退到同步调用: {}", e.getMessage());
            String fallbackText = chatClient.prompt()
                    .messages(messages)
                    .call()
                    .content();
            fullResponse.append(fallbackText);
            listener.onStreamingToken(fallbackText);
        }

        listener.onStreamingEnd();

        String responseText = fullResponse.toString();
        state.addAssistantMessage(responseText);
        chatHistoryService.saveAssistantMessage(responseText);
        return responseText;
    }

    /**
     * 将内部 ChatMessage 列表转换为 Spring AI Message 列表。
     *
     * ChatClient 自动处理工具调用，因此不需要手动添加
     * assistantWithTools 或 tool 类型的消息。
     */
    private List<Message> convertToSpringAiMessages(List<ChatMessage> chatMessages) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessage cm : chatMessages) {
            switch (cm.role()) {
                case "user" ->
                    messages.add(new UserMessage(cm.content()));
                case "assistant" -> {
                    if (cm.hasToolCalls()) {
                        // 将遗留的 ToolCall 转换为 Spring AI AssistantMessage.ToolCall
                        List<AssistantMessage.ToolCall> toolCalls = cm.toolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(
                                        tc.id(), "function", tc.name(), tc.arguments()))
                                .toList();
                        messages.add(AssistantMessage.builder()
                                .content(cm.content())
                                .toolCalls(toolCalls)
                                .build());
                    } else {
                        messages.add(new AssistantMessage(cm.content()));
                    }
                }
                case "tool" -> {
                    var response = new ToolResponseMessage.ToolResponse(
                            cm.toolCallId(), "tool", cm.content());
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(response))
                            .build());
                }
                case "system" ->
                    messages.add(new SystemMessage(cm.content()));
            }
        }
        return messages;
    }
}
