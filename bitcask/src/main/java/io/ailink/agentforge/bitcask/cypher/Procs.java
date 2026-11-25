package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.Clj.kw;

import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import io.ailink.agentforge.bitcask.value.Dur;
import io.ailink.agentforge.bitcask.value.Point;

/**
 * 用户过程与函数的注册表（clj {@code cypher.procs}；进程级、按小写名字）。语义检查读签名、执行器调函数。内置的 db.labels /
 * db.relationshipTypes / db.propertyKeys 不在这里（执行器直接实现）。
 * <p>形态沿用 clj（Kw 键的 map）：过程 {@code {:name :inputs :outputs :fn :mode}}，字段 {@code {:name :type :nullable? [:default :optional?]}}；
 * 函数 {@code {:name :arity #{n …} :fn}}。:fn 分别是 {@link Procedure} / {@link UserFunction}，:mode 是 :read / :write。
 */
final class Procs {
    static final Kw NAME = kw("name"), INPUTS = kw("inputs"), OUTPUTS = kw("outputs"), FN = kw("fn"), MODE = kw("mode"),
            TYPE = kw("type"), NULLABLE = kw("nullable?"), DEFAULT = kw("default"), OPTIONAL = kw("optional?"), ARITY = kw("arity"),
            READ = kw("read"), WRITE = kw("write");
    private static final Set<String> TYPES = Set.of("INTEGER", "FLOAT", "NUMBER", "STRING", "BOOLEAN", "LIST", "MAP", "NODE",
            "RELATIONSHIP", "PATH", "ANY");
    private static final Set<String> BUILTIN_NS = Set.of("db", "fts", "search", "date", "time", "localtime", "datetime", "localdatetime",
            "duration", "point");
    private static final Pattern NAME_RE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private static final Map<String, Map<Object, Object>> REGISTRY = new ConcurrentHashMap<>();
    private static final Map<String, Map<Object, Object>> FN_REGISTRY = new ConcurrentHashMap<>();

    private Procs() {
    }

    private static Map<Object, Object> parseType(String t) {
        String s = String.valueOf(t).trim().toUpperCase(Locale.ROOT);
        boolean nullable = s.endsWith("?");
        String base = nullable ? s.substring(0, s.length() - 1) : s;
        if (!TYPES.contains(base)) throw new IllegalArgumentException("unknown procedure type \"" + t + "\"");
        return Clj.map(TYPE, base, NULLABLE, nullable);
    }

    private static List<Object> parseFields(List<ProcParam> fs) {
        List<Object> out = new ArrayList<>();
        if (fs == null) return out;
        for (ProcParam p : fs) {
            Map<Object, Object> m = parseType(p.type());
            m.put(NAME, p.name());
            if (p.hasDefault()) {
                m.put(DEFAULT, p.defaultValue());
                m.put(OPTIONAL, true);
            }
            out.add(m);
        }
        return out;
    }

    private static void checkName(String nm) {
        if (nm == null || !NAME_RE.matcher(nm).matches())
            throw new IllegalArgumentException("names need a namespace, e.g. my.proc: \"" + nm + "\"");
        if (BUILTIN_NS.contains(nm.split("\\.")[0].toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("the namespace of " + nm + " is reserved for built-in functions");
    }

    static void register(String nm, List<ProcParam> inputs, List<ProcParam> outputs, Procedure fn, boolean write) {
        checkName(nm);
        if (fn == null) throw new IllegalArgumentException("fn");
        String k = nm.toLowerCase(Locale.ROOT);
        REGISTRY.put(k, Clj.map(NAME, k, INPUTS, parseFields(inputs), OUTPUTS, parseFields(outputs), FN, fn, MODE, write ? WRITE : READ));
    }

    static void registerFn(String nm, int minArity, int maxArity, UserFunction fn) {
        checkName(nm);
        if (fn == null) throw new IllegalArgumentException("fn");
        if (minArity < 0 || maxArity < minArity) throw new IllegalArgumentException("arity");
        Set<Object> ar = new LinkedHashSet<>();
        for (long i = minArity; i <= maxArity; i++) ar.add(i);
        String k = nm.toLowerCase(Locale.ROOT);
        FN_REGISTRY.put(k, Clj.map(NAME, k, ARITY, ar, FN, fn));
    }

    /** 用户函数 {:name :arity #{Long …} :fn}；没有 ⇒ null。名字须已小写。 */
    static Map<Object, Object> lookupFn(String nm) {
        return nm == null ? null : FN_REGISTRY.get(nm);
    }

    /** 用户过程；没有 ⇒ null。名字须已小写。 */
    static Map<Object, Object> lookup(String nm) {
        return nm == null ? null : REGISTRY.get(nm);
    }

    static void unregister(String nm) {
        String k = String.valueOf(nm).toLowerCase(Locale.ROOT);
        REGISTRY.remove(k);
        FN_REGISTRY.remove(k);
    }

    /** {procedures [名] functions [名]}（各自升序）。 */
    static Map<String, List<String>> registered() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("procedures", new ArrayList<>(new TreeMap<>(REGISTRY).keySet()));
        m.put("functions", new ArrayList<>(new TreeMap<>(FN_REGISTRY).keySet()));
        return m;
    }

    /** 值是否符合声明的类型（不含 null 的判断）。pred(:node|:rel|:path|:entity, v)。 */
    static boolean typeOk(Map<?, ?> spec, Object v, BiPredicate<Kw, Object> pred) {
        return switch ((String) spec.get(TYPE)) {
            case "INTEGER" -> Clj.isInteger(v);
            case "FLOAT", "NUMBER" -> v instanceof Number;
            case "STRING" -> v instanceof String;
            case "BOOLEAN" -> v instanceof Boolean;
            case "LIST" -> v instanceof List<?> && !pred.test(kw("path"), v);
            case "MAP" -> v instanceof Map<?, ?> && !pred.test(kw("entity"), v);
            case "NODE" -> pred.test(kw("node"), v);
            case "RELATIONSHIP" -> pred.test(kw("rel"), v);
            case "PATH" -> pred.test(kw("path"), v);
            default -> true;
        };
    }

    /** INTEGER 传给 FLOAT 参数 ⇒ 转成浮点。 */
    static Object coerce(Map<?, ?> spec, Object v) {
        return "FLOAT".equals(spec.get(TYPE)) && Clj.isInteger(v) ? (Object) ((Number) v).doubleValue() : v;
    }

    /** 过程 / 函数交出的值规范成 Cypher 的值：整数 ⇒ Long、浮点 ⇒ Double、map 的键 ⇒ 字符串、数组 / 集合 ⇒ 列表。 */
    static Object normOut(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Long || v instanceof Double) return v;
        if (Clj.isInteger(v)) return ((Number) v).longValue();
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof Kw k) return k.name;
        if (v instanceof Character c) return String.valueOf(c);
        if (v instanceof VNode || v instanceof VRel || v instanceof VPath || v instanceof Temporal || v instanceof Dur || v instanceof Point
                || v instanceof float[])
            return v;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(k instanceof Kw kk ? kk.name : String.valueOf(k), normOut(x)));
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            for (Object x : it) out.add(normOut(x));
            return out;
        }
        if (v instanceof Object[] a) {
            List<Object> out = new ArrayList<>();
            for (Object x : a) out.add(normOut(x));
            return out;
        }
        return v;
    }
}
