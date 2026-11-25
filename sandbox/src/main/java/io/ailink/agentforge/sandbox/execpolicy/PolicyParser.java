package io.ailink.agentforge.sandbox.execpolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .rules} 文件解析（Codex execpolicy/src/parser.rs 的语言子集，Apache-2.0）。上游用完整的 Starlark 解释器；这里只认规则文件实际用到的那部分：
 * <ul>
 * <li>顶层语句：函数调用、{@code NAME = 表达式}（之后可按名引用），以换行或 {@code ;} 分隔，{@code #} 注释；</li>
 * <li>表达式：字符串（{@code "…" '…'} 及三引号、{@code r"…"}，常见转义）、列表、名字、{@code None/True/False}、整数，{@code +} 拼接；</li>
 * <li>内建：{@code prefix_rule(pattern, decision="allow", match=None, not_match=None, justification=None)}、
 *     {@code network_rule(host, protocol, decision, justification=None)}、{@code host_executable(name, paths)}；参数可按位置或按名。</li>
 * </ul>
 * 每个 prefix_rule 的 {@code match} / {@code not_match} 例子在该文件读完时自检（只对这条规则；例子串按 shlex 切词），不过 ⇒ {@link ExecPolicyException}。
 * 非线程安全；一个解析器读若干文件后 {@link #build}。
 */
public final class PolicyParser {
    private final Map<String, List<PrefixRule>> rules = new LinkedHashMap<>();
    private final List<NetworkRule> networkRules = new ArrayList<>();
    private final Map<String, List<String>> hostExecutables = new LinkedHashMap<>();

    private record Pending(List<PrefixRule> rules, List<List<String>> matches, List<List<String>> notMatches, String where) {
    }

    /** 读一个文件（{@code identifier} 只用于报错定位）。出错 ⇒ {@link ExecPolicyException}，本文件已加进来的规则不回滚。 */
    public PolicyParser parse(String identifier, String contents) {
        List<Pending> pending = new ArrayList<>();
        new Interp(identifier, contents, pending).run();
        for (Pending v : pending) validate(v);
        return this;
    }

    public Policy build() {
        return new Policy(rules, networkRules, hostExecutables);
    }

    private void validate(Pending v) {
        Map<String, List<PrefixRule>> m = new LinkedHashMap<>();
        for (PrefixRule r : v.rules()) m.computeIfAbsent(r.first(), k -> new ArrayList<>()).add(r);
        Policy p = new Policy(m, List.of(), hostExecutables);
        for (List<String> ex : v.notMatches()) {
            if (!p.matchesForCommand(ex, null, true).isEmpty())
                throw new ExecPolicyException(v.where() + ": not_match 例子命中了规则：" + Shlex.join(ex));
        }
        List<String> unmatched = new ArrayList<>();
        for (List<String> ex : v.matches()) if (p.matchesForCommand(ex, null, true).isEmpty()) unmatched.add(Shlex.join(ex));
        if (!unmatched.isEmpty()) throw new ExecPolicyException(v.where() + ": match 例子没有命中规则：" + String.join("；", unmatched));
    }

    // ══ 解释器 ═══════════════════════════════════════════════════════════

    private final class Interp {
        private final String file;
        private final String src;
        private final List<Pending> pending;
        private final Map<String, Object> globals = new HashMap<>();
        private int i;

        Interp(String file, String src, List<Pending> pending) {
            this.file = file;
            this.src = src;
            this.pending = pending;
        }

        private ExecPolicyException err(int at, String msg) {
            int line = 1, col = 1;
            for (int k = 0; k < at && k < src.length(); k++) {
                if (src.charAt(k) == '\n') {
                    line++;
                    col = 1;
                } else col++;
            }
            return new ExecPolicyException(file + ":" + line + ":" + col + ": " + msg);
        }

        private String where(int at) {
            return err(at, "").getMessage().replaceAll(": $", "");
        }

        /** 跳过空白与注释；{@code newlines} = 换行也算空白（括号内）。 */
        private void ws(boolean newlines) {
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || (newlines && c == '\n')) i++;
                else if (c == '\\' && i + 1 < src.length() && src.charAt(i + 1) == '\n') i += 2;
                else if (c == '#') {
                    while (i < src.length() && src.charAt(i) != '\n') i++;
                } else break;
            }
        }

        void run() {
            while (true) {
                ws(true);
                while (i < src.length() && src.charAt(i) == ';') {
                    i++;
                    ws(true);
                }
                if (i >= src.length()) return;
                int start = i;
                String name = ident();
                if (name == null) throw err(i, "这里应该是一条语句（函数调用或赋值）");
                ws(false);
                if (i < src.length() && src.charAt(i) == '=' && !src.startsWith("==", i)) {
                    i++;
                    globals.put(name, expr());
                } else if (i < src.length() && src.charAt(i) == '(') {
                    call(name, start);
                } else {
                    throw err(i, "语句只能是函数调用或赋值：" + name);
                }
                ws(false);
                if (i < src.length() && src.charAt(i) != '\n' && src.charAt(i) != ';') throw err(i, "一行只能写一条语句");
            }
        }

        private String ident() {
            int s = i;
            if (i < src.length() && (Character.isLetter(src.charAt(i)) || src.charAt(i) == '_')) {
                i++;
                while (i < src.length() && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                return src.substring(s, i);
            }
            return null;
        }

        private Object expr() {
            ws(true);
            Object v = atom();
            while (true) {
                int save = i;
                ws(true);
                if (i < src.length() && src.charAt(i) == '+') {
                    i++;
                    Object r = atom();
                    v = plus(v, r, save);
                } else {
                    i = save;
                    return v;
                }
            }
        }

        @SuppressWarnings("unchecked")
        private Object plus(Object a, Object b, int at) {
            if (a instanceof String sa && b instanceof String sb) return sa + sb;
            if (a instanceof List<?> la && b instanceof List<?> lb) {
                List<Object> out = new ArrayList<>((List<Object>) la);
                out.addAll(lb);
                return out;
            }
            throw err(at, "+ 两边只能同是字符串或同是列表");
        }

        private Object atom() {
            ws(true);
            if (i >= src.length()) throw err(i, "表达式写到一半文件就完了");
            char c = src.charAt(i);
            if (c == '"' || c == '\'') return string(false);
            if ((c == 'r' || c == 'R') && i + 1 < src.length() && (src.charAt(i + 1) == '"' || src.charAt(i + 1) == '\'')) {
                i++;
                return string(true);
            }
            if (c == '[') return list();
            if (Character.isDigit(c) || (c == '-' && i + 1 < src.length() && Character.isDigit(src.charAt(i + 1)))) {
                int s = i++;
                while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
                return Long.parseLong(src.substring(s, i));
            }
            int start = i;
            String name = ident();
            if (name == null) throw err(i, "认不出的表达式");
            switch (name) {
                case "None":
                    return null;
                case "True":
                    return Boolean.TRUE;
                case "False":
                    return Boolean.FALSE;
                default:
                    break;
            }
            ws(false);
            if (i < src.length() && src.charAt(i) == '(') return call(name, start);
            if (!globals.containsKey(name)) throw err(start, "未定义的名字：" + name);
            return globals.get(name);
        }

        private List<Object> list() {
            i++; // [
            List<Object> out = new ArrayList<>();
            while (true) {
                ws(true);
                if (i >= src.length()) throw err(i, "列表没有闭合");
                if (src.charAt(i) == ']') {
                    i++;
                    return out;
                }
                out.add(expr());
                ws(true);
                if (i < src.length() && src.charAt(i) == ',') i++;
                else if (i < src.length() && src.charAt(i) == ']') {
                    i++;
                    return out;
                } else throw err(i, "列表元素之间要用逗号分开");
            }
        }

        private String string(boolean raw) {
            char q = src.charAt(i);
            boolean triple = src.startsWith(String.valueOf(q).repeat(3), i);
            int start = i;
            i += triple ? 3 : 1;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= src.length()) throw err(start, "字符串没有闭合");
                char c = src.charAt(i);
                if (triple ? src.startsWith(String.valueOf(q).repeat(3), i) : c == q) {
                    i += triple ? 3 : 1;
                    return sb.toString();
                }
                if (c == '\n' && !triple) throw err(i, "字符串里不能直接换行（用三引号或 \\n）");
                if (c == '\\' && i + 1 < src.length()) {
                    char e = src.charAt(i + 1);
                    if (raw) {
                        sb.append(c).append(e);
                        i += 2;
                        continue;
                    }
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case '0' -> sb.append('\0');
                        case '\\', '\'', '"' -> sb.append(e);
                        case '\n' -> { }
                        case 'x' -> {
                            if (i + 3 >= src.length()) throw err(i, "\\x 后面要两位十六进制");
                            sb.append((char) Integer.parseInt(src.substring(i + 2, i + 4), 16));
                            i += 2;
                        }
                        case 'u' -> {
                            if (i + 5 >= src.length()) throw err(i, "\\u 后面要四位十六进制");
                            sb.append((char) Integer.parseInt(src.substring(i + 2, i + 6), 16));
                            i += 4;
                        }
                        default -> sb.append(c).append(e);
                    }
                    i += 2;
                    continue;
                }
                sb.append(c);
                i++;
            }
        }

        private Object call(String name, int start) {
            i++; // (
            List<Object> positional = new ArrayList<>();
            Map<String, Object> named = new LinkedHashMap<>();
            while (true) {
                ws(true);
                if (i >= src.length()) throw err(start, "调用没有闭合：" + name);
                if (src.charAt(i) == ')') {
                    i++;
                    break;
                }
                int save = i;
                String key = ident();
                ws(true);
                if (key != null && i < src.length() && src.charAt(i) == '=' && !src.startsWith("==", i)) {
                    i++;
                    if (named.containsKey(key)) throw err(save, "参数重复：" + key);
                    named.put(key, expr());
                } else {
                    i = save;
                    if (!named.isEmpty()) throw err(save, "按位置的参数不能写在按名参数后面");
                    positional.add(expr());
                }
                ws(true);
                if (i < src.length() && src.charAt(i) == ',') i++;
                else if (i < src.length() && src.charAt(i) == ')') {
                    i++;
                    break;
                } else throw err(i, "参数之间要用逗号分开");
            }
            try {
                return switch (name) {
                    case "prefix_rule" -> prefixRule(bind(name, positional, named, List.of("pattern", "decision", "match", "not_match", "justification")),
                            start);
                    case "network_rule" -> networkRule(bind(name, positional, named, List.of("host", "protocol", "decision", "justification")));
                    case "host_executable" -> hostExecutable(bind(name, positional, named, List.of("name", "paths")));
                    default -> throw new ExecPolicyException("未知函数：" + name);
                };
            } catch (ExecPolicyException e) {
                if (e.getMessage().startsWith(file + ":")) throw e;
                throw err(start, e.getMessage());
            }
        }

        private Map<String, Object> bind(String fn, List<Object> positional, Map<String, Object> named, List<String> params) {
            if (positional.size() > params.size()) throw new ExecPolicyException(fn + " 的参数太多");
            Map<String, Object> out = new HashMap<>();
            for (int k = 0; k < positional.size(); k++) out.put(params.get(k), positional.get(k));
            for (var e : named.entrySet()) {
                if (!params.contains(e.getKey())) throw new ExecPolicyException(fn + " 没有参数 " + e.getKey());
                if (out.containsKey(e.getKey())) throw new ExecPolicyException(fn + " 的参数 " + e.getKey() + " 给了两次");
                out.put(e.getKey(), e.getValue());
            }
            return out;
        }

        private String optString(Map<String, Object> a, String k) {
            Object v = a.get(k);
            if (v == null) return null;
            if (v instanceof String s) return s;
            throw new ExecPolicyException(k + " 必须是字符串");
        }

        private String justification(Map<String, Object> a) {
            String j = optString(a, "justification");
            if (j != null && j.isBlank()) throw new ExecPolicyException("justification cannot be empty");
            return j;
        }

        private Object prefixRule(Map<String, Object> a, int start) {
            String dec = optString(a, "decision");
            Decision decision = dec == null ? Decision.ALLOW : Decision.parse(dec);
            String just = justification(a);
            if (!(a.get("pattern") instanceof List<?> pat)) throw new ExecPolicyException("pattern 必须是列表");
            if (pat.isEmpty()) throw new ExecPolicyException("pattern cannot be empty");
            List<PatternToken> tokens = new ArrayList<>();
            for (Object o : pat) tokens.add(patternToken(o));
            List<List<String>> matches = examples(a.get("match"));
            List<List<String>> notMatches = examples(a.get("not_match"));
            List<PatternToken> rest = tokens.subList(1, tokens.size());
            List<PrefixRule> made = new ArrayList<>();
            for (String head : tokens.getFirst().alternatives()) {
                PrefixRule r = new PrefixRule(head, rest, decision, just);
                made.add(r);
                rules.computeIfAbsent(head, k -> new ArrayList<>()).add(r);
            }
            pending.add(new Pending(made, matches, notMatches, where(start)));
            return null;
        }

        private PatternToken patternToken(Object o) {
            if (o instanceof String s) return PatternToken.single(s);
            if (o instanceof List<?> l) {
                List<String> alts = new ArrayList<>();
                for (Object x : l) {
                    if (!(x instanceof String s)) throw new ExecPolicyException("pattern alternative must be a string");
                    alts.add(s);
                }
                if (alts.isEmpty()) throw new ExecPolicyException("pattern alternatives cannot be empty");
                return new PatternToken(alts);
            }
            throw new ExecPolicyException("pattern element must be a string or list of strings");
        }

        private List<List<String>> examples(Object v) {
            if (v == null) return List.of();
            if (!(v instanceof List<?> l)) throw new ExecPolicyException("match / not_match 必须是列表");
            List<List<String>> out = new ArrayList<>();
            for (Object o : l) {
                if (o instanceof String s) {
                    List<String> t = Shlex.split(s);
                    if (t == null) throw new ExecPolicyException("example string has invalid shell syntax");
                    if (t.isEmpty()) throw new ExecPolicyException("example cannot be an empty string");
                    out.add(t);
                } else if (o instanceof List<?> ts) {
                    List<String> t = new ArrayList<>();
                    for (Object x : ts) {
                        if (!(x instanceof String s)) throw new ExecPolicyException("example tokens must be strings");
                        t.add(s);
                    }
                    if (t.isEmpty()) throw new ExecPolicyException("example cannot be an empty list");
                    out.add(t);
                } else throw new ExecPolicyException("example must be a string or list of strings");
            }
            return out;
        }

        private Object networkRule(Map<String, Object> a) {
            String host = optString(a, "host"), protocol = optString(a, "protocol"), dec = optString(a, "decision");
            if (host == null || protocol == null || dec == null) throw new ExecPolicyException("network_rule 要 host、protocol、decision 三个参数");
            Decision decision = dec.equals("deny") ? Decision.FORBIDDEN : Decision.parse(dec);
            networkRules.add(new NetworkRule(NetworkRule.normalizeHost(host), NetworkRule.normalizeProtocol(protocol), decision, justification(a)));
            return null;
        }

        private Object hostExecutable(Map<String, Object> a) {
            String name = optString(a, "name");
            if (name == null || name.isEmpty()) throw new ExecPolicyException("host_executable name cannot be empty");
            if (name.contains("/") || name.contains("\\")) throw new ExecPolicyException("host_executable name must be a bare executable name (got " + name + ")");
            if (!(a.get("paths") instanceof List<?> ps)) throw new ExecPolicyException("host_executable paths 必须是列表");
            List<String> paths = new ArrayList<>();
            for (Object o : ps) {
                if (!(o instanceof String p)) throw new ExecPolicyException("host_executable paths must be strings");
                if (!p.startsWith("/")) throw new ExecPolicyException("host_executable paths must be absolute (got " + p + ")");
                if (!p.substring(p.lastIndexOf('/') + 1).equals(name))
                    throw new ExecPolicyException("host_executable path `" + p + "` must have basename `" + name + "`");
                if (!paths.contains(p)) paths.add(p);
            }
            hostExecutables.put(name, paths);
            return null;
        }
    }
}
