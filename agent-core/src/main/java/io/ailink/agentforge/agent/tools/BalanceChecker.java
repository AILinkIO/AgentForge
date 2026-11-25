package io.ailink.agentforge.agent.tools;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 默认检查器：括号 / 引号 / 注释是否闭合（按扩展名认语言，认不出回 null = 看不了）。
 *
 * <p>它不是解析器，只答"括号配不配得上"。判的是"有没有变多"（见 {@link Check}），所以偶尔的误报
 * （例如 JS 正则字面量里的括号）只要改动前后都在就不拦人。位置照实说成"从这里开始的形式没闭合"，
 * 不猜"该补在哪儿"。
 */
public final class BalanceChecker implements Checker {

    private enum Sq { NONE, FULL, CHAR }

    private record Lang(boolean slashComments, boolean hashComments, boolean semiComments, boolean blockComments,
                        boolean nestedBlock, Sq sq, boolean dqMultiline, boolean backtick, boolean tripleQuotes,
                        boolean lispChars, boolean lispBlock, boolean clojureChars, boolean rustRaw) { }

    private static final Lang C = new Lang(true, false, false, true, false, Sq.CHAR, false, false, false, false, false, false, false);
    private static final Lang JS = new Lang(true, false, false, true, false, Sq.FULL, false, true, false, false, false, false, false);
    private static final Lang GO = new Lang(true, false, false, true, false, Sq.CHAR, false, true, false, false, false, false, false);
    private static final Lang RUST = new Lang(true, false, false, true, true, Sq.CHAR, true, false, false, false, false, false, true);
    private static final Lang CSS = new Lang(false, false, false, true, false, Sq.FULL, false, false, false, false, false, false, false);
    private static final Lang JSON = new Lang(false, false, false, false, false, Sq.NONE, false, false, false, false, false, false, false);
    private static final Lang JSONC = new Lang(true, false, false, true, false, Sq.NONE, false, false, false, false, false, false, false);
    private static final Lang PY = new Lang(false, true, false, false, false, Sq.FULL, false, false, true, false, false, false, false);
    private static final Lang SCHEME = new Lang(false, false, true, false, false, Sq.NONE, true, false, false, true, true, false, false);
    private static final Lang CLJ = new Lang(false, false, true, false, false, Sq.NONE, true, false, false, false, false, true, false);

    private static final Map<String, Lang> BY_EXT = Map.ofEntries(
            Map.entry("c", C), Map.entry("h", C), Map.entry("cc", C), Map.entry("cpp", C), Map.entry("cxx", C),
            Map.entry("hpp", C), Map.entry("hh", C), Map.entry("hxx", C), Map.entry("java", C), Map.entry("cs", C),
            Map.entry("kt", C), Map.entry("kts", C), Map.entry("scala", C), Map.entry("swift", C),
            Map.entry("go", GO), Map.entry("rs", RUST),
            Map.entry("js", JS), Map.entry("mjs", JS), Map.entry("cjs", JS), Map.entry("jsx", JS),
            Map.entry("ts", JS), Map.entry("tsx", JS), Map.entry("mts", JS), Map.entry("cts", JS), Map.entry("dart", JS),
            Map.entry("css", CSS), Map.entry("scss", JSONC), Map.entry("less", JSONC),
            Map.entry("json", JSON), Map.entry("jsonc", JSONC),
            Map.entry("py", PY), Map.entry("pyi", PY),
            Map.entry("ss", SCHEME), Map.entry("scm", SCHEME), Map.entry("sls", SCHEME), Map.entry("sps", SCHEME),
            Map.entry("sch", SCHEME), Map.entry("rkt", SCHEME), Map.entry("lisp", SCHEME), Map.entry("lsp", SCHEME),
            Map.entry("cl", SCHEME), Map.entry("el", SCHEME),
            Map.entry("clj", CLJ), Map.entry("cljs", CLJ), Map.entry("cljc", CLJ), Map.entry("edn", CLJ));

    private static final int MAX_PROBLEMS = 100;

