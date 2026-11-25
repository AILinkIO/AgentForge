package io.ailink.agentforge.agent.models;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 读环境变量：**空串当没设**（{@code export FOO=} 之后 getenv 给空串，而"设了一个空 key"
 * 没有任何有意义的解释）。
 *
 * <p>Java 改不了进程环境，测试用 {@link #override(Map)} 换一张表（表里没有的键当没设，
 * **不回落到真环境** —— 否则开发者机器上的真 key 会漏进用例）。
 */
public final class EnvVars {
    private static volatile Function<String, String> source = System::getenv;

    private EnvVars() { }

    /** 非空值，或 null。 */
    public static String get(String name) {
        if (name == null || name.isEmpty()) return null;
        String v = source.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }

    /** 测试用：只认这张表（可变，用例里可以继续 put）。 */
    public static Map<String, String> override(Map<String, String> vars) {
        Map<String, String> m = new HashMap<>(Objects.requireNonNull(vars));
        source = m::get;
        return m;
    }

    /** 测试用：只认给定的函数。 */
    public static void override(Function<String, String> f) { source = Objects.requireNonNull(f); }

    public static void reset() { source = System::getenv; }

    /** provider id → 环境变量名片段：大写，非字母数字换成 {@code _}（{@code "zhipuai-coding"} → {@code ZHIPUAI_CODING}）。 */
    public static String idToEnv(String id) {
        StringBuilder sb = new StringBuilder();
        for (char c : id.toCharArray()) {
            sb.append(c < 128 && Character.isLetterOrDigit(c) ? Character.toUpperCase(c) : '_');
        }
        return sb.toString();
    }
}
