package io.ailink.agentforge.agent.tool;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 工具的一个参数声明（对应 coxswain {@code define-tool} 参数表里的一项：
 * {@code [name type "说明" (default v)]}）。
 *
 * @param type        string / integer / number / boolean / array / object
 * @param required    没有默认值即必填
 * @param defaultValue 默认值（Java 值：String / Long / Double / Boolean / List / null）
 * @param enumValues  可选：字符串枚举
 * @param schema      可选：array 的 items / object 的完整 schema，原样并入
 */
public record Param(String name, String type, String description, boolean required,
                    Object defaultValue, List<String> enumValues, JsonNode schema) {

    public static Param required(String name, String type, String description) {
        return new Param(name, type, description, true, null, null, null);
    }

    public static Param optional(String name, String type, String description, Object defaultValue) {
        return new Param(name, type, description, false, defaultValue, null, null);
    }

    public Param withEnum(List<String> values) {
        return new Param(name, type, description, required, defaultValue, values, schema);
    }

    public Param withSchema(JsonNode s) {
        return new Param(name, type, description, required, defaultValue, enumValues, s);
    }
}
