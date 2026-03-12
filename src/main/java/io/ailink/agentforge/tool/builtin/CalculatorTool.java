package io.ailink.agentforge.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.tool.Tool;
import io.ailink.agentforge.tool.ToolResult;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

@Component
public class CalculatorTool implements Tool {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScriptEngine engine = new ScriptEngineManager().getEngineByName("JavaScript");

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "执行数学计算。支持基本运算(+、-、*、/)和数学函数(如 Math.sqrt, Math.pow, Math.sin 等)。";
    }

    @Override
    public JsonNode inputSchema() {
        String schema = """
            {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "要计算的数学表达式，例如: '2 + 3 * 4' 或 'Math.sqrt(16)' 或 'Math.pow(2, 10)'"
                    }
                },
                "required": ["expression"]
            }
            """;
        try {
            return objectMapper.readTree(schema);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("expression")) {
            return ToolResult.error(null, "缺少必需参数: expression");
        }

        String expression = arguments.get("expression").asText();
        
        if (expression == null || expression.isBlank()) {
            return ToolResult.error(null, "表达式不能为空");
        }

        // 安全检查：只允许数字、运算符、空格和 Math 函数
        String sanitized = expression.trim();
        if (!isValidExpression(sanitized)) {
            return ToolResult.error(null, "表达式包含不允许的字符");
        }

        try {
            Object result = engine.eval(sanitized);
            String resultStr = formatResult(result);
            return ToolResult.success(null, resultStr);
        } catch (Exception e) {
            return ToolResult.error(null, "计算错误: " + e.getMessage());
        }
    }

    private boolean isValidExpression(String expr) {
        // 允许: 数字、运算符、括号、空格、Math.函数、小数点
        return expr.matches("^[0-9+\\-*/().\\sMath]+$") ||
               expr.matches("^[0-9+\\-*/().\\s]+$");
    }

    private String formatResult(Object result) {
        if (result == null) {
            return "null";
        }
        if (result instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d.doubleValue());
            }
            return String.valueOf(d);
        }
        if (result instanceof Number n) {
            return n.toString();
        }
        return result.toString();
    }
}
