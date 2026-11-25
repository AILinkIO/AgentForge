package io.ailink.agentforge.bitcask.cypher;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Cursor;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.GraphKeys;

/**
 * 执行器的路径部分（clj exec.clj 84–929）：图访问、变长展开、shortestPath / allShortestPaths、量化路径、
 * 路径搜索前缀（ANY / ALL / SHORTEST k / SHORTEST k GROUPS）与它的剪枝（逐轮剪可达点上界、逐条剪、关系不重复接续确认、
 * 超预算分组退路）、多段模式的逐轮加深（:path-deepen，接续式）。
 *
 * <p>clj 的 volatile 一律用 {@code AtomicReference<Object>}（跨文件的 {@link #LEN_CAP} 里的 :cap / :hit / :parts 也是）。
 * 续体（clj 里无参 fn）是 {@code Supplier<Object>}；登记续体的回调 (add 截断处的点 thunk) 是
 * {@code BiConsumer<Object, Supplier<Object>>}。
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class ExecPaths {
    private ExecPaths() {
    }

    private static final Kw OUT = Kw.of("out"), IN = Kw.of("in"), BOTH = Kw.of("both");
    private static final Kw KIND = Kw.of("kind"), K = Kw.of("k"), REL = Kw.of("rel"), VARLEN = Kw.of("varlen"), QPP = Kw.of("qpp");
    private static final Kw SPEC = Kw.of("spec"), TYPES = Kw.of("types"), DIR = Kw.of("dir"), MN = Kw.of("mn"), MX = Kw.of("mx");
    private static final Kw NODES = Kw.of("nodes"), STEPS = Kw.of("steps"), WHERE = Kw.of("where"), PVAR = Kw.of("pvar");
    private static final Kw REV = Kw.of("rev"), PE = Kw.of("pe"), VAR = Kw.of("var"), GROUPS = Kw.of("groups");
    private static final Kw IDS_DIRS = Kw.of("ids-dirs");
    private static final Kw VARS = Kw.of("vars"), CAP = Kw.of("cap"), HIT = Kw.of("hit"), PARTS = Kw.of("parts"), IDX = Kw.of("idx"),
            TIP = Kw.of("tip");
    private static final Kw BOUND = Kw.of("bound"), FREE = Kw.of("free");
    private static final Kw SHORTEST = Kw.of("shortest"), ANY = Kw.of("any"), ANY_SHORTEST = Kw.of("any-shortest"),
            ALL_SHORTEST = Kw.of("all-shortest"), SHORTEST_GROUPS = Kw.of("shortest-groups");
    private static final Kw REL_CHECK = Kw.of("rel-check"), BUILD_PATH = Kw.of("build-path");
    private static final Kw ROOT = Kw.of("root");
    /** clj 的 ::none（终点没绑成节点时的目标标记，不会等于任何 vid）。 */
    private static final Kw NONE = Kw.of("im.ttalk.bitcask.cypher.exec/none");
    /** clj 的 ::fallback。 */
    private static final Kw FALLBACK = Kw.of("im.ttalk.bitcask.cypher.exec/fallback");
    /** clj 的 ::in-fast 行标记（行键是字符串；用一个不会和变量名撞的键）。 */
    private static final String IN_FAST = "\u0000exec/in-fast";
    private static final BigInteger U64_MAX = new BigInteger("18446744073709551615");

    // ── 小工具 ───────────────────────────────────────────

    private static long lng(Object o) {
        return ((Number) o).longValue();
    }

    private static Object g(Object m, Kw k) {
        return Clj.get(m, k);
    }

    private static List<Object> lst(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    private static <T> List<T> rev(List<T> l) {
        List<T> r = new ArrayList<>(l);
        Collections.reverse(r);
        return r;
    }

    private static Set<Object> conjSet(Set<Object> s, Object x) {
        Set<Object> n = s == null ? new HashSet<>() : new HashSet<>(s);
        n.add(x);
        return n;
    }

    private static Set<Object> setOrEmpty(Object s) {
        return s == null ? Set.of() : (Set<Object>) s;
    }

    private static Kw flip(Object d) {
        if (d == OUT) return IN;
        if (d == IN) return OUT;
        if (d == BOTH) return BOTH;
        return null;
    }

    private static Kw flipCase(Object d) {
        Kw f = flip(d);
        if (f == null) throw new IllegalArgumentException("No matching clause: " + d);
        return f;
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> m = a == null ? new LinkedHashMap<>() : new LinkedHashMap<>(a);
        if (b != null) m.putAll(b);
        return m;
    }

    private static Map<String, Object> assoc(Map<String, Object> m, Object... kvs) {
        return Clj.assoc(m, kvs);
    }

    /** (reduce (fn [in o] (op ctx o in)) in ops)。 */
    private static Iterable<Map<String, Object>> runOps(Exec.Ctx ctx, List<?> ops, Iterable<Map<String, Object>> in) {
        Iterable<Map<String, Object>> cur = in;
        for (Object o : ops) cur = Exec.op(ctx, o, cur);
        return cur;
    }

    private static List<Map<String, Object>> toList(Iterable<Map<String, Object>> it) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : it) out.add(r);
        return out;
    }

    private static List<Map<String, Object>> one(Map<String, Object> row) {
        List<Map<String, Object>> l = new ArrayList<>(1);
        l.add(row);
        return l;
    }

    /** 惰性 mapcat（同 Exec 的 lazy-mapcat；自带一份，免得依赖它的 Java 签名）。 */
    private static Iterable<Map<String, Object>> lazyMapcat(Function<Map<String, Object>, Iterable<Map<String, Object>>> f,
                                                           Iterable<Map<String, Object>> s) {
        return () -> new Iterator<>() {
            final Iterator<Map<String, Object>> src = s.iterator();
            Iterator<Map<String, Object>> cur = Collections.emptyIterator();

            @Override
            public boolean hasNext() {
                while (!cur.hasNext()) {
                    if (!src.hasNext()) return false;
                    cur = f.apply(src.next()).iterator();
                }
                return true;
            }

            @Override
            public Map<String, Object> next() {
                if (!hasNext()) throw new NoSuchElementException();
                return cur.next();
            }
        };
    }

    private static List<Object> relIds(List<?> rels) {
        List<Object> out = new ArrayList<>(rels.size());
        for (Object r : rels) out.add(((VRel) r).id());
        return out;
    }

    private static long vidOf(Object node) {
        return ((VNode) node).vid();
    }

    /** volatile 的 vswap! inc（值是 Long）。 */
    private static void incVol(AtomicReference<Object> v) {
        v.set(lng(v.get()) + 1);
    }

    private static void conjVol(AtomicReference<Object> v, Object x) {
        List<Object> l = new ArrayList<>(lst(v.get()));
        l.add(x);
        v.set(l);
    }

    @FunctionalInterface
    interface Fn4 {
        void apply(Object a, Object b, Object c, Object d);
    }

    /** 分层回调 (f 长度 候选 前沿)：返回真 ⇒ 停。候选 [[终点 关系 迭代] …]，前沿 [[点 已用关系 已走跳数] …]。 */
    @FunctionalInterface
    interface LayerFn {
        boolean apply(long len, List<Object> cands, List<Object> tips);
    }

    /** 逐条剪：(per-path 截断项 want budget 不走的关系) ⇒ 能到的点。budget 是 long[1] 或 null。 */
    @FunctionalInterface
    interface PerPath {
        Set<Object> apply(List<Object> item, Set<Object> want, long[] budget, Set<Object> banned);
    }

    /** clj 的 ExceptionInfo {::over true}：剪枝的边数预算扣完。 */
    private static final class Over extends RuntimeException {
        Over() {
            super("path pruning budget exhausted", null, false, false);
        }
    }

    // ── 求值 / 加深状态 ───────────────────────────────────

    static Object ev(Exec.Ctx ctx, Object e, Map<String, Object> row) {
        return Expr.evalExpr(e, row, ctx);
    }

    /** clj 参数顺序 (ev e row ctx)。 */
    static Object ev(Object e, Map<String, Object> row, Exec.Ctx ctx) {
        return Expr.evalExpr(e, row, ctx);
    }

    /**
     * clj 的 *len-cap*：多段路径逐轮加深时（D-45）的 Kw 键 map——:vars 这条路径的关系变量（List），:cap 总长上限（Long 或
     * AtomicReference&lt;Object&gt; 装 Long），:hit 截断标记（AtomicReference 装 Boolean）；展开算子（单条 / 变长 / 量化）照它限长。
     * D-47 接续式加深另带 :parts（AtomicReference 装 List，元素 [算子下标 Long, Supplier&lt;Object&gt; thunk]，thunk.get() ⇒ 下一轮
     * 这个算子新多出一跳的输出行 Iterable&lt;Map&lt;String,Object&gt;&gt;）、:idx（Map 算子向量 ⇒ 它在 inner 里的下标 Long）与
     * :tip（BiConsumer&lt;Object,Object&gt;：(tip 关系变量 [[点 已用关系 跳数] …])）。null = 不在加深里。
     */
    static final ThreadLocal<Map<Object, Object>> LEN_CAP = new ThreadLocal<>();

    /** 总长上限（Number 或 AtomicReference 装的 Number）。 */
    static long capOf(Object cap) {
        if (cap instanceof AtomicReference<?> a) return lng(a.get());
        return lng(cap);
    }

    /** 这个算子在逐轮加深的 inner 里的下标（不在加深里 ⇒ null）。args 不含算子头。 */
    static Object opIndex(Object kind, Object args) {
        Map<Object, Object> lc = LEN_CAP.get();
        Object m = lc == null ? null : lc.get(IDX);
        if (m == null) return null;
        List<Object> key = new ArrayList<>();
        key.add(kind);
        key.addAll(lst(args));
        return ((Map<Object, Object>) m).get(key);
    }

    /** 这条路径已绑上的关系的总长。 */
    static long rowLen(Object row, Object vars) {
        long acc = 0;
        for (Object v : lst(vars)) {
            Object x = Clj.get(row, v);
            if (x instanceof VRel) acc++;
            else if (x instanceof List<?> l) acc += l.size();
        }
        return acc;
    }

    static long countArg(Exec.Ctx ctx, Object e, Object what) {
        Object n = ev(ctx, e, Map.of());
        if (!Clj.isInteger(n))
            throw CypherError.execc("SyntaxError", "InvalidArgumentType", what, " needs an integer, got a non-integer value");
        boolean neg = n instanceof BigInteger bi ? bi.signum() < 0 : lng(n) < 0;
        if (neg)
            throw CypherError.execc("SyntaxError", "NegativeIntegerArgument", what, " needs a non-negative integer, got a negative number");
        return lng(n);
    }

    /** clj 参数顺序 (count-arg e what ctx)。 */
    static long countArg(Object e, Object what, Exec.Ctx ctx) {
        return countArg(ctx, e, what);
    }

    static boolean isVid(Object i) {
        if (i instanceof BigInteger bi) return bi.signum() >= 0 && bi.compareTo(U64_MAX) <= 0;
        return Clj.isInteger(i) && lng(i) >= 0;
    }

    static long toVid(Object i) {
        return ((Number) i).longValue();
    }

    // ── 图访问 ────────────────────────────────────────────

    static List<Object> typeIds(Exec.Ctx ctx, Object types) {
        List<Object> ts = lst(types);
        if (ts.isEmpty()) {
            List<Object> one = new ArrayList<>(1);
            one.add(null);
            return one;
        }
        LinkedHashSet<Object> out = new LinkedHashSet<>();
        for (Object t : ts) {
            Object id = Exec.typeId(ctx, t);
            if (id instanceof Number) out.add(id);   // 去掉 ::none
        }
        return new ArrayList<>(out);
    }

    /** [[Rel 对端] …]；types [] = 全部类型；类型名查不到 ⇒ 没有这种边；:both 时自环只算一次。 */
    static List<RelHop> edgesOf(Exec.Ctx ctx, Object vid, Object types, Object dir) {
        return edgesByIds(ctx, vid, typeIds(ctx, types), dir);
    }

    /** 同 {@link #edgesOf}，类型已解析成 id（{@link #typeIds}；一个算子解析一次，不必每行重算）。 */
    static List<RelHop> edgesByIds(Exec.Ctx ctx, Object vid, Object ids, Object dir) {
        Store st = ctx.store;
        long v = lng(vid);
        List<RelHop> out = null, in = null;
        if (dir != IN) {
            out = new ArrayList<>();
            for (Object id : lst(ids)) out.addAll(st.edges(v, 0, id == null ? null : lng(id)));
        }
        if (dir != OUT) {
            in = new ArrayList<>();
            for (Object id : lst(ids)) in.addAll(st.edges(v, 1, id == null ? null : lng(id)));
        }
        if (dir == BOTH) {
            for (RelHop h : in) if (h.rel().src() != v) out.add(h);
            return out;
        }
        if (dir == OUT) return out;
        if (dir == IN) return in;
        throw new IllegalArgumentException("No matching clause: " + dir);
    }

    static Set<Object> usedRels(Object vars, Object row) {
        Set<Object> acc = new HashSet<>();
        for (Object v : lst(vars)) {
            Object x = Clj.get(row, v);
            if (x instanceof VRel r) acc.add(r.id());
            else if (x instanceof List<?> l) for (Object e : l) acc.add(((VRel) e).id());
        }
        return acc;
    }

    /** 变长展开：深度优先，路径内一条边不重复（也不与本 MATCH 已绑定的关系重复）⇒ [[终点 [Rel]] …]，按发现序。 */
    static List<Object> varPaths(Exec.Ctx ctx, Object v, Object types, Object dir, Object mn, Object mx, Object used) {
        List<Object> out = new ArrayList<>();
        List<Object> ids = typeIds(ctx, types);
        vpDfs(ctx, lng(v), 0, new ArrayList<>(), (Set<Object>) (used == null ? new HashSet<>() : used), ids, dir, lng(mn), lng(mx), out);
        return out;
    }

    private static void vpDfs(Exec.Ctx ctx, long v, long depth, List<Object> rels, Set<Object> used, List<Object> ids, Object dir,
                              long mn, long mx, List<Object> out) {
        if (depth >= mn) out.add(Clj.vec(v, rels));
        if (depth < mx) {
            for (RelHop h : edgesByIds(ctx, v, ids, dir)) {
                Object id = h.rel().id();
                if (!used.contains(id)) vpDfs(ctx, h.other(), depth + 1, Clj.conj(rels, h.rel()), conjSet(used, id), ids, dir, mn, mx, out);
            }
        }
    }

    static List<Object> unwindPath(Object v, Map<Object, Object> seen) {
        Object cur = v;
        List<Object> ns = new ArrayList<>(), rs = new ArrayList<>();
        while (true) {
            Object p = seen.get(cur);
            if (p == ROOT) {
                ns.add(cur);
                Collections.reverse(ns);
                Collections.reverse(rs);
                return Clj.vec(ns, rs);
            }
            List<?> ur = (List<?>) p;
            ns.add(cur);
            rs.add(ur.get(1));
            cur = ur.get(0);
        }
    }

    /**
     * 单向 BFS 最短路。照搬 clj：内层 reduce 找到终点时 reduced 只停内层，外层接着处理同层余下的点，找到标记会被之后
     * 新发现的点覆盖（clj 原样如此）。
     */
    static Object bfs(Exec.Ctx ctx, Object a, Object b, Object types, Object dir, Object mn, Object mx) {
        List<Object> ids = typeIds(ctx, types);
        List<Object> frontier = Clj.vec(a);
        Map<Object, Object> seen = new HashMap<>();
        seen.put(a, ROOT);
        long depth = 0, mxl = lng(mx);
        while (!frontier.isEmpty() && depth < mxl) {
            List<Object> nxt = new ArrayList<>();
            boolean found = false;
            for (Object u : frontier) {
                for (RelHop h : edgesByIds(ctx, u, ids, dir)) {
                    Object o = h.other();
                    if (seen.containsKey(o)) continue;
                    nxt.add(o);
                    seen.put(o, Clj.vec(u, h.rel()));
                    found = Objects.equals(o, b);
                    if (found) break;   // reduced：只停内层
                }
            }
            if (found) {
                List<Object> p = unwindPath(b, seen);
                return ((List<?>) p.get(1)).size() >= lng(mn) ? p : null;
            }
            frontier = nxt;
            depth++;
        }
        return null;
    }

    static List<Object> pathRels(Exec.Ctx ctx, Object path, Object types, Object dir) {
        List<Object> p = lst(path);
        List<Object> out = new ArrayList<>();
        for (int i = 0; i + 1 < p.size(); i++) {
            Object u = p.get(i), v = p.get(i + 1);
            VRel found = null;
            for (RelHop h : edgesOf(ctx, u, types, dir)) {
                if (Objects.equals(h.other(), v)) {
                    found = h.rel();
                    break;
                }
            }
            if (found == null) throw CypherError.exec("shortestPath: edge vanished while reading the path");
            out.add(found);
        }
        return out;
    }

    /** 最短路 ⇒ [[vid …] [Rel …]] 或 null。单一类型（或不限类型）的有向直读查询走图层的双向 BFS，其余走这里的单向 BFS。 */
    static Object shortest(Exec.Ctx ctx, Object a, Object b, Object types, Object dir, Object mn, Object mx) {
        if (Objects.equals(a, b)) return lng(mn) == 0 ? Clj.vec(Clj.vec(a), Clj.vec()) : null;
        if (dir != BOTH && Clj.count(types) <= 1 && ctx.store instanceof Store.Direct) {
            Object et = Clj.isEmpty(types) ? null : Exec.typeId(ctx, Clj.first(types));
            if (et != null && !(et instanceof Number)) return null;   // ::none
            long s = dir == OUT ? lng(a) : lng(b), d = dir == OUT ? lng(b) : lng(a);
            List<Long> p0 = Graph.open(Exec.db(ctx)).shortestPath(s, d, et == null ? null : lng(et));
            if (p0 == null) return null;
            List<Object> p = new ArrayList<>(p0);
            if (dir != OUT) Collections.reverse(p);
            long len = p.size() - 1;
            return lng(mn) <= len && len <= lng(mx) ? Clj.vec(p, pathRels(ctx, p, types, dir)) : null;
        }
        return bfs(ctx, a, b, types, dir, mn, mx);
    }

    /** allShortestPaths：按层 BFS，记下每个顶点在最短距离上的全部前驱（顶点, 边），到达终点的那一层走完就停，再回溯出全部路径。 */
    static final long MAX_ALL_PATHS = 100000;

    static List<Object> allShortest(Exec.Ctx ctx, Object a, Object b, Object types, Object dir, Object mn, Object mx) {
        if (Objects.equals(a, b)) return lng(mn) == 0 ? Clj.vec(Clj.vec(Clj.vec(a), Clj.vec())) : Clj.vec();
        List<Object> ids = typeIds(ctx, types);
        Map<Object, Long> dist = new HashMap<>();
        Map<Object, List<Object[]>> preds = new HashMap<>();
        dist.put(a, 0L);
        List<Object> frontier = Clj.vec(a);
        long depth = 0, mxl = lng(mx);
        Long foundDepth = null;
        while (!frontier.isEmpty() && depth < mxl) {
            long d1 = depth + 1;
            List<Object> nxt = new ArrayList<>();
            for (Object u : frontier) {
                for (RelHop h : edgesByIds(ctx, u, ids, dir)) {
                    Object o = h.other();
                    Long od = dist.get(o);
                    if (od == null) {
                        nxt.add(o);
                        dist.put(o, d1);
                        List<Object[]> l = new ArrayList<>();
                        l.add(new Object[]{u, h.rel()});
                        preds.put(o, l);
                    } else if (od == d1) {
                        preds.get(o).add(new Object[]{u, h.rel()});
                    }
                }
            }
            if (dist.containsKey(b)) {
                foundDepth = d1;
                break;
            }
            frontier = nxt;
            depth = d1;
        }
        if (foundDepth == null) return Clj.vec();
        if (foundDepth < lng(mn)) return Clj.vec();
        List<Object[]> ps = unwindShortest(a, b, preds);
        List<Object> out = new ArrayList<>(ps.size());
        for (Object[] p : ps) out.add(Clj.vec(p[0], p[1]));
        return out;
    }

    /** 回溯全部最短路（[ns rs]）。超过上限即报错（中间结果超了总数必然也超，与 clj 收齐后再判同一个错）。 */
    private static List<Object[]> unwindShortest(Object a, Object v, Map<Object, List<Object[]>> preds) {
        List<Object[]> out = new ArrayList<>();
        if (Objects.equals(v, a)) {
            out.add(new Object[]{Clj.vec(a), Clj.vec()});
            return out;
        }
        List<Object[]> pl = preds.get(v);
        if (pl == null) return out;
        for (Object[] ur : pl) {
            for (Object[] sub : unwindShortest(a, ur[0], preds)) {
                out.add(new Object[]{Clj.conj((List<?>) sub[0], v), Clj.conj((List<?>) sub[1], ur[1])});
                if (out.size() > MAX_ALL_PATHS)
                    throw CypherError.execc("ArgumentError", "NumberOutOfRange", "allShortestPaths: more than ", MAX_ALL_PATHS, " paths");
            }
        }
        return out;
    }

    /** 普通命名路径：从起点出发依次接上每一段关系（变长段是一串关系），每条关系的另一端是下一个顶点；有一段是 null ⇒ 整条 null。 */
    static Object buildPath(Object start, Object steps, Object row) {
        if (!(start instanceof VNode)) return null;
        List<Object> ns = Clj.vec(start), rs = new ArrayList<>();
        for (Object step : lst(steps)) {
            Object rv = Clj.first(step);
            Object seg = Clj.get(row, rv);
            if (seg == null) return null;
            List<?> segL = seg instanceof List<?> l ? l : List.of(seg);
            for (Object ro : segL) {
                VRel r = (VRel) ro;
                long cur = vidOf(ns.getLast());
                long nxt = r.src() == cur ? r.dst() : r.src();
                ns.add(new VNode(nxt));
                rs.add(r);
            }
        }
        return new VPath(ns, rs);
    }

    // ── 内置过程 ──────────────────────────────────────────

    static byte[] firstKey(Object d, byte[] lo, byte[] hi) {
        try (Cursor c = ((Bitcask) d).rangeCursor(RangeOptions.between(lo, hi))) {
            return c.moveNext() ? c.key() : null;
        }
    }

    /** 标签索引里出现过的全部标签：每次跳到下一个标签的前缀之后（不逐个顶点扫）。 */
    static List<Object> allLabels(Object d) {
        byte[] base = GraphKeys.familyBytes("l"), hi = GraphKeys.successor(base);
        byte[] lo = base;
        List<Object> acc = new ArrayList<>();
        while (true) {
            byte[] k = firstKey(d, lo, hi);
            if (k == null) return acc;
            GraphKeys.LabelKey lk = GraphKeys.parseLabelKey(k);
            if (lk == null) return acc;
            acc.add(lk.label());
            lo = GraphKeys.successor(GraphKeys.labelPrefix(lk.label()));
        }
    }

    // ── 量化路径 ──────────────────────────────────────────

    /** 量化路径倒过来走（从模式右端出发）：节点与步骤倒序、方向翻转，嵌套的一起倒；:rev 标记让结果再倒回模式的顺序。 */
    static Map<Object, Object> revSpec(Object spec) {
        Map<Object, Object> m = new LinkedHashMap<>((Map<Object, Object>) spec);
        m.put(NODES, rev(lst(m.get(NODES))));
        List<Object> ss = new ArrayList<>();
        for (Object st : rev(lst(m.get(STEPS)))) {
            Map<Object, Object> s = (Map<Object, Object>) st;
            ss.add(g(s, KIND) == QPP ? Clj.assoc(s, SPEC, revSpec(s.get(SPEC))) : Clj.assoc(s, DIR, flip(s.get(DIR))));
        }
        m.put(STEPS, ss);
        m.put(REV, true);
        return m;
    }

    /** 从 start 出发、按顺序走 rels 得到的路径。 */
    static VPath pathOf(Object start, Object rels) {
        long cur = lng(start);
        List<Object> ns = Clj.vec(new VNode(cur));
        for (Object ro : lst(rels)) {
            VRel r = (VRel) ro;
            long nxt = cur == r.src() ? r.dst() : r.src();
            ns.add(new VNode(nxt));
            cur = nxt;
        }
        return new VPath(ns, new ArrayList<>(lst(rels)));
    }

    static List<Object> varPathsCapped(Exec.Ctx ctx, Object v, Object types, Object dir, Object mn, Object mx, Object used, Object cap,
                                       Object hit) {
        return varPathsCapped(ctx, v, types, dir, mn, mx, used, cap, hit, null, null);
    }

    /**
     * 同 {@link #varPaths}，另限总长 cap（Number 或 AtomicReference；cap 截断了还能走的路径 ⇒ 置 hit（AtomicReference&lt;Object&gt;））。
     * frontier（可选）：从这些部分路径接着走 [[点 深度 关系 已用] …]，缺省只从 v 出发（这时 v / used 不用）。
     * add（可选）：截断点登记续体——(add 截断处的点 thunk)，thunk.get() ⇒ 下一轮的前沿（只比截断处多走一跳的 [点 深度 关系 已用]）；
     * 截断处的点（D-48）是 [[点 这条部分路径已用的关系 这一段已走的跳数] …]：以后的路径都从这些点再走至少一跳，逐轮剪可达点上界用
     * （已用的关系 D-49 起用来逐条剪，跳数 D-50 起用来按下限 / 上限深搜）。
     */
    static List<Object> varPathsCapped(Exec.Ctx ctx, Object v, Object types, Object dir, Object mn, Object mx, Object used, Object cap,
                                       Object hit, BiConsumer<Object, Supplier<Object>> add, Object frontier) {
        List<Object> out = new ArrayList<>();
        List<Object> ids = typeIds(ctx, types);
        Iterable<Object> fr = frontier != null ? (Iterable<Object>) frontier
                : List.of(Clj.vec(v, 0L, Clj.vec(), used == null ? new HashSet<>() : used));
        AtomicReference<Object> h = (AtomicReference<Object>) hit;
        long mnl = lng(mn), mxl = lng(mx);
        for (Object item : fr) {
            List<?> it = (List<?>) item;
            vpcDfs(ctx, lng(it.get(0)), lng(it.get(1)), (List<Object>) it.get(2), (Set<Object>) it.get(3), ids, dir, mnl, mxl, cap, h, add, out);
        }
        return out;
    }

    private static void vpcDfs(Exec.Ctx ctx, long v, long depth, List<Object> rels, Set<Object> used, List<Object> ids, Object dir,
                               long mn, long mx, Object cap, AtomicReference<Object> hit, BiConsumer<Object, Supplier<Object>> add,
                               List<Object> out) {
        if (depth >= mn) out.add(Clj.vec(v, rels));
        if (depth < Math.min(mx, capOf(cap))) {
            for (RelHop h : edgesByIds(ctx, v, ids, dir)) {
                Object id = h.rel().id();
                if (!used.contains(id)) vpcDfs(ctx, h.other(), depth + 1, Clj.conj(rels, h.rel()), conjSet(used, id), ids, dir, mn, mx, cap,
                        hit, add, out);
            }
        } else if (depth < mx) {
            // 到了上限：还有能走的 ⇒ 截断（登记续体，下一轮从这里接着走）
            List<RelHop> es = new ArrayList<>();
            for (RelHop h : edgesByIds(ctx, v, ids, dir)) if (!used.contains(h.rel().id())) es.add(h);
            if (!es.isEmpty()) {
                hit.set(true);
                if (add != null) {
                    add.accept(Clj.vec(Clj.vec(v, used, depth)), () -> {
                        List<Object> nf = new ArrayList<>();
                        for (RelHop h : es)
                            nf.add(Clj.vec(h.other(), depth + 1, Clj.conj(rels, h.rel()), conjSet(used, h.rel().id())));
                        return nf;
                    });
                }
            }
        }
    }

    static void qppWalk(Exec.Ctx ctx, Object spec, Map<String, Object> row, Object cur, Object used, Fn4 emit) {
        qppWalk(ctx, spec, row, cur, used, emit, Long.MAX_VALUE, new AtomicReference<>(false));
    }

    static void qppWalk(Exec.Ctx ctx, Object spec, Map<String, Object> row, Object cur, Object used, Fn4 emit, Object cap,
                        AtomicReference<Object> hit) {
        qppWalk(ctx, spec, row, cur, used, emit, cap, hit, null);
    }

    /**
     * 从 cur 出发按 spec 迭代 mn..mx 次；每个结果调 (emit 终点 用过的关系 迭代绑定 关系)（都是走的顺序）。一次迭代按子模式走一遍：
     * 单条关系、变长关系、嵌套的量化路径；整条路径里关系不重复；WHERE 在每次迭代末尾判断；子路径变量是这次迭代的路径。
     * cap / hit（D-44，分层搜索用）：总长不超过 cap（Number 或 AtomicReference），被截断过 ⇒ hit 置真。
     * add（D-47，接续式加深）：截断点登记续体（闭包，走到 emit 为止）；续体只往下走，不会重交旧结果——下一轮把 cap 加 1 后依次调用。
     */
    static void qppWalk(Exec.Ctx ctx, Object spec, Map<String, Object> row, Object cur, Object used, Fn4 emit, Object cap,
                        AtomicReference<Object> hit, BiConsumer<Object, Supplier<Object>> add) {
        new Walk(ctx, (Map<Object, Object>) spec, row, emit, cap, hit, add)
                .iter(0, lng(cur), (Set<Object>) (used == null ? new HashSet<>() : used), new ArrayList<>(), new ArrayList<>());
    }

    private static final class Walk {
        final Exec.Ctx ctx;
        final List<Object> nodes, steps;
        final Object where, pvar;
        final long mn, mx;
        final boolean rev;
        final Map<String, Object> row;
        final Fn4 emit;
        final Object cap;
        final AtomicReference<Object> hit;
        final BiConsumer<Object, Supplier<Object>> add;
        final int n;

        Walk(Exec.Ctx ctx, Map<Object, Object> spec, Map<String, Object> row, Fn4 emit, Object cap, AtomicReference<Object> hit,
             BiConsumer<Object, Supplier<Object>> add) {
            this.ctx = ctx;
            this.nodes = lst(spec.get(NODES));
            this.steps = lst(spec.get(STEPS));
            this.where = spec.get(WHERE);
            this.mn = lng(spec.get(MN));
            this.mx = lng(spec.get(MX));
            this.pvar = spec.get(PVAR);
            this.rev = Clj.truthy(spec.get(REV));
            this.row = row;
            this.emit = emit;
            this.cap = cap;
            this.hit = hit;
            this.add = add;
            this.n = steps.size();
        }

        boolean nodeOk(Object nspec, long vid, Map<String, Object> env) {
            Object pe = Clj.nth(nspec, 2);
            return Exec.isNodeOk(ctx, vid, Clj.nth(nspec, 1), pe != null ? Clj.vec(pe) : Clj.vec(), env);
        }

        void hop(int i, long v, Set<Object> used, Map<String, Object> b, List<Object> rs, long start, long len, Fn4 k) {
            if (i == n) {
                // 子路径变量先绑上，WHERE 里可以引用它
                Map<String, Object> b2 = pvar != null ? assoc(b, pvar, rev ? pathOf(v, rev(rs)) : pathOf(start, rs)) : b;
                if (where == null || Expr.isPredicateTrue(ev(ctx, where, merge(row, b2)))) k.apply(v, used, b2, rs);
                return;
            }
            Map<Object, Object> st = (Map<Object, Object>) steps.get(i);
            Object nn = nodes.get(i + 1);
            Object nnVar = Clj.first(nn);
            Map<String, Object> env = merge(row, b);
            Object pe = st.get(PE);
            Predicate<VRel> peOk = r -> pe == null || Exec.isPropsMatch(ctx, Exec.relProps(ctx, r), Clj.vec(pe), env);
            Object kind = st.get(KIND);
            if (kind == REL) {
                List<RelHop> es = edgesByIds(ctx, v, typeIds(ctx, st.get(TYPES)), st.get(DIR));
                Object rv = st.get(VAR);
                if (len < capOf(cap)) {
                    for (RelHop h : es) {
                        Object id = h.rel().id();
                        long o = h.other();
                        if (!used.contains(id) && peOk.test(h.rel()) && nodeOk(nn, o, env))
                            hop(i + 1, o, conjSet(used, id), assoc(b, rv, h.rel(), nnVar, new VNode(o)), Clj.conj(rs, h.rel()), start, len + 1, k);
                    }
                } else {
                    // 截断：还有能走的关系 ⇒ 登记续体
                    List<RelHop> es2 = new ArrayList<>();
                    for (RelHop h : es) if (!used.contains(h.rel().id())) es2.add(h);
                    if (!es2.isEmpty()) {
                        hit.set(true);
                        if (add != null) {
                            add.accept(Clj.vec(Clj.vec(v, used, len)), () -> {
                                for (RelHop h : es2) {
                                    long o = h.other();
                                    if (peOk.test(h.rel()) && nodeOk(nn, o, env))
                                        hop(i + 1, o, conjSet(used, h.rel().id()), assoc(b, rv, h.rel(), nnVar, new VNode(o)),
                                                Clj.conj(rs, h.rel()), start, len + 1, k);
                                }
                                return null;
                            });
                        }
                    }
                }
            } else if (kind == VARLEN) {
                Set<Object> used0 = used;
                BiConsumer<Object, Object> hopOut = (o, relsO) -> {
                    List<Object> rels = lst(relsO);
                    Set<Object> u2 = new HashSet<>(used0);
                    u2.addAll(relIds(rels));
                    hop(i + 1, lng(o), u2, assoc(b, st.get(VAR), rev ? rev(rels) : new ArrayList<>(rels), nnVar, new VNode(lng(o))),
                            Clj.into(rs, rels), start, len + rels.size(), k);
                };
                for (Object e : varlenGo(st, List.of(Clj.vec(v, 0L, Clj.vec(), used0)), len, hopOut)) {
                    List<?> el = (List<?>) e;
                    long o = lng(el.get(0));
                    List<Object> rels = (List<Object>) el.get(1);
                    boolean allPe = true;
                    for (Object r : rels) {
                        if (!peOk.test((VRel) r)) {
                            allPe = false;
                            break;
                        }
                    }
                    if (allPe && nodeOk(nn, o, env)) hopOut.accept(o, rels);
                }
            } else if (kind == QPP) {
                Map<Object, Object> inner = (Map<Object, Object>) st.get(SPEC);
                boolean irev = Clj.truthy(inner.get(REV));
                qppWalk(ctx, inner, env, v, used, (end, used2, itersO, relsO) -> {
                            long e = lng(end);
                            if (nodeOk(nn, e, env)) {
                                List<Object> iters = irev ? rev(lst(itersO)) : lst(itersO);
                                List<Object> rels = lst(relsO);
                                List<Object> prs = irev ? rev(rels) : rels;
                                Map<String, Object> b2 = b;
                                for (Object gv : lst(inner.get(GROUPS))) b2 = assoc(b2, gv, collect(iters, gv));
                                hop(i + 1, e, (Set<Object>) used2, assoc(b2, inner.get(VAR), prs, nnVar, new VNode(e)), Clj.into(rs, rels), start,
                                        len + rels.size(), k);
                            }
                        }, capOf(cap) - len, hit,
                        // 嵌套的量化路径：里面的跳数相对它自己的起点 ⇒ 交给外层时记成不知道
                        add == null ? null : (us, t) -> {
                            List<Object> us2 = new ArrayList<>();
                            for (Object x : lst(us)) us2.add(Clj.vec(Clj.nth(x, 0), Clj.nth(x, 1), null));
                            add.accept(us2, t);
                        });
            } else {
                throw new IllegalArgumentException("No matching clause: " + kind);
            }
        }

        /** go：从前沿走一遍 ⇒ [[终点 关系] …]；有截断 ⇒ 登记续体（续体接着走，结果经 hop-out 往下交）。 */
        List<Object> varlenGo(Map<Object, Object> st, Object frontier, long len, BiConsumer<Object, Object> hopOut) {
            List<Object[]> ps = new ArrayList<>();
            List<Object> outs = varPathsCapped(ctx, null, st.get(TYPES), st.get(DIR), st.get(MN), st.get(MX), null, capOf(cap) - len, hit,
                    add == null ? null : (us, t) -> ps.add(new Object[]{us, t}), frontier);
            if (add != null && !ps.isEmpty()) {
                List<Object> tips = new ArrayList<>();
                for (Object[] p : ps)
                    for (Object x : lst(p[0])) tips.add(Clj.vec(Clj.nth(x, 0), Clj.nth(x, 1), len + lng(Clj.nth(x, 2))));
                add.accept(tips, () -> {
                    List<Object> fr = new ArrayList<>();
                    for (Object[] p : ps) fr.addAll(lst(((Supplier<Object>) p[1]).get()));
                    // clj 原样：续体交出的结果不再过关系属性 / 节点检查
                    for (Object e : varlenGo(st, fr, len, hopOut)) hopOut.accept(Clj.nth(e, 0), Clj.nth(e, 1));
                    return null;
                });
            }
            return outs;
        }

        void iter(long kk, long cur, Set<Object> used, List<Object> iters, List<Object> rels) {
            if (kk >= mn) emit.apply(cur, used, iters, rels);
            if (kk < mx) {
                Object n0 = nodes.get(0);
                if (nodeOk(n0, cur, row)) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put((String) Clj.first(n0), new VNode(cur));
                    hop(0, cur, used, b, new ArrayList<>(), cur, rels.size(),
                            (last, used2, b2, rs) -> iter(kk + 1, lng(last), (Set<Object>) used2, Clj.conj(iters, b2), Clj.into(rels, lst(rs))));
                }
            }
        }
    }

    /** (mapv #(get % g) iters)。 */
    private static List<Object> collect(List<Object> iters, Object gv) {
        List<Object> out = new ArrayList<>(iters.size());
        for (Object m : iters) out.add(Clj.get(m, gv));
        return out;
    }

    static Iterable<Map<String, Object>> qppExpand(Exec.Ctx ctx, Object from, Object hv, Object spec0, Object to, Object intoQ, Object uniq,
                                                  Object revQ, Iterable<Map<String, Object>> in, Object idx) {
        boolean rev = Clj.truthy(revQ), into = Clj.truthy(intoQ);
        Map<Object, Object> spec = rev ? revSpec(spec0) : (Map<Object, Object>) spec0;
        return lazyMapcat(row -> {
            Object f = row.get(from);
            if (!(f instanceof VNode fn)) return List.of();
            Object target = into ? (row.get(to) instanceof VNode tn ? (Object) tn.vid() : NONE) : null;
            List<Object> out = new ArrayList<>();
            Set<Object> used = usedRels(uniq, row);
            Function<List<Object>, List<Map<String, Object>>> toRows = outs -> {
                List<Map<String, Object>> res = new ArrayList<>(outs.size());
                for (Object o : outs) {
                    Object[] e = (Object[]) o;
                    List<Object> iters = rev ? rev(lst(e[1])) : lst(e[1]);
                    List<Object> rels = rev ? rev(lst(e[2])) : lst(e[2]);
                    Map<String, Object> r = assoc(row, to, new VNode(lng(e[0])), hv, rels);
                    for (Object gv : lst(spec.get(GROUPS))) r = assoc(r, gv, collect(iters, gv));
                    res.add(r);
                }
                return res;
            };
            Map<Object, Object> lc = LEN_CAP.get();
            Map<Object, Object> capped = lc != null && lst(lc.get(VARS)).contains(hv) ? lc : null;
            if (capped != null && capped.get(PARTS) != null) {
                // 接续式加深（D-47）：capv 是这条路径的相对值，续体每次把它加 1 再接着走，只交新多出一跳的结果
                AtomicReference<Object> parts = (AtomicReference<Object>) capped.get(PARTS);
                AtomicReference<Object> capv = new AtomicReference<>(capOf(capped.get(CAP)) - rowLen(row, capped.get(VARS)));
                AtomicReference<List<Object>> sink = new AtomicReference<>(out);
                AtomicReference<List<Supplier<Object>>> ps = new AtomicReference<>(new ArrayList<>());
                AtomicReference<Object> hit = new AtomicReference<>(false);
                Fn4 emit = (end, u, iters, rels) -> {
                    if (!into || Objects.equals(target, end)) sink.get().add(new Object[]{end, iters, rels});
                };
                BiConsumer<Object, Object> tf = (BiConsumer<Object, Object>) capped.get(TIP);
                // step：下一轮——cap 加 1，依次调用上一轮登记的续体；它们新登记的续体再留给下一轮
                Function<List<Supplier<Object>>, Object>[] step = new Function[1];
                step[0] = ts -> {
                    incVol(capv);
                    sink.set(new ArrayList<>());
                    ps.set(new ArrayList<>());
                    for (Supplier<Object> t : ts) t.get();
                    List<Supplier<Object>> ts2 = ps.get();
                    if (!ts2.isEmpty()) conjVol(parts, Clj.vec(idx, (Supplier<Object>) () -> step[0].apply(ts2)));
                    return toRows.apply(sink.get());
                };
                qppWalk(ctx, spec, row, fn.vid(), used, emit, capv, hit, (us, t) -> {
                    ps.get().add(t);
                    if (tf != null) tf.accept(hv, us);
                });
                List<Supplier<Object>> ts = ps.get();
                if (!ts.isEmpty()) conjVol(parts, Clj.vec(idx, (Supplier<Object>) () -> step[0].apply(ts)));
                return toRows.apply(sink.get());
            }
            Fn4 emit = (end, u, iters, rels) -> {
                if (!into || Objects.equals(target, end)) out.add(new Object[]{end, iters, rels});
            };
            if (capped != null)
                qppWalk(ctx, spec, row, fn.vid(), used, emit, capOf(capped.get(CAP)) - rowLen(row, capped.get(VARS)),
                        (AtomicReference<Object>) capped.get(HIT));
            else qppWalk(ctx, spec, row, fn.vid(), used, emit);
            return toRows.apply(out);
        }, in);
    }

    // ── 可达点上界 ────────────────────────────────────────

    /** u 沿 ids / dir 的邻点，跳过 banned 里的关系；budget（long[1]）按边数扣，扣完 ⇒ 抛 Over（D-49）。 */
    static List<Object> nextNodes(Exec.Ctx ctx, Object u, Object ids, Object dir, Set<Object> banned, long[] budget) {
        List<RelHop> es = edgesByIds(ctx, u, ids, dir);
        if (budget != null) {
            budget[0] -= es.size();
            if (budget[0] < 0) throw new Over();
        }
        List<Object> out = new ArrayList<>(es.size());
        if (banned != null && !banned.isEmpty()) {
            for (RelHop h : es) if (!banned.contains(h.rel().id())) out.add(h.other());
        } else {
            for (RelHop h : es) out.add(h.other());
        }
        return out;
    }

    static Set<Object> reachSet(Exec.Ctx ctx, Object sources, Object idsDirs, Object maxHops, boolean zero, Set<Object> want) {
        return reachSet(ctx, sources, idsDirs, maxHops, zero, want, null, null);
    }

    /**
     * 从 sources 出发、沿 [[类型 id 方向] …] 任一走法、走 1..max-hops 跳能到的点（zero ⇒ 再含 sources 自己）——分层搜索判断「还会不会有新终点」用的上界。
     * want（D-48，可选）：只关心这些点，都找到了就提前停（返回的集合这时不全）。banned / budget（D-49，可选）：不走这些关系；走过的边数超过预算 ⇒ 抛 Over。
     */
    static Set<Object> reachSet(Exec.Ctx ctx, Object sources, Object idsDirs, Object maxHops, boolean zero, Set<Object> want,
                                Set<Object> banned, long[] budget) {
        Set<Object> seen = new HashSet<>();
        int wantN = want == null ? 0 : want.size();
        long[] hits = {0};
        Predicate<Object> see = v -> {
            if (seen.add(v)) {
                if (want != null && want.contains(v)) hits[0]++;
                return true;
            }
            return false;
        };
        if (zero) for (Object s : (Iterable<Object>) sources) see.test(s);
        List<Object> frontier = new ArrayList<>(new LinkedHashSet<>(Clj.vecOf((Iterable<Object>) sources)));
        long hops = 0, mh = lng(maxHops);
        while (!(frontier.isEmpty() || hops >= mh || (want != null && hits[0] >= wantN))) {
            List<Object> nxt = new ArrayList<>();
            for (Object u : frontier)
                for (Object idd : lst(idsDirs))
                    for (Object o : nextNodes(ctx, u, Clj.nth(idd, 0), Clj.nth(idd, 1), banned, budget))
                        if (see.test(o)) nxt.add(o);
            frontier = nxt;
            hops++;
        }
        return seen;
    }

    private record St(Object u, int i, long c) {
    }

    static Set<Object> reachEnds(Exec.Ctx ctx, Object v, Object steps, Object maxHops) {
        return reachEnds(ctx, List.of(Clj.vec(v, 0L, 0L)), steps, maxHops, null, null, null);
    }

    /**
     * 从 v 出发按 steps（走的时候的顺序；每步 {:ids-dirs [[类型 方向] …] :mn :mx}）走完全部步骤能到的终点——逐轮加深判断「还会不会有新终点」
     * 用的上界（D-47）：不管关系不重复、量化路径按并集类型算；总跳数不超过 max-hops；全部步骤的下限都是 0 才含 v 自己。
     * 状态 (走到第几步 i, 点 u, 这一步里已走 c ≤ mn 跳)：同一跳数层内先把「这一步走够了 ⇒ 进入下一步」的零代价转移做完，再各走一跳。
     * 第二种调法（D-48）：从给定的状态 seeds [[u i c] …] 出发，want / banned / budget 同 {@link #reachSet}。
     */
    static Set<Object> reachEnds(Exec.Ctx ctx, Object seeds, Object steps, Object maxHops, Set<Object> want, Set<Object> banned,
                                 long[] budget) {
        List<Object> sts = lst(steps);
        int n = sts.size();
        long[] mns = new long[n];
        for (int j = 0; j < n; j++) mns[j] = lng(g(sts.get(j), MN));
        long[] offs = new long[n + 1];
        for (int j = 0; j < n; j++) offs[j + 1] = offs[j] + mns[j] + 1;
        long width = offs[n] + 1;
        List<List<Object>> idsDirs = new ArrayList<>(n);
        for (Object st : sts) {
            List<Object> l = new ArrayList<>();
            for (Object td : lst(g(st, IDS_DIRS))) l.add(Clj.vec(typeIds(ctx, Clj.nth(td, 0)), Clj.nth(td, 1)));
            idsDirs.add(l);
        }
        long mh = lng(maxHops);
        if (width > 63) {
            // 状态位不够（极端的多段）：退回各步类型的并集
            List<Object> srcs = new ArrayList<>();
            for (Object s : (Iterable<Object>) seeds) srcs.add(Clj.nth(s, 0));
            List<Object> all = new ArrayList<>();
            for (List<Object> l : idsDirs) all.addAll(l);
            boolean allZero = true;
            for (long m : mns) if (m != 0) allZero = false;
            return reachSet(ctx, srcs, all, maxHops, want != null || allZero, want, banned, budget);
        }
        Map<Object, Long> seen = new HashMap<>();
        Set<Object> ends = new HashSet<>();
        int wantN = want == null ? 0 : want.size();
        long[] hits = {0};
        java.util.function.Consumer<Object> end = u -> {
            if (ends.add(u) && want != null && want.contains(u)) hits[0]++;
        };
        java.util.function.BooleanSupplier enough = () -> want != null && hits[0] >= wantN;
        StateFn state = (u, i, c) -> {
            long bit = 1L << (offs[i] + Math.min(c, mns[i]));
            Long old = seen.get(u);
            long nv = (old == null ? 0 : old) | bit;
            if (old == null || old != nv) {
                seen.put(u, nv);
                return true;
            }
            return false;
        };
        // 零代价转移：(i, u, mn) ⇒ (i+1, u, 0)；走到 n ⇒ 终点
        java.util.function.Consumer<List<St>> close = layer -> {
            for (int k = 0; k < layer.size(); k++) {
                St s = layer.get(k);
                if (s.i == n) end.accept(s.u);
                else if (s.c >= mns[s.i] && s.i < n) {
                    if (s.i + 1 == n) end.accept(s.u);
                    else if (state.apply(s.u, s.i + 1, 0)) layer.add(new St(s.u, s.i + 1, 0));
                }
            }
        };
        List<St> layer = new ArrayList<>();
        for (Object sd : (Iterable<Object>) seeds) {
            Object u = Clj.nth(sd, 0);
            int i = (int) lng(Clj.nth(sd, 1));
            long c = lng(Clj.nth(sd, 2));
            if (state.apply(u, i, c)) layer.add(new St(u, i, Math.min(c, mns[i])));
        }
        close.accept(layer);
        long hops = 0;
        while (!layer.isEmpty() && hops < mh && !enough.getAsBoolean()) {
            List<St> nxt = new ArrayList<>();
            for (St s : layer) {
                if (s.i >= n) continue;
                if (!(s.c < mns[s.i] || lng(g(sts.get(s.i), MX)) > mns[s.i])) continue;
                for (Object idd : idsDirs.get(s.i))
                    for (Object o : nextNodes(ctx, s.u, Clj.nth(idd, 0), Clj.nth(idd, 1), banned, budget))
                        if (state.apply(o, s.i, s.c + 1)) nxt.add(new St(o, s.i, Math.min(s.c + 1, mns[s.i])));
            }
            close.accept(nxt);
            layer = nxt;
            hops++;
        }
        return ends;
    }

    @FunctionalInterface
    private interface StateFn {
        boolean apply(Object u, int i, long c);
    }

    /** 量化路径至少走几跳（迭代下限 × 一次迭代的下限）。 */
    static long specLo(Object spec) {
        long sum = 0;
        for (Object st : lst(g(spec, STEPS))) {
            Object kind = g(st, KIND);
            if (kind == REL) sum += 1;
            else if (kind == VARLEN) sum += g(st, MN) == null ? 1 : lng(g(st, MN));
            else if (kind == QPP) sum += specLo(g(st, SPEC));
            else throw new IllegalArgumentException("No matching clause: " + kind);
        }
        return lng(g(spec, MN)) * sum;
    }

    /** 量化路径里所有关系步的 [类型 id 方向]（嵌套的也算）。 */
    static List<Object> qppIdsDirs(Exec.Ctx ctx, Object spec) {
        List<Object> out = new ArrayList<>();
        for (Object st : lst(g(spec, STEPS))) {
            if (g(st, KIND) == QPP) out.addAll(qppIdsDirs(ctx, g(st, SPEC)));
            else out.add(Clj.vec(typeIds(ctx, g(st, TYPES)), g(st, DIR)));
        }
        return out;
    }

    /** 从 target 反着 BFS（不管关系不重复）⇒ {点 到 target 的最少跳数}，至多 max 跳：分层搜索剪掉到不了终点的部分路径用（D-45；是下界，剪枝不丢结果）。 */
    static Map<Object, Long> distTo(Exec.Ctx ctx, Object target, Object ids, Object dir, Object max) {
        Kw back = flipCase(dir);
        Map<Object, Long> dist = new HashMap<>();
        dist.put(target, 0L);
        List<Object> frontier = Clj.vec(target);
        long d = 0, mxl = lng(max);
        while (!frontier.isEmpty() && d < mxl) {
            List<Object> nxt = new ArrayList<>();
            for (Object u : frontier)
                for (RelHop h : edgesByIds(ctx, u, ids, back)) {
                    Object o = h.other();
                    if (!dist.containsKey(o)) {
                        dist.put(o, d + 1);
                        nxt.add(o);
                    }
                }
            frontier = nxt;
            d++;
        }
        return dist;
    }

    @FunctionalInterface
    interface KeepFn {
        boolean test(Object o, long len);
    }

    static void varlenLayers(Exec.Ctx ctx, Object start, Object types, Object dir, Object mn, Object mx, Object used, LayerFn f) {
        varlenLayers(ctx, start, types, dir, mn, mx, used, f, null);
    }

    /**
     * 单段变长关系：按长度一层层地交出 [[终点 关系 nil] …]（层内与深度优先的发现序相同）；(f 长度 候选 前沿) 返回真 ⇒ 停
     * （前沿 [[点 已用关系 已走跳数] …]：以后更长的路径都从这些点再走至少一跳，D-48）。
     * keep（可选）：(keep 点 长度) 为假的部分路径不再展开（D-45 剪枝）。
     */
    static void varlenLayers(Exec.Ctx ctx, Object start, Object types, Object dir, Object mn, Object mx, Object used, LayerFn f, KeepFn keep) {
        List<Object> ids = typeIds(ctx, types);
        long depth = 0, mnl = lng(mn), mxl = lng(mx);
        List<Object[]> frontier = new ArrayList<>();
        frontier.add(new Object[]{start, Clj.vec(), used == null ? new HashSet<>() : used});
        while (true) {
            List<Object> cands = new ArrayList<>();
            if (depth >= mnl) for (Object[] fr : frontier) cands.add(Clj.vec(fr[0], fr[1], null));
            List<Object> tips = new ArrayList<>(frontier.size());
            for (Object[] fr : frontier) tips.add(Clj.vec(fr[0], fr[2], depth));
            if (f.apply(depth, cands, tips) || depth >= mxl) return;
            List<Object[]> nxt = new ArrayList<>();
            for (Object[] fr : frontier) {
                Set<Object> u = (Set<Object>) fr[2];
                for (RelHop h : edgesByIds(ctx, fr[0], ids, dir)) {
                    Object id = h.rel().id();
                    if (!u.contains(id) && (keep == null || keep.test(h.other(), depth + 1)))
                        nxt.add(new Object[]{h.other(), Clj.conj((List<?>) fr[1], h.rel()), conjSet(u, id)});
                }
            }
            if (nxt.isEmpty()) return;
            depth++;
            frontier = nxt;
        }
    }

    /**
     * 量化路径：逐轮加深（第 L 轮只交出长度正好 L 的）⇒ 同 {@link #varlenLayers} 的回调；某轮没有截断点 ⇒ 都走完了。
     * D-47 起接续式：每轮在截断点登记续体，下一轮把 cap 加 1、只从这些续体接着走一跳，不从头重跑。
     * 回调的第三个参数是这一轮截断处的 [[点 已用关系 已走跳数] …]（D-48）。
     */
    static void qppLayers(Exec.Ctx ctx, Object spec, Map<String, Object> row, Object start, Object used, LayerFn f) {
        AtomicReference<Object> cap = new AtomicReference<>(0L), hit = new AtomicReference<>(false);
        AtomicReference<List<Object>> sink = new AtomicReference<>(new ArrayList<>()), tips = new AtomicReference<>(new ArrayList<>());
        AtomicReference<List<Supplier<Object>>> ps = new AtomicReference<>(new ArrayList<>());
        Fn4 emit = (end, u, iters, rels) -> sink.get().add(Clj.vec(end, rels, iters));
        qppWalk(ctx, spec, row, start, used, emit, cap, hit, (us, t) -> {
            ps.get().add(t);
            tips.get().addAll(lst(us));
        });
        while (true) {
            if (f.apply(capOf(cap), new ArrayList<>(sink.get()), tips.get())) return;
            List<Supplier<Object>> ts = ps.get();
            if (ts.isEmpty()) return;
            sink.set(new ArrayList<>());
            ps.set(new ArrayList<>());
            tips.set(new ArrayList<>());
            incVol(cap);
            for (Supplier<Object> t : ts) t.get();
        }
    }

    /** 从 start 按点 BFS（跳过 used 里的关系与 rel-ok 不过的关系），至多 mx 跳 ⇒ [到达顺序 {点 距离} {点 [[前驱 关系] …]}]（最短距离上的全部前驱）。 */
    static List<Object> bfsPreds(Exec.Ctx ctx, Object start, Object ids, Object dir, Object mx, Set<Object> used, Predicate<VRel> relOk) {
        Map<Object, Long> dist = new HashMap<>();
        Map<Object, List<Object[]>> preds = new HashMap<>();
        List<Object> order = new ArrayList<>();
        dist.put(start, 0L);
        List<Object> frontier = Clj.vec(start);
        long d = 0, mxl = lng(mx);
        while (!frontier.isEmpty() && d < mxl) {
            long d1 = d + 1;
            List<Object> nxt = new ArrayList<>();
            for (Object u : frontier)
                for (RelHop h : edgesByIds(ctx, u, ids, dir)) {
                    if (used.contains(h.rel().id()) || !relOk.test(h.rel())) continue;
                    Object o = h.other();
                    Long od = dist.get(o);
                    if (od == null) {
                        dist.put(o, d1);
                        List<Object[]> l = new ArrayList<>();
                        l.add(new Object[]{u, h.rel()});
                        preds.put(o, l);
                        nxt.add(o);
                        order.add(o);
                    } else if (od == d1) {
                        preds.get(o).add(new Object[]{u, h.rel()});
                    }
                }
            frontier = nxt;
            d = d1;
        }
        return Clj.vec(order, dist, preds);
    }

    /** 前驱图上 start ⇒ v 的全部最短路径（关系序列，走的顺序）；最多 limit 条（按 clj for 的顺序取前 limit 条）。 */
    static List<Object> unwindAll(Object preds, Object start, Object v, long limit) {
        List<Object> out = new ArrayList<>();
        unwindGo((Map<Object, List<Object[]>>) preds, start, v, limit, out);
        return out;
    }

    private static void unwindGo(Map<Object, List<Object[]>> preds, Object start, Object v, long limit, List<Object> out) {
        if (limit <= 0) return;
        if (Objects.equals(v, start)) {
            out.add(new ArrayList<>());
            return;
        }
        List<Object[]> pl = preds.get(v);
        if (pl == null) return;
        for (Object[] ur : pl) {
            List<Object> sub = new ArrayList<>();
            unwindGo(preds, start, ur[0], limit - out.size(), sub);
            for (Object rs : sub) {
                out.add(Clj.conj((List<?>) rs, ur[1]));
                if (out.size() >= limit) return;
            }
        }
    }

    // ── 挑选 ──────────────────────────────────────────────

    /**
     * 按终点分组挑选（各前缀）：feed 喂一层（同一长度）的候选 [[终点 候选行的 Supplier] …]，返回「ends 里都凑够了」；
     * rows ⇒ 结果行（终点按首次出现的先后、组内按长度）。已凑够的终点不再算候选行。
     * D-48：pending ⇒ 还没凑够的终点；retain(点集) ⇒ 没凑够、又不在点集里的终点从上界里去掉，返回「都凑够了」。
     */
    static Selector pathSelector(Object kind, Object k, Object ends0) {
        return new Selector((Kw) kind, lng(k), (Iterable<Object>) ends0);
    }

    static final class Selector {
        private static final class Grp {
            List<Object> rows = new ArrayList<>();
            long groups;
            boolean closed;
        }

        final Kw kind;
        final long k;
        final Set<Object> ends;
        final LinkedHashMap<Object, Grp> groups = new LinkedHashMap<>();

        Selector(Kw kind, long k, Iterable<Object> ends0) {
            this.kind = kind;
            this.k = k;
            this.ends = new HashSet<>(Clj.vecOf(ends0));
        }

        boolean satisfied(Grp g) {
            if (kind == SHORTEST || kind == ANY) return g.rows.size() >= k;
            if (kind == ANY_SHORTEST) return g.rows.size() >= 1;
            if (kind == ALL_SHORTEST) return g.closed;
            if (kind == SHORTEST_GROUPS) return g.groups >= k;
            throw new IllegalArgumentException("No matching clause: " + kind);
        }

        private boolean allSatisfied() {
            for (Object e : ends) {
                Grp g = groups.get(e);
                if (g == null || !satisfied(g)) return false;
            }
            return true;
        }

        Set<Object> pending() {
            Set<Object> out = new HashSet<>();
            for (Object e : ends) {
                Grp g = groups.get(e);
                if (!(g != null && satisfied(g))) out.add(e);
            }
            return out;
        }

        /** cands：[[终点 Supplier&lt;Iterable&lt;Map&gt;&gt;] …]（Object[2]）。 */
        boolean feed(List<Object[]> cands) {
            Map<Object, Boolean> layer = new HashMap<>();
            for (Object[] c : cands) {
                Object end = c[0];
                if (!ends.contains(end)) continue;
                Grp g0 = groups.get(end);
                if (g0 != null && satisfied(g0)) continue;
                Iterator<Map<String, Object>> it = ((Supplier<Iterable<Map<String, Object>>>) c[1]).get().iterator();
                if (!it.hasNext()) continue;
                Grp g = g0 != null ? g0 : new Grp();
                List<Object> rows = new ArrayList<>(g.rows);
                if (kind == SHORTEST || kind == ANY) {
                    long need = k - g.rows.size();
                    for (long j = 0; j < need && it.hasNext(); j++) rows.add(it.next());
                } else if (kind == ANY_SHORTEST) {
                    if (g.rows.isEmpty()) rows = Clj.vec(it.next());
                } else {
                    while (it.hasNext()) rows.add(it.next());
                }
                g.rows = rows;
                groups.put(end, g);
                layer.put(end, true);
            }
            // 层末：这一层有结果的终点，组数 + 1、ALL SHORTEST 收口
            for (Object end : layer.keySet()) {
                Grp g = groups.get(end);
                g.groups++;
                g.closed = true;
            }
            return allSatisfied();
        }

        List<Object> rows() {
            List<Object> out = new ArrayList<>();
            for (Grp g : groups.values()) out.addAll(g.rows);
            return out;
        }

        boolean retain(Set<Object> keep) {
            for (Object e : pending()) if (!keep.contains(e)) ends.remove(e);
            return allSatisfied();
        }
    }

    /** clj 的 *prune-budget*：逐条剪的边数预算 [基数 每个截断项]（D-50；测试里调小来走分组退路）。List 两个 Long。 */
    static final ThreadLocal<List<Object>> PRUNE_BUDGET = ThreadLocal.withInitial(() -> List.of(10000L, 32L));

    static boolean isOver(Object e) {
        return e instanceof Over;
    }

    /** want 里各点反着 BFS（steps 各步类型的并集，不管关系不重复）⇒ {点 到 want 里最近一点的最少跳数}，至多 max 跳——逐条深搜的下界剪枝用（D-50）。 */
    static Map<Object, Long> distToSet(Exec.Ctx ctx, Set<Object> want, Object steps, Object max) {
        List<Object> idsDirs = distinctIdsDirs(lst(steps));
        Map<Object, Long> dist = new HashMap<>();
        for (Object w : want) dist.put(w, 0L);
        List<Object> frontier = new ArrayList<>(want);
        long d = 0, mxl = lng(max);
        while (!frontier.isEmpty() && d < mxl) {
            List<Object> nxt = new ArrayList<>();
            for (Object u : frontier)
                for (Object idd : idsDirs)
                    for (RelHop h : edgesByIds(ctx, u, Clj.nth(idd, 0), flip(Clj.nth(idd, 1)))) {
                        Object o = h.other();
                        if (!dist.containsKey(o)) {
                            dist.put(o, d + 1);
                            nxt.add(o);
                        }
                    }
            frontier = nxt;
            d++;
        }
        return dist;
    }

    /** (into [] (comp (mapcat :ids-dirs) (distinct)) steps)。 */
    private static List<Object> distinctIdsDirs(List<Object> steps) {
        LinkedHashSet<Object> s = new LinkedHashSet<>();
        for (Object st : steps) s.addAll(lst(g(st, IDS_DIRS)));
        return new ArrayList<>(s);
    }

    static final long WITNESS_MAX_DEPTH = 4096;

    /**
     * 从截断项 [第几步 i 点 u 已用关系 这一步已走 c 跳（null = 不知道 ⇒ 0..下限都试）] 出发、先在第 i 步走至少一跳、关系不在已用里且互不重复、
     * 按 steps（每步 {:ids-dirs [[类型 id 方向] …] :mn :mx}，类型已解析）走完、总跳数 ≤ hops 能到的 want 里的点（D-50）。
     * 深度优先，按 dist（到 want 的最少跳数，下界）剪枝；budget（long[1]，按展开的边数扣）扣完或深度超过 WITNESS_MAX_DEPTH ⇒ 抛 Over。
     * 找全 want ⇒ 提前停。返回找到的点集——深搜走完而没找到的点，确实没有关系不重复的接续能到。
     */
    static Set<Object> trailWitness(Exec.Ctx ctx, Object steps, Object item, Set<Object> want, Object hops, Map<Object, Long> dist,
                                    long[] budget) {
        return new Witness(ctx, lst(steps), (List<Object>) item, want, lng(hops), dist, budget).run();
    }

    private static final class Witness {
        final Exec.Ctx ctx;
        final List<Object> steps;
        final int n, i0;
        final Object u0, c0;
        final Set<Object> used0, want, found = new HashSet<>();
        final long hops0;
        final Map<Object, Long> dist;
        final long[] budget;

        Witness(Exec.Ctx ctx, List<Object> steps, List<Object> item, Set<Object> want, long hops, Map<Object, Long> dist, long[] budget) {
            this.ctx = ctx;
            this.steps = steps;
            this.n = steps.size();
            this.i0 = (int) lng(item.get(0));
            this.u0 = item.get(1);
            this.used0 = item.get(2) == null ? Set.of() : (Set<Object>) item.get(2);
            this.c0 = Clj.nth(item, 3);
            this.want = want;
            this.hops0 = hops;
            this.dist = dist;
            this.budget = budget;
        }

        private void charge(int k) {
            budget[0] -= k;
            if (budget[0] < 0) throw new Over();
        }

        private long mnOf(int i) {
            return lng(g(steps.get(i), MN));
        }

        Set<Object> run() {
            // 边数上界：关系不重复的接续最多把从 u0 能走到的、没用过的关系各走一遍；还要走的跳数（第 i0 步至少 1 跳、余下的到下限、
            // 后面各步的下限）比它多 ⇒ 一个也到不了
            List<Object> idsDirs = distinctIdsDirs(steps.subList(i0, n));
            Set<Object> rels = new HashSet<>(), seen = new HashSet<>();
            seen.add(u0);
            List<Object> frontier = Clj.vec(u0);
            while (!frontier.isEmpty()) {
                List<Object> nxt = new ArrayList<>();
                for (Object u : frontier)
                    for (Object idd : idsDirs) {
                        List<RelHop> es = edgesByIds(ctx, u, Clj.nth(idd, 0), Clj.nth(idd, 1));
                        charge(es.size());
                        for (RelHop h : es) {
                            Object id = h.rel().id();
                            if (!used0.contains(id)) {
                                rels.add(id);
                                if (seen.add(h.other())) nxt.add(h.other());
                            }
                        }
                    }
                frontier = nxt;
            }
            long avail = rels.size();
            long rest = 0;
            for (int j = i0 + 1; j < n; j++) rest += mnOf(j);
            long need = Math.max(1, mnOf(i0) - (c0 == null ? mnOf(i0) : lng(c0))) + rest;
            long hops = Math.min(hops0, avail);
            if (need <= avail) hopFrom(i0, u0, c0 == null ? mnOf(i0) : lng(c0), used0, hops, 0);
            return new HashSet<>(found);
        }

        private boolean done() {
            return found.size() >= want.size();
        }

        // 第 i0 步里已走的跳数不知道（null）：按「下限已满足、上限不限」算（两头都放宽）
        private void hopFrom(int i, Object u, long c, Set<Object> used, long h, long depth) {
            Object st = steps.get(i);
            long mx = lng(g(st, MX));
            if ((c < mx || (c0 == null && i == i0)) && h > 0) {
                if (depth > WITNESS_MAX_DEPTH) throw new Over();
                for (Object idd : lst(g(st, IDS_DIRS))) {
                    List<RelHop> es = edgesByIds(ctx, u, Clj.nth(idd, 0), Clj.nth(idd, 1));
                    charge(es.size());
                    for (RelHop hp : es) {
                        Object id = hp.rel().id();
                        Long d = dist.get(hp.other());
                        if (!done() && !used.contains(id) && d != null && d <= h - 1)
                            visit(i, hp.other(), c + 1, conjSet(used, id), h - 1, depth + 1);
                    }
                }
            }
        }

        private void visit(int i, Object u, long c, Set<Object> used, long h, long depth) {
            if (done()) return;
            if (c >= mnOf(i)) {
                if (i + 1 == n) {
                    if (want.contains(u)) found.add(u);
                } else {
                    visit(i + 1, u, 0, used, h, depth);
                }
            }
            hopFrom(i, u, c, used, h, depth);
        }
    }

    /**
     * 逐轮剪可达点上界（D-48 / D-49 / D-50），返回「都凑够了」。截断项 [第几步 点 已用关系 这一步已走跳数]：
     * 1. 全局：global(want) ⇒ 从全部截断处、不管已用关系能到的点；没凑够又到不了的去掉。
     * 2. 逐条：剩下没凑够的，对每条部分路径 perPath(截断项, want, budget, 不走的关系) 排除它已用的关系求可达（D-49）；
     *    求得到的再用 {@link #trailWitness} 深搜一条关系互不重复、合乎每步下限 / 上限的接续来确认（D-50）。某条路径确认的终点不再找，都确认了就停。
     * 3. 预算：可达与深搜各有边数预算（32 × 截断项数 + 10000）。深搜的超了 ⇒ 之后的只按可达算（保守）；可达的超了 ⇒ 余下的截断项按
     *    （第几步, 点）分组、不走组内各条共同用过的关系，每组求一次可达（D-50）。
     */
    static boolean pruneRound(Exec.Ctx ctx, Selector sel, Function<Set<Object>, Set<Object>> global, PerPath perPath, Object steps,
                              long hops, Iterable<Object> items0) {
        Set<Object> want = sel.pending();
        if (want.isEmpty()) return true;
        if (sel.retain(global.apply(want))) return true;
        List<Object> items = Clj.vecOf(items0);
        int n = items.size();
        List<Object> pb = PRUNE_BUDGET.get();
        long[] budget = {lng(pb.get(0)) + lng(pb.get(1)) * n};
        long[] wbudget = {lng(pb.get(0)) + lng(pb.get(1)) * n};
        Set<Object> want0 = sel.pending();
        Map<Object, Long>[] dist = new Map[1];
        Set<Object> keep = new HashSet<>();
        Integer from = null;
        Set<Object> w = new HashSet<>(want0);
        boolean wit = true;
        for (int k = 0; k < n && !w.isEmpty(); k++) {
            List<Object> item = (List<Object>) items.get(k);
            Set<Object> found;
            try {
                found = perPath.apply(item, w, budget, (Set<Object>) Clj.nth(item, 2));
            } catch (Over e) {
                from = k;
                break;
            }
            Set<Object> cand = new HashSet<>();
            for (Object x : w) if (found.contains(x)) cand.add(x);
            Set<Object> conf;
            if (wit && !cand.isEmpty()) {
                try {
                    if (dist[0] == null) dist[0] = distToSet(ctx, want0, steps, hops);
                    conf = trailWitness(ctx, steps, item, cand, hops, dist[0], wbudget);
                } catch (Over e) {
                    conf = cand;
                    wit = false;
                }
            } else {
                conf = cand;
            }
            w.removeAll(conf);
            keep.addAll(conf);
        }
        if (from != null) {
            // 可达的预算超了：余下的按（第几步, 点）分组，组内共同用过的关系才不走
            LinkedHashMap<List<Object>, List<List<Object>>> groups = new LinkedHashMap<>();
            for (Object it : items.subList(from, n)) {
                List<Object> item = (List<Object>) it;
                groups.computeIfAbsent(Clj.vec(item.get(0), item.get(1)), x -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<List<Object>, List<List<Object>>> e : groups.entrySet()) {
                Set<Object> wr = new HashSet<>(want0);
                wr.removeAll(keep);
                if (wr.isEmpty()) break;
                List<List<Object>> its = e.getValue();
                Set<Object> banned = new HashSet<>(setOrEmpty(Clj.nth(its.getFirst(), 2)));
                for (List<Object> it : its.subList(1, its.size())) {
                    Set<Object> used = setOrEmpty(Clj.nth(it, 2));
                    banned.removeIf(x -> !used.contains(x));
                }
                Set<Object> cs = new HashSet<>();
                for (List<Object> it : its) cs.add(Clj.nth(it, 3));
                Object i = e.getKey().get(0), u = e.getKey().get(1);
                Set<Object> found = perPath.apply(Clj.vec(i, u, banned, cs.size() == 1 ? cs.iterator().next() : null), wr, null, banned);
                for (Object x : wr) if (found.contains(x)) keep.add(x);
            }
        }
        return sel.retain(keep);
    }

    // ── 路径搜索 ──────────────────────────────────────────

    /**
     * ANY / ALL SHORTEST（以及 k = 1 的 SHORTEST / ANY、SHORTEST 1 GROUPS）、下限 ≤ 1、单段变长关系、候选上只有关系属性检查（没有挑选前的条件）
     * ⇒ 按点 BFS 求最短（最短路径必是简单路径，满足关系不重复）；终点 = 起点（环路）另走分层搜索（D-45：同 GQL，返回环路）。
     * 不适用 ⇒ FALLBACK。
     */
    static Object shortestFast(Exec.Ctx ctx, Map<String, Object> row, Object argsO, long k) {
        List<Object> args = lst(argsO);
        Object pv = Clj.nth(args, 0), from = Clj.nth(args, 1), r = Clj.nth(args, 2), types = Clj.nth(args, 3), dir = Clj.nth(args, 4);
        Object mn = Clj.nth(args, 5), mx = Clj.nth(args, 6), to = Clj.nth(args, 7), uniq = Clj.nth(args, 8);
        Map<Object, Object> prefix = (Map<Object, Object>) Clj.nth(args, 9);
        List<Object> inner = lst(Clj.nth(args, 10));
        Object mode = Clj.nth(args, 11), revQ = Clj.nth(args, 12), spec = Clj.nth(args, 13);
        boolean rev = Clj.truthy(revQ);
        Object kind0 = g(prefix, KIND);
        Kw kind = (kind0 == SHORTEST || kind0 == ANY) && k == 1 ? ANY_SHORTEST
                : kind0 == SHORTEST_GROUPS && k == 1 ? ALL_SHORTEST : (Kw) kind0;
        long mn1 = mn == null ? 1 : lng(mn);
        boolean innerOk = true;
        for (Object o : inner) if (Clj.head(o) != REL_CHECK && Clj.head(o) != BUILD_PATH) innerOk = false;
        if (!(spec == null && (kind == ANY_SHORTEST || kind == ALL_SHORTEST) && mn1 <= 1 && innerOk)) return FALLBACK;
        Object f = row.get(from);
        Object t = mode == BOUND ? row.get(to) : null;
        List<Object> checks = new ArrayList<>();
        for (Object o : inner) if (Clj.head(o) == REL_CHECK) checks.add(o);
        Predicate<VRel> relOk = checks.isEmpty() ? rel -> true
                : rel -> runOps(ctx, checks, one(assoc(row, r, Clj.vec(rel)))).iterator().hasNext();
        long start = vidOf(f);
        List<Object> bp = bfsPreds(ctx, start, typeIds(ctx, types), dir, mx == null ? Long.MAX_VALUE : mx, usedRels(uniq, row), relOk);
        List<Object> order = (List<Object>) bp.get(0);
        Map<Object, List<Object[]>> preds = (Map<Object, List<Object[]>>) bp.get(2);
        Function<Object[], List<Map<String, Object>>> build = er -> {
            List<Object> rels = rev ? rev(lst(er[1])) : new ArrayList<>(lst(er[1]));
            return toList(runOps(ctx, inner, one(assoc(row, r, rels, to, new VNode(lng(er[0]))))));
        };
        // 返回 [行, 是否 clj 的惰性序列]：clj 里 (into 序列 …) 是往前 conj，顺序要跟着倒
        Function<Object, Object[]> forEnd = end -> {
            if (lng(end) == start) {
                if (mn1 == 0) return new Object[]{build.apply(new Object[]{end, Clj.vec()}), true};
                // 环路：分层搜索（终点 = 起点）
                return new Object[]{pathSearch(ctx, assoc(row, to, new VNode(start)),
                        Clj.vec(pv, from, r, types, dir, mn, mx, to, uniq, Clj.assoc(prefix, KIND, kind, K, 1L), inner, BOUND, revQ, null)), false};
            }
            List<Object> paths = unwindAll(preds, start, end, kind == ANY_SHORTEST ? 1 : MAX_ALL_PATHS);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object p : paths) out.addAll(build.apply(new Object[]{end, p}));
            return new Object[]{out, false};
        };
        if (mode == BOUND) {
            long tv = vidOf(t);
            return tv == start || preds.containsKey(tv) ? (List<Object>) forEnd.apply(tv)[0] : Clj.vec();
        }
        Object[] first = forEnd.apply(start);
        List<Object> restRows = new ArrayList<>();
        for (Object e : order) restRows.addAll((List<Object>) forEnd.apply(e)[0]);
        List<Object> out = new ArrayList<>();
        if ((Boolean) first[1]) {
            // (into 惰性序列 xs)：xs 逐个 cons 到前面
            out.addAll(rev(restRows));
            out.addAll((List<Object>) first[0]);
        } else {
            out.addAll((List<Object>) first[0]);
            out.addAll(restRows);
        }
        return out;
    }

    private static long prefixK(Exec.Ctx ctx, Object k) {
        if (k == null) return 1;
        if (Clj.isInteger(k)) return lng(k);
        Object v = ev(ctx, k, Map.of());
        return v == null ? 1 : lng(v);
    }

    /**
     * 带前缀的单段变长 / 量化 path pattern（D-43、D-44）：按长度一层层地找路径（路径内关系不重复，也不与本 MATCH 已绑定的重复），
     * 每条候选跑 inner（关系属性检查、拼路径、挑选前的条件），按终点分组挑：
     * - 终点已绑定（mode :bound）：凑够前缀要的就停；
     * - 终点是自由的（mode :free，搜索把它绑上）：先求可达点的上界，上界里每个点都凑够了就停，否则走完为止。
     * rev ⇒ 从模式的右端搜（关系 / 迭代倒回模式的顺序）。同一长度内的先后与原来深度优先的发现序相同。
     * args（不含算子头）：[pv from r types dir mn mx to uniq prefix inner mode rev? spec end-conds]。
     */
    static List<Object> pathSearch(Exec.Ctx ctx, Map<String, Object> row, Object argsO) {
        List<Object> args = lst(argsO);
        Object from = Clj.nth(args, 1), r = Clj.nth(args, 2), types = Clj.nth(args, 3), dir = Clj.nth(args, 4);
        Object mn = Clj.nth(args, 5), mx = Clj.nth(args, 6), to = Clj.nth(args, 7), uniq = Clj.nth(args, 8);
        Object prefix = Clj.nth(args, 9);
        List<Object> inner = lst(Clj.nth(args, 10));
        Object mode = Clj.nth(args, 11);
        boolean rev = Clj.truthy(Clj.nth(args, 12));
        Object spec0 = Clj.nth(args, 13);
        List<Object> endConds = lst(Clj.nth(args, 14));
        Object f = row.get(from);
        Object t = mode == BOUND ? row.get(to) : null;
        if (!(f instanceof VNode && (mode == FREE || t instanceof VNode))) return Clj.vec();
        long k = prefixK(ctx, g(prefix, K));
        Object fast = k > 0 && !Clj.truthy(row.get(IN_FAST)) ? shortestFast(ctx, assoc(row, IN_FAST, true), args, k) : FALLBACK;
        if (fast != FALLBACK) {
            List<Object> out = new ArrayList<>();
            for (Object rw : (List<Object>) fast) out.add(Clj.dissoc((Map<String, Object>) rw, IN_FAST));
            return out;
        }
        Map<Object, Object> spec = spec0 == null ? null : rev ? revSpec(spec0) : (Map<Object, Object>) spec0;
        Set<Object> used = usedRels(uniq, row);
        long fv = vidOf(f);
        // D-47：只引用终点的挑选前条件先用在可达点上界上——通不过的终点永远凑不够，不该让它拖住搜索
        Predicate<Object> endOk = vid -> {
            for (Object c : endConds)
                if (!Expr.isPredicateTrue(ev(ctx, c, assoc(row, to, new VNode(lng(vid)))))) return false;
            return true;
        };
        List<Object> hopIds = spec != null ? qppIdsDirs(ctx, spec) : Clj.vec(Clj.vec(typeIds(ctx, types), dir));
        Set<Object> ends;
        if (mode == BOUND) {
            ends = new HashSet<>();
            ends.add(vidOf(t));
        } else {
            Set<Object> rs = spec != null ? reachSet(ctx, List.of(fv), hopIds, Long.MAX_VALUE, lng(spec.get(MN)) == 0, null)
                    : reachSet(ctx, List.of(fv), hopIds, mx, lng(mn) == 0, null);
            ends = new HashSet<>();
            for (Object v : rs) if (endOk.test(v)) ends.add(v);
        }
        Selector sel = pathSelector(g(prefix, KIND), k, ends);
        long mxS = spec != null ? Long.MAX_VALUE : mx == null ? Long.MAX_VALUE : lng(mx);
        // 逐条深搜（D-50）用的一步：量化路径按并集类型、跳数下限 = 迭代下限 × 一次迭代的下限
        List<Object> wsteps = Clj.vec(Clj.map(IDS_DIRS, hopIds, MN, spec != null ? specLo(spec) : mn == null ? 1L : lng(mn), MX, mxS));
        LayerFn onLayer = (len, cands, tips) -> {
            List<Object[]> fc = new ArrayList<>(cands.size());
            for (Object c : cands) {
                Object end = Clj.nth(c, 0), rels0 = Clj.nth(c, 1), iters0 = Clj.nth(c, 2);
                fc.add(new Object[]{end, (Supplier<Iterable<Map<String, Object>>>) () -> {
                    List<Object> rels = rev ? rev(lst(rels0)) : new ArrayList<>(lst(rels0));
                    Map<String, Object> row2 = assoc(row, r, rels, to, new VNode(lng(end)));
                    if (spec != null) {
                        List<Object> iters = rev ? rev(lst(iters0)) : lst(iters0);
                        for (Object gv : lst(spec.get(GROUPS))) row2 = assoc(row2, gv, collect(iters, gv));
                    }
                    return runOps(ctx, inner, one(row2));
                }});
            }
            if (sel.feed(fc)) return true;
            // D-48 / D-49：以后的候选都从前沿再走至少一跳——没凑够、又从前沿到不了的终点去掉
            long rem = mxS - len;
            List<Object> tipVs = new ArrayList<>();
            List<Object> items = new ArrayList<>();
            for (Object tp : tips) {
                tipVs.add(Clj.nth(tp, 0));
                items.add(Clj.vec(0L, Clj.nth(tp, 0), Clj.nth(tp, 1), Clj.nth(tp, 2)));
            }
            return pruneRound(ctx, sel,
                    want -> reachSet(ctx, tipVs, hopIds, rem, false, want),
                    (item, want, budget, banned) -> reachSet(ctx, List.of(item.get(1)), hopIds, rem, false, want, banned, budget),
                    wsteps, rem, items);
        };
        if (k <= 0) return Clj.vec();
        if (spec != null) {
            qppLayers(ctx, spec, row, fv, used, onLayer);
        } else {
            KeepFn keep = null;
            if (mode == BOUND) {
                // 终点已知：到不了终点（或上限内到不了）的部分路径不再展开
                long mxl = mx == null ? Long.MAX_VALUE : lng(mx);
                Map<Object, Long> dist = distTo(ctx, vidOf(t), typeIds(ctx, types), dir, mxl);
                keep = (o, len) -> {
                    Long d = dist.get(o);
                    return d != null && len + d <= mxl;
                };
            }
            varlenLayers(ctx, fv, types, dir, mn == null ? 1L : mn, mx == null ? Long.MAX_VALUE : mx, used, onLayer, keep);
        }
        return sel.rows();
    }

    /**
     * 带前缀的多段 path pattern（D-45）：起点（mode :bound 时终点也）已绑定；整段模式按总长 L = 0, 1, … 交出长度正好 L 的行，按终点分组挑；
     * 凑够（:free 时可达点上界里都凑够）或某轮没有截断点 ⇒ 停。
     * D-47 接续式加深：第一轮整段模式在 cap 0 下跑一遍；之后每轮只从上一轮登记的续体接着走一跳，新出的行再过那个算子后面的算子，不从头重跑。
     * 首尾同一变量（环路）：:bound，终点 = 起点，候选按路径的末端算。
     * args（不含算子头）：[pv from to mode prefix inner rel-vars steps max-hops n0 end-conds]。
     */
    static List<Object> pathDeepen(Exec.Ctx ctx, Map<String, Object> row, Object argsO) {
        List<Object> args = lst(argsO);
        Object pv = Clj.nth(args, 0), from = Clj.nth(args, 1), to = Clj.nth(args, 2), mode = Clj.nth(args, 3), prefix = Clj.nth(args, 4);
        List<Object> inner = lst(Clj.nth(args, 5)), relVars = lst(Clj.nth(args, 6)), steps = lst(Clj.nth(args, 7));
        long maxHops = lng(Clj.nth(args, 8));
        Object n0 = Clj.nth(args, 9);
        List<Object> endConds = lst(Clj.nth(args, 10));
        Object f = row.get(from);
        Object t = mode == BOUND ? row.get(to) : null;
        if (!(f instanceof VNode && (mode == FREE || t instanceof VNode))) return Clj.vec();
        long k = prefixK(ctx, g(prefix, K));
        Predicate<Object> endOk = vid -> {
            for (Object c : endConds)
                if (!Expr.isPredicateTrue(ev(ctx, c, assoc(row, to, new VNode(lng(vid)))))) return false;
            return true;
        };
        Set<Object> ends = new HashSet<>();
        if (mode == BOUND) ends.add(vidOf(t));
        else for (Object v : reachEnds(ctx, vidOf(f), steps, maxHops)) if (endOk.test(v)) ends.add(v);
        Selector sel = pathSelector(g(prefix, KIND), k, ends);
        Map<Object, Object> idx = new HashMap<>();
        for (int j = 0; j < inner.size(); j++) idx.put(inner.get(j), (long) j);
        boolean fwd = Objects.equals(from, n0);
        // D-48：截断处登记成 [搜索顺序上的第几步 点 已用关系 跳数]
        int nsteps = steps.size();
        Map<Object, Object> stepOf = new HashMap<>();
        for (int p = 0; p < relVars.size(); p++) stepOf.put(relVars.get(p), (long) (fwd ? p : nsteps - 1 - p));
        AtomicReference<List<Object>> tips = new AtomicReference<>(new ArrayList<>());
        BiConsumer<Object, Object> tip = (v, us) -> {
            Object i = stepOf.get(v);
            List<Object> l = new ArrayList<>(tips.get());
            for (Object x : lst(us)) l.add(Clj.vec(i, Clj.nth(x, 0), Clj.nth(x, 1), Clj.nth(x, 2)));
            tips.set(l);
        };
        List<Object> wsteps = new ArrayList<>(nsteps);
        for (Object st : steps) {
            List<Object> l = new ArrayList<>();
            for (Object td : lst(g(st, IDS_DIRS))) l.add(Clj.vec(typeIds(ctx, Clj.nth(td, 0)), Clj.nth(td, 1)));
            wsteps.add(Clj.assoc((Map<Object, Object>) st, IDS_DIRS, l));
        }
        // 以后的行都从截断处在那一步里再走一跳；之后按 reach-ends 的状态走（这一步已走的跳数不知道 ⇒ 1..mn 都算上）
        // ts：[[第几步 点 这一步已走跳数（null = 不知道）] …]；banned / budget 是逐条剪时的这条路径已用的关系与预算（D-49）
        FutureEnds futureEnds = (len, ts, want, banned, budget) -> {
            LinkedHashSet<Object> seeds = new LinkedHashSet<>();
            for (Object tsi : ts) {
                Object iO = Clj.nth(tsi, 0), u = Clj.nth(tsi, 1), c = Clj.nth(tsi, 2);
                Object ws = wsteps.get((int) lng(iO));
                long mn = lng(g(ws, MN));
                for (Object idd : lst(g(ws, IDS_DIRS)))
                    for (Object o : nextNodes(ctx, u, Clj.nth(idd, 0), Clj.nth(idd, 1), banned, budget)) {
                        if (c != null) seeds.add(Clj.vec(o, iO, Math.min(lng(c) + 1, mn)));
                        else for (long cc = Math.min(1, mn); cc <= mn; cc++) seeds.add(Clj.vec(o, iO, cc));
                    }
            }
            return reachEnds(ctx, new ArrayList<>(seeds), steps, Math.max(0, maxHops - len - 1), want, banned, budget);
        };
        // 搜索终点：顺着模式搜 ⇒ 路径的最后一个点；倒着搜 ⇒ 第一个点
        Function<Map<String, Object>, Object> endOf = rw -> {
            List<Object> ns = ((VPath) rw.get(pv)).nodes();
            return vidOf(fwd ? ns.getLast() : ns.getFirst());
        };
        if (k <= 0) return Clj.vec();
        // cap / parts 跨轮不变：续体闭包里抓住的就是它们（每轮只把 parts 清空）
        AtomicReference<Object> cap = new AtomicReference<>(0L), parts = new AtomicReference<>(new ArrayList<>()),
                hit = new AtomicReference<>(false);
        long len = 0;
        List<Object> frontier = null;
        while (true) {
            parts.set(new ArrayList<>());
            tips.set(new ArrayList<>());
            Map<Object, Object> lc = Clj.map(VARS, relVars, CAP, cap, HIT, hit, PARTS, parts, IDX, idx, TIP, tip);
            List<Map<String, Object>> rows;
            Map<Object, Object> prev = LEN_CAP.get();
            LEN_CAP.set(lc);
            try {
                if (len == 0) {
                    rows = toList(runOps(ctx, inner, one(row)));
                } else {
                    // 接着走：续体交出新行，再过截断算子后面的算子
                    rows = new ArrayList<>();
                    for (Object part : frontier) {
                        int i = (int) lng(Clj.nth(part, 0));
                        Supplier<Object> thunk = (Supplier<Object>) Clj.nth(part, 1);
                        List<Map<String, Object>> got = toList((Iterable<Map<String, Object>>) thunk.get());
                        rows.addAll(toList(runOps(ctx, inner.subList(i + 1, inner.size()), got)));
                    }
                }
            } finally {
                LEN_CAP.set(prev);
            }
            // 同一终点的几条候选各自成一个 thunk：选择器按先后取
            List<Object[]> cands = new ArrayList<>();
            for (Map<String, Object> rw : rows) {
                Object p = rw.get(pv);
                if (p instanceof VPath vp && len == vp.rels().size())
                    cands.add(new Object[]{endOf.apply(rw), (Supplier<Iterable<Map<String, Object>>>) () -> one(rw)});
            }
            boolean done = sel.feed(cands);
            final long flen = len;
            if (done || lst(parts.get()).isEmpty() || pruneDeepen(ctx, sel, futureEnds, wsteps, maxHops, flen, tips.get()))
                return sel.rows();
            len++;
            frontier = lst(parts.get());
        }
    }

    @FunctionalInterface
    private interface FutureEnds {
        Set<Object> apply(long len, List<Object> ts, Set<Object> want, Set<Object> banned, long[] budget);
    }

    /** path-deepen 的一轮剪枝（D-48 / D-49：没凑够、又从截断处到不了的终点去掉；都凑够了（或没剩下）就停）。 */
    private static boolean pruneDeepen(Exec.Ctx ctx, Selector sel, FutureEnds futureEnds, List<Object> wsteps, long maxHops, long len,
                                       List<Object> ts) {
        return pruneRound(ctx, sel,
                want -> {
                    LinkedHashSet<Object> g0 = new LinkedHashSet<>();
                    for (Object x : ts) g0.add(Clj.vec(Clj.nth(x, 0), Clj.nth(x, 1), null));
                    return futureEnds.apply(len, new ArrayList<>(g0), want, null, null);
                },
                (item, want, budget, banned) ->
                        futureEnds.apply(len, List.of(Clj.vec(item.get(0), item.get(1), Clj.nth(item, 3))), want, banned, budget),
                wsteps, Math.max(0, maxHops - len), ts);
    }
}
