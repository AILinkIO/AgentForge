package io.ailink.agentforge.agent.tools.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * search-text / find-files 的 {@code glob}（coxswain {@code keel text glob} + search.ss 的 {@code {a,b}} 展开）。
 *
 * <ul>
 *   <li>{@code *} {@code ?} {@code [abc]} {@code [!abc]} {@code \x}；整串匹配。</li>
 *   <li>⚠️ {@code *} 不特殊对待 {@code /}：带 {@code /} 的模式拿整条相对路径去比，
 *       所以 {@code src/*.java} 也会匹配 {@code src/a/b.java}；不带 {@code /} 的拿基名比。</li>
 *   <li>{@code {a,b}} 展开成多条（笛卡尔积）；⛔ 不支持嵌套；没闭合照字面。</li>
 * </ul>
 */
public final class Glob {
    private Glob() { }

    public static List<String> expand(String g) {
        int open = g.indexOf('{');
        int close = open < 0 ? -1 : g.indexOf('}', open + 1);
        if (close < 0) return List.of(g);
        String head = g.substring(0, open), tail = g.substring(close + 1);
        List<String> out = new ArrayList<>();
        for (String alt : g.substring(open + 1, close).split(",", -1)) out.addAll(expand(head + alt + tail));
        return out;
    }

    public static Pattern compile(String glob) {
        StringBuilder r = new StringBuilder();
        int n = glob.length();
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> r.append(".*");
                case '?' -> r.append('.');
                case '\\' -> {
                    if (i + 1 < n) r.append(Pattern.quote(String.valueOf(glob.charAt(++i))));
                    else r.append("\\\\");
                }
                case '[' -> {
                    int j = i + 1;
                    if (j < n && (glob.charAt(j) == '!' || glob.charAt(j) == '^')) j++;
                    if (j < n && glob.charAt(j) == ']') j++;
                    while (j < n && glob.charAt(j) != ']') j++;
                    if (j >= n) { r.append("\\["); break; }
                    StringBuilder cls = new StringBuilder("[");
                    int k = i + 1;
                    if (glob.charAt(k) == '!' || glob.charAt(k) == '^') { cls.append('^'); k++; }
                    for (; k < j; k++) {
                        char x = glob.charAt(k);
                        if (x == '\\' || x == '[' || x == '&' || x == '^' && k > i + 1) cls.append('\\');
                        cls.append(x);
                    }
                    r.append(cls).append(']');
                    i = j;
                }
                default -> r.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(r.toString(), Pattern.DOTALL);
    }

    /** 多条模式里命中任意一条就算。坏模式当不匹配。 */
    public static boolean hit(List<String> globs, String rel) { return matcher(globs).test(rel); }

    /** 先编译好，逐个路径问。 */
    public static java.util.function.Predicate<String> matcher(List<String> globs) {
        List<Pattern> full = new ArrayList<>(), base = new ArrayList<>();
        for (String g : globs) {
            try { (g.indexOf('/') >= 0 ? full : base).add(compile(g)); } catch (RuntimeException ignored) { }
        }
        return rel -> {
            for (Pattern p : full) if (p.matcher(rel).matches()) return true;
            if (!base.isEmpty()) {
                String b = basename(rel);
                for (Pattern p : base) if (p.matcher(b).matches()) return true;
            }
            return false;
        };
    }

    static String basename(String rel) {
        int i = rel.lastIndexOf('/');
        return i < 0 ? rel : rel.substring(i + 1);
    }
}
