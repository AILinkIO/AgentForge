package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.Clj.conj;
import static io.ailink.agentforge.bitcask.cypher.Clj.kw;
import static io.ailink.agentforge.bitcask.cypher.Clj.nth;
import static io.ailink.agentforge.bitcask.cypher.Clj.vec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cypher 的词法与语法（clj {@code cypher.parser}；对照 Erlang bitcask 的 lexer / yecc 文法，按 openCypher 2024.3）。手写扫描器 + 递归下降；
 * 运算符优先级（低 ⇒ 高）：OR &lt; XOR &lt; AND &lt; NOT（前缀）&lt; = &lt;&gt; &lt; &lt;= &gt; &gt;= @@ =~（不结合）&lt; IN / STARTS WITH / ENDS WITH /
 * CONTAINS / IS [NOT] NULL / 标签判断（不结合）&lt; &lt;=&gt; &lt; + - &lt; * / % &lt; ^（左结合）&lt; 一元 - + &lt; . [ ]。
 * 关键字不区分大小写、都是非保留字；STARTS WITH / ENDS WITH / IS NULL / IS NOT NULL 合成一个记号。
 * <p>AST 形态同 clj（{@link Kw} 开头的 List，位置 loc = [行 列] 从 1 起），见 clj 源的文档串；{@link Sema} 是它的消费者。
 * 链式比较 {@code a < b <= c} 解析成 {@code a < b AND b <= c}；多个查询用 UNION 连接：{@code [:union [all? …] [[:query …] …] loc]}。
 */
final class Parser {
    private Parser() {
    }

    static final Set<String> KEYWORDS = Set.of("MATCH", "OPTIONAL", "WHERE", "RETURN", "WITH", "UNWIND", "AS", "ORDER", "BY", "SKIP",
            "LIMIT", "ASC", "ASCENDING", "DESC", "DESCENDING", "DISTINCT", "AND", "OR", "XOR", "NOT", "IN", "NULL", "TRUE", "FALSE",
            "CONTAINS", "CREATE", "SET", "REMOVE", "DELETE", "DETACH", "MERGE", "ON", "CASE", "WHEN", "THEN", "ELSE", "END", "CALL",
            "YIELD", "UNION", "ALL", "IS", "OFFSET", "INF", "INFINITY", "NAN");

    // 记号类型
    static final Kw IDENT = kw("ident"), INT = kw("int"), FLOAT = kw("float"), STRING = kw("string"), PARAM = kw("param"),
            KW = kw("kw"), PUNCT = kw("punct"), BADNUM = kw("badnum"), END = kw("end");
    /** 2^63：只能出现在负号后面（-9223372036854775808 是最小的整数字面量）。 */
    static final Kw MIN_LONG_ABS = kw("min-long-abs");

    // AST 关键字
    static final Kw QUERY = kw("query"), UNION = kw("union"), MATCH = kw("match"), UNWIND = kw("unwind"), WITH = kw("with"),
            RETURN = kw("return"), CREATE = kw("create"), SET = kw("set"), REMOVE = kw("remove"), DELETE = kw("delete"),
            MERGE = kw("merge"), CALL_PROC = kw("call-proc"), STAR = kw("star"), ITEMS = kw("items"), ITEM = kw("item"),
            PART = kw("part"), CHAIN = kw("chain"), SHORTEST = kw("shortest"), NODE = kw("node"), REL = kw("rel"),
            SET_PROP = kw("set-prop"), SET_PROPS = kw("set-props"), SET_LABELS = kw("set-labels"), REMOVE_PROP = kw("remove-prop"),
            REMOVE_LABELS = kw("remove-labels"), LIT = kw("lit"), VAR = kw("var"), HAS_LABELS = kw("has-labels"), PROP = kw("prop"),
            CALL = kw("call"), COUNT_STAR = kw("count-star"), LIST = kw("list"), MAP = kw("map"), OP = kw("op"), UNOP = kw("unop"),
            IS_NULL = kw("is-null"), CASE = kw("case"), INDEX = kw("index"), SLICE = kw("slice"), ANY = kw("any"), NOT = kw("not"),
            ALL = kw("all"), WILD = kw("wild"), LIST_COMP = kw("list-comp"), QUANT = kw("quant"), REDUCE = kw("reduce"),
            MAP_PROJ = kw("map-proj"), PROP_SEL = kw("prop-sel"), ALL_PROPS = kw("all-props"), ENTRY = kw("entry"),
            VAR_SEL = kw("var-sel"), PATTERN_PRED = kw("pattern-pred"), PATTERN_COMP = kw("pattern-comp"),
            EXISTS_SUB = kw("exists-sub"), SHORTEST_EXPR = kw("shortest-expr"), QPP = kw("qpp"), SINGLE = kw("single"),
            OUT = kw("out"), IN = kw("in"), BOTH = kw("both"), ASC = kw("asc"), DESC = kw("desc"), REPLACE = kw("replace"),
            MERGE_KW = kw("merge"), AND = kw("and"), NONE = kw("none"), MINUS = kw("-"), KIND = kw("kind"), K = kw("k"),
            LOC = kw("loc"), ALL_SHORTEST = kw("all-shortest"), ANY_SHORTEST = kw("any-shortest"),
            SHORTEST_GROUPS = kw("shortest-groups"), MATCH_KW = kw("match");

    private static final List<String> SYNTAX = Arrays.asList("SyntaxError", null);

    /** 记号。v：值（标识符名、数、字符串、参数名、大写关键字、标点）；name：关键字的源文本原样。 */
    record Tok(Kw t, Object v, List<Object> loc, String text, int pos, int end, String name, boolean quoted) {
    }

    private static List<Object> loc(long line, long col) {
        return vec(line, col);
    }

    // ═══ 词法 ═══

    private static boolean identStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean identPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean ws(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /** 最长匹配：三个字符的在前。 */
    private static final String[] PUNCTS = {"<=>", "<=", ">=", "<>", "+=", "..", "@@", "=~", "(", ")", "{", "}", "[", "]", ",", ".", ":",
            "|", "=", "<", ">", "+", "-", "*", "/", "%", "^", ";", "!", "&"};

    private static final Pattern HEX4 = Pattern.compile("[0-9a-fA-F]{4}");
    private static final Pattern HEX6 = Pattern.compile("[0-9a-fA-F]{6}");

    /** 字符串 / 反引号名字里的转义（openCypher 2024.3），以及引号连写（'' / "" / ``）。 */
    private static String unescape(String s, List<Object> loc, Character quote) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (quote != null && c == quote && i + 1 < n && s.charAt(i + 1) == quote) {
                sb.append(c);
                i += 2;
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                char e = s.charAt(i + 1);
                switch (e) {
                    case 'n' -> {
                        sb.append('\n');
                        i += 2;
                    }
                    case 't' -> {
                        sb.append('\t');
                        i += 2;
                    }
                    case 'r' -> {
                        sb.append('\r');
                        i += 2;
                    }
                    case 'b' -> {
                        sb.append('\b');
                        i += 2;
                    }
                    case 'f' -> {
                        sb.append('\f');
                        i += 2;
                    }
                    case '\\', '\'', '"', '`' -> {
                        sb.append(e);
                        i += 2;
                    }
                    case 'u' -> {
                        String h = i + 6 <= n ? s.substring(i + 2, i + 6) : null;
                        if (h != null && HEX4.matcher(h).matches()) {
                            sb.append((char) Integer.parseInt(h, 16));
                            i += 6;
                        } else throw CypherError.fail("parse", "bad \\u escape", loc, List.of("SyntaxError", "InvalidUnicodeLiteral"));
                    }
                    case 'U' -> {
                        String h = i + 8 <= n ? s.substring(i + 2, i + 8) : null;
                        Long v = h != null && HEX6.matcher(h).matches() ? Long.parseLong(h, 16) : null;
                        if (v != null && Character.isValidCodePoint(v.intValue())) {
                            sb.appendCodePoint(v.intValue());
                            i += 8;
                        } else
                            throw CypherError.fail("parse", "bad \\U escape (needs 6 hex digits)", loc, List.of("SyntaxError", "InvalidUnicodeLiteral"));
                    }
                    default -> throw CypherError.fail("unknown escape \\" + e, loc);
                }
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 反引号名字的结束位置（i 指向开头的反引号）：`` 与 \` 都是名字里的反引号。 */
    private static int quotedEnd(String src, int i, int n, List<Object> loc) {
        int k = i + 1;
        while (true) {
            if (k >= n) throw CypherError.fail("unterminated `quoted` name", loc);
            char c = src.charAt(k);
            if (c == '\\') k += 2;
            else if (c == '`' && k + 1 < n && src.charAt(k + 1) == '`') k += 2;
            else if (c == '`') return k;
            else k++;
        }
    }

