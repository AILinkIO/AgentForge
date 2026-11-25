package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.INFINITE;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.L;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.M;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.clj;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.col;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.err;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.message;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.okInv;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.ops;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.pr;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.q;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.rows;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.sorted;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.stage;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.vi;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskIndex;
import io.ailink.agentforge.bitcask.Options;
import io.ailink.agentforge.bitcask.SearchHit;
import io.ailink.agentforge.bitcask.SearchOptions;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.GraphKeys;
import io.ailink.agentforge.bitcask.graph.VertexSpec;

/**
 * Cypher 子集 Q-3（写语句）与 Q-4（全文 @@、向量 &lt;=&gt;、score()）。移植自 clj-bitcask cypher_write_test.clj
 * （对照 bitcask（Erlang）bitcask_cypher_write_tests）：写的结果用图层 API 核对；检索结果与直接调引擎接口对照。
 */
class CypherWriteTest {
    @TempDir
    Path d;

    @FunctionalInterface
    interface Body {
        void run(Bitcask db, Graph g);
    }

    /** clj with-graph：opts 为 null ⇒ KV 库，否则检索库。 */
    void withGraph(Options opts, Body body) {
        assumeNative();
        try (Bitcask db = opts == null ? Bitcask.open(d) : Bitcask.openIndex(d, opts)) {
            body.run(db, Graph.open(db));
        }
    }

    static Options whitespace() {
        return Options.defaults().analyzer(Options.Analyzer.WHITESPACE);
    }

    /** clj search-opts：{:analyzer :whitespace :vector-dimension 3}。 */
    static Options searchOpts() {
        return whitespace().vectorDimension(3);
    }

