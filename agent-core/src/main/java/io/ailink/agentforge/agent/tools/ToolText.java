package io.ailink.agentforge.agent.tools;

import java.util.ArrayList;
import java.util.List;

/** 工具层共用的几个文本小工具（对应 coxswain 的 {@code (coxn text lines)} 与 {@code format "~s"}）。 */
public final class ToolText {
    private ToolText() { }

    /** 与 Scheme {@code ~s} 同形：带双引号，反斜杠与引号转义。报错里的路径都这么印。 */
    public static String q(String s) {
        if (s == null) return "#f";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 按 \n 切，保留末尾空段："a\nb\n" ⇒ [a, b, ""]。 */
    public static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    /** 切行并丢掉最后那个空段（"a\nb\n" ⇒ [a, b]；"" ⇒ []）。diff 用。 */
    public static List<String> splitLinesNoTrailing(String s) {
        List<String> l = splitLines(s);
        if (!l.isEmpty() && l.getLast().isEmpty()) l.removeLast();
        return l;
    }

    /** 换行数 + 1（"" ⇒ 1，"a\n" ⇒ 2）。 */
    public static int lineCount(String s) {
        int k = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') k++;
        return k;
    }

    /** 以换行结尾时末尾不另算一行；"" ⇒ 0。 */
    public static int lineCountNoTrailing(String s) {
        if (s.isEmpty()) return 0;
        int n = lineCount(s);
        return s.charAt(s.length() - 1) == '\n' ? n - 1 : n;
    }

    /** 只数非空行。 */
    public static int lineCountNoEmpty(String s) {
        int k = 0;
        boolean seen = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                if (seen) k++;
                seen = false;
            } else seen = true;
        }
        return seen ? k + 1 : k;
    }

    /** 文本中第 index 个字符在第几行（1 起）。 */
    public static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
