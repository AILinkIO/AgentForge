package io.ailink.agentforge.sandbox.execpolicy;

import java.util.ArrayList;
import java.util.List;

/** POSIX shlex 的切词 / 拼接（Rust shlex crate 的 split / try_join 等价物）。 */
public final class Shlex {
    private Shlex() {
    }

    /** 切词；引号没闭合 ⇒ {@code null}。 */
    public static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inWord = false;
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (inWord) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inWord = false;
                }
                i++;
            } else if (c == '\'') {
                int e = s.indexOf('\'', i + 1);
                if (e < 0) return null;
                cur.append(s, i + 1, e);
                inWord = true;
                i = e + 1;
            } else if (c == '"') {
                int j = i + 1;
                boolean closed = false;
                while (j < n) {
                    char d = s.charAt(j);
                    if (d == '"') {
                        closed = true;
                        break;
                    }
                    if (d == '\\' && j + 1 < n && "$`\"\\\n".indexOf(s.charAt(j + 1)) >= 0) {
                        if (s.charAt(j + 1) != '\n') cur.append(s.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    cur.append(d);
                    j++;
                }
                if (!closed) return null;
                inWord = true;
                i = j + 1;
            } else if (c == '\\') {
                if (i + 1 >= n) return null;
                if (s.charAt(i + 1) != '\n') {
                    cur.append(s.charAt(i + 1));
                    inWord = true;
                }
                i += 2;
            } else if (c == '#' && !inWord) {
                while (i < n && s.charAt(i) != '\n') i++;
            } else {
                cur.append(c);
                inWord = true;
                i++;
            }
        }
        if (inWord) out.add(cur.toString());
        return out;
    }

    /** 拼成一行 shell 文本（需要时加单引号）。 */
    public static String join(List<String> words) {
        List<String> parts = new ArrayList<>();
        for (String w : words) parts.add(quote(w));
        return String.join(" ", parts);
    }

    public static String quote(String w) {
        if (w.isEmpty()) return "''";
        boolean safe = true;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!(Character.isLetterOrDigit(c) || "@%+=:,./-_".indexOf(c) >= 0)) {
                safe = false;
                break;
            }
        }
        return safe ? w : "'" + w.replace("'", "'\"'\"'") + "'";
    }
}
