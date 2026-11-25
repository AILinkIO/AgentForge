package io.ailink.agentforge.cli.chat;

import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.tool.ToolCall;
import io.ailink.agentforge.ui.DisplayMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话状态管理器
 * 
 * 负责管理对话历史和显示消息，提供线程安全的操作接口。
 * 将消息状态与业务逻辑分离，便于测试和复用。
 */
public class ConversationState {

    /**
     * LLM 使用的对话历史（包含系统消息、用户消息、助手消息、工具结果）
     */
    private final List<ChatMessage> conversationHistory;

    /**
     * 终端显示用的消息列表（包含角色、内容、时间）
     */
    private final List<DisplayMessage> displayMessages;

    /**
     * 当前消息的时间戳
     */
    private String currentTime;

    public ConversationState() {
        this.conversationHistory = new ArrayList<>();
        this.displayMessages = new ArrayList<>();
    }

    /**
     * 初始化对话状态
     * 
     * @param recentMessages 从历史中加载的最近消息（ChatMessageEntity 转换而来）
     */
    public ConversationState(List<ChatMessage> recentMessages) {
        this.conversationHistory = new ArrayList<>(recentMessages);
        this.displayMessages = new ArrayList<>();
    }

    /**
     * 添加用户消息
     * 
     * @param content 消息内容
     */
    public void addUserMessage(String content) {
        currentTime = java.time.LocalTime.now().toString();
        displayMessages.add(new DisplayMessage("user", content, currentTime));
        conversationHistory.add(ChatMessage.user(content));
    }

    /**
     * 添加助手消息
     * 
     * @param content 消息内容
     */
    public void addAssistantMessage(String content) {
        currentTime = java.time.LocalTime.now().toString();
        displayMessages.add(new DisplayMessage("assistant", content, currentTime));
        conversationHistory.add(ChatMessage.assistant(content));
    }

    /**
     * 添加带有工具调用的助手消息
     * 
     * @param content 消息内容
     * @param toolCalls 工具调用列表
     */
    public void addAssistantMessageWithTools(String content, List<ToolCall> toolCalls) {
        currentTime = java.time.LocalTime.now().toString();
        conversationHistory.add(ChatMessage.assistantWithTools(content, toolCalls));
    }

    /**
     * 添加工具结果消息
     * 
     * @param toolCallId 工具调用 ID
     * @param content 工具执行结果
     */
    public void addToolResult(String toolCallId, String content) {
        conversationHistory.add(ChatMessage.toolResult(toolCallId, content));
        displayMessages.add(new DisplayMessage("tool",
                content,
                currentTime));
    }

    /**
     * 添加显示消息（用于从历史加载时）
     * 
     * @param role 消息角色
     * @param content 消息内容
     * @param time 消息时间
     */
    public void addDisplayMessage(String role, String content, String time) {
        displayMessages.add(new DisplayMessage(role, content, time));
    }

    /**
     * 获取对话历史
     * 
     * @return 对话消息列表
     */
    public List<ChatMessage> getConversationHistory() {
        return conversationHistory;
    }

    /**
     * 获取显示消息列表
     * 
     * @return 显示用消息列表
     */
    public List<DisplayMessage> getDisplayMessages() {
        return displayMessages;
    }

    /**
     * 获取当前时间戳
     * 
     * @return 当前时间字符串
     */
    public String getCurrentTime() {
        return currentTime;
    }

    /**
     * 清空对话状态
     */
    public void clear() {
        conversationHistory.clear();
        displayMessages.clear();
    }

    /**
     * 检查是否为空
     * 
     * @return 如果没有消息返回 true
     */
    public boolean isEmpty() {
        return conversationHistory.isEmpty();
    }
}