    private static final Pattern HEX_NUM = Pattern.compile("^0[xX]((?:_?[0-9a-fA-F])+)");
    private static final Pattern OCT_NUM = Pattern.compile("^0o((?:_?[0-7])+)");
    private static final Pattern DOT_FLOAT = Pattern.compile("^\\.\\d(?:_?\\d)*(?:[eE][+-]?\\d(?:_?\\d)*)?(?:[fFdD](?![\\p{L}\\p{N}_]))?");
    private static final Pattern NUM = Pattern.compile("^(\\d(?:_?\\d)*)(\\.\\d(?:_?\\d)*)?([eE][+-]?\\d(?:_?\\d)*)?");
    private static final Pattern PARAM_NAME = Pattern.compile("^\\$([\\p{L}\\p{N}_\\p{Mn}\\p{Mc}\\p{Pc}]+)");
    private static final Pattern WITH_TAIL = Pattern.compile("^[\\s]+(?i:WITH)(?![A-Za-z0-9_])");
    private static final Pattern NOT_NULL_TAIL = Pattern.compile("^[\\s]+(?i:NOT)[\\s]+(?i:NULL)(?![A-Za-z0-9_])");
    private static final Pattern NULL_TAIL = Pattern.compile("^[\\s]+(?i:NULL)(?![A-Za-z0-9_])");

    /** 词法状态：位置与行列。 */
    private static final class Lex {
        final String src;
        final int n;
        final List<Tok> out = new ArrayList<>();
        int i = 0;
        long line = 1, col = 1;

        Lex(String src) {
            this.src = src;
            this.n = src.length();
        }

        /** 推进到 j，算行列。 */
        void adv(int j) {
            for (int k = i; k < j; k++) {
                if (src.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else col++;
            }
            i = j;
        }

        void emit(Kw t, Object v, List<Object> loc, int j, String name, boolean quoted) {
            out.add(new Tok(t, v, loc, src.substring(i, j), i, j, name, quoted));
            adv(j);
        }

        void emit(Kw t, Object v, List<Object> loc, int j) {
            emit(t, v, loc, j, null, false);
        }
    }

    /** 文本 ⇒ 记号表（最后一个是 :end）。 */
    static List<Tok> tokenize(String src) {
        Lex x = new Lex(src);
        int n = x.n;
        while (x.i < n) {
            int i = x.i;
            char c = src.charAt(i);
            List<Object> loc = loc(x.line, x.col);
            if (ws(c)) {
                x.adv(i + 1);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int e = src.indexOf('\n', i);
                x.adv(e < 0 ? n : e);
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                if (e < 0) throw CypherError.fail("unterminated comment", loc);
                x.adv(e + 2);
            } else if (c == '0' && i + 1 < n && "xXo".indexOf(src.charAt(i + 1)) >= 0) {
                boolean hex = src.charAt(i + 1) != 'o';
                Matcher m = (hex ? HEX_NUM : OCT_NUM).matcher(src.substring(i, Math.min(n, i + 100)));
                boolean found = m.find();
                if (!found || (i + 2 + m.group(1).length() < n && identPart(src.charAt(i + 2 + m.group(1).length()))))
                    throw CypherError.fail("parse", hex ? "invalid number literal (hexadecimal)" : "invalid number literal (octal)", loc,
                            List.of("SyntaxError", "InvalidNumberLiteral"));
                String digits = m.group(1).replace("_", "");
                Object v;
                try {
                    v = Long.parseLong(digits, hex ? 16 : 8);
                } catch (NumberFormatException ex) {
                    if (BigInteger.ONE.shiftLeft(63).equals(new BigInteger(digits, hex ? 16 : 8))) v = MIN_LONG_ABS;
                    else throw CypherError.fail("parse", "integer literal too large", loc, List.of("SyntaxError", "IntegerOverflow"));
                }
                x.emit(INT, v, loc, i + m.group(0).length());
            } else if (c == '.' && i + 1 < n && digit(src.charAt(i + 1)) && !prevIsAccessor(x.out)) {
                Matcher m = DOT_FLOAT.matcher(src.substring(i, Math.min(n, i + 400)));
                m.find();
                String mm = m.group();
                String txt = mm.replaceAll("[fFdD]$", "").replace("_", "");
                x.emit(FLOAT, Double.parseDouble("0" + txt), loc, i + mm.length());
            } else if (digit(c)) {
                Matcher m = NUM.matcher(src.substring(i, Math.min(n, i + 400)));
                m.find();
                String all0 = m.group(0), frac = m.group(2), ex = m.group(3);
                int after = i + all0.length();
                boolean suffix = (frac != null || ex != null) && after < n && "fFdD".indexOf(src.charAt(after)) >= 0
                        && !(after + 1 < n && identPart(src.charAt(after + 1)));
                String all = all0.replace("_", "");
                int j = after + (suffix ? 1 : 0);
                if (j < n && identPart(src.charAt(j))) {
                    int e = j;
                    while (e < n && identPart(src.charAt(e))) e++;
                    x.emit(BADNUM, src.substring(i, e), loc, e);
                } else if (frac != null || ex != null) {
                    double d = Double.parseDouble(all);
                    if (Double.isInfinite(d))
                        throw CypherError.fail("parse", "floating point literal is too large", loc, List.of("SyntaxError", "FloatingPointOverflow"));
                    x.emit(FLOAT, d, loc, j);
                } else {
                    Object v;
                    try {
                        v = Long.parseLong(all);
                    } catch (NumberFormatException e) {
                        if ("9223372036854775808".equals(all)) v = MIN_LONG_ABS;
                        else throw CypherError.fail("parse", "integer literal too large", loc, List.of("SyntaxError", "IntegerOverflow"));
                    }
                    x.emit(INT, v, loc, j);
                }
            } else if (c == '\'' || c == '"') {
                int k = i + 1;
                while (true) {
                    if (k >= n) throw CypherError.fail("unterminated string", loc);
                    char ck = src.charAt(k);
                    if (ck == '\\') k += 2;
                    else if (ck == c && k + 1 < n && src.charAt(k + 1) == c) k += 2;
                    else if (ck == c) break;
                    else k++;
                }
                x.emit(STRING, unescape(src.substring(i + 1, k), loc, c), loc, k + 1);
            } else if (c == '`') {
                int e = quotedEnd(src, i, n, loc);
                String nm = unescape(src.substring(i + 1, e), loc, '`');
                x.emit(IDENT, nm, loc, e + 1, null, true);
            } else if (c == '$') {
                if (i + 1 < n && src.charAt(i + 1) == '`') {
                    int e = quotedEnd(src, i + 1, n, loc);
                    x.emit(PARAM, unescape(src.substring(i + 2, e), loc, '`'), loc, e + 1);
                } else {
                    Matcher m = PARAM_NAME.matcher(src.substring(i, Math.min(n, i + 300)));
                    if (!m.find()) throw CypherError.fail("bad parameter name", loc);
                    x.emit(PARAM, m.group(1), loc, i + m.group(0).length());
                }
            } else if (identStart(c)) {
                int j = i;
                while (j < n && identPart(src.charAt(j))) j++;
                String word = src.substring(i, j);
                String up = word.toUpperCase(Locale.ROOT);
                String rest = src.substring(j, Math.min(n, j + 64));
                String ct = null;
                int cj = 0;
                Pattern[] tries = switch (up) {
                    case "STARTS", "ENDS" -> new Pattern[]{WITH_TAIL};
                    case "IS" -> new Pattern[]{NOT_NULL_TAIL, NULL_TAIL};
                    default -> new Pattern[0];
                };
                for (Pattern p : tries) {
                    Matcher m = p.matcher(rest);
                    if (m.find()) {
                        ct = switch (up) {
                            case "STARTS" -> "STARTS_WITH";
                            case "ENDS" -> "ENDS_WITH";
                            default -> p == NOT_NULL_TAIL ? "IS_NOT_NULL" : "IS_NULL";
                        };
                        cj = j + m.group().length();
                        break;
                    }
                }
                if (ct != null) x.emit(KW, ct, loc, cj);
                else if (KEYWORDS.contains(up)) x.emit(KW, up, loc, j, word, false);
                else x.emit(IDENT, word, loc, j);
            } else {
                String p = null;
                for (String q : PUNCTS) {
                    if (src.startsWith(q, i)) {
                        p = q;
                        break;
                    }
                }
                if (p == null)
                    throw CypherError.fail("parse", "unexpected character " + prStr(String.valueOf(c)), loc,
                            c > 127 ? List.of("SyntaxError", "InvalidUnicodeCharacter") : null);
                x.emit(PUNCT, p, loc, i + p.length());
            }
        }
        x.out.add(new Tok(END, null, loc(x.line, x.col), null, -1, -1, null, false));
        return x.out;
    }

