package io.ailink.agentforge.bitcask.graph;

import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.b;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.cat;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.cfg;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.chunkKeys;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.code;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.dsts;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.ints;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.ok;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.props;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.range;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.s;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.srcs;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.u;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.u64;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.graph.Adjacency.AdjEntry;
import io.ailink.agentforge.bitcask.graph.Adjacency.Group;
import io.ailink.agentforge.bitcask.graph.Adjacency.LB;
import io.ailink.agentforge.bitcask.graph.Graph.AdjQuery;
import io.ailink.agentforge.bitcask.graph.Graph.BatchOptions;
import io.ailink.agentforge.bitcask.graph.Graph.BfsHit;
import io.ailink.agentforge.bitcask.graph.Graph.BfsOptions;
import io.ailink.agentforge.bitcask.graph.Graph.Direction;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeOp;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeRef;
import io.ailink.agentforge.bitcask.graph.Graph.SubgraphOptions;
import io.ailink.agentforge.bitcask.graph.Graph.SubgraphVertex;
import io.ailink.agentforge.bitcask.graph.Graph.VertexOp;

/**
 * 图层 V1（邻接表布局 + 保留命名空间前缀）：编解码、key 数、大属性、自环 / rank、分块（增长 / 分裂 / 回内联 / 滞回）、批量接口、
 * 删顶点选项、并发直通写不丢边、随机交错不变式、etype 并发注册、布局与保留前缀防撞。移植自 clj-bitcask graph_v1_test.clj
 * （期望值对照 bitcask（Erlang）graphdb_*_tests）。
 * <p>clj 里靠 with-redefs 数 bc/get / range / 写批次数 / 写盘字节 / chunk-key 调用次数的断言（D-18～D-21 的读写量回归）在 Java 里没法
 * 无侵入地拦截 {@code Bitcask}（final 类、无钩子），只保留这些测试的功能断言，计数断言跳过。
 */
class GraphV1Test {
    @TempDir
    Path tmp;

    Bitcask db;
    Graph g;

