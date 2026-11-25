package io.ailink.agentforge.bitcask.graph;

import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.code;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.dsts;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.pairs;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.props;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.range;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.srcs;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.graph.Graph.AdjQuery;
import io.ailink.agentforge.bitcask.graph.Graph.BfsHit;
import io.ailink.agentforge.bitcask.graph.Graph.BfsOptions;
import io.ailink.agentforge.bitcask.graph.Graph.Direction;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeOp;
import io.ailink.agentforge.bitcask.graph.Graph.RemoveEdgeOp;
import io.ailink.agentforge.bitcask.graph.Graph.VertexOp;

/**
 * 图存储层（V1）。移植自 clj-bitcask graph_test.clj（§1–§13 的期望值源自 keel tests/bitcask-graph.ss，按邻接表语义改过）；
 * V1 自己的覆盖在 {@link GraphV1Test}。
 */
class GraphTest {
    @TempDir
    Path d;

    Bitcask db;
    Graph g;

    @BeforeEach
    void setUp() {
        assumeNative();
        db = Bitcask.open(d);
        g = Graph.open(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) db.close();
    }

    /** 关掉旧句柄、开新的。 */
    void reopen() {
        db.close();
        db = Bitcask.open(d);
        g = Graph.open(db);
    }

    /** 顶点 1..4；边（etype 7）：1→2, 2→3, 3→4, 1→3；边（etype 8）：1→3（props "weight:0.9"）。 */
    void seed() {
        g.putVertex(1, "alice");
        g.putVertex(2, "bob");
        g.putVertex(3, "carol");
        g.putVertex(4, "dave");
        g.putEdge(1, 7, 2);
        g.putEdge(2, 7, 3);
        g.putEdge(3, 7, 4);
        g.putEdge(1, 7, 3);
        g.putEdge(1, 8, 3, 0, "weight:0.9");
    }

    static List<String> visits(List<BfsHit> hs) {
        return hs.stream().map(h -> Long.toUnsignedString(h.vid()) + "@" + h.depth()).toList();
    }

    // ═══ §1 key codec ═══

    @Test
    void s1KeyCodec() {
        assertEquals("\u0001_g:n000000000000002a", GraphKeys.node(42));
        assertArrayEquals(u("ac"), GraphKeys.successor(u("ab")));
        assertArrayEquals(u("f"), GraphKeys.successor(u("e")));
        assertNull(GraphKeys.successor(new byte[]{(byte) 0xFF, (byte) 0xFF}));
        // 定宽 hex 的上界（ulong.MaxValue = -1 按无符号）
        assertEquals("\u0001_g:nffffffffffffffff", GraphKeys.node(-1));
        // V1
        assertEquals("\u0001_g:a000000000000002a", GraphKeys.head(42));
        String z15 = "0".repeat(15);
        assertEquals("\u0001_g:a" + z15 + "1" + "01" + "00000007" + z15 + "2" + z15 + "9", GraphKeys.chunkKey(1, 1, 7, 2, 9));
        assertEquals(63, GraphKeys.chunkKey(-1, 1, 0xFFFFFFFFL, -1, -1).length());
        assertEquals(new GraphKeys.ChunkKey(1, 1, 7, 2, 9), GraphKeys.parseChunk(GraphKeys.chunkKey(1, 1, 7, 2, 9)));
        assertEquals(42L, GraphKeys.parseHead(GraphKeys.ascii(GraphKeys.head(42))));
        assertNull(GraphKeys.parseHead(GraphKeys.ascii(GraphKeys.chunkKey(1, 1, 7, 2, 9))));
        assertArrayEquals(new long[]{1, 7, 2, 9}, GraphKeys.parsePropsKey(GraphKeys.ascii(GraphKeys.propsKey(1, 7, 2, 9))));
        // clj 的 group-prefix 在 Java 版没有对应函数：直接核对块键以 head + dir + etype 开头
        assertTrue(GraphKeys.chunkKey(1, 0, 7, 2, 9).startsWith(GraphKeys.head(1) + "00" + "00000007"));
        assertTrue(GraphKeys.chunkKey(1, 0, 7, 2, 9).startsWith(GraphKeys.head(1)));
    }

