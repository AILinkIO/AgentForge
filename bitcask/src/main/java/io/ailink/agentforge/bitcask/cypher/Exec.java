package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.Clj.kw;
import static io.ailink.agentforge.bitcask.cypher.Clj.nth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.BitcaskIndex;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.FieldsHit;
import io.ailink.agentforge.bitcask.Filter;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.SearchHit;
import io.ailink.agentforge.bitcask.SearchOptions;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.GraphKeys;
import io.ailink.agentforge.bitcask.graph.GraphModel;
import io.ailink.agentforge.bitcask.graph.VertexSpec;
import io.ailink.agentforge.bitcask.value.Point;
import io.ailink.agentforge.bitcask.value.Temporals;

/**
 * 逻辑计划的执行器（clj {@code cypher.exec}，路径搜索一节在 {@link ExecPaths}）。拉取式：每个算子把输入行序列变成输出行序列，
 * 序列是惰性的（{@link Iterable}），所以 LIMIT 之前的扫描 / 展开凑够行数就停；排序、聚合、去重、写算子要看全部输入，在那里物化。
 * 行是 变量名 ⇒ 值 的 map（值的形态见 {@link Expr}）。顶点的标签 / 属性、边的属性、关系类型名在一次查询内按需读、就地缓存。
 * 存储经 {@link Store}：只读语句直读，写语句在语句事务里（读都登记版本、写进缓冲，提交时一条原子批）。
 * 全文 / 向量检索总是读引擎（看不到同一语句里还没提交的写）。
 * <p>惰性序列每次 iterator() 都重新求值（不像 clj 的 lazy-seq 会缓存）：同一个序列只遍历一次（见 :optional 的写法）。
 */
final class Exec {
    private Exec() {
    }

    private static final long SEARCH_CAP = 1000000;

    /** 一条写语句最多写多少个顶点（选项 :max-write-vertices）；null = 不限（缺省）。clj {@code *max-write-vertices*}。 */
    static final ThreadLocal<Long> MAX_WRITE_VERTICES = new ThreadLocal<>();

    /** clj 的 {@code ::none}（类型名查不到）。 */
    static final Kw NONE = kw("none");
    private static final Object UNBOUND = new Object();

    // ── 关键字 ──
    private static final Kw K_NAME = kw("name"), K_ID = kw("id");
    private static final Kw IN = kw("in"), OUT = kw("out"), BOTH = kw("both"), ASC = kw("asc"), FIRST = kw("first");
    private static final Kw REPLACE = kw("replace"), MERGE = kw("merge");
    private static final Kw SET_PROP = kw("set-prop"), SET_PROPS = kw("set-props"), SET_LABELS = kw("set-labels"),
            REMOVE_PROP = kw("remove-prop"), REMOVE_LABELS = kw("remove-labels");
    private static final Kw K_NODE = kw("node"), K_REL = kw("rel"), K_PATH = kw("path"), K_ENTITY = kw("entity");
    private static final Kw COUNT_STAR = kw("count-star"), AGG_REF = kw("agg-ref");
    private static final Kw OP = kw("op"), SPACESHIP = kw("<=>"), PROP = kw("prop"), VAR = kw("var");
    private static final Kw SINGLE = kw("single"), ALL = kw("all"), ANY = kw("any"), ANY_SHORTEST = kw("any-shortest"),
            ALL_SHORTEST = kw("all-shortest"), SHORTEST = kw("shortest"), SHORTEST_GROUPS = kw("shortest-groups");
    private static final Kw KIND = kw("kind"), K = kw("k"), PUSH = kw("push"), UNION = kw("union"), ALL_Q = kw("all?"),
            PARTS = kw("parts"), OPS = kw("ops"), COLUMNS = kw("columns"), OUTER = kw("outer"), SUB = kw("sub"),
            PATTERN_PRED = kw("pattern-pred");
    private static final Kw HAS_LABEL = kw("has-label"), HAS_ANY_LABEL = kw("has-any-label"), EXISTS = kw("exists"), CMP = kw("cmp"),
            EQ = kw("eq"), LT = kw("lt"), LE = kw("le"), GT = kw("gt"), GE = kw("ge");
    private static final Kw F_EQ = kw("="), F_LT = kw("<"), F_LE = kw("<="), F_GT = kw(">"), F_GE = kw(">=");
    private static final Kw TERM = kw("term"), PHRASE = kw("phrase"), PREFIX = kw("prefix"), FUZZY = kw("fuzzy"), NEG = kw("neg"),
            OR = kw("or");
    // *len-cap* 的键（ExecPaths.LEN_CAP）
    private static final Kw VARS = kw("vars"), CAP = kw("cap"), HIT = kw("hit"), TIP = kw("tip");
    // 算子
    private static final Kw NODE_BY_ID = kw("node-by-id"), ID_LIST = kw("id-list"), LABEL_SCAN = kw("label-scan"),
            ALL_NODES_SCAN = kw("all-nodes-scan"), NODE_CHECK = kw("node-check"), REL_CHECK = kw("rel-check"), EXPAND = kw("expand"),
            VAR_EXPAND = kw("var-expand"), QPP_EXPAND = kw("qpp-expand");

    // ═══ 上下文与缓存 ═══

    /** 查询内缓存：有就取，没有算一个放进去（满 10 万项清空重来）。 */
    static <KK, V> V cacheGet(Map<KK, V> m, KK k, Supplier<? extends V> f) {
        if (m.containsKey(k)) return m.get(k);
        V v = f.get();
        if (m.size() > 100000) m.clear();
        m.put(k, v);
        return v;
    }

    /** 统计计数加 n。 */
    static void stat(Ctx ctx, String k, long n) {
        Object cur = ctx.stats.get(k);
        ctx.stats.put(k, (cur == null ? 0L : ((Number) cur).longValue()) + n);
    }

    static Bitcask db(Ctx ctx) {
        return ctx.store.db();
    }

    private static NodeInfo toInfo(GraphModel.DocInfo d) {
        return new NodeInfo(d.labels(), d.properties(), d.vector());
    }

    static NodeInfo nodeInfo(Ctx ctx, Object vid) {
        long v = ((Number) vid).longValue();
        return cacheGet(ctx.nodes, v, () -> {
            GraphModel.Doc d = ctx.store.doc(v);
            return d != null ? toInfo(GraphModel.docInfo(d)) : NodeInfo.EMPTY;
        });
    }

    static void forgetVertex(Ctx ctx, Object vid) {
        ctx.nodes.remove(((Number) vid).longValue());
    }

    /** 顶点的向量（float[] 或 null）。range 批量读回的文档不带向量（标 UNKNOWN），真用到时再单独读一次。 */
    static float[] nodeVector(Ctx ctx, Object vid) {
        long v = ((Number) vid).longValue();
        NodeInfo info = nodeInfo(ctx, v);
        Object vec = info.vector();
        if (vec == NodeInfo.UNKNOWN) {
            GraphModel.Doc d = ctx.store.doc(v);
            float[] vv = d == null ? null : d.vector();
            ctx.nodes.put(v, new NodeInfo(info.labels(), info.properties(), vv));
            return vv;
        }
        return (float[]) vec;
    }

    static Map<String, Object> relProps(Ctx ctx, Object rel) {
        VRel r = (VRel) rel;
        if (r.props() instanceof byte[] b) return GraphModel.decodeEdgeProps(b);
        return cacheGet(ctx.rels, r.id(), () -> GraphModel.decodeEdgeProps(ctx.store.edgeProps(r.src(), r.etype(), r.dst(), r.rank())));
    }

    /** 库句柄 ⇒ {[:id 名] id, [:name id] 名}。类型注册只增不改，查到的结果跨语句缓存；查不到的只在本语句内记。 */
    private static final Map<Bitcask, Map<Object, Object>> ETYPE_CACHES = Collections.synchronizedMap(new WeakHashMap<>());

    static Map<Object, Object> etypeCache(Bitcask d) {
        Map<Object, Object> m = ETYPE_CACHES.get(d);
        if (m != null) return m;
        m = new ConcurrentHashMap<>();
        ETYPE_CACHES.put(d, m);
        return m;
    }

    /** etype ⇒ 名字（String；没注册名字 ⇒ etype 本身，Long）。 */
    static Object typeName(Ctx ctx, Object etype) {
        long et = ((Number) etype).longValue();
        Map<Object, Object> m = ctx.etypes;
        List<Object> kk = List.of(K_NAME, et);
        Object hit = m.get(kk);
        if (hit != null) return hit;
        return cacheGet(ctx.types, et, () -> {
            String n = Graph.etypeName(db(ctx), et);
            if (n != null) {
                m.put(kk, n);
                return n;
            }
            return et;
        });
    }

    /** 类型名 ⇒ id（Long）或 {@link #NONE}。 */
    static Object typeId(Ctx ctx, Object nm) {
        Map<Object, Object> m = ctx.etypes;
        List<Object> kk = Arrays.asList(K_ID, nm);
        Object hit = m.get(kk);
        if (hit != null) return hit;
        return cacheGet(ctx.typeIds, nm, () -> {
            Long id = Graph.etypeId(db(ctx), (String) nm);
            if (id != null) {
                m.put(kk, id);
                return id;
            }
            return NONE;
        });
    }

    // ═══ 小工具（惰性序列 / 行） ═══

    private static Object ev(Object e, Map<String, Object> row, Ctx ctx) {
        return Expr.evalExpr(e, row, ctx);
    }

    private static Map<String, Object> assoc(Map<String, Object> row, Object... kvs) {
        Map<String, Object> m = new HashMap<>(row);
        for (int i = 0; i + 1 < kvs.length; i += 2) m.put((String) kvs[i], kvs[i + 1]);
        return m;
    }

    private static Object getOr(Map<String, Object> row, Object k, Object nf) {
        Object v = row.get(k);
        return v != null || row.containsKey(k) ? v : nf;
    }

    private static long lng(Object x) {
        return ((Number) x).longValue();
    }

    private static boolean isNode(Object x) {
        return x instanceof VNode;
    }

    private static boolean isRel(Object x) {
        return x instanceof VRel;
    }

    private static boolean isPath(Object x) {
        return x instanceof VPath;
    }

    /** clj plain-map?（Java 里顶点 / 边 / 路径 / 时间 / 点都不是 Map）。 */
    private static boolean isPlainMap(Object x) {
        return x instanceof Map<?, ?>;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object x) {
        if (x == null) return List.of();
        if (x instanceof List<?> l) return (List<Object>) l;
        return Clj.vecOf((Iterable<?>) x);
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Map<String, Object>> rowsOf(Object x) {
        return x == null ? List.of() : (Iterable<Map<String, Object>>) x;
    }

    private static List<Map<String, Object>> vecRows(Iterable<Map<String, Object>> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : in) out.add(r);
        return out;
    }

    /** 惰性 mapcat：f 每次在需要时才对下一个输入调用。 */
    static <A, B> Iterable<B> lazyMapcat(Function<? super A, ? extends Iterable<? extends B>> f, Iterable<? extends A> s) {
        return () -> new Iterator<B>() {
            final Iterator<? extends A> src = s.iterator();
            Iterator<? extends B> cur = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!cur.hasNext()) {
                    if (!src.hasNext()) return false;
                    Iterable<? extends B> it = f.apply(src.next());
                    cur = it == null ? Collections.emptyIterator() : it.iterator();
                }
                return true;
            }

