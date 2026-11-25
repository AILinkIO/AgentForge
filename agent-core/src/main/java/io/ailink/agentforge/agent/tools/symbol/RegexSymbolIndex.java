package io.ailink.agentforge.agent.tools.symbol;

import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.search.FileWalker;
import io.ailink.agentforge.agent.util.Fmt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 没有真索引时的默认 {@link SymbolIndex}：按语言的正则抽定义，词边界 grep 当引用。
 *
 * <p>覆盖 Java / Kotlin / JS / TS / Python / Go / Rust / C / C++ / Clojure / Scheme / Common Lisp。
 * 候选文件与 search-text 同一套（git 会看的文件）；每个文件按 (mtime, size) 缓存一份定义表，
 * 第二次查只重扫变过的文件。函数 / 方法的结束行用括号配平（Python 用缩进）估出来，
 * 给 find-symbol 的 source=true 用。
 *
 * <p>精度是正则级别的：会漏（宏生成的定义、奇怪的排版），偶尔会多（看起来像定义的调用）。
 */
public final class RegexSymbolIndex implements SymbolIndex {
    static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    static final int MAX_REFS = 5000;
    static final int MAX_BODY_LINES = 2000;

    private final Workspace ws;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public RegexSymbolIndex(Workspace ws) { this.ws = ws; }

    private record Cached(long mtime, long size, List<Def> defs) { }

    // ── 语言表 ─────────────────────────────────────────────────────────

    enum Body { BRACE, PAREN, INDENT, NONE }

    record Rule(Pattern re, int nameGroup, String kind, String kindFromGroup) { }

    record Lang(String name, Set<String> exts, Body body, List<Rule> rules, Pattern importLine) { }

    private static Rule r(String re, int g, String kind) { return new Rule(Pattern.compile(re), g, kind, null); }

    private static final String JAVA_MODS = "(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp|synchronized|native|default|transient|volatile)\\s+)*";

