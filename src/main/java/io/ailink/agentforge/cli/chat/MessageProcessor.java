package io.ailink.agentforge.cli.chat;

import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.dto.ChatResponse;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.tool.ToolCall;
import io.ailink.agentforge.tool.ToolExecutor;
import io.ailink.agentforge.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 消息处理器
 *
 * 负责调用 LLM、执行工具、处理响应的核心业务逻辑。
 * 将 AI 对话逻辑与 UI 交互分离。
 */
public class MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ChatHistoryService chatHistoryService;
    private final String systemPrompt;

    public MessageProcessor(LlmProvider llmProvider,
                          ToolRegistry toolRegistry,
                          ToolExecutor toolExecutor,
                          ChatHistoryService chatHistoryService,
                          String systemPrompt) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.chatHistoryService = chatHistoryService;
        this.systemPrompt = systemPrompt;
    }

    /**
     * 处理用户消息（无事件监听）
     */
    public String processMessage(String input, ConversationState state) {
        return processMessage(input, state, null);
    }

    /**
     * 处理用户消息，支持事件监听（工具调用通知 + 流式输出）
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

        // 构建请求
        ChatRequest request = buildRequest(state.getConversationHistory());

        // 调用 LLM
        ChatResponse<?> response = llmProvider.chat(request);

        // 处理工具调用
        if (response.hasToolCalls()) {
            return executeToolsAndGetResponse(response, state, listener);
        }

        // 直接返回响应
        String responseText = response.content();
        state.addAssistantMessage(responseText);
        chatHistoryService.saveAssistantMessage(responseText);

        // 通过监听器输出响应
        if (listener != null) {
            listener.onStreamingStart();
            listener.onStreamingToken(responseText);
            listener.onStreamingEnd();
        }

        return responseText;
    }

    /**
     * 执行工具并获取最终响应（无事件监听）
     */
    public String executeToolsAndGetResponse(ChatResponse<?> response, ConversationState state) {
        return executeToolsAndGetResponse(response, state, null);
    }

    /**
     * 执行工具并获取最终响应，支持事件监听
     *
     * @param response LLM 响应（包含工具调用）
     * @param state    对话状态
     * @param listener 事件监听器（可为 null）
     * @return 最终响应文本
     */
    public String executeToolsAndGetResponse(ChatResponse<?> response, ConversationState state,
                                              ChatEventListener listener) {
        List<ToolCall> toolCalls = response.toolCalls();

        // 添加助手消息（带工具调用）到历史
        state.addAssistantMessageWithTools(response.content(), toolCalls);

        // 执行所有工具调用
        for (ToolCall toolCall : toolCalls) {
            if (listener != null) {
                listener.onToolExecuting(toolCall.name());
            }

            var result = toolExecutor.execute(toolCall);
            String toolResult = String.format("[%s] %s", toolCall.name(), result.content());
            state.addToolResult(toolCall.id(), toolResult);

            if (listener != null) {
                listener.onToolResult(toolCall.name(), toolResult);
            }
        }

        // 构建第二轮请求
        ChatRequest request = buildRequest(state.getConversationHistory());

        // 使用流式输出获取最终响应
        if (listener != null) {
            return streamResponse(request, state, listener);
        }

        // 无监听器时使用同步调用
        ChatResponse<?> finalResponse = llmProvider.chat(request);
        String responseText = finalResponse.content();
        state.addAssistantMessage(responseText);
        chatHistoryService.saveAssistantMessage(responseText);
        return responseText;
    }

    /**
     * 流式获取 LLM 响应
     */
    private String streamResponse(ChatRequest request, ConversationState state, ChatEventListener listener) {
        listener.onStreamingStart();
        StringBuilder fullResponse = new StringBuilder();

        try {
            llmProvider.chatStream(request)
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        listener.onStreamingToken(token);
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("流式响应失败，回退到同步调用: {}", e.getMessage());
            ChatResponse<?> fallback = llmProvider.chat(request);
            String fallbackText = fallback.content();
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
     * 构建聊天请求
     */
    private ChatRequest buildRequest(List<ChatMessage> conversationHistory) {
        ChatRequest.Builder builder = ChatRequest.builder()
                .system(systemPrompt)
                .messages(conversationHistory);

        // 添加工具定义
        if (toolRegistry.hasTools()) {
            builder.tools(toolRegistry.getToolDefinitions());
        }

        return builder.build();
    }
}
