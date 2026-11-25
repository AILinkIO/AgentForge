package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Nil;
import io.ailink.agentforge.agent.codemode.Datum.Pair;
import io.ailink.agentforge.agent.codemode.Datum.SChar;
import io.ailink.agentforge.agent.codemode.Datum.Sym;

import java.util.ArrayList;
import java.util.List;

/**
 * 读入：文本 → 数据。只认安全的那部分读法 —— 没有 {@code #%} 系统原语引用（出现就拒），
 * 没有读期求值。{@code [ ]} 与 {@code ( )} 同义（但要配对）。
 */
final class Reader {
    static final class ReadError extends RuntimeException {
        ReadError(String m) { super(m); }
    }

    private static final Object CLOSE_PAREN = new Object();
    private static final Object CLOSE_BRACKET = new Object();
    private static final Object DOT = new Object();
    private static final Object EOF = new Object();

    private final String s;
    private int i;

    private Reader(String s) { this.s = s; }

    static List<Object> readAll(String text) {
        Reader r = new Reader(text);
        List<Object> out = new ArrayList<>();
        while (true) {
            Object x = r.read();
            if (x == EOF) return out;
            if (x == CLOSE_PAREN || x == CLOSE_BRACKET) throw new ReadError("多出来一个右括号（位置 " + r.i + "）");
            if (x == DOT) throw new ReadError("非法的 .（位置 " + r.i + "）");
            out.add(x);
        }
    }

    private int peek() { return i < s.length() ? s.charAt(i) : -1; }