    static void concurrently(int threads, Runnable each) {
        List<CompletableFuture<Void>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) fs.add(CompletableFuture.runAsync(each));
        for (CompletableFuture<Void> f : fs) f.join();
    }

    // ── Q-3：写 ───────────────────────────────────────────

    @Test
    void createNodesAndEdges() {
        withGraph(null, (db, g) -> {
            CypherResult r = q(db, "CREATE (a:Person:Admin {name: 'ann', age: 31})-[:KNOWS {since: 2020}]->(b:Person {name: 'bob'}) RETURN id(a), id(b)");
            long a = (Long) r.rows().getFirst().get(0), b = (Long) r.rows().getFirst().get(1);
            assertEquals(M("nodes-created", 2, "relationships-created", 1, "labels-added", 3, "properties-set", 4), r.stats());
            assertEquals(new Node(a, List.of("Person", "Admin"), M("name", "ann", "age", 31)), vi(g, a));
            Long k = g.etypeId("KNOWS");
            assertNotNull(k, "关系类型自动注册");
            assertEquals(M("since", 2020), Graph.edgeProperties(g.edge(a, k, b).props()));
            assertEquals(List.of(a, b), g.verticesByLabel("Person"));
            q(db, "MATCH (a {name: 'ann'}), (b {name: 'bob'}) CREATE (a)<-[:KNOWS]-(b), (a)-[:KNOWS]->(b)");
            List<Object> ranks = new ArrayList<>();
            for (Object id : col(db, "MATCH ({name: 'ann'})-[r:KNOWS]->({name: 'bob'}) RETURN id(r)")) ranks.add(((List<?>) id).get(3));
            assertEquals(L(0, 1), ranks, "平行边取下一个 rank");
            assertEquals(L("ann"), col(db, "MATCH ({name: 'bob'})-[:KNOWS]->(x) RETURN x.name"));
            CypherResult t = q(db, "CREATE (:Thing)");
            assertEquals(List.of(), t.columns());
            assertEquals(List.of(), t.rows());
            okInv(g);
        });
    }

    @Test
    void sharedIdSpace() {
        withGraph(null, (db, g) -> {
            g.putVertex(100, VertexSpec.labels("X"));
            assertEquals(101L, g.createVertex(VertexSpec.of(null, null)));
            g.putVertex(200, "v");
            assertEquals(L(L(201)), rows(db, "CREATE (n:Y) RETURN id(n)"));
            assertEquals(202L, g.createVertex(VertexSpec.of(null, null)));
            assertEquals(L(L(203), L(204), L(205)), rows(db, "UNWIND $xs AS x CREATE (n:Z {v: x}) RETURN id(n) ORDER BY id(n)", M("xs", L(1, 2, 3))));
            assertEquals(L(1, 2, 3), col(db, "MATCH (n:Z) RETURN n.v ORDER BY n.v"));
            okInv(g);
        });
    }

    @Test
    void setRemove() {
        withGraph(whitespace().vectorDimension(3).vectorMetric(Options.VectorMetric.L2), (db, g) -> {
            long v = (Long) rows(db, "CREATE (n:P {name: 'x', a: 1, b: 2}) RETURN id(n)").getFirst().getFirst();
            assertEquals(M("properties-set", 3, "labels-added", 1), q(db, "MATCH (n:P) SET n.a = n.a + 10, n.c = 'new', n.b = null, n:Q:P").stats());
            assertEquals(new Node(v, List.of("P", "Q"), M("name", "x", "a", 11, "c", "new")), vi(g, v));
            q(db, "MATCH (n:P) SET n += {d: true, a: null}");
            Map<String, Object> p = g.vertexInfo(v).properties();
            assertEquals(true, p.get("d"));
            assertEquals("new", p.get("c"));
            assertFalse(p.containsKey("a"));
            q(db, "MATCH (n:P) SET n = {only: 1}");
            assertEquals(new Node(v, List.of("P", "Q"), M("only", 1)), vi(g, v));
            q(db, "MATCH (n:P) SET n.text = 'hello world', n.embedding = [1, 2, 3]");
            assertEquals(L(L("hello world", L(1.0, 2.0, 3.0))), rows(db, "MATCH (n:P) RETURN n.text, n.embedding"));
            q(db, "MATCH (n:P) REMOVE n.only, n.embedding, n:Q");
            assertEquals(L(L(null, null, L("P"))), rows(db, "MATCH (n:P) RETURN n.only, n.embedding, labels(n)"));
            assertEquals(List.of(), g.verticesByLabel("Q"));
            q(db, "MATCH (n:P) CREATE (n)-[:R {w: 1}]->(:P2)");
            q(db, "MATCH ()-[r:R]->() SET r.w = r.w + 1, r.t = 'x'");
            assertEquals(L(M("w", 2, "t", "x")), col(db, "MATCH ()-[r:R]->() RETURN properties(r)"));
            q(db, "MATCH ()-[r:R]->() REMOVE r.t");
            assertEquals(L(M("w", 2)), col(db, "MATCH ()-[r:R]->() RETURN properties(r)"));
            CypherException e = err(() -> q(db, "MATCH (n:P) SET n.m = {a: 1}"));
            assertNotNull(e);
            assertEquals("exec", e.stage());
            assertTrue(e.cypherMessage().startsWith("unsupported property type for `m`"), e.cypherMessage());
            assertEquals("exec", stage(() -> q(db, "MATCH (n:P) SET n.l = [1, 'a']")));
            okInv(g);
        });
    }

    @Test
    void delete() {
        withGraph(null, (db, g) -> {
            q(db, "CREATE (a:N {k: 1})-[:E]->(b:N {k: 2})-[:E]->(c:N {k: 3})");
            CypherException e = err(() -> q(db, "MATCH (n:N {k: 2}) DELETE n"));
            assertNotNull(e);
            assertEquals("exec", e.stage());
            assertTrue(e.cypherMessage().startsWith("cannot delete node"), e.cypherMessage());
            assertEquals(L(L(3)), rows(db, "MATCH (n:N) RETURN count(*)"), "原子：什么都没删");
            assertEquals(M("relationships-deleted", 1), q(db, "MATCH (a:N {k: 1})-[r:E]->(b) DELETE r").stats());
            assertEquals(M("nodes-deleted", 1), q(db, "MATCH (n:N {k: 1}) DELETE n").stats());
            assertEquals(M("relationships-deleted", 1, "nodes-deleted", 1), q(db, "MATCH (b:N {k: 2})-[r]->(c) DELETE r, b").stats());
            q(db, "MATCH (c:N {k: 3}) CREATE (c)-[:E]->(:N {k: 4}), (c)-[:E]->(c)");
            assertEquals(M("relationships-deleted", 2, "nodes-deleted", 1), q(db, "MATCH (c:N {k: 3}) DETACH DELETE c").stats());
            assertEquals(L(4), col(db, "MATCH (n:N) RETURN n.k"));
            assertEquals(col(db, "MATCH (n:N) RETURN id(n)"), new ArrayList<Object>(g.verticesByLabel("N")));
            assertEquals(L(L(0)), rows(db, "MATCH ()-[r]->() RETURN count(r)"));
            okInv(g);
        });
    }

    @Test
    void statementAtomicity() {
        withGraph(null, (db, g) -> {
            q(db, "CREATE (:Seed)");
            // clj (model/read-counter db)：这里直接比 id 计数器的原始字节
            byte[] before = db.get(GraphKeys.COUNTER);
            CypherException e = err(() -> q(db, "CREATE (n:X {v: 1})-[:E]->(m:X) SET n.v = 1 / 0"));
            assertNotNull(e);
            assertEquals("exec", e.stage());
            assertEquals("division by zero", e.cypherMessage());
            assertEquals(L(L(0)), rows(db, "MATCH (n:X) RETURN count(*)"));
            assertArrayEquals(before, db.get(GraphKeys.COUNTER), "计数器也没动");
            assertNotNull(g.etypeId("E"), "关系类型在语句开始前注册，语句失败也留着");
            okInv(g);
        });
    }

    @Test
    void largeWriteStatement() {
        // D-40：去掉了「一条语句最多写 4096 个顶点」的限制
        withGraph(null, (db, g) -> {
            assertEquals(10000L, q(db, "UNWIND range(1, 10000) AS x CREATE (:B {x: x})").stats().get("nodes-created"));
            assertEquals(L(L(10000, 50005000)), rows(db, "MATCH (n:B) RETURN count(*), sum(n.x)"));
            assertEquals(10000L, q(db, "MATCH (n:B) WITH collect(n) AS ns UNWIND range(0, 9999) AS i WITH ns[i] AS a, ns[(i + 1) % 10000] AS b CREATE (a)-[:NX]->(b)")
                    .stats().get("relationships-created"));
            okInv(g);
        });
    }

    @Test
    void concurrentUpdates() {
        // 8 个线程各做 20 次 MATCH … SET c.n = c.n + 1：不丢更新；8×20 个并发 CREATE 的 id 互不相同
        withGraph(null, (db, g) -> {
            q(db, "CREATE (:Counter {n: 0})");
            concurrently(8, () -> {
                for (int i = 0; i < 20; i++) Cypher.execute(db, "MATCH (c:Counter) SET c.n = c.n + 1", null, INFINITE);
            });
            assertEquals(L(160), col(db, "MATCH (c:Counter) RETURN c.n"));
            concurrently(8, () -> {
                for (int i = 0; i < 20; i++) Cypher.execute(db, "CREATE (:Item)", null, INFINITE);
            });
            List<Object> ids = col(db, "MATCH (n:Item) RETURN id(n)");
            assertEquals(160, ids.size());
            assertEquals(160, new HashSet<>(ids).size());
            okInv(g);
        });
    }

    @Test
    void statementConflictsWithDirectWriters() {
        // 图层 API 与 Cypher 写语句交错写同一个顶点 / 邻接表：语句冲突重跑，结果对称、计数精确
        withGraph(null, (db, g) -> {
            long t = g.registerEtype("E");
            long hub = g.createVertex(VertexSpec.of(List.of("Hub"), M("n", 0)));
            // 直写的目标是指定 id 10000..10099：先把水位推过这一段，免得分配器把还没写到的 id 发给 Cypher 新建的顶点
            g.putVertex(20000, "fence");
            CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 100; i++) g.putEdge(hub, t, 10000 + i);
            });
            CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 50; i++) Cypher.execute(db, "MATCH (h:Hub) CREATE (h)-[:E]->(:Leaf)", null, INFINITE);
            });
            CompletableFuture<Void> f3 = CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 50; i++) Cypher.execute(db, "MATCH (h:Hub) SET h.n = h.n + 1", null, INFINITE);
            });
            f1.join();
            f2.join();
            f3.join();
            assertEquals(L(L(150)), rows(db, "MATCH (h:Hub)-[:E]->() RETURN count(*)"));
            assertEquals(150L, g.degree(hub));
            assertEquals(L(50), col(db, "MATCH (h:Hub) RETURN h.n"));
            okInv(g);
        });
    }

    @Test
    void txnReadsSeeOwnWrites() {
        withGraph(null, (db, g) -> {
            assertEquals(L(L(1, 2)), rows(db, "CREATE (a:T {k: 1})-[:E]->(b:T {k: 2}) WITH a MATCH (a)-[:E]->(x) RETURN a.k, x.k"));
            assertEquals(L(L(2)), rows(db, "CREATE (:T {k: 9}) WITH 1 AS one MATCH (n:T) WHERE n.k < 5 RETURN count(*)"));
            assertEquals(L(L(3)), rows(db, "MATCH (n:T {k: 9}) SET n:U WITH n MATCH (m:U) RETURN count(m) + 2"));
            okInv(g);
        });
    }

    // ── Q-4：全文与向量 ───────────────────────────────────

    /** 名字 ⇒ vid。 */
    static Map<String, Long> docs(Graph g) {
        Map<String, Long> m = new HashMap<>();
        m.put("a", g.createVertex(new VertexSpec(List.of("Doc"), M("name", "a"), "graph databases store edges", new float[]{1f, 0f, 0f})));
        m.put("b", g.createVertex(new VertexSpec(List.of("Doc"), M("name", "b"), "vector search finds neighbours", new float[]{0.9f, 0.1f, 0f})));
        m.put("c", g.createVertex(new VertexSpec(List.of("Doc", "Hot"), M("name", "c"), "graph and vector together", new float[]{0f, 1f, 0f})));
        m.put("d", g.createVertex(new VertexSpec(List.of("Note"), M("name", "d"), "cooking recipes", new float[]{0f, 0f, 1f})));
        m.put("e", g.createVertex(new VertexSpec(List.of("Doc"), M("name", "e"), "graphs graphing graphite", new float[]{0.5f, 0.5f, 0f})));
        return m;
    }

    static Long parseNode(String key) {
        return GraphKeys.parseNode(key.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fulltext() {
        withGraph(searchOpts(), (db, g) -> {
            BitcaskIndex ix = (BitcaskIndex) db;
            docs(g);
            ix.flush();
            Function<String, List<Object>> n = s -> sorted(col(db, "MATCH (n) WHERE n.text @@ $q RETURN n.name", M("q", s)));
            assertEquals(L("a", "c"), n.apply("graph"));
            assertEquals(L("c"), n.apply("graph vector"), "并列：都要出现");
            assertEquals(L("a", "b", "c"), n.apply("graph OR vector"));
            assertEquals(L("a"), n.apply("graph -vector"), "排除");
            assertEquals(L("c"), n.apply("\"graph and vector\""), "短语");
            assertEquals(L("a", "c", "e"), n.apply("graph*"), "前缀");
            assertEquals(L("a", "c", "e"), n.apply("graphs~"), "模糊");
            List<Object> expect = new ArrayList<>();
            for (SearchHit h : ix.searchText("graph", SearchOptions.k(10))) {
                Long v = parseNode(h.key());
                if (v != null) expect.add(L(v, h.score()));
            }
            assertEquals(sorted(expect), sorted(new ArrayList<Object>(rows(db, "MATCH (n) WHERE n.text @@ 'graph' RETURN id(n), score(n)"))));
            assertEquals(clj("[:fts-seek 'n' 'text']"),
                    pr(((List<?>) ops("MATCH (n:Doc) WHERE n.text @@ 'x' AND n.name = 'a' RETURN n").getFirst()).subList(0, 3)));
            q(db, "MATCH (a {name: 'a'}), (b) WHERE b.name IN ['b', 'c'] CREATE (a)-[:L]->(b)");
            assertEquals(L("c"), col(db, "MATCH ({name: 'a'})-[:L]->(m) WHERE m.text @@ 'together' RETURN m.name"));
            assertEquals(L(true), col(db, "MATCH ({name: 'a'})-[:L]->(m) WHERE m.text @@ 'together' RETURN score(m) > 0"));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) WHERE n.name @@ 'a' RETURN n")));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) WHERE n.text @@ 1 RETURN n")));
        });
    }

    @Test
    void fulltextNeedsIndexMode() {
        withGraph(null, (db, g) -> {
            g.createVertex(VertexSpec.text("x"));
            assertTrue(message(() -> q(db, "MATCH (n) WHERE n.text @@ 'x' RETURN n")).startsWith("full-text and vector search need"));
        });
    }

    @Test
    void vectorSearch() {
        withGraph(searchOpts(), (db, g) -> {
            BitcaskIndex ix = (BitcaskIndex) db;
            Map<String, Long> ids = docs(g);
            ix.flush();
            List<Object> qv = L(1.0, 0.2, 0.0);
            float[] qf = {1.0f, 0.2f, 0.0f};
            List<Long> all = List.of(ids.get("a"), ids.get("b"), ids.get("c"), ids.get("d"), ids.get("e"));
            // 精确排序（rank-by-vector）⇒ vid 列表
            java.util.function.BiFunction<List<Long>, Integer, List<Object>> brute = (vids, k) -> {
                List<String> keys = new ArrayList<>();
                for (long v : vids) keys.add(GraphKeys.node(v));
                List<Object> out = new ArrayList<>();
                for (SearchHit h : ix.rankByVector(qf, keys, k)) {
                    Long v = parseNode(h.key());
                    if (v != null) out.add(v);
                }
                return out;
            };
            assertEquals(brute.apply(all, 3), col(db, "MATCH (n) RETURN id(n) ORDER BY n.embedding <=> $q LIMIT 3", M("q", qv)));
            assertEquals(clj("[:vector-topk 'n' [:param 'q'] [] [] [] nil [:lit 3]]"),
                    pr(ops("MATCH (n) RETURN n ORDER BY n.embedding <=> $q LIMIT 3").getFirst()));
            List<Object> want = new ArrayList<>(brute.apply(List.of(ids.get("a"), ids.get("b"), ids.get("c"), ids.get("e")), 4));
            want.remove(ids.get("b"));
            CypherResult r = q(db, "MATCH (n:Doc) WHERE n.name <> 'b' RETURN id(n), n.embedding <=> $q AS d ORDER BY d LIMIT 3", M("q", qv));
            assertEquals(Boolean.TRUE, r.stats().get("vector-exhaustive"));
            List<Object> firsts = new ArrayList<>(), seconds = new ArrayList<>();
            for (List<Object> row : r.rows()) {
                firsts.add(row.get(0));
                seconds.add(row.get(1));
            }
            assertEquals(want.subList(0, 3), firsts);
            assertEquals(sorted(seconds), seconds);
            double s0 = ix.rankByVector(qf, List.of(GraphKeys.node(ids.get("a"))), 1).getFirst().score();
            assertEquals(L(-s0), col(db, "MATCH (n {name: 'a'}) RETURN n.embedding <=> $q", M("q", qv)));
            double thr = -0.9;
            List<Object> exp = new ArrayList<>();
            for (long v : all) if (-ix.rankByVector(qf, List.of(GraphKeys.node(v)), 1).getFirst().score() < thr) exp.add(v);
            assertEquals(sorted(exp), sorted(col(db, "MATCH (n) WHERE n.embedding <=> $q < $t RETURN id(n)", M("q", qv, "t", thr))));
            assertEquals(clj("[:vector-range 'n']"), pr(((List<?>) ops("MATCH (n) WHERE n.embedding <=> $q < 0.5 RETURN n").getFirst()).subList(0, 2)));
            q(db, "MATCH (a {name: 'd'}), (x) WHERE x.name IN ['a', 'c', 'e'] CREATE (a)-[:L]->(x)");
            assertEquals(brute.apply(List.of(ids.get("a"), ids.get("c"), ids.get("e")), 2),
                    col(db, "MATCH ({name: 'd'})-[:L]->(m) RETURN id(m) ORDER BY m.embedding <=> $q LIMIT 2", M("q", qv)));
            long nv = g.createVertex(VertexSpec.labels("Doc"));
            assertEquals(L((Object) null), col(db, "MATCH (n) WHERE id(n) = $i RETURN n.embedding <=> $q", M("i", nv, "q", qv)));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) RETURN n.name <=> $q", M("q", qv))));
            // float[] 参数
            Map<String, Object> fp = new HashMap<>();
            fp.put("q", qf);
            assertEquals(brute.apply(all, 2), col(db, "MATCH (n) RETURN id(n) ORDER BY n.embedding <=> $q LIMIT 2", fp));
        });
    }

    @Test
    void vectorNeedsConfig() {
        withGraph(null, (db, g) -> {
            g.createVertex(VertexSpec.of(null, null));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) RETURN n ORDER BY n.embedding <=> [1.0] LIMIT 1")));
        });
    }

    // ── 二期：MERGE ───────────────────────────────────────

    @Test
    void mergeNode() {
        withGraph(null, (db, g) -> {
            String mq = "MERGE (u:User {name: $n}) ON CREATE SET u.created = 1 ON MATCH SET u.hits = coalesce(u.hits, 0) + 1 RETURN id(u), u.created, u.hits";
            CypherResult r1 = q(db, mq, M("n", "ann"));
            List<Object> row1 = r1.rows().getFirst();
            long u = (Long) row1.get(0);
            assertEquals(L(1, null), L(row1.get(1), row1.get(2)));
            assertEquals(M("nodes-created", 1, "labels-added", 1, "properties-set", 2), r1.stats());
            CypherResult r2 = q(db, mq, M("n", "ann"));
            List<Object> row2 = r2.rows().getFirst();
            assertEquals(L(u, 1), L(row2.get(0), row2.get(2)));
            assertEquals(M("properties-set", 1), r2.stats());
            assertEquals(L(L(u, 1, 2)), rows(db, mq, M("n", "ann")));
            List<Object> row3 = rows(db, mq, M("n", "bob")).getFirst();
            assertNotEquals(u, row3.get(0));
            assertEquals(L(1, null), L(row3.get(1), row3.get(2)));
            var info = g.vertexInfo(u);
            assertEquals(List.of("User"), info.labels());
            assertEquals(L("ann", 2), L(info.properties().get("name"), info.properties().get("hits")));
            long v3 = g.createVertex(VertexSpec.of(List.of("User"), M("name", "carl")));
            assertEquals(L(v3), col(db, "MERGE (u:User {name: 'carl'}) RETURN id(u)"), "与图层 API 同一份数据");
            Object m1 = rows(db, "MERGE (u:User:Admin {name: 'ann'}) RETURN id(u)").getFirst().getFirst();
            assertNotEquals(u, m1, "多标签：要全部标签都有才算匹配");
            assertEquals(L(m1), col(db, "MERGE (u:Admin:User {name: 'ann'}) RETURN id(u)"));
            q(db, "CREATE (:Dup {k: 1}), (:Dup {k: 1})");
            assertEquals(2, rows(db, "MERGE (d:Dup {k: 1}) RETURN d").size(), "匹配到多个：每个一行");
            assertEquals(2L, q(db, "UNWIND ['x', 'y', 'x'] AS n MERGE (:Tag {name: n})").stats().get("nodes-created"), "同一条语句里后面的行看得见前面建的");
            assertEquals(L("x", "y"), col(db, "MATCH (t:Tag) RETURN t.name ORDER BY t.name"));
            assertEquals(L(u), col(db, "MERGE (u {name: 'ann', hits: 2}) RETURN id(u)"), "没有标签：全量找");
            assertEquals("exec", stage(() -> q(db, "MERGE (u:User {name: $n}) RETURN u", M("n", null))));
            assertNotNull(err(() -> q(db, "MERGE (u:Boom {k: 1}) SET u.x = 1 / 0")));
            assertEquals(L(), rows(db, "MATCH (b:Boom) RETURN b"), "出错不留痕迹");
            okInv(g);
        });
    }

    @Test
    void mergeRel() {
        withGraph(null, (db, g) -> {
            q(db, "CREATE (:P {n: 1}), (:P {n: 2})");
            String mq = "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (a)-[r:KNOWS {since: 2020}]->(b) ON CREATE SET r.c = true ON MATCH SET r.m = true RETURN r.since, r.c, r.m";
            CypherResult r = q(db, mq);
            assertEquals(L(L(2020, true, null)), r.rows());
            assertEquals(1L, r.stats().get("relationships-created"));
            assertEquals(L(L(2020, true, true)), rows(db, mq));
            assertEquals(L(1), col(db, "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (b)<-[r:KNOWS]-(a) RETURN count(r)"), "反着写同一条边");
            q(db, "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (a)-[:KNOWS {since: 1999}]->(b)");
            q(db, "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (a)-[:LIKES]->(b)");
            assertEquals(L(L("KNOWS", 2), L("LIKES", 1)), rows(db, "MATCH (:P {n: 1})-[r]->(:P {n: 2}) RETURN type(r) AS t, count(*) ORDER BY t"));
            assertNotNull(g.edge(1, g.etypeId("LIKES"), 2), "类型自动注册");
            q(db, "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (a)<-[:LIKES]-(b)");
            assertEquals(L(1), col(db, "MATCH (:P {n: 2})-[r:LIKES]->(:P {n: 1}) RETURN count(r)"), "方向相反是另一条边");
            assertEquals("exec", stage(() -> q(db, "MATCH (a:P {n: 1}) OPTIONAL MATCH (a)-[:NONE]->(z) MERGE (a)-[:T]->(z)")));
            okInv(g);
        });
    }

    @Test
    void mergeConcurrent() {
        // 8 个线程 × 10 次 MERGE 同一个节点：只建一个，其余 79 次都走 ON MATCH
        withGraph(null, (db, g) -> {
            List<CompletableFuture<List<CypherResult>>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++)
                fs.add(CompletableFuture.supplyAsync(() -> {
                    List<CypherResult> out = new ArrayList<>();
                    for (int i = 0; i < 10; i++)
                        out.add(Cypher.execute(db, "MERGE (u:Hot {name: 'z'}) ON MATCH SET u.n = coalesce(u.n, 0) + 1 RETURN id(u)", null, INFINITE));
                    return out;
                }));
            List<CypherResult> res = new ArrayList<>();
            for (var f : fs) res.addAll(f.join());
            assertEquals(80, res.size());
            HashSet<Object> idset = new HashSet<>();
            for (CypherResult r : res) idset.add(r.rows().getFirst().getFirst());
            assertEquals(1, idset.size());
            assertEquals(L(L(1, 79)), rows(db, "MATCH (u:Hot) RETURN count(u), max(u.n)"));
            okInv(g);
        });
    }

    @Test
    void mergeRelConcurrent() {
        withGraph(null, (db, g) -> {
            q(db, "CREATE (:P {n: 1}), (:P {n: 2})");
            concurrently(8, () -> {
                for (int i = 0; i < 10; i++)
                    Cypher.execute(db, "MATCH (a:P {n: 1}), (b:P {n: 2}) MERGE (a)-[r:K]->(b) ON MATCH SET r.n = coalesce(r.n, 0) + 1", null, INFINITE);
            });
            assertEquals(L(L(1, 79)), rows(db, "MATCH (:P {n: 1})-[r:K]->(:P {n: 2}) RETURN count(r), max(r.n)"));
            okInv(g);
        });
    }

    @Test
    void mergePattern() {
        withGraph(null, (db, g) -> {
            String mq = "MERGE (a:X {k: 1})-[r:T]->(b:Y {k: 2}) ON CREATE SET r.c = 1 ON MATCH SET r.m = 1 RETURN id(a), id(b), r.c, r.m";
            CypherResult r1 = q(db, mq);
            List<Object> row = r1.rows().getFirst();
            Object a = row.get(0), b = row.get(1);
            assertEquals(L(1, null), L(row.get(2), row.get(3)));
            assertEquals(M("nodes-created", 2, "labels-added", 2, "properties-set", 3, "relationships-created", 1), r1.stats());
            assertEquals(L(L(a, b, 1, 1)), rows(db, mq));
            q(db, "MERGE (a:X {k: 1})-[:T]->(b:Y {k: 3})");
            assertEquals(L(2), col(db, "MATCH (x:X {k: 1}) RETURN count(x)"), "只有一端不同 ⇒ 整条新建");
            Object z = rows(db, "MATCH (a) WHERE id(a) = $a MERGE (a)-[:U]->(z:Z {k: 9}) RETURN id(z)", M("a", a)).getFirst().getFirst();
            CypherResult r2 = q(db, "MATCH (a) WHERE id(a) = $a MERGE (a)-[:U]->(z:Z {k: 9}) RETURN id(z)", M("a", a));
            assertEquals(L(L(z)), r2.rows());
            assertEquals(Map.of(), r2.stats());
            Object c1 = rows(db, "MERGE (:A)-[:T]->(:B)-[:T]->(c:C) RETURN id(c)").getFirst().getFirst();
            assertEquals(L(c1), col(db, "MERGE (:A)-[:T]->(:B)-[:T]->(c:C) RETURN id(c)"));
            assertEquals(L(L(1, 1, 1)), rows(db, "MATCH (a:A), (b:B), (c:C) RETURN count(a), count(b), count(c)"));
            assertNotNull(g.etypeId("U"));
            assertEquals("exec", stage(() -> q(db, "MERGE (a:X {k: $k})-[:T]->(:Y)", M("k", null))));
            okInv(g);
        });
    }

    @Test
    void mergePatternConcurrent() {
        withGraph(null, (db, g) -> {
            concurrently(8, () -> {
                for (int i = 0; i < 10; i++) Cypher.execute(db, "MERGE (a:Hot {k: 1})-[:T]->(b:Cold {k: 2})", null, INFINITE);
            });
            assertEquals(L(L(1, 1, 1)), rows(db, "MATCH (a:Hot)-[r:T]->(b:Cold) RETURN count(DISTINCT a), count(DISTINCT b), count(r)"));
            okInv(g);
        });
    }

    @Test
    void searchFunctions() {
        withGraph(searchOpts(), (db, g) -> {
            BitcaskIndex ix = (BitcaskIndex) db;
            Map<String, Long> ids = docs(g);
            ix.flush();
            Map<Long, String> nm = new HashMap<>();
            ids.forEach((k, v) -> nm.put(v, k));
            Function<List<SearchHit>, List<Object>> engine = hits -> {
                List<Object> out = new ArrayList<>();
                for (SearchHit h : hits) {
                    Long v = parseNode(h.key());
                    if (v != null && nm.containsKey(v)) out.add(nm.get(v));
                }
                return sorted(out);
            };
            Function<String, List<Object>> n = s -> sorted(col(db, s));
            assertEquals(engine.apply(ix.searchPhrase("graph and vector", SearchOptions.k(100))),
                    n.apply("MATCH (n) WHERE fts.phrase(n.text, 'graph and vector') RETURN n.name"));
            assertEquals(engine.apply(ix.searchNear("graph together", SearchOptions.k(100).slop(3))),
                    n.apply("MATCH (n) WHERE fts.near(n.text, 'graph together', 3) RETURN n.name"));
            assertEquals(engine.apply(ix.searchWildcard("graph*", SearchOptions.k(100))),
                    n.apply("MATCH (n) WHERE fts.wildcard(n.text, 'graph*') RETURN n.name"));
            assertEquals(engine.apply(ix.searchFuzzy("grph", SearchOptions.k(100).maxEdits(1))),
                    n.apply("MATCH (n) WHERE fts.fuzzy(n.text, 'grph') RETURN n.name"));
            assertEquals(engine.apply(ix.searchFuzzy("grap", SearchOptions.k(100).maxEdits(2))),
                    n.apply("MATCH (n) WHERE fts.fuzzy(n.text, 'grap', 2) RETURN n.name"));
            assertEquals(clj("[:search-seek 'n' 'fts.wildcard']"),
                    pr(((List<?>) ops("MATCH (n) WHERE fts.wildcard(n.text, 'graph*') RETURN n").getFirst()).subList(0, 3)));
            double best = Double.NEGATIVE_INFINITY;
            for (SearchHit h : ix.searchWildcard("graph*", SearchOptions.k(100))) best = Math.max(best, h.score());
            Object s1 = rows(db, "MATCH (n) WHERE fts.wildcard(n.text, 'graph*') RETURN n.name, score(n) ORDER BY score(n) DESC").getFirst().get(1);
            assertTrue(Math.abs(((Number) s1).doubleValue() - best) < 1.0e-6);
            assertEquals(n.apply("MATCH (n) WHERE fts.wildcard(n.text, 'graph*') AND n:Doc RETURN n.name"),
                    n.apply("MATCH (n:Doc) WHERE n.name <> 'zz' AND (fts.wildcard(n.text, 'graph*') OR false) RETURN n.name"), "作为过滤与作为起点结果相同");
            List<Object> qv = L(1.0, 0.1, 0.0);
            assertEquals(engine.apply(ix.searchHybrid("graph", new float[]{1.0f, 0.1f, 0.0f}, SearchOptions.k(3))),
                    sorted(col(db, "MATCH (n) WHERE search.hybrid(n, 'graph', $q, 3) RETURN n.name", M("q", qv))));
            assertEquals(3, rows(db, "MATCH (n) WHERE search.hybrid(n, 'graph', $q, 3) RETURN n", M("q", qv)).size());
            assertEquals(L(), rows(db, "MATCH (n) WHERE fts.phrase(n.text, $q) RETURN n", M("q", null)));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) WHERE fts.near(n.text, 'a', -1) RETURN n")));
            assertEquals("semantic", stage(() -> q(db, "MATCH (n) WHERE fts.phrase(n.name, 'a') RETURN n")));
            assertEquals("semantic", stage(() -> q(db, "MATCH (n) WHERE foo.bar(n) RETURN n")));
        });
    }
}
