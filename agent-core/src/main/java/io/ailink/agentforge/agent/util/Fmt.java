package io.ailink.agentforge.agent.util;

import java.util.ArrayList;
import java.util.List;

/** 给模型的文字里常用的几样小格式（对应 Scheme {@code format "~s"} 等）。 */
public final class Fmt {
    private Fmt() { }

    /** {@code ~s}：带双引号，反斜杠与引号转义。 */
    public static String q(Object o) {
        String s = String.valueOf(o);
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.append('"').toString();
    }

    /** 按 {@code \n} 切行，末尾那个换行不算多一行；{@code \r\n} 的 {@code \r} 去掉。 */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length(), start = 0;
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start < n) out.add(text.substring(start));
        return out;
    }

    /** 每行后面一个换行；空表是空串。 */
    public static String joinLines(List<String> xs) {
        if (xs.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String x : xs) b.append(x).append('\n');
        return b.toString();
    }

    public static String trimNewline(String s) {
        int n = s.length();
        while (n > 0 && (s.charAt(n - 1) == '\n' || s.charAt(n - 1) == '\r')) n--;
        return s.substring(0, n);
    }

    public static boolean blank(String s) { return s == null || s.isBlank(); }
}
