package io.ailink.agentforge.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具定义
 *
 * 发送给 LLM 的工具描述，包含名称、描述和参数 Schema。
 * 从 Tool 接口转换而来。
 *
 * @param name        工具名称
 * @param description 工具描述
 * @param inputSchema 输入参数 JSON Schema
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema
) {

    /**
     * 从 Tool 接口创建定义
     *
     * @param tool 工具实例
     * @return 工具定义
     */
    public static ToolDefinition from(Tool tool) {
        return new ToolDefinition(
                tool.name(),
                tool.description(),
                tool.inputSchema()
        );
    }
}
