package io.ailink.agentforge.agent.todo;

import java.util.ArrayList;
import java.util.List;

/**
 * 待办清单的解析（coxswain {@code (coxn agent todo)} 的纯函数一半）。
 *
 * <p>为什么用 markdown 复选框而不是结构化参数：那正是模型本来就在写的格式，出错率低；解析是纯函数，
 * 能穷举测。**认得宽，写得窄**：{@code - * +} 三种符号、记号大小写、前面的缩进都收；认不出的行
 * 整行丢掉不猜（"下面是计划："不该变成第一项），空文本的条目也丢。
 */
public final class Todo {
    private Todo() { }

    /** 只有三档：清单是给人看进度的，不是任务系统。 */
    public enum Status { PENDING, DOING, DONE }

    public record Item(Status status, String text) { }

    public static List<Item> parse(String text) {
        List<Item> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n", -1)) {
            Item it = parseLine(line);
            if (it != null) out.add(it);
        }
        return out;
    }

    static Item parseLine(String line) {
        int n = line.length();
        int i = skip(line, 0);
        if (i + 4 >= n) return null;
        char bullet = line.charAt(i);
        if (bullet != '-' && bullet != '*' && bullet != '+') return null;
        int j = skip(line, i + 1);
        if (j == i + 1) return null;                 // 符号后面必须有空白
        if (j + 2 >= n || line.charAt(j) != '[') return null;
        // 记号可能是一个代理对之外的 BMP 字符（✓ 在 BMP 里）
        if (line.charAt(j + 2) != ']') return null;
        Status st = mark(line.charAt(j + 1));
        if (st == null) return null;
        String txt = line.substring(skip(line, j + 3)).stripTrailing();
        return txt.isEmpty() ? null : new Item(st, txt);
    }

    static Status mark(char c) {
        return switch (c) {
            case ' ', '\0' -> Status.PENDING;
            case 'x', 'X', '✓' -> Status.DONE;
            // > 是自己约定的"正在做"；~ 与 * 也收（模型偶尔自创记号）
            case '>', '~', '*' -> Status.DOING;
            default -> null;                          // 认不出的整条丢，不替它编一个状态
        };
    }

    private static int skip(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r')) i++;
        return i;
    }
}
