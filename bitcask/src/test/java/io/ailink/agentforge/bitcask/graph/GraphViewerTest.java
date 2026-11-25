package io.ailink.agentforge.bitcask.graph;

import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.b;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.code;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.pairs;
import static io.ailink.agentforge.bitcask.graph.GraphTestSupport.range;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.graph.Graph.Direction;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.Graph.GroupSummary;
import io.ailink.agentforge.bitcask.graph.Graph.Subgraph;
import io.ailink.agentforge.bitcask.graph.Graph.SubgraphOptions;
import io.ailink.agentforge.bitcask.graph.Graph.SubgraphVertex;
import io.ailink.agentforge.bitcask.graph.Graph.VertexSummary;

/** 只读检视接口与有上限局部子图的稳定返回形状。移植自 clj-bitcask graph_viewer_test.clj（2026-11-19 graph-read-api-for-viewers）。 */
class GraphViewerTest {
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

    /** 关掉旧句柄、开新的（证明检视 API 在重开后可用）。 */
    void reopen() {
        db.close();
        db = Bitcask.open(d);
        g = Graph.open(db);
    }

    void seed() {
        g.putVertex(1, "alice");
        g.putVertex(2, "bob");
        g.putVertex(3, "carol");
        g.putVertex(4, "dave");
        g.putEdge(1, 7, 2);
        g.putEdge(2, 7, 3);
        g.putEdge(3, 7, 4);
        g.putEdge(1, 7, 3);
        g.putEdge(1, 8, 3, 0, "weight");
    }

