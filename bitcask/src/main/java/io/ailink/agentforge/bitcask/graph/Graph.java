package io.ailink.agentforge.bitcask.graph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import io.ailink.agentforge.bitcask.BatchOp;
import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.Condition;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.VersionedValue;
import io.ailink.agentforge.bitcask.graph.Adjacency.AdjEntry;
import io.ailink.agentforge.bitcask.graph.Adjacency.FullEntry;
import io.ailink.agentforge.bitcask.graph.Adjacency.Group;
import io.ailink.agentforge.bitcask.graph.Adjacency.LB;
import io.ailink.agentforge.bitcask.graph.Adjacency.Session;

/**
 * 图存储层 V1（邻接表布局 + 保留命名空间前缀；与 clj-bitcask {@code im.ttalk.bitcask.graph}、Erlang bitcask 图层数据互通）。
 * 一个顶点两个 KV —— 顶点 {@code n} + 邻接表头 {@code a}；hub 超过阈值把最大的组拆成块；超过内联上限的边属性落 {@code p} 家族。
 * 新库是布局 2（g 版本 2，keelson D-51）：块按条数切、块数多的组的块目录外置成 {@code d} 家族的 B+ 树；布局 1 的库照旧读写，
 * {@link #upgradeLayout} 之后碰到的组再转（数据与 clj-bitcask 逐字节相同）。
 * <ul>
 * <li>取邻居 / 度数 = 一次点查。</li>
 * <li>写边 = 两端邻接表头的条件写（读头带回 LSN，提交时有人先改过 ⇒ 整批不写 ⇒ 重读重试，缺省 8 次、指数抖动退避，耗尽抛
 *     {@code bitcask/graph-conflict}）：并发写不丢边、度数精确、两端对称——不要求单写者。</li>
 * <li>批量装载用 {@link #putEdges}（按 (src, etype, dst, rank) 排序、按顶点聚合，每个顶点一批只写一次）。</li>
 * <li>本对象不拥有库句柄；同一个库上可以有别的键（保留命名空间保证互不干扰）。</li>
 * <li>vid / rank 是 64 位无符号（long 按无符号解释），etype 是 32 位无符号。</li>
 * <li>一致性：未分块顶点一次点查即原子快照；分块顶点 {@link AdjQuery#consistent} 读前读后比对头的 LSN；多跳遍历跨跳弱一致。</li>
 * <li>数据模型（{@link GraphModel}）：顶点可带标签与属性、有标签索引、有统一的 id 分配器（{@link #createVertex}）；Cypher 与本 API 是同一套图 CRUD 的两个入口。</li>
 * </ul>
 * 线程安全（无可变状态；参数缓存按库句柄全局共享）。
 */
public final class Graph {
    public static final int DEFAULT_RETRIES = 8;
    public static final int DEFAULT_BATCH_VIDS = 256;
    public static final int DELETE_NEIGHBORS = 64;
    private static final int CONSISTENT_TRIES = 32;
    private static final byte[] EMPTY = GraphModel.EMPTY;

    /** g 键按库句柄（身份）缓存解出的参数，写会话不必每次读 g；{@link #upgradeLayout} 改写它时同时更新缓存（别的句柄要重开）。 */
    private static final Map<Bitcask, GraphConfig> CFG_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private final Bitcask db;
    private final int retries;

    private Graph(Bitcask db, int retries) {
        this.db = Objects.requireNonNull(db, "db");
        this.retries = retries;
    }

    /** 包一个库句柄（KV 库或检索库都行；本对象不拥有它，不负责关闭）。 */
    public static Graph open(Bitcask db) {
        return new Graph(db, DEFAULT_RETRIES);
    }

    /** 换写冲突重试次数（&lt; 0 = 不限）。 */
    public Graph withRetries(int retries) {
        return new Graph(db, retries);
    }

    public Bitcask db() {
        return db;
    }

    public int retries() {
        return retries;
    }

    /** 边方向。 */
    public enum Direction {
        OUT, IN, BOTH;

        Integer code() {
            return switch (this) {
                case OUT -> 0;
                case IN -> 1;
                case BOTH -> null;
            };
        }
    }

    // ═══ 布局 / 参数 ═══

    /** 图布局：{@code "v1"}（有 g 键）| {@code "empty"}。g 里的版本（块目录在头里的布局 1 / 目录外置的布局 2）见 {@link GraphConfig#layout()}。 */
    public String layout() {
        return db.get(GraphKeys.GRAPH_META) != null ? "v1" : "empty";
    }

    private static GraphConfig readCfg(Bitcask db) {
        byte[] bv = db.get(GraphKeys.GRAPH_META);
        if (bv == null) return null;
        GraphConfig cfg = GraphConfig.decode(bv);
        if (cfg == null) throw BitcaskException.of("bitcask/graph-layout-unknown");
        CFG_CACHE.put(db, cfg);
        return cfg;
    }

    private static GraphConfig cachedCfg(Bitcask db) {
        GraphConfig c = CFG_CACHE.get(db);
        return c != null ? c : readCfg(db);
    }

    /** 生效的图参数（g 键；没有 ⇒ 缺省 = 布局 2）。 */
    public static GraphConfig config(Bitcask db) {
        GraphConfig c = cachedCfg(db);
        return c != null ? c : GraphConfig.DEFAULT;
    }

    public GraphConfig config() {
        return config(db);
    }

    public GraphConfig init() {
        return init(GraphConfig.DEFAULT);
    }

    /** 首次写入前指定参数并写 g 键（选项同 clj {@code init!}，见 {@link GraphConfig.Options}；缺省布局 2，{@code layout(1)} = 旧布局）。 */
    public GraphConfig init(GraphConfig.Options opts) {
        return initWith(opts.build());
    }

    /** 首次写入前指定分块参数并写 g 键；已有 g ⇒ 不改，回现有参数。布局版本不认得 ⇒ {@code bitcask/graph-layout-unknown}。 */
    public GraphConfig init(GraphConfig cfg) {
        return initWith(cfg.validated());
    }

    private GraphConfig initWith(GraphConfig c) {
        while (true) {
            GraphConfig cur = readCfg(db);
            if (cur != null) return cur;
            if (db.tryWriteAtomic(List.of(BatchOp.put(GraphKeys.ascii(GraphKeys.GRAPH_META), c.encode(), Condition.absent()))).isSuccess()) {
                CFG_CACHE.put(db, c);
                return c;
            }
        }
    }

    public GraphConfig upgradeLayout() {
        return upgradeLayout(GraphConfig.options());
    }

    /**
     * 布局 1 的库升级到布局 2：只改写 g（带版本条件），数据不整库重写 —— 之后被写到的组按布局 2 的规则逐个转换（块目录超过 dirInlineMax 外置、
     * 超限的块按条数切）；读不依赖已经转完。opts 只取布局 2 的那几个（不给 ⇒ 缺省；inline-max / props-max 沿用库里的，除非显式给）。
     * 单向：升级后旧版本的程序拒开。已是布局 2 / 还没有图 ⇒ 回现有 / 缺省参数，不写。别的句柄要重开才看得到新参数（g 按句柄缓存）。
     */
    public GraphConfig upgradeLayout(GraphConfig.Options opts) {
        while (true) {
            VersionedValue v = db.getWithOrd(GraphKeys.GRAPH_META);
            if (v == null) return config();
            GraphConfig cur = GraphConfig.decode(v.value());
            if (cur == null) throw BitcaskException.of("bitcask/graph-layout-unknown");
            if (cur.layout2()) return cur;
            GraphConfig.Options o = opts.copy();
            if (o.adjInlineMax == null) o.adjInlineMax(cur.inlineMax());
            if (o.propsInlineMax == null) o.propsInlineMax(cur.propsMax());
            GraphConfig cfg = o.layout(2).build();
            if (db.tryWriteAtomic(List.of(BatchOp.put(GraphKeys.ascii(GraphKeys.GRAPH_META), cfg.encode(), Condition.ord(v.ord())))).isSuccess()) {
                CFG_CACHE.put(db, cfg);
                return cfg;
            }
        }
    }

