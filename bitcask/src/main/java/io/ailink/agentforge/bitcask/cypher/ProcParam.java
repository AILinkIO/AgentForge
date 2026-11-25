package io.ailink.agentforge.bitcask.cypher;

/**
 * 用户过程的参数 / 输出声明：类型 INTEGER / FLOAT / NUMBER / STRING / BOOLEAN / LIST / MAP / NODE / RELATIONSHIP / PATH / ANY，
 * 后缀 {@code ?} 表示可以为 null。hasDefault ⇒ 调用时可省（取 defaultValue）。
 */
public record ProcParam(String name, String type, boolean hasDefault, Object defaultValue) {
    public static ProcParam of(String name, String type) {
        return new ProcParam(name, type, false, null);
    }

    public static ProcParam withDefault(String name, String type, Object dflt) {
        return new ProcParam(name, type, true, dflt);
    }
}