    // ═══ §2 顶点 CRUD ═══

    @Test
    void s2VertexCrud() {
        seed();
        assertEquals("alice", g.vertex(1));
        assertNull(g.vertex(99));
        g.putVertex(1, "alice prime");
        assertEquals("alice prime", g.vertex(1));
    }

    // ═══ §3 边 CRUD ═══

    @Test
    void s3EdgeCrud() {
        seed();
        Edge e = g.edge(1, 8, 3);
        assertEquals(List.of(1L, 8L, 3L, 0L), List.of(e.src(), e.etype(), e.dst(), e.rank()));
        assertEquals("weight:0.9", props(e));
        assertNull(g.edge(3, 8, 1));
        g.putEdge(1, 8, 3, 0, "weight:1.0");
        assertEquals("weight:1.0", props(g.edge(1, 8, 3)));
        g.removeEdge(1, 8, 3);
        assertNull(g.edge(1, 8, 3));
        assertEquals(List.of(1L, 2L), srcs(g.inEdges(3)));
        assertEquals(List.of(2L, 3L), dsts(g.outEdges(1)));
        g.removeEdge(1, 8, 3);
    }

    // ═══ §4 删顶点级联 ═══

    @Test
    void s4RemoveVertexCascades() {
        seed();
        g.removeVertex(2);
        assertNull(g.vertex(2));
        assertEquals(List.of(3L, 3L), dsts(g.outEdges(1)));
        assertEquals(List.of(4L), dsts(g.outEdges(3)));
        assertEquals(List.of(1L), srcs(g.inEdges(3, AdjQuery.etype(7))));
        assertTrue(g.inEdges(2).isEmpty());
    }

    @Test
    void s4RemoveVertexFixesNeighborCounters() {
        for (long v : List.of(1L, 2L, 3L, 4L)) g.putVertex(v, "v");
        g.putEdge(1, 7, 2);
        g.putEdge(3, 7, 2);
        g.putEdge(2, 7, 3);
        g.putEdge(2, 9, 4);
        g.removeVertex(2);
        assertEquals(0, g.degree(1, 7));
        assertEquals(0, g.degree(3, 7));
        assertEquals(0, g.inDegree(4, 9));                       // 4 的 etype 9 入度归 0
        assertNull(db.get(GraphKeys.head(2)));                   // 自己的邻接表头删掉
        assertNotNull(g.vertex(3));
        assertTrue(g.outEdges(3).isEmpty());
    }

    @Test
    void s4SelfLoopIsRemovedWithoutTrippingTxnValidation() {
        g.putVertex(5, "loop");
        g.putEdge(5, 7, 5);
        g.putEdge(5, 7, 6);
        g.removeVertex(5);
        assertNull(g.vertex(5));
        assertTrue(g.edges().isEmpty());
        assertTrue(g.inEdges(6).isEmpty());
    }

    // ═══ §5 邻接扫描 ═══

    @Test
    void s5AdjacencyScans() {
        seed();
        assertEquals(List.of(2L, 3L, 3L), dsts(g.outEdges(1)));
        assertEquals(List.of(3L), dsts(g.outEdges(1, AdjQuery.etype(8))));
        assertEquals(List.of(2L), dsts(g.outEdges(1, new AdjQuery(null, 1, false))));
        assertTrue(g.outEdges(1, new AdjQuery(null, 0, false)).isEmpty());
        assertEquals(List.of(1L, 2L, 1L), srcs(g.inEdges(3)));
        assertEquals(List.of(1L), srcs(g.inEdges(3, AdjQuery.etype(8))));
        assertEquals(List.of(3L), srcs(g.inEdges(4)));
        assertEquals(List.of(4L), g.neighbors(3, Direction.OUT));
        assertEquals(List.of(1L, 2L), g.neighbors(3, Direction.IN));
        assertEquals(List.of(4L, 1L, 2L), g.neighbors(3, Direction.BOTH));
        assertEquals(3, g.degree(1));
        assertEquals(1, g.degree(1, 8));
        assertEquals(0, g.degree(4));
        assertEquals(2, g.degree(1, 7));
        g.putEdge(1, 7, 2, 0, "x");
        assertEquals(2, g.degree(1, 7));                         // upsert 不重复计数
        g.removeEdge(1, 8, 3);
        assertEquals(0, g.degree(1, 8));
    }