    /** 新写会话（首次写图时随批写 g 键）。Cypher 语句事务也用它。 */
    public static Session newSession(Bitcask db) {
        GraphConfig cfg = cachedCfg(db);
        if (cfg != null) return new Session(db, cfg, List.of());
        return new Session(db, GraphConfig.DEFAULT,
                List.of(BatchOp.put(GraphKeys.ascii(GraphKeys.GRAPH_META), GraphConfig.DEFAULT.encode(), Condition.absent())));
    }

    private <T> T withRetry(Supplier<T> f) {
        for (int attempt = 0; ; attempt++) {
            T r = f.get();
            if (r != Adjacency.Commit.CONFLICT) return r;
            if (retries >= 0 && attempt >= retries) throw BitcaskException.of("bitcask/graph-conflict");
            GraphModel.sleepBackoff(attempt);
        }
    }

    /** 一次会话：build(会话) ⇒ extra-ops；提交冲突整个重来。 */
    private Adjacency.Commit withSession(Function<Session, List<BatchOp>> build) {
        return withRetry(() -> {
            Session s = newSession(db);
            List<BatchOp> extra = build.apply(s);
            return s.commit(extra);
        });
    }

    // ═══ 顶点 ═══

    /** 写（整体替换）纯文本顶点：没有标签、没有属性（检索库里进 BM25——顶点即文档）。 */
    public void putVertex(long vid, String text) {
        putVertexValue(vid, Objects.requireNonNull(text, "spec"));
    }

    /**
     * 写（整体替换）顶点：标签随之替换，标签索引同批增删；与并发写同一顶点的写按版本条件串行。vid 不低于 id 高水位时先把水位推到 vid + 1。
     */
    public void putVertex(long vid, VertexSpec spec) {
        putVertexValue(vid, Objects.requireNonNull(spec, "spec"));
    }

    private void putVertexValue(long vid, Object spec) {
        byte[] nk = GraphKeys.nodeBytes(vid);
        GraphModel.VertexValue v = GraphModel.specToValue(spec);
        GraphModel.bumpWatermark(db, vid);
        withRetry(() -> {
            GraphModel.OldVertex o = GraphModel.oldVertex(db, vid);
            List<BatchOp> ops = new ArrayList<>();
            ops.add(GraphModel.valueOp(nk, v, o.cond()));
            ops.addAll(GraphModel.labelOps(vid, o.labels(), v.labels()));
            return db.tryWriteAtomic(ops).isConflict() ? Adjacency.Commit.CONFLICT : Adjacency.Commit.OK;
        });
    }

    /** 读顶点正文；没有 ⇒ null。 */
    public String vertex(long vid) {
        return db.getString(GraphKeys.node(vid));
    }

    /** 模型视图；不存在 ⇒ null。只有邻接表的顶点也算存在（没有标签、属性）。 */
    public VertexInfo vertexInfo(long vid) {
        return GraphModel.vertexInfo(db, vid);
    }

    public long createVertex(VertexSpec spec) {
        return createVertex(spec, 64);
    }

    /**
     * 自动分配 id 建顶点 ⇒ vid（从 1 起，全库一个 id 空间）。id 与顶点同批原子：并发调用拿不到同一个 id，也不会发出已被指定 id 写入占用的 id；
     * 删掉的 id 不再发出。retries &lt; 0 = 不限（计数器全库共享，缺省 64）。id 用尽 ⇒ {@code bitcask/graph-vid-exhausted}。
     */
    public long createVertex(VertexSpec spec, int retries) {
        GraphModel.VertexValue v = GraphModel.specToValue(spec == null ? new VertexSpec(null, null, null, null) : spec);
        return GraphModel.allocate(db, vid -> {
            List<BatchOp> ops = new ArrayList<>();
            ops.add(GraphModel.valueOp(GraphKeys.nodeBytes(vid), v, null));
            ops.addAll(GraphModel.labelOps(vid, List.of(), v.labels()));
            return ops;
        }, retries);
    }

    private void modifyLabels(long vid, Function<List<String>, List<String>> f) {
        withRetry(() -> {
            GraphModel.OldVertex o = GraphModel.oldVertex(db, vid);
            List<String> neu = f.apply(o.labels());
            List<BatchOp> ops = new ArrayList<>();
            if (o.doc() == null) {
                if (!GraphModel.vertexExists(db, vid)) throw BitcaskException.of("bitcask/graph-vertex-not-found", GraphModel.vidText(vid));
                if (neu.isEmpty()) return Adjacency.Commit.OK;
                ops.add(BatchOp.putDoc(GraphKeys.nodeBytes(vid),
                        new io.ailink.agentforge.bitcask.Document("", null, null, GraphModel.encodeMetaProps(neu, Map.of()), 0), Condition.absent()));
                ops.addAll(GraphModel.labelOps(vid, List.of(), neu));
            } else {
                if (o.labels().equals(neu)) return Adjacency.Commit.OK;
                ops.add(BatchOp.putDoc(GraphKeys.nodeBytes(vid), GraphModel.relabelValue(vid, o.doc(), neu), o.cond()));
                ops.addAll(GraphModel.labelOps(vid, o.labels(), neu));
            }
            return db.tryWriteAtomic(ops).isConflict() ? Adjacency.Commit.CONFLICT : Adjacency.Commit.OK;
        });
    }

    /** 加标签（已有的不重复；正文、属性、向量不变）。只有邻接表的顶点得到一个只有标签的文档；顶点不存在 ⇒ {@code bitcask/graph-vertex-not-found}。 */
    public void setLabels(long vid, Collection<String> labels) {
        List<String> add = GraphModel.normLabels(labels);
        modifyLabels(vid, old -> {
            LinkedHashSet<String> s = new LinkedHashSet<>(old);
            s.addAll(add);
            return List.copyOf(s);
        });
    }

    /** 去标签（没有的忽略）。 */
    public void removeLabels(long vid, Collection<String> labels) {
        Set<String> drop = Set.copyOf(GraphModel.normLabels(labels));
        modifyLabels(vid, old -> old.stream().filter(l -> !drop.contains(l)).toList());
    }

    /** 带标签的顶点 id，无符号升序（走标签索引）。 */
    public List<Long> verticesByLabel(String label) {
        return GraphModel.labelVids(db, label, -1, null);
    }

    /** 分页：limit &lt; 0 = 不限；after 非 null ⇒ 只要大于它的。 */
    public List<Long> verticesByLabel(String label, long limit, Long after) {
        return GraphModel.labelVids(db, label, limit, after);
    }

    /** 边的属性字节 ⇒ 属性 map。 */
    public static Map<String, Object> edgeProperties(byte[] props) {
        return GraphModel.decodeEdgeProps(props);
    }

    // ═══ 边 ═══

    private byte[] resolveProps(long src, long etype, long dst, long rank, byte[] p) {
        if (!Adjacency.isExt(p)) return p;
        byte[] v = db.get(GraphKeys.propsKey(src, etype, dst, rank));
        return v != null ? v : EMPTY;
    }

    public Edge edge(long src, long etype, long dst) {
        return edge(src, etype, dst, 0);
    }

    /** 点查边；没有 ⇒ null。 */
    public Edge edge(long src, long etype, long dst, long rank) {
        long et = Adjacency.u32(etype);
        byte[] p = Adjacency.lookup(db, src, 0, et, dst, rank);
        return p == null ? null : new Edge(src, et, dst, rank, resolveProps(src, et, dst, rank, p));
    }

    public void putEdge(long src, long etype, long dst) {
        putEdge(src, etype, dst, 0, null);
    }

    /**
     * 写边（upsert）：同 (src etype dst rank) 再写只改属性；否则两端邻接表一个带条件的原子批插入。props：byte[]、字符串，或属性 map
     * （meta 编码，Cypher 读出来就是属性）。端点超过 id 高水位时先推高它。
     */
    public void putEdge(long src, long etype, long dst, long rank, Object props) {
        byte[] p = GraphModel.encodeEdgeProps(props);
        long et = Adjacency.u32(etype);
        GraphModel.bumpWatermark(db, GraphModel.umax(src, dst));
        withSession(s -> {
            s.putEdge(src, et, dst, rank, p);
            return List.of();
        });
    }

    public void removeEdge(long src, long etype, long dst) {
        removeEdge(src, etype, dst, 0);
    }

