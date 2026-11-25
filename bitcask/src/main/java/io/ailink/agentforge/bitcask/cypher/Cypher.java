package io.ailink.agentforge.bitcask.cypher;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.value.Dur;
import io.ailink.agentforge.bitcask.value.Point;

/**
 * 图查询语言：openCypher 2024.3（含 keelson / bitcask 的增补：UNION、推导、模式谓词、量词、reduce、映射投影、数学 / 统计函数、
 * 时间类型、point、用户过程与函数、MERGE、路径搜索前缀 ANY / ALL / SHORTEST k / SHORTEST k GROUPS 与量化路径、NULLS FIRST / LAST）
 * + 两个检索运算符（{@code <=>} 向量距离、{@code @@} 全文匹配）。与图层 API（{@link Graph}）是同一套图 CRUD 的两个入口：
 * 同一个数据模型、同一个 id 空间、同一份标签索引——与 clj-bitcask / Erlang bitcask 写的库互相读得懂。
 * <pre>{@code
 * CypherResult r = Cypher.execute(db, "MATCH (a:Person {name: $n})-[:KNOWS]->(b) RETURN b.name AS friend", Map.of("n", "ann"));
 * // r.columns() = [friend]，r.rows() = [[bob]]
 * Cypher.Prepared p = Cypher.prepare("MATCH (n:Person) WHERE n.age > $min RETURN n ORDER BY n.age DESC LIMIT 10");
 * Cypher.execute(db, p, Map.of("min", 18));
 * }</pre>
 * <ul>
 * <li>一条写语句是一个原子批：读过的顶点 / 邻接表 / 标签索引项都登记版本，提交时带条件；别的写者插进来 ⇒ 整条语句重跑（缺省 8 次）。
 *     语句出错 ⇒ 什么都不写。</li>
 * <li>结果值见 {@link CypherResult}；报错 {@link CypherException}（码 bitcask/cypher-error，带 stage / openCypher 类型与细节代码 /
 *     行列）。存储层的错误照常以 BitcaskException 抛。</li>
 * <li>文本语句经一个 256 条的 LRU 缓存：重复的文本只解析 / 规划一次（{@link #prepare} 的结果线程安全、与库无关）。</li>
 * </ul>
 */
public final class Cypher {
    private Cypher() {
    }

    /** 执行选项：retries（写语句冲突时整条重跑的次数，&lt; 0 = 不限）、maxWriteVertices（一条写语句最多写多少个顶点，null = 不限）。 */
    public record Options(int retries, Long maxWriteVertices) {
        public static final Options DEFAULT = new Options(8, null);
    }

    /** 解析 + 语义检查 + 规划过的语句（线程安全、与库无关，可反复执行）。 */
    public static final class Prepared {
        private final String text;
        final Object analysis;
        final Object plan;

        Prepared(String text, Object analysis, Object plan) {
            this.text = text;
            this.analysis = analysis;
            this.plan = plan;
        }

        public String text() {
            return text;
        }

        /** 是否含写子句。 */
        public boolean writes() {
            return Clj.truthy(Clj.get(analysis, Clj.kw("writes?")));
        }

        @Override
        public String toString() {
            return "Cypher.Prepared[" + text + "]";
        }
    }

    // ═══ 错误翻译 ═══

    static <T> T translating(Supplier<T> body) {
        try {
            return body.get();
        } catch (CypherError e) {
            throw CypherException.of(e);
        }
    }

    // ═══ 准备 ═══