    static final List<Lang> LANGS = List.of(
            new Lang("Java", Set.of("java"), Body.BRACE, List.of(
                    r("^\\s*" + JAVA_MODS + "(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)", 1, "class"),
                    r("^\\s*" + JAVA_MODS + "(?:<[^>]*>\\s*)?(?!(?:return|new|else|throw|case|if|for|while|switch|catch|do|try|yield|assert)\\b)[\\w$.<>\\[\\]?,]+(?:\\s*<[^>]*>)?(?:\\[\\])*\\s+([A-Za-z_$][\\w$]*)\\s*\\((?:[^;{]*$|[^)]*\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?\\{.*)", 1, "method")),
                    Pattern.compile("^\\s*import\\b")),
            new Lang("Kotlin", Set.of("kt", "kts"), Body.BRACE, List.of(
                    r("^\\s*(?:(?:public|private|internal|protected|open|abstract|sealed|data|enum|annotation|inner|value|inline)\\s+)*(?:class|interface|object)\\s+([A-Za-z_]\\w*)", 1, "class"),
                    r("^\\s*(?:(?:public|private|internal|protected|open|abstract|override|suspend|inline|operator|infix|tailrec|external)\\s+)*fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?([A-Za-z_]\\w*)\\s*\\(", 1, "function"),
                    r("^\\s*typealias\\s+([A-Za-z_]\\w*)", 1, "type"),
                    r("^(?:(?:public|private|internal|const)\\s+)*(?:val|var)\\s+([A-Za-z_]\\w*)", 1, "variable")),
                    Pattern.compile("^\\s*import\\b")),
            new Lang("JavaScript/TypeScript", Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts"), Body.BRACE, List.of(
                    r("\\bfunction\\s*\\*?\\s*([A-Za-z_$][\\w$]*)\\s*[(<]", 1, "function"),
                    r("^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+([A-Za-z_$][\\w$]*)", 1, "class"),
                    r("^\\s*(?:export\\s+)?(?:declare\\s+)?(?:interface|type|enum)\\s+([A-Za-z_$][\\w$]*)", 1, "type"),
                    r("^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*(?::[^=]+)?=>|[A-Za-z_$][\\w$]*\\s*=>)", 1, "function"),
                    r("^\\s+(?:(?:public|private|protected|static|async|readonly|override|get|set)\\s+)*(?!(?:if|for|while|switch|catch|function|return|else|do)\\b)([A-Za-z_$][\\w$]*)\\s*(?:<[^>]*>)?\\([^)]*\\)\\s*(?::[^{]*)?\\{\\s*$", 1, "method")),
                    Pattern.compile("^\\s*import\\b|\\brequire\\s*\\(|^\\s*export\\s+.*\\bfrom\\b")),
            new Lang("Python", Set.of("py", "pyi"), Body.INDENT, List.of(
                    new Rule(Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_]\\w*)"), 2, null, "def"),
                    r("^\\s*class\\s+([A-Za-z_]\\w*)", 1, "class")),
                    Pattern.compile("^\\s*(?:from\\s+\\S+\\s+)?import\\b")),
            new Lang("Go", Set.of("go"), Body.BRACE, List.of(
                    new Rule(Pattern.compile("^func\\s+(\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)"), 2, null, "gofunc"),
                    r("^type\\s+([A-Za-z_]\\w*)", 1, "type"),
                    r("^(?:var|const)\\s+([A-Za-z_]\\w*)", 1, "variable")),
                    Pattern.compile("^\\s*import\\b|^\\s*\"[\\w./-]+\"\\s*$")),
            new Lang("Rust", Set.of("rs"), Body.BRACE, List.of(
                    r("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:(?:const|async|unsafe|extern(?:\\s+\"[^\"]*\")?)\\s+)*fn\\s+([A-Za-z_]\\w*)", 1, "function"),
                    r("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:struct|enum|trait|union|type)\\s+([A-Za-z_]\\w*)", 1, "type"),
                    r("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?mod\\s+([A-Za-z_]\\w*)", 1, "module"),
                    r("^\\s*macro_rules!\\s*([A-Za-z_]\\w*)", 1, "macro"),
                    r("^\\s*(?:pub(?:\\([^)]*\\))?\\s+)?(?:const|static)\\s+(?:mut\\s+)?([A-Za-z_]\\w*)", 1, "variable")),
                    Pattern.compile("^\\s*(?:pub\\s+)?use\\b|^\\s*extern\\s+crate\\b")),
            new Lang("C/C++", Set.of("c", "h", "cc", "cpp", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "inl"), Body.BRACE, List.of(
                    r("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)", 1, "macro"),
                    r("^\\s*(?:template\\s*<[^>]*>\\s*)?(?:typedef\\s+)?(?:class|struct|union|enum(?:\\s+class|\\s+struct)?)\\s+(?:\\[\\[[^\\]]*\\]\\]\\s*)?(?:[A-Z_][A-Z0-9_]*\\s+)?([A-Za-z_]\\w*)\\s*(?:final\\s*)?(?:[:{]|$)", 1, "type"),
                    r("^\\s*typedef\\b.*?\\b([A-Za-z_]\\w*)\\s*;\\s*$", 1, "type"),
                    r("^\\s*namespace\\s+([A-Za-z_][\\w:]*)", 1, "module"),
                    r("^(?=[A-Za-z_~])(?!(?:if|for|while|switch|return|else|do|case|goto|sizeof|delete|new|throw|typedef)\\b)(?:[A-Za-z_][\\w:<>,*&\\s]*?[\\s*&])?((?:[A-Za-z_]\\w*::)*~?[A-Za-z_]\\w*)\\s*\\([^;]*$", 1, "function")),
                    Pattern.compile("^\\s*#\\s*(?:include|import)\\b")),
            new Lang("Clojure", Set.of("clj", "cljs", "cljc", "bb"), Body.PAREN, List.of(
                    new Rule(Pattern.compile("^\\s*\\((defn-?|defmacro|defmulti|defmethod|defprotocol|defrecord|deftype|definterface|defonce|def|ns|deftest|defstruct)\\s+(?:\\^[^\\s]+\\s+)*([^\\s()\\[\\]{}\"]+)"), 2, null, "clj")),
                    Pattern.compile("\\(:require\\b|\\(:import\\b|\\(require\\b|\\(:use\\b")),
            new Lang("Scheme", Set.of("ss", "scm", "sls", "sld", "rkt", "sps"), Body.PAREN, List.of(
                    r("^\\s*\\((?:library|define-library)\\s+(\\([^()]*\\))", 1, "module"),
                    new Rule(Pattern.compile("^\\s*\\((define(?:-syntax|-record-type|-values|-tool|-rule|-inline|-integrable|-structure)?|define\\*)\\s+\\(?\\s*([^\\s()\\[\\]]+)"), 2, null, "scm")),
                    Pattern.compile("^\\s*\\((?:import|only|prefix|except|rename|require)\\b")),
            new Lang("Common Lisp", Set.of("lisp", "lsp", "cl", "asd"), Body.PAREN, List.of(
                    new Rule(Pattern.compile("(?i)^\\s*\\((defun|defmacro|defgeneric|defmethod|defclass|defstruct|defvar|defparameter|defconstant|deftype|defpackage|define-condition)\\s+\\(?([^\\s()]+)"), 2, null, "cl")),
                    Pattern.compile("(?i)\\(:use\\b|\\(:import-from\\b|\\(ql:quickload\\b|\\(require\\b")));

    static Lang langOf(String rel) {
        int dot = rel.lastIndexOf('.');
        if (dot < 0 || dot < rel.lastIndexOf('/')) return null;
        String ext = rel.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Lang l : LANGS) if (l.exts.contains(ext)) return l;
        return null;
    }

    private static String kindOf(Rule r, Matcher m) {
        if (r.kind != null) return r.kind;
        return switch (r.kindFromGroup) {
            case "def" -> m.group(1).isEmpty() ? "function" : "method";
            case "gofunc" -> m.group(1) == null ? "function" : "method";
            case "clj" -> switch (m.group(1)) {
                case "defn", "defn-", "defmethod", "deftest" -> "function";
                case "defmacro" -> "macro";
                case "ns" -> "module";
                case "defprotocol", "defrecord", "deftype", "definterface", "defstruct" -> "type";
                case "defmulti" -> "function";
                default -> "variable";
            };
            case "scm" -> {
                String f = m.group(1);
                if (f.equals("define-syntax")) yield "macro";
                if (f.equals("define-record-type") || f.equals("define-structure")) yield "type";
                if (f.equals("define-tool")) yield "function";
                // (define (f …)) 是函数，(define x …) 是变量
                int open = m.end(1);
                String rest = m.group(0).substring(open - m.start(0)).stripLeading();
                yield rest.startsWith("(") ? "function" : "variable";
            }
            case "cl" -> switch (m.group(1).toLowerCase(Locale.ROOT)) {
                case "defun", "defgeneric", "defmethod" -> "function";
                case "defmacro" -> "macro";
                case "defclass", "defstruct", "deftype", "define-condition" -> "type";
                case "defpackage" -> "module";
                default -> "variable";
            };
            default -> "symbol";
        };
    }

    // ── SymbolIndex ────────────────────────────────────────────────────

    @Override public boolean enabled() { return true; }

    @Override
    public Status status() {
        List<String> names = new ArrayList<>();
        for (Lang l : LANGS) names.add(l.name);
        return Status.simple(names);
    }

    @Override
    public List<Def> find(String name, int limit) {
        String q = name.toLowerCase(Locale.ROOT);
        List<Def> hits = new ArrayList<>();
        for (String rel : files()) {
            for (Def d : defsOf(rel)) if (d.name().toLowerCase(Locale.ROOT).contains(q)) hits.add(d);
        }
        // 精确的在前，再前缀，再其余；同档按文件、行
        hits.sort(Comparator.comparingInt((Def d) -> d.name().equals(name) ? 0
                        : d.name().equalsIgnoreCase(name) ? 1 : d.name().toLowerCase(Locale.ROOT).startsWith(q) ? 2 : 3)
                .thenComparing(Def::file).thenComparingInt(Def::row));
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    @Override
    public List<Ref> refs(String name) {
        if (name == null || name.isEmpty()) return List.of();
        boolean word = name.matches("[\\w$]+");
        Pattern p = word ? Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "(?![\\w$])") : Pattern.compile(Pattern.quote(name));
        List<Ref> out = new ArrayList<>();
        for (String rel : files()) {
            Lang lang = langOf(rel);
            String text = read(rel);
            if (text == null || lang == null || !text.contains(name)) continue;
            List<Def> defs = defsOf(rel);
            List<String> lines = Fmt.lines(text);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                Matcher m = p.matcher(line);
                if (!m.find()) continue;
                final int row = i;
                if (defs.stream().anyMatch(d -> d.row() == row && d.name().equals(name))) continue; // 定义处不算引用
                String kind;
                if (lang.importLine.matcher(line).find()
                        || (!word && name.startsWith("(") && lang.name.equals("Scheme") && !line.stripLeading().startsWith("(library")
                            && !line.stripLeading().startsWith("(define-library"))) kind = "import";
                else if (word && line.substring(m.end()).stripLeading().startsWith("(")) kind = "call";
                else if (lang.body == Body.PAREN && m.start() > 0 && line.charAt(m.start() - 1) == '(') kind = "call";
                else kind = "ref";
                out.add(new Ref(rel, i, m.start(), kind, enclosing(defs, i)));
                if (out.size() >= MAX_REFS) return out;
            }
        }
        return out;
    }

    private static String enclosing(List<Def> defs, int row) {
        Def best = null;
        for (Def d : defs) {
            if (d.row() <= row && d.endRow() >= row && (best == null || d.row() > best.row())) best = d;
        }
        return best == null ? null : best.name();
    }

    // ── 文件 ───────────────────────────────────────────────────────────

    private List<String> files() {
        Path root = ws.root();
        List<String> cs = FileWalker.candidates(root, "");
        if (cs == null) cs = FileWalker.walkAll(root, "", FileWalker.Kind.FILE);
        List<String> out = new ArrayList<>();
        for (String r : cs) if (langOf(r) != null) out.add(r);
        return out;
    }

    private String read(String rel) {
        Path p = ws.root().resolve(rel);
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.size(p) > MAX_FILE_BYTES) return null;
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    List<Def> defsOf(String rel) {
        Path p = ws.root().resolve(rel);
        long mtime, size;
        try {
            mtime = Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis();
            size = Files.size(p);
        } catch (IOException e) {
            cache.remove(rel);
            return List.of();
        }
        Cached c = cache.get(rel);
        if (c != null && c.mtime == mtime && c.size == size) return c.defs;
        String text = read(rel);
        Lang lang = langOf(rel);
        List<Def> defs = text == null || lang == null ? List.of() : extract(rel, lang, text);
        cache.put(rel, new Cached(mtime, size, defs));
        return defs;
    }

    /** 一个文件的文本 → 定义表（纯函数，测试直接钉它）。 */
    static List<Def> extract(String rel, Lang lang, String text) {
        List<String> lines = Fmt.lines(text);
        List<Def> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > 1000) continue;
            String t = line.stripLeading();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || (t.startsWith("#") && lang.body != Body.BRACE)
                    || t.startsWith(";")) continue;
            for (Rule r : lang.rules) {
                Matcher m = r.re.matcher(line);
                if (!m.find()) continue;
                String name = m.group(r.nameGroup);
                if (name == null || name.isEmpty()) continue;
                if (lang.name.equals("C/C++")) {
                    int k = name.lastIndexOf("::");
                    if (k >= 0 && r.kind.equals("function")) name = name.substring(k + 2);
                }
                String kind = kindOf(r, m);
                int col = m.start(r.nameGroup);
                int end = kind.equals("function") || kind.equals("method") ? endRow(lang, lines, i) : -1;
                out.add(new Def(rel, i, col, name, kind, end));
                break;
            }
        }
        return out;
    }

    /** 定义从第 row 行起，到哪一行收尾；估不出 -1。 */
    static int endRow(Lang lang, List<String> lines, int row) {
        return switch (lang.body) {
            case BRACE -> braceEnd(lines, row);
            case PAREN -> parenEnd(lines, row);
            case INDENT -> indentEnd(lines, row);
            case NONE -> -1;
        };
    }

    private static int braceEnd(List<String> lines, int row) {
        int depth = 0;
        boolean opened = false, inBlock = false;
        for (int i = row; i < lines.size() && i < row + MAX_BODY_LINES; i++) {
            String l = lines.get(i);
            char q = 0;
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                if (inBlock) { if (c == '*' && k + 1 < l.length() && l.charAt(k + 1) == '/') { inBlock = false; k++; } continue; }
                if (q != 0) { if (c == '\\') k++; else if (c == q) q = 0; continue; }
                if (c == '/' && k + 1 < l.length() && l.charAt(k + 1) == '/') break;
                if (c == '/' && k + 1 < l.length() && l.charAt(k + 1) == '*') { inBlock = true; k++; continue; }
                if (c == '"' || c == '\'' || c == '`') { q = c; continue; }
                if (c == '{') { depth++; opened = true; }
                else if (c == '}') { depth--; if (opened && depth == 0) return i; }
                else if (c == ';' && !opened && depth == 0) return i;   // 声明，没有体
            }
        }
        return -1;
    }

    private static int parenEnd(List<String> lines, int row) {
        int depth = 0;
        boolean opened = false, inStr = false;
        for (int i = row; i < lines.size() && i < row + MAX_BODY_LINES; i++) {
            String l = lines.get(i);
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                if (inStr) { if (c == '\\') k++; else if (c == '"') inStr = false; continue; }
                if (c == '"') { inStr = true; continue; }
                if (c == ';') break;
                if (c == '#' && k + 1 < l.length() && l.charAt(k + 1) == '\\') { k += 2; continue; }   // #\( 字符字面量
                if (c == '\\' && k + 1 < l.length()) { k++; continue; }                                // Clojure \(
                if (c == '(' || c == '[' || c == '{') { depth++; opened = true; }
                else if (c == ')' || c == ']' || c == '}') { depth--; if (opened && depth == 0) return i; }
            }
        }
        return -1;
    }

    private static int indentEnd(List<String> lines, int row) {
        int base = indent(lines.get(row));
        int last = row;
        for (int i = row + 1; i < lines.size() && i < row + MAX_BODY_LINES; i++) {
            String l = lines.get(i);
            if (l.isBlank()) continue;
            if (indent(l) <= base) break;
            last = i;
        }
        return last;
    }

    private static int indent(String l) {
        int n = 0;
        while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) n++;
        return n;
    }
}