    /** 幂等删边：不在 ⇒ 什么都不写；在 ⇒ 两端各摘一条（属性在 p 家族的一并删）。 */
    public void removeEdge(long src, long etype, long dst, long rank) {
        long et = Adjacency.u32(etype);
        withSession(s -> {
            s.delEdge(src, et, dst, rank);
            return List.of();
        });
    }

    // ═══ 批量 ═══

    /** 批量加边的一项。props 同 {@link #putEdge(long, long, long, long, Object)}。 */
    public record EdgeInput(long src, long etype, long dst, long rank, Object props) {
        public static EdgeInput of(long src, long etype, long dst) {
            return new EdgeInput(src, etype, dst, 0, null);
        }
    }

    /** 边的身份 (src, etype, dst, rank)。 */
    public record EdgeRef(long src, long etype, long dst, long rank) {
        public static EdgeRef of(long src, long etype, long dst) {
            return new EdgeRef(src, etype, dst, 0);
        }
    }

    /** 批量写选项：sort（缺省 true，按 (src, etype, dst, rank) 稳定排序）、batchVids（每批涉及的顶点上限，缺省 256，≥ 2）。 */
    public record BatchOptions(boolean sort, int batchVids) {
        public static final BatchOptions DEFAULT = new BatchOptions(true, DEFAULT_BATCH_VIDS);
    }

    private record Item(long src, long etype, long dst, long rank, byte[] props) {
    }

    private static final Comparator<Item> EDGE_ORDER = (a, b) -> {
        int c = Long.compareUnsigned(a.src, b.src);
        if (c != 0) return c;
        c = Long.compare(a.etype, b.etype);
        if (c != 0) return c;
        c = Long.compareUnsigned(a.dst, b.dst);
        return c != 0 ? c : Long.compareUnsigned(a.rank, b.rank);
    };

    private void runBatches(List<Item> items, BiConsumer<Session, Item> apply, BatchOptions opts) {
        BatchOptions o = opts == null ? BatchOptions.DEFAULT : opts;
        if (o.batchVids < 2) throw BitcaskException.of("bitcask/graph-config-invalid", "batch-vids", String.valueOf(o.batchVids));
        List<Item> xs = new ArrayList<>(items);
        if (o.sort) xs.sort(EDGE_ORDER);
        int i = 0;
        while (i < xs.size()) {
            Set<Long> vids = new HashSet<>();
            int j = i;
            while (j < xs.size()) {
                Item it = xs.get(j);
                Set<Long> v2 = new HashSet<>(vids);
                v2.add(it.src);
                v2.add(it.dst);
                if (v2.size() > o.batchVids && j > i) break;
                vids = v2;
                j++;
            }
            List<Item> batch = xs.subList(i, j);
            withSession(s -> {
                for (Item it : batch) apply.accept(s, it);
                return List.of();
            });
            i = j;
        }
    }

    public void putEdges(List<EdgeInput> edges) {
        putEdges(edges, BatchOptions.DEFAULT);
    }

    /**
     * 批量加边：先稳定排序（同 src 的边落进同一批，hub 的一批边落在相邻的块里；同一条边多次 upsert 仍按给的顺序生效），再按涉及的顶点切批，
     * 批内每个顶点读一次、改一次、写一次；每批独立原子、独立重试，批与批之间不原子。
     */
    public void putEdges(List<EdgeInput> edges, BatchOptions opts) {
        List<Item> items = new ArrayList<>(edges.size());
        long mx = 0;
        for (EdgeInput e : edges) {
            items.add(new Item(e.src, Adjacency.u32(e.etype), e.dst, e.rank, GraphModel.encodeEdgeProps(e.props)));
            mx = GraphModel.umax(mx, GraphModel.umax(e.src, e.dst));
        }
        if (!items.isEmpty()) GraphModel.bumpWatermark(db, mx);
        runBatches(items, (s, it) -> s.putEdge(it.src, it.etype, it.dst, it.rank, it.props), opts);
    }

    public void removeEdges(List<EdgeRef> edges) {
        removeEdges(edges, BatchOptions.DEFAULT);
    }

    /** 批量删边（幂等）；切批与原子性同 {@link #putEdges}。 */
    public void removeEdges(List<EdgeRef> edges, BatchOptions opts) {
        List<Item> items = new ArrayList<>(edges.size());
        for (EdgeRef e : edges) items.add(new Item(e.src, Adjacency.u32(e.etype), e.dst, e.rank, null));
        runBatches(items, (s, it) -> s.delEdge(it.src, it.etype, it.dst, it.rank), opts);
    }

    // ═══ 邻接读取 ═══

    /** 邻接读选项：etype（null = 全部）、limit（&lt; 0 = 不限）、consistent（分块顶点也给原子快照）。 */
    public record AdjQuery(Long etype, long limit, boolean consistent) {
        public static final AdjQuery ALL = new AdjQuery(null, -1, false);

        public static AdjQuery etype(long etype) {
            return new AdjQuery(etype, -1, false);
        }
    }

    private <T> T readConsistent(long vid, Supplier<T> read) {
        for (int n = CONSISTENT_TRIES; ; n--) {
            if (n == 0) throw BitcaskException.of("bitcask/graph-inconsistent-read", GraphModel.vidText(vid));
            if (n < CONSISTENT_TRIES) GraphModel.sleepBackoff(Math.min(CONSISTENT_TRIES - n, 5));
            long c1 = Adjacency.headOrd(db, vid);
            T r = read.get();
            if (c1 == Adjacency.headOrd(db, vid)) return r;
        }
    }

    private List<Edge> adjEdges(long vid, int dir, AdjQuery q) {
        AdjQuery o = q == null ? AdjQuery.ALL : q;
        Long et = Adjacency.u32(o.etype);
        Adjacency.RowMaker<Edge> mk = dir == 0
                ? (t, x, r, p) -> new Edge(vid, t, x, r, resolveProps(vid, t, x, r, p))
                : (t, x, r, p) -> new Edge(x, t, vid, r, EMPTY);
        Supplier<List<Edge>> read = () -> Adjacency.groupRows(db, vid, Adjacency.headGroups(db, vid, Adjacency.rawFn(dir, et)), dir, et, o.limit, mk);
        return o.consistent ? readConsistent(vid, read) : read.get();
    }

    /** 出边（序 = etype、dst、rank 升序）。 */
    public List<Edge> outEdges(long vid) {
        return adjEdges(vid, 0, null);
    }

    public List<Edge> outEdges(long vid, AdjQuery q) {
        return adjEdges(vid, 0, q);
    }

    /** 入边（序 = etype、src、rank 升序）；属性恒空。 */
    public List<Edge> inEdges(long vid) {
        return adjEdges(vid, 1, null);
    }

    public List<Edge> inEdges(long vid, AdjQuery q) {
        return adjEdges(vid, 1, q);
    }

