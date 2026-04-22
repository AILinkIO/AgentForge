package io.ailink.agentforge.tool;

/**
 * 工具调用数据传输对象
 */
public record ToolCall(
        String id,
        String name,
        String arguments
) {}
