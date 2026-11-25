package io.ailink.agentforge.sandbox.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * git 风格的 glob（deny-read 条目用；移植自 Codex build_glob_matcher：globset 的 literal_separator、allow_unclosed_class、
 * Posix 下反斜杠转义、Windows 下不分大小写）：{@code *} / {@code ?} 不跨 {@code /}；整段的 {@code **} 跨目录（{@code a/**}{@code /b} 含
 * {@code a/b}）；{@code [...]} 字符类（{@code !} / {@code ^} 取反，没闭合的 {@code [} 当字面）；{@code {a,b}} 选一；Posix 下 {@code \x} 转义。整串匹配。
 */
public final class Glob {
    private final Pattern regex;
    private final boolean windows;

    private Glob(Pattern regex, boolean windows) {
        this.regex = regex;
        this.windows = windows;
    }

    /** 编译；编不出来 ⇒ {@code null}（调用方按 Codex 失败即拒）。 */
    public static Glob compile(String pattern, boolean windows) {
        String p = windows ? pattern.replace('\\', '/') : pattern;
        try {
            String body = translate(p, 0, p.length(), windows);
            int flags = Pattern.DOTALL | (windows ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            return new Glob(Pattern.compile("^" + body + "$", flags), windows);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    public boolean matches(String path) {
        return regex.matcher(windows ? path.replace('\\', '/') : path).find();
    }

    public Pattern regex() {
        return regex;
    }

    // .NET Regex.Escape 的字符集
    private static String escape(char c) {
        if ("\\*+?|{}[]()^$.#".indexOf(c) >= 0) return "\\" + c;
        switch (c) {
            case ' ': return "\\ ";
            case '\t': return "\\t";
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\f': return "\\f";
            default:
                return Character.isLetterOrDigit(c) || c < 0x80 ? String.valueOf(c) : Pattern.quote(String.valueOf(c));
        }
    }

    private record Piece(String regex, int next) {
    }

    /** {@code [...]} ⇒ 正则；没闭合 ⇒ {@code null}。 */
    private static Piece tryClass(String p, int open, int end, boolean windows) {
        int i0 = open + 1;
        boolean negate = i0 < end && (p.charAt(i0) == '!' || p.charAt(i0) == '^');
        StringBuilder sb = new StringBuilder(negate ? "[^/" : "[");
        int i = negate ? i0 + 1 : i0;
        boolean first = true;
        while (i < end) {
            char c = p.charAt(i);
            if (c == ']' && !first) {
                sb.append(']');
                return new Piece(sb.toString(), i + 1);
            } else if (c == '\\' && !windows && i + 1 < end) {
                sb.append('\\').append(p.charAt(i + 1));
                i += 2;
                first = false;
            } else if (c == '-' && !first && i + 1 < end && p.charAt(i + 1) != ']') {
                sb.append('-');
                i++;
            } else {
                // Java 字符类里 & 连写是交集 ⇒ 也转义
                if ("\\[]^-&".indexOf(c) >= 0) sb.append('\\');
                sb.append(c);
                i++;
                first = false;
            }
        }
        return null;
    }

    /** {@code {a,b}} ⇒ 正则；没闭合 ⇒ {@code null}。 */
    private static Piece tryAlternation(String p, int open, int end, boolean windows) {
        int i = open + 1;
        int depth = 0;
        int partStart = open + 1;
        List<int[]> parts = new ArrayList<>();
        while (i < end) {
            char c = p.charAt(i);
            if (c == '\\' && !windows) {
                i += 2;
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                if (depth == 0) {
                    parts.add(new int[] {partStart, i});
                    StringBuilder sb = new StringBuilder("(?:");
                    for (int k = 0; k < parts.size(); k++) {
                        if (k > 0) sb.append('|');
                        sb.append(translate(p, parts.get(k)[0], parts.get(k)[1], windows));
                    }
                    return new Piece(sb.append(')').toString(), i + 1);
                }
                depth--;
                i++;
            } else if (c == ',' && depth == 0) {
                parts.add(new int[] {partStart, i});
                partStart = i + 1;
                i++;
            } else {
                i++;
            }
        }
        return null;
    }

    private static String translate(String p, int start, int end, boolean windows) {
        StringBuilder sb = new StringBuilder();
        int i = start;
        while (i < end) {
            char c = p.charAt(i);
            switch (c) {
                case '*' -> {
                    boolean dbl = i + 1 < end && p.charAt(i + 1) == '*';
                    boolean atStart = i == start || p.charAt(i - 1) == '/';
                    int j = i + 2;
                    boolean atEnd = j == end || (j < end && p.charAt(j) == '/');
                    if (dbl && atStart && atEnd) {
                        if (j == end) {
                            sb.append(".*");
                            i = j;
                        } else {
                            sb.append("(?:.*/)?");
                            i = j + 1;
                        }
                    } else {
                        sb.append("[^/]*");
                        i++;
                    }
                }
                case '?' -> {
                    sb.append("[^/]");
                    i++;
                }
                case '[' -> {
                    Piece cls = tryClass(p, i, end, windows);
                    if (cls != null) {
                        sb.append(cls.regex());
                        i = cls.next();
                    } else {
                        sb.append("\\[");
                        i++;
                    }
                }
                case '{' -> {
                    Piece alt = tryAlternation(p, i, end, windows);
                    if (alt != null) {
                        sb.append(alt.regex());
                        i = alt.next();
                    } else {
                        sb.append("\\{");
                        i++;
                    }
                }
                default -> {
                    if (c == '\\' && !windows && i + 1 < end) {
                        sb.append(escape(p.charAt(i + 1)));
                        i += 2;
                    } else {
                        sb.append(escape(c));
                        i++;
                    }
                }
            }
        }
        return sb.toString();
    }
}
