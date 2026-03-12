package io.ailink.agentforge.tool.builtin;

import com.fathzer.soft.javaluator.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ailink.agentforge.tool.Tool;
import io.ailink.agentforge.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Iterator;

@Component
public class CalculatorTool implements Tool {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Custom functions for Javaluator
    private static final Function SQRT = new Function("sqrt", 1);
    private static final Function POW = new Function("pow", 2);
    private static final Function SIN = new Function("sin", 1);
    private static final Function COS = new Function("cos", 1);
    private static final Function TAN = new Function("tan", 1);
    private static final Function LOG = new Function("log", 1);
    private static final Function ABS = new Function("abs", 1);
    private static final Function CEIL = new Function("ceil", 1);
    private static final Function FLOOR = new Function("floor", 1);
    private static final Function ROUND = new Function("round", 1);
    private static final Function MIN = new Function("min", 2);
    private static final Function MAX = new Function("max", 2);
    private static final Function EXP = new Function("exp", 1);
    
    // Constants
    private static final Constant PI = new Constant("pi");
    private static final Constant E = new Constant("e");
    
    private final DoubleEvaluator evaluator;

    public CalculatorTool() {
        Parameters params = DoubleEvaluator.getDefaultParameters();
        params.add(SQRT);
        params.add(POW);
        params.add(SIN);
        params.add(COS);
        params.add(TAN);
        params.add(LOG);
        params.add(ABS);
        params.add(CEIL);
        params.add(FLOOR);
        params.add(ROUND);
        params.add(MIN);
        params.add(MAX);
        params.add(EXP);
        params.add(PI);
        params.add(E);
        
        evaluator = new DoubleEvaluator(params) {
            @Override
            protected Double evaluate(Function function, Iterator<Double> arguments, Object evaluationContext) {
                String name = function.getName();
                if ("sqrt".equals(name)) {
                    return Math.sqrt(arguments.next());
                } else if ("pow".equals(name)) {
                    double base = arguments.next();
                    double exponent = arguments.next();
                    return Math.pow(base, exponent);
                } else if ("sin".equals(name)) {
                    return Math.sin(arguments.next());
                } else if ("cos".equals(name)) {
                    return Math.cos(arguments.next());
                } else if ("tan".equals(name)) {
                    return Math.tan(arguments.next());
                } else if ("log".equals(name)) {
                    return Math.log(arguments.next());
                } else if ("abs".equals(name)) {
                    return Math.abs(arguments.next());
                } else if ("ceil".equals(name)) {
                    return Math.ceil(arguments.next());
                } else if ("floor".equals(name)) {
                    return Math.floor(arguments.next());
                } else if ("round".equals(name)) {
                    return (double) Math.round(arguments.next());
                } else if ("min".equals(name)) {
                    double a = arguments.next();
                    double b = arguments.next();
                    return Math.min(a, b);
                } else if ("max".equals(name)) {
                    double a = arguments.next();
                    double b = arguments.next();
                    return Math.max(a, b);
                } else if ("exp".equals(name)) {
                    return Math.exp(arguments.next());
                }
                return super.evaluate(function, arguments, evaluationContext);
            }
            
            @Override
            protected Double evaluate(Constant constant, Object evaluationContext) {
                String name = constant.getName();
                if ("pi".equals(name)) {
                    return Math.PI;
                } else if ("e".equals(name)) {
                    return Math.E;
                }
                return super.evaluate(constant, evaluationContext);
            }
        };
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "执行数学计算。支持基本运算(+、-、*、/)和数学函数(如 sqrt, pow, sin 等)。";
    }

    @Override
    public JsonNode inputSchema() {
        String schema = """
            {
                "type": "object",
                "properties": {
                    "expression": {
                        "type": "string",
                        "description": "要计算的数学表达式，例如: '2 + 3 * 4' 或 'sqrt(16)' 或 'pow(2, 10)'"
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

        // 安全检查：只允许数字、运算符、空格和函数
        String sanitized = expression.trim();
        if (!isValidExpression(sanitized)) {
            return ToolResult.error(null, "表达式包含不允许的字符");
        }

        try {
            Double result = evaluator.evaluate(sanitized);
            String resultStr = formatResult(result);
            return ToolResult.success(null, resultStr);
        } catch (Exception e) {
            return ToolResult.error(null, "计算错误: " + e.getMessage());
        }
    }

    private boolean isValidExpression(String expr) {
        // 允许: 数字、运算符、括号、空格、函数名、常量名、逗号
        return expr.matches("^[0-9+\\-*/().\\s,a-zA-Z]+$");
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
