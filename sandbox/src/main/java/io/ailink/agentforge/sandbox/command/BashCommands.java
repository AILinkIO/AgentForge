package io.ailink.agentforge.sandbox.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code bash -lc "…"} 脚本的两种拆法（移植自 Codex shell-command/src/bash.rs，Apache-2.0；上游用 tree-sitter-bash，这里是手写的等价子集）：
 * <ul>
 * <li>{@link #parsePlainCommands}：<b>严格</b>——整段只由「纯字面词」组成的简单命令经 {@code && || ; |}（及换行）串起来时，回每条命令的词表；
 *     出现括号、重定向、变量 / 命令替换、通配、花括号展开、波浪号、转义、赋值前缀、控制流等任何会在运行时改变 argv 的东西 ⇒ {@code null}。
 *     只有它能用来<b>证明</b>命令安全（已知安全 / 规则匹配）。</li>
 * <li>{@link #parseLiteralCommands}：<b>宽松</b>——接受完整的 shell 语法（控制流、替换、重定向、管道、后台），回每个命令节点里静态可知的词
 *     （动态词与重定向略去；命令名本身是动态的整条略去）。语法错 ⇒ {@code null}。只用来<b>发现</b>危险命令，⛔ 用来证明安全。</li>
 * </ul>
 */
public final class BashCommands {
    private BashCommands() {
    }

    private static final Set<String> SHELLS = Set.of("bash", "zsh", "sh");

    /** {@code [shell, -c|-lc, script]}（shell 的 basename 是 bash / zsh / sh）⇒ script；否则 {@code null}（Codex extract_bash_command）。 */
    public static String extractBashScript(List<String> command) {
        if (command == null || command.size() != 3) return null;
        String flag = command.get(1);
        if (!flag.equals("-lc") && !flag.equals("-c")) return null;
        String shell = command.get(0);
        int slash = shell.lastIndexOf('/');
        String base = slash >= 0 ? shell.substring(slash + 1) : shell;
        return SHELLS.contains(base) ? command.get(2) : null;
    }

    /** {@code bash -lc "…"} 里的纯命令序列（Codex parse_shell_lc_plain_commands）。 */
    public static List<List<String>> parseShellLcPlainCommands(List<String> command) {
        String script = extractBashScript(command);
        return script == null ? null : parsePlainCommands(script);
    }

    /** {@code bash -lc "…"} 里的字面命令（Codex parse_shell_lc_literal_commands）。 */
    public static List<List<String>> parseShellLcLiteralCommands(List<String> command) {
        String script = extractBashScript(command);
        return script == null ? null : parseLiteralCommands(script);
    }

    // ══ 严格：纯字面词 + 安全运算符 ══════════════════════════════════════

    private static final Set<String> RESERVED = Set.of("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
            "function", "select", "time", "coproc", "in", "!", "{", "}", "[[", "]]");

    /** 运行时会改写的字符（Codex is_literal_word_or_number 的那一组）。 */
    private static boolean expandable(char c) {
        return "{}*?[]\\~^#$`".indexOf(c) >= 0;
    }

    private static boolean isWordBreak(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || ";&|()<>".indexOf(c) >= 0;
    }

    /**
     * 只由纯字面词与 {@code && || ; |} 组成的脚本 ⇒ 每条命令的词表（空脚本 ⇒ 空表）；否则 {@code null}（Codex parse_shell_script_into_commands）。
     */
    public static List<List<String>> parsePlainCommands(String src) {
        List<List<String>> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();
        // 上一个记号：0 = 开头 / 换行之后，1 = 词，2 = 二元运算符（&& || |），3 = 分号
        int prev = 0;
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') {
                i++;
                continue;
            }
            if (c == '\n') {
                if (prev == 1) {
                    commands.add(current);
                    current = new ArrayList<>();
                    prev = 0;
                } else if (prev == 3) {
                    prev = 0;
                }
                // 开头 / 二元运算符之后的换行：bash 允许续行
                i++;
                continue;
            }
            if (c == '&' || c == '|' || c == ';') {
                String op;
                if (c == '&') {
                    if (i + 1 < n && src.charAt(i + 1) == '&') op = "&&";
                    else return null; // 后台 & 不在白名单
                } else if (c == '|') {
                    if (i + 1 < n && src.charAt(i + 1) == '|') op = "||";
                    else if (i + 1 < n && src.charAt(i + 1) == '&') return null;
                    else op = "|";
                } else {
                    if (i + 1 < n && (src.charAt(i + 1) == ';' || src.charAt(i + 1) == '&')) return null;
                    op = ";";
                }
                if (prev != 1) return null; // 空命令位
                commands.add(current);
                current = new ArrayList<>();
                prev = op.equals(";") ? 3 : 2;
                i += op.length();
                continue;
            }
            if ("()<>".indexOf(c) >= 0) return null;
            // 一个词（可能是几段拼接）
            StringBuilder word = new StringBuilder();
            boolean firstWord = current.isEmpty();
            boolean anyQuoted = false;
            StringBuilder leadingUnquoted = new StringBuilder();
            boolean sawQuote = false;
            while (i < n && !isWordBreak(src.charAt(i))) {
                char d = src.charAt(i);
                if (d == '\'') {
                    int end = src.indexOf('\'', i + 1);
                    if (end < 0) return null;
                    word.append(src, i + 1, end);
                    i = end + 1;
                    anyQuoted = true;
                    sawQuote = true;
                } else if (d == '"') {
                    int j = i + 1;
                    StringBuilder s = new StringBuilder();
                    boolean closed = false;
                    while (j < n) {
                        char e = src.charAt(j);
                        if (e == '"') {
                            closed = true;
                            break;
                        }
                        if (e == '$' || e == '`') return null;
                        if (e == '\\' && j + 1 < n && "$`\"\\\n".indexOf(src.charAt(j + 1)) >= 0) return null;
                        s.append(e);
                        j++;
                    }
                    if (!closed) return null;
                    word.append(s);
                    i = j + 1;
                    anyQuoted = true;
                    sawQuote = true;
                } else {
                    // 一段不带引号的字面
                    int j = i;
                    while (j < n && !isWordBreak(src.charAt(j)) && src.charAt(j) != '\'' && src.charAt(j) != '"') j++;
                    String seg = src.substring(i, j);
                    if (seg.startsWith("=")) return null;
                    for (int k = 0; k < seg.length(); k++) if (expandable(seg.charAt(k))) return null;
                    if (!sawQuote) leadingUnquoted.append(seg);
                    word.append(seg);
                    i = j;
                }
            }
            if (word.isEmpty() && anyQuoted) return null; // "" 这类空拼接
            if (firstWord) {
                String lead = leadingUnquoted.toString();
                if (!anyQuoted && RESERVED.contains(lead)) return null;
                if (isAssignmentPrefix(lead)) return null;
            }
            current.add(word.toString());
            prev = 1;
        }
        if (prev == 2) return null; // 末尾悬着的 && || |
        if (prev == 1) commands.add(current);
        return commands;
    }

    /** {@code NAME=…} / {@code NAME+=…}（shell 变量赋值前缀）。 */
    static boolean isAssignmentPrefix(String s) {
        int eq = s.indexOf('=');
        if (eq <= 0) return false;
        String name = s.substring(0, eq);
        if (name.endsWith("+")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || !(Character.isLetter(name.charAt(0)) || name.charAt(0) == '_')) return false;
        for (int k = 1; k < name.length(); k++) {
            char c = name.charAt(k);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    // ══ 宽松：完整语法里的字面命令 ═══════════════════════════════════════

    /**
     * 任意 shell 脚本里每个命令节点的静态词（Codex parse_shell_lc_literal_commands 的脚本部分）；语法错 ⇒ {@code null}。
     * 命令替换 {@code $(…)} / 反引号 / 进程替换里的命令也算（递归）。
     */
    public static List<List<String>> parseLiteralCommands(String src) {
        try {
            List<List<String>> out = new ArrayList<>();
            new LiteralParser(src, out).parseProgram();
            return out;
        } catch (SyntaxError e) {
            return null;
        }
    }

    private static final class SyntaxError extends RuntimeException {
        SyntaxError() {
            super(null, null, false, false);
        }
    }

    /** 记号：词（{@code literal} 为 null = 动态）、运算符、重定向。 */
    private record Tok(Kind kind, String text, String literal) {
        enum Kind { WORD, OP, REDIR, EOF }

        boolean isOp(String s) {
            return kind == Kind.OP && text.equals(s);
        }

        boolean isWord(String s) {
            return kind == Kind.WORD && literal != null && literal.equals(s) && !text.contains("'") && !text.contains("\"");
        }
    }

    /** 词法 + 简单的递归下降：只为认出命令节点与发现语法错，不求值。 */
    private static final class LiteralParser {
        private final String src;
        private final List<List<String>> out;
        private final List<Tok> toks = new ArrayList<>();
        private int p;
        private final List<String> pendingHeredocs = new ArrayList<>();

        LiteralParser(String src, List<List<String>> out) {
            this.src = src;
            this.out = out;
            lex();
        }

        // ── 词法 ────────────────────────────────────────────────────

        private void lex() {
            int i = 0, n = src.length();
            boolean atWordStart = true;
            while (i < n) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r') {
                    i++;
                    atWordStart = true;
                    continue;
                }
                if (c == '\\' && i + 1 < n && src.charAt(i + 1) == '\n') {
                    i += 2;
                    continue;
                }
                if (c == '\n') {
                    toks.add(new Tok(Tok.Kind.OP, "\n", null));
                    i++;
                    if (!pendingHeredocs.isEmpty()) i = skipHeredocs(i);
                    atWordStart = true;
                    continue;
                }
                if (c == '#' && atWordStart) {
                    while (i < n && src.charAt(i) != '\n') i++;
                    continue;
                }
                // 运算符
                String op = matchOp(i);
                if (op != null) {
                    if (op.startsWith("<(") || op.startsWith(">(")) {
                        int end = matchParen(i + 1);
                        recurse(src.substring(i + 2, end));
                        toks.add(new Tok(Tok.Kind.WORD, src.substring(i, end + 1), null));
                        i = end + 1;
                        atWordStart = false;
                        continue;
                    }
                    boolean redir = op.contains("<") || op.contains(">") || op.equals("&>") || op.equals("&>>");
                    toks.add(new Tok(redir ? Tok.Kind.REDIR : Tok.Kind.OP, op, null));
                    if (op.equals("<<") || op.equals("<<-")) {
                        i += op.length();
                        while (i < n && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
                        int[] end = new int[1];
                        String delim = readWordRaw(i, end);
                        pendingHeredocs.add(unquote(delim));
                        toks.add(new Tok(Tok.Kind.WORD, delim, null));
                        i = end[0];
                        atWordStart = true;
                        continue;
                    }
                    i += op.length();
                    atWordStart = true;
                    continue;
                }
                // fd 数字紧跟重定向：2>…
                int j = i;
                while (j < n && Character.isDigit(src.charAt(j))) j++;
                if (j > i && j < n && (src.charAt(j) == '<' || src.charAt(j) == '>')) {
                    i = j;
                    continue;
                }
                int[] end = new int[1];
                StringBuilder lit = new StringBuilder();
                boolean literal = readWord(i, end, lit);
                String text = src.substring(i, end[0]);
                toks.add(new Tok(Tok.Kind.WORD, text, literal && !lit.isEmpty() ? lit.toString() : null));
                i = end[0];
                atWordStart = false;
            }
            if (!pendingHeredocs.isEmpty()) throw new SyntaxError();
            toks.add(new Tok(Tok.Kind.EOF, "", null));
        }

        private static final String[] OPS = {"<<<", "<<-", "&>>", ";;&", "&&", "||", ";;", ";&", "|&", ">>", "<<", "<&", ">&", "&>", "<>", ">|",
                "<(", ">(", "(", ")", ";", "&", "|", "<", ">"};

        private String matchOp(int i) {
            for (String op : OPS) if (src.startsWith(op, i)) return op;
            return null;
        }

        /** {@code src[open]} 是 '('：找配对的 ')'（跳过引号、嵌套）。 */
        private int matchParen(int open) {
            int depth = 0, i = open, n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    int e = src.indexOf('\'', i + 1);
                    if (e < 0) throw new SyntaxError();
                    i = e + 1;
                    continue;
                }
                if (c == '"') {
                    i = skipDouble(i);
                    continue;
                }
                if (c == '`') {
                    i = skipBacktick(i);
                    continue;
                }
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
                i++;
            }
            throw new SyntaxError();
        }

        private int skipDouble(int open) {
            int i = open + 1, n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '"') return i + 1;
                if (c == '$' && i + 1 < n && src.charAt(i + 1) == '(') {
                    i = matchParen(i + 1) + 1;
                    continue;
                }
                if (c == '`') {
                    i = skipBacktick(i);
                    continue;
                }
                i++;
            }
            throw new SyntaxError();
        }

        private int skipBacktick(int open) {
            int i = open + 1, n = src.length();
            while (i < n) {
                char c = src.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == '`') return i + 1;
                i++;
            }
            throw new SyntaxError();
        }

        private void recurse(String inner) {
            new LiteralParser(inner, out).parseProgram();
        }

        /** 读一个词到 {@code end[0]}；{@code lit} 收字面值；回它是不是纯字面（Codex parse_literal_shell_word）。 */
        private boolean readWord(int start, int[] end, StringBuilder lit) {
            int i = start, n = src.length();
            boolean literal = true;
            while (i < n) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || ";&|()<>".indexOf(c) >= 0) break;
                if (c == '\'') {
                    int e = src.indexOf('\'', i + 1);
                    if (e < 0) throw new SyntaxError();
                    lit.append(src, i + 1, e);
                    i = e + 1;
                } else if (c == '"') {
                    int j = i + 1;
                    boolean dynamic = false;
                    StringBuilder s = new StringBuilder();
                    while (true) {
                        if (j >= n) throw new SyntaxError();
                        char e = src.charAt(j);
                        if (e == '"') break;
                        if (e == '\\') {
                            if (j + 1 < n && "$`\"\\\n".indexOf(src.charAt(j + 1)) >= 0) dynamic = true;
                            if (j + 1 < n) s.append(e).append(src.charAt(j + 1));
                            j += 2;
                            continue;
                        }
                        if (e == '$' && j + 1 < n && src.charAt(j + 1) == '(') {
                            int close = matchParen(j + 1);
                            if (!src.startsWith("((", j + 1)) recurse(src.substring(j + 2, close));
                            dynamic = true;
                            j = close + 1;
                            continue;
                        }
                        if (e == '`') {
                            int close = skipBacktick(j);
                            recurse(src.substring(j + 1, close - 1));
                            dynamic = true;
                            j = close;
                            continue;
                        }
                        if (e == '$') dynamic = true;
                        s.append(e);
                        j++;
                    }
                    if (dynamic) literal = false;
                    else lit.append(s);
                    i = j + 1;
                } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '(') {
                    int close = matchParen(i + 1);
                    if (!src.startsWith("((", i + 1)) recurse(src.substring(i + 2, close));
                    literal = false;
                    i = close + 1;
                } else if (c == '$' && i + 1 < n && src.charAt(i + 1) == '{') {
                    int close = src.indexOf('}', i + 2);
                    if (close < 0) throw new SyntaxError();
                    literal = false;
                    i = close + 1;
                } else if (c == '`') {
                    int close = skipBacktick(i);
                    recurse(src.substring(i + 1, close - 1));
                    literal = false;
                    i = close;
                } else if (c == '\\') {
                    literal = false;
                    i += 2;
                } else {
                    if (expandable(c) || (c == '=' && i == start)) literal = false;
                    lit.append(c);
                    i++;
                }
            }
            end[0] = Math.min(i, n);
            return literal;
        }

        private String readWordRaw(int start, int[] end) {
            readWord(start, end, new StringBuilder());
            return src.substring(start, end[0]);
        }

        private static String unquote(String s) {
            return s.replace("'", "").replace("\"", "").replace("\\", "");
        }

        private int skipHeredocs(int i) {
            int n = src.length();
            for (String delim : pendingHeredocs) {
                while (true) {
                    if (i >= n) throw new SyntaxError();
                    int nl = src.indexOf('\n', i);
                    String line = nl < 0 ? src.substring(i) : src.substring(i, nl);
                    i = nl < 0 ? n : nl + 1;
                    if (line.strip().equals(delim)) break;
                }
            }
            pendingHeredocs.clear();
            return i;
        }

        // ── 语法 ────────────────────────────────────────────────────

        private Tok peek() {
            return toks.get(p);
        }

        private Tok next() {
            return toks.get(p++);
        }

        private void skipNewlines() {
            while (peek().isOp("\n")) p++;
        }

        void parseProgram() {
            parseList(Set.of());
            if (peek().kind() != Tok.Kind.EOF) throw new SyntaxError();
        }

        /** 一串命令，直到遇到 {@code terminators} 里的保留字 / 运算符（不吃掉）；回解析到的命令条数。 */
        private int parseList(Set<String> terminators) {
            int count = 0;
            while (true) {
                skipNewlines();
                Tok t = peek();
                if (t.kind() == Tok.Kind.EOF) return count;
                if (t.kind() == Tok.Kind.OP && (t.text().equals(")") || t.text().equals(";;") || t.text().equals(";&") || t.text().equals(";;&"))) {
                    if (terminators.contains(t.text())) return count;
                    throw new SyntaxError();
                }
                if (isTerminator(t, terminators)) return count;
                parsePipeline();
                count++;
                Tok sep = peek();
                if (sep.isOp(";") || sep.isOp("&") || sep.isOp("\n")) {
                    p++;
                } else if (sep.isOp("&&") || sep.isOp("||")) {
                    p++;
                    skipNewlines();
                    Tok after = peek();
                    if (after.kind() == Tok.Kind.EOF || (after.kind() == Tok.Kind.OP && !after.isOp("("))) throw new SyntaxError();
                } else if (sep.kind() != Tok.Kind.EOF && !(sep.kind() == Tok.Kind.OP && terminators.contains(sep.text()))
                        && !isTerminator(sep, terminators)) {
                    throw new SyntaxError();
                }
            }
        }

        private static boolean isTerminator(Tok t, Set<String> terminators) {
            return t.kind() == Tok.Kind.WORD && t.literal() != null && terminators.contains(t.literal()) && t.isWord(t.literal());
        }

        private void parsePipeline() {
            if (peek().isWord("!") || peek().isWord("time")) p++;
            parseCommand();
            while (peek().isOp("|") || peek().isOp("|&")) {
                p++;
                skipNewlines();
                if (peek().kind() == Tok.Kind.EOF || (peek().kind() == Tok.Kind.OP && !peek().isOp("("))) throw new SyntaxError();
                parseCommand();
            }
        }

        private void requireBody(Set<String> terminators) {
            if (parseList(terminators) == 0) throw new SyntaxError();
        }

        private void expectWord(String w) {
            skipNewlines();
            if (!peek().isWord(w)) throw new SyntaxError();
            p++;
        }

        private void parseCommand() {
            Tok t = peek();
            if (t.isOp("(")) {
                p++;
                if (peek().isOp("(")) { // (( 算术 ))
                    skipArithmetic();
                } else {
                    requireBody(Set.of(")"));
                    if (!peek().isOp(")")) throw new SyntaxError();
                    p++;
                }
                skipRedirects();
                return;
            }
            if (t.kind() == Tok.Kind.REDIR) {
                parseSimpleCommand();
                return;
            }
            if (t.kind() != Tok.Kind.WORD) throw new SyntaxError();
            if (t.isWord("{")) {
                p++;
                requireBody(Set.of("}"));
                expectWord("}");
                skipRedirects();
                return;
            }
            if (t.isWord("if")) {
                p++;
                requireBody(Set.of("then"));
                expectWord("then");
                requireBody(Set.of("elif", "else", "fi"));
                while (peek().isWord("elif")) {
                    p++;
                    requireBody(Set.of("then"));
                    expectWord("then");
                    requireBody(Set.of("elif", "else", "fi"));
                }
                if (peek().isWord("else")) {
                    p++;
                    requireBody(Set.of("fi"));
                }
                expectWord("fi");
                skipRedirects();
                return;
            }
            if (t.isWord("while") || t.isWord("until")) {
                p++;
                requireBody(Set.of("do"));
                parseDoGroup();
                return;
            }
            if (t.isWord("for") || t.isWord("select")) {
                p++;
                if (peek().isOp("(")) {
                    skipArithmeticFor();
                } else {
                    if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                    p++;
                    skipNewlines();
                    if (peek().isWord("in")) {
                        p++;
                        while (peek().kind() == Tok.Kind.WORD) p++;
                    }
                    if (peek().isOp(";") || peek().isOp("\n")) p++;
                }
                parseDoGroup();
                return;
            }
            if (t.isWord("case")) {
                p++;
                if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                p++;
                expectWord("in");
                while (true) {
                    skipNewlines();
                    if (peek().isWord("esac")) {
                        p++;
                        break;
                    }
                    if (peek().isOp("(")) p++;
                    // 模式：词 (| 词)* )
                    if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                    p++;
                    while (peek().isOp("|")) {
                        p++;
                        if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                        p++;
                    }
                    if (!peek().isOp(")")) throw new SyntaxError();
                    p++;
                    parseList(Set.of(";;", ";&", ";;&", "esac"));
                    if (peek().isOp(";;") || peek().isOp(";&") || peek().isOp(";;&")) p++;
                }
                skipRedirects();
                return;
            }
            if (t.isWord("function")) {
                p++;
                if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                p++;
                if (peek().isOp("(")) {
                    p++;
                    if (!peek().isOp(")")) throw new SyntaxError();
                    p++;
                }
                skipNewlines();
                parseCommand();
                return;
            }
            if (t.kind() == Tok.Kind.WORD && t.text().equals("[[")) {
                p++;
                while (!(peek().kind() == Tok.Kind.WORD && peek().text().equals("]]"))) {
                    if (peek().kind() == Tok.Kind.EOF) throw new SyntaxError();
                    p++;
                }
                p++;
                skipRedirects();
                return;
            }
            if (t.literal() != null && RESERVED.contains(t.literal()) && !t.text().contains("'") && !t.text().contains("\"")
                    && !t.literal().equals("[[")) {
                throw new SyntaxError(); // 不在位置上的保留字（如 if then …）
            }
            // 函数定义：name ( ) body
            if (p + 2 < toks.size() && toks.get(p + 1).isOp("(") && toks.get(p + 2).isOp(")")) {
                p += 3;
                skipNewlines();
                parseCommand();
                return;
            }
            parseSimpleCommand();
        }

        private void parseDoGroup() {
            expectWord("do");
            requireBody(Set.of("done"));
            expectWord("done");
            skipRedirects();
        }

        private void skipArithmetic() {
            // 已吃掉第一个 '('，当前是第二个 '('
            int depth = 1;
            while (depth > 0) {
                Tok t = next();
                if (t.kind() == Tok.Kind.EOF) throw new SyntaxError();
                if (t.isOp("(")) depth++;
                else if (t.isOp(")")) depth--;
            }
            if (!peek().isOp(")")) throw new SyntaxError();
            p++;
        }

        private void skipArithmeticFor() {
            p++; // (
            if (!peek().isOp("(")) throw new SyntaxError();
            skipArithmetic();
            if (peek().isOp(";") || peek().isOp("\n")) p++;
        }

        private void skipRedirects() {
            while (peek().kind() == Tok.Kind.REDIR) {
                p++;
                if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                p++;
            }
        }

        /** 简单命令：赋值前缀、重定向略去；命令名动态 ⇒ 整条略去；之后的动态词略去。 */
        private void parseSimpleCommand() {
            List<String> words = new ArrayList<>();
            boolean haveName = false, nameLiteral = false, any = false;
            while (true) {
                Tok t = peek();
                if (t.kind() == Tok.Kind.REDIR) {
                    p++;
                    if (peek().kind() != Tok.Kind.WORD) throw new SyntaxError();
                    p++;
                    any = true;
                    continue;
                }
                if (t.kind() != Tok.Kind.WORD) break;
                p++;
                any = true;
                if (!haveName) {
                    if (isAssignmentPrefix(leadingUnquoted(t.text()))) continue;
                    haveName = true;
                    nameLiteral = t.literal() != null;
                    if (nameLiteral) words.add(t.literal());
                    continue;
                }
                if (nameLiteral && t.literal() != null) words.add(t.literal());
            }
            if (!any) throw new SyntaxError();
            if (haveName && nameLiteral) out.add(words);
        }

        private static String leadingUnquoted(String text) {
            int i = 0;
            while (i < text.length() && text.charAt(i) != '\'' && text.charAt(i) != '"') i++;
            return text.substring(0, i);
        }
    }
}
