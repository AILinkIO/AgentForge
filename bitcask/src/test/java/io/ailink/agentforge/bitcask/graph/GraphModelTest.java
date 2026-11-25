package io.ailink.agentforge.bitcask.graph;

import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.code;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.ok;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.range;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskIndex;
import io.ailink.agentforge.bitcask.Document;
import io.ailink.agentforge.bitcask.Filter;
import io.ailink.agentforge.bitcask.Meta;
import io.ailink.agentforge.bitcask.Options;
import io.ailink.agentforge.bitcask.SearchHit;
import io.ailink.agentforge.bitcask.SearchOptions;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeOp;
import io.ailink.agentforge.bitcask.graph.Graph.VertexOp;

/**
 * 图的数据模型：meta 字符串数组与 contains 过滤、id 分配与高水位、标签与标签索引、顶点 / 边属性编码、与图层既有接口的配合。
 * 移植自 clj-bitcask graph_model_test.clj（期望值对照 bitcask（Erlang）graphdb_model_tests / graphdb_model_api_tests）。
 */
class GraphModelTest {
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

    static VertexSpec spec(List<String> labels, Map<String, ?> props, String text, float[] vec) {
        return new VertexSpec(labels, props, text, vec);
    }

    static final VertexSpec EMPTY_SPEC = new VertexSpec(null, null, null, null);

