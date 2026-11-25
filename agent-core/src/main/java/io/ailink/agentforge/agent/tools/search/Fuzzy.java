package io.ailink.agentforge.agent.tools.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模糊找文件：子序列匹配 + 打分排序（coxswain {@code (coxn text fuzzy) filter-candidates} 的同类）。
 *
 * <p>不分大小写；连续命中、命中在段首（{@code / _ - .} 之后或驼峰）、命中在基名里加分，长路径减分。
 * ⚠️ 整合时若有 UI 侧 {@code @} 补全的打分器，应合并成同一份 —— 两处排出两个顺序，用户会以为工具找错了。
 */
public final class Fuzzy {
    private Fuzzy() { }

    /** 匹配上的候选，按分数从高到低（同分短的在前，再按字母）。查询为空时原样返回。 */
    public static List<String> filter(List<String> candidates, String query) {
        String q = query.strip().toLowerCase().replace(" ", "");
        if (q.isEmpty()) return new ArrayList<>(candidates);
        record Hit(String s, int score) { }
        List<Hit> hits = new ArrayList<>();
        for (String c : candidates) {
            int sc = score(c, q);
            if (sc > Integer.MIN_VALUE) hits.add(new Hit(c, sc));
        }
        hits.sort(Comparator.comparingInt((Hit h) -> -h.score).thenComparingInt(h -> h.s.length()).thenComparing(h -> h.s));
        List<String> out = new ArrayList<>(hits.size());
        for (Hit h : hits) out.add(h.s);
        return out;
    }

    /** 不是子序列回 {@code Integer.MIN_VALUE}。贪心从后往前先在基名里找，找不到再整条。 */
    static int score(String cand, String q) {
        String lc = cand.toLowerCase();
        int baseStart = cand.lastIndexOf('/') + 1;
        int i = 0, score = 0, prev = -2;
        for (int k = 0; k < lc.length() && i < q.length(); k++) {
            if (lc.charAt(k) != q.charAt(i)) continue;
            int s = 1;
            if (k == prev + 1) s += 5;
            if (k == 0 || isSep(cand.charAt(k - 1))
                    || (Character.isUpperCase(cand.charAt(k)) && Character.isLowerCase(cand.charAt(k - 1)))) s += 4;
            if (k >= baseStart) s += 2;
            score += s;
            prev = k;
            i++;
        }
        if (i < q.length()) return Integer.MIN_VALUE;
        // 基名里整段包含查询：大加分
        if (lc.substring(baseStart).contains(q)) score += 20;
        return score * 10 - cand.length();
    }

    private static boolean isSep(char c) { return c == '/' || c == '_' || c == '-' || c == '.' || c == ' '; }
}