    // ═══ §6 按类型列边（扫 a 家族，带属性）═══

    @Test
    void s6EdgesByType() {
        seed();
        assertEquals(List.of("1->2", "1->3", "2->3", "3->4"), pairs(g.edgesByType(7)));
        assertEquals(List.of("1->3"), pairs(g.edgesByType(8)));
        assertTrue(g.edgesByType(99).isEmpty());
        assertEquals("weight:0.9", props(g.edgesByType(8).getFirst()));
        assertEquals(2, g.edgesByType(7, 2).size());
        assertEquals("weight:0.9", props(g.edge(1, 8, 3)));
    }

    // ═══ §7 属性过滤 ═══

    @Test
    void s7NeighborsWhere() {
        seed();
        assertEquals(List.of(1L), g.neighborsWhere(3, Direction.IN, (id, v) -> v != null && v.startsWith("a")));
        g.putEdge(3, 9, 99);
        assertEquals(List.of(99L), g.neighborsWhere(3, Direction.OUT, (id, v) -> v == null));
    }

    // ═══ §8 遍历 ═══

    @Test
    void s8Traversal() {
        seed();
        assertEquals(List.of("1@0", "2@1", "3@1", "4@2"), visits(g.bfs(1)));
        assertEquals(List.of("1@0", "2@1", "3@1"), visits(g.bfs(1, new BfsOptions(1, Direction.OUT, null, null))));
        assertEquals(List.of("4@0", "3@1", "1@2", "2@2"), visits(g.bfs(4, new BfsOptions(null, Direction.IN, null, null))));
        assertEquals(List.of("1@0", "3@1"), visits(g.bfs(1, new BfsOptions(null, Direction.OUT, 8L, null))));
        List<String> seen = new ArrayList<>();
        g.bfs(1, new BfsOptions(null, Direction.OUT, null, (v, dd) -> seen.add(v + "@" + dd)));
        assertEquals(List.of("1@0", "2@1", "3@1", "4@2"), seen);
        assertEquals(List.of(2L, 3L), g.kHop(1, 1));
        assertEquals(List.of(2L, 3L, 4L), g.kHop(1, 2));
        assertTrue(g.kHop(4, 2).isEmpty());
        assertEquals(List.of(1L, 3L, 4L), g.shortestPath(1, 4));
        assertNull(g.shortestPath(4, 1));
        assertEquals(List.of(2L), g.shortestPath(2, 2));
        assertNull(g.shortestPath(1, 4, 8L));
        assertEquals("bitcask/graph-negative", code(() -> g.kHop(1, -1)));
    }

    // ═══ §9 etype 注册 ═══

    @Test
    void s9EtypeRegistry() {
        assertEquals(1, g.registerEtype("follows"));
        assertEquals(2, g.registerEtype("likes"));
        assertEquals(1, g.registerEtype("follows"));
        assertEquals(1L, g.etypeId("follows"));
        assertEquals("likes", g.etypeName(2));
        assertNull(g.etypeId("hates"));
        assertNull(g.etypeName(99));
        assertEquals("bitcask/graph-etype-name-empty", code(() -> g.registerEtype("")));
        assertEquals(3, g.registerEtype("关注"));
        assertEquals("关注", g.etypeName(3));
    }

    // ═══ §10 持久性 ═══

    @Test
    void s10PersistsAcrossReopen() {
        seed();
        reopen();
        assertEquals("carol", g.vertex(3));
        assertEquals(List.of(2L, 3L, 3L), dsts(g.outEdges(1)));
        assertEquals(List.of(1L, 3L, 4L), g.shortestPath(1, 4));
        assertEquals(2, g.degree(1, 7));
    }