    /** 前一个记号是标识符 / 参数 / 右括号时 `.` 是属性访问（`n.5` 不合法，交给语法报错）。 */
    private static boolean prevIsAccessor(List<Tok> out) {
        if (out.isEmpty()) return false;
        Tok prev = out.getLast();
        return prev.t == IDENT || prev.t == PARAM || (prev.t == PUNCT && Set.of(")", "]", "}").contains(prev.v));
    }

    /** clj pr-str 一个字符串。 */
    static String prStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ═══ 语法 ═══

    /** 语法分析状态。 */
    private static final class St {
        final List<Tok> toks;
        final String src;
        int i = 0;

        St(List<Tok> toks, String src) {
            this.toks = toks;
            this.src = src;
        }
    }

    private static String tokText(Tok t) {
        if (t.t == END) return "end of query";
        if (t.t == STRING) return "'" + t.v + "'";
        if (t.t == PARAM) return "$" + t.v;
        return "`" + (t.text != null ? t.text : t.v) + "`";
    }

    /** 从第 from 个记号到刚读完的记号之间的源文本（列名用：openCypher 的列名是表达式原文）。 */
    private static String sourceSince(St s, int from) {
        Tok a = s.toks.get(from), b = s.toks.get(Math.max(from, s.i - 1));
        if (s.src != null && a.pos >= 0 && b.end >= 0) return s.src.substring(a.pos, b.end);
        return null;
    }

    private static Tok peek(St s) {
        return peek(s, 0);
    }

    private static Tok peek(St s, int k) {
        return s.toks.get(Math.min(s.i + k, s.toks.size() - 1));
    }

    private static Tok advance(St s) {
        Tok t = peek(s);
        s.i++;
        return t;
    }

    private static CypherError syntaxError(St s) {
        Tok t = peek(s);
        if (t.t == END) throw CypherError.fail("unexpected end of query", t.loc);
        throw CypherError.fail("syntax error near " + tokText(t), t.loc);
    }

    private static boolean punct(Tok t, String p) {
        return t.t == PUNCT && p.equals(t.v);
    }

    private static boolean kwIs(Tok t, String k) {
        return t.t == KW && k.equals(t.v);
    }

    private static boolean atPunct(St s, String p) {
        return punct(peek(s), p);
    }

    private static boolean atKw(St s, String k) {
        return kwIs(peek(s), k);
    }

    private static Tok expectPunct(St s, String p) {
        if (atPunct(s, p)) return advance(s);
        throw syntaxError(s);
    }

    private static Tok expectKw(St s, String k) {
        if (atKw(s, k)) return advance(s);
        throw syntaxError(s);
    }

    private static boolean acceptPunct(St s, String p) {
        if (atPunct(s, p)) {
            advance(s);
            return true;
        }
        return false;
    }

    private static boolean acceptKw(St s, String k) {
        if (atKw(s, k)) {
            advance(s);
            return true;
        }
        return false;
    }

    private static final Set<String> STRUCTURAL_KWS = Set.of("STARTS_WITH", "ENDS_WITH", "IS_NULL", "IS_NOT_NULL");

    /** 能当名字的记号：标识符，或关键字（都是非保留字）；except 里的除外。 */
    private static boolean nameTok(Tok t, Set<String> except) {
        return t.t == IDENT || (t.t == KW && !STRUCTURAL_KWS.contains(t.v) && !except.contains(t.v));
    }

    private static List<Object> varName(St s) {
        Tok t = peek(s);
        if (t.t == IDENT) {
            advance(s);
            return vec(VAR, t.v, t.loc);
        }
        if (nameTok(t, Set.of())) {
            advance(s);
            return vec(VAR, t.name, t.loc);
        }
        throw syntaxError(s);
    }

    /** 属性名、标签、关系类型、映射键：标识符，或关键字（取源文本原样）。 */
    private static String symbolicName(St s) {
        Tok t = peek(s);
        if (t.t == IDENT) {
            advance(s);
            return (String) t.v;
        }
        if (t.t == KW) {
            if (STRUCTURAL_KWS.contains(t.v)) throw syntaxError(s);
            advance(s);
            return t.name;
        }
        throw syntaxError(s);
    }

    /** 试着按 f 解析；f 返回 null 或报语法错 ⇒ 退回原位置、返回 null（模式推导 / 模式谓词与普通括号、列表的二义性）。 */
    private static Object attempt(St s, Supplier<Object> f) {
        int save = s.i;
        try {
            Object r = f.get();
            if (r == null) s.i = save;
            return r;
        } catch (CypherError e) {
            if (e.isParseError()) {
                s.i = save;
                return null;
            }
            throw e;
        }
    }

    private static List<Object> exprList(St s, String close) {
        List<Object> acc = new ArrayList<>();
        if (atPunct(s, close)) return acc;
        acc.add(expr(s));
        while (acceptPunct(s, ",")) acc.add(expr(s));
        return acc;
    }

    private static List<Object> mapLiteral(St s) {
        expectPunct(s, "{");
        if (acceptPunct(s, "}")) return vec(MAP, new ArrayList<>());
        List<Object> entries = new ArrayList<>();
        do {
            String k = symbolicName(s);
            expectPunct(s, ":");
            entries.add(vec(k, expr(s)));
        } while (acceptPunct(s, ","));
        expectPunct(s, "}");
        return vec(MAP, entries);
    }

    /** `x IN 源 [WHERE 谓词]` ⇒ [变量名 源 谓词]。 */
    private static Object[] filterBody(St s) {
        String v = (String) varName(s).get(1);
        expectKw(s, "IN");
        Object src = expr(s);
        Object pred = acceptKw(s, "WHERE") ? expr(s) : null;
        return new Object[]{v, src, pred};
    }

    private static List<Object> quantifier(St s, Kw kind, List<Object> loc) {
        expectPunct(s, "(");
        Object[] f = filterBody(s);
        if (f[2] == null)
            throw CypherError.fail("parse", "a quantifier needs WHERE: all(x IN list WHERE predicate)", loc, List.of("SyntaxError", "InvalidRelationshipPattern"));
        expectPunct(s, ")");
        return vec(QUANT, kind, f[0], f[1], f[2], loc);
    }

    private static List<Object> reduce(St s, List<Object> loc) {
        expectPunct(s, "(");
        Object acc = varName(s).get(1);
        expectPunct(s, "=");
        Object init = expr(s);
        expectPunct(s, ",");
        Object v = varName(s).get(1);
        expectKw(s, "IN");
        Object src = expr(s);
        expectPunct(s, "|");
        Object e = expr(s);
        expectPunct(s, ")");
        return vec(REDUCE, acc, init, v, src, e, loc);
    }

    private static List<Object> mapProjection(St s, Object v, List<Object> loc) {
        expectPunct(s, "{");
        List<Object> items = new ArrayList<>();
        if (!atPunct(s, "}")) {
            do {
                Object it;
                if (acceptPunct(s, ".")) it = acceptPunct(s, "*") ? vec(ALL_PROPS) : vec(PROP_SEL, symbolicName(s));
                else if (peek(s).t == IDENT && !punct(peek(s, 1), ":")) it = vec(VAR_SEL, varName(s));
                else {
                    String k = symbolicName(s);
                    expectPunct(s, ":");
                    it = vec(ENTRY, k, expr(s));
                }
                items.add(it);
            } while (acceptPunct(s, ","));
        }
        expectPunct(s, "}");
        return vec(MAP_PROJ, v, items, loc);
    }

    /** [ [p =] (a)-[…]->(b) [WHERE …] | e ]（'[' 已读）；不是模式推导 ⇒ null。 */
    private static Object patternCompBody(St s, List<Object> loc) {
        Object pv = null;
        if (peek(s).t == IDENT && punct(peek(s, 1), "=")) {
            pv = varName(s);
            advance(s);
        }
        if (!atPunct(s, "(")) return null;
        List<Object> c0 = chain(s, false);
        if (((List<?>) c0.get(2)).isEmpty()) return null;
        Object[] cw = desugarWheres(c0, acceptKw(s, "WHERE") ? expr(s) : null);
        expectPunct(s, "|");
        Object e = expr(s);
        expectPunct(s, "]");
        return vec(PATTERN_COMP, pv, cw[0], cw[1], e, loc);
    }

    private static List<Object> listCompBody(St s, List<Object> loc) {
        Object[] f = filterBody(s);
        Object proj = acceptPunct(s, "|") ? expr(s) : null;
        expectPunct(s, "]");
        return vec(LIST_COMP, f[0], f[1], f[2], proj, loc);
    }