    static Map<String, Object> m(Object... kvs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) out.put((String) kvs[i], kvs[i + 1]);
        return out;
    }

    static List<Long> vids(List<SubgraphVertex> vs) {
        return vs.stream().map(SubgraphVertex::vid).toList();
    }

    static final List<GroupSummary> SEED_GROUPS = List.of(new GroupSummary(Direction.OUT, 7, 2, false, 0, null),
            new GroupSummary(Direction.OUT, 8, 1, false, 0, null));

    @Test
    void describeKeyAndValue() {
        seed();
        // key families
        assertEquals(m("family", "node", "vid", 1L), Graph.describeKey(GraphKeys.node(1)));
        assertEquals(m("family", "head", "vid", 1L), Graph.describeKey(GraphKeys.head(1)));
        assertEquals(m("family", "chunk", "vid", 1L, "dir", Direction.OUT, "etype", 7L, "lb", List.of(2L, 9L)),
                Graph.describeKey(GraphKeys.chunkKey(1, 0, 7, 2, 9)));
        assertEquals(m("family", "props", "src", 1L, "etype", 8L, "dst", 3L, "rank", 0L), Graph.describeKey(GraphKeys.propsKey(1, 8, 3, 0)));
        assertEquals(m("family", "meta"), Graph.describeKey(GraphKeys.GRAPH_META));
        assertEquals(m("family", "etype-name", "etype", 7L), Graph.describeKey(GraphKeys.etypeName(7)));
        assertEquals(m("family", "etype-map", "name", "关注"), Graph.describeKey(GraphKeys.etypeByName("关注")));
        assertNull(Graph.describeKey("business-key"));
        assertNull(Graph.describeKey((String) null));

        // head and simple values
        assertEquals(m("groups", SEED_GROUPS), Graph.describeValue(GraphKeys.ascii(GraphKeys.head(1)), db.get(GraphKeys.head(1))));
        byte[] chunk1 = b(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(m("count", 1), Graph.describeValue(GraphKeys.ascii(GraphKeys.chunkKey(1, 0, 7, 2, 9)), chunk1));
        // 新库是布局 2（keelson D-51）：g 里多了按条数切块、块数安全阀、目录外置的参数
        assertEquals(m("layout", 2, "inline-max", 4096L, "chunk-max", 65536L, "props-max", 128L,
                        "chunk-entries", 512L, "chunk-count-max", 65536L, "dir-inline-max", 16L, "dir-node-max", 256L),
                Graph.describeValue(GraphKeys.ascii(GraphKeys.GRAPH_META), db.get(GraphKeys.GRAPH_META)));
        long id = g.registerEtype("weight");
        String k = GraphKeys.etypeName(id);
        assertEquals(m("name", "weight"), Graph.describeValue(GraphKeys.ascii(k), db.get(k)));
        assertInstanceOf(byte[].class, Graph.describeValue(GraphKeys.nodeBytes(1), db.get(GraphKeys.node(1))).get("value"));

        // malformed values do not throw
        assertEquals(m("malformed", "head"), Graph.describeValue(GraphKeys.ascii(GraphKeys.head(1)), b(1, 0)));
        assertEquals(m("malformed", "chunk"), Graph.describeValue(GraphKeys.ascii(GraphKeys.chunkKey(1, 0, 7, 2, 9)), null));
        assertEquals(m("malformed", "meta"), Graph.describeValue(GraphKeys.ascii(GraphKeys.GRAPH_META), b(2, 0, 0, 0)));
        assertNull(Graph.describeValue(GraphKeys.ascii("business-key"), b(0)));

        // stable summary on a reopen
        reopen();
        assertEquals(new VertexSummary(1, SEED_GROUPS), g.vertexSummary(1));
        assertEquals(List.of(), g.vertexSummary(99).groups());
    }

    @Test
    void vertexSummaryDescribesChunkedGroups() {
        g.init(GraphConfig.of(256, 256, 128));
        g.putEdges(range(1, 101).stream().map(x -> EdgeInput.of(1, 7, x)).toList());
        VertexSummary summary = g.vertexSummary(1);
        GroupSummary group = summary.groups().stream().filter(x -> x.etype() == 7 && x.chunked()).findFirst().orElseThrow();
        assertTrue(group.chunks() > 0);
        assertEquals(100, group.count());
        assertEquals(new GroupSummary(Direction.OUT, 7, 100, true, group.chunks(), null), group);
        byte[] lo = GraphKeys.ascii(GraphKeys.head(1));
        Entry e = db.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo))).stream()
                .filter(x -> GraphKeys.parseChunk(x.key()) != null).findFirst().orElseThrow();
        assertTrue((Integer) Graph.describeValue(e.key(), e.value()).get("count") > 0);
    }

    @Test
    void subgraphIsBoundedAndStable() {
        seed();
        // one outbound hop
        Subgraph r = g.subgraph(1, new SubgraphOptions(1, Direction.OUT, 7L, 10, 10));
        assertEquals(List.of(1L, 2L, 3L), vids(r.vertices()));
        assertEquals(List.of(0, 1, 1), r.vertices().stream().map(SubgraphVertex::depth).toList());
        assertEquals(List.of("1->2", "1->3"), pairs(r.edges()));
        assertTrue(r.edges().stream().allMatch(e -> e.props().length == 0));   // 子图的边不带属性
        assertFalse(r.truncated());

        // two hops
        r = g.subgraph(1, new SubgraphOptions(2, Direction.OUT, 7L, 10, 10));
        assertEquals(List.of(1L, 2L, 3L, 4L), vids(r.vertices()));
        assertEquals(List.of("1->2", "1->3", "2->3", "3->4"), pairs(r.edges()));
        assertFalse(r.truncated());

        // max-vertices truncates and drops edges to excluded vertices
        r = g.subgraph(1, new SubgraphOptions(1, Direction.OUT, 7L, 10, 2));
        assertEquals(List.of(1L, 2L), vids(r.vertices()));
        assertEquals(List.of("1->2"), pairs(r.edges()));
        assertTrue(r.truncated());

        // per-vertex edge limit marks truncation
        r = g.subgraph(1, new SubgraphOptions(2, Direction.OUT, 7L, 1, 10));
        assertEquals(List.of(1L, 2L, 3L), vids(r.vertices()));
        assertEquals(List.of("1->2", "2->3"), pairs(r.edges()));
        assertTrue(r.truncated());

        // inbound traversal keeps edge direction
        r = g.subgraph(3, new SubgraphOptions(1, Direction.IN, 7L, 10, 10));
        assertEquals(List.of(3L, 1L, 2L), vids(r.vertices()));
        assertEquals(List.of("1->3", "2->3"), pairs(r.edges()));
        assertFalse(r.truncated());

        // works on a reopen
        reopen();
        SubgraphOptions dflt = SubgraphOptions.DEFAULT;
        assertEquals(List.of("1->2", "1->3"),
                pairs(g.subgraph(1, new SubgraphOptions(dflt.hops(), dflt.direction(), 7L, dflt.perVertex(), dflt.maxVertices())).edges()));
        assertEquals("bitcask/graph-config-invalid",
                code(() -> g.subgraph(1, new SubgraphOptions(dflt.hops(), dflt.direction(), null, dflt.perVertex(), 0))));
        assertNotNull(g.subgraph(1));
    }
}
