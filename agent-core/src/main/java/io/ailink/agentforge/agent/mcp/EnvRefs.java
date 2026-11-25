package io.ailink.agentforge.agent.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code ${FOO}} → 环境变量（纯函数）。
 *
 * <p>⚠️ <b>没设的变量要报出来</b>，不是悄悄展成空串：一个 {@code Bearer } 开头的空 token 换来的是
 * 服务端一个 401，读起来像"这台服务器要认证"，与"你忘了 export"是两件事，用户的下一步也不同。
 * 没有收尾的 {@code }} 当普通字符，不猜。
 */
public final class EnvRefs {
    private EnvRefs() { }

    /** @param missing 没设（或设成空串）的变量名，按出现顺序 */
    public record Expanded<T>(T value, List<String> missing) { }

    public static Expanded<String> expand(String s) { return expand(s, System::getenv); }

    public static Expanded<String> expand(String s, Function<String, String> env) {
        if (s == null) return new Expanded<>(null, List.of());
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (i + 1 < n && s.charAt(i) == '$' && s.charAt(i + 1) == '{') {
                int close = s.indexOf('}', i + 2);
                if (close < 0) {
                    out.append(s.charAt(i));
                    i++;
                    continue;
                }
                String var = s.substring(i + 2, close);
                String val = env.apply(var);
                out.append(val == null ? "" : val);
                if (val == null || val.isEmpty()) missing.add(var);
                i = close + 1;
            } else {
                out.append(s.charAt(i));
                i++;
            }
        }
        return new Expanded<>(out.toString(), missing);
    }

    public static Expanded<Map<String, String>> expandAll(Map<String, String> m, Function<String, String> env) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            Expanded<String> x = expand(e.getValue(), env);
            out.put(e.getKey(), x.value());
            missing.addAll(x.missing());
        }
        return new Expanded<>(out, missing);
    }
}