    private static Object labelPrimary(St s) {
        if (acceptPunct(s, "(")) {
            Object e = labelExpr(s);
            expectPunct(s, ")");
            return e;
        }
        if (acceptPunct(s, "%")) return vec(WILD);
        return symbolicName(s);
    }

    private static Object labelFactor(St s) {
        return acceptPunct(s, "!") ? vec(NOT, labelFactor(s)) : labelPrimary(s);
    }

    private static Object labelAnd(St s) {
        List<Object> xs = vec(labelFactor(s));
        while (acceptPunct(s, "&")) xs.add(labelFactor(s));
        return xs.size() == 1 ? xs.getFirst() : vec(ALL, xs);
    }

    /** 标签表达式（openCypher 2024.3）：项 | 项、因子 &amp; 因子、!因子、(表达式)、%；名字 ⇒ 字符串。 */
    private static Object labelExpr(St s) {
        List<Object> xs = vec(labelAnd(s));
        while (acceptPunct(s, "|")) {
            acceptPunct(s, ":");
            xs.add(labelAnd(s));
        }
        return xs.size() == 1 ? xs.getFirst() : vec(ANY, xs);
    }

    /** 节点的标签：`:A:B`（都要有）、`:A|B&!C`、`IS A&B` ⇒ 标签项的向量（都要满足）。 */
    private static List<Object> nodeLabels(St s) {
        if (acceptKw(s, "IS")) return vec(labelExpr(s));
        List<Object> acc = new ArrayList<>();
        while (acceptPunct(s, ":")) acc.add(labelExpr(s));
        return acc;
    }

    private static final Set<String> ATOM_NAME_EXCEPT = Set.of("WHEN", "THEN", "ELSE", "END", "AND", "OR", "XOR", "NOT", "IN", "AS", "WHERE");
    private static final Set<String> CLAUSE_STARTS = Set.of("MATCH", "OPTIONAL", "WITH", "UNWIND", "RETURN", "CALL");

    private static Object varTail(St s, List<Object> v, Tok t) {
        if (atPunct(s, ":") || atKw(s, "IS")) return vec(HAS_LABELS, v, nodeLabels(s));
        if (atPunct(s, "{")) return mapProjection(s, v, t.loc);
        return v;
    }

    private static Object atom(St s) {
        Tok t = peek(s);
        if (t.t == INT) {
            if (t.v == MIN_LONG_ABS) throw CypherError.fail("parse", "integer literal too large", t.loc, List.of("SyntaxError", "IntegerOverflow"));
            advance(s);
            return vec(LIT, t.v);
        }
        if (t.t == BADNUM) throw CypherError.fail("parse", "invalid number literal " + t.v, t.loc, List.of("SyntaxError", "InvalidNumberLiteral"));
        if (t.t == FLOAT || t.t == STRING) {
            advance(s);
            return vec(LIT, t.v);
        }
        if (t.t == PARAM) {
            advance(s);
            return vec(PARAM, t.v, t.loc);
        }
        if (t.t == KW) {
            switch ((String) t.v) {
                case "ALL" -> {
                    if (punct(peek(s, 1), "(")) {
                        advance(s);
                        return quantifier(s, ALL, t.loc);
                    }
                    throw syntaxError(s);
                }
                case "CASE" -> {
                    advance(s);
                    Object subj = atKw(s, "WHEN") ? null : expr(s);
                    List<Object> alts = new ArrayList<>();
                    while (acceptKw(s, "WHEN")) {
                        // 简单 CASE：WHEN 1, 2 THEN …（多个值共用一个结果）
                        List<Object> ws = subj != null ? commaList(s, Parser::expr) : vec(expr(s));
                        expectKw(s, "THEN");
                        Object th = expr(s);
                        for (Object w : ws) alts.add(vec(w, th));
                    }
                    if (alts.isEmpty()) throw syntaxError(s);
                    Object els = acceptKw(s, "ELSE") ? expr(s) : null;
                    expectKw(s, "END");
                    return vec(CASE, subj, alts, els, t.loc);
                }
                case "TRUE" -> {
                    advance(s);
                    return vec(LIT, true);
                }
                case "FALSE" -> {
                    advance(s);
                    return vec(LIT, false);
                }
                case "NULL" -> {
                    advance(s);
                    return vec(LIT, null);
                }
                case "INF", "INFINITY" -> {
                    advance(s);
                    return vec(LIT, Double.POSITIVE_INFINITY);
                }
                case "NAN" -> {
                    advance(s);
                    return vec(LIT, Double.NaN);
                }
                default -> {
                    // 其余关键字（非保留字）当变量名
                    if (nameTok(t, ATOM_NAME_EXCEPT)) return varTail(s, varName(s), t);
                    throw syntaxError(s);
                }
            }
        }
        if (t.t == IDENT) {
            String lv = ((String) t.v).toLowerCase(Locale.ROOT);
            if (Set.of("any", "none", "single").contains(lv) && punct(peek(s, 1), "(") && peek(s, 2).t == IDENT && kwIs(peek(s, 3), "IN")) {
                advance(s);
                return quantifier(s, kw(lv), t.loc);
            }
            // EXISTS { 模式 [WHERE …] } / EXISTS { 子句 … }：存在性子查询
            if (lv.equals("exists") && punct(peek(s, 1), "{")) {
                advance(s);
                advance(s);
                List<Object> clauses;
                if (CLAUSE_STARTS.stream().anyMatch(k -> atKw(s, k))) {
                    clauses = new ArrayList<>();
                    while (!atPunct(s, "}")) clauses.add(clause(s));
                } else {
                    Object[] pw = matchPattern(s);
                    clauses = vec(vec(MATCH, false, pw[0], pw[1], t.loc));
                }
                expectPunct(s, "}");
                return vec(EXISTS_SUB, clauses, t.loc);
            }
            if (lv.equals("reduce") && punct(peek(s, 1), "(") && peek(s, 2).t == IDENT && punct(peek(s, 3), "=")) {
                advance(s);
                return reduce(s, t.loc);
            }
            // shortestPath((a)-[…]-(b)) / allShortestPaths(…) 当表达式：路径 / 路径列表（两端须已绑定）
            if ((lv.equals("shortestpath") || lv.equals("allshortestpaths")) && punct(peek(s, 1), "(") && punct(peek(s, 2), "(")) {
                advance(s);
                advance(s);
                List<Object> c = chain(s, false);
                expectPunct(s, ")");
                if (((List<?>) c.get(2)).size() != 1)
                    throw CypherError.fail("parse", "shortestPath needs exactly one relationship pattern", t.loc, List.of("SyntaxError", "InvalidRelationshipPattern"));
                return vec(SHORTEST_EXPR, lv.equals("shortestpath") ? SINGLE : ALL, c, t.loc);
            }
            if (punct(peek(s, 1), "(")) {
                advance(s);
                advance(s);
                if (atPunct(s, "*") && punct(peek(s, 1), ")")) {
                    advance(s);
                    advance(s);
                    if (lv.equals("count")) return vec(COUNT_STAR, t.loc);
                    throw CypherError.fail("only count(*) accepts *", t.loc);
                }
                boolean d = acceptKw(s, "DISTINCT") || (acceptKw(s, "ALL") && false);
                List<Object> args = exprList(s, ")");
                expectPunct(s, ")");
                return vec(CALL, lv, d, args, t.loc);
            }
            return varTail(s, varName(s), t);
        }
        if (t.t == PUNCT) {
            switch ((String) t.v) {
                case "(" -> {
                    Object pp = attempt(s, () -> {
                        List<Object> c = chain(s, false);
                        if (((List<?>) c.get(2)).isEmpty()) return null;
                        noInlineWhere(c, "a pattern predicate (use EXISTS { … })", t.loc);
                        return vec(PATTERN_PRED, c, t.loc);
                    });
                    if (pp != null) return pp;
                    advance(s);
                    Object e = expr(s);
                    expectPunct(s, ")");
                    return e;
                }
                case "[" -> {
                    advance(s);
                    Object pc = attempt(s, () -> patternCompBody(s, t.loc));
                    if (pc != null) return pc;
                    if (peek(s).t == IDENT && kwIs(peek(s, 1), "IN")) return listCompBody(s, t.loc);
                    List<Object> es = exprList(s, "]");
                    expectPunct(s, "]");
                    return vec(LIST, es);
                }
                case "{" -> {
                    return mapLiteral(s);
                }
                default -> throw syntaxError(s);
            }
        }
        throw syntaxError(s);
    }

    /** 记号 ⇒ [运算 优先级 结合]；openCypher：PowerOfExpression 左结合（2 ^ 3 ^ 2 = 64）。 */
    private record BinOp(Kw op, int prec, Kw assoc) {
    }