            @Override
            public B next() {
                if (!hasNext()) throw new NoSuchElementException();
                return cur.next();
            }
        };
    }

    private static <A, B> Iterable<B> lmap(Function<? super A, ? extends B> f, Iterable<? extends A> s) {
        return () -> new Iterator<B>() {
            final Iterator<? extends A> src = s.iterator();

            @Override
            public boolean hasNext() {
                return src.hasNext();
            }

            @Override
            public B next() {
                return f.apply(src.next());
            }
        };
    }

    private static <A> Iterable<A> lfilter(Predicate<? super A> p, Iterable<A> s) {
        return () -> new Iterator<A>() {
            final Iterator<A> src = s.iterator();
            A nx;
            boolean has;

            @Override
            public boolean hasNext() {
                while (!has && src.hasNext()) {
                    A x = src.next();
                    if (p.test(x)) {
                        nx = x;
                        has = true;
                    }
                }
                return has;
            }

            @Override
            public A next() {
                if (!hasNext()) throw new NoSuchElementException();
                has = false;
                A x = nx;
                nx = null;
                return x;
            }
        };
    }

    private static <A> Iterable<A> ltake(long n, Iterable<A> s) {
        return () -> new Iterator<A>() {
            Iterator<A> src;
            long left = n;

            @Override
            public boolean hasNext() {
                if (left <= 0) return false;
                if (src == null) src = s.iterator();
                return src.hasNext();
            }

            @Override
            public A next() {
                if (!hasNext()) throw new NoSuchElementException();
                left--;
                return src.next();
            }
        };
    }

    private static <A> Iterable<A> ldrop(long n, Iterable<A> s) {
        return () -> {
            Iterator<A> src = s.iterator();
            for (long i = 0; i < n && src.hasNext(); i++) src.next();
            return src;
        };
    }

    private static <A> Iterable<A> lconcat(List<? extends Iterable<? extends A>> parts) {
        return lazyMapcat(x -> x, parts);
    }

    /** 按分组键去重（1 与 1.0 同一行；惰性）。 */
    static <T> Iterable<T> distinctByKey(Iterable<T> rows) {
        return () -> {
            Set<Object> seen = new HashSet<>();
            return lfilter((T r) -> seen.add(Expr.groupKey(r)), rows).iterator();
        };
    }

    private static <A> Iterable<A> one(A x) {
        List<A> l = new ArrayList<>(1);
        l.add(x);
        return l;
    }

    /** 邻接展开的一步：{@link RelHop} 或 [Rel 对端]。 */
    private static VRel hopRel(Object h) {
        return h instanceof RelHop rh ? rh.rel() : (VRel) nth(h, 0);
    }

    private static long hopOther(Object h) {
        return h instanceof RelHop rh ? rh.other() : lng(nth(h, 1));
    }

    private static boolean contains(Object coll, Object x) {
        if (coll == null) return false;
        if (coll instanceof Collection<?> c) return c.contains(x);
        if (coll instanceof Map<?, ?> m) return m.containsKey(x);
        throw new IllegalArgumentException("contains on " + coll.getClass());
    }

    // ── clj volatile / fn 的几种 Java 表示（与 ExecPaths 的 *len-cap* 交互用）──

    /** 新的「截断标记」（clj (volatile! false)；同 ExecPaths，volatile 一律是 AtomicReference&lt;Object&gt;）。 */
    private static Object newFlag() {
        return new AtomicReference<Object>(false);
    }

    @SuppressWarnings("unchecked")
    private static void vreset(Object vol, Object v) {
        switch (vol) {
            case AtomicBoolean b -> b.set(Boolean.TRUE.equals(v));
            case AtomicReference<?> r -> ((AtomicReference<Object>) r).set(v);
            case AtomicLong l -> l.set(lng(v));
            case boolean[] b -> b[0] = Boolean.TRUE.equals(v);
            case Object[] a -> a[0] = v;
            default -> throw new IllegalStateException("not a volatile: " + vol);
        }
    }

    @SuppressWarnings("unchecked")
    private static void vconj(Object vol, Object x) {
        switch (vol) {
            case AtomicReference<?> r -> {
                AtomicReference<Object> rr = (AtomicReference<Object>) r;
                rr.set(Clj.conj((List<?>) rr.get(), x));
            }
            case Object[] a -> a[0] = Clj.conj((List<?>) a[0], x);
            case List<?> l -> ((List<Object>) l).add(x);
            default -> throw new IllegalStateException("not a volatile: " + vol);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callFn(Object f, Object... args) {
        try {
            return switch (f) {
                case BiConsumer<?, ?> c when args.length == 2 -> {
                    ((BiConsumer<Object, Object>) c).accept(args[0], args[1]);
                    yield null;
                }
                case BiFunction<?, ?, ?> c when args.length == 2 -> ((BiFunction<Object, Object, Object>) c).apply(args[0], args[1]);
                case Supplier<?> s when args.length == 0 -> s.get();
                case Callable<?> c when args.length == 0 -> c.call();
                case Function<?, ?> fn when args.length == 1 -> ((Function<Object, Object>) fn).apply(args[0]);
                default -> throw new IllegalStateException("not callable: " + f);
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ═══ 写模式过程的语句缓存 ═══

    private static final int Q_CACHE_SIZE = 256;
    private static final ConcurrentHashMap<String, Object> Q_CACHE = new ConcurrentHashMap<>();

    /** 清空写模式过程 query 的语句缓存（注册 / 注销过程与函数时调用：签名可能变了）。 */
    static void clearQCache() {
        Q_CACHE.clear();
    }

    /** 语句文本 ⇒ 规划结果（缓存 Q_CACHE_SIZE 条，满了清空重来）。 */
    static Object qPlan(String text) {
        Object hit = Q_CACHE.get(text);
        if (hit != null) return hit;
        Object a = Sema.analyze(Parser.parse(text));
        if (Clj.truthy(Clj.get(a, UNION))) throw CypherError.exec("procedures cannot run UNION statements through :q");
        Object pl = Plan.plan(a);
        if (Q_CACHE.size() >= Q_CACHE_SIZE) Q_CACHE.clear();
        Q_CACHE.put(text, pl);
        return pl;
    }

    private static List<String> strings(Object cols) {
        List<String> out = new ArrayList<>();
        if (cols != null) for (Object c : (Iterable<?>) cols) out.add((String) c);
        return out;
    }

    /**
     * 写模式过程拿到的句柄：db() 是库，query 在同一个语句事务里执行（共用写入计数、统计与上限；出错 ⇒ 整条语句回滚）。
     * 结果的 stats 是空 map（clj 的 :q 只交 {:columns :rows}）。
     */
    static ProcedureContext stmtHandle(Ctx ctx) {
        return new ProcedureContext() {
            @Override
            public Bitcask db() {
                return Exec.db(ctx);
            }

            @Override
            public CypherResult query(String text, Map<String, ?> params) {
                Object pl = qPlan(Clj.str(text));
                List<String> columns = strings(Clj.get(pl, COLUMNS));
                Map<String, Object> ps = new HashMap<>();
                // Java 调用方的 Integer 等规范成引擎的值（clj 只把关键字键转成名字）
                if (params != null) params.forEach((k, v) -> ps.put(String.valueOf(k), Procs.normOut(v)));
                Ctx ctx2 = ctx.withParams(ps);
                Iterable<Map<String, Object>> in = one(new HashMap<>());
                for (Object o : asList(Clj.get(pl, OPS))) in = op(ctx2, o, in);
                List<Map<String, Object>> rows = vecRows(in);
                List<List<Object>> out = new ArrayList<>();
                if (!columns.isEmpty())
                    for (Map<String, Object> row : rows) {
                        List<Object> r = new ArrayList<>(columns.size());
                        for (String c : columns) r.add(Exec.out(ctx2, row.get(c)));
                        out.add(r);
                    }
                return new CypherResult(columns, out, new LinkedHashMap<>());
            }
        };
    }

    /** 只读过程拿到的句柄：只有 db()。 */
    private static ProcedureContext readHandle(Ctx ctx) {
        return new ProcedureContext() {
            @Override
            public Bitcask db() {
                return Exec.db(ctx);
            }

            @Override
            public CypherResult query(String cypher, Map<String, ?> params) {
                throw CypherError.exec("read-only procedures cannot run Cypher statements");
            }
        };
    }

    /** 用户过程：每一行算一次参数（查类型、INTEGER ⇒ FLOAT），调函数；VOID ⇒ 原样放行，否则每条输出一行。 */
    static Iterable<Map<String, Object>> userProc(Ctx ctx, Map<?, ?> spec0, List<?> args, Iterable<Map<String, Object>> in) {
        List<Object> inputs = asList(spec0.get(Procs.INPUTS));
        List<Object> outputs = asList(spec0.get(Procs.OUTPUTS));
        Procedure f = (Procedure) spec0.get(Procs.FN);
        Object nm = spec0.get(Procs.NAME);
        List<Object> pairs = asList(nth(args, 1));
        List<Object> argx = asList(nth(args, 2));
        BiPredicate<Kw, Object> pred = (k, v) -> {
            if (k == K_NODE) return isNode(v);
            if (k == K_REL) return isRel(v);
            if (k == K_PATH) return isPath(v);
            if (k == K_ENTITY) return isNode(v) || isRel(v);
            throw new IllegalArgumentException(String.valueOf(k));
        };
        return lazyMapcat((Map<String, Object> row) -> {
            Map<String, Object> argm = new LinkedHashMap<>();
            int n = Math.min(argx.size(), inputs.size());
            for (int i = 0; i < n; i++) {
                Map<?, ?> spec = (Map<?, ?>) inputs.get(i);
                Object v = ev(argx.get(i), row, ctx);
                Object val;
                if (v == null) {
                    if (Clj.truthy(spec.get(Procs.NULLABLE))) val = null;
                    else throw CypherError.exec("argument `", spec.get(Procs.NAME), "` of ", nm, " must not be null");
                } else if (Procs.typeOk(spec, v, pred)) val = Procs.coerce(spec, v);
                else throw CypherError.execc("TypeError", "InvalidArgumentType", "argument `", spec.get(Procs.NAME), "` of ", nm,
                            " must be ", spec.get(Procs.TYPE));
                argm.put((String) spec.get(Procs.NAME), val);
            }
            ProcedureContext h = spec0.get(Procs.MODE) == Procs.WRITE ? stmtHandle(ctx) : readHandle(ctx);
            Iterable<? extends Map<String, ?>> out = f.call(h, argm);
            if (outputs.isEmpty()) {
                if (out != null) for (Object ignored : out) {
                    // 消费掉（VOID 过程的副作用）
                }
                return one(row);
            }
            if (out == null) return List.of();
            return lmap((Map<String, ?> rec0) -> {
                Map<String, Object> rec = new HashMap<>();
                if (rec0 != null) rec0.forEach((k, v) -> rec.put(String.valueOf(k), Procs.normOut(v)));
                Map<String, Object> r = new HashMap<>(row);
                for (Object p : pairs) r.put((String) nth(p, 1), rec.get(nth(p, 0)));
                return r;
            }, out);
        }, in);
    }

    /** 内置过程 db.labels / db.relationshipTypes / db.propertyKeys ⇒ 记录（{列名 值}）。 */
    static List<Map<String, Object>> callProc(Ctx ctx, Object nm) {
        Bitcask d = db(ctx);
        switch (String.valueOf(nm)) {
            case "db.labels" -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object l : asList(ExecPaths.allLabels(d))) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("label", l);
                    out.add(m);
                }
                return out;
            }
            case "db.relationshiptypes" -> {
                byte[] lo = GraphKeys.familyBytes("tn");
                int n = lo.length;
                List<Map<String, Object>> out = new ArrayList<>();
                for (Entry e : d.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo)))) {
                    byte[] k = e.key();
                    if (GraphKeys.isEtypeNameKey(k)) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("relationshipType", new String(k, n + 4, k.length - n - 4, StandardCharsets.UTF_8));
                        out.add(m);
                    }
                }
                return out;
            }
            case "db.propertykeys" -> {
                byte[] lo = GraphKeys.familyBytes("n");
                TreeSet<String> ks = new TreeSet<>();
                for (Entry e : d.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo)).meta(true))) {
                    Map<String, Object> m = GraphModel.safeDecodeMeta(e.meta());
                    if (m != null) ks.addAll(m.keySet());
                }
                ks.remove(GraphModel.LABELS_KEY);
                ks.remove(GraphModel.TYPES_KEY);
                List<Map<String, Object>> out = new ArrayList<>();
                for (String kk : ks) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("propertyKey", kk);
                    out.add(m);
                }
                return out;
            }
            default -> throw CypherError.exec("unknown procedure `", nm, "` (was it unregistered after the statement was prepared?)");
        }
    }

    // ═══ 写 ═══

    static Store.Tx tx(Ctx ctx) {
        if (ctx.tx == null) throw CypherError.exec("internal: updating clause outside a transaction");
        return ctx.tx;
    }

    static void noteWritten(Ctx ctx, Object vid) {
        long v = lng(vid);
        Set<Object> w = ctx.written;
        if (!w.contains(v)) {
            Long cap = MAX_WRITE_VERTICES.get();
            if (cap != null && w.size() >= cap)
                throw CypherError.execc("SemanticError", null, "one statement may write at most ", cap, " vertices (option :max-write-vertices)");
            w.add(v);
        }
        forgetVertex(ctx, v);
    }

    /** 图模型的校验错误 ⇒ 执行错误（英文报文，同 Erlang 版）。 */
    static <T> T modelCall(Supplier<T> f) {
        try {
            return f.get();
        } catch (BitcaskException e) {
            String c = e.code();
            Object a = e.data().get("bitcask/arg");
            if (c == null) throw e;
            switch (c) {
                case "bitcask/graph-property-type" -> throw CypherError.execc("TypeError", "InvalidPropertyType", "unsupported property type for `", a,
                        "` (allowed: null, boolean, integer, float, string, list of strings)");
                case "bitcask/graph-property-reserved" -> throw CypherError.exec("property names starting with `_` are reserved: ", a);
                case "bitcask/graph-property-key-invalid" -> throw CypherError.exec("invalid property name ", a);
                case "bitcask/graph-label-invalid" -> throw CypherError.exec("invalid label ", a);
                default -> {
                    if (c.startsWith("bitcask/graph-")) throw CypherError.exec(e.getMessage());
                    throw e;
                }
            }
        }
    }

    /** 查询向量：数值列表或 float[] ⇒ float[]。 */
    static float[] toF32(Object v) {
        if (v instanceof float[] f) return f;
        if (v instanceof List<?> l && l.stream().allMatch(x -> x instanceof Number)) {
            float[] out = new float[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).floatValue();
            return out;
        }
        throw CypherError.execc("TypeError", "InvalidArgumentType", "a vector must be a list of numbers");
    }

    @SuppressWarnings("unchecked")
    static Map<Object, Object> propsArg(Ctx ctx, Object e, Map<String, Object> row) {
        if (e == null) return new LinkedHashMap<>();
        Object m = ev(e, row, ctx);
        if (isPlainMap(m)) return (Map<Object, Object>) m;
        if (m == null) return new LinkedHashMap<>();
        throw CypherError.execc("TypeError", "InvalidArgumentType", "properties must be a map");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> createNode(Ctx ctx, Object node, Map<String, Object> row) {
        String v = (String) nth(node, 1);
        Object labels = nth(node, 2);
        Object pe = nth(node, 3);
        Object cur = getOr(row, v, UNBOUND);
        if (isNode(cur)) return row;
        if (cur == null) throw CypherError.exec("cannot create a relationship to a null node `", v, "`");
        Map<Object, Object> m = propsArg(ctx, pe, row);
        Object text = m.get("text");
        Object emb = m.get("embedding");
        Map<Object, Object> props = new LinkedHashMap<>(m);
        props.remove("text");
        props.remove("embedding");
        if (!(text == null || text instanceof String))
            throw CypherError.execc("TypeError", "InvalidArgumentType", "property `text` must be a string");
        float[] vec = emb != null ? toF32(emb) : null;
        @SuppressWarnings("unchecked")
        final List<String> ls0 = labels == null ? List.of() : (List<String>) labels;
        GraphModel.VertexValue val = modelCall(() -> GraphModel.specToValue(new VertexSpec(ls0, (Map<String, ?>) (Map<?, ?>) props, (String) text, vec)));
        var doc = val.doc();
        float[] dv = doc.vector() != null && doc.vector().length > 0 ? doc.vector() : null;
        GraphModel.Doc d = new GraphModel.Doc(doc.text(), doc.meta() != null && doc.meta().length > 0 ? doc.meta() : null, dv, 0);
        List<String> ls = val.labels();
        long vid = tx(ctx).allocate(d, ls);
        noteWritten(ctx, vid);
        stat(ctx, "nodes-created", 1);
        stat(ctx, "labels-added", ls.size());
        int np = modelCall(() -> GraphModel.normProps(props)).size();
        stat(ctx, "properties-set", np + (text != null ? 1 : 0) + (emb != null ? 1 : 0));
        return assoc(row, v, new VNode(vid));
    }

    static byte[] floorKey(long s, long t, long d) {
        return GraphKeys.rankFloor(s, t, d).getBytes(StandardCharsets.US_ASCII);
    }

    /** 新边的序号：从这对顶点的 rank 下限（以前的语句删过边时记下的）往上，跳过已有的与本语句删掉的——删掉的关系 id 不复用。 */
    static long nextRank(Ctx ctx, long s, long t, long d) {
        byte[] cur = tx(ctx).readRaw(floorKey(s, t, d));
        long r = cur != null ? GraphKeys.bytesToU64(cur) : 0;
        while (ctx.store.edgeProps(s, t, d, r) != null || ctx.deleted.contains(List.of(s, t, d, r))) r++;
        return r;
    }

    /** 删边：把这对顶点的 rank 下限推到 rank + 1。 */
    static void retireRank(Ctx ctx, Object id) {
        long s = lng(nth(id, 0)), t = lng(nth(id, 1)), d = lng(nth(id, 2)), r = lng(nth(id, 3));
        byte[] k = floorKey(s, t, d);
        byte[] cur = tx(ctx).readRaw(k);
        long floor = Math.max(r + 1, cur != null ? GraphKeys.bytesToU64(cur) : 0);
        tx(ctx).write("put", k, GraphKeys.u64Bytes(floor));
    }

    static Map<String, Object> createRel(Ctx ctx, Object rel, long prev, long nxt, Map<String, Object> row) {
        String rv = (String) nth(rel, 1);
        Object typ = nth(nth(rel, 2), 0);
        Object pe = nth(rel, 3);
        Object dir = nth(rel, 4);
        // 无方向（MERGE (a)-[:T]-(b) 找不到时）从左到右建
        long s = dir == IN ? nxt : prev, d = dir == IN ? prev : nxt;
        Object t0 = typeId(ctx, typ);
        if (t0 == NONE) throw CypherError.execc("TypeError", "InvalidArgumentType", "relationship type `", typ, "` is not registered");
        long t = lng(t0);
        Map<Object, Object> props = propsArg(ctx, pe, row);
        byte[] pb = modelCall(() -> GraphModel.encodeEdgeProps(props));
        long r = nextRank(ctx, s, t, d);
        tx(ctx).putEdge(s, t, d, r, pb);
        noteWritten(ctx, s);
        noteWritten(ctx, d);
        stat(ctx, "relationships-created", 1);
        stat(ctx, "properties-set", props.size());
        return assoc(row, rv, new VRel(s, t, d, r, pb));
    }

    static long boundNode(Object node, Map<String, Object> row) {
        Object v = nth(node, 1);
        Object n = row.get(v);
        if (n instanceof VNode vn) return vn.vid();
        throw CypherError.execc("SemanticError", null, "CREATE needs `", v, "` to be a node");
    }

    static Map<String, Object> createChain(Ctx ctx, Object chain, Map<String, Object> row0) {
        Object n0 = nth(chain, 1);
        Map<String, Object> row = createNode(ctx, n0, row0);
        long prev = boundNode(n0, row);
        for (Object step : asList(nth(chain, 2))) {
            Object rel = nth(step, 0), n = nth(step, 1);
            row = createNode(ctx, n, row);
            long nx = boundNode(n, row);
            row = createRel(ctx, rel, prev, nx, row);
            prev = nx;
        }
        return row;
    }

    static VModel[] updateVertex(Ctx ctx, long vid, java.util.function.UnaryOperator<VModel> f) {
        noteWritten(ctx, vid);
        VModel[] r = tx(ctx).updateVertex(vid, f);
        if (r == null) throw CypherError.exec("node ", GraphModel.vidText(vid), " no longer exists");
        return r;
    }

    /**
     * 模型上的属性赋值：null = 删除；text / embedding 落到正文 / 向量。replace：属性与正文整体换成 new（向量与标签不动，
     * 除非 new 里给了 embedding）。
     */
    static VModel assignProps(VModel m0, Map<?, ?> neu, Object mode) {
        VModel m = mode == REPLACE ? new VModel(m0.labels(), new LinkedHashMap<>(), "", m0.vector()) : m0;
        for (Map.Entry<?, ?> e : neu.entrySet()) {
            Object kk = e.getKey(), v = e.getValue();
            if ("text".equals(kk)) {
                if (v == null) m = m.withText("");
                else if (v instanceof String s) m = m.withText(s);
                else throw CypherError.execc("TypeError", "InvalidArgumentType", "property `text` must be a string");
            } else if ("embedding".equals(kk)) {
                m = m.withVector(v != null ? toF32(v) : null);
            } else if (v == null) {
                Map<String, Object> p = new LinkedHashMap<>(m.props());
                p.remove(kk);
                m = m.withProps(p);
            } else {
                Map<Object, Object> one = new LinkedHashMap<>();
                one.put(kk, v);
                var e2 = modelCall(() -> GraphModel.normProps(one)).entrySet().iterator().next();
                Map<String, Object> p = new LinkedHashMap<>(m.props());
                p.put(e2.getKey(), e2.getValue());
                m = m.withProps(p);
            }
        }
        return m;
    }

    static Map<String, Object> writeRelProps(Ctx ctx, Object v, VRel r, Map<?, ?> props0, Map<String, Object> row) {
        if (props0.containsKey("_raw")) throw CypherError.exec("this relationship has legacy binary properties; they cannot be edited from Cypher");
        Map<Object, Object> props = new LinkedHashMap<>();
        props0.forEach((k, x) -> {
            if (x != null) props.put(k, x);
        });
        byte[] pb = modelCall(() -> GraphModel.encodeEdgeProps(props));
        tx(ctx).putEdge(r.src(), r.etype(), r.dst(), r.rank(), pb);
        ctx.rels.remove(r.id());
        return assoc(row, v, new VRel(r.src(), r.etype(), r.dst(), r.rank(), pb));
    }

    static Map<String, Object> setProp(Ctx ctx, Object v, Object kk, Object val, Map<String, Object> row) {
        Object cur = row.get(v);
        if (cur == null) return row;
        if (cur instanceof VNode n) {
            Map<Object, Object> one = new LinkedHashMap<>();
            one.put(kk, val);
            updateVertex(ctx, n.vid(), m -> assignProps(m, one, MERGE));
            stat(ctx, "properties-set", 1);
            return row;
        }
        if (cur instanceof VRel r) {
            stat(ctx, "properties-set", 1);
            Map<Object, Object> p = new LinkedHashMap<>(relProps(ctx, r));
            p.put(kk, val);
            return writeRelProps(ctx, v, r, p, row);
        }
        throw CypherError.exec("cannot set a property of `", v, "`");
    }

    static Map<String, Object> nodePropsWithSpecials(Ctx ctx, long vid) {
        Map<String, Object> properties = nodeInfo(ctx, vid).properties();
        float[] vector = nodeVector(ctx, vid);
        if (vector == null) return properties;
        Map<String, Object> m = new LinkedHashMap<>(properties);
        m.put("embedding", Expr.floatsToVec(vector));
        return m;
    }

    static Map<String, Object> setItem(Ctx ctx, Object it, Map<String, Object> row) {
        Kw h = Clj.head(it);
        if (h == SET_PROP) return setProp(ctx, nth(it, 1), nth(it, 2), ev(nth(it, 3), row, ctx), row);
        if (h == SET_PROPS) {
            Object v = nth(it, 1), mode = nth(it, 3);
            Object m = ev(nth(it, 2), row, ctx);
            Map<?, ?> neu;
            if (m instanceof VNode n) neu = nodePropsWithSpecials(ctx, n.vid());
            else if (m instanceof VRel r) neu = relProps(ctx, r);
            else if (isPlainMap(m)) neu = (Map<?, ?>) m;
            else if (m == null) neu = Map.of();
            else throw CypherError.exec("SET n = / += needs a map");
            Object cur = row.get(v);
            if (cur == null) return row;
            if (cur instanceof VNode n) {
                updateVertex(ctx, n.vid(), mm -> assignProps(mm, neu, mode));
                stat(ctx, "properties-set", neu.size());
                return row;
            }
            if (cur instanceof VRel r) {
                stat(ctx, "properties-set", neu.size());
                Map<Object, Object> p = mode == REPLACE ? new LinkedHashMap<>() : new LinkedHashMap<>(relProps(ctx, r));
                p.putAll(neu);
                return writeRelProps(ctx, v, r, p, row);
            }
            throw CypherError.exec("cannot set properties of `", v, "`");
        }
        if (h == SET_LABELS) {
            Object v = nth(it, 1);
            List<Object> ls = asList(nth(it, 2));
            Object cur = row.get(v);
            if (cur == null) return row;
            if (cur instanceof VNode n) {
                VModel[] on = updateVertex(ctx, n.vid(), m -> {
                    Set<String> have = new HashSet<>(m.labels());
                    List<String> nl = new ArrayList<>(m.labels());
                    for (Object l : ls) if (!have.contains(l)) nl.add((String) l);
                    return m.withLabels(nl);
                });
                stat(ctx, "labels-added", on[1].labels().size() - on[0].labels().size());
                return row;
            }
            throw CypherError.exec("cannot set labels on `", v, "`");
        }
        throw new IllegalArgumentException("unknown SET item " + h);
    }

    static Map<String, Object> removeItem(Ctx ctx, Object it, Map<String, Object> row) {
        Kw h = Clj.head(it);
        if (h == REMOVE_PROP) return setProp(ctx, nth(it, 1), nth(it, 2), null, row);
        if (h == REMOVE_LABELS) {
            Object v = nth(it, 1);
            Set<Object> ls = Clj.setOf(asList(nth(it, 2)));
            Object cur = row.get(v);
            if (cur == null) return row;
            if (cur instanceof VNode n) {
                VModel[] on = updateVertex(ctx, n.vid(), m -> {
                    List<String> nl = new ArrayList<>();
                    for (String l : m.labels()) if (!ls.contains(l)) nl.add(l);
                    return m.withLabels(nl);
                });
                stat(ctx, "labels-removed", on[0].labels().size() - on[1].labels().size());
                return row;
            }
            throw CypherError.exec("cannot remove labels from `", v, "`");
        }
        throw new IllegalArgumentException("unknown REMOVE item " + h);
    }

    /** 要删的值 ⇒ [[vid …] [关系 id …]]。 */
    static List<List<Object>> deleteTargets(Iterable<?> vals) {
        List<Object> ns = new ArrayList<>(), rs = new ArrayList<>();
        for (Object v : vals) {
            if (v == null) continue;
            if (v instanceof VNode n) ns.add(n.vid());
            else if (v instanceof VRel r) rs.add(r.id());
            else if (v instanceof VPath p) {
                List<Object> xs = new ArrayList<>(p.nodes());
                xs.addAll(p.rels());
                List<List<Object>> ab = deleteTargets(xs);
                ns.addAll(ab.get(0));
                rs.addAll(ab.get(1));
            } else if (v instanceof List<?> l) {
                List<List<Object>> ab = deleteTargets(l);
                ns.addAll(ab.get(0));
                rs.addAll(ab.get(1));
            } else throw CypherError.exec("DELETE needs nodes, relationships or paths");
        }
        return List.of(ns, rs);
    }

    static void deleteAll(Ctx ctx, List<Object> rels, List<Object> nodes, boolean detach) {
        Store.Tx t = tx(ctx);
        Store st = ctx.store;
        for (Object id : new LinkedHashSet<>(rels)) {
            long s = lng(nth(id, 0)), et = lng(nth(id, 1)), d = lng(nth(id, 2)), r = lng(nth(id, 3));
            if (st.edgeProps(s, et, d, r) != null) {
                t.delEdge(s, et, d, r);
                ctx.deleted.add(id);
                retireRank(ctx, id);
                noteWritten(ctx, s);
                noteWritten(ctx, d);
                ctx.rels.remove(id);
                stat(ctx, "relationships-deleted", 1);
            }
        }
        for (Object vo : new LinkedHashSet<>(nodes)) {
            long vid = lng(vo);
            if (st.exists(vid)) {
                LinkedHashSet<Object> incident = new LinkedHashSet<>();
                for (Object h : (Iterable<?>) ExecPaths.edgesOf(ctx, vid, List.of(), OUT)) incident.add(hopRel(h).id());
                for (Object h : (Iterable<?>) ExecPaths.edgesOf(ctx, vid, List.of(), IN)) incident.add(hopRel(h).id());
                if (!incident.isEmpty() && !detach)
                    throw CypherError.execc("ConstraintVerificationFailed", "DeleteConnectedNode", "cannot delete node ", GraphModel.vidText(vid),
                            ": it still has relationships; use DETACH DELETE");
                for (Object id : incident) {
                    noteWritten(ctx, nth(id, 0));
                    noteWritten(ctx, nth(id, 2));
                    ctx.deleted.add(id);
                    retireRank(ctx, id);
                }
                ctx.deleted.add(vid);
                noteWritten(ctx, vid);
                t.deleteVertex(vid);
                stat(ctx, "relationships-deleted", incident.size());
                stat(ctx, "nodes-deleted", 1);
            }
        }
    }

    // ═══ MERGE ═══

    static Map<String, Object> applySets(Ctx ctx, Object items, Map<String, Object> row) {
        for (Object it : asList(items)) row = setItem(ctx, it, row);
        return row;
    }

    /** MERGE 按值匹配：属性里不能有 null（null 永远匹配不上，每次都会新建一个）。 */
    static void mergePropsCheck(Ctx ctx, Object pe, Map<String, Object> row) {
        for (Map.Entry<Object, Object> e : propsArg(ctx, pe, row).entrySet())
            if (e.getValue() == null)
                throw CypherError.execc("SemanticError", "MergeReadOwnWrites", "cannot MERGE with a null value for property `", e.getKey(), "`");
    }

    static List<Map<String, Object>> runOps(Ctx ctx, Object ops, Map<String, Object> row) {
        Iterable<Map<String, Object>> in = one(row);
        for (Object o : asList(ops)) in = op(ctx, o, in);
        return vecRows(in);
    }

    private static byte[] bumpGuard(byte[] cur) {
        return GraphKeys.u64Bytes((cur != null ? GraphKeys.bytesToU64(cur) : 0) + 1);
    }

    /**
     * MERGE (v:L {…})：按标签（没有标签就全量）+ 属性找；找到几个就交出几行（各自套 ON MATCH），一个都没有就建一个（套 ON CREATE）。
     * 找之前读 MERGE 守卫（登记版本），新建时写它：并发的同一个 MERGE 不会重复创建。
     */
    static List<Map<String, Object>> mergeNode(Ctx ctx, List<?> args, Map<String, Object> row) {
        Object v = nth(args, 0), pe = nth(args, 2), oc = nth(args, 3), om = nth(args, 4);
        List<Object> labels = asList(nth(args, 1));
        mergePropsCheck(ctx, pe, row);
        Store.Tx t = tx(ctx);
        byte[] guard = GraphKeys.mergeGuard(labels.isEmpty() ? null : (String) labels.getFirst());
        byte[] cur = t.readRaw(guard);
        List<Object> pes = pe != null ? Clj.vec(pe) : Clj.vec();
        List<Object> ops = !labels.isEmpty()
                ? Clj.vec(Clj.vec(LABEL_SCAN, v, labels.getFirst()), Clj.vec(NODE_CHECK, v, Clj.subvec(labels, 1), pes))
                : Clj.vec(Clj.vec(ALL_NODES_SCAN, v), Clj.vec(NODE_CHECK, v, Clj.vec(), pes));
        List<Map<String, Object>> found = runOps(ctx, ops, row);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!found.isEmpty()) {
            for (Map<String, Object> r : found) out.add(applySets(ctx, om, r));
            return out;
        }
        Map<String, Object> r1 = createNode(ctx, Clj.vec(K_NODE, v, labels, pe), row);
        t.write("put", guard, bumpGuard(cur));
        out.add(applySets(ctx, oc, r1));
        return out;
    }

    /**
     * MERGE (a)-[r:T {…}]->(b)：两端已绑定；找 a、b 之间这种类型、属性相符的边，没有就建一条（写会话对 a 的头带版本条件，
     * 并发的同一个 MERGE 不会重复创建）。
     */
    static List<Map<String, Object>> mergeRel(Ctx ctx, List<?> args, Map<String, Object> row) {
        Object from = nth(args, 0), r = nth(args, 1), typ = nth(args, 2), pe = nth(args, 3), dir = nth(args, 4), to = nth(args, 5),
                oc = nth(args, 6), om = nth(args, 7);
        mergePropsCheck(ctx, pe, row);
        for (Object nv : List.of(from, to))
            if (!isNode(row.get(nv))) throw CypherError.execc("SemanticError", null, "MERGE needs `", nv, "` to be a node (it is null)");
        List<Object> ops = Clj.vec(Clj.vec(EXPAND, from, r, Clj.vec(typ), dir, to, true, Clj.vec()));
        if (pe != null) ops.add(Clj.vec(REL_CHECK, r, pe));
        List<Map<String, Object>> found = runOps(ctx, ops, row);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!found.isEmpty()) {
            for (Map<String, Object> x : found) out.add(applySets(ctx, om, x));
            return out;
        }
        out.add(applySets(ctx, oc, createRel(ctx, Clj.vec(K_REL, r, Clj.vec(typ), pe, dir), ((VNode) row.get(from)).vid(),
                ((VNode) row.get(to)).vid(), row)));
        return out;
    }

    /**
     * 整条模式的 MERGE：查找子流水线有结果 ⇒ 逐行套 ON MATCH；没有 ⇒ 整条 CREATE 再套 ON CREATE。
     * 查找之前读模式里每个未绑定节点的 MERGE 守卫（第一个标签；没有标签用空标签），新建时写：并发的同一个 MERGE 不重复创建。
     */
    static List<Map<String, Object>> mergePattern(Ctx ctx, List<?> args, Map<String, Object> row) {
        Object find = nth(args, 0), chain = nth(args, 1), oc = nth(args, 2), om = nth(args, 3);
        Object n0 = nth(chain, 1);
        List<Object> steps = asList(nth(chain, 2));
        List<Object> nodes = new ArrayList<>();
        nodes.add(n0);
        for (Object s : steps) nodes.add(nth(s, 1));
        Store.Tx t = tx(ctx);
        for (Object n : nodes) mergePropsCheck(ctx, nth(n, 3), row);
        for (Object s : steps) mergePropsCheck(ctx, nth(nth(s, 0), 3), row);
        for (Object n : nodes) {
            Object v = nth(n, 1);
            if (row.containsKey(v) && row.get(v) == null)
                throw CypherError.execc("SemanticError", null, "MERGE needs `", v, "` to be a node (it is null)");
        }
        LinkedHashMap<java.nio.ByteBuffer, byte[]> gs = new LinkedHashMap<>();
        for (Object n : nodes) {
            if (isNode(row.get(nth(n, 1)))) continue;
            byte[] g = GraphKeys.mergeGuard((String) Clj.first(nth(n, 2)));
            gs.putIfAbsent(java.nio.ByteBuffer.wrap(g), g);
        }
        List<byte[]> guards = new ArrayList<>(gs.values());
        List<byte[]> curs = new ArrayList<>();
        for (byte[] g : guards) curs.add(t.readRaw(g));
        List<Map<String, Object>> found = runOps(ctx, find, row);
        List<Map<String, Object>> out = new ArrayList<>();
        if (!found.isEmpty()) {
            for (Map<String, Object> x : found) out.add(applySets(ctx, om, x));
            return out;
        }
        Map<String, Object> r1 = createChain(ctx, chain, row);
        for (int i = 0; i < guards.size(); i++) t.write("put", guards.get(i), bumpGuard(curs.get(i)));
        out.add(applySets(ctx, oc, r1));
        return out;
    }

    // ═══ 全文 ═══

    static String scoreKey(Object v) {
        return "  score " + Clj.str(v);
    }

    static Object ftsScore(Ctx ctx, Object v, Map<String, Object> row) {
        String sk = scoreKey(v);
        if (row.containsKey(sk)) return row.get(sk);
        Object n = row.get(v);
        return n instanceof VNode vn ? ctx.ftsScores.get(Arrays.asList(v, vn.vid())) : null;
    }

    static List<Object> ftsWord(String w) {
        if (w.endsWith("*") && w.length() > 1) return Clj.vec(PREFIX, w.substring(0, w.length() - 1));
        if (w.endsWith("~") && w.length() > 1) return Clj.vec(FUZZY, w.substring(0, w.length() - 1));
        return Clj.vec(TERM, w);
    }

    static List<Object> ftsTokens(String q) {
        List<Object> acc = new ArrayList<>();
        int n = q.length();
        int i = 0;
        while (i < n) {
            char c = q.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || (c == '-' && i + 1 < n && q.charAt(i + 1) == '"')) {
                boolean negated = c == '-';
                int st = negated ? i + 2 : i + 1;
                int e = q.indexOf('"', st);
                if (e < 0) e = n;
                List<Object> item = Clj.vec(PHRASE, q.substring(st, e));
                i = Math.min(n, e + 1);
                acc.add(negated ? Clj.vec(NEG, item) : item);
            } else {
                int e = i;
                while (e < n && !Character.isWhitespace(q.charAt(e))) e++;
                String w = q.substring(i, e);
                i = e;
                if (w.equals("OR")) acc.add(OR);
                else if (w.startsWith("-") && w.length() > 1) acc.add(Clj.vec(NEG, ftsWord(w.substring(1))));
                else acc.add(ftsWord(w));
            }
        }
        return acc;
    }

    /** 查询串 ⇒ [[[项 …] …] [排除项 …]]（AND 组，每组是 OR 的备选）。 */
    static List<Object> ftsParse(String q) {
        List<Object> ts = ftsTokens(q);
        List<List<Object>> groups = new ArrayList<>();
        List<Object> neg = new ArrayList<>();
        int i = 0;
        while (i < ts.size()) {
            Object t = ts.get(i++);
            if (t instanceof List<?> && Clj.first(t) == NEG) neg.add(Clj.second(t));
            else if (t == OR) {
                Object nx = i < ts.size() ? ts.get(i) : null;
                if (!groups.isEmpty() && nx != null && nx != OR && Clj.first(nx) != NEG) {
                    i++;
                    groups.getLast().add(nx);
                } else throw CypherError.exec("full-text query: OR needs a term on both sides");
            } else groups.add(Clj.vec(t));
        }
        if (groups.isEmpty()) throw CypherError.exec("full-text query has no positive terms");
        return List.of(groups, neg);
    }

    private static BitcaskIndex idx(Ctx ctx) {
        return (BitcaskIndex) db(ctx);
    }

    private static final Set<String> INDEX_CODES = Set.of("bitcask/dim-mismatch", "bitcask/query-dim-mismatch", "bitcask/no-index");

    /** 检索要检索库（open-index）；向量检索另要库配置了维度。引擎的参数错误（维度不符等）⇒ 执行错误。 */
    static <T> T indexCall(Ctx ctx, Supplier<T> f) {
        Bitcask d = db(ctx);
        if (!(d instanceof BitcaskIndex bi && bi.isIndex()))
            throw CypherError.exec("full-text and vector search need the graph opened with open-index");
        try {
            return f.get();
        } catch (BitcaskException e) {
            if (INDEX_CODES.contains(e.code())) throw CypherError.exec("vector search failed: ", e.getMessage());
            throw e;
        }
    }

    private static Long hitVid(String key) {
        return key == null ? null : GraphKeys.parseNode(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 命中 ⇒ {vid 分数}。 */
    static Map<Long, Double> vertexHits(List<?> hits) {
        Map<Long, Double> m = new LinkedHashMap<>();
        for (Object h : hits) {
            String key;
            double score;
            if (h instanceof SearchHit sh) {
                key = sh.key();
                score = sh.score();
            } else {
                FieldsHit fh = (FieldsHit) h;
                key = fh.key();
                score = fh.score();
            }
            Long v = hitVid(key);
            if (v != null) m.put(v, score);
        }
        return m;
    }

    static Map<Long, Double> fetchAll(Ctx ctx, IntFunction<List<?>> f) {
        long k = 1024;
        while (true) {
            final int kk = (int) k;
            List<?> hits = indexCall(ctx, () -> f.apply(kk));
            if (hits.size() < k) return vertexHits(hits);
            if (k >= SEARCH_CAP)
                throw CypherError.execc("ArgumentError", "NumberOutOfRange", "full-text term matches more than 1000000 documents; narrow the query");
            k = Math.min(k * 2, SEARCH_CAP);
        }
    }

    static Map<Long, Double> ftsItem(Ctx ctx, Object item) {
        Object kind = nth(item, 0);
        String w = (String) nth(item, 1);
        if (kind == TERM) return fetchAll(ctx, k -> idx(ctx).searchText(w, SearchOptions.k(k)));
        if (kind == PHRASE) return fetchAll(ctx, k -> idx(ctx).searchPhrase(w, SearchOptions.k(k)));
        if (kind == PREFIX) return fetchAll(ctx, k -> idx(ctx).searchWildcard(w + "*", SearchOptions.k(k)));
        if (kind == FUZZY) return fetchAll(ctx, k -> idx(ctx).searchFuzzy(w, SearchOptions.k(k).maxEdits(1)));
        throw new IllegalArgumentException(String.valueOf(kind));
    }

    @SuppressWarnings("unchecked")
    static Map<Long, Double> ftsSet(Ctx ctx, Object prop, Object q) {
        if (!"text".equals(prop))
            throw CypherError.exec("full-text matching works on the node text (n.text @@ '...'); `", prop, "` is not a full-text property");
        return (Map<Long, Double>) cacheGet(ctx.fts, q, () -> {
            List<Object> gn = ftsParse((String) q);
            List<List<Object>> groups = (List<List<Object>>) gn.get(0);
            List<Object> neg = (List<Object>) gn.get(1);
            List<Map<Long, Double>> sets = new ArrayList<>();
            for (List<Object> grp : groups) {
                Map<Long, Double> acc = new LinkedHashMap<>();
                for (Object i : grp) ftsItem(ctx, i).forEach((kk, v) -> acc.merge(kk, v, Math::max));
                sets.add(acc);
            }
            Map<Long, Double> inter = sets.getFirst();
            for (Map<Long, Double> s : sets.subList(1, sets.size())) {
                Map<Long, Double> nx = new LinkedHashMap<>();
                for (var e : inter.entrySet()) {
                    Double v2 = s.get(e.getKey());
                    if (v2 != null) nx.put(e.getKey(), e.getValue() + v2);
                }
                inter = nx;
            }
            Map<Long, Double> out = new LinkedHashMap<>(inter);
            for (Object ni : neg) out.keySet().removeAll(ftsItem(ctx, ni).keySet());
            return out;
        });
    }

    static boolean ftsMatch(Ctx ctx, Object v, Object vid, Object prop, Object q) {
        Double sc = ftsSet(ctx, prop, q).get(lng(vid));
        if (sc != null) {
            ctx.ftsScores.put(Arrays.asList(v, lng(vid)), sc);
            return true;
        }
        return false;
    }

    // 检索的函数形式：fts.phrase(n.text, q)、fts.near(n.text, q, slop)、fts.wildcard(n.text, pat)、fts.fuzzy(n.text, w[, edits])、
    // fts.fields(n, q)：取引擎的全部命中；search.hybrid(n, text, vec[, k])：引擎的混合检索（RRF）取前 k（缺省 10）。
    // 作为表达式按顶点判断是否命中（并记下分数给 score(n)）；作为 WHERE 合取项由规划器钉成起点（:search-seek）。

    static Map<Long, Double> searchEval(Ctx ctx, Object nm, List<?> vals) {
        Object a = nth(vals, 0), b = nth(vals, 1), c = nth(vals, 2);
        Supplier<CypherError> bad = () -> CypherError.execc("TypeError", "InvalidArgumentType", "invalid arguments to ", nm, "()");
        switch (String.valueOf(nm)) {
            case "fts.phrase" -> {
                if (a instanceof String s) return fetchAll(ctx, k -> idx(ctx).searchPhrase(s, SearchOptions.k(k)));
                throw bad.get();
            }
            case "fts.near" -> {
                if (a instanceof String s && Clj.isInteger(b) && lng(b) >= 0)
                    return fetchAll(ctx, k -> idx(ctx).searchNear(s, SearchOptions.k(k).slop((int) lng(b))));
                throw bad.get();
            }
            case "fts.wildcard" -> {
                if (a instanceof String s) return fetchAll(ctx, k -> idx(ctx).searchWildcard(s, SearchOptions.k(k)));
                throw bad.get();
            }
            case "fts.fuzzy" -> {
                Object ed = b != null ? b : (Object) 1L;
                if (a instanceof String s && Clj.isInteger(ed) && 0 <= lng(ed) && lng(ed) <= 2)
                    return fetchAll(ctx, k -> idx(ctx).searchFuzzy(s, SearchOptions.k(k).maxEdits((int) lng(ed))));
                throw bad.get();
            }
            case "fts.fields" -> {
                if (a instanceof String s) return fetchAll(ctx, k -> idx(ctx).searchFields(s, SearchOptions.k(k)));
                throw bad.get();
            }
            case "search.hybrid" -> {
                Object k = c != null ? c : (Object) 10L;
                if (a instanceof String s && Clj.isInteger(k) && lng(k) > 0) {
                    float[] q = toF32(b);
                    int kk = (int) Math.min(lng(k), SEARCH_CAP);
                    return vertexHits(indexCall(ctx, () -> idx(ctx).searchHybrid(s, q, SearchOptions.k(kk))));
                }
                throw bad.get();
            }
            default -> throw new IllegalArgumentException(String.valueOf(nm));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<Long, Double> searchSet(Ctx ctx, Object nm, Object vals) {
        return (Map<Long, Double>) cacheGet(ctx.fts, Arrays.asList(nm, vals), () -> searchEval(ctx, nm, asList(vals)));
    }

    /** 检索函数按顶点判断 ⇒ Boolean；有参数是 null ⇒ null。 */
    static Object searchMatch(Ctx ctx, Object v, Object vid, Object nm, Object vals) {
        for (Object x : asList(vals)) if (x == null) return null;
        Double sc = searchSet(ctx, nm, vals).get(lng(vid));
        if (sc != null) {
            ctx.ftsScores.put(Arrays.asList(v, lng(vid)), sc);
            return true;
        }
        return false;
    }

    // ═══ 向量 ═══

    static List<Object> vkey(float[] q) {
        List<Object> l = new ArrayList<>(q.length);
        for (float f : q) l.add(f);
        return l;
    }

    /** ⇒ [命中数 [[vid 距离] …]]。 */
    static List<Object> searchVec(Ctx ctx, float[] q, long k, Object label) {
        SearchOptions o = SearchOptions.k((int) k);
        if (label != null) o.filter(Filter.contains(GraphModel.LABELS_KEY, (String) label));
        List<SearchHit> hits = indexCall(ctx, () -> idx(ctx).searchVector(q, o));
        List<Object> vs = new ArrayList<>();
        List<Object> qk = vkey(q);
        for (SearchHit h : hits) {
            Long v = hitVid(h.key());
            if (v != null) {
                double dd = -h.score();
                vs.add(List.of(v, dd));
                ctx.vdist.put(List.of(v, qk), dd);
            }
        }
        return List.of((long) hits.size(), vs);
    }

    static void fillDistances(Ctx ctx, List<Long> vids, float[] q) {
        List<Object> keys = new ArrayList<>();
        for (Long v : vids) keys.add(GraphKeys.nodeBytes(v));
        List<SearchHit> hits = indexCall(ctx, () -> idx(ctx).rankByVector(q, keys, vids.size()));
        Map<Long, Double> got = new HashMap<>();
        for (SearchHit h : hits) {
            Long v = hitVid(h.key());
            if (v != null) got.put(v, -h.score());
        }
        List<Object> qk = vkey(q);
        for (Long v : vids) ctx.vdist.put(List.of(v, qk), got.get(v));
    }

    static Object vectorDistance(Ctx ctx, Object vid, Object qv) {
        float[] q = toF32(qv);
        List<Object> kk = List.of(lng(vid), vkey(q));
        if (!ctx.vdist.containsKey(kk)) fillDistances(ctx, List.of(lng(vid)), q);
        return ctx.vdist.get(kk);
    }

    static void exhaustive(Ctx ctx, boolean b) {
        Object cur = ctx.stats.get("vector-exhaustive");
        ctx.stats.put("vector-exhaustive", !b ? Boolean.FALSE : cur == null ? Boolean.TRUE : cur);
    }

    @SuppressWarnings("unchecked")
    static List<Long> vectorRange(Ctx ctx, float[] q, Object t, Object label) {
        double td = ((Number) t).doubleValue();
        long k = 64;
        while (true) {
            List<Object> r = searchVec(ctx, q, k, label);
            long n = lng(r.get(0));
            List<Object> hits = (List<Object>) r.get(1);
            boolean done = n < k || hits.isEmpty() || ((Number) nth(hits.getLast(), 1)).doubleValue() >= td;
            if (done || k >= SEARCH_CAP) {
                exhaustive(ctx, done);
                List<Long> out = new ArrayList<>();
                for (Object h : hits) if (((Number) nth(h, 1)).doubleValue() <= td) out.add(lng(nth(h, 0)));
                return out;
            }
            k = Math.min(k * 4, SEARCH_CAP);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Long> vectorTopk(Ctx ctx, float[] q, long k, Object label, Predicate<Long> pass) {
        if (k == 0) return new ArrayList<>();
        long cap = Math.min(SEARCH_CAP, Math.max(k * 64, 1024));
        long ask = Math.min(cap, Math.max(k * 2, 16));
        while (true) {
            List<Object> r = searchVec(ctx, q, ask, label);
            long n = lng(r.get(0));
            List<Long> ok = new ArrayList<>();
            for (Object h : (List<Object>) r.get(1)) {
                long v = lng(nth(h, 0));
                if (pass.test(v)) ok.add(v);
            }
            if (ok.size() >= k || n < ask) {
                exhaustive(ctx, true);
                return new ArrayList<>(ok.subList(0, (int) Math.min(k, ok.size())));
            }
            if (ask >= cap) {
                exhaustive(ctx, false);
                return ok;
            }
            ask = Math.min(cap, ask * 4);
        }
    }

    /** ORDER BY v.embedding <=> q：排序前对全部行的顶点一次批量算距离。 */
    static void prefetchDistances(Ctx ctx, Object keys, List<Map<String, Object>> rows) {
        for (Object key : asList(keys)) {
            Object e = nth(key, 0);
            if (!(e instanceof List<?>) || Clj.first(e) != OP || Clj.second(e) != SPACESHIP) continue;
            Object lhs = nth(e, 2), qe = nth(e, 3);
            if (!(Clj.first(lhs) == PROP && Clj.first(Clj.second(lhs)) == VAR && "embedding".equals(nth(lhs, 2))
                    && Clj.isEmpty(Sema.exprVars(qe))))
                continue;
            Object qv = ev(qe, new HashMap<>(), ctx);
            if (qv == null) continue;
            float[] q = toF32(qv);
            Object v = Clj.second(Clj.second(lhs));
            List<Object> qk = vkey(q);
            LinkedHashSet<Long> vids = new LinkedHashSet<>();
            for (Map<String, Object> row : rows)
                if (row.get(v) instanceof VNode n && !ctx.vdist.containsKey(List.of(n.vid(), qk))) vids.add(n.vid());
            if (!vids.isEmpty()) fillDistances(ctx, new ArrayList<>(vids), q);
        }
    }

    // ═══ 条件下推 ═══

    static boolean isScalar(Object v) {
        return v instanceof Long || v instanceof Double || v instanceof String || v instanceof Boolean;
    }

    /** 等值的数值两种写法：整数 c 也查浮点 c（能精确表示时）；整数值的浮点也查对应整数。 */
    static List<Object> numVariants(Object v) {
        if (v instanceof Long l) {
            double f = (double) l;
            return (long) f == l ? Clj.vec(v, f) : Clj.vec(v);
        }
        if (v instanceof Double d) return d == Math.rint(d) && Math.abs(d) < 9.2e18 ? Clj.vec(v, (long) (double) d) : Clj.vec(v);
        return Clj.vec(v);
    }

    private static Kw strictOp(Object op) {
        return op == LT ? F_LT : op == LE ? F_LE : op == GT ? F_GT : F_GE;
    }

    private static Kw looseOp(Object op) {
        return op == LT || op == LE ? F_LE : F_GE;
    }

    static List<Object> cmpAlts(Object kk, Object op, Object v) {
        if (op == EQ) {
            if (!isScalar(v)) return Clj.vec();
            List<Object> out = new ArrayList<>();
            for (Object x : numVariants(v)) out.add(Clj.vec(F_EQ, kk, x));
            return out;
        }
        if (v instanceof Long l) return Clj.vec(Clj.vec(strictOp(op), kk, v), Clj.vec(looseOp(op), kk, (double) l));
        if (v instanceof Double d) {
            double b = op == LT || op == LE ? Math.ceil(d) : Math.floor(d);
            return Clj.vec(Clj.vec(strictOp(op), kk, v),
                    Clj.vec(looseOp(op), kk, (long) Math.max(-9.223372036854776E18, Math.min(9.223372036854776E18, b))));
        }
        return Clj.vec();
    }

    /** [op kk x] ⇒ 引擎过滤条件。 */
    private static Filter cond(Object alt) {
        Object o = nth(alt, 0);
        String kk = (String) nth(alt, 1);
        Object x = nth(alt, 2);
        if (o == F_EQ) return Filter.eq(kk, x);
        if (o == F_LT) return Filter.lt(kk, x);
        if (o == F_LE) return Filter.lte(kk, x);
        if (o == F_GT) return Filter.gt(kk, x);
        return Filter.gte(kk, x);
    }

    /**
     * push ⇒ [引擎 meta 过滤 strict?]。每个条件都放宽成 Cypher 条件的必要条件：引擎里整数与浮点不互相比较、1 与 1.0 不相等，
     * 所以数值两边都查；跨类型的大小比较放宽到含等号；字符串不比大小；值是 null / 列表 / 映射的条件不下推。
     * strict? = 至少下推了一个属性条件（range 没交出来的 vid 一定不满足）。
     */
    static List<Object> engineFilter(Ctx ctx, Object push, Map<String, Object> row) {
        List<Filter> conds = new ArrayList<>();
        boolean strict = false;
        for (Object p : asList(push)) {
            Object kind = nth(p, 0), kk = nth(p, 1), op = nth(p, 2), e = nth(p, 3);
            if (kind != HAS_LABEL) strict = true;
            if (kind == HAS_LABEL) conds.add(Filter.contains(GraphModel.LABELS_KEY, (String) kk));
            else if (kind == HAS_ANY_LABEL) {
                List<String> ls = new ArrayList<>();
                for (Object l : asList(kk)) ls.add((String) l);
                conds.add(Filter.containsAny(GraphModel.LABELS_KEY, ls));
            } else if (kind == EXISTS) conds.add(Filter.exists((String) kk));
            else if (kind == CMP) {
                List<Object> alts = cmpAlts(kk, op, ev(e, row, ctx));
                if (alts.size() == 1) conds.add(cond(alts.getFirst()));
                else if (alts.size() > 1) {
                    List<Filter> fs = new ArrayList<>();
                    for (Object a : alts) fs.add(cond(a));
                    conds.add(Filter.or(fs));
                }
            } else if (kind == IN) {
                Object l = ev(op, row, ctx);
                if (l instanceof List<?> ll && !ll.isEmpty() && ll.stream().allMatch(Exec::isScalar)) {
                    LinkedHashSet<Object> vs = new LinkedHashSet<>();
                    for (Object x : ll) vs.addAll(numVariants(x));
                    conds.add(Filter.in((String) kk, new ArrayList<>(vs)));
                }
            }
        }
        return Arrays.asList(conds.isEmpty() ? null : Filter.and(conds), strict);
    }

    /** range 读回的文档 ⇒ 顶点信息进查询内缓存（向量不随 range 读回，标 UNKNOWN）。⇒ vid 集合。 */
    static Set<Long> cacheDocs(Ctx ctx, List<Entry> entries) {
        Set<Long> out = new HashSet<>();
        for (Entry e : entries) {
            Long vid = GraphKeys.parseNode(e.key());
            if (vid != null) {
                GraphModel.DocInfo di = GraphModel.docInfo(new GraphModel.Doc(new String(e.value(), StandardCharsets.UTF_8), e.meta(), null, 0));
                ctx.nodes.put(vid, new NodeInfo(di.labels(), di.properties(), NodeInfo.UNKNOWN));
                out.add(vid);
            }
        }
        return out;
    }

    static List<Entry> rangeDocs(Ctx ctx, byte[] lo, byte[] hi, Filter filter) {
        RangeOptions o = RangeOptions.between(lo, hi).meta(true).batch(Store.CHUNK);
        if (filter != null) o.filter(filter);
        return db(ctx).rangeEntries(o);
    }

    /** 标签索引的一块 vid：够密（跨度不超过个数的 4 倍）就对这一段 vid 做一次带过滤的 range、文档进缓存；太稀疏的照旧逐个读。 */
    static List<Long> fetchChunk(Ctx ctx, List<Long> vs, Filter filter, boolean strict) {
        if (vs.isEmpty()) return vs;
        long mn = vs.getFirst(), mx = vs.getLast();
        if (Long.compareUnsigned(mx - mn + 1, 4L * vs.size()) > 0) return vs;
        byte[] hi = mx == GraphModel.VID_MAX ? GraphKeys.successor(GraphKeys.familyBytes("n")) : GraphKeys.familyVid("n", mx + 1);
        Set<Long> got = cacheDocs(ctx, rangeDocs(ctx, GraphKeys.familyVid("n", mn), hi, filter));
        if (!strict) return vs;
        List<Long> out = new ArrayList<>();
        for (Long v : vs) if (got.contains(v)) out.add(v);
        return out;
    }

    /** 分块拉取的惰性序列：step(after) ⇒ [这一块交出的 vid, 下一块的 after（null = 没有了）]；第一块 after = null。 */
    private static Iterable<Long> chunks(Function<Long, Object[]> step) {
        return () -> new Iterator<>() {
            Iterator<Long> cur = Collections.emptyIterator();
            Long after = null;
            boolean more = true;

            @Override
            @SuppressWarnings("unchecked")
            public boolean hasNext() {
                while (!cur.hasNext()) {
                    if (!more) return false;
                    Object[] r = step.apply(after);
                    cur = ((List<Long>) r[0]).iterator();
                    after = (Long) r[1];
                    more = after != null;
                }
                return true;
            }

            @Override
            public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                return cur.next();
            }
        };
    }

    static Iterable<Long> pushedLabelVids(Ctx ctx, Object label, Filter filter, boolean strict) {
        return chunks(after -> {
            List<Long> vs = GraphModel.labelVids(db(ctx), label, Store.CHUNK, after);
            if (vs.isEmpty()) return new Object[]{vs, null};
            return new Object[]{fetchChunk(ctx, vs, filter, strict), vs.size() == Store.CHUNK ? vs.getLast() : null};
        });
    }

    static Iterable<Long> scannedDocVids(Ctx ctx, Filter filter) {
        return chunks(after -> {
            if (after != null && after == GraphModel.VID_MAX) return new Object[]{List.of(), null};
            byte[] lo = after != null ? GraphKeys.familyVid("n", after + 1) : GraphKeys.familyBytes("n");
            RangeOptions o = RangeOptions.between(lo, GraphKeys.successor(GraphKeys.familyBytes("n"))).meta(true).batch(Store.CHUNK);
            if (filter != null) o.filter(filter);
            List<Entry> es = new ArrayList<>();
            db(ctx).forEachRange(o, e -> {
                es.add(e);
                return es.size() < Store.CHUNK;
            });
            cacheDocs(ctx, es);
            List<Long> vs = new ArrayList<>();
            for (Entry e : es) {
                Long v = GraphKeys.parseNode(e.key());
                if (v != null) vs.add(v);
            }
            if (vs.isEmpty()) return new Object[]{vs, null};
            return new Object[]{vs, es.size() == Store.CHUNK ? vs.getLast() : null};
        });
    }

    // ═══ 算子 ═══

    @SuppressWarnings("unchecked")
    static boolean isPropsMatch(Ctx ctx, Object props, Object pes, Object row0) {
        Map<String, Object> row = (Map<String, Object>) row0;
        Map<?, ?> pm = (Map<?, ?>) props;
        for (Object pe : asList(pes)) {
            Object m = ev(pe, row, ctx);
            if (isPlainMap(m)) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) m).entrySet())
                    if (!Boolean.TRUE.equals(Expr.eq(pm == null ? null : pm.get(e.getKey()), e.getValue()))) return false;
            } else if (m == null) return false;
            else throw CypherError.execc("TypeError", "InvalidArgumentType", "pattern properties must be a map");
        }
        return true;
    }

    static boolean isNodeOk(Ctx ctx, Object vid, Object labels, Object pes, Object row) {
        NodeInfo info = nodeInfo(ctx, vid);
        Set<Object> hs = new HashSet<>(info.labels());
        for (Object l : asList(labels)) if (!Expr.isLabelOk(l, hs)) return false;
        return isPropsMatch(ctx, info.properties(), pes, row);
    }

    /** 一个排序键：方向 :asc | :desc | [方向 :first|:last]。NULLS FIRST / LAST 时 null 的位置与方向无关。 */
    static long keyCompare(Object a, Object b, Object d) {
        if (d instanceof List<?>) {
            Object dd = nth(d, 0), nulls = nth(d, 1);
            if (a == null && b == null) return 0;
            if (a == null) return nulls == FIRST ? -1 : 1;
            if (b == null) return nulls == FIRST ? 1 : -1;
            return keyCompare(a, b, dd);
        }
        long c = ((Number) Expr.orderCompare(a, b)).longValue();
        return d == ASC ? c : -c;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> sortKeyed(Ctx ctx, Object keys, List<Map<String, Object>> rows) {
        List<Object> ks = asList(keys);
        List<Object> dirs = new ArrayList<>();
        for (Object k : ks) dirs.add(nth(k, 1));
        List<Object[]> keyed = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object[] kv = new Object[ks.size()];
            for (int i = 0; i < kv.length; i++) kv[i] = ev(nth(ks.get(i), 0), row, ctx);
            keyed.add(new Object[]{kv, row});
        }
        Comparator<Object[]> cmp = (x, y) -> {
            Object[] ka = (Object[]) x[0], kb = (Object[]) y[0];
            for (int i = 0; i < ka.length; i++) {
                long c = keyCompare(ka[i], kb[i], dirs.get(i));
                if (c != 0) return c < 0 ? -1 : 1;
            }
            return 0;
        };
        keyed.sort(cmp);
        List<Map<String, Object>> out = new ArrayList<>(keyed.size());
        for (Object[] x : keyed) out.add((Map<String, Object>) x[1]);
        return out;
    }

    /** 聚合调用在一行上的输入：count(*) ⇒ true；percentileCont / Disc ⇒ [值 百分位]；其余 ⇒ 第一个参数的值。 */
    static Object aggArg(Ctx ctx, Object args, Map<String, Object> row) {
        if (args == null) return true;
        List<Object> a = asList(args);
        if (a.size() == 2) return Clj.vec(ev(a.get(0), row, ctx), ev(a.get(1), row, ctx));
        return ev(Clj.first(a), row, ctx);
    }

    private static boolean isCountStar(Object a) {
        return a == COUNT_STAR || Clj.first(a) == COUNT_STAR;
    }

    private static Object aggArgsOf(Object a) {
        return isCountStar(a) ? null : nth(a, 3);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowMap(Map<Object, Object> m) {
        return (Map<String, Object>) (Map<?, ?>) m;
    }

    /** 隐式分组聚合。没有分组键时走快路径：一组累加器直接归约（不建分组表、不算分组键）。 */
    static List<Map<String, Object>> aggregate(Ctx ctx, Object items0, List<Map<String, Object>> rows) {
        List<Object> items = asList(items0);
        for (Object it : items) if (!Sema.isContainsAggregate(nth(it, 1))) return aggregateGeneral(ctx, items, rows);
        List<Object[]> aggItems = new ArrayList<>();
        long base = 0;
        List<Object> specs = new ArrayList<>();
        for (Object it : items) {
            Object e = nth(it, 1);
            List<Object> sp = asList(Expr.findAggs(e));
            List<Object> r = asList(Expr.replaceAggs(e, base));
            aggItems.add(new Object[]{nth(it, 0), r.get(0)});
            specs.addAll(sp);
            base = lng(r.get(1));
        }
        int n = specs.size();
        Object[] accs = new Object[n], args = new Object[n];
        for (int i = 0; i < n; i++) {
            accs[i] = Expr.aggInit(specs.get(i));
            args[i] = aggArgsOf(specs.get(i));
        }
        for (Map<String, Object> row : rows)
            for (int i = 0; i < n; i++) accs[i] = Expr.aggStep(accs[i], aggArg(ctx, args[i], row));
        Map<Object, Object> refs = new HashMap<>();
        for (int i = 0; i < n; i++) refs.put(Clj.vec(AGG_REF, (long) i), Expr.aggFinal(accs[i]));
        Map<String, Object> out = new HashMap<>();
        for (Object[] ai : aggItems) out.put((String) ai[0], ev(ai[1], rowMap(refs), ctx));
        List<Map<String, Object>> res = new ArrayList<>();
        res.add(out);
        return res;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> aggregateGeneral(Ctx ctx, Object items0, List<Map<String, Object>> rows) {
        List<Object> items = asList(items0);
        List<Object> plain = new ArrayList<>();
        for (Object it : items) if (!Sema.isContainsAggregate(nth(it, 1))) plain.add(it);
        List<Object[]> aggItems = new ArrayList<>();
        List<Object[]> specs = new ArrayList<>(); // [i a]
        long base = 0;
        for (Object it : items) {
            Object e = nth(it, 1);
            if (Sema.isContainsAggregate(e)) {
                List<Object> sp = asList(Expr.findAggs(e));
                List<Object> r = asList(Expr.replaceAggs(e, base));
                long nb = lng(r.get(1));
                for (long i = base; i < nb; i++) specs.add(new Object[]{i, sp.get((int) (i - base))});
                aggItems.add(new Object[]{nth(it, 0), r.get(0)});
                base = nb;
            }
        }
        Object[] init = new Object[specs.size()];
        for (int i = 0; i < init.length; i++) init[i] = Expr.aggInit(specs.get(i)[1]);
        Map<Object, Object[]> groups = new HashMap<>(); // 键 ⇒ [vals accs first-row]
        List<Object> order = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<Object[]> vals = new ArrayList<>();
            List<Object> kk = new ArrayList<>();
            for (Object p : plain) {
                Object v = ev(nth(p, 1), row, ctx);
                vals.add(new Object[]{nth(p, 0), v});
                kk.add(Expr.groupKey(v));
            }
            Object[] cur = groups.get(kk);
            Object[] st = cur != null ? cur : new Object[]{vals, init, row};
            Object[] accs = ((Object[]) st[1]).clone();
            for (int i = 0; i < accs.length; i++) accs[i] = Expr.aggStep(accs[i], aggArg(ctx, aggArgsOf(specs.get(i)[1]), row));
            groups.put(kk, new Object[]{st[0], accs, st[2]});
            if (cur == null) order.add(kk);
        }
        List<Object> ks = order.isEmpty() && plain.isEmpty() ? Clj.vec(NONE) : order;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object kk : ks) {
            Object[] g = kk == NONE ? new Object[]{List.of(), init, new HashMap<String, Object>()} : groups.get(kk);
            Object[] accs = (Object[]) g[1];
            Map<Object, Object> env = new HashMap<>((Map<String, Object>) g[2]);
            // 含聚合的项在聚合之外只引用分组键（语义检查保证）：在组里任取一行（第一行）求值，分组键在组内都相同
            for (int i = 0; i < accs.length; i++) env.put(Clj.vec(AGG_REF, specs.get(i)[0]), Expr.aggFinal(accs[i]));
            Map<String, Object> r = new HashMap<>();
            for (Object[] v : (List<Object[]>) g[0]) r.put((String) v[0], v[1]);
            for (Object[] ai : aggItems) r.put((String) ai[0], ev(ai[1], rowMap(env), ctx));
            out.add(r);
        }
        return out;
    }

    private static boolean procsHas(Object name) {
        Object p = Sema.PROCS;
        if (p instanceof Map<?, ?> m) return m.containsKey(name);
        if (p instanceof Collection<?> c) return c.contains(name);
        return false;
    }

    private static Object lenCap() {
        return ExecPaths.LEN_CAP.get();
    }

    /** 逻辑计划的一个算子：输入行序列 ⇒ 输出行序列。 */
    @SuppressWarnings("unchecked")
    static Iterable<Map<String, Object>> op(Ctx ctx, Object opv, Iterable<Map<String, Object>> in) {
        List<Object> o = asList(opv);
        Kw kind = (Kw) o.getFirst();
        List<Object> args = o.subList(1, o.size());
        switch (kind.name) {
            case "node-by-id": {
                Object v = nth(args, 0), ide = nth(args, 1);
                boolean nocheck = Clj.truthy(nth(args, 2));
                return lazyMapcat((Map<String, Object> row) -> {
                    List<Object> ids;
                    if (ide instanceof List<?> && Clj.first(ide) == ID_LIST) {
                        Object l = ev(Clj.second(ide), row, ctx);
                        if (l instanceof List<?> ll) ids = (List<Object>) ll;
                        else if (l == null) ids = List.of();
                        else throw CypherError.execc("TypeError", "InvalidArgumentType", "id(v) IN needs a list");
                    } else ids = Clj.vec(ev(ide, row, ctx));
                    LinkedHashSet<Object> ds = new LinkedHashSet<>();
                    for (Object i : ids) if (ExecPaths.isVid(i)) ds.add(i);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (Object i : ds) {
                        long vid = lng(ExecPaths.toVid(i));
                        if (nocheck || ctx.store.exists(vid)) out.add(assoc(row, v, new VNode(vid)));
                    }
                    return out;
                }, in);
            }
            case "label-scan": {
                Object v = nth(args, 0), label = nth(args, 1), m = nth(args, 2);
                return lazyMapcat((Map<String, Object> row) -> {
                    Iterable<Long> vids;
                    if (Clj.truthy(m) && ctx.store instanceof Store.Direct) {
                        List<Object> push = Clj.into(Clj.vec(Clj.vec(HAS_LABEL, label)), asList(Clj.get(m, PUSH)));
                        List<Object> fs = engineFilter(ctx, push, row);
                        vids = pushedLabelVids(ctx, label, (Filter) fs.get(0), (Boolean) fs.get(1));
                    } else vids = ctx.store.labelVids((String) label);
                    return lmap((Long x) -> assoc(row, v, new VNode(x)), vids);
                }, in);
            }
            case "all-nodes-scan": {
                Object v = nth(args, 0), push = nth(args, 1);
                return lazyMapcat((Map<String, Object> row) -> {
                    Iterable<Long> vids;
                    // 条件都要 meta：只有邻接表头、没有 n 文档的顶点不可能满足——只扫 n 文档
                    if (Clj.truthy(push) && ctx.store instanceof Store.Direct)
                        vids = scannedDocVids(ctx, (Filter) engineFilter(ctx, push, row).get(0));
                    else vids = ctx.store.allVids();
                    return lmap((Long x) -> assoc(row, v, new VNode(x)), vids);
                }, in);
            }
            case "fts-seek": {
                Object v = nth(args, 0), prop = nth(args, 1), qe = nth(args, 2);
                return lazyMapcat((Map<String, Object> row) -> {
                    Object q = ev(qe, row, ctx);
                    if (q == null) return List.of();
                    if (q instanceof String) return seekRows(row, v, ftsSet(ctx, prop, q));
                    throw CypherError.execc("TypeError", "InvalidArgumentType", "the right side of @@ must be a string");
                }, in);
            }
            case "search-seek": {
                Object v = nth(args, 0), nm = nth(args, 1);
                List<Object> aes = asList(nth(args, 2));
                return lazyMapcat((Map<String, Object> row) -> {
                    List<Object> vals = new ArrayList<>();
                    for (Object a : aes) vals.add(ev(a, row, ctx));
                    for (Object x : vals) if (x == null) return List.of();
                    return seekRows(row, v, searchSet(ctx, nm, vals));
                }, in);
            }
            case "vector-range": {
                Object v = nth(args, 0), qe = nth(args, 1), te = nth(args, 2), label = nth(args, 3);
                return lazyMapcat((Map<String, Object> row) -> {
                    Object q = ev(qe, row, ctx), t = ev(te, row, ctx);
                    if (q == null || t == null) return List.of();
                    if (t instanceof Number)
                        return lmap((Long x) -> assoc(row, v, new VNode(x)), vectorRange(ctx, toF32(q), t, label));
                    throw CypherError.execc("TypeError", "InvalidArgumentType", "vector threshold must be a number");
                }, in);
            }
            case "vector-topk": {
                Object v = nth(args, 0), qe = nth(args, 1), ske = nth(args, 5), lie = nth(args, 6);
                List<Object> labels = asList(nth(args, 2));
                List<Object> pes = asList(nth(args, 3)), conjs = asList(nth(args, 4));
                long k = (ske != null ? lng(ExecPaths.countArg(ctx, ske, "SKIP")) : 0) + lng(ExecPaths.countArg(ctx, lie, "LIMIT"));
                return lazyMapcat((Map<String, Object> row) -> {
                    Object q = ev(qe, row, ctx);
                    if (q == null) return List.of();
                    List<Object> restLabels = labels.isEmpty() ? List.of() : Clj.subvec(labels, 1);
                    Predicate<Long> pass = restLabels.isEmpty() && pes.isEmpty() && conjs.isEmpty()
                            ? x -> true
                            : vid -> {
                                Map<String, Object> r1 = assoc(row, v, new VNode(vid));
                                if (!isNodeOk(ctx, vid, restLabels, pes, r1)) return false;
                                for (Object c : conjs) if (!Expr.isTruthy(ev(c, r1, ctx))) return false;
                                return true;
                            };
                    return lmap((Long x) -> assoc(row, v, new VNode(x)),
                            vectorTopk(ctx, toF32(q), k, labels.isEmpty() ? null : labels.getFirst(), pass));
                }, in);
            }
            case "node-check": {
                Object v = nth(args, 0), labels = nth(args, 1), pes = nth(args, 2);
                return lfilter(row -> row.get(v) instanceof VNode n && isNodeOk(ctx, n.vid(), labels, pes, row), in);
            }
            case "rel-check": {
                Object r = nth(args, 0), pe = nth(args, 1);
                return lfilter(row -> {
                    Object x = row.get(r);
                    List<Object> rels = x instanceof List<?> l ? (List<Object>) l : x == null ? List.of() : Clj.vec(x);
                    for (Object rel : rels) if (!isPropsMatch(ctx, relProps(ctx, rel), Clj.vec(pe), row)) return false;
                    return true;
                }, in);
            }
            case "expand":
                return expand(ctx, args, in);
            case "var-expand":
                return varExpand(ctx, args, in);
            // 量化路径：一次迭代按子模式走一遍（上一轮的终点是下一轮的起点），整条路径里关系不重复；
            // 子模式的变量交出为每次迭代一个值的列表，隐藏变量是走过的全部关系
            case "qpp-expand":
                return rowsOf(ExecPaths.qppExpand(ctx, nth(args, 0), nth(args, 1), nth(args, 2), nth(args, 3), nth(args, 4), nth(args, 5),
                        nth(args, 6), in, ExecPaths.opIndex(QPP_EXPAND, Clj.vecOf(args))));
            // 路径搜索前缀（ALL / ANY k / ANY SHORTEST / ALL SHORTEST / SHORTEST k / SHORTEST k GROUPS）
            case "path-search": {
                List<Object> a = Clj.vecOf(args);
                return lazyMapcat((Map<String, Object> row) -> rowsOf(ExecPaths.pathSearch(ctx, row, a)), in);
            }
            case "path-deepen": {
                List<Object> a = Clj.vecOf(args);
                return lazyMapcat((Map<String, Object> row) -> rowsOf(ExecPaths.pathDeepen(ctx, row, a)), in);
            }
            case "path-select":
                return pathSelect(ctx, args, in);
            case "shortest-path": {
                Object p = nth(args, 0), from = nth(args, 1), r = nth(args, 2), types = nth(args, 3), dir = nth(args, 4), mn = nth(args, 5),
                        mx = nth(args, 6), to = nth(args, 7);
                return lazyMapcat((Map<String, Object> row) -> {
                    if (row.get(from) instanceof VNode a && row.get(to) instanceof VNode b) {
                        Object res = ExecPaths.shortest(ctx, a.vid(), b.vid(), types, dir, mn, mx);
                        if (res == null) return List.of();
                        return one(pathRow(row, p, r, res));
                    }
                    return List.of();
                }, in);
            }
            case "all-shortest-paths": {
                Object p = nth(args, 0), from = nth(args, 1), r = nth(args, 2), types = nth(args, 3), dir = nth(args, 4), mn = nth(args, 5),
                        mx = nth(args, 6), to = nth(args, 7);
                return lazyMapcat((Map<String, Object> row) -> {
                    if (row.get(from) instanceof VNode a && row.get(to) instanceof VNode b) {
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (Object res : asList(ExecPaths.allShortest(ctx, a.vid(), b.vid(), types, dir, mn, mx))) out.add(pathRow(row, p, r, res));
                        return out;
                    }
                    return List.of();
                }, in);
            }
            case "build-path": {
                Object p = nth(args, 0), start = nth(args, 1), steps = nth(args, 2);
                return lmap((Map<String, Object> row) -> assoc(row, p, ExecPaths.buildPath(row.get(start), steps, row)), in);
            }
            case "call-proc": {
                Object nm = nth(args, 0);
                Map<Object, Object> spec = procsHas(nm) ? null : Procs.lookup((String) nm);
                if (spec != null) return userProc(ctx, spec, args, in);
                List<Object> pairs = asList(nth(args, 1));
                List<Map<String, Object>> recs = callProc(ctx, nm);
                return lazyMapcat((Map<String, Object> row) -> lmap((Map<String, Object> rec) -> {
                    Map<String, Object> r = new HashMap<>(row);
                    for (Object pr : pairs) r.put((String) nth(pr, 1), rec.get(nth(pr, 0)));
                    return r;
                }, recs), in);
            }
            case "merge-pattern": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) out.addAll(mergePattern(ctx, args, row));
                return out;
            }
            case "filter": {
                Object e = nth(args, 0);
                return lfilter(row -> Expr.isPredicateTrue(ev(e, row, ctx)), in);
            }
            case "unwind": {
                Object e = nth(args, 0), v = nth(args, 1);
                return lazyMapcat((Map<String, Object> row) -> {
                    Object l = ev(e, row, ctx);
                    if (l == null) return List.of();
                    if (l instanceof List<?> ll) return lmap((Object x) -> assoc(row, v, x), (List<Object>) ll);
                    return one(assoc(row, v, l));
                }, in);
            }
            case "project": {
                List<Object> items = asList(nth(args, 0));
                boolean keep = Clj.truthy(nth(args, 1));
                return lmap((Map<String, Object> row) -> {
                    Map<String, Object> p = new HashMap<>();
                    for (Object it : items) p.put((String) nth(it, 0), ev(nth(it, 1), row, ctx));
                    if (!keep) return p;
                    Map<String, Object> m = new HashMap<>(row);
                    m.putAll(p);
                    return m;
                }, in);
            }
            case "aggregate":
                return aggregate(ctx, nth(args, 0), vecRows(in));
            case "distinct": {
                List<Object> names = asList(nth(args, 0));
                List<Map<String, Object>> acc = new ArrayList<>();
                Set<Object> seen = new HashSet<>();
                for (Map<String, Object> row : in) {
                    List<Object> kk = new ArrayList<>();
                    for (Object n : names) kk.add(Expr.groupKey(row.get(n)));
                    if (seen.add(kk)) acc.add(row);
                }
                return acc;
            }
            case "sort": {
                Object keys = nth(args, 0);
                List<Map<String, Object>> rows = vecRows(in);
                prefetchDistances(ctx, keys, rows);
                return sortKeyed(ctx, keys, rows);
            }
            case "skip":
                return ldrop(lng(ExecPaths.countArg(ctx, nth(args, 0), "SKIP")), in);
            case "limit":
                return ltake(lng(ExecPaths.countArg(ctx, nth(args, 0), "LIMIT")), in);
            case "select": {
                List<Object> names = asList(nth(args, 0));
                return lmap((Map<String, Object> row) -> {
                    Map<String, Object> m = new HashMap<>();
                    for (Object n : names) m.put((String) n, row.get(n));
                    return m;
                }, in);
            }
            case "optional": {
                Object sub = nth(args, 0);
                List<Object> neu = asList(nth(args, 1));
                // OPTIONAL MATCH：每个输入行各跑一遍子流水线；一行都没有时交出原行、新变量为 null
                return lazyMapcat((Map<String, Object> row) -> {
                    Iterable<Map<String, Object>> res = one(row);
                    for (Object so : asList(sub)) res = op(ctx, so, res);
                    Iterator<Map<String, Object>> it = res.iterator();
                    if (it.hasNext()) {
                        Iterable<Map<String, Object>> once = () -> it; // 只遍历一次（惰性序列每次 iterator() 都会重算）
                        return once;
                    }
                    Map<String, Object> r = new HashMap<>(row);
                    for (Object n : neu) r.put((String) n, null);
                    return one(r);
                }, in);
            }
            case "merge-node": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) out.addAll(mergeNode(ctx, args, row));
                return out;
            }
            case "merge-rel": {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) out.addAll(mergeRel(ctx, args, row));
                return out;
            }
            case "create": {
                List<Object> chains = asList(nth(args, 0));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) {
                    for (Object c : chains) row = createChain(ctx, c, row);
                    out.add(row);
                }
                return out;
            }
            case "set": {
                List<Object> items = asList(nth(args, 0));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) {
                    for (Object it : items) row = setItem(ctx, it, row);
                    out.add(row);
                }
                return out;
            }
            case "remove": {
                List<Object> items = asList(nth(args, 0));
                List<Map<String, Object>> out = new ArrayList<>();
                for (Map<String, Object> row : vecRows(in)) {
                    for (Object it : items) row = removeItem(ctx, it, row);
                    out.add(row);
                }
                return out;
            }
            case "delete": {
                boolean detach = Clj.truthy(nth(args, 0));
                List<Object> es = asList(nth(args, 1));
                List<Map<String, Object>> rows = vecRows(in);
                List<Object> vals = new ArrayList<>();
                for (Map<String, Object> row : rows) for (Object e : es) vals.add(ev(e, row, ctx));
                List<List<Object>> nr = deleteTargets(vals);
                deleteAll(ctx, nr.get(1), nr.get(0), detach);
                return rows;
            }
            default:
                throw new IllegalArgumentException("unknown operator " + kind);
        }
    }

    /** 检索起点：按分数降序交出行（带分数列）。 */
    private static List<Map<String, Object>> seekRows(Map<String, Object> row, Object v, Map<Long, Double> hits) {
        List<Map.Entry<Long, Double>> es = new ArrayList<>(hits.entrySet());
        es.sort((a, b) -> Double.compare(-a.getValue(), -b.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : es) out.add(assoc(row, v, new VNode(e.getKey()), scoreKey(v), e.getValue()));
        return out;
    }

    /** [[vid …] [Rel …]] ⇒ 行里绑上关系（与命名路径）。 */
    private static Map<String, Object> pathRow(Map<String, Object> row, Object p, Object r, Object res) {
        List<Object> ns = asList(nth(res, 0));
        Object rels = nth(res, 1);
        Map<String, Object> m = assoc(row, r, rels);
        if (p != null) m.put((String) p, toPath(ns, rels));
        return m;
    }

    private static VPath toPath(List<Object> ns, Object rels) {
        List<Object> nodes = new ArrayList<>();
        for (Object n : ns) nodes.add(new VNode(lng(n)));
        return new VPath(nodes, asList(rels));
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Map<String, Object>> expand(Ctx ctx, List<Object> args, Iterable<Map<String, Object>> in) {
        Object from = nth(args, 0), r = nth(args, 1), types = nth(args, 2), dir = nth(args, 3), to = nth(args, 4);
        boolean into = Clj.truthy(nth(args, 5)), boundRel = Clj.truthy(nth(args, 7));
        Object uniq = nth(args, 6);
        Object ids = ExecPaths.typeIds(ctx, types);
        List<Object> argv = Clj.vecOf(args);
        return lazyMapcat((Map<String, Object> row) -> {
            if (!(row.get(from) instanceof VNode f)) return List.of();
            Object used = Clj.isEmpty(uniq) ? null : ExecPaths.usedRels(uniq, row);
            Object target = into ? (row.get(to) instanceof VNode t ? (Object) t.vid() : NONE) : null;
            Object want = boundRel ? (row.get(r) instanceof VRel x ? (Object) x.id() : NONE) : null;
            // 逐轮加深（D-47）：到了总长上限 ⇒ 交不出行；还有边 ⇒ 登记续体（下一轮 cap 加 1 后照常展开这一行）
            Supplier<Object> expandRow = () -> {
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object h : (Iterable<?>) ExecPaths.edgesByIds(ctx, f.vid(), ids, dir)) {
                    VRel rel = hopRel(h);
                    long o = hopOther(h);
                    if ((!into || Clj.eq(target, o)) && !(used != null && contains(used, rel.id())) && (!boundRel || Clj.eq(want, rel.id())))
                        out.add(assoc(row, r, rel, to, new VNode(o)));
                }
                return out;
            };
            boolean capped = false;
            Object lc = lenCap();
            if (lc != null) {
                Object vars = Clj.get(lc, VARS);
                if (contains(vars, r) && lng(ExecPaths.rowLen(row, vars)) >= lng(ExecPaths.capOf(Clj.get(lc, CAP)))) {
                    if (!Clj.isEmpty(ExecPaths.edgesByIds(ctx, f.vid(), ids, dir))) {
                        vreset(Clj.get(lc, HIT), true);
                        Object parts = Clj.get(lc, PARTS);
                        if (parts != null) vconj(parts, Clj.vec(ExecPaths.opIndex(EXPAND, argv), expandRow));
                        Object tip = Clj.get(lc, TIP);
                        if (tip != null) callFn(tip, r, Clj.vec(Clj.vec(f.vid(), used != null ? used : new HashSet<>(), 0L)));
                    }
                    capped = true;
                }
            }
            return capped ? List.of() : (List<Map<String, Object>>) expandRow.get();
        }, in);
    }

    @SuppressWarnings("unchecked")
    private static Iterable<Map<String, Object>> varExpand(Ctx ctx, List<Object> args, Iterable<Map<String, Object>> in) {
        Object from = nth(args, 0), r = nth(args, 1), types = nth(args, 2), dir = nth(args, 3), mn = nth(args, 4), mx = nth(args, 5),
                to = nth(args, 6), uniq = nth(args, 8);
        boolean into = Clj.truthy(nth(args, 7)), rev = Clj.truthy(nth(args, 9)), boundRel = Clj.truthy(nth(args, 10));
        List<Object> argv = Clj.vecOf(args);
        return lazyMapcat((Map<String, Object> row) -> {
            if (!(row.get(from) instanceof VNode f)) return List.of();
            Object target = into ? (row.get(to) instanceof VNode t ? (Object) t.vid() : NONE) : null;
            Object want = null;
            if (boundRel) {
                if (row.get(r) instanceof List<?> xs) {
                    List<Object> w = new ArrayList<>();
                    for (Object x : xs) w.add(x instanceof VRel vr ? vr.id() : null);
                    want = w;
                } else want = NONE;
            }
            final Object want0 = want;
            Function<Object, Map<String, Object>> mk = pr -> {
                long o = lng(nth(pr, 0));
                List<Object> rels = asList(nth(pr, 1));
                if (rev) {
                    rels = new ArrayList<>(rels);
                    Collections.reverse(rels);
                }
                if ((!into || Clj.eq(target, o)) && (!boundRel || Clj.eq(want0, relIds(rels)))) return assoc(row, r, rels, to, new VNode(o));
                return null;
            };
            Object used = ExecPaths.usedRels(uniq, row);
            Object lc = lenCap();
            Object capped = lc != null && contains(Clj.get(lc, VARS), r) ? lc : null;
            List<Map<String, Object>> out = new ArrayList<>();
            if (capped != null) {
                Object parts = Clj.get(capped, PARTS);
                if (parts != null) {
                    // 接续式加深（D-47）：go 从前沿走一遍；有截断 ⇒ 登记续体，下一轮只从那一跳接着走
                    long len = lng(ExecPaths.rowLen(row, Clj.get(capped, VARS)));
                    Object idx = ExecPaths.opIndex(VAR_EXPAND, argv);
                    return varGo(ctx, capped, r, types, dir, mn, mx, len, idx, parts, mk,
                            Clj.vec(Clj.vec(f.vid(), 0L, Clj.vec(), used)));
                }
                long left = lng(ExecPaths.capOf(Clj.get(capped, CAP))) - lng(ExecPaths.rowLen(row, Clj.get(capped, VARS)));
                for (Object pr : asList(ExecPaths.varPathsCapped(ctx, f.vid(), types, dir, mn, mx, used, left, Clj.get(capped, HIT)))) {
                    Map<String, Object> m = mk.apply(pr);
                    if (m != null) out.add(m);
                }
                return out;
            }
            for (Object pr : asList(ExecPaths.varPaths(ctx, f.vid(), types, dir, mn, mx, used))) {
                Map<String, Object> m = mk.apply(pr);
                if (m != null) out.add(m);
            }
            return out;
        }, in);
    }

    private static List<Object> relIds(List<Object> rels) {
        List<Object> out = new ArrayList<>();
        for (Object x : rels) out.add(((VRel) x).id());
        return out;
    }

    /** var-expand 的接续式加深：从 frontier 走一遍；有截断点 ⇒ 往 parts 登记续体（下一轮从截断处接着走一跳）。 */
    private static List<Map<String, Object>> varGo(Ctx ctx, Object capped, Object r, Object types, Object dir, Object mn, Object mx, long len,
                                                   Object idx, Object parts, Function<Object, Map<String, Object>> mk, Object frontier) {
        List<Object> ps = new ArrayList<>();
        Object hit = newFlag();
        Object tf = Clj.get(capped, TIP);
        BiConsumer<Object, Supplier<Object>> add = (us, t) -> {
            ps.add(t);
            if (tf != null) callFn(tf, r, us);
        };
        Object outs = ExecPaths.varPathsCapped(ctx, null, types, dir, mn, mx, null, lng(ExecPaths.capOf(Clj.get(capped, CAP))) - len, hit, add,
                frontier);
        if (!ps.isEmpty()) {
            List<Object> psv = new ArrayList<>(ps);
            Supplier<Object> thunk = () -> {
                List<Object> fr = new ArrayList<>();
                for (Object t : psv) fr.addAll(asList(callFn(t)));
                return varGo(ctx, capped, r, types, dir, mn, mx, len, idx, parts, mk, fr);
            };
            vconj(parts, Clj.vec(idx, thunk));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object pr : asList(outs)) {
            Map<String, Object> m = mk.apply(pr);
            if (m != null) out.add(m);
        }
        return out;
    }

    private static Iterable<Map<String, Object>> pathSelect(Ctx ctx, List<Object> args, Iterable<Map<String, Object>> in) {
        Object pv = nth(args, 0), spec = nth(args, 1);
        Object kind = Clj.get(spec, KIND), k0 = Clj.get(spec, K);
        long k;
        if (k0 == null) k = 1;
        else if (Clj.isInteger(k0)) k = lng(k0);
        else {
            Object x = ev(k0, new HashMap<>(), ctx);
            k = x == null ? 1 : lng(x);
        }
        LinkedHashMap<Object, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : in) {
            Object p = row.get(pv);
            Object kk = null;
            if (p instanceof VPath vp) kk = List.of(((VNode) vp.nodes().getFirst()).vid(), ((VNode) vp.nodes().getLast()).vid());
            groups.computeIfAbsent(kk, x -> new ArrayList<>()).add(row);
        }
        Function<Map<String, Object>, Integer> len = row -> row.get(pv) instanceof VPath vp ? vp.rels().size() : 0;
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> rows0 : groups.values()) {
            List<Map<String, Object>> rows = new ArrayList<>(rows0);
            rows.sort(Comparator.comparing(len));
            if (kind == ALL) out.addAll(rows);
            else if (kind == ANY || kind == SHORTEST) out.addAll(rows.subList(0, (int) Math.min(Math.max(k, 0), rows.size())));
            else if (kind == ANY_SHORTEST) out.addAll(rows.subList(0, Math.min(1, rows.size())));
            else if (kind == ALL_SHORTEST) {
                int m = len.apply(rows.getFirst());
                for (Map<String, Object> x : rows) {
                    if (len.apply(x) != m) break;
                    out.add(x);
                }
            } else if (kind == SHORTEST_GROUPS) {
                LinkedHashSet<Integer> ls = new LinkedHashSet<>();
                for (Map<String, Object> x : rows) {
                    if (ls.size() >= k) break;
                    ls.add(len.apply(x));
                }
                for (Map<String, Object> x : rows) if (ls.contains(len.apply(x))) out.add(x);
            } else throw new IllegalArgumentException(String.valueOf(kind));
        }
        return out;
    }

    // ═══ 结果 ═══

    /** 运行期值 ⇒ 对外的值（顶点 ⇒ {@link Node}，边 ⇒ {@link Relationship}，路径 ⇒ {@link Path}，列表 / map 递归）。 */
    static Object out(Ctx ctx, Object v) {
        if (v instanceof VNode n) {
            NodeInfo info = nodeInfo(ctx, n.vid());
            return new Node(n.vid(), info.labels(), info.properties());
        }
        if (v instanceof VRel r) return new Relationship(r.src(), typeName(ctx, r.etype()), r.dst(), r.rank(), relProps(ctx, r));
        if (v instanceof VPath p) {
            List<Node> ns = new ArrayList<>();
            for (Object x : p.nodes()) ns.add((Node) out(ctx, x));
            List<Relationship> rs = new ArrayList<>();
            for (Object x : p.rels()) rs.add((Relationship) out(ctx, x));
            return new Path(ns, rs);
        }
        if (Temporals.isTemporal(v) || v instanceof Point) return v;
        if (v instanceof List<?> l) {
            List<Object> o = new ArrayList<>(l.size());
            for (Object x : l) o.add(out(ctx, x));
            return o;
        }
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> o = new LinkedHashMap<>();
            m.forEach((kk, x) -> o.put(kk, out(ctx, x)));
            return o;
        }
        return v;
    }

    /** 模式表达式在当前行上的匹配结果（惰性）：子流水线按 [模式 已绑定的变量] 缓存在本查询里。 */
    static Iterable<Map<String, Object>> patternRows(Ctx ctx, Object part, Map<String, Object> row) {
        Object vars = Sema.exprVars(Clj.vec(PATTERN_PRED, part));
        List<String> bound = new ArrayList<>();
        for (Object v : (Iterable<?>) vars) if (row.containsKey(v)) bound.add((String) v);
        Collections.sort(bound);
        Object ops = cacheGet(ctx.patterns, Arrays.asList(part, bound), () -> Plan.patternOps(part, bound));
        Iterable<Map<String, Object>> in = one(row);
        for (Object o : asList(ops)) in = op(ctx, o, in);
        return in;
    }

    static Iterable<Map<String, Object>> subRows(Ctx ctx, Object q, Map<String, Object> row) {
        List<String> bound = new ArrayList<>();
        Object outer = Clj.get(q, OUTER);
        if (outer != null) for (Object v : (Iterable<?>) outer) if (row.containsKey(v)) bound.add((String) v);
        Collections.sort(bound);
        Object ops = cacheGet(ctx.patterns, Arrays.asList(SUB, q, bound), () -> Plan.subqueryOps(q, bound));
        Iterable<Map<String, Object>> in = one(row);
        for (Object o : asList(ops)) in = op(ctx, o, in);
        return in;
    }

    /** EXISTS { … }：以当前行为起点跑子查询，有一行就为真（子流水线按 [子查询 已绑定变量] 缓存）。 */
    static boolean existsSub(Ctx ctx, Object q, Map<String, Object> row) {
        return subRows(ctx, q, row).iterator().hasNext();
    }

    /** 模式表达式的值：匹配到的路径列表（带「模式」标记——布尔场合按「非空」当真假，openCypher）。clj 里是惰性的，这里物化。 */
    static PatternList patternPaths(Ctx ctx, Object part, Map<String, Object> row) {
        Object sv = Clj.second(nth(part, 1));
        List<Object> st = new ArrayList<>();
        for (Object s : asList(nth(part, 2))) st.add(Clj.vec(Clj.second(nth(s, 0)), Clj.second(nth(s, 1))));
        PatternList out = new PatternList();
        for (Map<String, Object> r : patternRows(ctx, part, row)) out.add(ExecPaths.buildPath(r.get(sv), st, r));
        return out;
    }

    /** 执行上下文（clj 的 Ctx 记录，实现 Env）。缓存与 stats / written / deleted 在 {@link #withParams} 出的副本间共用。 */
    static final class Ctx implements Env {
        final Store store;
        final Store.Tx tx;
        final Map<String, Object> params;
        final HashMap<Long, NodeInfo> nodes;
        final HashMap<Object, Map<String, Object>> rels;
        final HashMap<Long, Object> types;
        final HashMap<Object, Object> typeIds;
        final HashMap<Object, Object> vdist;
        final HashMap<Object, Object> fts;
        final HashMap<Object, Object> ftsScores;
        final Map<String, Object> stats;
        final Set<Object> written;
        final Map<Object, Object> etypes;
        final HashMap<Object, Object> patterns;
        final Instant now;
        final Set<Object> deleted;

        Ctx(Store store, Store.Tx tx, Map<String, Object> params, HashMap<Long, NodeInfo> nodes, HashMap<Object, Map<String, Object>> rels,
            HashMap<Long, Object> types, HashMap<Object, Object> typeIds, HashMap<Object, Object> vdist, HashMap<Object, Object> fts,
            HashMap<Object, Object> ftsScores, Map<String, Object> stats, Set<Object> written, Map<Object, Object> etypes,
            HashMap<Object, Object> patterns, Instant now, Set<Object> deleted) {
            this.store = store;
            this.tx = tx;
            this.params = params;
            this.nodes = nodes;
            this.rels = rels;
            this.types = types;
            this.typeIds = typeIds;
            this.vdist = vdist;
            this.fts = fts;
            this.ftsScores = ftsScores;
            this.stats = stats;
            this.written = written;
            this.etypes = etypes;
            this.patterns = patterns;
            this.now = now;
            this.deleted = deleted;
        }

        /** clj (assoc ctx :params …)：换参数，其余（缓存、统计、写入计数）共用。 */
        Ctx withParams(Map<String, Object> ps) {
            return new Ctx(store, tx, ps, nodes, rels, types, typeIds, vdist, fts, ftsScores, stats, written, etypes, patterns, now, deleted);
        }

        @Override
        public Map<String, Object> params() {
            return params;
        }

        @Override
        public NodeInfo nodeInfo(long vid) {
            // 本语句删掉的节点：再读标签 / 属性 ⇒ 错误（openCypher：DeletedEntityAccess）
            if (deleted.contains(vid))
                throw CypherError.execc("EntityNotFound", "DeletedEntityAccess", "node ", GraphModel.vidText(vid), " was deleted in this statement");
            return Exec.nodeInfo(this, vid);
        }

        @Override
        public float[] nodeVector(long vid) {
            return Exec.nodeVector(this, vid);
        }

        @Override
        public Map<String, Object> relProps(VRel r) {
            if (deleted.contains(r.id()))
                throw CypherError.execc("EntityNotFound", "DeletedEntityAccess", "this relationship was deleted in this statement");
            return Exec.relProps(this, r);
        }

        @Override
        public Object typeName(long etype) {
            return Exec.typeName(this, etype);
        }

        @Override
        public boolean ftsMatch(Object v, long vid, String prop, Object q) {
            return Exec.ftsMatch(this, v, vid, prop, q);
        }

        @Override
        public Object ftsScore(Object v, Map<String, Object> row) {
            return Exec.ftsScore(this, v, row);
        }

        @Override
        public Object vectorDistance(long vid, Object q) {
            return Exec.vectorDistance(this, vid, q);
        }

        @Override
        public Object searchMatch(Object v, long vid, String fnName, List<Object> vals) {
            return Exec.searchMatch(this, v, vid, fnName, vals);
        }

        @Override
        public Iterable<Map<String, Object>> patternRows(Object part, Map<String, Object> row) {
            return Exec.patternRows(this, part, row);
        }

        @Override
        public Instant now() {
            return now;
        }

        @Override
        public PatternList patternPaths(Object part, Map<String, Object> row) {
            return Exec.patternPaths(this, part, row);
        }

        @Override
        public boolean exists(Object q, Map<String, Object> row) {
            return Exec.existsSub(this, q, row);
        }

        @Override
        public Object shortest(Object e, Map<String, Object> row) {
            // [_ kind from to types dir [mn mx]]
            Object kind = nth(e, 1), from = nth(e, 2), to = nth(e, 3), types = nth(e, 4), dir = nth(e, 5), mm = nth(e, 6);
            Object mn = nth(mm, 0), mx = nth(mm, 1);
            if (!(row.get(from) instanceof VNode a && row.get(to) instanceof VNode b)) return null;
            if (kind == SINGLE) {
                Object res = ExecPaths.shortest(this, a.vid(), b.vid(), types, dir, mn, mx);
                return res == null ? null : toPath(asList(nth(res, 0)), nth(res, 1));
            }
            if (kind == ALL) {
                List<Object> out = new ArrayList<>();
                for (Object res : asList(ExecPaths.allShortest(this, a.vid(), b.vid(), types, dir, mn, mx)))
                    out.add(toPath(asList(nth(res, 0)), nth(res, 1)));
                return out;
            }
            return null;
        }

        @Override
        public Bitcask db() {
            return store.db();
        }
    }

    static Ctx makeCtx(Store st, Store.Tx t, Map<String, Object> params) {
        return new Ctx(st, t, params == null ? new HashMap<>() : params, new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashSet<>(), etypeCache(st.db()), new HashMap<>(),
                Instant.now(), new HashSet<>());
    }

    /** 打开的结果：列名、惰性的输出行（列为空时每行是 null）、上下文（UNION 时 ctxs 是各部分的）。 */
    record OpenRows(List<String> columns, Iterator<List<Object>> rows, Ctx ctx, List<Ctx> ctxs) {
        /** 统计（UNION 各部分相加）；行取完之后再读才完整。 */
        Map<String, Object> stats() {
            return ctxs != null ? mergeStats(ctxs) : Exec.stats(ctx);
        }
    }

    private record Opened(List<String> columns, Iterable<List<Object>> rows, Ctx ctx, List<Ctx> ctxs) {
    }

    /** UNION：各部分依次执行（同一个存储 / 语句事务），结果首尾相接；不带 ALL 时按行去重。 */
    private static Opened openUnion(Object plan, Store st, Store.Tx t, Map<String, Object> params) {
        Object u = Clj.get(plan, UNION);
        boolean all = Clj.truthy(Clj.get(u, ALL_Q));
        List<Opened> opened = new ArrayList<>();
        for (Object p : asList(Clj.get(u, PARTS))) opened.add(open(p, st, t, params));
        List<Iterable<List<Object>>> parts = new ArrayList<>();
        List<Ctx> ctxs = new ArrayList<>();
        for (Opened o : opened) {
            parts.add(o.rows());
            ctxs.add(o.ctx());
        }
        Iterable<List<Object>> rows = lconcat(parts);
        return new Opened(strings(Clj.get(plan, COLUMNS)), all ? rows : distinctByKey(rows), opened.getFirst().ctx(), ctxs);
    }

    private static Opened open(Object plan, Store st, Store.Tx t, Map<String, Object> params) {
        if (Clj.truthy(Clj.get(plan, UNION))) return openUnion(plan, st, t, params);
        Ctx ctx = makeCtx(st, t, params);
        Iterable<Map<String, Object>> in = one(new HashMap<>());
        for (Object o : asList(Clj.get(plan, OPS))) in = op(ctx, o, in);
        List<String> columns = strings(Clj.get(plan, COLUMNS));
        Iterable<Map<String, Object>> rows = in;
        Iterable<List<Object>> outRows = columns.isEmpty()
                ? lmap((Map<String, Object> row) -> (List<Object>) null, rows)
                : lmap((Map<String, Object> row) -> {
                    List<Object> r = new ArrayList<>(columns.size());
                    for (String c : columns) r.add(out(ctx, row.get(c)));
                    return r;
                }, rows);
        return new Opened(columns, outRows, ctx, null);
    }

    /** 执行计划但不收齐 ⇒ 列名 + 惰性的输出行（流式游标用：取多少算多少）。 */
    static OpenRows openRows(Object plan, Store st, Store.Tx t, Map<String, Object> params) {
        Opened o = open(plan, st, t, params);
        return new OpenRows(o.columns(), o.rows().iterator(), o.ctx(), o.ctxs());
    }

    /** 统计：去掉为 0 的数值项。 */
    static Map<String, Object> stats(Ctx ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        ctx.stats.forEach((k, v) -> {
            if (!(v instanceof Number n && n.longValue() == 0)) out.put(k, v);
        });
        return out;
    }

    /** 各部分统计相加（数值相加；非数值（vector-exhaustive）取与）。 */
    private static Map<String, Object> mergeStats(List<Ctx> ctxs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Ctx c : ctxs)
            stats(c).forEach((k, v) -> out.merge(k, v, (a, b) -> a instanceof Number x && b instanceof Number y ? (Object) (x.longValue() + y.longValue())
                    : (Object) (Clj.truthy(a) && Clj.truthy(b))));
        return out;
    }

    /** 执行计划 ⇒ 结果。st = 存储（直读或语句事务），t = 语句事务（写语句）或 null。 */
    static CypherResult run(Object plan, Store st, Store.Tx t, Map<String, Object> params) {
        Opened o = open(plan, st, t, params);
        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> r : o.rows()) rows.add(r);
        return new CypherResult(o.columns(), o.columns().isEmpty() ? new ArrayList<>() : rows,
                o.ctxs() != null ? mergeStats(o.ctxs()) : stats(o.ctx()));
    }
}