    static Map<String, Object> m(Object... kvs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) out.put((String) kvs[i], kvs[i + 1]);
        return out;
    }

    // ── meta 字符串数组 ───────────────────────────────────

    @Test
    void metaStringArraysRoundtrip() {
        assertEquals(m("a", 1L, "tags", List.of("x", "y"), "none", List.of(), "u", List.of("中文", "")),
                Meta.decodeMap(Meta.encode(m("a", 1, "tags", List.of("x", "y"), "none", List.of(), "u", List.of("中文", "")))));
        assertEquals(Meta.Type.STRING_ARRAY, Meta.typeOf(List.of("a")));
        assertEquals(List.of("x", "y"), Meta.lookup(Meta.encode(m("tags", List.of("x", "y"))), "tags"));
        assertThrows(IllegalArgumentException.class, () -> Meta.encode(m("bad", List.of(1, 2))));
        // 过滤操作数不能是数组
        assertEquals("bitcask/filter-invalid", code(() -> Filter.validate(Filter.eq("k", List.of("a")))));
        // clj [:contains "k" 1]：Java 的 contains 只收字符串，用底层 Condition 构造非字符串操作数
        assertEquals("bitcask/filter-invalid", code(() -> Filter.validate(new Filter.Condition(Filter.Op.CONTAINS, "k", 1L, null))));
        assertEquals("bitcask/filter-in-empty", code(() -> Filter.validate(Filter.containsAny("k", List.of()))));
    }

    @Test
    void containsFilterOnSearch() {
        try (BitcaskIndex ix = BitcaskIndex.open(tmp.resolve("ix"), Options.defaults().analyzer(Options.Analyzer.WHITESPACE).vectorDimension(2))) {
            ix.putDocument("a", new Document("apple", null, null, Meta.encode(m("tags", List.of("red", "fruit"))), 0));
            ix.putDocument("b", new Document("apple", null, null, Meta.encode(m("tags", List.of("green"))), 0));
            ix.putDocument("c", new Document("apple", null, null, Meta.encode(m("tags", "red")), 0));
            ix.flush();
            java.util.function.Function<Filter, Set<String>> ks = f -> ix.searchText("apple", SearchOptions.defaults().filter(f)).stream()
                    .map(SearchHit::key).collect(Collectors.toSet());
            assertEquals(Set.of("a", "c"), ks.apply(Filter.contains("tags", "red")), "数组任一元素命中；标量当单元素集合");
            assertEquals(Set.of("a", "b"), ks.apply(Filter.containsAny("tags", List.of("green", "fruit"))));
            assertEquals(Set.of("c"), ks.apply(Filter.eq("tags", "red")), "既有运算碰到数组按类型不匹配，标量照常");
            Set<String> both = new HashSet<>(ks.apply(Filter.eq("tags", "red")));
            both.addAll(ks.apply(Filter.notEq("tags", "red")));
            assertEquals(Set.of("a", "b", "c"), both);
        }
    }

    // ── id 分配 ───────────────────────────────────────────

    @Test
    void createVertexAllocatesFromOneAndSkipsUsedIds() {
        assertEquals(1, g.createVertex(EMPTY_SPEC));
        assertEquals(2, g.createVertex(VertexSpec.labels("A")));
        g.putVertex(100, "x");
        assertEquals(101, g.createVertex(null), "指定 id 写入推高水位");
        g.putEdge(500, 1, 7);
        assertEquals(501, g.createVertex(EMPTY_SPEC), "写边的端点同样推高");
        g.putEdges(List.of(EdgeInput.of(900, 1, 901)));
        assertEquals(902, g.createVertex(EMPTY_SPEC));
        g.putBatch(List.of(new VertexOp(1000, "y")));
        assertEquals(1001, g.createVertex(EMPTY_SPEC));
        ok(g);
    }

    @Test
    void firstAllocationInitialisesFromExistingVids() {
        // 顶点键与只有邻接表的顶点都算
        g.putVertex(40, "x");
        g.putEdge(3, 1, 77);
        assertNull(GraphModel.readCounter(db), "水位在第一次自动分配前不建");
        assertEquals(78, g.createVertex(EMPTY_SPEC));
        ok(g);
    }

    @Test
    void firstAllocationInitialisesFromExistingVidsUnsigned() {
        // 大 vid（无符号）
        g.putVertex(-2, "x");
        assertEquals(-1, g.createVertex(EMPTY_SPEC));
        assertEquals("bitcask/graph-vid-exhausted", code(() -> g.createVertex(EMPTY_SPEC)));
        assertTrue(GraphModel.readCounter(db).next().exhausted());
    }

    @Test
    void allocationSkipsIdsTakenBehindItsBack() {
        assertEquals(1, g.createVertex(EMPTY_SPEC));
        // 绕过水位直接写 n2 / a3（模拟推水位与写入之间的空当）
        db.put(GraphKeys.node(2), "squatter");
        db.put(GraphKeys.head(3), new byte[0]);
        assertEquals(4, g.createVertex(EMPTY_SPEC));
        assertEquals("squatter", g.vertex(2), "分配器不覆盖已有数据");
    }

    @Test
    void concurrentAllocationNeverReusesAnId() {
        List<CompletableFuture<List<Long>>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int tt = t;
            fs.add(CompletableFuture.supplyAsync(() -> {
                List<Long> ids = new ArrayList<>();
                for (int i = 0; i < 50; i++) ids.add(g.createVertex(VertexSpec.of(List.of("T"), Map.of("t", tt, "i", i))));
                return ids;
            }, r -> Thread.ofPlatform().start(r)));
        }
        List<Long> ids = fs.stream().flatMap(f -> f.join().stream()).toList();
        assertEquals(400, ids.size());
        assertEquals(400, new HashSet<>(ids).size());
        assertEquals(new HashSet<>(range(1, 401)), new HashSet<>(ids));
        assertEquals(400, g.verticesByLabel("T").size());
        ok(g);
    }

    // ── 标签与属性 ────────────────────────────────────────

    @Test
    void vertexModelRoundtrip() {
        // clj 的 :Person 关键字标签在 Java 里就是字符串 "Person"
        long v = g.createVertex(spec(List.of("Person", "Person", "Admin"),
                m("name", "ann", "age", 31, "score", 1.5, "ok", true, "tags", List.of("a", "b"), "gone", null), "hello", null));
        assertEquals(new VertexInfo(v, List.of("Person", "Admin"),
                        m("name", "ann", "age", 31L, "score", 1.5, "ok", true, "tags", List.of("a", "b"), "text", "hello")),
                g.vertexInfo(v));
        assertEquals("hello", g.vertex(v), "正文仍可按旧接口读");
        g.putVertex(v, spec(null, m("text", "prop wins"), "body", null));
        assertEquals(m("text", "prop wins"), g.vertexInfo(v).properties(), "同名属性优先于正文");
        assertEquals(List.of(), g.verticesByLabel("Person"), "整体替换：标签随之替换，索引同批删");
        g.putVertex(v, VertexSpec.labels("X"));
        g.putVertex(v, "plain again");
        assertEquals(new VertexInfo(v, List.of(), m("text", "plain again")), g.vertexInfo(v));
        assertEquals(List.of(), g.verticesByLabel("X"), "写纯文本也删掉旧标签的索引项");
        ok(g);
    }

    @Test
    void danglingAndMissingVertices() {
        g.putEdge(1, 1, 2);
        assertEquals(new VertexInfo(2, List.of(), Map.of()), g.vertexInfo(2), "只写了边的顶点也是顶点");
        assertNull(g.vertexInfo(99));
        g.setLabels(2, List.of("L"));
        assertEquals(List.of("L"), g.vertexInfo(2).labels(), "只有邻接表的顶点得到一个只有标签的文档");
        assertEquals("bitcask/graph-vertex-not-found", code(() -> g.setLabels(99, List.of("L"))));
        ok(g);
    }

    @Test
    void setAndRemoveLabelsKeepTheRest() {
        db.close();
        db = null;
        try (BitcaskIndex ix = BitcaskIndex.open(tmp.resolve("ix"), Options.defaults().analyzer(Options.Analyzer.WHITESPACE).vectorDimension(2))) {
            Graph gx = Graph.open(ix);
            long v = gx.createVertex(spec(List.of("A"), m("k", 1), "graph text", new float[]{1.0f, 0.0f}));
            gx.setLabels(v, List.of("B", "A", "C"));
            assertEquals(List.of("A", "B", "C"), gx.vertexInfo(v).labels());
            gx.removeLabels(v, List.of("A", "nope"));
            assertEquals(new VertexInfo(v, List.of("B", "C"), m("k", 1L, "text", "graph text")), gx.vertexInfo(v));
            ix.flush();
            assertEquals(List.of(GraphKeys.node(v)), ix.searchVector(new float[]{1.0f, 0.0f}, SearchOptions.k(5).filter(Filter.contains("_labels", "B")))
                    .stream().map(SearchHit::key).toList(), "向量保留，标签可作 meta 过滤");
            assertEquals(List.of(), ix.searchVector(new float[]{1.0f, 0.0f}, SearchOptions.k(5).filter(Filter.contains("_labels", "A"))));
            assertEquals(List.of(GraphKeys.node(v)), ix.searchText("graph").stream().map(SearchHit::key).toList(), "正文仍进全文");
            ok(gx);
        }
    }

    @Test
    void verticesByLabelPages() {
        for (long v : List.of(5L, 1L, 9L, 3L, 7L)) g.putVertex(v, VertexSpec.labels("P"));
        g.putVertex(4, VertexSpec.labels("Q"));
        assertEquals(List.of(1L, 3L, 5L, 7L, 9L), g.verticesByLabel("P"));
        assertEquals(List.of(1L, 3L), g.verticesByLabel("P", 2, null));
        assertEquals(List.of(5L, 7L), g.verticesByLabel("P", 2, 3L));
        assertEquals(List.of(), g.verticesByLabel("P", -1, 9L));
        assertEquals(List.of(), g.verticesByLabel("P", 0, null));
        assertEquals(List.of(), g.verticesByLabel("none"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void propertyAndLabelValidation() {
        assertEquals("bitcask/graph-property-reserved", code(() -> g.createVertex(VertexSpec.of(null, m("_x", 1)))));
        assertEquals("bitcask/graph-property-type", code(() -> g.createVertex(VertexSpec.of(null, m("x", m("nested", 1))))));
        assertEquals("bitcask/graph-property-type", code(() -> g.createVertex(VertexSpec.of(null, m("x", List.of(1, "a"))))));
        assertEquals("bitcask/graph-property-type", code(() -> g.createVertex(VertexSpec.of(null, m("x", new BigInteger("1000000000000000000000000000000"))))));
        assertEquals("bitcask/graph-label-invalid", code(() -> g.createVertex(VertexSpec.labels(""))));
        assertEquals("bitcask/graph-label-invalid", code(() -> g.createVertex(VertexSpec.of((List) List.of(1), null))));
        // clj (put-vertex! db 1 42)：Java 的 putVertex 只收 String / VertexSpec，走组合事务的 VertexOp（spec 是 Object）
        assertEquals("bitcask/graph-vertex-spec-invalid", code(() -> g.putBatch(List.of(new VertexOp(1, 42)))));
        assertNull(GraphModel.readCounter(db), "校验失败什么都不写");
    }

    @Test
    void typedListPropertiesGraphApi() {
        LocalDate day = LocalDate.of(2020, 1, 1);
        long v = g.createVertex(VertexSpec.of(null, m("xs", List.of(1, 2), "fs", List.of(0.5, 1), "bs", List.of(true), "ds", List.of(day), "ss", List.of("a"))));
        assertEquals(m("xs", List.of(1L, 2L), "fs", List.of(0.5, 1.0), "bs", List.of(true), "ds", List.of(day), "ss", List.of("a")),
                g.vertexInfo(v).properties());
        assertEquals("bitcask/graph-property-type", code(() -> g.createVertex(VertexSpec.of(null, m("x", List.of(day, LocalTime.of(1, 0)))))));
    }

    @Test
    void edgePropertiesEncoding() {
        g.putEdge(1, 1, 2, 0, m("w", 0.5, "tag", "x"));
        g.putEdge(1, 1, 3, 0, "legacy text");
        g.putEdge(1, 1, 4, 0, new byte[]{(byte) 0xff, 0, 1});
        g.putEdge(1, 1, 5);
        g.putEdge(1, 1, 6, 0, Map.of());
        assertEquals(m("w", 0.5, "tag", "x"), g.edge(1, 1, 2).properties());
        assertEquals(m("_raw", "legacy text"), Graph.edgeProperties(g.edge(1, 1, 3).props()));
        assertArrayEquals(new byte[]{-1, 0, 1}, (byte[]) g.edge(1, 1, 4).properties().get("_raw"));
        assertEquals(Map.of(), g.edge(1, 1, 5).properties());
        assertEquals(Map.of(), g.edge(1, 1, 6).properties());
        assertEquals("bitcask/graph-property-reserved", code(() -> g.putEdge(1, 1, 7, 0, m("_raw", "x"))));
        ok(g);
    }

    @Test
    void removeVertexDropsLabels() {
        long a = g.createVertex(VertexSpec.labels("A", "B"));
        long b = g.createVertex(VertexSpec.labels("A"));
        g.putEdge(a, 1, b);
        g.removeVertex(a);
        assertEquals(List.of(b), g.verticesByLabel("A"));
        assertEquals(List.of(), g.verticesByLabel("B"));
        assertNull(g.vertexInfo(a));
        assertEquals(3, g.createVertex(EMPTY_SPEC), "删掉的 id 不再发出");
        ok(g);
    }

    @Test
    void putBatchWithModelVertices() {
        g.putVertex(1, VertexSpec.labels("Old"));
        g.putBatch(List.of(new VertexOp(1, VertexSpec.of(List.of("New"), m("x", 1))), new VertexOp(2, "text"),
                new EdgeOp(1, 1, 2, 0, m("w", 1))));
        assertEquals(new VertexInfo(1, List.of("New"), m("x", 1L)), g.vertexInfo(1));
        assertEquals(List.of(), g.verticesByLabel("Old"));
        assertEquals(m("w", 1L), g.edge(1, 1, 2).properties());
        ok(g);
    }

    @Test
    void invariantsCatchLabelIndexAndCounterDrift() {
        long v = g.createVertex(VertexSpec.labels("A"));
        ok(g);
        db.delete(GraphKeys.labelKey("A", v));
        Graph.InvariantReport r = g.checkInvariants();
        assertFalse(r.ok());
        assertEquals("label-index-mismatch", r.problems().getFirst().getFirst());
        db.put(GraphKeys.labelKey("A", v), new byte[0]);
        db.put(GraphKeys.COUNTER, GraphModel.encodeCounter(GraphModel.Watermark.of(1)));
        assertTrue(g.checkInvariants().problems().stream().anyMatch(p -> "vid-counter-low".equals(p.getFirst())));
    }

    @Test
    void describeKeyKnowsModelFamilies() {
        assertEquals(m("family", "label", "label", "Person", "vid", 42L), Graph.describeKey(GraphKeys.labelKey("Person", 42)));
        assertEquals(m("family", "label", "label", "中文", "vid", 1L), Graph.describeKey(GraphKeys.labelKey("中文", 1)));
        assertEquals(m("family", "vid-counter"), Graph.describeKey(GraphKeys.COUNTER));
        assertEquals(m("family", "node", "vid", 7L), Graph.describeKey(u(GraphKeys.node(7))));
    }
}