    private static final Kw LEFT = kw("left"), RIGHT = kw("right");
    private static final Map<String, BinOp> BINOPS = Map.ofEntries(
            Map.entry("OR", new BinOp(kw("or"), 100, LEFT)), Map.entry("XOR", new BinOp(kw("xor"), 110, LEFT)),
            Map.entry("AND", new BinOp(kw("and"), 120, LEFT)),
            Map.entry("=", new BinOp(kw("="), 200, NONE)), Map.entry("<>", new BinOp(kw("<>"), 200, NONE)),
            Map.entry("<", new BinOp(kw("<"), 200, NONE)), Map.entry("<=", new BinOp(kw("<="), 200, NONE)),
            Map.entry(">", new BinOp(kw(">"), 200, NONE)), Map.entry(">=", new BinOp(kw(">="), 200, NONE)),
            Map.entry("@@", new BinOp(kw("at-at"), 200, NONE)), Map.entry("=~", new BinOp(kw("regex"), 200, NONE)),
            Map.entry("IN", new BinOp(kw("in"), 210, NONE)), Map.entry("STARTS_WITH", new BinOp(kw("starts-with"), 210, NONE)),
            Map.entry("ENDS_WITH", new BinOp(kw("ends-with"), 210, NONE)), Map.entry("CONTAINS", new BinOp(kw("contains"), 210, NONE)),
            Map.entry("<=>", new BinOp(kw("<=>"), 250, LEFT)), Map.entry("+", new BinOp(kw("+"), 300, LEFT)),
            Map.entry("-", new BinOp(kw("-"), 300, LEFT)), Map.entry("*", new BinOp(kw("*"), 400, LEFT)),
            Map.entry("/", new BinOp(kw("/"), 400, LEFT)), Map.entry("%", new BinOp(kw("%"), 400, LEFT)),
            Map.entry("^", new BinOp(kw("pow"), 500, LEFT)));

    private static final Set<Kw> COMPARISON_OPS = Set.of(kw("="), kw("<>"), kw("<"), kw("<="), kw(">"), kw(">="));

    private static BinOp binopOf(Tok t) {
        if (t.t == PUNCT || t.t == KW) return BINOPS.get((String) t.v);
        return null;
    }

    private static Object exprPrec(St s, int minPrec) {
        Tok t = peek(s);
        Object left;
        if (kwIs(t, "NOT")) {
            advance(s);
            left = vec(UNOP, NOT, exprPrec(s, 131));
        } else if (punct(t, "-")) {
            advance(s);
            Tok nx = peek(s);
            if (nx.t == INT && nx.v == MIN_LONG_ABS) {
                advance(s);
                left = vec(LIT, Long.MIN_VALUE);
            } else left = vec(UNOP, MINUS, exprPrec(s, 601));
        } else if (punct(t, "+")) {
            advance(s);
            left = exprPrec(s, 601);
        } else left = atom(s);
        Integer lastNonassoc = null;
        while (true) {
            t = peek(s);
            if (punct(t, ".") && 700 >= minPrec) {
                advance(s);
                String nm = symbolicName(s);
                if (atPunct(s, "(")) {
                    // 命名空间函数：fts.phrase(…)、search.hybrid(…)
                    Tok lp = peek(s);
                    List<Object> nsParts = new ArrayList<>();
                    Object x = left;
                    while (true) {
                        if (Clj.is(x, VAR)) {
                            nsParts.addFirst(Clj.second(x));
                            break;
                        } else if (Clj.is(x, PROP)) {
                            nsParts.addFirst(nth(x, 2));
                            x = nth(x, 1);
                        } else {
                            nsParts = null;
                            break;
                        }
                    }
                    Object y = left;
                    while (Clj.is(y, PROP)) y = nth(y, 1);
                    Object vloc = nth(y, 2);
                    if (nsParts == null) throw CypherError.fail("function names are name.name(…)", lp.loc);
                    advance(s);
                    boolean d = acceptKw(s, "DISTINCT") || (acceptKw(s, "ALL") && false);
                    List<Object> args = exprList(s, ")");
                    expectPunct(s, ")");
                    StringBuilder fn = new StringBuilder();
                    for (Object p : nsParts) fn.append(p).append('.');
                    fn.append(nm);
                    left = vec(CALL, fn.toString().toLowerCase(Locale.ROOT), d, args, vloc);
                    lastNonassoc = null;
                } else {
                    left = vec(PROP, left, nm);
                    lastNonassoc = null;
                }
                continue;
            }
            if (punct(t, "[") && 700 >= minPrec) {
                advance(s);
                if (acceptPunct(s, "..")) {
                    Object to = atPunct(s, "]") ? null : expr(s);
                    expectPunct(s, "]");
                    left = vec(SLICE, left, null, to);
                } else {
                    Object i = expr(s);
                    if (acceptPunct(s, "..")) {
                        Object to = atPunct(s, "]") ? null : expr(s);
                        expectPunct(s, "]");
                        left = vec(SLICE, left, i, to);
                    } else {
                        expectPunct(s, "]");
                        left = vec(INDEX, left, i);
                    }
                }
                lastNonassoc = null;
                continue;
            }
            if ((punct(t, ":") || kwIs(t, "IS")) && 210 >= minPrec) {
                if (Integer.valueOf(210).equals(lastNonassoc)) throw syntaxError(s);
                left = vec(HAS_LABELS, left, nodeLabels(s));
                lastNonassoc = 210;
                continue;
            }
            if ((kwIs(t, "IS_NULL") || kwIs(t, "IS_NOT_NULL")) && 210 >= minPrec) {
                if (Integer.valueOf(210).equals(lastNonassoc)) throw syntaxError(s);
                advance(s);
                left = vec(IS_NULL, left, kwIs(t, "IS_NULL"));
                lastNonassoc = 210;
                continue;
            }
            BinOp b = binopOf(t);
            if (b == null || b.prec < minPrec) return left;
            if (COMPARISON_OPS.contains(b.op)) {
                // 链式比较：a < b <= c ⇒ a < b AND b <= c（openCypher）
                if (Integer.valueOf(b.prec).equals(lastNonassoc)) throw syntaxError(s);
                advance(s);
                Object right = exprPrec(s, b.prec + 1);
                Object acc = vec(OP, b.op, left, right);
                Object prev = right;
                while (true) {
                    BinOp b2 = binopOf(peek(s));
                    if (b2 != null && COMPARISON_OPS.contains(b2.op)) {
                        advance(s);
                        Object r2 = exprPrec(s, b.prec + 1);
                        acc = vec(OP, AND, acc, vec(OP, b2.op, prev, r2));
                        prev = r2;
                    } else break;
                }
                left = acc;
                lastNonassoc = b.prec;
            } else {
                if (b.assoc == NONE && Integer.valueOf(b.prec).equals(lastNonassoc)) throw syntaxError(s);
                advance(s);
                Object right = exprPrec(s, b.assoc == RIGHT ? b.prec : b.prec + 1);
                left = vec(OP, b.op, left, right);
                lastNonassoc = b.assoc == NONE ? b.prec : null;
            }
        }
    }

    static Object expr(St s) {
        return exprPrec(s, 0);
    }

    // ═══ 模式 ═══

    private static Object props(St s) {
        if (atPunct(s, "{")) return mapLiteral(s);
        if (peek(s).t == PARAM) {
            Tok t = advance(s);
            return vec(PARAM, t.v, t.loc);
        }
        return null;
    }

    private static List<Object> nodePattern(St s) {
        Tok t = expectPunct(s, "(");
        Object v = nameTok(peek(s), Set.of("WHERE", "IS")) ? varName(s) : null;
        List<Object> ls = nodeLabels(s);
        Object p = props(s);
        Tok wt = peek(s);
        Object w = acceptKw(s, "WHERE") ? expr(s) : null;
        if (p != null && w != null) throw CypherError.fail("a node pattern can have a property map or WHERE, not both", wt.loc);
        expectPunct(s, ")");
        List<Object> n = vec(NODE, v, ls, p, t.loc);
        if (w != null) n.add(w);
        return n;
    }

    private static List<Object> varlen(St s) {
        Object a = peek(s).t == INT ? advance(s).v : null;
        if (acceptPunct(s, "..")) {
            Object b = peek(s).t == INT ? advance(s).v : null;
            return vec(a, b);
        }
        return vec(a, a);
    }

    /** 关系的方括号部分；里面的语法错误报「关系模式无效」（openCypher：InvalidRelationshipPattern）。 */
    private static Object[] relDetail(St s) {
        if (!acceptPunct(s, "[")) return new Object[]{null, new ArrayList<>(), null, null, null};
        try {
            return relDetailInner(s);
        } catch (CypherError e) {
            if ("parse".equals(e.stage) && !String.valueOf(e.getMessage()).contains("relationship pattern"))
                throw new CypherError("parse", "invalid relationship pattern: " + e.getMessage(), e.line, e.column, "SyntaxError",
                        "InvalidRelationshipPattern");
            throw e;
        }
    }

