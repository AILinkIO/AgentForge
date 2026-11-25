package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.L;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.M;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.clj;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.col;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.err;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.heads;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.kw;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.message;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.okInv;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.ops;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.pr;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.q;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.rows;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.sorted;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.stage;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.vi;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Options;
import io.ailink.agentforge.bitcask.graph.Edge;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.Graph.AdjQuery;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.VertexSpec;

/**
 * Cypher 子集 Q-1～Q-2（移植自 clj-bitcask cypher_test.clj，对照 bitcask（Erlang）bitcask_cypher_tests）：
 * 解析 / 语义检查（一期语法逐条有正例，非法输入报阶段与行列）；执行（查询结果与图层 API 手算对照；规划的起点选择；LIMIT 提前停扫）。
 */
class CypherTest {
    @TempDir
    Path d;

    // ── 解析与语义检查（不碰库，不要求原生库）────────────

    static final List<String> PHASE1 = List.of(
            // MATCH / WHERE / 节点 / 关系 / 方向 / 多类型
            "MATCH (n) RETURN n",
            "MATCH (n:Person:Admin {name: 'x', age: 3}) WHERE n.age > 1 RETURN n",
            "MATCH (a)-[r:KNOWS]->(b), (b)<-[:KNOWS|LIKES]-(c), (c)-[:A|:B]-(d) RETURN a, r, b, c, d",
            "MATCH (a)-->(b)<--(c)--(d) RETURN d",
            "MATCH (a)<-->(b) RETURN b",
            "match (n) where n.x = 1 return n",
            // 变长
            "MATCH (a)-[*]->(b) RETURN b",
            "MATCH (a)-[r:T*2]->(b) RETURN r",
            "MATCH (a)-[:T*1..3]->(b) RETURN b",
            "MATCH (a)-[:T*..3]->(b) RETURN b",
            "MATCH (a)-[:T*2..]->(b) RETURN b",
            "MATCH (a)-[:T*0..1 {w: 1}]->(b) RETURN b",
            // 最短路
            "MATCH p = shortestPath((a)-[:T*]->(b)) RETURN p, length(p), nodes(p), relationships(p)",
            "MATCH shortestPath((a:X {id: 1})-[*..5]-(b:Y)) RETURN a, b",
            // RETURN / WITH / ORDER / SKIP / LIMIT / DISTINCT / *
            "MATCH (n) RETURN DISTINCT n.name AS name ORDER BY name DESC SKIP 1 LIMIT $k",
            "MATCH (n) RETURN *",
            "MATCH (n) RETURN *, n.x AS x",
            "MATCH (n) WITH n, count(*) AS c WHERE c > 1 RETURN n, c ORDER BY c ASCENDING, n.name DESCENDING",
            "MATCH (n) WITH DISTINCT n.city AS city ORDER BY city LIMIT 3 RETURN city",
            "MATCH (n) WITH * RETURN n",
            "UNWIND $list AS x RETURN x",
            "UNWIND [1, 2.5, 'a', true, false, null, [1], {a: 1}] AS x RETURN x",
            // 表达式
            "RETURN 1 + 2 * 3 - 4 / 2 % 3 ^ 2 AS v, -1 AS m, 'a' + 'b' AS s",
            "RETURN 1 = 1 AND 2 <> 3 OR NOT false XOR true AS b",
            "RETURN 1 < 2, 2 <= 2, 3 > 2, 3 >= 3",
            "RETURN 'abc' STARTS WITH 'a', 'abc' ENDS WITH 'c', 'abc' CONTAINS 'b', 1 IN [1, 2]",
            "RETURN null IS NULL, 1 IS NOT NULL",
            "MATCH (n) WHERE n:Person:Admin RETURN n",
            "RETURN coalesce(null, 1), size([1]), toInteger('3'), toFloat(1), toString(1), toLower('A'), toUpper('a'), abs(-1)",
            "MATCH (n)-[r]->(m) RETURN id(n), labels(n), type(r), properties(r), keys(n), startNode(r), endNode(r)",
            "MATCH (n) RETURN count(*), count(n), count(DISTINCT n.x), sum(n.x), avg(n.x), min(n.x), max(n.x), collect(n.x)",
            "MATCH (n) RETURN count(*) + 1 AS c1",
            "MATCH (n) RETURN n.order, n.`weird name`, n.limit",
            "MATCH (n:Order) RETURN n",
            "MATCH (`my var`) RETURN `my var`",
            "RETURN 'it\\'s', \"dq \\\"x\\\"\", '\\u4e2d', 1.5e3, 2E-1, 7",
            "// comment\nMATCH (n) /* block\ncomment */ RETURN n;",
            "MATCH (n {name: $name}) RETURN n",
            "CREATE (n $props) RETURN n",
            // 写子句
            "CREATE (n:Person {name: 'x'})",
            "CREATE (a:A)-[:T {w: 1}]->(b:B)<-[:U]-(c)",
            "MATCH (a), (b) CREATE (a)-[:T]->(b)",
            "MATCH (n) SET n.x = 1, n += {y: 2}, n = {z: 3}, n:L:M",
            "MATCH (n) REMOVE n.x, n:L",
            "MATCH (n) DELETE n",
            "MATCH (n)-[r]->() DETACH DELETE n, r",
            "MATCH (n) SET n.x = 1 RETURN n",
            // 二期：OPTIONAL MATCH、MERGE、CASE
            "MATCH (a) OPTIONAL MATCH (a)-[r:T]->(b) WHERE b.x > 1 RETURN a, r, b",
            "OPTIONAL MATCH (n:X) RETURN n",
            "MERGE (n:A {k: 1}) RETURN n",
            "MERGE (x:A) ON CREATE SET x.y = 1",
            "MERGE (n:A) ON MATCH SET n.m = 1 ON CREATE SET n.c = 1, n:B ON MATCH SET n.m2 = 2 RETURN n",
            "MATCH (a), (b) MERGE (a)-[r:T {w: 1}]->(b) ON CREATE SET r.c = true RETURN r",
            "MATCH (a), (b) MERGE (a)<-[:T]-(b)",
            "RETURN CASE WHEN 1 > 2 THEN 'a' WHEN true THEN 'b' ELSE 'c' END",
            "MATCH (n) RETURN CASE n.k WHEN 1 THEN 'one' END, CASE WHEN n.k IS NULL THEN 0 END AS z",
            "MATCH (n) RETURN n.case, n.end, n.when",
            // 二期其余：命名路径、allShortestPaths、标签表达式、NULLS、=~、下标 / 切片、新函数、CALL、整条模式的 MERGE、检索函数
            "MATCH p = (a)-[r:T]->(b)<-[*1..2]-(c) WHERE length(p) > 1 RETURN p, nodes(p), relationships(p)",
            "MATCH p = allShortestPaths((a)-[:T*..5]->(b)) RETURN p",
            "MATCH (n:A|B:!C) WHERE n:D|E AND NOT n:!F RETURN n",
            "MATCH (n) RETURN n ORDER BY n.x DESC, n.y ASCENDING",
            "MATCH (n) WHERE n.s =~ '^a.*' RETURN n.list[0], n.list[1..], n.list[..-1], n['key'], [1, 2, 3][$i]",
            "RETURN substring('ab', 1), replace('a', 'a', 'b'), split('a,b', ','), trim(' a '), ltrim(' a'), rtrim('a '), left('ab', 1), right('ab', 1), reverse('ab'), head([1]), tail([1]), last([1]), range(1, 3), range(1, 9, 2)",
            "CALL db.labels()",
            "CALL db.relationshipTypes() YIELD relationshipType AS t WHERE t <> 'X' RETURN t",
            "CALL db.labels() YIELD label MATCH (n) WHERE label IN labels(n) RETURN label, count(n)",
            "MERGE (a:X {k: 1})-[r:T]->(b:Y)-[:U]->(c) ON CREATE SET r.c = 1 RETURN a, b, c",
            "MATCH (a) MERGE (a)-[:T]->(:Z {k: 2})",
            "MATCH (n) WHERE fts.phrase(n.text, 'a b') AND fts.near(n.text, 'a b', 2) OR search.hybrid(n, 'x', [1.0], 3) RETURN n");

