package io.ailink.agentforge.llm.dto;

import io.ailink.agentforge.tool.ToolCall;

import java.util.List;

/**
 * 聊天消息数据传输对象
 *
 * @param role       消息角色：user, assistant, system, tool
 * @param content    消息内容
 * @param toolCalls  assistant 消息的工具调用列表
 * @param toolCallId tool 消息关联的工具调用 ID
 */
public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    public static ChatMessage assistantWithTools(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