    /** 扫整个 a 家族，按 (src, etype, dst, rank) 序出边（etype 给定只出该类型）。O(V + E)。 */
    private List<Edge> scanOut(Long etype, long limit, boolean resolve) {
        List<Edge> acc = new ArrayList<>();
        if (limit == 0) return acc;
        Long et = Adjacency.u32(etype);
        Adjacency.WantFn want = Adjacency.wantFn(0, et);
        byte[] lo = GraphKeys.familyBytes("a");
        db.forEachRange(RangeOptions.between(lo, GraphKeys.successor(lo)).batch(128), e -> {
            Long src = GraphKeys.parseHead(e.key());
            if (src == null) return true;
            List<Group> gs = Adjacency.decodeHead(e.value(), want);
            if (gs == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", GraphModel.vidText(src));
            List<Group> outs = gs.stream().filter(g -> g.dir() == 0 && (et == null || et == g.etype()) && !g.skipped()).toList();
            boolean chunked = outs.stream().anyMatch(g -> g.chunks() != null || g.xdir() != null);
            if (chunked) {
                long left = limit >= 0 ? limit - acc.size() : -1;
                acc.addAll(Adjacency.groupRows(db, src, outs, 0, et, left,
                        (t, o, r, p) -> new Edge(src, t, o, r, resolve ? resolveProps(src, t, o, r, p) : EMPTY)));
            } else {
                outer:
                for (Group g : outs) {
                    for (AdjEntry x : g.entries()) {
                        if (limit >= 0 && acc.size() >= limit) break outer;
                        acc.add(new Edge(src, g.etype(), x.other(), x.rank(),
                                resolve ? resolveProps(src, g.etype(), x.other(), x.rank(), x.props()) : EMPTY));
                    }
                }
            }
            return limit < 0 || acc.size() < limit;
        });
        return acc;
    }

    /** 全部边（序 = src、etype、dst、rank；属性原样）。 */
    public List<Edge> edges() {
        return scanOut(null, -1, true);
    }

    public List<Edge> edges(long limit) {
        if (limit < 0) throw new IllegalArgumentException("limit");
        return scanOut(null, limit, true);
    }

    /** 按类型全局列边（扫 a 家族 O(V + E)）。 */
    public List<Edge> edgesByType(long etype) {
        return scanOut(etype, -1, true);
    }

    public List<Edge> edgesByType(long etype, long limit) {
        if (limit < 0) throw new IllegalArgumentException("limit");
        return scanOut(etype, limit, true);
    }

    /** 邻居 vid（out ++ in，不全局去重；组内同一对端多个 rank 只出一次）+ 来自几个组。 */
    private long[] neighborIds(long v, Direction dir, Long etype, int[] ngroups) {
        Long et = Adjacency.u32(etype);
        List<Group> gs = Adjacency.headGroups(db, v, Adjacency.rawFn(dir.code(), et));
        LongStream.Builder b = LongStream.builder();
        if (dir != Direction.IN) Adjacency.addIds(db, v, gs, 0, et, true, b);
        if (dir != Direction.OUT) Adjacency.addIds(db, v, gs, 1, et, true, b);
        if (ngroups != null) {
            ngroups[0] = (int) gs.stream().filter(g -> (dir == Direction.OUT ? g.dir() == 0 : dir == Direction.IN ? g.dir() == 1 : true)
                    && (et == null || et == g.etype())).count();
        }
        return b.build().toArray();
    }

    long[] neighborList(long v, Direction dir, Long etype) {
        return neighborIds(v, dir, etype, null);
    }

    public List<Long> neighbors(long vid) {
        return neighbors(vid, Direction.OUT);
    }

    /** 邻居（out ++ in，去重保序）。 */
    public List<Long> neighbors(long vid, Direction dir) {
        int[] ng = new int[1];
        long[] ids = neighborIds(vid, dir, null, ng);
        List<Long> out = new ArrayList<>(ids.length);
        if (ng[0] <= 1) {
            for (long x : ids) out.add(x);
            return out;
        }
        Set<Long> seen = new HashSet<>();
        for (long x : ids) if (seen.add(x)) out.add(x);
        return out;
    }

    /** 属性过滤邻居：pred(邻居 vid, 顶点正文)（悬挂边端点缺失时正文为 null）。 */
    public List<Long> neighborsWhere(long vid, Direction dir, BiPredicate<Long, String> pred) {
        return neighbors(vid, dir).stream().filter(n -> pred.test(n, vertex(n))).toList();
    }

    public long degree(long vid) {
        return Adjacency.dirCounts(db, vid, null)[0];
    }

    /** 出度（读头即得）。只数该 etype。 */
    public long degree(long vid, long etype) {
        return Adjacency.dirCounts(db, vid, Adjacency.u32(etype))[0];
    }

    public long inDegree(long vid) {
        return Adjacency.dirCounts(db, vid, null)[1];
    }

    public long inDegree(long vid, long etype) {
        return Adjacency.dirCounts(db, vid, Adjacency.u32(etype))[1];
    }

    // ═══ 检视（查看工具用的稳定接口）═══

    /** 邻接表组摘要：{@code dirLevels} 只在块目录外置（布局 2）时有，否则 null。 */
    public record GroupSummary(Direction dir, long etype, long count, boolean chunked, long chunks, Integer dirLevels) {
    }

    /** 顶点的邻接表摘要。 */
    public record VertexSummary(long vid, List<GroupSummary> groups) {
    }

    private static final Pattern HEX4 = Pattern.compile("[0-9a-f]{4}");

    /**
     * 识别原始图键（只看键布局，不读库）：不是图键 ⇒ null。返回 map：{@code family}（node / head / chunk / props / dir-node / meta / etype-name /
     * etype-map / label / vid-counter / rank-floor / merge-guard）+ 该家族的字段（vid、dir、etype、lb、level、src、dst、rank、name、label）。
     */
    public static Map<String, Object> describeKey(byte[] kb) {
        if (kb == null) return null;
        int pl = GraphKeys.NS.length();
        Map<String, Object> m = new LinkedHashMap<>();
        Function<int[], String> raw = a -> new String(kb, a[0], a[1], StandardCharsets.UTF_8);
        GraphKeys.ChunkKey chunk = GraphKeys.parseChunk(kb);
        long[] props = GraphKeys.parsePropsKey(kb);
        GraphKeys.LabelKey lk;
        GraphKeys.DirNodeKey dn;
        Long v;
        if ((v = GraphKeys.parseNode(kb)) != null) {
            m.put("family", "node");
            m.put("vid", v);
        } else if ((v = GraphKeys.parseHead(kb)) != null) {
            m.put("family", "head");
            m.put("vid", v);
        } else if (chunk != null) {
            m.put("family", "chunk");
            m.put("vid", chunk.vid());
            m.put("dir", chunk.dir() == 0 ? Direction.OUT : Direction.IN);
            m.put("etype", chunk.etype());
            m.put("lb", List.of(chunk.other(), chunk.rank()));
        } else if (props != null) {
            m.put("family", "props");
            m.put("src", props[0]);
            m.put("etype", props[1]);
            m.put("dst", props[2]);
            m.put("rank", props[3]);
        } else if ((dn = GraphKeys.parseDirNode(kb)) != null) {
            m.put("family", "dir-node");
            m.put("vid", dn.vid());
            m.put("dir", dn.dir() == 0 ? Direction.OUT : Direction.IN);
            m.put("etype", dn.etype());
            m.put("level", dn.level());
            m.put("lb", List.of(dn.other(), dn.rank()));
        } else if (kb.length == pl + 1 && kb[pl] == 'g') {
            m.put("family", "meta");
        } else if ((v = GraphKeys.parseEtypeKey(kb)) != null) {
            m.put("family", "etype-name");
            m.put("etype", v);
        } else if (GraphKeys.isEtypeNameKey(kb)) {
            int n = Integer.parseInt(raw.apply(new int[]{pl + 2, 4}), 16);
            m.put("family", "etype-map");
            m.put("name", raw.apply(new int[]{pl + 6, n}));
        } else if ((lk = GraphKeys.parseLabelKey(kb)) != null) {
            m.put("family", "label");
            m.put("label", lk.label());
            m.put("vid", lk.vid());
        } else if (kb.length == pl + 1 && kb[pl] == 'c') {
            m.put("family", "vid-counter");
        } else if (kb.length == pl + 41 && kb[pl] == 'r') {
            m.put("family", "rank-floor");
            m.put("src", Long.parseUnsignedLong(raw.apply(new int[]{pl + 1, 16}), 16));
            m.put("etype", Long.parseLong(raw.apply(new int[]{pl + 17, 8}), 16));
            m.put("dst", Long.parseUnsignedLong(raw.apply(new int[]{pl + 25, 16}), 16));
        } else if (kb.length >= pl + 5 && kb[pl] == 'm' && HEX4.matcher(raw.apply(new int[]{pl + 1, 4})).matches()
                && kb.length == pl + 5 + Integer.parseInt(raw.apply(new int[]{pl + 1, 4}), 16)) {
            int n = kb.length - pl - 5;
            m.put("family", "merge-guard");
            m.put("label", n > 0 ? raw.apply(new int[]{pl + 5, n}) : null);
        } else return null;
        return m;
    }

    public static Map<String, Object> describeKey(String k) {
        return k == null ? null : describeKey(k.getBytes(StandardCharsets.UTF_8));
    }

    private static List<GroupSummary> summaries(List<Group> gs) {
        return gs.stream().map(g -> new GroupSummary(g.dir() == 0 ? Direction.OUT : Direction.IN, g.etype(), g.count(),
                g.chunks() != null || g.xdir() != null,
                g.xdir() != null ? g.xdir().n() : g.chunks() != null ? g.chunks().size() : 0,
                g.xdir() != null ? g.xdir().levels() : null)).toList();
    }

    /**
     * 解码原始图键的值（只读、稳定）：值或布局畸形 ⇒ {@code {"malformed": 家族}}，不抛。头 ⇒ {@code groups}；块 ⇒ {@code count}；
     * 目录节点 ⇒ {@code level count}；g ⇒ 图参数（带 layout）；t / tn ⇒ etype 注册表；l ⇒ {@code empty}；c ⇒ {@code next-vid} / {@code exhausted}；
     * r ⇒ {@code rank-floor}；m ⇒ {@code version}；n / p ⇒ {@code value} 原样。不是图键 ⇒ null。
     */
    public static Map<String, Object> describeValue(byte[] key, byte[] v) {
        Map<String, Object> k = describeKey(key);
        if (k == null) return null;
        String family = (String) k.get("family");
        Map<String, Object> out = new LinkedHashMap<>();
        Runnable bad = () -> {
            out.clear();
            out.put("malformed", family);
        };
        if (v == null) {
            bad.run();
            return out;
        }
        switch (family) {
            case "node", "props" -> out.put("value", v);
            case "head" -> {
                List<Group> gs = Adjacency.decodeHead(v);
                if (gs == null) bad.run();
                else out.put("groups", summaries(gs));
            }
            case "chunk" -> {
                List<AdjEntry> es = Adjacency.decodeChunk(v);
                if (es == null) bad.run();
                else out.put("count", es.size());
            }
            case "meta" -> {
                GraphConfig c = GraphConfig.decode(v);
                if (c == null) bad.run();
                else {
                    out.put("layout", c.layout());
                    out.put("inline-max", c.inlineMax());
                    out.put("chunk-max", c.chunkMax());
                    out.put("props-max", c.propsMax());
                    if (c.layout2()) {
                        out.put("chunk-entries", c.chunkEntries());
                        out.put("chunk-count-max", c.chunkCountMax());
                        out.put("dir-inline-max", c.dirInlineMax());
                        out.put("dir-node-max", c.dirNodeMax());
                    }
                }
            }
            case "dir-node" -> {
                Adjacency.Node nd = Adjacency.decodeNode(v);
                if (nd == null) bad.run();
                else {
                    out.put("level", nd.level());
                    out.put("count", nd.lbs().size());
                }
            }
            case "label" -> {
                if (v.length == 0) out.put("empty", true);
                else bad.run();
            }
            case "vid-counter" -> {
                GraphModel.Watermark w;
                try {
                    w = GraphModel.decodeCounter(v);
                } catch (RuntimeException ex) {
                    w = null;
                }
                if (w == null) bad.run();
                else if (w.exhausted()) out.put("exhausted", true);
                else out.put("next-vid", w.next());
            }
            case "rank-floor" -> {
                if (v.length == 8) out.put("rank-floor", GraphKeys.bytesToU64(v));
                else bad.run();
            }
            case "merge-guard" -> {
                if (v.length == 8) out.put("version", GraphKeys.bytesToU64(v));
                else bad.run();
            }
            case "etype-name" -> {
                if (v.length > 0) out.put("name", new String(v, StandardCharsets.UTF_8));
                else bad.run();
            }
            case "etype-map" -> {
                if (v.length == 4) out.put("etype", GraphKeys.bytesToU32(v));
                else bad.run();
            }
            default -> bad.run();
        }
        return out;
    }

    /** 邻接表头摘要（头部畸形照常抛 {@code bitcask/graph-adjacency-malformed}）。 */
    public VertexSummary vertexSummary(long vid) {
        return new VertexSummary(vid, summaries(Adjacency.loadVertex(db, vid).groups));
    }

    // ═══ 删顶点（级联）═══

    public void removeVertex(long vid) {
        removeVertex(vid, DELETE_NEIGHBORS);
    }

    /**
     * 级联删顶点：按邻居分批（每批 ≤ batchNeighbors 个；0 = 一批原子删完），每批一条带条件的原子批——同时改邻居的头与自己的头，
     * 提交后图仍对称，中途失败重跑即收敛。最后一批删自己的头、残余块、相关 p 键、顶点键与它的标签索引项。顶点不存在也不报错。
     */
    public void removeVertex(long vid, int batchNeighbors) {
        if (batchNeighbors < 0) throw BitcaskException.of("bitcask/graph-config-invalid", "batch-neighbors", String.valueOf(batchNeighbors));
        boolean all = batchNeighbors == 0;
        boolean[] more = new boolean[1];
        do {
            withSession(s -> {
                boolean[] done = new boolean[1];
                List<FullEntry> es = s.firstEntries(vid, all ? -1 : batchNeighbors, done);
                TreeSet<Long> others = new TreeSet<>(Long::compareUnsigned);
                for (FullEntry e : es) if (e.other() != vid) others.add(e.other());
                List<Long> sortedOthers = new ArrayList<>(others);
                List<Long> batch = all ? sortedOthers : sortedOthers.subList(0, Math.min(batchNeighbors, sortedOthers.size()));
                boolean last = done[0] && batch.size() == sortedOthers.size();
                Set<Long> sel = new HashSet<>(batch);
                if (last) sel.add(vid);
                for (FullEntry e : es) {
                    if (!sel.contains(e.other())) continue;
                    if (e.dir() == 0) s.delEdge(vid, e.etype(), e.other(), e.rank());
                    else s.delEdge(e.other(), e.etype(), vid, e.rank());
                }
                more[0] = !last;
                List<BatchOp> extra = new ArrayList<>();
                if (last) {
                    if (es.isEmpty()) extra.add(BatchOp.check(GraphKeys.ascii(GraphKeys.head(vid)), Condition.absent()));
                    extra.addAll(GraphModel.removalOps(db, vid));
                }
                return extra;
            });
        } while (more[0]);
    }

    // ═══ 组合事务 ═══

    /** 组合事务里的一项。 */
    public sealed interface GraphOp permits VertexOp, EdgeOp, RemoveEdgeOp {
    }

    /** 写顶点（spec 是 String 或 {@link VertexSpec}）。 */
    public record VertexOp(long vid, Object spec) implements GraphOp {
    }

    public record EdgeOp(long src, long etype, long dst, long rank, Object props) implements GraphOp {
    }

    public record RemoveEdgeOp(long src, long etype, long dst, long rank) implements GraphOp {
    }

    /**
     * 组合事务：顶点 / 边 / 删边一个带条件的原子批落盘（冲突重试同 putEdge）。空批、同批重复 (src etype dst rank) / 重复顶点 ⇒
     * 当场抛，零副作用；只删不存在的边、批里什么都不剩 ⇒ {@code bitcask/graph-batch-empty}。不做删顶点的级联。
     */
    public void putBatch(List<GraphOp> ops) {
        if (ops.isEmpty()) throw BitcaskException.of("bitcask/graph-batch-empty");
        Set<Long> seenV = new HashSet<>();
        Set<List<Long>> seenE = new HashSet<>();
        List<Object[]> specs = new ArrayList<>();
        List<GraphOp> edgeOps = new ArrayList<>();
        long mx = 0;
        boolean anyVid = false;
        for (GraphOp op : ops) {
            switch (op) {
                case null -> throw BitcaskException.of("bitcask/graph-batch-op-invalid", "null");
                case VertexOp v -> {
                    if (!seenV.add(v.vid)) throw BitcaskException.of("bitcask/graph-batch-duplicate-vertex", GraphModel.vidText(v.vid));
                    Objects.requireNonNull(v.spec, "spec");
                }
                case EdgeOp e -> edgeOps.add(e);
                case RemoveEdgeOp e -> edgeOps.add(e);
            }
        }
        for (GraphOp op : edgeOps) {
            long src, et, dst, rank;
            if (op instanceof EdgeOp e) {
                src = e.src;
                et = Adjacency.u32(e.etype);
                dst = e.dst;
                rank = e.rank;
            } else {
                RemoveEdgeOp e = (RemoveEdgeOp) op;
                src = e.src;
                et = Adjacency.u32(e.etype);
                dst = e.dst;
                rank = e.rank;
            }
            if (!seenE.add(List.of(src, et, dst, rank)))
                throw BitcaskException.of("bitcask/graph-batch-duplicate-edge", GraphModel.vidText(src), Long.toUnsignedString(et),
                        GraphModel.vidText(dst), GraphModel.vidText(rank));
        }
        for (GraphOp op : ops) {
            if (op instanceof VertexOp v) {
                specs.add(new Object[]{v.vid, GraphModel.specToValue(v.spec)});
                mx = GraphModel.umax(mx, v.vid);
                anyVid = true;
            } else if (op instanceof EdgeOp e) {
                mx = GraphModel.umax(mx, GraphModel.umax(e.src, e.dst));
                anyVid = true;
            }
        }
        List<Object[]> edges = new ArrayList<>();
        for (GraphOp op : edgeOps) {
            if (op instanceof EdgeOp e) edges.add(new Object[]{true, e.src, Adjacency.u32(e.etype), e.dst, e.rank, GraphModel.encodeEdgeProps(e.props)});
            else {
                RemoveEdgeOp e = (RemoveEdgeOp) op;
                edges.add(new Object[]{false, e.src, Adjacency.u32(e.etype), e.dst, e.rank, null});
            }
        }
        if (anyVid) GraphModel.bumpWatermark(db, mx);
        Adjacency.Commit r = withSession(s -> {
            for (Object[] e : edges) {
                if ((Boolean) e[0]) s.putEdge((Long) e[1], (Long) e[2], (Long) e[3], (Long) e[4], (byte[]) e[5]);
                else s.delEdge((Long) e[1], (Long) e[2], (Long) e[3], (Long) e[4]);
            }
            List<BatchOp> extra = new ArrayList<>();
            for (Object[] sp : specs) {
                long vid = (Long) sp[0];
                GraphModel.VertexValue v = (GraphModel.VertexValue) sp[1];
                GraphModel.OldVertex o = GraphModel.oldVertex(db, vid);
                extra.add(GraphModel.valueOp(GraphKeys.nodeBytes(vid), v, o.cond()));
                extra.addAll(GraphModel.labelOps(vid, o.labels(), v.labels()));
            }
            return extra;
        });
        if (r == Adjacency.Commit.NOOP) throw BitcaskException.of("bitcask/graph-batch-empty");
    }

    // ═══ 遍历（输出序 = BFS 发现序）═══

    /** BFS 命中：顶点与深度。 */
    public record BfsHit(long vid, int depth) {
    }

    /** BFS 选项：maxDepth（null = 不限）、direction、etype（null = 全部）、visit（伴随发现序逐点回调，可 null）。 */
    public record BfsOptions(Integer maxDepth, Direction direction, Long etype, BiConsumer<Long, Integer> visit) {
        public static final BfsOptions DEFAULT = new BfsOptions(null, Direction.OUT, null, null);
    }

    public List<BfsHit> bfs(long start) {
        return bfs(start, BfsOptions.DEFAULT);
    }

    /** 广度优先，含起点（深度 0），按发现序。 */
    public List<BfsHit> bfs(long start, BfsOptions opts) {
        BfsOptions o = opts == null ? BfsOptions.DEFAULT : opts;
        Direction dir = o.direction == null ? Direction.OUT : o.direction;
        if (o.maxDepth != null && o.maxDepth < 0) throw BitcaskException.of("bitcask/graph-negative", "maxDepth");
        if (o.visit != null) o.visit.accept(start, 0);
        Set<Long> visited = new HashSet<>();
        visited.add(start);
        List<BfsHit> acc = new ArrayList<>();
        acc.add(new BfsHit(start, 0));
        List<Long> frontier = List.of(start);
        int depth = 0;
        while (o.maxDepth == null || depth < o.maxDepth) {
            List<Long> fresh = new ArrayList<>();
            for (long v : frontier) for (long n : neighborList(v, dir, o.etype)) if (visited.add(n)) fresh.add(n);
            if (fresh.isEmpty()) break;
            depth++;
            for (long v : fresh) {
                if (o.visit != null) o.visit.accept(v, depth);
                acc.add(new BfsHit(v, depth));
            }
            frontier = fresh;
        }
        return acc;
    }

    public List<Long> kHop(long start, int k) {
        return kHop(start, k, Direction.OUT, null);
    }

    /** 距起点 1..k 跳的顶点（不含起点），发现序。 */
    public List<Long> kHop(long start, int k, Direction dir, Long etype) {
        if (k < 0) throw BitcaskException.of("bitcask/graph-negative", "k");
        return bfs(start, new BfsOptions(k, dir, etype, null)).stream().filter(h -> h.depth > 0).map(BfsHit::vid).toList();
    }

    /** 子图里的顶点：深度与出 / 入度。 */
    public record SubgraphVertex(long vid, int depth, long outDegree, long inDegree, long degree) {
    }

    /** 局部子图；truncated = 因顶点或每顶点边数上限没取全。边不带属性。 */
    public record Subgraph(List<SubgraphVertex> vertices, List<Edge> edges, boolean truncated) {
    }

    /** 子图选项：hops（≥ 1）、direction、etype、perVertex（每个顶点每轮最多取多少条边）、maxVertices（含起点）。 */
    public record SubgraphOptions(int hops, Direction direction, Long etype, int perVertex, int maxVertices) {
        public static final SubgraphOptions DEFAULT = new SubgraphOptions(1, Direction.OUT, null, 50, 200);
    }

    private SubgraphVertex vertexRow(long vid, int depth, Long etype) {
        long[] cs = Adjacency.dirCounts(db, vid, Adjacency.u32(etype));
        return new SubgraphVertex(vid, depth, cs[0], cs[1], cs[0] + cs[1]);
    }

    private record FromEdge(Edge e, long from) {
    }

    private static List<Edge> distinctEdges(List<Edge> es) {
        Set<List<Long>> seen = new HashSet<>();
        List<Edge> out = new ArrayList<>();
        for (Edge e : es) if (seen.add(List.of(e.src(), e.etype(), e.dst(), e.rank()))) out.add(e);
        return out;
    }

    private List<FromEdge> frontierEdges(List<Long> frontier, Direction dir, Long etype, int perVertex, boolean[] truncated) {
        boolean out = dir != Direction.IN, in = dir != Direction.OUT;
        Long et = Adjacency.u32(etype);
        Adjacency.WantFn want = Adjacency.rawFn(dir.code(), et);
        List<FromEdge> all = new ArrayList<>();
        Set<List<Long>> seen = new HashSet<>();
        for (long v : frontier) {
            List<Group> gs = Adjacency.headGroups(db, v, want);
            List<Edge> mine = new ArrayList<>();
            if (out) mine.addAll(Adjacency.groupRows(db, v, gs, 0, et, perVertex, (t, o, r, p) -> new Edge(v, t, o, r, EMPTY)));
            if (in) mine.addAll(Adjacency.groupRows(db, v, gs, 1, et, perVertex, (t, o, r, p) -> new Edge(o, t, v, r, EMPTY)));
            mine = distinctEdges(mine);
            List<Edge> kept = mine.subList(0, Math.min(perVertex, mine.size()));
            long total = (out ? Adjacency.countGroups(gs, 0, et) : 0) + (in ? Adjacency.countGroups(gs, 1, et) : 0);
            if (kept.size() < mine.size() || (kept.size() == perVertex && total > kept.size())) truncated[0] = true;
            for (Edge e : kept) if (seen.add(List.of(e.src(), e.etype(), e.dst(), e.rank()))) all.add(new FromEdge(e, v));
        }
        return all;
    }

    public Subgraph subgraph(long vid) {
        return subgraph(vid, SubgraphOptions.DEFAULT);
    }

    /** 有上限的局部子图（BFS 发现序；跨跳弱一致）。悬挂边端点也会作为顶点返回。 */
    public Subgraph subgraph(long vid, SubgraphOptions opts) {
        SubgraphOptions o = opts == null ? SubgraphOptions.DEFAULT : opts;
        if (o.hops <= 0) throw BitcaskException.of("bitcask/graph-config-invalid", "hops", String.valueOf(o.hops));
        if (o.perVertex <= 0) throw BitcaskException.of("bitcask/graph-config-invalid", "per-vertex", String.valueOf(o.perVertex));
        if (o.maxVertices <= 0) throw BitcaskException.of("bitcask/graph-config-invalid", "max-vertices", String.valueOf(o.maxVertices));
        Direction dir = o.direction == null ? Direction.OUT : o.direction;
        boolean[] truncated = new boolean[1];
        List<Long> frontier = List.of(vid);
        Set<Long> visited = new HashSet<>(Set.of(vid));
        List<SubgraphVertex> vertices = new ArrayList<>(List.of(vertexRow(vid, 0, o.etype)));
        List<Edge> acc = new ArrayList<>();
        for (int depth = 0; ; depth++) {
            List<FromEdge> edges = frontierEdges(frontier, dir, o.etype, o.perVertex, truncated);
            List<Long> next = new ArrayList<>();
            for (FromEdge fe : edges) {
                Edge e = fe.e;
                long other = switch (dir) {
                    case OUT -> e.dst();
                    case IN -> e.src();
                    case BOTH -> e.src() == fe.from ? e.dst() : e.src();
                };
                if (visited.contains(other)) continue;
                if (vertices.size() < o.maxVertices) {
                    visited.add(other);
                    vertices.add(vertexRow(other, depth + 1, o.etype));
                    next.add(other);
                } else truncated[0] = true;
            }
            for (FromEdge fe : edges) if (visited.contains(fe.e.src()) && visited.contains(fe.e.dst())) acc.add(fe.e);
            if (depth + 1 >= o.hops || next.isEmpty()) return new Subgraph(vertices, distinctEdges(acc), truncated[0]);
            frontier = next;
        }
    }

    private static List<Long> walkUp(long v, Map<Long, Long> parents) {
        List<Long> path = new ArrayList<>();
        Long cur = v;
        while (cur != null) {
            if (!parents.containsKey(cur)) throw BitcaskException.of("bitcask/graph-path-broken", GraphModel.vidText(cur));
            path.addFirst(cur);
            cur = parents.get(cur);
        }
        return path;
    }

    public List<Long> shortestPath(long src, long dst) {
        return shortestPath(src, dst, null);
    }

    /** 双向最短路（有向：前进沿出边、回溯沿入边，每轮展开较小的一侧）⇒ [src … dst]；不通 ⇒ null；src = dst ⇒ [src]。 */
    public List<Long> shortestPath(long src, long dst, Long etype) {
        if (src == dst) return List.of(src);
        Map<Long, Long> fwd = new HashMap<>(), bwd = new HashMap<>();
        fwd.put(src, null);
        bwd.put(dst, null);
        List<Long> fwdF = List.of(src), bwdF = List.of(dst);
        while (true) {
            boolean forward = fwdF.size() <= bwdF.size();
            Map<Long, Long> parents = forward ? fwd : bwd;
            Map<Long, Long> other = forward ? bwd : fwd;
            List<Long> newF = new ArrayList<>();
            for (long v : forward ? fwdF : bwdF) {
                for (long n : neighborList(v, forward ? Direction.OUT : Direction.IN, etype)) {
                    if (!parents.containsKey(n)) {
                        parents.put(n, v);
                        newF.add(n);
                    }
                }
            }
            if (newF.isEmpty()) return null;
            for (long meet : newF) {
                if (other.containsKey(meet)) {
                    List<Long> head = walkUp(meet, fwd);
                    List<Long> tail = walkUp(meet, bwd).reversed();
                    List<Long> path = new ArrayList<>(head);
                    path.addAll(tail.subList(1, tail.size()));
                    return path;
                }
            }
            if (forward) fwdF = newF;
            else bwdF = newF;
        }
    }

    // ═══ etype 注册（t / tn 家族）═══

    private static void checkEtypeName(String nm) {
        if (nm == null || nm.isEmpty()) throw BitcaskException.of("bitcask/graph-etype-name-empty");
    }

    public static Long etypeId(Bitcask db, String nm) {
        checkEtypeName(nm);
        byte[] bv = db.get(GraphKeys.etypeByName(nm));
        if (bv == null) return null;
        if (bv.length != 4) throw BitcaskException.of("bitcask/graph-etype-record-malformed", nm);
        return GraphKeys.bytesToU32(bv);
    }

    /** 名字 ⇒ id；没注册 ⇒ null。 */
    public Long etypeId(String nm) {
        return etypeId(db, nm);
    }

    public static String etypeName(Bitcask db, long id) {
        byte[] bv = db.get(GraphKeys.etypeName(id));
        if (bv == null) return null;
        if (bv.length == 0) throw BitcaskException.of("bitcask/graph-etype-name-blank", Long.toUnsignedString(id));
        return new String(bv, StandardCharsets.UTF_8);
    }

    /** id ⇒ 名字；没注册 ⇒ null。 */
    public String etypeName(long id) {
        return etypeName(db, id);
    }

    public static long registerEtype(Bitcask db, String nm) {
        checkEtypeName(nm);
        while (true) {
            Long id = etypeId(db, nm);
            if (id != null) return id;
            byte[] lo = GraphKeys.familyBytes("t");
            long mx = 0;
            for (Entry e : db.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo)))) {
                Long x = GraphKeys.parseEtypeKey(e.key());
                if (x != null && x > mx) mx = x;
            }
            long nid = mx + 1;
            if (db.tryWriteAtomic(List.of(BatchOp.put(GraphKeys.ascii(GraphKeys.etypeName(nid)), nm.getBytes(StandardCharsets.UTF_8), Condition.absent()),
                    BatchOp.put(GraphKeys.etypeByName(nm), GraphKeys.u32Bytes(nid), Condition.absent()))).isSuccess()) return nid;
        }
    }

    /** 注册边类型名 ⇒ id（从 1 起；同名再注册幂等回原 id）。两个键都带 absent 条件写：并发注册不会拿到同一个 id。 */
    public long registerEtype(String nm) {
        return registerEtype(db, nm);
    }

    // ═══ OLAP ═══

    public Csr materialize() {
        return materialize(null);
    }

    /** 把 a 家族（给定 etype 只取该类型）流式物化为进程内 CSR。可再生缓存：图变了丢掉重建。 */
    public Csr materialize(Long etype) {
        Long et = Adjacency.u32(etype);
        Adjacency.WantFn want = Adjacency.rawFn(0, et);
        LongStream.Builder srcs = LongStream.builder(), dsts = LongStream.builder();
        byte[] lo = GraphKeys.familyBytes("a");
        db.forEachRange(RangeOptions.between(lo, GraphKeys.successor(lo)).batch(128), e -> {
            Long src = GraphKeys.parseHead(e.key());
            if (src == null) return true;
            List<Group> gs = Adjacency.decodeHead(e.value(), want);
            if (gs == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", GraphModel.vidText(src));
            long s = src;
            Adjacency.addIds(db, s, gs, 0, et, false, x -> {
                srcs.add(s);
                dsts.add(x);
            });
            return true;
        });
        return Csr.build(srcs.build().toArray(), dsts.build().toArray());
    }

    // ═══ 全图不变式校验 ═══

    /** 校验结果：ok 时带顶点 / 边 / 块数；否则 problems（最多 20 条，每条 [类别, 细节…]）。 */
    public record InvariantReport(boolean ok, long vertices, long edges, long chunks, List<List<Object>> problems) {
    }

    private static final int CAP = 20;

    private static void addProblem(List<List<Object>> ps, List<Object> p) {
        if (ps.size() < CAP) ps.add(p);
    }

    private long checkVertex(long vid, List<Group> gs, Map<List<Object>, List<AdjEntry>> seenChunks, List<List<Object>> problems,
                             Set<String> dirNodes) {
        long edges = 0;
        Set<List<Object>> used = new HashSet<>();
        for (Group g : gs) {
            List<AdjEntry> es;
            if (g.entries() != null) es = g.entries();
            else {
                es = new ArrayList<>();
                List<LB> lbs = g.chunks();
                if (g.xdir() != null) {
                    // 布局 2：目录树逐个节点核对，走到的节点记下来（最后找 d 家族里的孤儿）
                    Adjacency.XdirCheck xc = Adjacency.xdirCheck(db, vid, g.dir(), g.etype(), g.xdir().levels(), g.xdir().n());
                    dirNodes.addAll(xc.nodes());
                    for (List<Object> p : xc.problems()) addProblem(problems, p);
                    lbs = xc.lbs();
                }
                for (int i = 0; i < lbs.size(); i++) {
                    LB lb = lbs.get(i), nxt = i + 1 < lbs.size() ? lbs.get(i + 1) : null;
                    List<Object> key = List.of(g.dir(), g.etype(), lb);
                    used.add(key);
                    List<AdjEntry> ces = seenChunks.get(key);
                    if (ces == null) {
                        addProblem(problems, List.of("missing-chunk", vid, g.dir(), g.etype(), List.of(lb.o(), lb.r())));
                        continue;
                    }
                    for (AdjEntry e : ces) {
                        if (!(Adjacency.cmp(lb.o(), lb.r(), e.other(), e.rank()) <= 0
                                && (nxt == null || Adjacency.cmp(e.other(), e.rank(), nxt.o(), nxt.r()) < 0))) {
                            addProblem(problems, List.of("chunk-range", vid, g.dir(), g.etype(), List.of(lb.o(), lb.r())));
                            break;
                        }
                    }
                    es.addAll(ces);
                }
            }
            if (g.count() != es.size()) addProblem(problems, List.of("count-mismatch", vid, g.dir(), g.etype(), g.count(), es.size()));
            for (AdjEntry e : es) {
                if (g.dir() == 0) {
                    edges++;
                    if (Adjacency.lookup(db, e.other(), 1, g.etype(), vid, e.rank()) == null)
                        addProblem(problems, List.of("asymmetric", List.of(vid, g.etype(), e.other(), e.rank()), "missing-in"));
                    if (Adjacency.isExt(e.props()) && db.get(GraphKeys.propsKey(vid, g.etype(), e.other(), e.rank())) == null)
                        addProblem(problems, List.of("props-missing", List.of(vid, g.etype(), e.other(), e.rank())));
                } else if (Adjacency.lookup(db, e.other(), 0, g.etype(), vid, e.rank()) == null)
                    addProblem(problems, List.of("asymmetric", List.of(e.other(), g.etype(), vid, e.rank()), "missing-out"));
            }
        }
        for (List<Object> k : seenChunks.keySet()) if (!used.contains(k)) addProblem(problems, List.of("orphan-chunk", vid, k));
        return edges;
    }

    private void modelProblems(Long maxHead, List<List<Object>> problems) {
        Set<List<Object>> expected = new HashSet<>();
        Long maxN = null;
        for (Entry e : GraphModel.rangeAll(db, GraphKeys.familyBytes("n"), true, 256)) {
            Long vid = GraphKeys.parseNode(e.key());
            if (vid == null) continue;
            for (String l : GraphModel.labelsFromMeta(e.meta())) expected.add(List.of(l, vid));
            if (maxN == null || GraphModel.ucmp(vid, maxN) > 0) maxN = vid;
        }
        Set<List<Object>> indexed = new HashSet<>();
        for (Entry e : GraphModel.rangeAll(db, GraphKeys.familyBytes("l"), false, 256)) {
            GraphKeys.LabelKey lk = GraphKeys.parseLabelKey(e.key());
            indexed.add(lk != null ? List.of(lk.label(), lk.vid()) : List.of("bad-label-key", new String(e.key(), StandardCharsets.ISO_8859_1)));
        }
        Long mx = maxN == null ? maxHead : maxHead == null ? maxN : (Long) GraphModel.umax(maxN, maxHead);
        if (!expected.equals(indexed)) {
            addProblem(problems, List.of("label-index-mismatch", expected.stream().filter(x -> !indexed.contains(x)).limit(5).toList(),
                    indexed.stream().filter(x -> !expected.contains(x)).limit(5).toList()));
        }
        GraphModel.Counter c;
        try {
            c = GraphModel.readCounter(db);
        } catch (RuntimeException ex) {
            addProblem(problems, List.of("bad-vid-counter"));
            return;
        }
        if (c != null && mx != null && !GraphModel.belowWatermark(mx, c.next()))
            addProblem(problems, List.of("vid-counter-low", c.next().exhausted() ? "exhausted" : c.next().next(), mx));
    }

    /**
     * 流式扫 a / d / p 家族逐条核对：头 / 块可解码；块目录（头里的，或布局 2 外置的目录树：节点逐个核对、没有孤儿节点）= 实际块键、
     * 块内条目落在自己的下界区间；每组 Count = 实际条目数；对称；
     * 标 ext 的出边 ⟺ 有 p 键；标签索引 = 各顶点 _labels 展开的集合；id 高水位大于任何已用的 vid。诊断 / 测试用。
     */
    public InvariantReport checkInvariants() {
        List<List<Object>> problems = new ArrayList<>();
        long[] counters = new long[3]; // vertices edges chunks
        Object[] st = new Object[3];   // vid gs chunks
        Long[] maxHead = new Long[1];
        Set<String> dirNodes = new HashSet<>();
        Runnable finish = () -> {
            if (st[0] == null) return;
            @SuppressWarnings("unchecked") Map<List<Object>, List<AdjEntry>> ch = (Map<List<Object>, List<AdjEntry>>) st[2];
            @SuppressWarnings("unchecked") List<Group> gs = (List<Group>) st[1];
            counters[1] += checkVertex((Long) st[0], gs, ch, problems, dirNodes);
            st[0] = null;
            st[1] = null;
            st[2] = new HashMap<>();
        };
        st[2] = new HashMap<List<Object>, List<AdjEntry>>();
        byte[] lo = GraphKeys.familyBytes("a");
        db.forEachRange(RangeOptions.between(lo, GraphKeys.successor(lo)).batch(128), e -> {
            Long vid = GraphKeys.parseHead(e.key());
            if (vid != null) {
                finish.run();
                if (maxHead[0] == null || Long.compareUnsigned(vid, maxHead[0]) > 0) maxHead[0] = vid;
                List<Group> gs = Adjacency.decodeHead(e.value());
                if (gs != null) {
                    st[0] = vid;
                    st[1] = gs;
                    st[2] = new HashMap<>();
                    counters[0]++;
                } else addProblem(problems, List.of("bad-head", vid));
                return true;
            }
            GraphKeys.ChunkKey ck = GraphKeys.parseChunk(e.key());
            if (ck != null) {
                List<AdjEntry> es = Adjacency.decodeChunk(e.value());
                if (es == null) addProblem(problems, List.of("bad-chunk", new String(e.key(), StandardCharsets.US_ASCII)));
                else if (!Objects.equals(ck.vid(), st[0])) addProblem(problems, List.of("orphan-chunk", ck.vid(), List.of(ck.dir(), ck.etype(), new LB(ck.other(), ck.rank()))));
                else {
                    @SuppressWarnings("unchecked") Map<List<Object>, List<AdjEntry>> ch = (Map<List<Object>, List<AdjEntry>>) st[2];
                    ch.put(List.of(ck.dir(), ck.etype(), new LB(ck.other(), ck.rank())), es);
                    counters[2]++;
                }
            }
            return true;
        });
        finish.run();
        // 布局 2 的目录节点：每个都要能从某个组的根走到
        byte[] dlo = GraphKeys.familyBytes("d");
        db.forEachRange(RangeOptions.between(dlo, GraphKeys.successor(dlo)).batch(256), e -> {
            String k = new String(e.key(), StandardCharsets.US_ASCII);
            if (!dirNodes.contains(k)) addProblem(problems, List.of("orphan-dir-node", k));
            return true;
        });
        for (Entry e : GraphModel.rangeAll(db, GraphKeys.familyBytes("p"), false, 256)) {
            long[] k = GraphKeys.parsePropsKey(e.key());
            if (k != null && !Adjacency.isExt(Adjacency.lookup(db, k[0], 0, k[1], k[2], k[3])))
                addProblem(problems, List.of("orphan-props", List.of(k[0], k[1], k[2], k[3])));
        }
        modelProblems(maxHead[0], problems);
        return problems.isEmpty() ? new InvariantReport(true, counters[0], counters[1], counters[2], List.of())
                : new InvariantReport(false, 0, 0, 0, problems);
    }
}