    @Test
    void parsesPhase1Syntax() {
        for (String s : PHASE1) {
            Cypher.validate(s);
            assertNotNull(Cypher.explain(s), s);
        }
    }

    @Test
    void writesFlag() {
        assertFalse(Cypher.writes("MATCH (n) RETURN n"));
        assertTrue(Cypher.writes("CREATE (n)"));
        assertTrue(Cypher.writes("MATCH (n) SET n.x = 1"));
        assertTrue(Cypher.writes("MATCH (n) DETACH DELETE n"));
        assertTrue(Cypher.writes("MATCH (n) REMOVE n:L"));
        assertFalse(Cypher.prepare("MATCH (n) RETURN n").writes());
    }

    /** [查询 阶段 行 列（null = 不查） 消息片段]。 */
    static final Object[][] REJECTS = {
            {"MATCH (n RETURN n", "parse", 1, 10, "RETURN"},
            {"MATCH (n)\nRETURN n.", "parse", 2, null, "end of query"},
            {"MATCH (n) RETURN 'abc", "parse", 1, null, ""},
            {"MATCH (n) RETURN m", "semantic", 1, 18, "`m` not defined"},
            {"MATCH (n) RETURN foo(n)", "semantic", 1, 18, "unknown function"},
            {"MATCH (n) RETURN size(n, 1)", "semantic", 1, 18, "wrong number"},
            {"MATCH (n) WHERE count(*) > 1 RETURN n", "semantic", 1, 1, "WHERE"},
            {"MATCH (n) RETURN count(count(n))", "semantic", 1, 11, "nested"},
            {"MATCH (n) WITH n.x RETURN 1", "semantic", 1, 11, "aliased"},
            {"MATCH (n) RETURN n MATCH (m) RETURN m", "semantic", 1, 11, "last clause"},
            {"MATCH (n)", "semantic", 1, 1, "must end"},
            {"MATCH (a) MERGE (a)-[:T*2]->(b) RETURN a", "semantic", 1, 20, "variable-length"},
            {"MATCH (a) MERGE (a) RETURN a", "semantic", 1, 18, "already bound"},
            {"MATCH (a), (b) MERGE (a)-[:T|U]->(b) RETURN a", "semantic", 1, 25, "exactly one"},
            {"MATCH (a), (b) MERGE (a:X)-[:T]->(b) RETURN a", "semantic", 1, 23, "bound node"},
            {"MERGE (n {embedding: [1.0]}) RETURN n", "semantic", 1, 7, "embedding"},
            {"MERGE (a)-[r:T]->(b)-[r:T]->(c) RETURN a", "semantic", 1, 23, "used twice"},
            {"MERGE (a)-[:T]->(b:X|Y) RETURN a", "semantic", 1, 17, "label expressions"},
            {"CREATE (n:A|B)", "semantic", 1, 9, "label expressions"},
            {"MATCH (n) SET n:!X", "semantic", 1, 15, "label expressions"},
            {"CALL db.nope()", "semantic", 1, 1, "unknown procedure"},
            {"CALL db.labels() YIELD nope RETURN nope", "semantic", 1, 24, "no output"},
            {"CALL db.labels() RETURN 1", "semantic", 1, 1, "needs YIELD"},
            {"MATCH (n) RETURN n ORDER BY n.x NULLS MIDDLE", "parse", 1, 33, "NULLS"},
            {"MATCH (n) WHERE fts.phrase(n.name, 'a') RETURN n", "semantic", 1, 17, "node text"},
            {"MATCH (n) WHERE search.hybrid(n.text, 'a', [1.0]) RETURN n", "semantic", 1, 17, "node variable"},
            {"MATCH (n) WHERE foo.bar(n) RETURN n", "semantic", 1, 17, "unknown function `foo.bar`"},
            {"MATCH (a), (b) MERGE (a)-[:T]->(b) ON CREATE SET c.x = 1 RETURN a", "semantic", 1, 50, "not defined"},
            {"RETURN CASE END", "parse", 1, 13, "syntax error"},
            {"MATCH (a)-[r]->(b)-[r]->(c) RETURN a", "semantic", 1, 21, "used twice"},
            {"MATCH (a)-[r]->(b) MATCH (r) RETURN r", "semantic", 1, 27, "already declared"},
            {"CREATE (a)-[:T]-(b)", "semantic", 1, 11, "directed"},
            {"CREATE (a)-[]->(b)", "semantic", 1, 11, "exactly one"},
            {"CREATE (a)-[:T*2]->(b)", "semantic", 1, 11, "variable-length"},
            {"MATCH (n) RETURN toLower(DISTINCT n.x)", "semantic", 1, 18, "DISTINCT"},
            {"MATCH (n) RETURN sum(*)", "parse", 1, 18, "count(*)"},
            {"MATCH (n) RETURN n LIMIT n.x", "semantic", 1, 11, "SKIP / LIMIT"},
            {"MATCH (n) RETURN n.x AS a, count(*) AS c ORDER BY n.y", "semantic", 1, 11, "not returned"},
            {"MATCH (n) RETURN n AS a, n AS a", "semantic", 1, 11, "duplicate"},
            {"MATCH (n) SET m.x = 1", "semantic", 1, 15, "not defined"},
            {"WITH 1 AS x MATCH (x) RETURN x", "semantic", 1, 20, "type conflict"},
            {"MATCH (n)-[r*2]->(m) SET r.x = 1", "semantic", 1, 26, "not a node or relationship"},
            {"MATCH (a) CREATE (a:L)-[:T]->(b)", "semantic", 1, 19, "already bound"},
            {"MATCH (a) SET a._x = 1", "semantic", 1, 15, "reserved"},
            {"MATCH (a) REMOVE a._labels", "semantic", 1, 18, "reserved"},
            {"MATCH (a) RETURN score(a.x)", "semantic", 1, 18, "score() takes a node variable"},
            {"MATCH (a) WHERE (a)-->(b) RETURN a", "semantic", 1, 17, "cannot introduce new variables"},
            {"RETURN 1 AS x UNION RETURN 2 AS y", "semantic", 1, 15, "same column names"},
            {"RETURN 1 AS x UNION RETURN 2 AS x UNION ALL RETURN 3 AS x", "semantic", 1, 15, "UNION and UNION ALL"},
            {"MATCH (n) RETURN exists(n)", "semantic", 1, 18, "exists()"},
    };

    @Test
    void rejectsWithStageAndLocation() {
        for (Object[] r : REJECTS) {
            String s = (String) r[0];
            CypherException e = err(() -> Cypher.explain(s));
            assertNotNull(e, s);
            assertEquals(r[1], e.stage(), s);
            assertEquals(Long.valueOf((Integer) r[2]), e.line(), s);
            if (r[3] != null) assertEquals(Long.valueOf((Integer) r[3]), e.column(), s);
            assertTrue(String.valueOf(e.cypherMessage()).contains((String) r[4]), s + " — " + e.cypherMessage());
            assertEquals("bitcask/cypher-error", e.code(), s);
        }
    }

    @Test
    void columnNames() {
        // clj (c/parse …) 的 {:query [_ [_ _ items]]}：直接走包内的 Parser / Sema
        Object a = Sema.analyze(Parser.parse("MATCH (n) RETURN n.name, count(DISTINCT n.x), n.age + 1, 'lit', n AS m, $p"));
        List<?> query = (List<?>) Clj.get(a, kw("query"));
        List<?> items = (List<?>) Clj.nth(query.get(1), 2);
        List<Object> names = new ArrayList<>();
        for (Object it : items) names.add(Clj.first(it));
        assertEquals(List.of("n.name", "count(DISTINCT n.x)", "n.age + 1", "'lit'", "m", "$p"), names);
    }

    // ── 执行：固定的小图 ──────────────────────────────────

    record People(Bitcask db, Graph g, long knows, long works, long likes,
                  long ann, long bob, long cat, long dan, long eve, long acme, long globex) {
    }

