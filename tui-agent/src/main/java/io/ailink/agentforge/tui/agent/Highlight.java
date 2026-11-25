package io.ailink.agentforge.tui.agent;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenMaker;
import org.fife.ui.rsyntaxtextarea.TokenMakerFactory;
import org.fife.ui.rsyntaxtextarea.TokenTypes;

import javax.swing.text.Segment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 代码块语法高亮：借 RSyntaxTextArea 的 TokenMaker（现成的 40+ 种语言词法器）分词，词类 ⇒ font-lock face。
 * 只用分词器（{@code Segment} 进、{@code Token} 链出），不建 Swing 组件，无头可用。认不出的语言 ⇒ 不着色。
 * 跨行状态（块注释、多行字符串）按 TokenMaker 的「上一行末尾的词类」接着分。
 */
final class Highlight {
    private Highlight() { }

    /** 一段着色：行内 [a, b)。 */
    record Span(int a, int b, String face) { }

    private static final Map<String, String> LANG = Map.ofEntries(
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("kotlin", SyntaxConstants.SYNTAX_STYLE_KOTLIN), Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("kts", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT), Map.entry("javascript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("jsx", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT), Map.entry("mjs", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT), Map.entry("typescript", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("tsx", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("python", SyntaxConstants.SYNTAX_STYLE_PYTHON), Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("rust", SyntaxConstants.SYNTAX_STYLE_RUST), Map.entry("rs", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("go", SyntaxConstants.SYNTAX_STYLE_GO), Map.entry("golang", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C), Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS), Map.entry("c++", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cc", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS), Map.entry("hpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cxx", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP), Map.entry("csharp", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("c#", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("clojure", SyntaxConstants.SYNTAX_STYLE_CLOJURE), Map.entry("clj", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("cljs", SyntaxConstants.SYNTAX_STYLE_CLOJURE), Map.entry("cljc", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("edn", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("lisp", SyntaxConstants.SYNTAX_STYLE_LISP), Map.entry("elisp", SyntaxConstants.SYNTAX_STYLE_LISP),
            Map.entry("emacs-lisp", SyntaxConstants.SYNTAX_STYLE_LISP), Map.entry("scheme", SyntaxConstants.SYNTAX_STYLE_LISP),
            Map.entry("common-lisp", SyntaxConstants.SYNTAX_STYLE_LISP), Map.entry("cl", SyntaxConstants.SYNTAX_STYLE_LISP),
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL), Map.entry("bash", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("zsh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL), Map.entry("shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("console", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL), Map.entry("shellsession", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bat", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH), Map.entry("cmd", SyntaxConstants.SYNTAX_STYLE_WINDOWS_BATCH),
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON), Map.entry("jsonc", SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS),
            Map.entry("json5", SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML), Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML), Map.entry("svg", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML), Map.entry("htm", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS), Map.entry("less", SyntaxConstants.SYNTAX_STYLE_LESS),
            Map.entry("scala", SyntaxConstants.SYNTAX_STYLE_SCALA),
            Map.entry("groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY), Map.entry("gradle", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("ruby", SyntaxConstants.SYNTAX_STYLE_RUBY), Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("php", SyntaxConstants.SYNTAX_STYLE_PHP), Map.entry("lua", SyntaxConstants.SYNTAX_STYLE_LUA),
            Map.entry("perl", SyntaxConstants.SYNTAX_STYLE_PERL), Map.entry("pl", SyntaxConstants.SYNTAX_STYLE_PERL),
            Map.entry("makefile", SyntaxConstants.SYNTAX_STYLE_MAKEFILE), Map.entry("make", SyntaxConstants.SYNTAX_STYLE_MAKEFILE),
            Map.entry("dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE), Map.entry("docker", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE),
            Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI), Map.entry("toml", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("dart", SyntaxConstants.SYNTAX_STYLE_DART), Map.entry("d", SyntaxConstants.SYNTAX_STYLE_D),
            Map.entry("tcl", SyntaxConstants.SYNTAX_STYLE_TCL), Map.entry("latex", SyntaxConstants.SYNTAX_STYLE_LATEX),
            Map.entry("tex", SyntaxConstants.SYNTAX_STYLE_LATEX), Map.entry("vb", SyntaxConstants.SYNTAX_STYLE_VISUAL_BASIC),
            Map.entry("fortran", SyntaxConstants.SYNTAX_STYLE_FORTRAN), Map.entry("asm", SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_X86),
            Map.entry("nsis", SyntaxConstants.SYNTAX_STYLE_NSIS), Map.entry("csv", SyntaxConstants.SYNTAX_STYLE_CSV),
            Map.entry("markdown", SyntaxConstants.SYNTAX_STYLE_MARKDOWN), Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN));

    /** 代码块信息串（```java title=… 的第一个词）⇒ RSyntaxTextArea 的语言名；认不出 ⇒ null。 */
    static String style(String info) {
        if (info == null) return null;
        String w = info.strip().split("[\\s{,]", 2)[0].toLowerCase(Locale.ROOT);
        if (w.startsWith(".")) w = w.substring(1);
        return LANG.get(w);
    }

    /** 每行的着色段（下标与 lines 对齐）；语言认不出或分词出错 ⇒ 全空（照原样画）。 */
    static List<List<Span>> spans(String info, List<String> lines) {
        List<List<Span>> out = new ArrayList<>();
        String st = style(info);
        TokenMaker tm = null;
        if (st != null) {
            try { tm = TokenMakerFactory.getDefaultInstance().getTokenMaker(st); } catch (RuntimeException | LinkageError e) { tm = null; }
        }
        int last = TokenTypes.NULL;
        for (String line : lines) {
            List<Span> ls = new ArrayList<>();
            out.add(ls);
            if (tm == null) continue;
            try {
                char[] cs = line.toCharArray();
                Segment seg = new Segment(cs, 0, cs.length);
                Token t = tm.getTokenList(seg, last, 0);
                for (; t != null && t.isPaintable(); t = t.getNextToken()) {
                    String f = face(t.getType());
                    int a = t.getTextOffset(), b = a + t.length();
                    if (f != null && b > a && a >= 0 && b <= cs.length) ls.add(new Span(a, b, f));
                }
                last = tm.getLastTokenTypeOnLine(seg, last);
            } catch (RuntimeException e) {
                ls.clear();
                last = TokenTypes.NULL;
            }
        }
        return out;
    }

    static String face(int type) {
        return switch (type) {
            case TokenTypes.COMMENT_EOL, TokenTypes.COMMENT_MULTILINE, TokenTypes.COMMENT_KEYWORD, TokenTypes.COMMENT_MARKUP -> "font-lock-comment-face";
            case TokenTypes.COMMENT_DOCUMENTATION -> "font-lock-doc-face";
            case TokenTypes.RESERVED_WORD -> "font-lock-keyword-face";
            case TokenTypes.RESERVED_WORD_2 -> "font-lock-builtin-face";
            case TokenTypes.FUNCTION -> "font-lock-function-call-face";
            case TokenTypes.DATA_TYPE -> "font-lock-type-face";
            case TokenTypes.LITERAL_BOOLEAN -> "font-lock-constant-face";
            case TokenTypes.LITERAL_NUMBER_DECIMAL_INT, TokenTypes.LITERAL_NUMBER_FLOAT, TokenTypes.LITERAL_NUMBER_HEXADECIMAL -> "font-lock-number-face";
            case TokenTypes.LITERAL_STRING_DOUBLE_QUOTE, TokenTypes.LITERAL_CHAR, TokenTypes.LITERAL_BACKQUOTE, TokenTypes.REGEX -> "font-lock-string-face";
            case TokenTypes.VARIABLE -> "font-lock-variable-name-face";
            case TokenTypes.ANNOTATION, TokenTypes.PREPROCESSOR -> "font-lock-preprocessor-face";
            case TokenTypes.MARKUP_TAG_NAME -> "font-lock-function-name-face";
            case TokenTypes.MARKUP_TAG_ATTRIBUTE -> "font-lock-variable-name-face";
            case TokenTypes.MARKUP_TAG_ATTRIBUTE_VALUE -> "font-lock-string-face";
            case TokenTypes.MARKUP_TAG_DELIMITER -> "shadow";
            case TokenTypes.ERROR_STRING_DOUBLE, TokenTypes.ERROR_CHAR -> "font-lock-string-face";
            default -> null;
        };
    }
}