    private static boolean simpleLabel(Object lx) {
        return lx == null || lx instanceof String
                || (Clj.is(lx, ANY) && ((List<?>) nth(lx, 1)).stream().allMatch(x -> x instanceof String));
    }

    private static Object[] relDetailInner(St s) {
        Object v0 = nameTok(peek(s), Set.of("WHERE", "IS")) ? varName(s) : null;
        Tok lt = peek(s);
        Object lx = (acceptPunct(s, ":") || acceptKw(s, "IS")) ? labelExpr(s) : null;
        boolean simple = simpleLabel(lx);
        // 复杂的类型表达式要变量：匿名时起一个隐藏名字
        Object v = v0 != null ? v0 : (!simple ? vec(VAR, "  rt" + s.i, lt.loc) : null);
        List<Object> types = lx == null ? new ArrayList<>() : lx instanceof String ? vec(lx) : simple ? Clj.vecOf((List<?>) nth(lx, 1)) : new ArrayList<>();
        Object vl = acceptPunct(s, "*") ? varlen(s) : null;
        Object p = props(s);
        Tok wt = peek(s);
        Object w = acceptKw(s, "WHERE") ? expr(s) : null;
        if (p != null && w != null)
            throw CypherError.fail("parse", "a relationship pattern can have a property map or WHERE, not both", wt.loc, List.of("SyntaxError", "InvalidRelationshipPattern"));
        if (!simple && vl != null)
            throw CypherError.fail("parse", "label expressions with & ! ( ) % are not supported on variable-length relationships", lt.loc,
                    List.of("SyntaxError", "InvalidRelationshipPattern"));
        if (!simple) {
            Object hl = vec(HAS_LABELS, v, vec(lx));
            w = w != null ? vec(OP, AND, hl, w) : hl;
        }
        expectPunct(s, "]");
        return new Object[]{v, types, vl, p, w};
    }

    private static boolean relPatternAhead(St s) {
        return atPunct(s, "-") || (atPunct(s, "<") && punct(peek(s, 1), "-"));
    }

    private static List<Object> relPattern(St s) {
        Tok t = peek(s);
        Object[] d;
        Kw dir;
        if (atPunct(s, "<")) {
            advance(s);
            expectPunct(s, "-");
            d = relDetail(s);
            expectPunct(s, "-");
            dir = acceptPunct(s, ">") ? BOTH : IN;
        } else {
            expectPunct(s, "-");
            d = relDetail(s);
            expectPunct(s, "-");
            dir = acceptPunct(s, ">") ? OUT : BOTH;
        }
        List<Object> r = vec(REL, d[0], d[1], d[3], dir, d[2], t.loc);
        if (d[4] != null) r.add(d[4]);
        return r;
    }

    /** 模式里节点 / 关系自带的 WHERE（(n WHERE …)、-[r WHERE …]-）。嵌套的模式推导在解析时已各自合并、去掉了。 */
    private static List<Object> inlineWheres(Object pattern) {
        List<Object> out = new ArrayList<>();
        collectWheres(pattern, out);
        return out;
    }

    private static void collectWheres(Object x, List<Object> out) {
        if (x instanceof List<?> l) {
            if (Clj.is(l, NODE) && l.size() == 6) out.add(l.get(5));
            else if (Clj.is(l, REL) && l.size() == 8) out.add(l.get(7));
            for (Object y : l) collectWheres(y, out);
        } else if (x instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                collectWheres(e.getKey(), out);
                collectWheres(e.getValue(), out);
            }
        }
    }

    /** clj walk/prewalk：先变换节点再进子节点。 */
    private static Object stripWheres(Object x) {
        if (x instanceof List<?> l) {
            List<?> y = l;
            if ((Clj.is(l, NODE) && l.size() == 6) || (Clj.is(l, REL) && l.size() == 8)) y = l.subList(0, Clj.is(l, NODE) ? 5 : 7);
            List<Object> out = new ArrayList<>(y.size());
            for (Object z : y) out.add(stripWheres(z));
            return out;
        }
        if (x instanceof Map<?, ?> m) {
            Map<Object, Object> out = new java.util.LinkedHashMap<>();
            m.forEach((k, v) -> out.put(stripWheres(k), stripWheres(v)));
            return out;
        }
        return x;
    }

    private static Object andAll(List<Object> ws) {
        Object a = ws.getFirst();
        for (int i = 1; i < ws.size(); i++) a = vec(OP, AND, a, ws.get(i));
        return a;
    }

    /** 模式里的 WHERE 并进外面的 WHERE（AND）⇒ [去掉之后的模式 合并的 WHERE]。 */
    private static Object[] desugarWheres(Object pattern, Object w) {
        List<Object> ws = inlineWheres(pattern);
        if (ws.isEmpty()) return new Object[]{pattern, w};
        if (w != null) ws.add(w);
        return new Object[]{stripWheres(pattern), andAll(ws)};
    }

    /**
     * MATCH 的图模式 + WHERE ⇒ [parts where]：带路径搜索前缀的 path pattern 的元素谓词挂在这个 part 上（第 5 个元素，挑选之前用），
     * 其余的元素谓词并进 WHERE。
     */
    private static Object[] matchPattern(St s) {
        List<Object> parts = new ArrayList<>();
        for (Object part : pattern(s)) {
            if (nth(part, 3) != null) {
                List<Object> ws = inlineWheres(vec(part));
                @SuppressWarnings("unchecked") List<Object> p2 = (List<Object>) stripWheres(part);
                if (!ws.isEmpty()) p2 = conj(p2, andAll(ws));
                parts.add(p2);
            } else parts.add(part);
        }
        return desugarWheres(parts, where(s));
    }

    private static void noInlineWhere(Object pattern, String what, List<Object> loc) {
        if (!inlineWheres(pattern).isEmpty())
            throw CypherError.fail("parse", "WHERE inside a pattern is not allowed in " + what, loc, List.of("SyntaxError", "InvalidRelationshipPattern"));
    }

    /** 关系 / 括号路径后面的量词：* + {k} {m,n}。 */
    private static boolean graphQuantifierAhead(St s) {
        if (atPunct(s, "*") || atPunct(s, "+")) return true;
        if (atPunct(s, "{")) {
            Tok n = peek(s, 1);
            return n.t == INT || punct(n, ",");
        }
        return false;
    }

    private static List<Object> graphQuantifier(St s) {
        if (acceptPunct(s, "*")) return vec(0L, null);
        if (acceptPunct(s, "+")) return vec(1L, null);
        expectPunct(s, "{");
        Object a = peek(s).t == INT ? advance(s).v : null;
        if (acceptPunct(s, ",")) {
            Object b = peek(s).t == INT ? advance(s).v : null;
            expectPunct(s, "}");
            return vec(a != null ? a : 0L, b);
        }
        expectPunct(s, "}");
        if (a == null) throw syntaxError(s);
        return vec(a, a);
    }

    private static List<Object> anonNode(List<Object> loc) {
        return vec(NODE, null, new ArrayList<>(), null, loc);
    }

    /** 量化路径的一步：sub = 子模式，w = 子模式里的 WHERE（含元素自带的），q = [mn mx]，pv = 子路径变量。 */
    private static List<Object> qppStep(Object sub, Object w, Object q, List<Object> loc, Object pv) {
        Object[] sw = desugarWheres(sub, w);
        return vec(QPP, sw[0], sw[1], q, loc, pv);
    }

    /**
     * 两个相邻的节点模式是同一个节点（openCypher 2024.3）：变量取有名字的那个、标签合并、属性 map 最多一个、WHERE 用 AND 合并。
     * ⇒ [合并的节点 别名-或-null]。
     */
    private static Object[] mergeAdjacent(List<?> a, List<?> b, List<Object> loc) {
        Object va = nth(a, 1), la = nth(a, 2), pa = nth(a, 3), aloc = nth(a, 4), wa = nth(a, 5);
        Object vb = nth(b, 1), lb = nth(b, 2), pb = nth(b, 3), wb = nth(b, 5);
        if (pa != null && pb != null) throw CypherError.fail("adjacent node patterns cannot both have a property map", loc);
        Object w = wa != null && wb != null ? vec(OP, AND, wa, wb) : (wa != null ? wa : wb);
        List<Object> labels = Clj.into(Clj.vecOf((List<?>) la), (List<?>) lb);
        List<Object> n = vec(NODE, va != null ? va : vb, labels, pa != null ? pa : pb, aloc);
        if (w != null) n.add(w);
        // 两个名字不同：第二个绑到同一个节点（别名）
        Object alias = va != null && vb != null && !Clj.eq(nth(va, 1), nth(vb, 1)) ? vec(vb, va) : null;
        return new Object[]{n, alias};
    }