    // ═══ §11 OLAP ═══

    static List<String> comps(Csr c) {
        return c.components().stream().map(x -> Long.toString(x.vid()) + ":" + x.component()).toList();
    }

    static List<String> dists(List<Csr.Distance> ds) {
        return ds.stream().map(x -> x.vid() + ":" + x.distance()).toList();
    }

    @Test
    void s11Olap() {
        seed();
        Csr c = g.materialize();
        assertEquals(4, c.vertexCount());
        assertEquals(4, c.edgeCount());                          // 1→3 双 etype 去重记一条
        assertArrayEquals(new long[]{1, 2, 3, 4}, c.vertices());
        assertEquals(List.of(2L, 3L), c.outNeighbors(1));
        assertEquals(List.of(4L), c.outNeighbors(3));
        assertTrue(c.outNeighbors(4).isEmpty());
        assertTrue(c.outNeighbors(99).isEmpty());
        List<Csr.RankedVertex> ranks = c.pageRank();
        Map<Long, Double> rank = ranks.stream().collect(Collectors.toMap(Csr.RankedVertex::vid, Csr.RankedVertex::rank));
        assertEquals(4, ranks.size());
        assertTrue(Math.abs(1.0 - ranks.stream().mapToDouble(Csr.RankedVertex::rank).sum()) < 1e-6);
        assertTrue(rank.get(3L) > rank.get(1L));
        assertTrue(rank.get(4L) > 0);
        assertEquals(List.of("1:1", "2:1", "3:1", "4:1"), comps(c));
        assertEquals(List.of("1:0", "2:1", "3:1", "4:2"), dists(c.shortestDistances(1)));
        assertEquals(List.of("4:0"), dists(c.shortestDistances(4)));
        assertNull(c.shortestDistances(99));
        Csr c8 = g.materialize(8L);
        assertEquals(2, c8.vertexCount());
        assertEquals(1, c8.edgeCount());
        assertEquals(List.of(3L), c8.outNeighbors(1));
        assertEquals("bitcask/graph-pagerank-damping", code(() -> c.pageRank(1.5, 1e-10, 100)));
    }

    @Test
    void s11TwoComponents() {
        for (long v : List.of(1L, 2L, 3L, 4L)) g.putVertex(v, "v");
        g.putEdge(1, 7, 2);
        g.putEdge(3, 7, 4);
        assertEquals(List.of("1:1", "2:1", "3:3", "4:3"), comps(g.materialize()));
    }

    @Test
    void s11UnsignedVidOrder() {
        // vid 是无符号的 ——「负」的 long 排在最后，与 keel / C# 的 ulong 序一致
        g.putEdge(-1, 7, 1);
        g.putEdge(1, 7, 2);
        Csr c = g.materialize();
        assertArrayEquals(new long[]{1, 2, -1}, c.vertices());
        assertEquals(List.of("1:1", "2:1", "-1:1"), comps(c));
        assertEquals(List.of(1L, -1L), srcs(g.edges()));
    }

    // ═══ §12 一致性 ═══

    @Test
    void s12ReadYourWrites() {
        seed();
        g.putEdge(1, 42, 777, 0, "fresh");
        assertEquals(List.of(777L), dsts(g.outEdges(1, AdjQuery.etype(42))));
    }

    /** during 在后台跑；期间反复 consistent 读 hub 的出边，每次都严格升序无重复。 */
    void hubReadsWhile(long src, long etype, Runnable during) {
        CompletableFuture<Void> f = CompletableFuture.runAsync(during);
        while (true) {
            List<Long> ds = dsts(g.outEdges(src, new AdjQuery(etype, -1, true)));
            assertEquals(ds.stream().sorted().toList(), ds);         // 严格升序（vid 都是正数，有符号序 = 无符号序）
            assertEquals(new HashSet<>(ds).size(), ds.size());
            if (f.isDone()) {
                f.join();
                return;
            }
        }
    }

