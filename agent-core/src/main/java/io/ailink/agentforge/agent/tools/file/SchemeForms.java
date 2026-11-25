package io.ailink.agentforge.agent.tools.file;

import java.util.ArrayList;
import java.util.List;

/**
 * Scheme 顶层形式扫描（form.ss 里 {@code scheme-top-forms} 的移植；原版直接用 Chez 的 reader）。
 *
 * <p>只认结构：列表 / 向量 / 字符串 / 字符字面量 / 三种注释 / 引号前缀 / {@code #!指令}，
 * 够判"一个形式从哪到哪、叫什么"。范围规矩：同一行的尾注归上面那个形式，独立成行的注释归下面这个。
 */
public final class SchemeForms {
    private SchemeForms() { }

    /** 一个顶层形式：[start, end) 字符偏移；name 匿名为 null。 */
    public record TopForm(int start, int end, String name) { }

    /** 扫描结果：解析得动的那些形式 + 从哪儿起读不动（null = 整篇都读得动）。 */
    public record Result(List<TopForm> forms, Integer stop) { }

    /** 读出来的 datum（只保留取名字要用的信息）。 */
    private sealed interface Datum permits Atom, Seq { }

    private record Atom(String text, boolean symbol) implements Datum { }

    private record Seq(List<Datum> items, boolean list) implements Datum { }

    private static final class ReadError extends RuntimeException {
        ReadError() { super(null, null, false, false); }
    }

    private static final Object EOF = new Object();

    public static Result scan(String text) {
        String t = stripShebang(text);
        Reader r = new Reader(t);
        List<TopForm> acc = new ArrayList<>();
        int prev = 0;
        boolean first = true;
        while (true) {
            int p0 = r.pos;
            Object d;
            try {
                d = r.read();
            } catch (ReadError e) {
                return new Result(acc, formStart(t, p0, prev, first));
            }
            if (d == EOF) return new Result(acc, null);
            int start = formStart(t, p0, prev, first);
            int end = r.pos;
            acc.add(new TopForm(start, end, datumName((Datum) d)));
            prev = end;
            first = false;
        }
    }

    /** {@code #!/usr/bin/env …} 不是 reader 指令；换成等长空格（偏移要对得上）。 */
    static String stripShebang(String text) {
        if (!text.startsWith("#!/")) return text;
        int nl = text.indexOf('\n');
        if (nl < 0) return text;
        return " ".repeat(nl) + text.substring(nl);
    }