    private static final int CACHE_SIZE = 256;
    private static final Map<String, Prepared> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Prepared> e) {
            return size() > CACHE_SIZE;
        }
    });

    /** 只做解析与语义检查（不规划）：语句不合法 ⇒ {@link CypherException}。 */
    public static void validate(String text) {
        translating(() -> Sema.analyze(Parser.parse(String.valueOf(text))));
    }

    /** 解析 + 语义检查 + 规划一次（经 256 条的 LRU 缓存）。 */
    public static Prepared prepare(String text) {
        String s = String.valueOf(text);
        Prepared p = CACHE.get(s);
        if (p != null) return p;
        p = prepareUncached(s);
        CACHE.put(s, p);
        return p;
    }

    private static Prepared prepareUncached(String text) {
        return translating(() -> {
            Object a = Sema.analyze(Parser.parse(text));
            Object pl;
            try {
                pl = Plan.plan(a);
            } catch (CypherError e) {
                if (e.isParseError()) throw e;
                throw new CypherError("plan", e.getMessage(), null, null, "SemanticError", null);
            }
            return new Prepared(text, a, pl);
        });
    }

    /** 规划结果的文本形态（算子与列名；调试用）。 */
    public static String explain(String query) {
        return Printer.pr(prepare(query).plan);
    }

    /** 语句是否含写子句。 */
    public static boolean writes(String query) {
        return prepare(query).writes();
    }

    // ═══ 用户过程 / 函数 ═══

    /**
     * 注册只读过程（进程级、名字不分大小写，至少两段：my.proc），之后可以 {@code CALL my.proc(…) YIELD …}。outputs 为空 ⇒ VOID 过程。
     * 同名再注册 ⇒ 覆盖。只读过程拿到的 {@link ProcedureContext#db()} 只读，不要在里面写库。注册 / 注销会清空语句缓存。
     */
    public static void registerProcedure(String name, List<ProcParam> inputs, List<ProcParam> outputs, Procedure fn) {
        Procs.register(name, inputs, outputs, fn, false);
        clearCaches();
    }

    /** 注册写模式过程：含它的语句是写语句；{@link ProcedureContext#query} 在同一个语句事务里执行（出错整条回滚）。 */
    public static void registerWriteProcedure(String name, List<ProcParam> inputs, List<ProcParam> outputs, Procedure fn) {
        Procs.register(name, inputs, outputs, fn, true);
        clearCaches();
    }

    /** 注册用户函数（命名规则同过程）：参数个数 ∈ [minArity, maxArity]。之后可以 {@code RETURN my.slug(n.name)}。 */
    public static void registerFunction(String name, int minArity, int maxArity, UserFunction fn) {
        Procs.registerFn(name, minArity, maxArity, fn);
        clearCaches();
    }

    /** 注销过程或函数。 */
    public static void unregisterProcedure(String name) {
        Procs.unregister(name);
        clearCaches();
    }

    public static void unregisterFunction(String name) {
        unregisterProcedure(name);
    }

    /** 已注册的用户过程与函数：{@code {procedures [名], functions [名]}}。 */
    public static Map<String, List<String>> registered() {
        return Procs.registered();
    }

    private static void clearCaches() {
        CACHE.clear();
        Exec.clearQCache();
    }

    // ═══ 参数 ═══

    static Object normValue(Object v) {
        if (v == null || v instanceof Boolean || v instanceof String || v instanceof Long) return v;
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof BigInteger b) return b.bitLength() < 64 ? (Object) b.longValue() : b;
        if (v instanceof Double) return v;
        if (v instanceof Float || v instanceof BigDecimal || v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof Character c) return String.valueOf(c);
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof float[] || v instanceof Temporal || v instanceof Dur || v instanceof Point) return v;
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), normValue(x)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object x : c) out.add(normValue(x));
            return out;
        }
        if (v instanceof Object[] a) {
            List<Object> out = new ArrayList<>(a.length);
            for (Object x : a) out.add(normValue(x));
            return out;
        }
        if (v instanceof double[] a) {
            List<Object> out = new ArrayList<>(a.length);
            for (double x : a) out.add(x);
            return out;
        }
        if (v instanceof long[] a) {
            List<Object> out = new ArrayList<>(a.length);
            for (long x : a) out.add(x);
            return out;
        }
        throw new IllegalArgumentException("Cypher 参数值不支持：" + v);
    }

    static Map<String, Object> normParams(Map<String, ?> ps) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ps != null) ps.forEach((k, v) -> out.put(String.valueOf(k), normValue(v)));
        return out;
    }

    private static void checkParams(Prepared p, Map<String, Object> ps) {
        Object need = Clj.get(p.analysis, Clj.kw("params"));
        TreeSet<String> missing = new TreeSet<>();
        if (need instanceof Collection<?> c) for (Object n : c) if (!ps.containsKey(String.valueOf(n))) missing.add(String.valueOf(n));
        if (!missing.isEmpty()) {
            List<String> xs = new ArrayList<>();
            for (String m : missing) xs.add("$" + m);
            throw CypherException.of(new CypherError("semantic", "missing parameter(s): " + String.join(", ", xs), null, null,
                    "ParameterMissing", "MissingParameter"));
        }
    }

    // ═══ 执行 ═══

    private static final Kw OPS = Clj.kw("ops"), UNION = Clj.kw("union"), PARTS = Clj.kw("parts"), OPTIONAL = Clj.kw("optional"),
            CREATE = Clj.kw("create"), MERGE_REL = Clj.kw("merge-rel"), MERGE_PATTERN = Clj.kw("merge-pattern");

    /** 算子连同 OPTIONAL MATCH 里嵌套的算子。 */
    private static void allOps(Object ops, List<Object> out) {
        if (!(ops instanceof List<?> l)) return;
        for (Object o : l) {
            out.add(o);
            if (Clj.head(o) == OPTIONAL) allOps(Clj.second(o), out);
        }
    }

    private static void stepTypes(Object chain, Set<String> ts) {
        Object steps = Clj.nth(chain, 2);
        if (!(steps instanceof List<?> sl)) return;
        for (Object st : sl) {
            Object rel = Clj.first(st);
            Object t = Clj.first(Clj.nth(rel, 2));
            if (t instanceof String s) ts.add(s);
        }
    }

    /** CREATE / MERGE 用到的关系类型先注册好（幂等；在语句之外——语句失败也留着）。 */
    private static void registerTypes(Bitcask db, Object plan) {
        Object union = Clj.get(plan, UNION);
        if (union != null) {
            for (Object part : (List<?>) Clj.get(union, PARTS)) registerTypes(db, part);
            return;
        }
        List<Object> ops = new ArrayList<>();
        allOps(Clj.get(plan, OPS), ops);
        Set<String> ts = new LinkedHashSet<>();
        for (Object o : ops) {
            Kw k = Clj.head(o);
            if (k == CREATE) {
                for (Object ch : (List<?>) Clj.second(o)) stepTypes(ch, ts);
            } else if (k == MERGE_REL) {
                if (Clj.nth(o, 3) instanceof String s) ts.add(s);
            } else if (k == MERGE_PATTERN) stepTypes(Clj.nth(o, 2), ts);
        }
        for (String t : ts) if (Graph.etypeId(db, t) == null) Graph.registerEtype(db, t);
    }

    public static CypherResult execute(Bitcask db, String query) {
        return execute(db, prepare(query), Map.of(), Options.DEFAULT);
    }

    public static CypherResult execute(Bitcask db, String query, Map<String, ?> params) {
        return execute(db, prepare(query), params, Options.DEFAULT);
    }

    public static CypherResult execute(Bitcask db, String query, Map<String, ?> params, Options opts) {
        return execute(db, prepare(query), params, opts);
    }

    public static CypherResult execute(Graph graph, String query, Map<String, ?> params) {
        return execute(graph.db(), prepare(query), params, Options.DEFAULT);
    }

    public static CypherResult execute(Bitcask db, Prepared p, Map<String, ?> params) {
        return execute(db, p, params, Options.DEFAULT);
    }

    /** 执行一条语句（文本或 {@link #prepare} 的结果）。参数值：null、布尔、整数、浮点、字符串、列表、map、float[]（向量）、时间值、Point。 */
    public static CypherResult execute(Bitcask db, Prepared p, Map<String, ?> params, Options opts) {
        Options o = opts == null ? Options.DEFAULT : opts;
        Map<String, Object> ps = normParams(params);
        checkParams(p, ps);
        return translating(() -> {
            if (!p.writes()) return Exec.run(p.plan, Store.direct(db), null, ps);
            registerTypes(db, p.plan);
            for (int attempt = 0; ; attempt++) {
                Store.Tx t = Store.tx(db);
                CypherResult r;
                Long prev = Exec.MAX_WRITE_VERTICES.get();
                Exec.MAX_WRITE_VERTICES.set(o.maxWriteVertices());
                try {
                    r = Exec.run(p.plan, t, t, ps);
                } finally {
                    Exec.MAX_WRITE_VERTICES.set(prev);
                }
                if (!"conflict".equals(t.commit())) return r;
                if (o.retries() >= 0 && attempt >= o.retries())
                    throw CypherError.exec("statement kept conflicting with concurrent writers; retries exhausted");
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1 << Math.min(attempt, 6)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    // ═══ 流式游标 ═══

    /**
     * 只读语句的流式游标：执行是惰性的，fetch 取多少算多少（LIMIT 之前的扫描也按需推进）。写语句 ⇒ 报错。
     * 游标借着库句柄，库关了再取会报存储层的错；用完 close（取完自动关闭）。
     */
    public static CypherCursor cursor(Bitcask db, String query, Map<String, ?> params) {
        Prepared p = prepare(query);
        Map<String, Object> ps = normParams(params);
        if (p.writes()) throw CypherException.of(new CypherError("exec", "cursors are only for read-only queries", null, null, null, null));
        checkParams(p, ps);
        Exec.OpenRows opened = translating(() -> Exec.openRows(p.plan, Store.direct(db), null, ps));
        return new CypherCursor(opened.columns(), opened.rows());
    }
}
