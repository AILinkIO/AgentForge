package io.ailink.agentforge.agent.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息里的 {@code @路径} 提及（coxswain {@code (coxn text at-token)} 的简化版）：{@code @} 在行首或空白之后，
 * 一直到下一个空白；去掉尾随的标点。像邮箱那样前面紧贴字符的 {@code a@b} 不算。
 */
final class AtMentions {
    private AtMentions() { }

    static final int MAX = 10;
    private static final String TRAIL = ",.;:!?)]}，。；：！？）】、\"'";

    static List<String> paths(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != '@' || (i > 0 && !Character.isWhitespace(text.charAt(i - 1)))) continue;
            int j = i + 1;
            while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
            String p = text.substring(i + 1, j);
            while (!p.isEmpty() && TRAIL.indexOf(p.charAt(p.length() - 1)) >= 0) p = p.substring(0, p.length() - 1);
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
            i = j;
        }
        return out;
    }

    /** 去掉开头那个 {@code @xxx}（直接派活时：那个提及是"派给谁"，不是附件）。 */
    static String stripLeading(String text) {
        if (text == null || text.isEmpty() || text.charAt(0) != '@') return text;
        int i = 1;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return text.substring(i);
    }
}