    private static int formStart(String text, int p0, int prev, boolean first) {
        int n = text.length();
        int i = first ? p0 : skipSameLineComment(text, Math.max(p0, prev), n);
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** 这一行剩下的只有空白 + 可选的 ; 注释，就跳到下一行开头。只跳一行。 */
    private static int skipSameLineComment(String text, int i, int n) {
        boolean semi = false;
        for (int j = i; j < n; j++) {
            char c = text.charAt(j);
            if (c == '\n') return j + 1;
            if (c == ';') semi = true;
            else if (!semi && !Character.isWhitespace(c)) return i;
        }
        return i;
    }

    private static String datumName(Datum d) {
        if (!(d instanceof Seq s) || !s.list() || s.items().isEmpty()) return null;
        if (!(s.items().getFirst() instanceof Atom h) || !h.symbol()) return null;
        String head = h.text();
        if (head.equals("library") || head.equals("module")) {
            return s.items().size() > 1 ? write(s.items().get(1)) : null;
        }
        if (head.startsWith("define")) return s.items().size() > 1 ? headSymbol(s.items().get(1)) : null;
        return null;
    }

    private static String headSymbol(Datum x) {
        if (x instanceof Atom a) return a.symbol() ? a.text() : null;
        Seq s = (Seq) x;
        if (!s.list() || s.items().isEmpty()) return null;
        return headSymbol(s.items().getFirst());
    }

    private static String write(Datum d) {
        if (d instanceof Atom a) return a.text();
        Seq s = (Seq) d;
        StringBuilder sb = new StringBuilder(s.list() ? "(" : "#(");
        for (int i = 0; i < s.items().size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(write(s.items().get(i)));
        }
        return sb.append(')').toString();
    }

    private static final class Reader {
        final String s;
        final int n;
        int pos;

        Reader(String s) { this.s = s; this.n = s.length(); }

        char at(int k) { return k < n ? s.charAt(k) : '\0'; }

        static boolean delim(char c) {
            return Character.isWhitespace(c) || c == '(' || c == ')' || c == '[' || c == ']' || c == '"' || c == ';';
        }

        /** 跳过空白与注释；遇到 #; 读掉下一个 datum。 */
        void skipAtmosphere() {
            while (pos < n) {
                char c = s.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == ';') {
                    while (pos < n && s.charAt(pos) != '\n') pos++;
                    continue;
                }
                if (c == '#' && at(pos + 1) == '|') {
                    pos += 2;
                    int depth = 1;
                    while (depth > 0) {
                        if (pos >= n) throw new ReadError();
                        if (s.startsWith("#|", pos)) { depth++; pos += 2; }
                        else if (s.startsWith("|#", pos)) { depth--; pos += 2; }
                        else pos++;
                    }
                    continue;
                }
                if (c == '#' && at(pos + 1) == ';') {
                    pos += 2;
                    Object d = read();
                    if (d == EOF) throw new ReadError();
                    continue;
                }
                if (c == '#' && at(pos + 1) == '!' && Character.isLetter(at(pos + 2))) {
                    // #!chezscheme / #!r6rs / #!fold-case：reader 指令，当注释
                    pos += 2;
                    while (pos < n && !delim(s.charAt(pos))) pos++;
                    continue;
                }
                return;
            }
        }

        Object read() {
            skipAtmosphere();
            if (pos >= n) return EOF;
            char c = s.charAt(pos);
            switch (c) {
                case '(', '[' -> { pos++; return list(c == '(' ? ')' : ']', true); }
                case ')', ']' -> throw new ReadError();
                case '"' -> { string(); return new Atom("\"…\"", false); }
                case '\'' -> { pos++; return prefixed("quote"); }
                case '`' -> { pos++; return prefixed("quasiquote"); }
                case ',' -> {
                    pos++;
                    if (at(pos) == '@') { pos++; return prefixed("unquote-splicing"); }
                    return prefixed("unquote");
                }
                case '|' -> {
                    int st = ++pos;
                    while (pos < n && s.charAt(pos) != '|') pos++;
                    if (pos >= n) throw new ReadError();
                    String name = s.substring(st, pos++);
                    return new Atom(name, true);
                }
                case '#' -> { return hash(); }
                default -> { return atom(); }
            }
        }

        private Object prefixed(String head) {
            Object d = read();
            if (d == EOF) throw new ReadError();
            return new Seq(List.of(new Atom(head, true), (Datum) d), true);
        }

        private Object hash() {
            char c1 = at(pos + 1);
            if (c1 == '(') { pos += 2; return list(')', false); }
            if (c1 == '\\') {
                pos += 2;
                if (pos >= n) throw new ReadError();
                pos += Character.charCount(s.codePointAt(pos));
                while (pos < n && !delim(s.charAt(pos))) pos++;
                return new Atom("#\\", false);
            }
            if (c1 == '\'' || c1 == '`' || c1 == ',') {
                pos += 2;
                if (c1 == ',' && at(pos) == '@') pos++;
                return prefixed("syntax");
            }
            // #vu8( / #2( 之类：前缀后跟括号是向量
            int k = pos + 1;
            while (k < n && Character.isLetterOrDigit(s.charAt(k))) k++;
            if (k > pos + 1 && at(k) == '(') { pos = k + 1; return list(')', false); }
            return atom();
        }

        private Object list(char close, boolean isList) {
            List<Datum> items = new ArrayList<>();
            while (true) {
                skipAtmosphere();
                if (pos >= n) throw new ReadError();
                char c = s.charAt(pos);
                if (c == ')' || c == ']') {
                    if (c != close) throw new ReadError();
                    pos++;
                    return new Seq(items, isList);
                }
                // 点对 (a . b)
                if (c == '.' && delim(at(pos + 1)) && isList) { pos++; continue; }
                Object d = read();
                if (d == EOF) throw new ReadError();
                items.add((Datum) d);
            }
        }

        private void string() {
            pos++;
            while (pos < n) {
                char c = s.charAt(pos);
                if (c == '\\') { pos += 2; continue; }
                if (c == '"') { pos++; return; }
                pos++;
            }
            throw new ReadError();
        }

        private Object atom() {
            int st = pos;
            while (pos < n && !delim(s.charAt(pos))) {
                if (s.charAt(pos) == '|') {   // 符号中间的 |…| 段
                    int e = s.indexOf('|', pos + 1);
                    if (e < 0) throw new ReadError();
                    pos = e + 1;
                    continue;
                }
                pos++;
            }
            if (pos == st) { pos++; throw new ReadError(); }
            String tok = s.substring(st, pos);
            return new Atom(tok, isSymbol(tok));
        }

        private static boolean isSymbol(String tok) {
            if (tok.startsWith("#")) return false;
            char c = tok.charAt(0);
            if (Character.isDigit(c)) return false;
            if ((c == '+' || c == '-' || c == '.') && tok.length() > 1 && (Character.isDigit(tok.charAt(1)) || tok.charAt(1) == '.')) return false;
            return true;
        }
    }
}
