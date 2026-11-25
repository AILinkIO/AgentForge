package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 候选过滤与排序（helm / fido 的手感，coxswain {@code coxn.minibuffer/match}）：
 * 完全相同 &gt; 前缀 &gt; 子串 &gt; flex（按顺序出现即可）；全小写的 pattern 不分大小写。
 * pattern 里有空格时按词拆开（orderless）：每个词都要命中（子串或 flex），顺序不限；排序按第一个词的命中等级。
 * 同一档里保持原来的顺序。
 */
public final class Matcher {
    private Matcher() {}

    public static List<String> match(String pattern, List<String> items) {
        String p = pattern == null ? "" : pattern.trim();
        String[] terms = p.isEmpty() ? new String[0] : p.split("\\s+");
        if (terms.length <= 1) return match1(p, items);
        List<String> out = new ArrayList<>();
        outer:
        for (String it : match1(terms[0], items)) {
            for (int i = 1; i < terms.length; i++) if (rank(terms[i], it) < 0) continue outer;
            out.add(it);
        }
        return out;
    }

    private static List<String> match1(String pat, List<String> items) {
        if (pat.isBlank()) return new ArrayList<>(items);
        record R(int rank, int idx, String item) {}
        List<R> rs = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int r = rank(pat, items.get(i));
            if (r >= 0) rs.add(new R(r, i, items.get(i)));
        }
        rs.sort(Comparator.comparingInt(R::rank).thenComparingInt(R::idx));
        List<String> out = new ArrayList<>(rs.size());
        for (R r : rs) out.add(r.item);
        return out;
    }

    /** 命中等级：0 相同、1 前缀、2 子串、3 flex、-1 不中。 */
    public static int rank(String pat, String item) {
        boolean fold = pat.equals(pat.toLowerCase(Locale.ROOT));
        String s = fold ? item.toLowerCase(Locale.ROOT) : item;
        if (s.equals(pat)) return 0;
        if (s.startsWith(pat)) return 1;
        if (s.contains(pat)) return 2;
        return flex(pat, s) ? 3 : -1;
    }

    static boolean flex(String pat, String s) {
        int i = 0;
        for (int j = 0; i < pat.length() && j < s.length(); j++) if (pat.charAt(i) == s.charAt(j)) i++;
        return i == pat.length();
    }

    /** 候选里要高亮的位置：输入的每个词（不分大小写）第一次出现的地方。⇒ [[a, b] …]。 */
    public static List<int[]> spans(String item, String input) {
        List<int[]> out = new ArrayList<>();
        if (input == null) return out;
        String li = item.toLowerCase(Locale.ROOT);
        for (String t : input.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (t.isEmpty()) continue;
            int i = li.indexOf(t);
            if (i >= 0) out.add(new int[]{i, i + t.length()});
        }
        return out;
    }
}