    static People people(Bitcask db) {
        Graph g = Graph.open(db);
        long knows = g.registerEtype("KNOWS");
        long works = g.registerEtype("WORKS_AT");
        long likes = g.registerEtype("LIKES");
        long ann = g.createVertex(VertexSpec.of(List.of("Person"), M("name", "ann", "age", 31, "city", "Paris")));
        long bob = g.createVertex(VertexSpec.of(List.of("Person"), M("name", "bob", "age", 25, "city", "Berlin")));
        long cat = g.createVertex(VertexSpec.of(List.of("Person", "Admin"), M("name", "cat", "age", 40, "city", "Paris")));
        long dan = g.createVertex(VertexSpec.of(List.of("Person"), M("name", "dan", "age", 19)));
        long eve = g.createVertex(VertexSpec.of(List.of("Person"), M("name", "eve", "age", 31.0, "city", "Berlin", "tags", L("x", "y"))));
        long acme = g.createVertex(VertexSpec.of(List.of("Company"), M("name", "acme")));
        long globex = g.createVertex(VertexSpec.of(List.of("Company"), M("name", "globex")));
        g.putEdge(ann, knows, bob, 0, M("since", 2020));
        g.putEdge(ann, knows, bob, 1, M("since", 2022));
        g.putEdge(bob, knows, cat, 0, M("since", 2021));
        g.putEdge(cat, knows, ann, 0, M("since", 2019));
        g.putEdge(ann, knows, dan);
        g.putEdge(dan, knows, eve);
        g.putEdges(List.of(EdgeInput.of(ann, works, acme), EdgeInput.of(bob, works, acme), EdgeInput.of(cat, works, globex)));
        g.putEdge(eve, likes, cat, 0, "legacy:1");
        g.putEdge(eve, 99, ann);
        g.putEdge(eve, 99, 1000);
        g.putVertex(500, "legacy text");
        return new People(db, g, knows, works, likes, ann, bob, cat, dan, eve, acme, globex);
    }

    @FunctionalInterface
    interface PeopleBody {
        void run(People p);
    }

