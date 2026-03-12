package io.ailink.agentforge.tool;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 工具执行结果
 *
 * 工具执行后返回给 LLM 的结果。
 *
 * @param toolCallId 关联的工具调用 ID
 * @param content    结果内容
 * @param isError    是否为错误
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolResult(
        String toolCallId,
        String content,
        boolean isError
) {

    /**
     * 创建成功结果
     *
     * @param toolCallId 工具调用 ID
     * @param content    结果内容
     * @return 成功结果
     */
    public static ToolResult success(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    /**
     * 创建错误结果
     *
     * @param toolCallId 工具调用 ID
     * @param error      错误信息
     * @return 错误结果
     */
    public static ToolResult error(String toolCallId, String error) {
        return new ToolResult(toolCallId, error, true);
    }
}