    @BeforeEach
    void setUp() {
        assumeNative();
        db = Bitcask.open(tmp.resolve("db"));
        g = Graph.open(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    /** 另开一个独立库跑 body（同一测试里比较多次装载结果用）。 */
    <T> T withFreshDb(String name, java.util.function.Function<Graph, T> body) {
        try (Bitcask x = Bitcask.open(tmp.resolve(name))) {
            return body.apply(Graph.open(x));
        }
    }

    static AdjEntry ent(long o, long r, byte[] p) {
        return new AdjEntry(o, r, p);
    }

    static final byte[] E0 = new byte[0];

    /** 条目比较用的形态：[o r 属性]（EXT 记成 "ext"）。 */
    static List<Object> norm(AdjEntry e) {
        return List.of(e.other(), e.rank(), Adjacency.isExt(e.props()) ? "ext" : Arrays.toString(e.props()));
    }

    static List<List<Object>> norm(List<AdjEntry> es) {
        return es.stream().map(GraphV1Test::norm).toList();
    }

    static String rawNode(long v) {
        return "n" + String.format("%016x", v);
    }

    static EdgeInput ei(long s, long t, long dd) {
        return EdgeInput.of(s, t, dd);
    }

    static EdgeInput ei(long s, long t, long dd, long rank, Object props) {
        return new EdgeInput(s, t, dd, rank, props);
    }

    static EdgeRef er(long s, long t, long dd) {
        return EdgeRef.of(s, t, dd);
    }

    static final GraphConfig SMALL = GraphConfig.of(256, 256, 128);

    // ═══ 编解码 ═══

    @Test
    void chunkCodecRoundtripAndLayout() {
        for (List<AdjEntry> es : List.of(
                List.of(ent(1, 0, E0), ent(2, 0, E0)),
                List.of(ent(1, 0, E0), ent(1, 3, E0), ent(5, -1, E0)),
                List.of(ent(1, 0, u("a")), ent(2, 7, Adjacency.EXT), ent(3, 0, E0)),
                List.<AdjEntry>of())) {
            assertEquals(norm(es), norm(Adjacency.decodeChunk(Adjacency.encodeChunk(es))));
        }
        // 字节布局与 Erlang 版逐字节相同：<<1, 0, 2:32, 5:64, 6:64>>
        assertArrayEquals(cat(ints(1, 0, 0, 0, 0, 2), u64(5), u64(6)), Adjacency.encodeChunk(List.of(ent(5, 0, E0), ent(6, 0, E0))));
    }

    @Test
    void headMalformedIsRejected() {
        assertNull(Adjacency.decodeHead(b()));
        assertNull(Adjacency.decodeHead(b(2, 0, 0)));
        assertEquals(List.of(), Adjacency.decodeHead(b(1, 0, 0)));
        assertNull(Adjacency.decodeHead(b(1, 0, 0, 0)));                                       // 尾随字节
        assertNull(Adjacency.decodeHead(cat(ints(1, 0, 1, 0, 0, 0, 0, 0, 7), u64(2), u64(1))));  // Count 2 只有 1 条
        assertNull(Adjacency.decodeHead(cat(ints(1, 0, 1, 0, 0, 0, 0, 0, 7), u64(0))));          // Count = 0
        assertNull(Adjacency.decodeHead(cat(ints(1, 0, 1, 2, 0, 0, 0, 0, 7), u64(1), u64(1))));  // dir 2
        assertNull(Adjacency.decodeHead(cat(ints(1, 0, 1, 0, 4, 0, 0, 0, 7), u64(5), ints(0, 0, 0, 1), u64(3), u64(0)))); // 块目录缺 (0,0)
        assertNull(Adjacency.decodeChunk(cat(ints(1, 0, 0, 0, 0, 2), u64(1))));
        List<Object[]> out = new ArrayList<>();
        for (long t = 0; t < 65536; t++) out.add(new Object[]{t, 2L, 0L, E0});
        assertEquals("bitcask/graph-too-many-groups",
                code(() -> Adjacency.buildVertex(1, out, List.of(), new GraphConfig(2, 1L << 30, 65536, 128, 512, 65536, 16, 256))));
    }

    // ═══ key 数 / 大属性 / 自环 ═══

    @Test
    void keyCountIsPerVertex() {
        for (long v = 1; v <= 10; v++) g.putVertex(v, "v");
        List<EdgeInput> es = new ArrayList<>();
        for (long a = 1; a <= 10; a++) for (long c = 1; c <= 10; c++) if (a != c) es.add(ei(a, 7, c));
        g.putEdges(es);
        assertEquals(21, db.status().keyCount());                // 10 顶点 + 10 个头 + g
        assertEquals(9, g.degree(3));
        assertEquals(9, g.inDegree(3));
    }

    @Test
    void largePropsLifecycle() {
        g.init(cfg(4096, 4096, 16));
        String big = "x".repeat(100);
        String pk = GraphKeys.propsKey(1, 7, 2, 0);
        g.putEdge(1, 7, 2, 0, big);
        assertEquals(big, db.getString(pk));
        assertEquals(big, props(g.edge(1, 7, 2)));
        assertEquals(List.of(big), g.outEdges(1).stream().map(GraphTestSupport::props).toList());
        assertEquals(List.of(big), g.edgesByType(7).stream().map(GraphTestSupport::props).toList());
        ok(g);
        g.putEdge(1, 7, 2, 0, "small");
        assertNull(db.get(pk));
        assertEquals("small", props(g.edge(1, 7, 2)));
        g.putEdge(1, 7, 2, 0, big);
        g.removeEdge(1, 7, 2);
        assertNull(db.get(pk));
        g.putEdge(1, 7, 3, 0, big);
        g.removeVertex(1);
        assertNull(db.get(GraphKeys.propsKey(1, 7, 3, 0)));
        assertEquals(0, ok(g).edges());
    }

    @Test
    void selfLoopAndRank() {
        g.putEdge(1, 7, 1);
        g.putEdge(1, 7, 2, 5, null);
        g.putEdge(1, 7, 2, 9, "r9");
        assertEquals(3, g.degree(1));
        assertEquals(1, g.inDegree(1));
        assertEquals(List.of(List.of(1L, 0L), List.of(2L, 5L), List.of(2L, 9L)),
                g.outEdges(1).stream().map(e -> List.of(e.dst(), e.rank())).toList());
        assertEquals("r9", props(g.edge(1, 7, 2, 9)));
        assertNull(g.edge(1, 7, 2));
        g.removeEdge(1, 7, 1);
        assertEquals(0, g.inDegree(1));
        g.putEdge(1, 7, 1);
        g.removeVertex(1);
        assertNull(db.get(GraphKeys.head(1)));
        assertTrue(g.inEdges(2).isEmpty());
        assertEquals(0, ok(g).edges());
    }

    // ═══ 分块 ═══

    @Test
    void chunkGrowSplitShrink() {
        g.init(SMALL);
        g.putEdges(List.of(ei(900, 8, 1), ei(901, 8, 1), ei(902, 8, 1)));
        assertTrue(chunkKeys(db, 1).isEmpty());
        for (long y = 1; y <= 300; y++) g.putEdge(1, 7, y);        // y = 1 是自环
        List<byte[]> ks = chunkKeys(db, 1);
        assertTrue(ks.size() >= 9);
        assertTrue(ks.stream().allMatch(k -> db.get(k).length <= 256));
        assertTrue(ks.stream().allMatch(k -> s(k).substring(21, 23).equals("00")));  // 跳过保留前缀后，只有出边组分块
        assertTrue(db.get(GraphKeys.head(1)).length < 512);        // 头 = 入边内联组 + 出边块目录
        assertEquals(range(1, 301), dsts(g.outEdges(1)));
        assertEquals(List.of(1L, 900L, 901L, 902L), srcs(g.inEdges(1)));
        assertEquals(150, g.edge(1, 7, 150).dst());
        assertEquals(300, g.degree(1));
        assertEquals(303, ok(g).edges());
        assertEquals(range(1, 8), dsts(g.outEdges(1, new AdjQuery(null, 7, false))));
        g.removeEdges(range(1, 301, 3).stream().map(x -> er(1, 7, x)).toList());
        ok(g);
        g.removeEdges(range(11, 301).stream().map(x -> er(1, 7, x)).toList());
        assertEquals(List.of(2L, 3L, 5L, 6L, 8L, 9L), dsts(g.outEdges(1)));
        assertTrue(chunkKeys(db, 1).isEmpty());                    // 48 B < 256/4 ⇒ 回内联
        assertEquals(0, ok(g).chunks());
    }

    @Test
    void chunkHysteresis() {
        g.init(SMALL);
        g.putEdges(range(1, 101).stream().map(x -> ei(1, 7, x)).toList());
        assertFalse(chunkKeys(db, 1).isEmpty());
        g.removeEdges(range(21, 101).stream().map(x -> er(1, 7, x)).toList());
        assertFalse(chunkKeys(db, 1).isEmpty());                   // 160 B > 64 B：仍分块
        assertEquals(20, g.degree(1));
        ok(g);
    }

    // ═══ 批量接口 / 删顶点选项 ═══

    static List<Object> snap(Graph x) {
        List<Object> out = new ArrayList<>();
        for (long v = 0; v < 18; v++) {
            out.add(List.of(x.outEdges(v).stream().map(e -> List.of(e.dst(), e.rank(), props(e))).toList(), srcs(x.inEdges(v))));
        }
        return out;
    }

    @Test
    void putEdgesSortAndBatchOpts() {
        List<EdgeInput> edges = new ArrayList<>();
        for (long i = 1; i <= 300; i++) edges.add(ei((i * 7) % 13, 7, (i * 11) % 17, 0, String.valueOf(i)));
        List<BatchOptions> os = Arrays.asList(null, new BatchOptions(false, Graph.DEFAULT_BATCH_VIDS), new BatchOptions(true, 2),
                new BatchOptions(true, 4096));
        List<List<Object>> results = new ArrayList<>();
        for (int i = 0; i < os.size(); i++) {
            BatchOptions o = os.get(i);
            results.add(withFreshDb("b" + i, x -> {
                x.putEdges(edges, o);
                ok(x);
                return snap(x);
            }));
        }
        assertEquals(1, new HashSet<>(results).size());
        Map<List<Long>, String> lastProps = new HashMap<>();
        for (EdgeInput e : edges) lastProps.put(List.of(e.src(), e.dst()), (String) e.props());
        @SuppressWarnings("unchecked")
        List<List<Object>> outs0 = (List<List<Object>>) ((List<Object>) results.getFirst().getFirst()).getFirst();
        for (List<Object> row : outs0) assertEquals(lastProps.get(List.of(0L, (Long) row.get(0))), row.get(2));
        assertEquals("bitcask/graph-config-invalid", code(() -> g.putEdges(List.of(ei(1, 7, 2)), new BatchOptions(true, 1))));
        // clj 的 [[1 7]]（项不成形）⇒ graph-batch-op-invalid：Java 的 EdgeInput 是 record，编译期即排除，跳过
        g.removeEdges(List.of(er(1, 7, 2)), new BatchOptions(false, 2));
    }

    @Test
    void removeVertexOpts() {
        g.putEdges(range(100, 250).stream().map(x -> ei(1, 7, x)).toList());
        g.withRetries(-1).removeVertex(1, 0);                     // clj {:batch-neighbors :all :retries :infinity}
        assertEquals(0, ok(g).edges());
        g.putEdges(range(100, 250).stream().map(x -> ei(1, 7, x)).toList());
        g.removeVertex(1);                                         // 缺省 64 个邻居一批 ⇒ 3 批
        assertEquals(0, ok(g).edges());
        // clj 里 0 不合法（:all 是全部）；Java 用 0 表示全部，负数不合法
        assertEquals("bitcask/graph-config-invalid", code(() -> g.removeVertex(1, -1)));
    }

    // ═══ hub 路径读写量（D-18）：只保留功能断言 ═══

    @Test
    void removeHubReadsOnlyTouchedChunks() {
        g.init(SMALL);
        // 出边两组（etype 7 / 9）+ 入边 + 自环；每块约 30 条 ⇒ 出边约 50 块
        List<EdgeInput> a = new ArrayList<>(range(2, 1202).stream().map(x -> ei(1, 7, x)).toList());
        a.addAll(range(600, 900).stream().map(x -> ei(1, 9, x)).toList());
        g.putEdges(a);
        List<EdgeInput> b2 = new ArrayList<>(List.of(ei(1, 7, 1)));
        b2.addAll(range(5000, 5100).stream().map(x -> ei(x, 8, 1)).toList());
        g.putEdges(b2);
        int nchunks = chunkKeys(db, 1).size();
        g.removeVertex(1);
        // 计数断言（不开 range、≤ 30 批、每批只读用到的块）需要拦截 bc/get，Java 里跳过
        assertTrue(nchunks > 40);
        assertNull(db.get(GraphKeys.head(1)));
        assertTrue(chunkKeys(db, 1).isEmpty());
        assertTrue(g.inEdges(1201).isEmpty());
        assertTrue(g.outEdges(5000).isEmpty());
        assertEquals(0, ok(g).edges());
    }

    @Test
    void chunkedReadUsesDirectoryPointReads() {
        g.init(SMALL);
        List<EdgeInput> a = new ArrayList<>(range(1, 301).stream().map(x -> ei(1, 7, x)).toList());
        a.addAll(range(1000, 1100).stream().map(x -> ei(1, 9, x)).toList());
        g.putEdges(a);
        List<Long> all = dsts(g.outEdges(1));
        // 点读次数（limit 5 只读首块、度数只读头）需要拦截 bc/get，跳过；功能断言照旧
        assertEquals(all.subList(0, 5), dsts(g.outEdges(1, new AdjQuery(null, 5, false))));
        assertEquals(400, g.degree(1));
        assertEquals(400, g.outEdges(1, new AdjQuery(null, -1, true)).size());
        List<Long> want = new ArrayList<>(range(1, 301));
        want.addAll(range(1000, 1100));
        assertEquals(want, all);
        assertEquals(range(1000, 1100), dsts(g.outEdges(1, AdjQuery.etype(9))));
        List<Long> w2 = new ArrayList<>(range(291, 301));
        w2.addAll(range(1000, 1010));
        List<Long> got = dsts(g.outEdges(1, new AdjQuery(null, 310, false)));
        assertEquals(w2, got.subList(290, got.size()));
        // 目录里有、盘上没有的块按空跳过（弱一致读）
        byte[] victim = chunkKeys(db, 1).get(1);
        int gone = Adjacency.decodeChunk(db.get(victim)).size();
        db.delete(victim);
        assertEquals(400 - gone, g.outEdges(1).size());
    }

    @Test
    void putEdgesSortsByDstWithinSrc() {
        List<EdgeInput> edges = new ArrayList<>(range(2, 2002).stream().map(x -> ei(1, 7, x)).toList());
        Collections.shuffle(edges, new Random(42));
        List<Long> sorted = withFreshDb("s", x -> {
            x.init(SMALL);
            x.putEdges(edges, new BatchOptions(true, 64));
            ok(x);
            return dsts(x.outEdges(1));
        });
        List<Long> unsorted = withFreshDb("u", x -> {
            x.init(SMALL);
            x.putEdges(edges, new BatchOptions(false, 64));
            ok(x);
            return dsts(x.outEdges(1));
        });
        // 写盘字节数对比（排序后 < 不排序的一半）需要拦截 try-write-atomic!，跳过
        assertEquals(range(2, 2002), sorted);
        assertEquals(range(2, 2002), unsorted);
    }

    // ═══ 读写路径常数（D-19）═══

    /** golden 头：出 7（2 条，rank + 属性）、出 8（1 条，外置属性）、入 9（40 条，分块 2 块）。 */
    static final byte[] GOLDEN = cat(ints(1, 0, 3),
            ints(0, 3, 0, 0, 0, 7), u64(2), u64(2), u64(0), ints(0, 0), u64(3), u64(5), ints(0, 2, 97, 98),
            ints(0, 2, 0, 0, 0, 8), u64(1), u64(4), ints(0xFF, 0xFF),
            ints(1, 5, 0, 0, 0, 9), u64(40), ints(0, 0, 0, 2), u64(0), u64(0), u64(10), u64(0));

    @Test
    void headCodecGoldenAndPartialDecode() {
        List<Group> gs = List.of(
                Group.inline(0, 7, 2, 0, List.of(ent(2, 0, E0), ent(3, 5, u("ab")))),
                Group.inline(0, 8, 1, 0, List.of(ent(4, 0, Adjacency.EXT))),
                Group.chunked(1, 9, 40, 1, List.of(new LB(0, 0), new LB(10, 0))));
        // 字节布局不变（与 bitcask 图层 v2 逐字节相同）
        assertArrayEquals(GOLDEN, Adjacency.encodeHead(gs));
        assertEquals(norm(List.of(ent(2, 0, E0), ent(3, 5, u("ab")))),
                norm(Adjacency.decodeChunk(Adjacency.encodeChunk(List.of(ent(2, 0, E0), ent(3, 5, u("ab")))))));
        List<Group> dec = Adjacency.decodeHead(GOLDEN);
        assertEquals(3, dec.size());
        assertEquals(List.of(0, 7L, 2L, 3), List.of(dec.get(0).dir(), dec.get(0).etype(), dec.get(0).count(), dec.get(0).flags()));
        assertEquals(norm(gs.get(0).entries()), norm(dec.get(0).entries()));
        assertEquals(norm(gs.get(1).entries()), norm(dec.get(1).entries()));
        assertSame(Adjacency.EXT, dec.get(1).entries().getFirst().props());
        assertEquals(List.of(1, 9L, 40L, 1), List.of(dec.get(2).dir(), dec.get(2).etype(), dec.get(2).count(), dec.get(2).flags()));
        assertEquals(gs.get(2).chunks(), dec.get(2).chunks());
        // 只数度数：内联组不解条目，计数照旧
        List<Group> skim = Adjacency.decodeHead(GOLDEN, Adjacency.WANT_NONE);
        assertEquals(List.of(List.of(0, 7L, 2L), List.of(0, 8L, 1L), List.of(1, 9L, 40L)),
                skim.stream().map(x -> List.of(x.dir(), x.etype(), x.count())).toList());
        assertTrue(skim.stream().allMatch(Group::skipped));       // 分块组也只校验块 0、跳过块目录
        assertTrue(skim.stream().allMatch(x -> x.entries() == null));
        assertTrue(skim.stream().allMatch(x -> x.chunks() == null));
        // 只解要的组
        List<Group> part = Adjacency.decodeHead(GOLDEN, Adjacency.wantFn(0, 8L));
        assertEquals(List.of(true, false, true), part.stream().map(Group::skipped).toList());
        assertEquals(List.of(List.of(4L, 0L, "ext")), norm(part.get(1).entries()));
        // 跳过时照样校验截断
        for (byte[] bad : List.of(
                cat(ints(1, 0, 1, 0, 0, 0, 0, 0, 7), u64(2), u64(1)),                 // Count 2 只有 1 条
                cat(ints(1, 0, 1, 0, 2, 0, 0, 0, 7), u64(1), u64(1), ints(0, 5, 1, 2)), // 属性长 5 只剩 2
                cat(ints(1, 0, 1, 0, 0, 0, 0, 0, 7), u64(1), u64(1), ints(9)))) {      // 尾随字节
            assertNull(Adjacency.decodeHead(bad, Adjacency.WANT_NONE));
            assertNull(Adjacency.decodeHead(bad));
        }
    }

    @Test
    void readPathsReadEachHeadOnce() {
        // 读头次数（每条读路径只读一次头）需要拦截 bc/get，跳过；功能断言照旧
        g.putEdges(List.of(ei(1, 7, 2), ei(1, 7, 3), ei(1, 8, 4), ei(5, 7, 1), ei(6, 9, 1), ei(2, 7, 3)));
        assertEquals(List.of(2L, 3L, 4L, 5L, 6L), g.neighbors(1, Direction.BOTH));
        assertEquals(List.of(5L, 6L), g.neighbors(1, Direction.IN));
        assertEquals(List.of(1L, 5L), g.bfs(1, new BfsOptions(1, Direction.IN, 7L, null)).stream().map(BfsHit::vid).toList());
        assertEquals(3, g.degree(1));
        assertEquals(2, g.inDegree(1));
        assertNotNull(g.edge(1, 8, 4));
        Graph.Subgraph sg = g.subgraph(1, new SubgraphOptions(2, Direction.BOTH, null, 50, 200));
        assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L), sg.vertices().stream().map(SubgraphVertex::vid).collect(Collectors.toSet()));
        assertEquals(new SubgraphVertex(1, 0, 3, 2, 5), sg.vertices().getFirst());
        assertEquals(6, sg.edges().size());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                g.bfs(1, new BfsOptions(1, Direction.BOTH, null, null)).stream().map(BfsHit::vid).sorted().toList());
    }

    @Test
    void graphConfigIsCachedPerHandle() {
        // g 键读次数需要拦截 bc/get，跳过；保留「新句柄用的是盘上 init 的参数」这条功能断言
        g.init(SMALL);
        for (int i = 0; i < 5; i++) g.putEdge(1, 7, 10 + i);
        db.close();
        db = Bitcask.open(tmp.resolve("db"));
        g = Graph.open(db);
        for (int i = 0; i < 300; i++) g.putEdge(1, 7, 100 + i);
        assertEquals(256, g.config().inlineMax());
        assertFalse(chunkKeys(db, 1).isEmpty());                   // 用的是 init 的 256，不是缺省 4096
        ok(g);
    }

    @Test
    void csrBuildArraysMatchesPairs() {
        long[][] pairs = {{1, 2}, {2, -1}, {-1, 1}, {1, 2}, {-2, -2}, {2, -1}, {1, -2}};
        // clj 的 csr/build（对表）与 build-arrays 在 Java 只有一个 build(long[], long[])
        Csr c = Csr.build(Arrays.stream(pairs).mapToLong(p -> p[0]).toArray(), Arrays.stream(pairs).mapToLong(p -> p[1]).toArray());
        assertArrayEquals(new long[]{1, 2, -2, -1}, c.vertices()); // 无符号升序
        assertEquals(5, c.edgeCount());                            // 重边去重，自环保留
        assertEquals(List.of(2L, -2L), c.outNeighbors(1));
        assertEquals(List.of(-2L), c.outNeighbors(-2));
        assertEquals(0, Csr.build(new long[0], new long[0]).vertexCount());
        assertEquals(0, Csr.build(new long[0], new long[0]).edgeCount());
    }

    // ═══ 字节上的读 / 收尾只看改过的块（D-20）═══

    @Test
    void rawHeadGroupsAndLookup() {
        List<Group> raw = Adjacency.decodeHead(GOLDEN, Adjacency.rawFn(null, null));
        assertEquals(List.of(17, 69, 97), raw.stream().map(Group::raw).toList());   // 条目区 / 块目录在头字节里的起点
        assertEquals(List.of(-1L, -1L, 2L), raw.stream().map(Group::nchunks).toList());
        assertNull(Adjacency.decodeHead(cat(ints(1, 0, 1, 0, 4, 0, 0, 0, 7), u64(5), ints(0, 0, 0, 1), u64(3), u64(0)),
                Adjacency.rawFn(null, null)));                       // 块 0 不是 (0,0)
    }

    @Test
    void edgeLookupOnRawBytes() {
        g.init(cfg(256, 256, 4));
        g.putEdges(range(2, 400).stream().map(x -> ei(1, 7, x)).toList());                   // 分块、定宽 8
        g.putEdges(range(2, 300).stream().map(x -> ei(1, 8, x, 3 * x, null)).toList());      // 分块、定宽 16
        g.putEdges(List.of(ei(1, 9, 5, 0, "ab"), ei(1, 9, 6, 0, "long-props"), ei(1, 9, 7)));  // 内联、变长（含外置）
        g.putEdges(List.of(ei(2, 7, 3, 9, null), ei(2, 7, 4)));                                // 内联、定宽 16
        // 「读头 + 一块 = 2 次 get」需要拦截 bc/get，跳过
        assertEquals(250, g.edge(1, 7, 250).dst());
        for (long dd : List.of(2L, 3L, 157L, 399L)) assertNotNull(g.edge(1, 7, dd));
        assertNull(g.edge(1, 7, 400));
        assertNull(g.edge(1, 7, 1));
        assertEquals(600, g.edge(1, 8, 200, 600).rank());
        assertNull(g.edge(1, 8, 200));                             // rank 0 不在
        assertEquals("ab", props(g.edge(1, 9, 5)));
        assertEquals("long-props", props(g.edge(1, 9, 6)));
        assertEquals("", props(g.edge(1, 9, 7)));
        assertNull(g.edge(1, 9, 8));
        assertNotNull(g.edge(2, 7, 3, 9));
        assertNull(g.edge(2, 7, 3));
        assertNotNull(g.edge(2, 7, 4));
        assertNull(g.edge(3, 7, 4));
        // 缺块按空；坏块报码
        byte[] k = chunkKeys(db, 1).get(1);
        long o = GraphKeys.parseChunk(k).other();
        db.delete(k);
        assertNull(g.edge(1, 7, o));
        db.put(k, b(1, 0, 0, 0, 0, 2, 0, 0, 0));
        assertEquals("bitcask/graph-chunk-malformed", code(() -> g.edge(1, 7, o)));
    }

    @Test
    void neighborIdsAndMaterializeFromBytes() {
        g.init(cfg(256, 256, 4));
        // 同一对端多个 rank（跨块）、带属性（变长 + 外置）、入边
        List<EdgeInput> a = new ArrayList<>();
        for (long dd = 2; dd < 120; dd++) for (long r = 0; r < 3; r++) a.add(ei(1, 7, dd, r, null));
        g.putEdges(a);
        g.putEdges(List.of(ei(1, 9, 500, 0, "x"), ei(1, 9, 501, 0, "long-props"), ei(1, 9, 500, 4, "y")));
        g.putEdges(List.of(ei(700, 7, 1), ei(701, 8, 1), ei(700, 7, 1, 3, null)));
        List<Long> want = new ArrayList<>(range(2, 120));
        want.addAll(List.of(500L, 501L, 700L, 701L));
        assertEquals(want, g.neighbors(1, Direction.BOTH));
        assertEquals(List.of(700L, 701L), g.neighbors(1, Direction.IN));
        assertEquals(List.of(1L), g.neighbors(700));              // 单组：两个 rank 只出一次，不再走全局去重
        assertEquals(List.of(1L), g.neighbors(700, Direction.BOTH));
        List<Edge> o7 = g.outEdges(1, AdjQuery.etype(7));
        List<Long> every3 = new ArrayList<>();
        for (int i = 0; i < o7.size(); i += 3) every3.add(o7.get(i).dst());
        assertEquals(range(2, 120), every3);
        assertEquals(List.of(List.of(2L, 0L), List.of(2L, 1L), List.of(2L, 2L), List.of(3L, 0L), List.of(3L, 1L)),
                g.outEdges(1, new AdjQuery(null, 5, false)).stream().map(e -> List.of(e.dst(), e.rank())).toList());
        assertEquals(range(2, 120), g.kHop(1, 1, Direction.OUT, 7L));
        assertEquals(List.of(1L, 500L), g.shortestPath(1, 500));
        Csr c = g.materialize();
        List<Edge> es = g.edges();
        Csr ref = Csr.build(es.stream().mapToLong(Edge::src).toArray(), es.stream().mapToLong(Edge::dst).toArray());
        assertArrayEquals(ref.vertices(), c.vertices());
        assertEquals(ref.edgeCount(), c.edgeCount());
        List<Long> w2 = new ArrayList<>(range(2, 120));
        w2.addAll(List.of(500L, 501L));
        assertEquals(w2, c.outNeighbors(1));
        assertEquals(119, g.materialize(7L).edgeCount());          // 1→2..119 + 700→1（两个 rank 去重）
    }

    @Test
    void hubWriteFinalizesOnlyDirtyChunks() {
        g.init(SMALL);
        g.putEdges(range(2, 2002).stream().map(x -> ei(1, 7, x)).toList());
        int nchunks = chunkKeys(db, 1).size();
        // 「收尾只给改过的块拼块键（≤ 6 次 chunk-key）」需要拦截 gk/chunk-key，跳过
        g.putEdge(1, 7, 1000000);
        assertTrue(nchunks > 60);
        // 逐条随机加删穿过分裂 / 空块删除，对模型 + 不变式
        Random rnd = new Random(7);
        TreeSet<Long> model = new TreeSet<>(range(2, 2002));
        model.add(1000000L);
        for (int i = 0; i < 1500; i++) {
            long dd = 2 + rnd.nextInt(2500);
            if (rnd.nextBoolean()) {
                g.putEdge(1, 7, dd);
                model.add(dd);
            } else {
                g.removeEdge(1, 7, dd);
                model.remove(dd);
            }
        }
        assertEquals(new ArrayList<>(model), dsts(g.outEdges(1)));
        ok(g);
        // 删掉中间一整块的条目 ⇒ 块被删、目录更新
        byte[] k = chunkKeys(db, 1).get(3);
        List<Long> ids = Adjacency.decodeChunk(db.get(k)).stream().map(AdjEntry::other).toList();
        for (long dd : ids) g.removeEdge(1, 7, dd);
        assertNull(db.get(k));
        ok(g);
    }

    // ═══ 写会话的字节视图（D-21）═══
    // byte-views-write-the-same-bytes-as-vectors / byte-view-tables-in-session：clj 的 *bin-tables* 字节视图优化在 Java 版没有移植
    // （见 Adjacency 类注释），没有可做差分的两条路径；这里只把同一串确定性操作跑一遍、核对全图不变式。

    /** 按种子生成并执行一串图操作（单线程、确定性）。 */
    static void runScript(Graph x, long seed) {
        Random rnd = new Random(seed);
        long[] vids = {1, 2, 3, 4, 5, 6, 7, 8, -1};
        long[] types = {7, 7, 8, 9};
        x.init(cfg(256, 256, 8));
        // hub：两个类型、乱序装载，跨块分裂
        List<EdgeInput> hub = new ArrayList<>(range(100, 700).stream().map(dd -> ei(1, 7, dd)).toList());
        Collections.shuffle(hub, rnd);
        x.putEdges(hub, new BatchOptions(false, Graph.DEFAULT_BATCH_VIDS));
        x.putEdges(range(100, 400, 3).stream().map(dd -> ei(1, 8, dd, dd % 2 == 0 ? 0 : dd + 1, null)).toList());
        for (int i = 0; i < 1500; i++) {
            long s = vids[rnd.nextInt(vids.length)];
            long dd = rnd.nextInt(3) == 0 ? 100 + rnd.nextInt(700) : vids[rnd.nextInt(vids.length)];
            long t = types[rnd.nextInt(types.length)];
            long r = rnd.nextInt(4) == 0 ? rnd.nextInt(3) : 0;
            switch (rnd.nextInt(10)) {
                case 0, 1, 2, 3 -> {
                    Object p = switch (rnd.nextInt(4)) {
                        case 0 -> null;
                        case 1 -> "p";
                        case 2 -> "long-props-here";
                        default -> "";
                    };
                    x.putEdge(s, t, dd, r, p);
                }
                case 4, 5, 6 -> x.removeEdge(s, t, dd, r);
                case 7 -> {
                    // 自环 s = dd 时两条边身份相同 r ≠ r+1，不会撞重复检查
                    x.putBatch(List.of(new VertexOp(s, "v" + Long.toUnsignedString(s)), new EdgeOp(s, t, dd, r, null), new EdgeOp(dd, t, s, r + 1, null)));
                }
                case 8 -> {
                    List<EdgeInput> es = new ArrayList<>();
                    for (int k = 0; k < 20; k++) es.add(ei(s, t, 1000 + rnd.nextInt(50), rnd.nextInt(2), null));
                    x.putEdges(es);
                }
                default -> {
                    List<EdgeRef> es = new ArrayList<>();
                    for (int k = 0; k < 20; k++) es.add(new EdgeRef(s, t, 1000 + rnd.nextInt(50), rnd.nextInt(2)));
                    x.removeEdges(es);
                }
            }
        }
        // rank 出现又删光、大 hub 删到回内联、删顶点
        x.putEdge(2, 7, 3, 5, null);
        x.removeEdge(2, 7, 3, 5);
        x.removeEdges(range(100, 690).stream().map(dd -> er(1, 7, dd)).toList());
        x.removeVertex(3);
    }

    @Test
    void scriptedMixedOpsKeepInvariants() {
        for (long seed : List.of(1L, 2L, 3L)) {
            Graph.InvariantReport r = withFreshDb("seed" + seed, x -> {
                runScript(x, seed);
                return x.checkInvariants();
            });
            assertTrue(r.ok(), "seed " + seed + " " + r);
            assertTrue(r.edges() > 0);
        }
    }

    // ═══ 读路径一遍出边 map / 计数只走组头（D-24）═══

    @Test
    void readPathsAgreeWithScan() {
        g.init(cfg(256, 256, 4));
        Random rnd = new Random(11);
        g.putEdges(range(2, 300).stream().map(dd -> ei(1, 7, dd, dd % 3, null)).toList());   // 分块 hub，带 rank
        List<EdgeInput> rs = new ArrayList<>();
        long[] ts = {7, 8, 9};
        for (int i = 0; i < 400; i++) {
            long s = 2 + rnd.nextInt(20), t = ts[rnd.nextInt(3)], dd = 1 + rnd.nextInt(25), r = rnd.nextInt(2);
            Object p = switch (rnd.nextInt(3)) {
                case 0 -> null;
                case 1 -> "ab";
                default -> "long-props";
            };
            rs.add(ei(s, t, dd, r, p));
        }
        g.putEdges(rs);
        List<Edge> all = g.edges();
        assertTrue(all.size() > 500);
        LinkedHashSet<Long> vids = new LinkedHashSet<>();
        for (Edge e : all) {
            vids.add(e.src());
            vids.add(e.dst());
        }
        Comparator<Edge> inKey = Comparator.comparingLong(Edge::etype).thenComparingLong(Edge::src).thenComparingLong(Edge::rank);
        for (long v : vids) {
            List<Edge> outs = all.stream().filter(e -> e.src() == v).toList();
            List<Edge> ins = all.stream().filter(e -> e.dst() == v).sorted(inKey).toList();
            assertEquals(outs, g.outEdges(v), "out " + v);
            assertEquals(outs, g.outEdges(v, new AdjQuery(null, -1, true)));
            assertEquals(outs.subList(0, Math.min(3, outs.size())), g.outEdges(v, new AdjQuery(null, 3, false)));
            List<Edge> gotIns = g.inEdges(v);
            assertEquals(ins.stream().map(e -> List.of(e.src(), e.etype(), e.dst(), e.rank())).toList(),
                    gotIns.stream().map(e -> List.of(e.src(), e.etype(), e.dst(), e.rank())).toList(), "in " + v);
            assertTrue(gotIns.stream().allMatch(e -> e.props().length == 0));
            assertEquals(outs.size(), g.degree(v));
            assertEquals(ins.size(), g.inDegree(v));
            assertEquals(outs.stream().filter(e -> e.etype() == 8).count(), g.degree(v, 8));
            assertEquals(outs.stream().filter(e -> e.etype() == 9).toList(), g.outEdges(v, AdjQuery.etype(9)));
        }
        assertArrayEquals(new long[]{0, 0}, Adjacency.dirCounts(db, 99999, null));
        // 头变形：计数同样报码（结构校验与 decodeHead 同口径）
        for (byte[] bad : List.of(b(1, 0, 1, 0, 0, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 1),  // Count 2 只有 1 条
                b(1, 0, 0, 9))) {                                                                             // 尾随字节
            db.put(GraphKeys.head(4242), bad);
            assertEquals("bitcask/graph-adjacency-malformed", code(() -> g.degree(4242)));
            assertEquals("bitcask/graph-adjacency-malformed", code(() -> g.outEdges(4242)));
        }
    }

    // ═══ 并发（条件写 + 重试，不要求单写者）═══

    @Test
    void concurrentWritersSameHubLoseNothing() {
        long hub = 1;
        int nproc = 16, per = 25;
        Graph gi = g.withRetries(-1);
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (int p = 1; p <= nproc; p++) {
            int pp = p;
            fs.add(CompletableFuture.runAsync(() -> {
                for (int i = 1; i <= per; i++) {
                    long dd = pp * 1000L + i;
                    gi.putEdge(hub, 7, dd);
                    gi.putEdge(dd, 7, hub);
                }
            }, runner()));
        }
        fs.forEach(CompletableFuture::join);
        int n = nproc * per;
        assertEquals(n, g.degree(hub, 7));
        assertEquals(n, g.inDegree(hub, 7));
        assertEquals(n, g.outEdges(hub).size());
        assertEquals(2L * n, ok(g).edges());
    }

    /** 每个任务一个平台线程（公共 ForkJoin 池并行度可能只有 1～2，模拟不出 clj future 的并发度）。 */
    static java.util.concurrent.Executor runner() {
        return r -> Thread.ofPlatform().start(r);
    }

    static void retryConflict(Runnable f) {
        while (true) {
            try {
                f.run();
                return;
            } catch (BitcaskException e) {
                if (!"bitcask/graph-conflict".equals(e.code())) throw e;
            }
        }
    }

    @Test
    void concurrentMixedOpsKeepInvariants() {
        g.init(cfg(128, 128, 128));
        int nv = 12;
        Graph gi = g.withRetries(-1);
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            int pp = p;
            fs.add(CompletableFuture.runAsync(() -> {
                Random rnd = new Random(pp);
                for (int i = 0; i < 120; i++) {
                    long a = 1 + rnd.nextInt(nv), c = 1 + rnd.nextInt(nv), r = 1 + rnd.nextInt(4);
                    if (rnd.nextInt(3) == 0) retryConflict(() -> g.removeEdge(a, 7, c, r));
                    else gi.putEdge(a, 7, c, r, null);
                }
            }, runner()));
        }
        for (int p : List.of(100, 101)) {
            fs.add(CompletableFuture.runAsync(() -> {
                Random rnd = new Random(p);
                for (int i = 0; i < 10; i++) {
                    long v = 1 + rnd.nextInt(nv);
                    retryConflict(() -> g.removeVertex(v));
                }
            }, runner()));
        }
        fs.forEach(CompletableFuture::join);
        ok(g);
    }

    @Test
    void registerEtypeConcurrently() {
        List<String> names = range(1, 17).stream().map(i -> "et-" + i).toList();
        List<CompletableFuture<Long>> fs = names.stream().map(nm -> CompletableFuture.supplyAsync(() -> g.registerEtype(nm), runnerS())).toList();
        List<Long> ids = fs.stream().map(CompletableFuture::join).sorted().toList();
        assertEquals(range(1, 17), ids);
        for (String nm : names) assertEquals(nm, g.etypeName(g.etypeId(nm)));
        List<CompletableFuture<Long>> same = range(0, 16).stream().map(i -> CompletableFuture.supplyAsync(() -> g.registerEtype("same"), runnerS())).toList();
        assertEquals(Set.of(17L), same.stream().map(CompletableFuture::join).collect(Collectors.toSet()));
        assertEquals(18, g.registerEtype("later"));
    }

    static java.util.concurrent.Executor runnerS() {
        return runner();
    }

    // ═══ 布局 / 保留命名空间 ═══

    @Test
    void layoutDetection() {
        assertEquals("empty", g.layout());
        db.put("note", u("app"));                                  // 应用键不参与图布局检测
        assertEquals("empty", g.layout());
        g.putEdge(1, 7, 2);
        assertEquals("v1", g.layout());
        assertEquals(GraphConfig.DEFAULT, g.config());
    }

    @Test
    void ordinaryKeysCannotHitGraphNames() {
        g.putVertex(1, "one");
        g.putEdge(1, 7, 2, 0, "p");
        // 这些是能撞裸键布局的普通业务 key；0x01 首字节让它们在字节层够不到 V1 图键。
        db.put("a0000000000000002", u("app-a"));
        db.put("g", u("app-g"));
        db.put(rawNode(1), u("app-n"));
        assertEquals("v1", g.layout());
        assertEquals("app-a", db.getString("a0000000000000002"));
        assertEquals("app-g", db.getString("g"));
        assertEquals("app-n", db.getString(rawNode(1)));
        assertEquals(List.of(new Edge(1, 7, 2, 0, u("p"))), g.edges());
        g.putEdge(2, 7, 3);
        assertEquals(2, ok(g).edges());
        assertEquals("one", g.vertex(1));
    }

    // ═══ 自审回归 ═══

    @Test
    void negativeEtypeIsTheSameGroup() {
        // etype 按 32 位无符号：-1 ≡ 0xFFFFFFFF。写入时没规整的话，第二条边会把同一类型写成两个组、头被写坏。
        g.putEdge(1, -1, 2);
        g.putEdge(1, -1, 3);
        g.putEdge(1, 0xFFFFFFFFL, 4);
        assertEquals(List.of(2L, 3L, 4L), dsts(g.outEdges(1)));
        assertEquals(List.of(2L, 3L, 4L), dsts(g.outEdges(1, AdjQuery.etype(-1))));
        assertEquals(3, g.degree(1, -1));
        assertEquals(3, g.degree(1, 0xFFFFFFFFL));
        assertNotNull(g.edge(1, -1, 3));
        assertEquals(3, g.edgesByType(-1).size());
        g.removeEdge(1, -1, 2);
        assertEquals(List.of(3L, 4L), dsts(g.outEdges(1)));
        assertEquals(2, ok(g).edges());
    }

    @Test
    void etypeOutOfRangeIsRejected() {
        long big = 1L << 40;
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.putEdge(1, big, 2)));
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.putEdge(1, -2147483649L, 2)));
        // 批量接口先校验整批：坏的在最后也一条都不写
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.putEdges(List.of(ei(1, 7, 2), ei(3, 7, 4), ei(5, big, 6)))));
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.putBatch(List.of(new EdgeOp(1, 7, 2, 0, null), new EdgeOp(1, big, 3, 0, null)))));
        assertTrue(g.edges().isEmpty());
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.outEdges(1, AdjQuery.etype(big))));
        assertEquals("bitcask/graph-etype-out-of-range", code(() -> g.edgesByType(big)));
        g.putEdge(1, -2147483648L, 2);                             // 下界：有符号最小值 ≡ 0x80000000
        assertEquals(List.of(0x80000000L), g.outEdges(1).stream().map(Edge::etype).toList());
    }

    @Test
    void scanRejectsMalformedHead() {
        g.putEdge(1, 7, 2);
        db.put(GraphKeys.head(5), u("garbage"));
        assertEquals("bitcask/graph-adjacency-malformed", code(() -> g.edges()));
        assertEquals("bitcask/graph-adjacency-malformed", code(() -> g.materialize()));
        Graph.InvariantReport r = g.checkInvariants();
        assertFalse(r.ok());
        assertTrue(r.problems().contains(List.of("bad-head", 5L)), String.valueOf(r));
    }

    @Test
    void streamingInvariantsCatchDamage() {
        g.putEdge(1, 7, 2);
        g.putEdge(3, 7, 4);
        assertEquals(new Graph.InvariantReport(true, 4, 2, 0, List.of()), g.checkInvariants());
        // 抹掉 2 的入边：1→2 不对称
        db.put(GraphKeys.head(2), Adjacency.encodeHead(List.of()));
        // 孤立块与孤立属性键
        db.put(GraphKeys.chunkKey(3, 0, 7, 9, 0), Adjacency.encodeChunk(List.of()));
        db.put(GraphKeys.propsKey(3, 7, 4, 0), u("x"));
        Graph.InvariantReport r = g.checkInvariants();
        Set<Object> kinds = r.problems().stream().map(List::getFirst).collect(Collectors.toSet());
        assertFalse(r.ok());
        assertTrue(kinds.contains("asymmetric"), String.valueOf(r));
        assertTrue(kinds.contains("orphan-chunk"), String.valueOf(r));
        assertTrue(kinds.contains("orphan-props"), String.valueOf(r));
    }
}