    void withPeople(PeopleBody body) {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            body.run(people(db));
        }
    }

    @Test
    void basicMatch() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L("cat", "ann", "eve", "bob", "dan"), col(db, "MATCH (n:Person) RETURN n.name ORDER BY n.age DESC, n.name"));
            assertEquals(L(L("ann", "Paris"), L("cat", "Paris")), rows(db, "MATCH (n:Person {city: 'Paris'}) RETURN n.name, n.city ORDER BY n.name"));
            assertEquals(L("cat"), col(db, "MATCH (n:Person:Admin) RETURN n.name"));
            assertEquals(L("cat"), col(db, "MATCH (n:Admin:Person) RETURN n.name"));
            assertEquals(L(), col(db, "MATCH (n:Nobody) RETURN n"));
            // 全部顶点 = n 文档 ∪ 邻接表头（含悬挂端点 1000、旧式顶点 500）
            assertEquals(L(L(9)), rows(db, "MATCH (n) RETURN count(n)"));
            assertEquals(L(1, 2, 3, 4, 5, 6, 7, 500, 1000), sorted(col(db, "MATCH (n) RETURN id(n)")));
            assertEquals(L("ann"), col(db, "MATCH (n) WHERE id(n) = $id RETURN n.name", M("id", p.ann())));
            assertEquals(L("ann", "bob"), col(db, "MATCH (n) WHERE id(n) IN $ids RETURN n.name ORDER BY n.name",
                    M("ids", L(p.bob(), p.ann(), 424242, p.bob()))));
            assertEquals(L(L("legacy text", L())), rows(db, "MATCH (n) WHERE id(n) = 500 RETURN n.text, labels(n)"));
            assertEquals(L(L(new Node(1000, List.of(), Map.of()))), rows(db, "MATCH (n) WHERE id(n) = 1000 RETURN n"));
        });
    }

    @Test
    void resultValueShapes() {
        withPeople(p -> {
            Bitcask db = p.db();
            List<Object> r0 = rows(db, "MATCH (n {name: 'bob'})-[r:KNOWS]->(m) RETURN n, r, m").getFirst();
            assertEquals(new Node(p.bob(), List.of("Person"), M("name", "bob", "age", 25, "city", "Berlin")), r0.get(0));
            assertEquals(new Relationship(p.bob(), "KNOWS", p.cat(), 0, M("since", 2021)), r0.get(1));
            assertEquals(p.cat(), ((Node) r0.get(2)).id());
            assertEquals(L(L(99)), rows(db, "MATCH (e {name: 'eve'})-[r]->(a {name: 'ann'}) RETURN type(r)"));
            assertEquals(L(M("_raw", "legacy:1")), col(db, "MATCH ()-[r:LIKES]->() RETURN properties(r)"));
            // id(r) = [src etype dst rank]
            assertEquals(L(L(p.bob(), p.knows(), p.cat(), 0)), col(db, "MATCH ({name: 'bob'})-[r:KNOWS]->() RETURN id(r)"));
        });
    }

    @Test
    void expandMatchesApi() {
        withPeople(p -> {
            Bitcask db = p.db();
            long ann = p.ann(), knows = p.knows();
            List<Edge> out = p.g().outEdges(ann, AdjQuery.etype(knows));
            List<Object> want = new ArrayList<>(), dsts = new ArrayList<>();
            for (Edge e : out) {
                want.add(L(ann, knows, e.dst(), e.rank()));
                dsts.add(e.dst());
            }
            assertEquals(sorted(want), sorted(col(db, "MATCH (a)-[r:KNOWS]->(b) WHERE id(a) = $a RETURN id(r)", M("a", ann))));
            assertEquals(sorted(dsts), sorted(col(db, "MATCH (a)-[:KNOWS]->(b) WHERE id(a) = $a RETURN id(b)", M("a", ann))));
            assertEquals(L(L("ann", 2019)), rows(db, "MATCH (a {name: 'ann'})<-[r:KNOWS]-(c) RETURN a.name, r.since"));
            assertEquals(L("bob", "bob", "cat", "dan"), sorted(col(db, "MATCH (a {name: 'ann'})-[:KNOWS]-(b) RETURN b.name")));
            assertEquals(sorted(L("bob", "bob", "dan", "acme")), sorted(col(db, "MATCH (a {name: 'ann'})-[:KNOWS|WORKS_AT]->(b) RETURN b.name")));
            assertEquals(L(), col(db, "MATCH (a {name: 'ann'})-[:NOPE]->(b) RETURN b"));
            assertEquals(sorted(L(p.cat(), ann, 1000)), sorted(col(db, "MATCH (e {name: 'eve'})-->(b) RETURN id(b)")));
            assertEquals(L(2020, 2022), col(db, "MATCH ({name: 'ann'})-[r:KNOWS]->({name: 'bob'}) RETURN r.since ORDER BY r.since"));
            assertEquals(L(L(2)), rows(db, "MATCH ({name: 'ann'})-[r:KNOWS {since: 2022}]->(b) RETURN r.since - 2020"));
        });
    }

    @Test
    void expandIntoAndUniqueness() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L(), rows(db, "MATCH (a)-[:KNOWS]->(b), (b)-[:KNOWS]->(a) RETURN a.name, b.name"));
            assertEquals(L("ann", "ann", "bob", "bob", "cat", "cat"),
                    sorted(col(db, "MATCH (a)-[:KNOWS]->(b)-[:KNOWS]->(c)-[:KNOWS]->(a) RETURN a.name")));
            assertEquals(L(), rows(db, "MATCH (a {name: 'dan'})-[r1]-(b)-[r2]-(a) RETURN r1, r2"));
            assertEquals(2, rows(db, "MATCH (a {name: 'ann'})-[r1:KNOWS]->(b {name: 'bob'})<-[r2:KNOWS]-(a) RETURN r1, r2").size());
            assertEquals(4, rows(db, "MATCH (a {name: 'ann'})-[r1:KNOWS]->(b {name: 'bob'}) MATCH (a)-[r2:KNOWS]->(b) RETURN r1, r2").size());
        });
    }

    /** 手算：从 v 沿类型 t 的出边，路径内边不重复，长度 mn..mx 的路径终点（名字）。 */
    static List<Object> manualPaths(Graph g, long t, long v, int mn, int mx) {
        List<Object> out = new ArrayList<>();
        mp(g, t, v, 0, mn, mx, new HashSet<>(), out);
        return out;
    }

    private static void mp(Graph g, long t, long v, int depth, int mn, int mx, Set<List<Long>> used, List<Object> out) {
        if (depth >= mn) out.add(g.vertexInfo(v).properties().get("name"));
        if (depth < mx) {
            for (Edge e : g.outEdges(v, AdjQuery.etype(t))) {
                List<Long> k = List.of(v, e.dst(), e.rank());
                if (used.contains(k)) continue;
                Set<List<Long>> u2 = new HashSet<>(used);
                u2.add(k);
                mp(g, t, e.dst(), depth + 1, mn, mx, u2, out);
            }
        }
    }

    @Test
    void varLength() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(sorted(manualPaths(p.g(), p.knows(), p.ann(), 1, 3)),
                    sorted(col(db, "MATCH (a)-[:KNOWS*1..3]->(b) WHERE id(a) = $a RETURN b.name", M("a", p.ann()))));
            assertEquals(L("ann"), col(db, "MATCH (a {name: 'ann'})-[:KNOWS*0]->(b) RETURN b.name"));
            assertTrue(col(db, "MATCH (a {name: 'ann'})-[:KNOWS*0..1]->(b) RETURN b.name").contains("ann"));
            List<Object> ls = col(db, "MATCH (a {name: 'ann'})-[r:KNOWS*2]->(c {name: 'cat'}) RETURN r");
            List<Object> sinces = new ArrayList<>();
            for (Object l : ls) {
                List<Object> s = new ArrayList<>();
                for (Object r : (List<?>) l) s.add(((Relationship) r).properties().get("since"));
                sinces.add(s);
            }
            assertEquals(L(L(2020, 2021), L(2022, 2021)), sorted(sinces));
            assertEquals(L(2, 2), col(db, "MATCH (a {name: 'ann'})-[r:KNOWS*2]->(c {name: 'cat'}) RETURN size(r)"));
            assertEquals(L(L(2), L(2)), rows(db, "MATCH (a {name: 'ann'}), (c {name: 'cat'}) MATCH (a)-[r:KNOWS*2]->(c) RETURN size(r)"));
            assertEquals(manualPaths(p.g(), p.knows(), p.ann(), 1, 10).size(), rows(db, "MATCH (a {name: 'ann'})-[:KNOWS*]->(b) RETURN b").size());
        });
    }

    @Test
    void shortestPath() {
        withPeople(p -> {
            Bitcask db = p.db();
            List<Long> path = p.g().shortestPath(p.ann(), p.eve(), p.knows());
            List<Object> r = rows(db, "MATCH p = shortestPath((a {name: 'ann'})-[:KNOWS*]->(b {name: 'eve'})) RETURN length(p), nodes(p)").getFirst();
            assertEquals((long) (path.size() - 1), r.get(0));
            List<Object> ids = new ArrayList<>();
            for (Object n : (List<?>) r.get(1)) ids.add(((Node) n).id());
            assertEquals(path, ids);
            assertEquals(L(L(2)), rows(db, "MATCH p = shortestPath((b {name: 'eve'})<-[:KNOWS*]-(a {name: 'ann'})) RETURN length(p)"));
            assertEquals(L(L(1)), rows(db, "MATCH p = shortestPath((a {name: 'cat'})-[*]-(b {name: 'eve'})) RETURN length(p)"));
            assertEquals(L(), rows(db, "MATCH p = shortestPath((a {name: 'ann'})-[:KNOWS*..1]->(b {name: 'eve'})) RETURN p"));
            assertEquals(L(), rows(db, "MATCH p = shortestPath((a {name: 'eve'})-[:KNOWS*]->(b {name: 'ann'})) RETURN p"));
            List<Object> r2 = rows(db, "MATCH shortestPath((a {name: 'cat'})-[r:KNOWS|WORKS_AT*]->(c:Company {name: 'acme'})) RETURN size(r), r").getFirst();
            assertEquals(2L, r2.get(0));
            List<Object> types = new ArrayList<>();
            for (Object x : (List<?>) r2.get(1)) types.add(((Relationship) x).type());
            assertEquals(L("KNOWS", "WORKS_AT"), types);
        });
    }

    @Test
    void whereSemantics() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L("ann", "eve"), col(db, "MATCH (n:Person) WHERE n.age = 31 RETURN n.name ORDER BY n.name"));
            assertEquals(L("dan"), col(db, "MATCH (n:Person) WHERE n.city IS NULL RETURN n.name"));
            assertEquals(4, col(db, "MATCH (n:Person) WHERE n.city IS NOT NULL RETURN n").size());
            assertEquals(L(), col(db, "MATCH (n:Person) WHERE n.city = null RETURN n"));
            assertEquals(L("bob", "eve"), col(db, "MATCH (n:Person) WHERE n.city IN ['Berlin', 'Rome'] RETURN n.name ORDER BY n.name"));
            assertEquals(L("bob", "eve"), col(db, "MATCH (n:Person) WHERE n.city STARTS WITH 'Be' RETURN n.name ORDER BY n.name"));
            assertEquals(L("ann", "cat"), col(db, "MATCH (n:Person) WHERE n.city ENDS WITH 'is' RETURN n.name ORDER BY n.name"));
            assertEquals(L("eve"), col(db, "MATCH (n:Person) WHERE 'y' IN n.tags RETURN n.name"));
            assertEquals(L("ann", "bob"), col(db, "MATCH (n:Person) WHERE n.age > 20 AND n.age < 35 AND n.name <> 'eve' RETURN n.name ORDER BY n.name"));
            assertEquals(L("cat", "dan"), col(db, "MATCH (n:Person) WHERE n.age < 20 OR n.age > 35 RETURN n.name ORDER BY n.name"));
            assertEquals(L("dan"), col(db, "MATCH (n:Person) WHERE NOT (n.age >= 20) RETURN n.name"));
            assertEquals(L("ann-Paris"), col(db, "MATCH (n {name: 'ann'}) RETURN n.name + '-' + n.city"));
            assertEquals(L(L(7, 2.5, 1, 8.0, 2, true, null, null)),
                    rows(db, "RETURN 1 + 2 * 3, 5 / 2.0, 7 % 3, 2 ^ 3, 5 / 2, null IS NULL, null = null, 1 < 'a'"));
            assertEquals(L(L(true, false, null)), rows(db, "RETURN 1 IN [1, null], 2 IN [1], 2 IN [1, null]"));
            assertEquals(L(L(3, 1.5, "7", "abc", "ABC", 4, 2, null)),
                    rows(db, "RETURN toInteger('3'), toFloat('1.5'), toString(7), toLower('ABC'), toUpper('abc'), size('中文ab'), size([1, 2]), toInteger('x')"));
            // 运算符优先级
            assertEquals(L(L(true)), rows(db, "RETURN NOT 1 = 2"));
            assertEquals(L(L(4.0)), rows(db, "RETURN -2 ^ 2"));
            assertEquals(L(L(-1)), rows(db, "RETURN 1 - 1 - 1"));
            assertEquals(L(L(64.0)), rows(db, "RETURN 2 ^ 3 ^ 2"), "左结合（openCypher；D-35 前同 Erlang 版右结合）");
        });
    }

    @Test
    void aggregation() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L(L(5, 5, 2, 146.0, 29.2, 19, 40)),
                    rows(db, "MATCH (n:Person) RETURN count(*), count(n.age), count(DISTINCT n.city), sum(n.age), avg(n.age), min(n.age), max(n.age)"));
            assertEquals(L(L("Berlin", 2), L("Paris", 2), L(null, 1)), rows(db, "MATCH (n:Person) RETURN n.city AS city, count(*) AS c ORDER BY city"));
            assertEquals(L(L("acme", L("ann", "bob")), L("globex", L("cat"))),
                    rows(db, "MATCH (p:Person)-[:WORKS_AT]->(c:Company) WITH c.name AS company, p.name AS person ORDER BY person RETURN company, collect(person) AS people ORDER BY company"));
            assertEquals(L(L(0, null, L())), rows(db, "MATCH (n:Nobody) RETURN count(*), max(n.x), collect(n.x)"));
            assertEquals(L(), rows(db, "MATCH (n:Nobody) RETURN n.x, count(*)"));
            assertEquals(L(L("Berlin", 3), L("Paris", 3)),
                    rows(db, "MATCH (n:Person) WHERE n.city IS NOT NULL RETURN n.city AS city, count(*) + 1 AS c ORDER BY c DESC, city"));
            assertEquals(L(L(2)), rows(db, "MATCH (n:Person) WITH n.city AS city, count(*) AS c WHERE c > 1 AND city IS NOT NULL RETURN count(*)"));
            List<Object> r = rows(db, "MATCH (n:Person) WHERE n.age > 30 AND n.age < 32 RETURN n.age, count(*)").getFirst();
            assertEquals(31.0, ((Number) r.get(0)).doubleValue());
            assertEquals(2L, r.get(1));
        });
    }

    @Test
    void distinctOrderPaging() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L("Berlin", "Paris", null), col(db, "MATCH (n:Person) RETURN DISTINCT n.city ORDER BY n.city"));
            assertEquals(L(null, "Paris", "Berlin"), col(db, "MATCH (n:Person) RETURN DISTINCT n.city AS c ORDER BY c DESC"));
            assertEquals(L("bob", "cat"), col(db, "MATCH (n:Person) RETURN n.name ORDER BY n.name SKIP 1 LIMIT 2"));
            assertEquals(L("cat", "dan"), col(db, "MATCH (n:Person) RETURN n.name ORDER BY n.name SKIP $s LIMIT $l", M("s", 2, "l", 2)));
            assertEquals(L(), col(db, "MATCH (n:Person) RETURN n.name LIMIT 0"));
            assertEquals(L("dan", "bob"), col(db, "MATCH (n:Person) RETURN n.name ORDER BY n.age LIMIT 2"));
            assertEquals("exec", stage(() -> q(db, "MATCH (n) RETURN n LIMIT $l", M("l", -1))));
        });
    }

    @Test
    void unwindWithPipeline() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L(L(1, "a"), L(2, "a")), rows(db, "UNWIND $xs AS x RETURN x, 'a'", M("xs", L(1, 2))));
            assertEquals(L(), rows(db, "UNWIND null AS x RETURN x"));
            assertEquals(L(L(5)), rows(db, "UNWIND 5 AS x RETURN x"));
            assertEquals(L("bob", "dan"), col(db, "UNWIND ['ann'] AS name MATCH (a:Person {name: name})-[:KNOWS]->(b) RETURN DISTINCT b.name ORDER BY b.name"));
            assertEquals(L(L("ann", 2)), rows(db, "MATCH (a:Person)-[:KNOWS]->(b) WITH a, count(DISTINCT b) AS n WHERE n > 1 RETURN a.name, n"));
            assertEquals(L("ann", "bob", "cat", "dan", "eve"),
                    col(db, "MATCH (n:Person) WITH n ORDER BY n.name WITH collect(n.name) AS ns UNWIND ns AS x RETURN x"));
        });
    }

    @Test
    void functions() {
        withPeople(p -> {
            Bitcask db = p.db();
            List<Object> r = rows(db, "MATCH (c {name: 'cat'})-[r:KNOWS]->(a) RETURN labels(c), properties(r), keys(c), type(r), id(startNode(r)), id(endNode(r)), coalesce(c.nope, c.city, 'x')").getFirst();
            assertEquals(L("Person", "Admin"), r.get(0));
            assertEquals(M("since", 2019), r.get(1));
            assertEquals(L("age", "city", "name"), r.get(2));
            assertEquals("KNOWS", r.get(3));
            assertEquals(L(p.cat(), p.ann()), L(r.get(4), r.get(5)));
            assertEquals("Paris", r.get(6));
            assertEquals("exec", stage(() -> q(db, "RETURN 1 / 0")));
            assertEquals("semantic", stage(() -> q(db, "RETURN 'a' - 1")), "字面量类型不对：编译期（D-37）");
            assertEquals("exec", stage(() -> q(db, "WITH $s AS s RETURN s - 1", M("s", "a"))), "看不出来的：执行期");
            assertEquals("missing parameter(s): $x", message(() -> q(db, "RETURN $x")));
        });
    }

    @Test
    void params() {
        withPeople(p -> {
            Bitcask db = p.db();
            assertEquals(L(L("x", null, L(1, null), M("k", 1))), rows(db, "RETURN $s, $u, $l, $m", M("s", "x", "u", null, "l", L(1, null), "m", M("k", 1))));
            assertEquals(L("ann"), col(db, "MATCH (n:Person {name: $p.name, city: $p.city}) RETURN n.name", M("p", M("name", "ann", "city", "Paris"))));
            assertEquals("semantic", stage(() -> q(db, "MATCH (n:Person $p) RETURN n.name", M("p", M("name", "ann")))),
                    "D-37：MATCH 里不能用参数当属性 map（TCK）");
            Cypher.Prepared pr = Cypher.prepare("MATCH (n:Person {name: $n}) RETURN n.age");
            CypherResult r1 = Cypher.execute(db, pr, M("n", "bob"));
            assertEquals(List.of("n.age"), r1.columns());
            assertEquals(L(L(25)), r1.rows());
            assertEquals(Map.of(), r1.stats());
            CypherResult r2 = Cypher.execute(db, pr, M("n", "cat"));
            assertEquals(List.of("n.age"), r2.columns());
            assertEquals(L(L(40)), r2.rows());
            assertEquals(Map.of(), r2.stats());
        });
    }

    @Test
    void explainStartChoice() {
        assertEquals(clj("[:node-by-id 'n' [:param 'i']]"), pr(ops("MATCH (n:Person) WHERE id(n) = $i RETURN n").getFirst()));
        assertEquals(clj("[:node-by-id 'n' [:id-list [:list [[:lit 1] [:lit 2]]]]]"), pr(ops("MATCH (n) WHERE id(n) IN [1, 2] RETURN n").getFirst()));
        // 其余标签下推给引擎，原来的过滤照留；RETURN n 要文档 ⇒ fetch
        assertEquals(clj("[[:label-scan 'n' 'A' {:fetch true, :push [[:has-label 'B']]}] [:node-check 'n' ['B'] []]]"),
                pr(ops("MATCH (n:A:B) RETURN n").subList(0, 2)));
        assertEquals(clj("[:all-nodes-scan 'n' [[:cmp 'x' :eq [:lit 1]]]]"), pr(ops("MATCH (n {x: 1}) RETURN n").getFirst()));
        assertEquals(clj("[:all-nodes-scan 'n']"), pr(ops("MATCH (n) RETURN n").getFirst()));
        assertEquals(clj("[:label-scan 'n' 'A']"), pr(ops("MATCH (n:A) RETURN id(n), count(*)").getFirst()), "只用到 id 的标签扫描不取文档");
        assertEquals(clj("[:label-scan 'n' 'A' {:fetch true, :push [[:cmp 'k' :gt [:lit 3]] [:in 'c' [:list [[:lit 'x'] [:lit 'y']]]] [:exists 'z']]}]"),
                pr(ops("MATCH (n:A) WHERE 3 < n.k AND n.c IN ['x', 'y'] AND n.z IS NOT NULL AND n.text = 'a' AND n.k = n.j RETURN n").getFirst()),
                "比较翻转、IN、IS NOT NULL 下推；正文 / 向量、两边都是变量的不下推");
        assertEquals(clj("[:node-by-id 'n' [:param 'i'] :nocheck]"), pr(ops("MATCH (n)-[:T]->(m) WHERE id(n) = $i RETURN m").getFirst()),
                "id 起点紧跟一跳展开：存在性交给展开");
        List<Object> o1 = ops("MATCH (a)-[:T]->(b:B) RETURN a");
        assertEquals(clj("[:label-scan 'b' 'B']"), pr(o1.get(0)));
        List<?> e = (List<?>) o1.get(1);
        List<Object> e2 = new ArrayList<>(List.of(e.get(0), e.get(1)));
        e2.addAll(e.subList(3, e.size()));
        assertEquals(clj("[:expand 'b' ['T'] :in 'a' false [] false]"), pr(e2));
        assertEquals(List.of("label-scan", "filter", "expand"), heads(ops("MATCH (a:A)-->(b) WHERE a.x = 1 RETURN b").subList(0, 3)));
        List<Object> o2 = ops("MATCH (a:A) WITH a MATCH (b:B), (a)-->(b) RETURN b");
        assertEquals(List.of("label-scan", "project", "select", "expand", "node-check"), heads(o2.subList(0, 5)));
        assertEquals(clj("[:node-check 'b' ['B'] []]"), pr(o2.get(4)));
        List<Object> o3 = ops("MATCH (a:A), (b:B) MATCH (a)-->(b) RETURN b");
        assertEquals(List.of("label-scan", "label-scan", "expand"), heads(o3.subList(0, 3)));
        assertEquals(Boolean.TRUE, Clj.nth(o3.get(2), 6), "两端都已绑定 ⇒ 展开到");
        assertEquals(List.of("label-scan", "label-scan"), heads(ops("MATCH (a:A), (b:B) RETURN a, b").subList(0, 2)));
        List<Object> o4 = ops("MATCH (n:A) RETURN n ORDER BY n.x LIMIT 3");
        assertEquals(List.of("label-scan", "project", "sort", "limit", "select"), heads(o4));
        assertEquals("[:limit [:lit 3]]", pr(o4.get(3)));
    }

    @Test
    void limitStopsScanning() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            Graph g = Graph.open(db);
            for (int i = 0; i < 2000; i++) g.createVertex(VertexSpec.labels("L"));
            // clj 版用 with-redefs 数 model/label-vids 的调用次数（LIMIT 1 只取第一批）；Java 静态方法换不掉，只核对结果
            assertEquals(L(L(1)), rows(db, "MATCH (n:L) RETURN id(n) LIMIT 1"));
            assertEquals(L(L(2000)), rows(db, "MATCH (n:L) RETURN count(*)"));
        }
    }

    @Test
    void twoHopMatchesApi() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            Graph g = Graph.open(db);
            long t = g.registerEtype("E");
            Random rnd = new Random(789);
            List<Long> vs = new ArrayList<>();
            for (int i = 1; i < 201; i++) vs.add(g.createVertex(VertexSpec.of(List.of("V"), M("k", i % 5))));
            List<EdgeInput> es = new ArrayList<>();
            for (int i = 0; i < 800; i++) {
                long a = vs.get(rnd.nextInt(200));
                long b = vs.get(rnd.nextInt(200));
                es.add(EdgeInput.of(a, t, b));
            }
            g.putEdges(es);
            List<Object> manual = new ArrayList<>();
            for (long v : vs) {
                long c = 0;
                for (Edge e1 : g.outEdges(v, AdjQuery.etype(t)))
                    for (Edge e2 : g.outEdges(e1.dst(), AdjQuery.etype(t)))
                        if (!(v == e1.dst() && e1.dst() == e2.dst() && e1.rank() == e2.rank())) c++;
                if (c > 0) manual.add(L(v, c));
            }
            assertEquals(sorted(manual), sorted(new ArrayList<>(rows(db, "MATCH (a:V)-[:E]->(b)-[:E]->(c) RETURN id(a), count(*)"))));
            List<Object> k2 = new ArrayList<>();
            for (long v : vs) if (Long.valueOf(2).equals(g.vertexInfo(v).properties().get("k"))) k2.add(v);
            assertEquals(sorted(k2), sorted(col(db, "MATCH (n:V {k: 2}) RETURN id(n)")));
            okInv(g);
        }
    }

    @Test
    void predicatesAgreeWithDoubleNegation() {
        // 移植自 Erlang 的下推对照：参照查询把谓词包成 NOT (NOT (P))（三值逻辑下语义相同、不匹配任何下推形态）。
        // 数据混入整数 / 浮点 / 字符串 / 布尔 / 缺失、时而整数时而浮点的列。KV 与检索两种库各跑一遍。
        assumeNative();
        for (boolean index : new boolean[]{false, true}) {
            Path dir = d.resolve(index ? "ix" : "kv");
            try (Bitcask db = index ? Bitcask.openIndex(dir, Options.defaults().analyzer(Options.Analyzer.WHITESPACE)) : Bitcask.open(dir)) {
                Graph g = Graph.open(db);
                for (int i = 1; i < 901; i++) {
                    boolean a = i <= 600 ? i % 3 != 0 : i % 25 == 0;
                    Map<String, Object> props = M("k", i % 10, "f", (i % 10) + (i % 2 == 0 ? 0.0 : 0.5), "s", "x" + (i % 10), "b", i % 4 == 0,
                            "m", switch (i % 3) {
                                case 0 -> (long) (i % 5);
                                case 1 -> (double) (i % 5);
                                default -> "a";
                            });
                    if (i % 7 == 0) props.remove("k");
                    else props.put("z", 1L);
                    List<String> labels = new ArrayList<>();
                    if (a) {
                        labels.add("A");
                        if (i % 11 == 0) labels.add("B");
                    } else labels.add("B");
                    g.putVertex(i, VertexSpec.of(labels, props));
                }
                Map<String, Object> ps = M("p", 5, "ps", L(2, 3.0, "x"));
                java.util.function.Function<String, List<Object>> ids = s -> sorted(col(db, s, ps));
                for (String pd : List.of("n.k = 3", "n.k = 3.0", "n.f = 2", "n.f = 2.5", "n.f < 3", "n.f >= 2", "n.k < 2.5", "n.k > 7.0", "3 > n.k",
                        "n.k <= 4 AND n.k >= 4.0", "n.s = 'x3'", "n.s < 'x5'", "n.m = 1", "n.m = 2.0", "n.m IN [1, 2.0, 'a']",
                        "n.k IN [1, 9]", "n.z IS NOT NULL", "n.b = true", "n.b = false", "n:B", "n.k = $p", "n.k = null",
                        "n.k > $p AND n.s <> 'x9'", "n.k IN $ps", "n.k = 1 OR n.k = 2", "n.nope = 1")) {
                    assertEquals(ids.apply("MATCH (n:A) WHERE NOT (NOT (" + pd + ")) RETURN id(n)"),
                            ids.apply("MATCH (n:A) WHERE " + pd + " RETURN id(n)"), pd);
                    assertEquals(ids.apply("MATCH (n) WHERE NOT (NOT (" + pd + ")) RETURN id(n)"),
                            ids.apply("MATCH (n) WHERE " + pd + " RETURN id(n)"), "all: " + pd);
                }
                assertEquals(ids.apply("MATCH (n:A) WHERE NOT (NOT (n.k = 4 AND n:B)) RETURN id(n)"), ids.apply("MATCH (n:A:B {k: 4}) RETURN id(n)"));
                assertEquals(ids.apply("MATCH (n) WHERE NOT (NOT (n.k = 4)) RETURN id(n)"), ids.apply("MATCH (n {k: 4.0}) RETURN id(n)"));
                List<List<Object>> full = rows(db, "MATCH (n:A) WHERE n.k = 2 RETURN n ORDER BY id(n)");
                assertTrue(full.size() > 20);
                for (List<Object> r : full) {
                    Node n = (Node) r.getFirst();
                    assertEquals(vi(g, n.id()), n);
                }
                assertEquals(rows(db, "MATCH (n:A) WITH n, n.k AS k RETURN k, count(*) ORDER BY k"),
                        rows(db, "MATCH (n:A) WHERE NOT (NOT (n:A)) RETURN n.k, count(*) ORDER BY n.k"));
            }
        }
    }

    // ── 二期：OPTIONAL MATCH、CASE ─────────────────────────

    @Test
    void optionalMatch() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            q(db, "CREATE (a:P {n: 1})-[:T {w: 5}]->(b:P {n: 2})-[:T]->(c:P {n: 3}), (d:P {n: 4})");
            assertEquals(L(L(1, 2), L(2, 3), L(3, null), L(4, null)), rows(db, "MATCH (a:P) OPTIONAL MATCH (a)-[:T]->(b) RETURN a.n, b.n ORDER BY a.n"));
            assertEquals(L(L(1, 5, 3), L(2, null, null), L(3, null, null), L(4, null, null)),
                    rows(db, "MATCH (a:P) OPTIONAL MATCH (a)-[r:T]->()-[:T]->(c) RETURN a.n, r.w, c.n ORDER BY a.n"));
            assertEquals(L(L(1, null), L(2, 3), L(3, null), L(4, null)),
                    rows(db, "MATCH (a:P) OPTIONAL MATCH (a)-[:T]->(b) WHERE b.n > 2 RETURN a.n, b.n ORDER BY a.n"), "WHERE 属于可选模式");
            assertEquals(L(L(1, 1, L(2)), L(2, 1, L(3)), L(3, 0, L()), L(4, 0, L())),
                    rows(db, "MATCH (a:P) OPTIONAL MATCH (a)-[:T]->(b) RETURN a.n, count(b), collect(b.n) ORDER BY a.n"));
            assertEquals(L(L(1, 2), L(3, -1)), rows(db, "MATCH (a:P) WHERE a.n IN [1, 3] OPTIONAL MATCH (a)-[:T]->(b) RETURN a.n, coalesce(b.n, -1) ORDER BY a.n"));
            assertEquals(L(L((Object) null)), rows(db, "OPTIONAL MATCH (x:Nope) RETURN x"));
            assertEquals(L(L(0)), rows(db, "OPTIONAL MATCH (x:Nope) RETURN count(x)"));
            assertEquals(L(L(1, 3)), rows(db, "MATCH (a:P) OPTIONAL MATCH (a)-[:T]->(b) MATCH (b)-[:T]->(c) RETURN a.n, c.n"));
            assertEquals(L(L(1, null)), rows(db, "MATCH (a:P {n: 1}) OPTIONAL MATCH (a)-[:T]->(b:Nope) RETURN a.n, b"));
            List<Object> o = ops("MATCH (a:P) OPTIONAL MATCH (a)-[r:T]->(b) RETURN a, b");
            List<?> s = (List<?>) o.get(0), opt = (List<?>) o.get(1);
            assertEquals(clj("[:label-scan 'a' 'P']"), pr(s.subList(0, 3)));
            assertEquals(kw("optional"), opt.getFirst());
            List<?> inner = (List<?>) ((List<?>) opt.get(1)).getFirst();
            assertEquals(clj("[:expand 'a' 'r' ['T'] :out 'b' false]"), pr(inner.subList(0, 7)));
            assertEquals(List.of("r", "b"), opt.get(2));
            assertEquals(L(L(4, null)), rows(db, "MATCH (a:P {n: 4}) OPTIONAL MATCH (a)-->(b) SET a.seen = true RETURN a.n, b"), "写语句里");
        }
    }

    @Test
    void caseExpr() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            q(db, "UNWIND [1, 2, 3, 4] AS i CREATE (:P {n: i})");
            q(db, "CREATE (:P)");
            assertEquals(L(L(1, "small"), L(2, "small"), L(3, "big"), L(4, "big"), L(null, "none")),
                    rows(db, "MATCH (p:P) RETURN p.n, CASE WHEN p.n IS NULL THEN 'none' WHEN p.n > 2 THEN 'big' ELSE 'small' END ORDER BY p.n"));
            assertEquals(L(L(1, "one"), L(2, "two"), L(3, null), L(4, null), L(null, null)),
                    rows(db, "MATCH (p:P) RETURN p.n, CASE p.n WHEN 1 THEN 'one' WHEN 2.0 THEN 'two' END ORDER BY p.n"));
            assertEquals(L(L("else")), rows(db, "RETURN CASE WHEN null THEN 'x' ELSE 'else' END"));
            assertEquals(L(L(2, 3)), rows(db, "MATCH (p:P) RETURN sum(CASE WHEN p.n > 2 THEN 1 ELSE 0 END), count(CASE WHEN p.n < 4 THEN p END)"));
            assertEquals(L(L("many")), rows(db, "MATCH (p:P) WITH count(*) AS c RETURN CASE WHEN c > 3 THEN 'many' ELSE 'few' END"));
            assertEquals(L(L("big", 2), L("small", 2), L(null, 1)),
                    rows(db, "MATCH (p:P) RETURN CASE WHEN p.n > 2 THEN 'big' WHEN p.n > 0 THEN 'small' END AS g, count(*) ORDER BY g"));
            assertEquals(L(4, 3), col(db, "MATCH (p:P) WHERE CASE WHEN p.n > 2 THEN true ELSE false END RETURN p.n ORDER BY CASE p.n WHEN 4 THEN 0 ELSE 1 END"));
            assertEquals(L(L(1)), rows(db, "RETURN CASE WHEN true THEN 1 ELSE 1 / 0 END"), "只求值命中的分支");
            assertEquals(L(L(1)), rows(db, "RETURN CASE WHEN true THEN 1 WHEN 1 / 0 = 1 THEN 2 END"), "命中之后的条件也不求值");
            assertEquals(List.of("CASE WHEN true THEN 1 END"), q(db, "RETURN CASE WHEN true THEN 1 END").columns());
        }
    }

    // ── 二期其余：命名路径、allShortestPaths、标签表达式、排序、函数、CALL ───

    /** a(1) -T-> b(2) -T-> d(4)；a -T-> c(3) -T-> d；e(5) 孤立。 */
    static void phase2Graph(Bitcask db) {
        q(db, "CREATE (a:P:X {n: 1, s: 'hello world'})-[:T {w: 1}]->(b:P {n: 2})-[:T {w: 2}]->(d:Q {n: 4}), (a)-[:T {w: 3}]->(c:Q {n: 3})-[:T {w: 4}]->(d), (e:R {n: 5})");
    }

    static List<Object> props(Object nodesOrRels, String k) {
        List<Object> out = new ArrayList<>();
        for (Object x : (List<?>) nodesOrRels)
            out.add(x instanceof Node n ? n.properties().get(k) : ((Relationship) x).properties().get(k));
        return out;
    }

    @Test
    void namedPathAndAllShortest() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            phase2Graph(db);
            List<Object> got = new ArrayList<>();
            for (List<Object> r : rows(db, "MATCH p = (a {n: 1})-[:T]->(m)-[:T]->(z) RETURN length(p), nodes(p), relationships(p) ORDER BY m.n"))
                got.add(L(r.get(0), props(r.get(1), "n"), props(r.get(2), "w")));
            assertEquals(L(L(2, L(1, 2, 4), L(1, 2)), L(2, L(1, 3, 4), L(3, 4))), got);
            assertEquals(L(L(1, 2), L(1, 3), L(2, 4), L(2, 4)),
                    rows(db, "MATCH p = (a {n: 1})-[:T*1..2]->(c) RETURN length(p), last(nodes(p)).n ORDER BY length(p), last(nodes(p)).n"));
            List<Object> got2 = new ArrayList<>();
            for (List<Object> r : rows(db, "MATCH p = (d {n: 4})<-[:T]-()<-[:T]-(a) RETURN nodes(p) ORDER BY nodes(p)[1].n")) got2.add(props(r.get(0), "n"));
            assertEquals(L(L(4, 2, 1), L(4, 3, 1)), got2);
            assertEquals(L(L(3)), rows(db, "MATCH p = (a {n: 1})-[:T]->(m) WHERE nodes(p)[1].n > 2 RETURN m.n"));
            assertEquals(L(L(5, null)), rows(db, "MATCH (e {n: 5}) OPTIONAL MATCH p = (e)-[:T]->() RETURN e.n, p"));
            assertEquals(L(L(2, 2), L(2, 3)),
                    rows(db, "MATCH (a {n: 1}), (d {n: 4}) MATCH p = allShortestPaths((a)-[:T*]->(d)) RETURN length(p), nodes(p)[1].n ORDER BY nodes(p)[1].n"));
            assertEquals(L(L(2)), rows(db, "MATCH (a {n: 1}), (d {n: 4}) MATCH p = shortestPath((a)-[:T*]->(d)) RETURN length(p)"));
            assertEquals(L(), rows(db, "MATCH (a {n: 1}), (e {n: 5}) MATCH p = allShortestPaths((a)-[:T*]->(e)) RETURN p"));
            assertEquals(L(), rows(db, "MATCH (a {n: 1}), (d {n: 4}) MATCH p = allShortestPaths((a)-[:T*3..]->(d)) RETURN p"));
            assertEquals(L(L(1)), rows(db, "MATCH (a {n: 1}), (b {n: 2}) MATCH p = allShortestPaths((a)-[:T*]-(b)) RETURN length(p)"));
        }
    }

    @Test
    void labelExprAndOrdering() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            phase2Graph(db);
            assertEquals(L(1, 2, 3, 4), col(db, "MATCH (n:P|Q) RETURN n.n ORDER BY n.n"));
            assertEquals(L(2), col(db, "MATCH (n:P:!X) RETURN n.n"));
            assertEquals(L(3, 4, 5), col(db, "MATCH (n:!P) RETURN n.n ORDER BY n.n"));
            assertEquals(L(3, 4, 5), col(db, "MATCH (n) WHERE n:Q|R RETURN n.n ORDER BY n.n"));
            assertEquals(L(1), col(db, "MATCH (n) WHERE n:P AND NOT n:!X RETURN n.n"));
            assertEquals(L(3), col(db, "MATCH (:P {n: 1})-[:T]->(m:Q|R) RETURN m.n"));
            assertEquals(col(db, "MATCH (n) WHERE NOT (NOT (n:Q|X)) AND n.n > 0 RETURN n.n ORDER BY n.n"),
                    col(db, "MATCH (n:Q|X) WHERE n.n > 0 RETURN n.n ORDER BY n.n"), "A|B 下推与不下推一致");
            List<?> s = (List<?>) ops("MATCH (n:Q|X) WHERE n.n > 0 RETURN n").getFirst();
            assertEquals(clj("[:all-nodes-scan 'n']"), pr(s.subList(0, 2)));
            assertEquals(clj("[:has-any-label ['Q' 'X']]"), pr(((List<?>) s.get(2)).getFirst()));
            q(db, "CREATE (:S {a: 1, b: null}), (:S {a: 1, b: 2}), (:S {a: null, b: 1})");
            // D-42 恢复（同 bitcask：来自 SQL / GQL）
            assertEquals(L(L(null, 1), L(1, null), L(1, 2)), rows(db, "MATCH (s:S) RETURN s.a, s.b ORDER BY s.a NULLS FIRST, s.b NULLS FIRST"));
            assertEquals(L(L(1, 2), L(1, null), L(null, 1)), rows(db, "MATCH (s:S) RETURN s.a, s.b ORDER BY s.a DESC NULLS LAST, s.b DESC NULLS LAST"));
            assertEquals(L(L(null, 1), L(1, 2), L(1, null)), rows(db, "MATCH (s:S) RETURN s.a, s.b ORDER BY s.a DESC, s.b ASC"));
        }
    }

    @Test
    void phase2Functions() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            assertEquals(L(true, false, true, null), CypherTestSupport.one(db, "RETURN ['hello' =~ 'h.*o', 'hello' =~ 'ell', 'Hello' =~ '(?i)hello', null =~ 'x']"));
            assertEquals("exec", stage(() -> q(db, "RETURN 'a' =~ '('")));
            assertEquals(L("ell", "llo", "", "a+b+c", L("a", "b", "", "c"), L("中", "文")),
                    CypherTestSupport.one(db, "RETURN [substring('hello', 1, 3), substring('hello', 2), substring('hi', 5), replace('a-b-c', '-', '+'), split('a,b,,c', ','), split('中文', '')]"));
            assertEquals(L("x", "x  ", "  x", "he", "lo", "文中"),
                    CypherTestSupport.one(db, "RETURN [trim('  x  '), ltrim('  x  '), rtrim('  x  '), left('hello', 2), right('hello', 2), reverse('中文')]"));
            assertEquals(L(null, null, null), CypherTestSupport.one(db, "RETURN [substring(null, 1), replace('a', null, 'b'), left(null, 1)]"));
            assertEquals("exec", stage(() -> q(db, "RETURN substring('abc', -1)")));
            assertEquals(L(1, L(2, 3), 3, null, L(), null, L(3, 2, 1)),
                    CypherTestSupport.one(db, "RETURN [head([1, 2, 3]), tail([1, 2, 3]), last([1, 2, 3]), head([]), tail([]), last([]), reverse([1, 2, 3])]"));
            assertEquals(L(L(1, 2, 3), L(0, 3, 6, 9), L(5, 3, 1), L()),
                    CypherTestSupport.one(db, "RETURN [range(1, 3), range(0, 10, 3), range(5, 1, -2), range(1, 0)]"));
            assertEquals("exec", stage(() -> q(db, "RETURN range(1, 5, 0)")));
            assertEquals(L(1, 4, null, L(2, 3), L(1, 2), L(3, 4), L(), 7, null),
                    CypherTestSupport.one(db, "RETURN [[1, 2, 3, 4][0], [1, 2, 3, 4][-1], [1, 2, 3, 4][9], [1, 2, 3, 4][1..3], [1, 2, 3, 4][..2], [1, 2, 3, 4][-2..], [1, 2][5..], {a: 7}['a'], [1][null]]"));
            assertEquals(L(L(2)), rows(db, "RETURN [1, 2, 3][$i]", M("i", 1)));
            q(db, "CREATE (:P {name: 'x', tags: ['a', 'b']})");
            assertEquals(L("x", "b", "P"), CypherTestSupport.one(db, "MATCH (n:P) RETURN [n['name'], n.tags[-1], labels(n)[0]]"));
            assertEquals("exec", stage(() -> q(db, "RETURN [1, 2]['a']")));
        }
    }

    @Test
    void procedures() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            assertEquals(L(), rows(db, "CALL db.labels()"));
            phase2Graph(db);
            assertEquals(L(L("P"), L("Q"), L("R"), L("X")), rows(db, "CALL db.labels()"));
            assertEquals(List.of("label"), q(db, "CALL db.labels()").columns());
            assertEquals(L(L("T")), rows(db, "CALL db.relationshipTypes()"));
            assertEquals(L(L("n"), L("s")), rows(db, "CALL db.propertyKeys()"));
            assertEquals(L(L(L("P", "Q", "R"))), rows(db, "CALL db.labels() YIELD label AS l WHERE l <> 'X' RETURN collect(l)"));
            assertEquals(L(L("P", 2), L("Q", 2), L("R", 1), L("X", 1)),
                    rows(db, "CALL db.labels() YIELD label MATCH (n) WHERE label IN labels(n) RETURN label, count(n) ORDER BY label"));
            q(db, "MATCH (e:R) DETACH DELETE e");
            assertEquals(L(L("P"), L("Q"), L("X")), rows(db, "CALL db.labels()"), "标签没有顶点了就不再列出");
        }
    }

    @Test
    void streamingCursor() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            q(db, "UNWIND range(1, 1000) AS i CREATE (:P {n: i})");
            String s = "MATCH (p:P) WHERE p.n % 3 = 0 RETURN p.n";
            List<List<Object>> all = rows(db, s);
            CypherCursor cur = Cypher.cursor(db, s, null);
            assertEquals(List.of("p.n"), cur.columns());
            List<List<Object>> acc = new ArrayList<>();
            while (true) {
                CypherCursor.Batch b = cur.fetch(50);
                acc.addAll(b.rows());
                if (!b.more()) break;
                assertEquals(50, b.rows().size());
            }
            assertEquals(all, acc);
            assertEquals("cursor closed", message(() -> cur.fetch(1)), "取完自动关闭");
            cur.close();
            cur.close(); // close 幂等
            try (CypherCursor c2 = Cypher.cursor(db, "MATCH (p:P) RETURN p.n ORDER BY p.n", null)) {
                CypherCursor.Batch b = c2.fetch(2);
                assertEquals(L(L(1), L(2)), b.rows());
                assertTrue(b.more());
            }
            // clj 版传 prepare 过的语句；Java 的 cursor 只收文本（内部同样经 prepare 缓存）
            CypherCursor c3 = Cypher.cursor(db, "MATCH (p:P) WHERE p.n > $m RETURN count(*)", M("m", 990));
            CypherCursor.Batch b3 = c3.fetch(10);
            assertEquals(L(L(10)), b3.rows());
            assertFalse(b3.more());
            // clj 版还用 with-redefs 数标签索引的读取批次（按需推进）；Java 换不掉静态方法，只核对取 3 行可行
            try (CypherCursor c4 = Cypher.cursor(db, "MATCH (p:P) RETURN id(p)", null)) {
                assertEquals(3, c4.fetch(3).rows().size());
            }
            assertEquals("cursors are only for read-only queries", message(() -> Cypher.cursor(db, "CREATE (:X)", null)));
            assertTrue(message(() -> Cypher.cursor(db, "RETURN $x", null)).startsWith("missing parameter"));
            CypherCursor c5 = Cypher.cursor(db, "RETURN 1 / 0", null);
            assertEquals("exec", stage(() -> c5.fetch(1)));
        }
    }
}
