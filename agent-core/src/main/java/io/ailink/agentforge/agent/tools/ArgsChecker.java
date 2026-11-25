package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.AgentTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 参数类型校验：照工具声明的 schema 查模型交来的参数（对应 coxswain {@code tools/args.ss}）。
 *
 * <p>会话层在执行之前调 {@link #check}；不为 null 就把那句话作为 is_error 结果交回去、不执行。
 * 只查声明了简单 type 的键；认不出的 type 放过；没声明的键不管（多余参数不是这里的事）。
 */
public final class ArgsChecker {
    private ArgsChecker() { }

    /** null = 没问题；否则一句给模型的话。 */
    public static String check(AgentTool tool, ObjectNode args) {
        List<Map.Entry<String, String>> ps = schemaArgProblems(tool.schema(), args);
        if (ps.isEmpty()) return null;
        List<String> why = new ArrayList<>();
        for (var p : ps) why.add(p.getValue());
        return "工具 " + tool.name() + " 的参数类型不对：" + String.join("；", why) + "。请照 schema 改写参数再调一次";
    }

    /** 纯函数：(schema, args) → [(键, 一句话)…]。 */
    public static List<Map.Entry<String, String>> schemaArgProblems(JsonNode schema, JsonNode args) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (schema == null || args == null || !args.isObject()) return out;
        JsonNode props = schema.get("properties");
        if (props == null || !props.isObject()) return out;
        for (Map.Entry<String, JsonNode> e : ((ObjectNode) args).properties()) {
            String want = declaredType(props, e.getKey());
            if (want == null) continue;
            String why = mismatch(e.getKey(), want, e.getValue());
            if (why != null) out.add(Map.entry(e.getKey(), why));
        }
        return out;
    }

    private static String declaredType(JsonNode props, String key) {
        JsonNode p = props.get(key);
        if (p == null || !p.isObject()) return null;
        JsonNode t = p.get("type");
        return t != null && t.isString() ? t.asString() : null;
    }

    private static String mismatch(String key, String want, JsonNode v) {
        if (v == null || v.isNull()) return key + " 传了 null —— 不填就别写这个键（省略即默认值）";
        if (ok(want, v)) return null;
        return key + " 要 " + label(want) + "，收到的是 " + kindOf(v) + "（" + show(v) + "）";
    }

    private static boolean ok(String want, JsonNode v) {
        return switch (want) {
            case "string" -> v.isString();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "array" -> v.isArray();
            case "object" -> v.isObject();
            default -> true;
        };
    }

    private static String label(String want) {
        return switch (want) {
            case "string" -> "字符串";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "布尔值（true / false）";
            case "array" -> "数组";
            case "object" -> "对象";
            default -> want;
        };
    }

    private static String kindOf(JsonNode v) {
        if (v.isString()) return "字符串";
        if (v.isBoolean()) return "布尔值";
        if (v.isIntegralNumber()) return "整数";
        if (v.isNumber()) return "小数";
        if (v.isArray()) return "数组";
        if (v.isObject()) return v.isEmpty() ? "空对象" : "对象";
        return "别的东西";
    }

    /** 值的短写：太长就截，免得一条报错把整个 content 参数抄一遍。 */
    private static String show(JsonNode v) {
        String s = v.toString();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
