package io.ailink.agentforge.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口
 *
 * 定义可被 LLM 调用的工具。
 * 实现此接口并标注 @Component 即可自动注册到 ToolRegistry。
 *
 * 使用示例：
 * <pre>
 * &#64;Component
 * public class CalculatorTool implements Tool {
 *     &#64;Override
 *     public String name() { return "calculator"; }
 *
 *     &#64;Override
 *     public String description() { return "执行数学计算"; }
 *
 *     &#64;Override
 *     public JsonNode inputSchema() { ... }
 *
 *     &#64;Override
 *     public ToolResult execute(JsonNode arguments) { ... }
 * }
 * </pre>
 */
public interface Tool {

    /**
     * 工具名称
     *
     * 必须唯一，用于 LLM 识别和调用。
     * 建议使用小写字母、数字和下划线，如: calculator, web_search
     *
     * @return 工具名称
     */
    String name();

    /**
     * 工具描述
     *
     * 发送给 LLM 的描述，帮助 LLM 理解工具用途。
     * 应清晰描述工具的功能和使用场景。
     *
     * @return 工具描述
     */
    String description();

    /**
     * 输入参数 JSON Schema
     *
     * 定义工具接受的参数结构。
     * LLM 会根据此 Schema 生成参数。
     *
     * @return JSON Schema 节点
     */
    JsonNode inputSchema();

    /**
     * 执行工具
     *
     * @param arguments 工具参数 (由 LLM 生成)
     * @return 执行结果
     */
    ToolResult execute(JsonNode arguments);
}