    /**
     * 路径：节点 (关系 节点)…。qpp（MATCH / EXISTS 的图模式）⇒ 还认量化关系、量化括号路径（可嵌套）、不带量词的括号路径（展开进外面的链，
     * p = 记成这一段的子路径）、相邻的节点模式。⇒ [:chain n0 steps] 或 [:chain n0 steps 子路径 [别名…]]，子路径是 [p 起点下标 终点下标]。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> chain(St s, boolean qpp) {
        Tok t0 = peek(s);
        Supplier<Boolean> paren = () -> qpp && atPunct(s, "(")
                && (punct(peek(s, 1), "(") || (nameTok(peek(s, 1), Set.of()) && punct(peek(s, 2), "=") && punct(peek(s, 3), "(")));
        Supplier<Boolean> node = () -> qpp && atPunct(s, "(") && !paren.get();
        List<Object> n0 = paren.get() ? anonNode(t0.loc) : nodePattern(s);
        List<Object> aliases = new ArrayList<>();
        List<Object> steps = new ArrayList<>();
        List<Object> subs = new ArrayList<>();
        while (true) {
            List<Object> lastNode = steps.isEmpty() ? n0 : (List<Object>) ((List<Object>) steps.getLast()).get(1);
            int idx = steps.size();
            if (paren.get()) {
                Tok lt = advance(s);
                Object pv = null;
                if (nameTok(peek(s), Set.of()) && punct(peek(s, 1), "=")) {
                    pv = varName(s);
                    advance(s);
                }
                List<Object> sub = chain(s, true);
                Object w = acceptKw(s, "WHERE") ? expr(s) : null;
                expectPunct(s, ")");
                if (graphQuantifierAhead(s)) {
                    List<Object> q = graphQuantifier(s);
                    List<Object> n = node.get() ? nodePattern(s) : anonNode(lt.loc);
                    steps.add(vec(qppStep(sub, w, q, lt.loc, pv), n));
                } else {
                    // 不带量词：展开；子模式的首节点与当前末节点相邻 ⇒ 合并；括号里的 WHERE 是这一段的谓词
                    List<Object> x = (List<Object>) sub.get(1);
                    List<Object> xsteps = (List<Object>) sub.get(2);
                    List<Object> xsubs = sub.size() > 3 ? (List<Object>) sub.get(3) : List.of();
                    if (w != null) x = (List<Object>) mergeAdjacent(x, vec(NODE, null, new ArrayList<>(), null, lt.loc, w), lt.loc)[0];
                    Object[] ma = mergeAdjacent(lastNode, x, lt.loc);
                    if (steps.isEmpty()) n0 = (List<Object>) ma[0];
                    else setLast(steps, (List<Object>) ma[0]);
                    if (sub.size() > 4) aliases.addAll((List<Object>) sub.get(4));
                    if (ma[1] != null) aliases.add(ma[1]);
                    steps.addAll(xsteps);
                    for (Object sp : xsubs) {
                        List<?> sl = (List<?>) sp;
                        subs.add(vec(sl.get(0), idx + (Long) sl.get(1), idx + (Long) sl.get(2)));
                    }
                    if (pv != null) subs.add(vec(pv, (long) idx, (long) (idx + xsteps.size())));
                }
            } else if (node.get()) {
                Tok nt = peek(s);
                List<Object> n = nodePattern(s);
                Object[] ma = mergeAdjacent(lastNode, n, nt.loc);
                if (steps.isEmpty()) n0 = (List<Object>) ma[0];
                else setLast(steps, (List<Object>) ma[0]);
                if (ma[1] != null) aliases.add(ma[1]);
            } else if (relPatternAhead(s)) {
                Tok rt = peek(s);
                List<Object> r = relPattern(s);
                if (qpp && graphQuantifierAhead(s)) {
                    // 量化关系 -[r:T WHERE …]->{1,3} ⇒ 子模式 ()-[r]->()
                    List<Object> q = graphQuantifier(s);
                    if (r.get(5) != null)
                        throw CypherError.fail("parse", "a quantified relationship cannot also have *", rt.loc, List.of("SyntaxError", "InvalidRelationshipPattern"));
                    List<Object> n = node.get() ? nodePattern(s) : anonNode(rt.loc);
                    steps.add(vec(qppStep(vec(CHAIN, anonNode(rt.loc), vec(vec(r, anonNode(rt.loc)))), null, q, rt.loc, null), n));
                } else {
                    List<Object> n = nodePattern(s);
                    steps.add(vec(r, n));
                }
            } else {
                List<Object> out = vec(CHAIN, n0, steps);
                if (!subs.isEmpty() || !aliases.isEmpty()) out.add(subs);
                if (!aliases.isEmpty()) out.add(aliases);
                return out;
            }
        }
    }

    /** 把链上的末节点换成 m（步表非空时改最后一步的节点）。 */
    @SuppressWarnings("unchecked")
    private static void setLast(List<Object> steps, List<Object> m) {
        List<Object> last = (List<Object>) steps.getLast();
        steps.set(steps.size() - 1, vec(last.get(0), m));
    }

    private static boolean word(Tok t, String w) {
        if (t.t != IDENT && t.t != KW) return false;
        Object x = t.name != null ? t.name : t.v;
        return w.equals(String.valueOf(x).toLowerCase(Locale.ROOT));
    }

    private static void pathKeywords(St s) {
        if (word(peek(s), "path") || word(peek(s), "paths")) advance(s);
    }

    private static Object pathCount(St s) {
        Tok t = peek(s);
        if (t.t == INT) {
            advance(s);
            return t.v;
        }
        if (t.t == PARAM) {
            advance(s);
            return vec(PARAM, t.v, t.loc);
        }
        return null;
    }

    /** 路径搜索前缀（openCypher 2024.3）：ALL | ANY [k] | ANY SHORTEST | ALL SHORTEST | SHORTEST k | SHORTEST [k] GROUP(S)，可跟 PATH / PATHS。 */
    private static Map<Object, Object> pathPrefix(St s) {
        Tok t = peek(s), t1 = peek(s, 1);
        if (word(t, "all") && word(t1, "shortest")) {
            advance(s);
            advance(s);
            pathKeywords(s);
            return Clj.map(KIND, ALL_SHORTEST, LOC, t.loc);
        }
        if (word(t, "any") && word(t1, "shortest")) {
            advance(s);
            advance(s);
            pathKeywords(s);
            return Clj.map(KIND, ANY_SHORTEST, LOC, t.loc);
        }
        if (word(t, "all")) {
            advance(s);
            pathKeywords(s);
            return Clj.map(KIND, ALL, LOC, t.loc);
        }
        if (word(t, "any") && !punct(t1, "=")) {
            advance(s);
            Object k = pathCount(s);
            pathKeywords(s);
            return Clj.map(KIND, ANY, K, k != null ? k : 1L, LOC, t.loc);
        }
        if (word(t, "shortest") && !punct(t1, "=") && !punct(t1, "(")) {
            advance(s);
            Object k = pathCount(s);
            pathKeywords(s);
            if (word(peek(s), "group") || word(peek(s), "groups")) {
                advance(s);
                return Clj.map(KIND, SHORTEST_GROUPS, K, k != null ? k : 1L, LOC, t.loc);
            }
            if (k == null) throw CypherError.fail("SHORTEST needs a number of paths (or GROUPS)", t.loc);
            return Clj.map(KIND, SHORTEST, K, k, LOC, t.loc);
        }
        return null;
    }

    private static List<Object> patternElem(St s) {
        Tok t = peek(s);
        if (t.t == IDENT && punct(peek(s, 1), "(")) {
            String nm = ((String) t.v).toLowerCase(Locale.ROOT);
            advance(s);
            advance(s);
            Kw kind = switch (nm) {
                case "shortestpath" -> SINGLE;
                case "allshortestpaths" -> ALL;
                default -> throw CypherError.fail("unknown path function " + t.v, t.loc);
            };
            List<Object> c = chain(s, false);
            expectPunct(s, ")");
            return vec(SHORTEST, kind, c, t.loc);
        }
        return chain(s, true);
    }

    private static List<Object> patternPart(St s) {
        Object pv = null;
        if (nameTok(peek(s), Set.of()) && punct(peek(s, 1), "=")) {
            pv = varName(s);
            advance(s);
        }
        Map<Object, Object> prefix = pathPrefix(s);
        List<Object> elem = patternElem(s);
        List<Object> part = vec(PART, pv, elem);
        if (prefix != null) part.add(prefix);
        return part;
    }

    private static List<Object> pattern(St s) {
        List<Object> acc = vec(patternPart(s));
        while (acceptPunct(s, ",")) acc.add(patternPart(s));
        return acc;
    }

