package io.ailink.agentforge.bitcask.graph;

import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.range;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.s;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.graph.Adjacency.Group;
import io.ailink.agentforge.bitcask.graph.Adjacency.LB;
import io.ailink.agentforge.bitcask.graph.Graph.BatchOptions;
import io.ailink.agentforge.bitcask.graph.Graph.Direction;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;

/**
 * 图存储布局 2（keelson D-51，designs/graph-layout-2.md）：块按条数切、块目录超过阈值外置成 d 家族的 B+ 树、每组块数安全阀、
 * 布局 1 照旧、布局 1 → 2 的升级。用很小的参数（每块 4 条、块数 &gt; 4 外置、节点最多 4 项）逼出多层目录树，
 * 随机加边 / 删边，每一步与内存里的模型对照，并跑 checkInvariants。移植自 clj-bitcask graph_layout2_test.clj。
 */
class GraphLayout2Test {
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

    static GraphConfig.Options tiny() {
        return GraphConfig.options().adjInlineMax(64).adjChunkEntries(4).adjChunkCountMax(0).adjDirInlineMax(4).adjDirNodeMax(4);
    }

    static List<String> familyKeys(Bitcask db, String fam) {
        byte[] lo = GraphKeys.familyBytes(fam);
        return db.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo))).stream()
                .map(e -> new String(e.key(), StandardCharsets.US_ASCII)).toList();
    }

    static Group groupOf(Bitcask db, long vid, int dir) {
        return Adjacency.headGroups(db, vid, Adjacency.wantFn(null, null)).stream().filter(x -> x.dir() == dir).findFirst().orElse(null);
    }

    static List<Long> sorted(Set<Long> xs) {
        List<Long> l = new ArrayList<>(xs);
        l.sort(Long::compareUnsigned);
        return l;
    }

    /** 模型：in = 入边的 src 集合、out = 出边的 dst 集合（etype 1、rank 0）。 */
    record Model(Set<Long> in, Set<Long> out) {
        Model() {
            this(new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }

    static void checkAgainst(Graph g, long hub, Model model, Random r) {
        List<Long> ins = sorted(model.in), outs = sorted(model.out);
        assertEquals(ins, g.inEdges(hub).stream().map(Edge::src).toList(), "入边与模型一致（有序）");
        assertEquals(outs, g.outEdges(hub).stream().map(Edge::dst).toList(), "出边与模型一致（有序）");
        assertEquals(ins.size(), g.inDegree(hub));
        assertEquals(outs.size(), g.degree(hub));
        assertEquals(ins.subList(0, Math.min(3, ins.size())),
                g.inEdges(hub, new Graph.AdjQuery(null, 3, false)).stream().map(Edge::src).toList(), "取前 N 条");
        List<Long> both = new ArrayList<>(new LinkedHashSet<Long>() {{
            addAll(outs);
            addAll(ins);
        }});
        assertEquals(both, g.neighbors(hub, Direction.BOTH));
        List<Long> sample = new ArrayList<>(model.in);
        Collections.shuffle(sample, r);
        for (long x : sample.subList(0, Math.min(20, sample.size())))
            assertNotNull(Adjacency.lookup(g.db(), hub, 1, 1, x, 0), "点查已有的入边");
        Graph.InvariantReport inv = g.checkInvariants();
        assertTrue(inv.ok(), String.valueOf(inv.problems()));
    }

    @Test
    void configEncodesBothLayouts() {
        for (GraphConfig cfg : List.of(GraphConfig.DEFAULT, GraphConfig.LAYOUT1, new GraphConfig(2, 4096, 65536, 128, 7, 0, 3, 9)))
            assertEquals(cfg, GraphConfig.decode(cfg.encode()));
        assertEquals(23, GraphConfig.DEFAULT.encode().length);
        assertEquals(11, GraphConfig.LAYOUT1.encode().length);
        assertNull(GraphConfig.decode(new byte[]{3, 0, 0}), "认不得的布局 ⇒ null");
        List<LB> lbs = List.of(new LB(0, 0), new LB(5, 1), new LB(9, 0));
        Adjacency.Node nd = Adjacency.decodeNode(Adjacency.encodeNode(2, lbs));
        assertEquals(2, nd.level());
        assertEquals(lbs, nd.lbs());
        assertNull(Adjacency.decodeNode(Adjacency.encodeNode(1, List.of(new LB(5, 0), new LB(3, 0)))), "下界不升序 ⇒ 畸形");
        String k = GraphKeys.dirNodeKey(42, 1, 7, 3, 9, 2);
        assertEquals(65, k.length());
        assertEquals(new GraphKeys.DirNodeKey(42, 1, 7, 3, 9, 2), GraphKeys.parseDirNode(k));
        assertNull(GraphKeys.parseChunk(k));
        assertEquals("dir-node", Graph.describeKey(k).get("family"));
        // describe-value：目录节点，以及 1.6.0 漏掉的 l / c / r / m 家族
        assertEquals(Map.of("level", 2, "count", 3), Graph.describeValue(GraphKeys.ascii(k), Adjacency.encodeNode(2, lbs)));
        assertEquals(Map.of("malformed", "dir-node"), Graph.describeValue(GraphKeys.ascii(k), new byte[]{1, 1, 0, 0}));
        assertEquals(Map.of("empty", true), Graph.describeValue(GraphKeys.labelKey("L", 3), new byte[0]));
        assertEquals(Map.of("next-vid", 5L), Graph.describeValue(GraphKeys.ascii(GraphKeys.COUNTER), new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 5}));
        assertEquals(Map.of("exhausted", true), Graph.describeValue(GraphKeys.ascii(GraphKeys.COUNTER), new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(Map.of("malformed", "vid-counter"), Graph.describeValue(GraphKeys.ascii(GraphKeys.COUNTER), new byte[]{0}));
        assertEquals(Map.of("rank-floor", 7L), Graph.describeValue(GraphKeys.ascii(GraphKeys.rankFloor(1, 2, 3)), GraphKeys.u64Bytes(7)));
        assertEquals(Map.of("version", 9L), Graph.describeValue(GraphKeys.mergeGuard("L"), GraphKeys.u64Bytes(9)));
    }

    @Test
    void newLibraryIsLayout2() {
        g.putEdge(1, 1, 2);
        assertEquals(GraphConfig.DEFAULT, GraphConfig.decode(db.get(GraphKeys.GRAPH_META)));
        assertEquals(2, g.config().layout());
    }

    @Test
    void randomHubMatchesModel() {
        g.init(tiny());
        long hub = 1000000;
        Random r = new Random(7);
        Model model = new Model();
        // 加到多层目录
        for (int i = 0; i < 600; i++) {
            long x = 1 + r.nextInt(400);
            if (r.nextDouble() < 0.85) {
                g.putEdge(x, 1, hub);
                model.in.add(x);
            } else {
                g.putEdge(hub, 1, x);
                model.out.add(x);
            }
        }
        Group in = groupOf(db, hub, 1);
        assertNotNull(in.xdir(), "入边组的目录已外置");
        assertTrue(in.xdir().levels() >= 3, "至少三层：" + in.xdir());
        checkAgainst(g, hub, model, r);
        // 随机删到转回内联目录、再转回内联组
        List<Long> xs = new ArrayList<>(model.in);
        Collections.shuffle(xs, r);
        for (long x : xs) {
            if (r.nextDouble() < 0.97) {
                g.removeEdge(x, 1, hub);
                model.in.remove(x);
            }
        }
        checkAgainst(g, hub, model, r);
        Group in2 = groupOf(db, hub, 1);
        long n = in2 == null ? 0 : in2.xdir() != null ? in2.xdir().n() : in2.chunks() != null ? in2.chunks().size() : 0;
        // 空块都删了（下界 (0,0) 的块恒保留）；半空的相邻块、稀疏的目录节点不合并（同布局 1，设计稿 Q6）
        assertTrue(n <= model.in.size() + 1, "块数 " + n + "，剩 " + model.in.size() + " 条");
        for (long x : List.copyOf(model.in)) g.removeEdge(x, 1, hub);
        model.in.clear();
        checkAgainst(g, hub, model, r);
        assertNull(groupOf(db, hub, 1), "入边删光 ⇒ 组没了");
        assertTrue(familyKeys(db, "d").stream().noneMatch(k -> GraphKeys.parseDirNode(k).dir() == 1), "入边组没有残留的目录节点");
        // 批量加回来、混合 upsert
        List<Long> back = range(2000, 2600);
        g.putEdges(back.stream().map(x -> EdgeInput.of(x, 1, hub)).toList());
        model.in.addAll(back);
        g.putEdges(back.subList(0, 50).stream().map(x -> EdgeInput.of(x, 1, hub)).toList(), new BatchOptions(true, 3));
        checkAgainst(g, hub, model, r);
        // 删顶点：块与目录节点一起清掉
        g.removeVertex(hub, 37);
        assertEquals(0, g.inDegree(hub));
        assertTrue(familyKeys(db, "d").isEmpty(), "没有残留的目录节点");
        assertTrue(g.checkInvariants().ok());
    }

    @Test
    void rankAndPropsInTree() {
        g.init(tiny().propsInlineMax(4));
        long hub = 7;
        for (int i = 0; i < 120; i++)
            g.putEdge(hub, 1, 1 + (i % 40), i / 40, "p" + i + "-" + "x".repeat(i % 7));
        assertNotNull(groupOf(db, hub, 0).xdir());
        assertEquals(120, g.degree(hub));
        assertEquals(120, g.outEdges(hub).size());
        assertEquals("p41-xxxxxx", s(g.edge(hub, 1, 2, 1).props()), "外置属性（p 家族）照常读回");
        for (int i = 0; i < 60; i++) g.removeEdge(hub, 1, 1 + (i % 40), i / 40);
        assertEquals(60, g.degree(hub));
        assertNull(g.edge(hub, 1, 2, 0));
        assertTrue(g.checkInvariants().ok());
    }

    @Test
    void safetyValveCapsChunkCount() {
        g.init(tiny().adjChunkCountMax(8).adjDirInlineMax(64));
        long hub = 9;
        g.putEdges(range(1, 2001).stream().map(x -> EdgeInput.of(x, 1, hub)).toList());
        for (int i = 0; i < 200; i++) g.putEdge(5000 + 7L * i, 1, hub);
        Group in = groupOf(db, hub, 1);
        long n = in.chunks() != null ? in.chunks().size() : in.xdir().n();
        assertTrue(n <= 40, "块数被安全阀压住（每块条数随组放大）：" + n);
        assertEquals(2200, g.inDegree(hub));
        assertTrue(g.checkInvariants().ok());
    }

    @Test
    void layout1UnchangedAndUpgrade() {
        g.init(GraphConfig.options().layout(1).adjInlineMax(64).adjChunkMax(64));
        long hub = 5;
        for (int i = 0; i < 300; i++) g.putEdge(i + 1, 1, hub);
        assertEquals(1, g.config().layout());
        assertNotNull(groupOf(db, hub, 1).chunks(), "布局 1：目录总在头里");
        assertTrue(familyKeys(db, "d").isEmpty());
        // 升级到布局 2：只改 g，之后写到的组再转
        GraphConfig cfg = g.upgradeLayout(GraphConfig.options().adjChunkEntries(4).adjDirInlineMax(4).adjDirNodeMax(4));
        assertEquals(2, cfg.layout());
        assertEquals(64, cfg.inlineMax(), "inline-max 沿用库里的");
        assertNotNull(groupOf(db, hub, 1).chunks(), "没写之前不动");
        assertEquals(300, g.inEdges(hub).size(), "读不依赖已经转完");
        g.putEdge(1000, 1, hub);
        assertNotNull(groupOf(db, hub, 1).xdir(), "写到之后目录外置");
        for (int i = 0; i < 200; i++) g.putEdge(2000 + i, 1, hub);
        assertEquals(501, g.inDegree(hub));
        List<Long> want = new ArrayList<>(range(1, 301));
        want.add(1000L);
        want.addAll(range(2000, 2200));
        want.sort(Long::compareUnsigned);
        assertEquals(want, g.inEdges(hub).stream().map(Edge::src).toList());
        Graph.InvariantReport inv = g.checkInvariants();
        assertTrue(inv.ok(), String.valueOf(inv.problems()));
        // 已是布局 2：不写、回现有参数
        assertEquals(cfg, g.upgradeLayout());
    }

    @Test
    void staleDirectoryConflictsInsteadOfWriting() {
        g.init(tiny());
        long hub = 3;
        g.putEdges(range(1, 200).stream().map(x -> EdgeInput.of(x, 1, hub)).toList());
        Group in = groupOf(db, hub, 1);
        String root = GraphKeys.dirNodeKey(hub, 1, 1, in.xdir().levels(), 0, 0);
        Adjacency.Session s0 = new Adjacency.Session(db, g.config(), List.of());
        // 读头之后、走树之前根节点没了（模拟别人改过）：会话标过期，提交当冲突
        db.delete(root);
        s0.putEdge(999, 1, hub, 0, new byte[0]);
        assertTrue(s0.stale());
        assertEquals(Adjacency.Commit.CONFLICT, s0.commit(List.of()));
    }

    @Test
    void randomInterleavedOps() {
        // 加 / 删随机交替（同一批顶点反复进出），多个种子；每 300 步对照模型、跑不变式
        for (int seed = 1; seed < 6; seed++) {
            try (Bitcask x = Bitcask.open(tmp.resolve("seed" + seed))) {
                Graph gx = Graph.open(x);
                gx.init(tiny());
                long hub = 7777;
                Random r = new Random(seed);
                Model model = new Model();
                for (int step = 0; step < 1500; step++) {
                    long v = 1 + r.nextInt(300);
                    boolean in = r.nextDouble() < 0.8;
                    boolean add = r.nextDouble() < 0.6;
                    if (in && add) gx.putEdge(v, 1, hub);
                    else if (in) gx.removeEdge(v, 1, hub);
                    else if (add) gx.putEdge(hub, 1, v);
                    else gx.removeEdge(hub, 1, v);
                    Set<Long> side = in ? model.in : model.out;
                    if (add) side.add(v);
                    else side.remove(v);
                    if ((step + 1) % 300 == 0) checkAgainst(gx, hub, model, r);
                }
            }
        }
    }

    /** 自环、平行边（多个 rank）、删边混在一棵多层树里：不变式与逐条读回。 */
    @Test
    void selfLoopsAndParallelEdgesInTree() {
        g.init(tiny());
        long hub = 11;
        TreeSet<String> want = new TreeSet<>();
        for (int i = 0; i < 160; i++) {
            long other = i % 5 == 0 ? hub : 100 + (i % 50);
            long rank = i / 50;
            g.putEdge(other, 1, hub, rank, null);
            want.add(other + "/" + rank);
        }
        assertNotNull(groupOf(db, hub, 1).xdir());
        for (int i = 0; i < 160; i += 3) {
            long other = i % 5 == 0 ? hub : 100 + (i % 50);
            long rank = i / 50;
            g.removeEdge(other, 1, hub, rank);
            want.remove(other + "/" + rank);
        }
        TreeSet<String> got = new TreeSet<>();
        for (Edge e : g.inEdges(hub)) got.add(e.src() + "/" + e.rank());
        assertEquals(want, got);
        assertEquals(want.size(), g.inDegree(hub));
        Graph.InvariantReport inv = g.checkInvariants();
        assertTrue(inv.ok(), String.valueOf(inv.problems()));
        // 摘要带目录层数
        Graph.GroupSummary sum = g.vertexSummary(hub).groups().stream().filter(x -> x.dir() == Direction.IN).findFirst().orElseThrow();
        assertTrue(sum.chunked());
        if (groupOf(db, hub, 1).xdir() != null) assertNotNull(sum.dirLevels());
        // 孤儿目录节点被 checkInvariants 找出来
        db.put(GraphKeys.dirNodeKey(hub, 1, 1, 1, 123456, 0), Adjacency.encodeNode(1, List.of(new LB(123456, 0))));
        Graph.InvariantReport bad = g.checkInvariants();
        assertTrue(!bad.ok() && bad.problems().stream().anyMatch(p -> p.getFirst().equals("orphan-dir-node")), String.valueOf(bad.problems()));
    }
}