    @Override
    public List<String> problems(String path, String text) {
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot < 0 || dot < slash) return null;
        Lang lang = BY_EXT.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (lang == null) return null;
        return scan(lang, text);
    }

    private record Open(char c, int line, int col) { }

    private static final class Scan {
        final String s;
        final int n;
        int i, line = 1, col = 1;
        final List<String> out = new ArrayList<>();

        Scan(String s) { this.s = s; this.n = s.length(); }

        char at(int k) { return k < n ? s.charAt(k) : '\0'; }

        void adv() {
            if (i < n && s.charAt(i) == '\n') { line++; col = 1; } else col++;
            i++;
        }

        void adv(int k) { for (int j = 0; j < k; j++) adv(); }

        boolean starts(String p) { return s.startsWith(p, i); }

        void problem(String p) { if (out.size() < MAX_PROBLEMS) out.add(p); }
    }

    private static String pos(int line, int col) { return "第 " + line + " 行第 " + col + " 列"; }

    private static char closerOf(char o) { return o == '(' ? ')' : o == '[' ? ']' : '}'; }

    private static List<String> scan(Lang L, String text) {
        Scan sc = new Scan(text);
        Deque<Open> stack = new ArrayDeque<>();
        while (sc.i < sc.n) {
            char c = sc.at(sc.i);
            int l0 = sc.line, c0 = sc.col;
            // 注释
            if (L.slashComments && c == '/' && sc.at(sc.i + 1) == '/') { skipLine(sc); continue; }
            if (L.hashComments && c == '#') { skipLine(sc); continue; }
            if (L.semiComments && c == ';') { skipLine(sc); continue; }
            if (L.blockComments && c == '/' && sc.at(sc.i + 1) == '*') {
                if (!skipBlock(sc, "/*", "*/", L.nestedBlock)) sc.problem(pos(l0, c0) + " 开始的块注释没闭合");
                continue;
            }
            if (L.lispBlock && c == '#' && sc.at(sc.i + 1) == '|') {
                if (!skipBlock(sc, "#|", "|#", true)) sc.problem(pos(l0, c0) + " 开始的块注释没闭合");
                continue;
            }
            // 字符字面量
            if (L.lispChars && c == '#' && sc.at(sc.i + 1) == '\\') {
                sc.adv(3);
                while (sc.i < sc.n && !delimiter(sc.at(sc.i))) sc.adv();
                continue;
            }
            if (L.clojureChars && c == '\\') {
                sc.adv(2);
                while (sc.i < sc.n && !delimiter(sc.at(sc.i))) sc.adv();
                continue;
            }
            // 字符串
            if (L.rustRaw && c == 'r' && (sc.at(sc.i + 1) == '"' || sc.at(sc.i + 1) == '#')
                    && (sc.i == 0 || !Character.isLetterOrDigit(sc.at(sc.i - 1)) && sc.at(sc.i - 1) != '_')) {
                int k = sc.i + 1, hashes = 0;
                while (sc.at(k) == '#') { hashes++; k++; }
                if (sc.at(k) == '"') {
                    String close = "\"" + "#".repeat(hashes);
                    sc.adv(k - sc.i + 1);
                    int end = sc.s.indexOf(close, sc.i);
                    if (end < 0) { sc.problem(pos(l0, c0) + " 开始的字符串没闭合"); sc.adv(sc.n - sc.i); }
                    else sc.adv(end + close.length() - sc.i);
                    continue;
                }
            }
            if (L.tripleQuotes && (sc.starts("\"\"\"") || sc.starts("'''"))) {
                String q = sc.s.substring(sc.i, sc.i + 3);
                sc.adv(3);
                boolean closed = false;
                while (sc.i < sc.n) {
                    if (sc.at(sc.i) == '\\') { sc.adv(2); continue; }
                    if (sc.starts(q)) { sc.adv(3); closed = true; break; }
                    sc.adv();
                }
                if (!closed) sc.problem(pos(l0, c0) + " 开始的字符串没闭合");
                continue;
            }
            if (c == '"') { string(sc, '"', L.dqMultiline, l0, c0); continue; }
            if (c == '\'' && L.sq == Sq.FULL) { string(sc, '\'', false, l0, c0); continue; }
            if (c == '\'' && L.sq == Sq.CHAR) {
                int end = charLiteralEnd(sc);
                if (end > 0) { sc.adv(end - sc.i); continue; }
                sc.adv();
                continue;
            }
            if (c == '`' && L.backtick) { string(sc, '`', true, l0, c0); continue; }
            // 括号
            if (c == '(' || c == '[' || c == '{') {
                stack.push(new Open(c, l0, c0));
                sc.adv();
                continue;
            }
            if (c == ')' || c == ']' || c == '}') {
                if (stack.isEmpty()) {
                    sc.problem(pos(l0, c0) + "：多出来一个 '" + c + "'");
                } else if (closerOf(stack.peek().c) == c) {
                    stack.pop();
                } else if (stack.stream().anyMatch(o -> closerOf(o.c) == c)) {
                    while (closerOf(stack.peek().c) != c) {
                        Open o = stack.pop();
                        sc.problem(pos(o.line, o.col) + " 开始的 '" + o.c + "' 没闭合（到 " + pos(l0, c0) + " 的 '" + c + "' 时还开着）");
                    }
                    stack.pop();
                } else {
                    Open o = stack.peek();
                    sc.problem(pos(l0, c0) + " 的 '" + c + "' 与 " + pos(o.line, o.col) + " 的 '" + o.c + "' 对不上");
                }
                sc.adv();
                continue;
            }
            sc.adv();
        }
        List<Open> rest = new ArrayList<>(stack);
        java.util.Collections.reverse(rest);
        for (Open o : rest) sc.problem(pos(o.line, o.col) + " 开始的 '" + o.c + "' 没闭合");
        return sc.out;
    }

    private static boolean delimiter(char c) {
        return Character.isWhitespace(c) || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '"' || c == ';';
    }

    private static void skipLine(Scan sc) {
        while (sc.i < sc.n && sc.at(sc.i) != '\n') sc.adv();
    }

    private static boolean skipBlock(Scan sc, String open, String close, boolean nested) {
        sc.adv(open.length());
        int depth = 1;
        while (sc.i < sc.n) {
            if (nested && sc.starts(open)) { depth++; sc.adv(open.length()); continue; }
            if (sc.starts(close)) {
                sc.adv(close.length());
                if (--depth == 0) return true;
                continue;
            }
            sc.adv();
        }
        return false;
    }

    private static void string(Scan sc, char q, boolean multiline, int l0, int c0) {
        sc.adv();
        while (sc.i < sc.n) {
            char c = sc.at(sc.i);
            if (c == '\\' && q != '`') { sc.adv(2); continue; }
            if (c == '\\') { sc.adv(2); continue; }
            if (c == q) { sc.adv(); return; }
            if (c == '\n' && !multiline) {
                sc.problem(pos(l0, c0) + " 开始的字符串到行尾还没闭合");
                return;
            }
            sc.adv();
        }
        sc.problem(pos(l0, c0) + " 开始的字符串没闭合");
    }

    /** 'a' / '\n' 这类字符字面量的结尾（不含）；不像字符字面量（Rust 生命周期等）回 -1。 */
    private static int charLiteralEnd(Scan sc) {
        int i = sc.i;
        if (sc.at(i + 1) == '\\') {
            for (int k = i + 2; k < Math.min(sc.n, i + 14); k++) {
                char c = sc.at(k);
                if (c == '\n') return -1;
                if (c == '\'' && k > i + 2) return k + 1;
            }
            return -1;
        }
        if (i + 1 >= sc.n || sc.at(i + 1) == '\n' || sc.at(i + 1) == '\'') return -1;
        int cp = sc.s.codePointAt(i + 1);
        int k = i + 1 + Character.charCount(cp);
        return sc.at(k) == '\'' ? k + 1 : -1;
    }
}