    // ═══ 子句 ═══

    private static Object where(St s) {
        return acceptKw(s, "WHERE") ? expr(s) : null;
    }

    private static List<Object> projection(St s) {
        Supplier<Object> item = () -> {
            int from = s.i;
            Object e = expr(s);
            String text = sourceSince(s, from);
            Object alias = acceptKw(s, "AS") ? varName(s) : null;
            return vec(ITEM, e, alias, text);
        };
        Supplier<List<Object>> items = () -> {
            List<Object> acc = vec(item.get());
            while (acceptPunct(s, ",")) acc.add(item.get());
            return acc;
        };
        if (acceptPunct(s, "*")) return vec(STAR, acceptPunct(s, ",") ? items.get() : new ArrayList<>());
        return vec(ITEMS, items.get());
    }

    private static List<Object> order(St s) {
        List<Object> acc = new ArrayList<>();
        if (!atKw(s, "ORDER")) return acc;
        advance(s);
        expectKw(s, "BY");
        do {
            Object e = expr(s);
            Object dir;
            if (acceptKw(s, "ASC") || acceptKw(s, "ASCENDING")) dir = ASC;
            else if (acceptKw(s, "DESC") || acceptKw(s, "DESCENDING")) dir = DESC;
            else dir = ASC;
            // NULLS FIRST / LAST（来自 SQL / GQL；bitcask 保留，keelson 同步）
            if (word(peek(s), "nulls") && (word(peek(s, 1), "first") || word(peek(s, 1), "last"))) {
                advance(s);
                Tok w = advance(s);
                dir = vec(dir, kw(String.valueOf(w.name != null ? w.name : w.v).toLowerCase(Locale.ROOT)));
            }
            acc.add(vec(e, dir));
        } while (acceptPunct(s, ","));
        return acc;
    }

    /** SET / REMOVE 的目标：v 或 (v)。 */
    private static List<Object> parenVar(St s) {
        if (acceptPunct(s, "(")) {
            List<Object> v = varName(s);
            expectPunct(s, ")");
            return v;
        }
        return varName(s);
    }

    private static Object setItem(St s) {
        List<Object> v = parenVar(s);
        if (acceptPunct(s, ".")) {
            String k = symbolicName(s);
            expectPunct(s, "=");
            return vec(SET_PROP, v, k, expr(s));
        }
        if (acceptPunct(s, "=")) return vec(SET_PROPS, v, expr(s), REPLACE);
        if (acceptPunct(s, "+=")) return vec(SET_PROPS, v, expr(s), MERGE_KW);
        if (atPunct(s, ":")) return vec(SET_LABELS, v, nodeLabels(s));
        throw syntaxError(s);
    }

    private static Object removeItem(St s) {
        List<Object> v = parenVar(s);
        if (acceptPunct(s, ".")) return vec(REMOVE_PROP, v, symbolicName(s));
        if (atPunct(s, ":")) return vec(REMOVE_LABELS, v, nodeLabels(s));
        throw syntaxError(s);
    }

    private static List<Object> commaList(St s, Function<St, Object> f) {
        List<Object> acc = vec(f.apply(s));
        while (acceptPunct(s, ",")) acc.add(f.apply(s));
        return acc;
    }

    private static Object clause(St s) {
        Tok t = peek(s);
        List<Object> loc = t.loc;
        if (t.t != KW) throw syntaxError(s);
        switch ((String) t.v) {
            case "MATCH" -> {
                advance(s);
                Object[] pw = matchPattern(s);
                return vec(MATCH, false, pw[0], pw[1], loc);
            }
            case "OPTIONAL" -> {
                advance(s);
                expectKw(s, "MATCH");
                Object[] pw = matchPattern(s);
                return vec(MATCH, true, pw[0], pw[1], loc);
            }
            case "MERGE" -> {
                advance(s);
                List<Object> part = patternPart(s);
                noInlineWhere(vec(part), "MERGE", loc);
                List<Object> onCreate = new ArrayList<>(), onMatch = new ArrayList<>();
                while (atKw(s, "ON") && (kwIs(peek(s, 1), "CREATE") || kwIs(peek(s, 1), "MATCH"))) {
                    advance(s);
                    boolean create = kwIs(advance(s), "CREATE");
                    expectKw(s, "SET");
                    List<Object> items = commaList(s, Parser::setItem);
                    (create ? onCreate : onMatch).addAll(items);
                }
                return vec(MERGE, part, onCreate, onMatch, loc);
            }
            case "UNWIND" -> {
                advance(s);
                Object e = expr(s);
                expectKw(s, "AS");
                return vec(UNWIND, e, varName(s), loc);
            }
            case "CALL" -> {
                advance(s);
                // 过程名：至少两段（ns.name，可以更多）；参数：(表达式 …)，括号可省 = 隐式参数（取同名的查询参数）
                List<String> nm = new ArrayList<>(List.of(symbolicName(s)));
                while (acceptPunct(s, ".")) nm.add(symbolicName(s));
                if (nm.size() < 2) throw CypherError.fail("procedure names need a namespace, e.g. db.labels", loc);
                String pname = String.join(".", nm).toLowerCase(Locale.ROOT);
                Object args = null;
                if (acceptPunct(s, "(")) {
                    if (acceptPunct(s, ")")) args = new ArrayList<>();
                    else {
                        List<Object> es = commaList(s, Parser::expr);
                        expectPunct(s, ")");
                        args = es;
                    }
                }
                if (acceptKw(s, "YIELD")) {
                    if (acceptPunct(s, "*")) return vec(CALL_PROC, pname, args, STAR, where(s), loc);
                    List<Object> items = commaList(s, st -> {
                        List<Object> v = varName(st);
                        return vec(v, acceptKw(st, "AS") ? varName(st) : v);
                    });
                    return vec(CALL_PROC, pname, args, items, where(s), loc);
                }
                return vec(CALL_PROC, pname, args, null, null, loc);
            }
            case "WITH" -> {
                advance(s);
                boolean d = acceptKw(s, "DISTINCT");
                List<Object> p = projection(s);
                List<Object> o = order(s);
                Object sk = (acceptKw(s, "SKIP") || acceptKw(s, "OFFSET")) ? expr(s) : null;
                Object li = acceptKw(s, "LIMIT") ? expr(s) : null;
                return vec(WITH, d, p, o, sk, li, where(s), loc);
            }
            case "RETURN" -> {
                advance(s);
                boolean d = acceptKw(s, "DISTINCT");
                List<Object> p = projection(s);
                List<Object> o = order(s);
                Object sk = (acceptKw(s, "SKIP") || acceptKw(s, "OFFSET")) ? expr(s) : null;
                Object li = acceptKw(s, "LIMIT") ? expr(s) : null;
                return vec(RETURN, d, p, o, sk, li, loc);
            }
            case "CREATE" -> {
                advance(s);
                List<Object> p = pattern(s);
                noInlineWhere(p, "CREATE", loc);
                return vec(CREATE, p, loc);
            }
            case "SET" -> {
                advance(s);
                return vec(SET, commaList(s, Parser::setItem), loc);
            }
            case "REMOVE" -> {
                advance(s);
                return vec(REMOVE, commaList(s, Parser::removeItem), loc);
            }
            case "DELETE" -> {
                advance(s);
                return vec(DELETE, false, commaList(s, Parser::expr), loc);
            }
            case "DETACH" -> {
                advance(s);
                expectKw(s, "DELETE");
                return vec(DELETE, true, commaList(s, Parser::expr), loc);
            }
            default -> throw syntaxError(s);
        }
    }

    /** 文本 ⇒ AST {@code [:query [子句]]}，或 UNION 连接的多个查询 {@code [:union [all? …] [[:query …] …] loc]}。 */
    static Object parse(String src) {
        St s = new St(tokenize(src), src);
        List<Object> parts = new ArrayList<>(), alls = new ArrayList<>();
        Object uloc = null;
        List<Object> acc = vec(clause(s));
        while (true) {
            Tok t = peek(s);
            boolean end = t.t == END;
            if (!end && punct(t, ";")) {
                advance(s);
                if (peek(s).t == END) end = true;
                else throw syntaxError(s);
            }
            if (end) {
                if (parts.isEmpty()) return vec(QUERY, acc);
                parts.add(vec(QUERY, acc));
                return vec(UNION, alls, parts, uloc);
            }
            if (kwIs(t, "UNION")) {
                advance(s);
                boolean all = acceptKw(s, "ALL");
                if (!all) acceptKw(s, "DISTINCT");
                parts.add(vec(QUERY, acc));
                alls.add(all);
                if (uloc == null) uloc = t.loc;
                acc = vec(clause(s));
            } else acc.add(clause(s));
        }
    }
}
