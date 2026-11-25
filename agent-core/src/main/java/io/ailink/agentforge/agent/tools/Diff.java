package io.ailink.agentforge.agent.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * 紧凑的 unified diff（Myers 最短编辑序列，先剥公共头尾）。审批预览与 apply-patch 预览用。
 */
public final class Diff {
    private Diff() { }

    private enum Op { EQ, DEL, ADD }

    private record Edit(Op op, String line) { }

    /** 两段文本的 unified diff；相同时回 ""。 */
    public static String unified(String before, String after, String nameA, String nameB, int context) {
        return unified(ToolText.splitLinesNoTrailing(before), ToolText.splitLinesNoTrailing(after), nameA, nameB, context);
    }

    public static String unified(List<String> a, List<String> b, String nameA, String nameB, int context) {
        List<Edit> es = edits(a, b);
        boolean changed = es.stream().anyMatch(e -> e.op != Op.EQ);
        if (!changed) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(nameA).append('\n').append("+++ ").append(nameB).append('\n');
        int n = es.size();
        int i = 0;
        // 每个编辑在 a / b 中的起始行号（0 起）
        int[] ai = new int[n + 1], bi = new int[n + 1];
        for (int k = 0; k < n; k++) {
            ai[k + 1] = ai[k] + (es.get(k).op != Op.ADD ? 1 : 0);
            bi[k + 1] = bi[k] + (es.get(k).op != Op.DEL ? 1 : 0);
        }
        while (i < n) {
            while (i < n && es.get(i).op == Op.EQ) i++;
            if (i >= n) break;
            int start = Math.max(0, i - context);
            int end = i;
            // 向后扩：两段改动之间的相同行不超过 2*context 就并成一块
            while (true) {
                while (end < n && es.get(end).op != Op.EQ) end++;
                int eq = end;
                while (eq < n && es.get(eq).op == Op.EQ) eq++;
                if (eq < n && eq - end <= 2 * context) { end = eq; continue; }
                end = Math.min(n, end + context);
                break;
            }
            int la = 0, lb = 0;
            for (int k = start; k < end; k++) {
                if (es.get(k).op != Op.ADD) la++;
                if (es.get(k).op != Op.DEL) lb++;
            }
            sb.append("@@ -").append(range(ai[start], la)).append(" +").append(range(bi[start], lb)).append(" @@\n");
            for (int k = start; k < end; k++) {
                Edit e = es.get(k);
                sb.append(e.op == Op.EQ ? ' ' : e.op == Op.DEL ? '-' : '+').append(e.line).append('\n');
            }
            i = end;
        }
        if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String range(int start0, int len) {
        if (len == 0) return start0 + ",0";
        return (start0 + 1) + (len == 1 ? "" : "," + len);
    }

    private static List<Edit> edits(List<String> a, List<String> b) {
        int pre = 0;
        while (pre < a.size() && pre < b.size() && a.get(pre).equals(b.get(pre))) pre++;
        int suf = 0;
        while (suf < a.size() - pre && suf < b.size() - pre
                && a.get(a.size() - 1 - suf).equals(b.get(b.size() - 1 - suf))) suf++;
        List<Edit> out = new ArrayList<>();
        for (int k = 0; k < pre; k++) out.add(new Edit(Op.EQ, a.get(k)));
        out.addAll(myers(a.subList(pre, a.size() - suf), b.subList(pre, b.size() - suf)));
        for (int k = a.size() - suf; k < a.size(); k++) out.add(new Edit(Op.EQ, a.get(k)));
        return out;
    }

    private static List<Edit> myers(List<String> a, List<String> b) {
        int n = a.size(), m = b.size();
        List<Edit> out = new ArrayList<>();
        if (n == 0) { for (String s : b) out.add(new Edit(Op.ADD, s)); return out; }
        if (m == 0) { for (String s : a) out.add(new Edit(Op.DEL, s)); return out; }
        int max = n + m, off = max;
        int[] v = new int[2 * max + 2];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        long budget = 0;
        for (int d = 0; d <= max; d++) {
            budget += v.length;
            if (budget > 16_000_000L) {
                // 改动太大：回溯表会吃掉几百 MB。退成"整段删 + 整段加"——预览照样正确，只是不够紧凑
                for (String s : a) out.add(new Edit(Op.DEL, s));
                for (String s : b) out.add(new Edit(Op.ADD, s));
                return out;
            }
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x = (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) ? v[off + k + 1] : v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) { x++; y++; }
                v[off + k] = x;
                if (x >= n && y >= m) { found = d; break; }
            }
            if (found >= 0) break;
        }
        // 回溯
        List<Edit> rev = new ArrayList<>();
        int x = n, y = m;
        for (int d = found; d > 0; d--) {
            int[] vp = trace.get(d);
            int k = x - y;
            int prevK = (k == -d || (k != d && vp[off + k - 1] < vp[off + k + 1])) ? k + 1 : k - 1;
            int px = vp[off + prevK], py = px - prevK;
            while (x > px && y > py) { rev.add(new Edit(Op.EQ, a.get(x - 1))); x--; y--; }
            if (x == px) rev.add(new Edit(Op.ADD, b.get(y - 1)));
            else rev.add(new Edit(Op.DEL, a.get(x - 1)));
            x = px;
            y = py;
        }
        while (x > 0 && y > 0) { rev.add(new Edit(Op.EQ, a.get(x - 1))); x--; y--; }
        for (int k = rev.size() - 1; k >= 0; k--) out.add(rev.get(k));
        return out;
    }
}