    private void skipAtmosphere() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == ';') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '#' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                int depth = 1;
                i += 2;
                while (depth > 0) {
                    if (i + 1 >= s.length()) throw new ReadError("块注释 #| 没有收尾");
                    if (s.charAt(i) == '|' && s.charAt(i + 1) == '#') { depth--; i += 2; }
                    else if (s.charAt(i) == '#' && s.charAt(i + 1) == '|') { depth++; i += 2; }
                    else i++;
                }
            } else if (c == '#' && i + 1 < s.length() && s.charAt(i + 1) == ';') {
                i += 2;
                Object skipped = read();
                if (skipped == EOF) throw new ReadError("#; 后面没有数据");
            } else {
                return;
            }
        }
    }

    private Object read() {
        skipAtmosphere();
        int c = peek();
        if (c < 0) return EOF;
        switch (c) {
            case '(' -> { i++; return readList(')'); }
            case '[' -> { i++; return readList(']'); }
            case ')' -> { i++; return CLOSE_PAREN; }
            case ']' -> { i++; return CLOSE_BRACKET; }
            case '\'' -> { i++; return quoted("quote"); }
            case '`' -> { i++; return quoted("quasiquote"); }
            case ',' -> {
                i++;
                if (peek() == '@') { i++; return quoted("unquote-splicing"); }
                return quoted("unquote");
            }
            case '"' -> { i++; return readString(); }
            case '#' -> { return readHash(); }
            default -> { return readAtom(); }
        }
    }

    private Object quoted(String name) {
        Object x = read();
        if (x == EOF || x == CLOSE_PAREN || x == CLOSE_BRACKET || x == DOT) throw new ReadError(name + " 后面缺数据");
        return Datum.list(Sym.of(name), x);
    }

    private Object readList(char close) {
        List<Object> items = new ArrayList<>();
        Object tail = Nil.NIL;
        while (true) {
            Object x = read();
            if (x == EOF) throw new ReadError("读到结尾了，表还没收（少了 " + close + "）");
            if (x == CLOSE_PAREN || x == CLOSE_BRACKET) {
                if ((x == CLOSE_PAREN) != (close == ')')) throw new ReadError("括号不配对：期待 " + close);
                break;
            }
            if (x == DOT) {
                if (items.isEmpty()) throw new ReadError("非法的 .");
                tail = read();
                if (tail == EOF || tail == CLOSE_PAREN || tail == CLOSE_BRACKET || tail == DOT) throw new ReadError(". 后面缺数据");
                Object end = read();
                if (end != CLOSE_PAREN && end != CLOSE_BRACKET) throw new ReadError(". 后面只能有一个数据");
                if ((end == CLOSE_PAREN) != (close == ')')) throw new ReadError("括号不配对：期待 " + close);
                break;
            }
            items.add(x);
        }
        Object r = tail;
        for (int k = items.size() - 1; k >= 0; k--) r = new Pair(items.get(k), r);
        return r;
    }

    private String readString() {
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new ReadError("字符串没有收尾的 \"");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw new ReadError("字符串没有收尾的 \"");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'a' -> b.append('\u0007');
                case 'b' -> b.append('\b');
                case '0' -> b.append('\0');
                case '\\' -> b.append('\\');
                case '"' -> b.append('"');
                case 'x' -> {
                    int semi = s.indexOf(';', i);
                    if (semi < 0) throw new ReadError("\\x 转义要以 ; 结尾");
                    try { b.appendCodePoint(Integer.parseInt(s.substring(i, semi), 16)); }
                    catch (IllegalArgumentException ex) { throw new ReadError("坏的 \\x 转义"); }
                    i = semi + 1;
                }
                case '\n', ' ', '\t' -> {
                    // 行尾续行：跳过换行与下一行开头的空白
                    while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n')) i++;
                }
                default -> throw new ReadError("不认识的转义 \\" + e);
            }
        }
    }

    private Object readHash() {
        if (i + 1 >= s.length()) throw new ReadError("# 后面缺东西");
        char c = s.charAt(i + 1);
        if (c == '(') {
            i += 2;
            Object lst = readList(')');
            List<Object> items = Datum.toList(lst);
            if (items == null) throw new ReadError("向量里不许有 .");
            return items.toArray();
        }
        if (c == '\\') {
            i += 2;
            int start = i;
            if (i >= s.length()) throw new ReadError("#\\ 后面缺字符");
            i += Character.charCount(s.codePointAt(i));
            while (i < s.length() && !delimiter(s.charAt(i))) i++;
            String tok = s.substring(start, i);
            if (tok.codePointCount(0, tok.length()) == 1) return new SChar(tok.codePointAt(0));
            return switch (tok) {
                case "space" -> new SChar(' ');
                case "newline", "linefeed" -> new SChar('\n');
                case "tab" -> new SChar('\t');
                case "return" -> new SChar('\r');
                case "nul", "null" -> new SChar(0);
                case "alarm" -> new SChar(7);
                case "backspace" -> new SChar(8);
                case "delete", "rubout" -> new SChar(127);
                case "escape", "altmode" -> new SChar(27);
                default -> {
                    if (tok.startsWith("x") && tok.length() > 1) {
                        try { yield new SChar(Integer.parseInt(tok.substring(1), 16)); }
                        catch (NumberFormatException e) { throw new ReadError("不认识的字符 #\\" + tok); }
                    }
                    throw new ReadError("不认识的字符 #\\" + tok);
                }
            };
        }
        if (c == '\'') { i += 2; return quoted("syntax"); }
        if (c == '%' || Character.isDigit(c)) throw new ReadError("不支持的读法 #" + c);
        String tok = token();
        switch (tok) {
            case "#t", "#true" -> { return Boolean.TRUE; }
            case "#f", "#false" -> { return Boolean.FALSE; }
            default -> { }
        }
        Object n = parseNumber(tok);
        if (n != null) return n;
        throw new ReadError("不认识的读法 " + tok);
    }

    private static boolean delimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '[' || c == ']' || c == '"' || c == ';';
    }

    private String token() {
        int start = i;
        while (i < s.length() && !delimiter(s.charAt(i))) i++;
        return s.substring(start, i);
    }

    private Object readAtom() {
        String tok = token();
        if (tok.equals(".")) return DOT;
        if (tok.startsWith("|") && tok.endsWith("|") && tok.length() >= 2) return Sym.of(tok.substring(1, tok.length() - 1));
        Object n = parseNumber(tok);
        if (n != null) return n;
        return Sym.of(tok);
    }

    /** 数字字面量；不是数字回 null。 */
    static Object parseNumber(String tok) { return parseNumber(tok, 10); }

    static Object parseNumber(String tok, int radix) {
        boolean exact = false;
        boolean inexact = false;
        while (tok.length() >= 2 && tok.charAt(0) == '#') {
            char p = Character.toLowerCase(tok.charAt(1));
            switch (p) {
                case 'x' -> radix = 16;
                case 'b' -> radix = 2;
                case 'o' -> radix = 8;
                case 'd' -> radix = 10;
                case 'e' -> exact = true;
                case 'i' -> inexact = true;
                default -> { return null; }
            }
            tok = tok.substring(2);
        }
        if (tok.isEmpty()) return null;
        Object v = parseReal(tok, radix);
        if (v == null) return null;
        if (inexact && v instanceof Long l) return l.doubleValue();
        if (exact && v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d)) return (long) (double) d;
            return null;
        }
        return v;
    }

    private static Object parseReal(String tok, int radix) {
        switch (tok) {
            case "+inf.0" -> { return Double.POSITIVE_INFINITY; }
            case "-inf.0" -> { return Double.NEGATIVE_INFINITY; }
            case "+nan.0", "-nan.0" -> { return Double.NaN; }
            default -> { }
        }
        int slash = tok.indexOf('/');
        if (slash > 0) {
            Object a = parseReal(tok.substring(0, slash), radix);
            Object b = parseReal(tok.substring(slash + 1), radix);
            if (a instanceof Long x && b instanceof Long y && y != 0) {
                return x % y == 0 ? (Object) (x / y) : (Object) ((double) x / y);
            }
            return null;
        }
        String body = tok.startsWith("+") || tok.startsWith("-") ? tok.substring(1) : tok;
        if (body.isEmpty()) return null;
        if (radix == 10) {
            if (!Character.isDigit(body.charAt(0)) && !(body.charAt(0) == '.' && body.length() > 1 && Character.isDigit(body.charAt(1)))) {
                return null;
            }
            boolean integral = body.chars().allMatch(Character::isDigit);
            try {
                if (integral) {
                    try { return Long.parseLong(tok); } catch (NumberFormatException e) { return Double.parseDouble(tok); }
                }
                if (!tok.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")) return null;
                return Double.parseDouble(tok);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Long.parseLong(tok, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