    @Test
    void s12InsertDuringScan() {
        g.init(GraphConfig.of(256, 256, 128));                       // 让 hub 分块，读分多次
        g.putEdges(range(0, 100).stream().map(i -> EdgeInput.of(1, 7, 100 + i)).toList());
        Graph gi = g.withRetries(-1);
        hubReadsWhile(1, 7, () -> {
            for (int i = 0; i < 50; i++) gi.putEdge(1, 7, 1000 + i, 0, null);
        });
        List<Long> ds = dsts(g.outEdges(1));
        assertTrue(ds.containsAll(range(100, 200)));
        assertEquals(150, ds.size());
        assertTrue(g.checkInvariants().ok());
    }

    @Test
    void s12DeleteDuringScan() {
        g.init(GraphConfig.of(256, 256, 128));
        g.putEdges(range(0, 100).stream().map(i -> EdgeInput.of(2, 7, 300 + i)).toList());
        Graph gi = g.withRetries(-1);
        hubReadsWhile(2, 7, () -> {
            for (long dd = 300; dd < 350; dd++) gi.removeEdge(2, 7, dd, 0);
        });
        assertEquals(range(350, 400), dsts(g.outEdges(2)));
        assertTrue(g.checkInvariants().ok());
    }

    // ═══ §13 组合事务 ═══

    @Test
    void s13PutBatch() {
        g.putBatch(List.of(new VertexOp(42, "alice"), new EdgeOp(42, 7, 100, 0, null), new EdgeOp(42, 7, 101, 5, "w")));
        assertEquals("alice", g.vertex(42));
        assertEquals(2, g.outEdges(42).size());
        assertEquals(2, g.degree(42, 7));
        assertEquals(1, g.inEdges(100).size());
        assertEquals("w", props(g.edge(42, 7, 101, 5)));
        g.putBatch(List.of(new EdgeOp(42, 7, 100, 0, "x")));
        assertEquals("x", props(g.edge(42, 7, 100)));
        assertEquals(2, g.degree(42, 7));
        g.putBatch(List.of(new EdgeOp(42, 7, 102, 0, null), new RemoveEdgeOp(42, 7, 100, 0)));
        assertNull(g.edge(42, 7, 100));
        assertNotNull(g.edge(42, 7, 102));
        assertEquals(2, g.degree(42, 7));
        g.putBatch(List.of(new VertexOp(43, "bob"), new RemoveEdgeOp(42, 7, 999, 0)));
        assertEquals("bob", g.vertex(43));
        reopen();
        assertEquals("alice", g.vertex(42));
        assertEquals(2, g.outEdges(42).size());
        assertEquals(2, g.degree(42, 7));
    }

    @Test
    void s13ShapeErrorsHaveNoSideEffects() {
        g.putVertex(1, "seed");
        long kc0 = db.status().keyCount();
        assertEquals("bitcask/graph-batch-duplicate-edge", code(() -> g.putBatch(List.of(new VertexOp(42, "x"),
                new EdgeOp(42, 7, 100, 0, null), new EdgeOp(42, 7, 100, 0, null)))));
        assertEquals("bitcask/graph-batch-empty", code(() -> g.putBatch(List.of())));
        assertEquals("bitcask/graph-batch-duplicate-vertex", code(() -> g.putBatch(List.of(new VertexOp(42, "a"), new VertexOp(42, "b")))));
        // 只删不存在的边 ⇒ 批里什么都不剩 ⇒ 空批
        assertEquals("bitcask/graph-batch-empty", code(() -> g.putBatch(List.of(new RemoveEdgeOp(1, 1, 1, 0)))));
        // clj 的 [:edge "x" 1 2]（vid 不是整数）在 Java 里是编译期类型错误；这里换成 null 项
        java.util.List<Graph.GraphOp> withNull = new ArrayList<>();
        withNull.add(null);
        assertEquals("bitcask/graph-batch-op-invalid", code(() -> g.putBatch(withNull)));
        assertEquals(kc0, db.status().keyCount());
        assertNull(g.vertex(42));
    }
}
