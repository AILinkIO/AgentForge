package io.ailink.agentforge.bitcask.cypher;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.bitcask.value.Temporals;

/**
 * openCypher TCK 运行器（移植自 clj-bitcask test/im/ttalk/bitcask/tck.clj；开发工具，缺省不跑）：
 * <pre>gradle :bitcask:test --tests '*TckTest' -Ptck[=features 目录] [-Ptck.filter=子串,子串]</pre>
 * 目录缺省 {@code ~/.cache/keelson-tck/openCypher-2024.3/tck/features}。每个场景一个动态测试、开一个新的临时库（跑完删除）；
 * 支持的步骤：an empty graph / any graph、having executed、parameters are、executing (control) query、result should be（in any order /
 * in order / ignoring element order for lists / empty）、&lt;Error&gt; should be raised at &lt;phase&gt;（严格比对错误类型与细节代码）、
 * no side effects / the side effects should be、there exists a procedure、the &lt;名字&gt; graph。副作用按执行前后的图状态比较。
 * 汇总写到 build/tck/summary.txt，全部场景写到 build/tck/report.txt。
 */
class TckTest {
    private static final long TIMEOUT_MS = 20000;
    private static final Object NAN = new Object() {
        @Override
        public String toString() {
            return "NaN";
        }
    };
    private static final List<String[]> REPORT = new ArrayList<>();
    private static final List<Object[]> LEAKED = new ArrayList<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tck-query");
        t.setDaemon(true);
        return t;
    });

    // ═══ Gherkin ═══

    record Step(String text, String doc, List<List<String>> table) {
        Step withText(String t) {
            return new Step(t, doc, table);
        }
    }

    record Scenario(String name, List<Step> steps, List<List<List<String>>> examples, boolean outline) {
    }

    /** 表格行 | a | b | ⇒ [a b]（\| 是字面的竖线，\\ ⇒ \，\n ⇒ 换行）。 */
    static List<String> splitRow(String line) {
        String s = line.trim();
        String inner = s.substring(1, Math.max(1, s.length() - 1));
        List<String> cells = new ArrayList<>();
        int st = 0;
        for (int i = 0; i < inner.length(); ) {
            char c = inner.charAt(i);
            if (c == '\\') i += 2;
            else if (c == '|') {
                cells.add(inner.substring(st, i));
                i++;
                st = i;
            } else i++;
        }
        cells.add(inner.substring(Math.min(st, inner.length())));
        List<String> out = new ArrayList<>();
        for (String cell : cells) {
            String c = cell.trim();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < c.length(); ) {
                char ch = c.charAt(i);
                if (ch == '\\' && i + 1 < c.length()) {
                    char e = c.charAt(i + 1);
                    switch (e) {
                        case '|' -> sb.append('|');
                        case '\\' -> sb.append('\\');
                        case 'n' -> sb.append('\n');
                        default -> sb.append(ch).append(e);
                    }
                    i += 2;
                } else {
                    sb.append(ch);
                    i++;
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static final Pattern STEP = Pattern.compile("^(Given|When|Then|And|But) ");

    static List<Scenario> parseFeature(java.nio.file.Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int n = lines.size();
        List<Scenario> out = new ArrayList<>();
        List<Step> bg = null;
        String mode = null;
        String name = null;
        List<Step> steps = null;
        List<List<List<String>>> examples = null;
        boolean outline = false;
        for (int i = 0; i < n; i++) {
            String line = lines.get(i), t = line.trim();
            if (t.isBlank() || t.startsWith("#") || t.startsWith("@") || t.startsWith("Feature:")) continue;
            if (t.startsWith("Background:")) {
                if (name != null) out.add(new Scenario(name, steps, examples, outline));
                name = null;
                bg = new ArrayList<>();
                mode = "background";
            } else if (t.matches("^Scenario( Outline| Template)?:.*")) {
                if (name != null && !"background".equals(mode)) out.add(new Scenario(name, steps, examples, outline));
                outline = t.matches("^Scenario (Outline|Template):.*");
                name = t.substring(t.indexOf(':') + 1).trim();
                steps = new ArrayList<>();
                examples = new ArrayList<>();
                mode = "steps";
            } else if (t.startsWith("Examples:")) {
                examples.add(new ArrayList<>());
                mode = "examples";
            } else if (STEP.matcher(t).find()) {
                Step st = new Step(STEP.matcher(t).replaceFirst(""), null, null);
                if ("background".equals(mode)) bg.add(st);
                else steps.add(st);
            } else if (t.startsWith("\"\"\"")) {
                int indent = line.length() - line.stripLeading().length();
                int end = i + 1;
                while (end < n && !lines.get(end).trim().startsWith("\"\"\"")) end++;
                List<String> body = new ArrayList<>();
                for (String l : lines.subList(i + 1, end)) body.add(l.length() >= indent ? l.substring(indent) : l.stripLeading());
                List<Step> target = "background".equals(mode) ? bg : steps;
                Step last = target.getLast();
                target.set(target.size() - 1, new Step(last.text, String.join("\n", body), last.table));
                i = end;
            } else if (t.startsWith("|")) {
                List<String> row = splitRow(t);
                if ("examples".equals(mode)) examples.getLast().add(row);
                else {
                    List<Step> target = "background".equals(mode) ? bg : steps;
                    Step last = target.getLast();
                    List<List<String>> tb = last.table == null ? new ArrayList<>() : new ArrayList<>(last.table);
                    tb.add(row);
                    target.set(target.size() - 1, new Step(last.text, last.doc, tb));
                }
            }
        }
        if (name != null) out.add(new Scenario(name, steps, examples, outline));
        List<Scenario> res = new ArrayList<>();
        for (Scenario sc : out) {
            List<Step> all = new ArrayList<>(bg == null ? List.of() : bg);
            all.addAll(sc.steps);
            res.add(new Scenario(sc.name, all, sc.examples, sc.outline));
        }
        return res;
    }

    /** Scenario Outline ⇒ 按 Examples 每一行展开（替换 &lt;名字&gt;）。 */
    static List<Scenario> expand(Scenario sc) {
        if (!sc.outline) return List.of(sc);
        List<Scenario> out = new ArrayList<>();
        for (int bi = 0; bi < sc.examples.size(); bi++) {
            List<List<String>> ex = sc.examples.get(bi);
            if (ex.isEmpty()) continue;
            List<String> header = ex.getFirst();
            for (int ri = 1; ri < ex.size(); ri++) {
                List<String> row = ex.get(ri);
                Map<String, String> m = new LinkedHashMap<>();
                for (int k = 0; k < header.size() && k < row.size(); k++) m.put(header.get(k), row.get(k));
                java.util.function.UnaryOperator<String> sub = s -> {
                    if (s == null) return null;
                    String acc = s;
                    for (var e : m.entrySet()) acc = acc.replace("<" + e.getKey() + ">", e.getValue());
                    return acc;
                };
                List<Step> steps = new ArrayList<>();
                for (Step st : sc.steps) {
                    List<List<String>> tb = null;
                    if (st.table != null) {
                        tb = new ArrayList<>();
                        for (List<String> r : st.table) tb.add(r.stream().map(sub).toList());
                    }
                    steps.add(new Step(sub.apply(st.text), sub.apply(st.doc), tb));
                }
                out.add(new Scenario(sc.name + " #" + (bi + 1) + "." + ri, steps, List.of(), false));
            }
        }
        return out;
    }

    // ═══ TCK 的值写法 ═══

    record TNode(Set<String> labels, Map<String, Object> props) {
    }

    record TRel(String type, Map<String, Object> props) {
    }

    record TPath(List<Object> nodes, List<Object> rels) {
    }

    static final class VParser {
        final String s;
        final int n;
        int pos;

        VParser(String s) {
            this.s = s;
            this.n = s.length();
        }

        RuntimeException bad() {
            return new IllegalArgumentException("bad TCK value: " + s + " at " + pos);
        }

        void ws() {
            while (pos < n && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Character peekc() {
            return pos < n ? s.charAt(pos) : null;
        }

        void expect(String tok) {
            ws();
            if (s.startsWith(tok, pos)) pos += tok.length();
            else throw bad();
        }

        boolean tryTok(String tok) {
            ws();
            if (s.startsWith(tok, pos)) {
                pos += tok.length();
                return true;
            }
            return false;
        }

        String ident() {
            ws();
            if (Objects.equals(peekc(), '`')) {
                int e = s.indexOf('`', pos + 1);
                String r = s.substring(pos + 1, e);
                pos = e + 1;
                return r;
            }
            int st = pos;
            while (pos < n && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            if (st == pos) throw bad();
            return s.substring(st, pos);
        }

        Object value() {
            ws();
            Character ch = peekc();
            if (ch == null) throw bad();
            if (ch == '\'') return stringLit();
            if (ch == '[') {
                int p0 = pos;
                pos++;
                ws();
                boolean rel = Objects.equals(peekc(), ':');
                pos = p0;
                return rel ? rel() : listLit();
            }
            if (ch == '{') return mapLit();
            if (ch == '(') return node();
            if (ch == '<') return path();
            if (tryTok("null")) return null;
            if (tryTok("true")) return true;
            if (tryTok("false")) return false;
            if (tryTok("NaN")) return NAN;
            if (tryTok("-Infinity")) return Double.NEGATIVE_INFINITY;
            if (tryTok("Infinity")) return Double.POSITIVE_INFINITY;
            if (tryTok("-Inf")) return Double.NEGATIVE_INFINITY;
            if (tryTok("Inf")) return Double.POSITIVE_INFINITY;
            return number();
        }

        private static final Pattern NUM = Pattern.compile("^-?(\\d+\\.\\d*|\\.\\d+|\\d+)([eE][+-]?\\d+)?");

        Object number() {
            Matcher m = NUM.matcher(s.substring(pos));
            if (!m.find()) throw bad();
            pos += m.group().length();
            if (m.group().contains(".") || m.group(2) != null) {
                double d = Double.parseDouble(m.group());
                return d == 0.0 ? 0.0 : d; // clj 的 = 里 -0.0 与 0.0 相等
            }
            return Long.parseLong(m.group());
        }

        String stringLit() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                Character ch = peekc();
                if (ch == null) throw bad();
                if (ch == '\'') {
                    pos++;
                    return sb.toString();
                }
                if (ch == '\\') {
                    char e = s.charAt(pos + 1);
                    pos += 2;
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(ch);
                    pos++;
                }
            }
        }

        List<Object> listLit() {
            expect("[");
            List<Object> acc = new ArrayList<>();
            if (tryTok("]")) return acc;
            acc.add(value());
            while (tryTok(",")) acc.add(value());
            expect("]");
            return acc;
        }

        Map<String, Object> mapLit() {
            expect("{");
            Map<String, Object> acc = new HashMap<>();
            if (tryTok("}")) return acc;
            do {
                String k = ident();
                expect(":");
                acc.put(k, value());
            } while (tryTok(","));
            expect("}");
            return acc;
        }

        Set<String> labels() {
            Set<String> acc = new HashSet<>();
            while (tryTok(":")) acc.add(ident());
            return acc;
        }

        TNode node() {
            expect("(");
            Set<String> ls = labels();
            ws();
            Map<String, Object> ps = Objects.equals(peekc(), '{') ? mapLit() : new HashMap<>();
            expect(")");
            return new TNode(ls, ps);
        }

        TRel rel() {
            expect("[");
            expect(":");
            String t = ident();
            ws();
            Map<String, Object> ps = Objects.equals(peekc(), '{') ? mapLit() : new HashMap<>();
            expect("]");
            return new TRel(t, ps);
        }

        TPath path() {
            expect("<");
            List<Object> nodes = new ArrayList<>(List.of(node()));
            List<Object> rels = new ArrayList<>();
            while (true) {
                if (tryTok(">")) return new TPath(nodes, rels);
                if (tryTok("<-")) {
                    TRel r = rel();
                    expect("-");
                    nodes.add(node());
                    rels.add(List.of("in", r));
                } else if (tryTok("-")) {
                    TRel r = rel();
                    expect("->");
                    nodes.add(node());
                    rels.add(List.of("out", r));
                } else throw bad();
            }
        }

        Object parseAll() {
            Object v = value();
            ws();
            if (pos < n) throw bad();
            return v;
        }
    }

    static Object parseValue(String s) {
        return new VParser(s).parseAll();
    }

    // ═══ 结果值 ⇒ 可比较的形态 ═══

    static Map<String, Object> canonMap(Map<?, ?> m) {
        Map<String, Object> out = new HashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), canon(v)));
        return out;
    }

    static Object canon(Object v) {
        if (v == null) return null;
        if (v instanceof Double d && d.isNaN()) return NAN;
        if (v instanceof Double d && d == 0.0) return 0.0; // clj 的 = 里 -0.0 与 0.0 相等
        if (v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Double) return v;
        if (v instanceof Number x) return x instanceof Integer || x instanceof Short || x instanceof Byte ? (Object) x.longValue() : (Object) x.doubleValue();
        if (Temporals.isTemporal(v)) return Temporals.toIso(v);
        if (v instanceof Node nd) return new TNode(new HashSet<>(nd.labels()), canonMap(nd.properties()));
        if (v instanceof Relationship r) return new TRel(String.valueOf(r.type()), canonMap(r.properties()));
        if (v instanceof Path p) {
            List<Object> nodes = new ArrayList<>();
            for (Node nd : p.nodes()) nodes.add(canon(nd));
            List<Object> rels = new ArrayList<>();
            for (int i = 0; i < p.relationships().size(); i++) {
                Relationship r = p.relationships().get(i);
                rels.add(List.of(r.src() == p.nodes().get(i).id() ? "out" : "in", canon(r)));
            }
            return new TPath(nodes, rels);
        }
        if (v instanceof Map<?, ?> m) return canonMap(m);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(canon(x));
            return out;
        }
        if (v instanceof float[] f) {
            List<Object> out = new ArrayList<>();
            for (float x : f) out.add((double) x);
            return out;
        }
        return String.valueOf(v);
    }

    /** 打印形态（只用于排序）。 */
    static String pr(Object x) {
        if (x instanceof Map<?, ?> m) return new TreeMap<>(canonMapStr(m)).toString();
        return String.valueOf(x);
    }

    private static Map<String, String> canonMapStr(Map<?, ?> m) {
        Map<String, String> out = new HashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), pr(v)));
        return out;
    }

    /** ignoring element order for lists：列表（含嵌套）按打印形态排序。 */
    static Object sortLists(Object x) {
        if (x instanceof TNode n) return new TNode(n.labels, castMap(sortLists(n.props)));
        if (x instanceof TRel r) return new TRel(r.type, castMap(sortLists(r.props)));
        if (x instanceof TPath) return x;
        if (x instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), sortLists(v)));
            return out;
        }
        if (x instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object y : l) out.add(sortLists(y));
            out.sort(Comparator.comparing(TckTest::pr));
            return out;
        }
        return x;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ═══ 执行 ═══

    private static java.nio.file.Path root;
    private static final Set<String> REGISTERED = new HashSet<>();

    private static boolean numEq(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return Objects.equals(a, b);
    }

    private static final Pattern PROC = Pattern.compile("procedure ([\\w.]+)\\((.*?)\\) :: \\((.*?)\\)");

    private static List<ProcParam> fields(String s) {
        List<ProcParam> out = new ArrayList<>();
        for (String f : String.valueOf(s).split(",")) {
            String[] ab = f.split("::");
            String a = ab[0].trim();
            if (!a.isEmpty()) out.add(ProcParam.of(a, ab.length > 1 ? ab[1].trim() : "ANY"));
        }
        return out;
    }

    /** there exists a procedure test.my.proc(in :: INTEGER?) :: (out :: STRING?): + 表格 ⇒ 注册一个按表格查的过程。 */
    private static void tckProcedure(String text, List<List<String>> table) {
        Matcher m = PROC.matcher(text);
        if (!m.find()) throw new IllegalArgumentException("bad procedure step: " + text);
        String nm = m.group(1);
        List<ProcParam> in = fields(m.group(2)), out = fields(m.group(3));
        List<String> header = table.getFirst();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> r : table.subList(1, table.size())) {
            if (r.size() != header.size() || r.equals(List.of(""))) continue;
            Map<String, Object> row = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                Object v = parseValue(r.get(i));
                row.put(header.get(i), v == NAN ? Double.NaN : v);
            }
            rows.add(row);
        }
        Cypher.registerProcedure(nm, in, out, (ctx, args) -> {
            List<Map<String, Object>> res = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                if (in.stream().allMatch(p -> numEq(args.get(p.name()), r.get(p.name())))) {
                    Map<String, Object> o = new LinkedHashMap<>();
                    for (ProcParam p : out) o.put(p.name(), r.get(p.name()));
                    res.add(o);
                }
            }
            return res;
        });
        REGISTERED.add(nm);
    }

    private record Snap(Set<Object> nodes, Set<Object> rels, Set<Object> labels, Set<Object> props) {
    }

    private static Snap snapshot(Bitcask db) {
        CypherResult nodes = Cypher.execute(db, "MATCH (n) RETURN id(n), labels(n), properties(n)");
        CypherResult rels = Cypher.execute(db, "MATCH ()-[r]->() RETURN id(r), properties(r)");
        Set<Object> ns = new HashSet<>(), rs = new HashSet<>(), ls = new HashSet<>(), ps = new HashSet<>();
        for (List<Object> r : nodes.rows()) {
            ns.add(r.get(0));
            ls.addAll((List<?>) r.get(1));
            ((Map<?, ?>) r.get(2)).forEach((k, v) -> ps.add(List.of("n", String.valueOf(r.get(0)), k, String.valueOf(canon(v)))));
        }
        for (List<Object> r : rels.rows()) {
            rs.add(r.get(0));
            ((Map<?, ?>) r.get(1)).forEach((k, v) -> ps.add(List.of("r", String.valueOf(r.get(0)), k, String.valueOf(canon(v)))));
        }
        return new Snap(ns, rs, ls, ps);
    }

    private static Map<String, Long> sideEffects(Snap a, Snap b) {
        Map<String, Long> m = new LinkedHashMap<>();
        BiCount d = (k, x, y) -> {
            m.put("+" + k, y.stream().filter(e -> !x.contains(e)).count());
            m.put("-" + k, x.stream().filter(e -> !y.contains(e)).count());
        };
        d.put("nodes", a.nodes, b.nodes);
        d.put("relationships", a.rels, b.rels);
        d.put("labels", a.labels, b.labels);
        d.put("properties", a.props, b.props);
        return m;
    }

    private interface BiCount {
        void put(String k, Set<Object> a, Set<Object> b);
    }

    private record Err(String stage, String msg, String type, String detail) {
    }

    private record Res(CypherResult ok, Err err, boolean timeout) {
    }

    private static Err errInfo(Throwable e) {
        if (e instanceof CypherException c) return new Err(c.stage(), c.cypherMessage(), c.type(), c.detail());
        return new Err("crash", e.getClass().getName() + ": " + e.getMessage(), null, null);
    }

    private static Res runQuery(Bitcask db, String text, Map<String, Object> params) {
        Future<Res> f = POOL.submit(() -> {
            try {
                return new Res(Cypher.execute(db, text, params), null, false);
            } catch (Throwable e) {
                return new Res(null, errInfo(e), false);
            }
        });
        try {
            return f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return new Res(null, null, true);
        } catch (Exception e) {
            return new Res(null, errInfo(e), false);
        }
    }

    /** 场景状态。 */
    private static final class St {
        Map<String, Object> params = new HashMap<>();
        Res result;
        Map<String, Long> effects;
        String status; // null = 还在跑 / pass；fail / timeout
        String reason;
        String phaseMismatch;

        boolean done() {
            return status != null;
        }

        void fail(String msg) {
            status = "fail";
            reason = msg;
        }
    }

    private static Map<String, Object> paramTable(List<List<String>> table) {
        Map<String, Object> m = new HashMap<>();
        for (List<String> r : table) {
            Object x = parseValue(r.get(1));
            m.put(r.get(0), x == NAN ? Double.NaN : x);
        }
        return m;
    }

    private static <T> Map<T, Integer> freq(List<T> xs) {
        Map<T, Integer> m = new HashMap<>();
        for (T x : xs) m.merge(x, 1, Integer::sum);
        return m;
    }

    private static void expectRows(St st, Step s) {
        Res r = st.result;
        if (r == null) {
            st.fail("no query executed");
            return;
        }
        if (r.timeout) {
            st.status = "timeout";
            return;
        }
        if (r.err != null) {
            st.fail("unexpected error [" + r.err.stage + "] " + r.err.msg);
            return;
        }
        boolean ordered = s.text.contains("in order"), ign = s.text.contains("ignoring element order for lists");
        List<List<String>> table = s.table != null ? s.table : List.of(List.of());
        List<String> header = table.getFirst();
        List<List<String>> rows = table.subList(1, table.size());
        List<String> expCols = s.text.contains("empty") ? null : header;
        List<String> actCols = r.ok.columns();
        if (expCols != null && !new HashSet<>(expCols).equals(new HashSet<>(actCols))) {
            st.fail("columns differ: expected " + expCols + " got " + actCols);
            return;
        }
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < actCols.size(); i++) idx.put(actCols.get(i), i);
        java.util.function.UnaryOperator<Object> prep = x -> ign ? sortLists(x) : x;
        List<List<Object>> exp = new ArrayList<>();
        for (List<String> row : rows) {
            List<Object> e = new ArrayList<>();
            for (String c : row) e.add(prep.apply(parseValue(c)));
            exp.add(e);
        }
        List<List<Object>> act = new ArrayList<>();
        if (expCols != null) {
            for (List<Object> row : r.ok.rows()) {
                List<Object> a = new ArrayList<>();
                for (String c : expCols) a.add(prep.apply(canon(row.get(idx.get(c)))));
                act.add(a);
            }
        } else act.addAll(r.ok.rows());
        boolean same = ordered ? exp.equals(act) : freq(exp).equals(freq(act));
        if (!same) st.fail("rows differ:\n      expected " + exp + "\n      got      " + act);
    }

    private static final Pattern ERR = Pattern.compile("an? (\\w+) should be raised at (compile time|runtime|any time)(?:: *(.*))?");

    private static void expectError(St st, String text) {
        Matcher m = ERR.matcher(text);
        if (!m.find()) {
            st.fail("bad error step: " + text);
            return;
        }
        String kind = m.group(1), phase = m.group(2), detail = m.group(3);
        Res r = st.result;
        if (r == null) {
            st.fail("no query executed");
            return;
        }
        if (r.timeout) {
            st.status = "timeout";
            return;
        }
        if (r.ok != null) {
            st.fail("expected " + kind + " (" + detail + ") at " + phase + ", but the query succeeded");
            return;
        }
        Err e = r.err;
        if ("crash".equals(e.stage)) {
            st.fail("expected " + kind + " (" + detail + "), got a raw exception: " + e.msg);
            return;
        }
        if (!kind.equals(e.type) || (detail != null && !"*".equals(detail) && !detail.equals(e.detail))) {
            st.fail("expected " + kind + "/" + detail + ", got " + e.type + "/" + e.detail + " [" + e.stage + "] " + e.msg);
            return;
        }
        boolean compile = Set.of("parse", "semantic", "plan").contains(e.stage);
        boolean okPhase = switch (phase) {
            case "compile time" -> compile;
            case "runtime" -> !compile;
            default -> true;
        };
        if (!okPhase) st.phaseMismatch = kind + " expected at " + phase + ", raised at " + e.stage + ": " + e.msg;
    }

    private static final Pattern GRAPH = Pattern.compile("^the ([\\w-]+) graph");

    private static void step(Bitcask db, St st, Step s) throws IOException {
        String text = s.text;
        if (text.matches("^(an empty graph|any graph).*")) return;
        if (text.startsWith("there exists a procedure")) {
            tckProcedure(text, s.table);
            return;
        }
        Matcher gm = GRAPH.matcher(text);
        if (gm.find()) {
            String g = gm.group(1);
            java.nio.file.Path f = root.resolve("..").resolve("graphs").resolve(g).resolve(g + ".cypher");
            Res r = runQuery(db, Files.readString(f), Map.of());
            if (r.timeout) st.status = "timeout";
            else if (r.err != null) st.fail("graph setup failed [" + r.err.stage + "] " + r.err.msg);
            return;
        }
        if (text.startsWith("having executed")) {
            Res r = runQuery(db, s.doc, Map.of());
            if (r.timeout) st.status = "timeout";
            else if (r.err != null) st.fail("setup failed [" + r.err.stage + "] " + r.err.msg);
            return;
        }
        if (text.startsWith("parameters are")) {
            st.params.putAll(paramTable(s.table));
            return;
        }
        if (text.matches("^executing (control )?query.*")) {
            boolean control = text.contains("control");
            Snap before = control ? null : snapshot(db);
            Res r = runQuery(db, s.doc, st.params);
            st.result = r;
            if (r.timeout) {
                st.status = "timeout";
                return;
            }
            st.effects = before != null ? sideEffects(before, snapshot(db)) : null;
            return;
        }
        if (text.startsWith("the result should be")) {
            expectRows(st, s);
            return;
        }
        if (text.contains("should be raised at")) {
            expectError(st, text);
            return;
        }
        if (text.startsWith("no side effects")) {
            Map<String, Long> e = new TreeMap<>();
            if (st.effects != null) st.effects.forEach((k, v) -> {
                if (v != 0) e.put(k, v);
            });
            if (!e.isEmpty()) st.fail("side effects: expected none, got " + e);
            return;
        }
        if (text.startsWith("the side effects should be")) {
            Map<String, Long> exp = new TreeMap<>(), act = new TreeMap<>();
            for (List<String> r : s.table) exp.put(r.get(0), Long.parseLong(r.get(1)));
            if (st.effects != null) st.effects.forEach((k, v) -> {
                if (v != 0) act.put(k, v);
            });
            if (!exp.equals(act)) st.fail("side effects: expected " + exp + " got " + act);
            return;
        }
        st.fail("unsupported step: " + text);
    }

    private static void rmRf(java.nio.file.Path d) {
        try (Stream<java.nio.file.Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    /** ⇒ [状态, 原因]：pass / pass-phase / fail / timeout。 */
    static String[] runScenario(Scenario sc) throws IOException {
        java.nio.file.Path d = Files.createTempDirectory("kjtck");
        Bitcask db = Bitcask.open(d);
        St st = new St();
        try {
            for (Step s : sc.steps) {
                step(db, st, s);
                if (st.done()) break;
            }
        } catch (Throwable e) {
            st.fail("runner: " + e);
        } finally {
            if ("timeout".equals(st.status)) LEAKED.add(new Object[]{db, d});
            else {
                db.close();
                rmRf(d);
            }
            for (String p : REGISTERED) Cypher.unregisterProcedure(p);
            REGISTERED.clear();
        }
        if (st.status != null) return new String[]{st.status, st.reason};
        if (st.phaseMismatch != null) return new String[]{"pass-phase", st.phaseMismatch};
        return new String[]{"pass", null};
    }

    // ═══ 入口 ═══

    @TestFactory
    Stream<DynamicTest> tck() throws IOException {
        String dir = System.getProperty("agentforge.tck.dir");
        Assumptions.assumeTrue(dir != null, "SKIP: TCK 缺省不跑（-Ptck[=features 目录]）");
        Assumptions.assumeTrue(BitcaskRuntime.isAvailable(), "SKIP: libbitcask 不在");
        root = java.nio.file.Path.of(dir.isBlank() ? System.getProperty("user.home") + "/.cache/keelson-tck/openCypher-2024.3/tck/features" : dir);
        Assumptions.assumeTrue(Files.isDirectory(root), "SKIP: TCK 目录不在：" + root);
        String filter = System.getProperty("agentforge.tck.filter");
        List<String> filters = filter == null || filter.isBlank() ? List.of() : Arrays.asList(filter.split(","));
        List<java.nio.file.Path> files;
        try (Stream<java.nio.file.Path> s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".feature")).sorted()
                    .filter(p -> filters.isEmpty() || filters.stream().anyMatch(f -> p.toString().contains(f))).toList();
        }
        List<DynamicTest> tests = new ArrayList<>();
        for (java.nio.file.Path f : files) {
            String rel = root.relativize(f).toString();
            for (Scenario sc0 : parseFeature(f)) {
                for (Scenario sc : expand(sc0)) {
                    tests.add(DynamicTest.dynamicTest(rel + " — " + sc.name, () -> {
                        String[] r = runScenario(sc);
                        synchronized (REPORT) {
                            REPORT.add(new String[]{r[0], rel, sc.name, r[1]});
                        }
                        if (r[0].equals("fail") || r[0].equals("timeout")) fail(r[0] + ": " + r[1]);
                    }));
                }
            }
        }
        return tests.stream();
    }

    private static String reasonKey(String r) {
        String s = (r == null ? "" : r).split("\n")[0];
        s = s.replaceAll("`[^`]*`", "`…`").replaceAll("'[^']*'", "'…'").replaceAll("\"[^\"]*\"", "\"…\"").replaceAll("\\d+", "N");
        if (s.startsWith("rows differ")) s = "rows differ";
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    @AfterAll
    static void summary() throws IOException {
        if (REPORT.isEmpty()) return;
        java.nio.file.Path out = java.nio.file.Path.of("build", "tck");
        Files.createDirectories(out);
        StringBuilder rep = new StringBuilder();
        Map<String, Integer> by = new TreeMap<>();
        Map<String, List<String[]>> groups = new HashMap<>();
        for (String[] r : REPORT) {
            rep.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]);
            if (r[3] != null) rep.append("\n    ").append(r[3]);
            rep.append('\n');
            by.merge(r[0], 1, Integer::sum);
            if (r[0].equals("fail") || r[0].equals("timeout"))
                groups.computeIfAbsent(reasonKey(r[3] != null ? r[3] : r[0]), k -> new ArrayList<>()).add(r);
        }
        Files.writeString(out.resolve("report.txt"), rep);
        StringBuilder sum = new StringBuilder("TCK 场景 " + REPORT.size() + "：" + by + "\n\n失败按原因归类（数量、原因、一个例子）：\n");
        groups.entrySet().stream().sorted((a, b) -> b.getValue().size() - a.getValue().size()).forEach(e -> sum
                .append(String.format("%4d  %s%n        e.g. %s — %s%n", e.getValue().size(), e.getKey(), e.getValue().getFirst()[1],
                        e.getValue().getFirst()[2])));
        Files.writeString(out.resolve("summary.txt"), sum);
        System.out.println(sum);
        for (Object[] l : LEAKED) rmRf((java.nio.file.Path) l[1]);
    }
}
