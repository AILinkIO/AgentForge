package io.ailink.agentforge.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用
 *
 * LLM 返回的工具调用请求，包含调用 ID、工具名称和参数。
 *
 * @param id        调用 ID (用于关联工具结果)
 * @param name      工具名称
 * @param arguments 工具参数 (JSON 对象)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        String name,
        JsonNode arguments
) {

    /**
     * 创建工具调用
     *
     * @param id        调用 ID
     * @param name      工具名称
     * @param arguments 参数
     * @return 工具调用实例
     */
    public static ToolCall of(String id, String name, JsonNode arguments) {
        return new ToolCall(id, name, arguments);
    }
}
