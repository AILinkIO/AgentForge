package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.L;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.M;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.q;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.stage;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;

/**
 * 按 openCypher 2024.3 文法（grammar/openCypher.bnf）逐条产生式探测（D-40；移植自 clj-bitcask cypher_grammar_test.clj）。
 * 每条：[产生式 查询 期望]，期望是行（核对结果）或 OK（能解析、执行不出错）；最后一组是文法里没有、也不再接受的写法。
 */
class CypherGrammarTest {
    @TempDir
    Path d;

    static final Object OK = new Object() {
        @Override
        public String toString() {
            return ":ok";
        }
    };

    static final String SETUP = """
            CREATE (a:A:P {name: 'a', n: 1})-[:T {w: 1}]->(b:B:P {name: 'b', n: 2})-[:T {w: 2}]->(c:C {name: 'c', n: 3}),
                      (a)-[:U {w: 5}]->(c), (c)-[:T {w: 3}]->(d:D {name: 'd', n: 4})""";

    static final Object[][] CASES = {
            // ── <program> / <statement> ──
            {"<composite statement> UNION", "RETURN 1 AS x UNION RETURN 1 AS x", L(L(1))},
            {"<composite conjunction> UNION ALL", "RETURN 1 AS x UNION ALL RETURN 1 AS x", L(L(1), L(1))},
            {"<composite conjunction> UNION DISTINCT", "RETURN 1 AS x UNION DISTINCT RETURN 1 AS x", L(L(1))},
            {"<standalone procedure call>", "CALL db.labels", OK},
            {"<standalone procedure call> ()", "CALL db.labels()", OK},
            {"<standalone procedure call yield clause> *", "CALL db.labels() YIELD *", OK},
            {"<standalone procedure call yield clause> items", "CALL db.labels() YIELD label AS l", OK},
            {"<linear statement> 无结果语句", "MATCH (n:A) SET n.seen = true", OK},
            {"<linear statement> 多个子句", "MATCH (n:A) WITH n UNWIND [1] AS i RETURN n.name, i", L(L("a", 1))},
            // ── 查询语句 ──
            {"<simple match statement>", "MATCH (n:A) RETURN n.name", L(L("a"))},
            {"<optional match statement>", "MATCH (n:A) OPTIONAL MATCH (n)-[:NONE]->(m) RETURN n.name, m", L(L("a", null))},
            {"<unwind statement>", "UNWIND [1, 2] AS x RETURN x", L(L(1), L(2))},
            {"<with statement>", "MATCH (n:P) WITH n.name AS nm ORDER BY nm DESC SKIP 1 LIMIT 1 WHERE nm IS NOT NULL RETURN nm", L(L("a"))},
            {"<with statement> DISTINCT", "UNWIND [1, 1] AS x WITH DISTINCT x RETURN x", L(L(1))},
            {"<with statement> *", "WITH 1 AS a WITH * RETURN a", L(L(1))},
            // ── 更新语句 ──
            {"<create statement> 节点", "CREATE (), (:X), (x:X:Y {k: 1}), (:X $p) RETURN 1", L(L(1))},
            {"<create path pattern> 关系 →", "CREATE (a:X)-[:R {k: 1}]->(b:X) RETURN 1", L(L(1))},
            {"<create relationship pointing left> ←", "CREATE (a:X)<-[r:R $p]-(b:X) RETURN type(r)", L(L("R"))},
            {"<create path pattern> 路径变量", "CREATE p = (:X)-[:R]->(:X) RETURN length(p)", L(L(1))},
            {"<merge statement>", "MERGE (m:M {k: 1}) RETURN m.k", L(L(1))},
            {"<merge action> ON CREATE / ON MATCH", "MERGE (m:M {k: 2}) ON CREATE SET m.c = 1 ON MATCH SET m.c = 2 RETURN m.c", L(L(1))},
            {"<merge path pattern> 关系", "MATCH (a:A), (b:B) MERGE (a)-[r:T]->(b) RETURN type(r)", L(L("T"))},
            {"<merge relationship pointing left>", "MATCH (a:A), (b:B) MERGE (b)<-[r:T]-(a) RETURN type(r)", L(L("T"))},
            {"<merge path pattern> 路径变量", "MERGE p = (:M2 {k: 1})-[:R]->(:M2 {k: 2}) RETURN length(p)", L(L(1))},
            {"<set all properties item>", "MATCH (n:A) SET n = {name: 'a', n: 1} RETURN n.n", L(L(1))},
            {"<add all properties item>", "MATCH (n:A) SET n += {z: 1} RETURN n.z", L(L(1))},
            {"<set labels item>", "MATCH (n:A) SET n:L1:L2 RETURN n:L1 AND n:L2", L(L(true))},
            {"<set property item> 变量", "MATCH (n:A) SET n.k = 1 RETURN n.k", L(L(1))},
            {"<set property item> 后缀表达式", "MATCH (n:A) SET (n).k = 2 RETURN n.k", L(L(2))},
            {"<remove labels item>", "MATCH (n:A) REMOVE n:L1:L2 RETURN n:L1", L(L(false))},
            {"<remove property item>", "MATCH (n:A) REMOVE n.k RETURN n.k", L(L((Object) null))},
            {"<delete statement>", "CREATE (x:Del) WITH x DELETE x RETURN 1", L(L(1))},
            {"<delete statement> DETACH 多项", "CREATE (x:Del)-[:R]->(y:Del) WITH x, y DETACH DELETE x, y RETURN 1", L(L(1))},
            // ── CALL ──
            {"<call procedure statement> + <yield clause> WHERE", "CALL db.labels() YIELD label AS l WHERE l = 'A' RETURN l", L(L("A"))},
            // ── RETURN ──
            {"<return statement> DISTINCT", "UNWIND [1, 1, 2] AS x RETURN DISTINCT x ORDER BY x", L(L(1), L(2))},
            {"<return item list> * 与项", "WITH 1 AS a RETURN *, 2 AS b", L(L(1, 2))},
            {"<return item alias> 关键字名字", "RETURN 1 AS limit", L(L(1))},
            {"<ascending order> ASC / ASCENDING", "UNWIND [2, 1] AS x RETURN x ORDER BY x ASCENDING", L(L(1), L(2))},
            {"<descending order> DESC / DESCENDING", "UNWIND [1, 2] AS x RETURN x ORDER BY x DESCENDING", L(L(2), L(1))},
            {"<offset synonym> SKIP", "UNWIND [1, 2, 3] AS x RETURN x SKIP 1 LIMIT 1", L(L(2))},
            {"<offset synonym> OFFSET", "UNWIND [1, 2, 3] AS x RETURN x OFFSET 2", L(L(3))},
            {"<order by and page clause> 只有 LIMIT", "UNWIND [1, 2, 3] AS x RETURN x LIMIT 1", L(L(1))},
            // ── 图模式 ──
            {"<path pattern list> + <graph pattern where clause>", "MATCH (a:A), (b:B) WHERE a.n < b.n RETURN a.name, b.name", L(L("a", "b"))},
            {"<path variable declaration>", "MATCH p = (:A)-[:T]->(:B) RETURN length(p)", L(L(1))},
            {"<all path search> ALL", "MATCH ALL (:A)-[:T*]->(x:C) RETURN count(*)", L(L(1))},
            {"<all path search> ALL PATHS", "MATCH p = ALL PATHS (:A)-[*]->(x:C) RETURN count(p)", L(L(2))},
            {"<any path search> ANY", "MATCH p = ANY (:A)-[*]->(x:C) RETURN count(p)", L(L(1))},
            {"<any path search> ANY k PATHS", "MATCH p = ANY 2 PATHS (:A)-[*]->(x:C) RETURN count(p)", L(L(2))},
            {"<any shortest path search>", "MATCH p = ANY SHORTEST (:A)-[*]->(x:C) RETURN length(p)", L(L(1))},
            {"<all shortest path search>", "MATCH p = ALL SHORTEST PATH (:A)-[*]->(x:C) RETURN length(p)", L(L(1))},
            {"<counted shortest path search>", "MATCH p = SHORTEST 2 PATHS (:A)-[*]->(x:C) RETURN length(p) ORDER BY length(p)", L(L(1), L(2))},
            {"<counted shortest group search>", "MATCH p = SHORTEST 1 GROUP (:A)-[*]->(x:C) RETURN length(p)", L(L(1))},
            {"<counted shortest group search> 不写数", "MATCH p = SHORTEST GROUPS (:A)-[*]->(x:D) RETURN length(p)", L(L(2))},
            {"<number of paths> 参数", "MATCH p = SHORTEST $k (:A)-[*]->(x:C) RETURN count(p)", L(L(2))},
            {"<legacy shortest path pattern> SHORTESTPATH", "MATCH p = shortestPath((a:A)-[*]->(d:D)) RETURN length(p)", L(L(2))},
            {"<legacy shortest path pattern> ALLSHORTESTPATHS", "MATCH p = allShortestPaths((a:A)-[*]->(d:D)) RETURN length(p)", L(L(2))},
            {"<quantified path primary> {m,n}", "MATCH (:A) ((x)-[:T]->(y)){1,2} (z) RETURN z.name ORDER BY z.name", L(L("b"), L("c"))},
            {"<graph pattern quantifier> +", "MATCH (:A) ((x)-[:T]->(y))+ (z) RETURN count(z)", L(L(3))},
            {"<graph pattern quantifier> *", "MATCH (:A) ((x)-[:T]->(y))* (z) RETURN count(z)", L(L(4))},
            {"<fixed quantifier> {k}", "MATCH (:A) ((x)-[:T]->(y)){2} (z) RETURN z.name", L(L("c"))},
            {"<general quantifier> {,n}", "MATCH (:A) ((x)-[:T]->(y)){,1} (z) RETURN count(z)", L(L(2))},
            {"<general quantifier> {m,}", "MATCH (:A) ((x)-[:T]->(y)){3,} (z) RETURN z.name", L(L("d"))},
            {"<parenthesized path pattern where clause>", "MATCH (:A) ((x)-[r:T]->(y) WHERE r.w > 1){1,3} (z) RETURN count(z)", L(L(0))},
            {"<quantified path primary> 关系", "MATCH (:A)-[r:T]->{2}(z) RETURN z.name, size(r)", L(L("c", 2))},
            {"组变量", "MATCH (:A) ((x)-[:T]->(y)){3} (z) RETURN [v IN y | v.name]", L(L(L("b", "c", "d")))},
            // D-41：量化路径通用化、括号路径、并排节点、子路径变量
            {"<path factor> 嵌套量化路径", "MATCH (:A) ((x) ((p)-[:T]->(q)){2} (y)){1} (z) RETURN z.name, [l IN q | size(l)]", L(L("c", L(2)))},
            {"<quantified path primary> 子模式里的变长关系", "MATCH (:A) ((x)-[r:T*1..2]->(y)){1} (z:C) RETURN [l IN r | size(l)]", L(L(L(2)))},
            {"<subpath variable declaration> 量化", "MATCH (:A) (p = (x)-[:T]->(y)){2} (z) RETURN [q IN p | length(q)]", L(L(L(1, 1)))},
            {"<subpath variable declaration> 不带量词", "MATCH (a:A) (q = (a)-[:T]->(b) WHERE b.n > 0) RETURN length(q), b.name", L(L(1, "b"))},
            {"<parenthesized path pattern expression> 不带量词", "MATCH ((a:A)-[:T]->(b) WHERE b.n = 2) RETURN b.name", L(L("b"))},
            {"<path term> 并排（同一变量）", "MATCH (:A)-[:T]->(b) (b)-[:T]->(c) RETURN c.name", L(L("c"))},
            {"<path term> 并排（不同变量 = 别名）", "MATCH (:A)-[:T]->(b) (x)-[:T]->(c) RETURN x.name, b = x", L(L("b", true))},
            {"<is labeled predicate part 2> 任意表达式", "MATCH p = (:A)-[:T]->(:B) RETURN head(nodes(p)) IS A, (last(nodes(p))):B&!A", L(L(true, true))},
            {"<element pattern where clause> 变长关系", "MATCH (:A)-[r:T* WHERE size(r) = 2]->(x) RETURN x.name", L(L("c"))},
            {"<path search prefix> 图模式的 WHERE 在挑选之后", "MATCH p = ANY SHORTEST (:A)-[*]->(x:C) WHERE length(p) > 1 RETURN count(*)", L(L(0))},
            {"<path search prefix> 元素谓词在挑选之前", "MATCH p = ANY SHORTEST (:A)-[r* WHERE all(e IN r WHERE type(e) = 'T')]->(x:C) RETURN length(p)", L(L(2))},
            {"<node pattern> 空", "MATCH () RETURN count(*) > 0", L(L(true))},
            {"<node pattern filler> 变量 + 标签 + 属性", "MATCH (n:A {n: 1}) RETURN n.name", L(L("a"))},
            {"<node pattern filler> 变量 + 标签 + WHERE", "MATCH (n:P WHERE n.n > 1) RETURN n.name", L(L("b"))},
            {"<node pattern filler> 只有标签", "MATCH (:B) RETURN count(*)", L(L(1))},
            {"<node pattern filler> 只有属性", "MATCH ({name: 'c'}) RETURN count(*)", L(L(1))},
            {"<element pattern where clause> 只有 WHERE", "MATCH (WHERE true) RETURN count(*) > 0", L(L(true))},
            {"<full relationship pointing right>", "MATCH (:A)-[:T]->(x) RETURN x.name", L(L("b"))},
            {"<full relationship pointing left>", "MATCH (x)<-[:T]-(:A) RETURN x.name", L(L("b"))},
            {"<full relationship left or right>", "MATCH (:B)<-[:T]->(x) RETURN x.name ORDER BY x.name", L(L("a"), L("c"))},
            {"<full relationship any direction>", "MATCH (:B)-[:T]-(x) RETURN x.name ORDER BY x.name", L(L("a"), L("c"))},
            {"<full relationship *> 不带方括号", "MATCH (:A)-->(x)<--(y) RETURN count(*)", OK},
            {"<full relationship *> --", "MATCH (:B)--(x) RETURN count(*)", L(L(2))},
            {"<full relationship *> <-->", "MATCH (:B)<-->(x) RETURN count(*)", L(L(2))},
            {"<relationship pattern filler> 变量 + 标签 + 属性", "MATCH ()-[r:T {w: 2}]->() RETURN r.w", L(L(2))},
            {"<relationship pattern filler> WHERE", "MATCH ()-[r:T WHERE r.w > 2]->() RETURN r.w", L(L(3))},
            {"<relationship pattern filler> 只有谓词", "MATCH ()-[{w: 5}]->(x) RETURN x.name", L(L("c"))},
            {"<path length> *", "MATCH (:A)-[*]->(x:D) RETURN count(*)", L(L(2))},
            {"<fixed path length> *k", "MATCH (:A)-[*2]->(x) RETURN x.name ORDER BY x.name", L(L("c"), L("d"))},
            {"<lower and upper bound path length> *m..n", "MATCH (:A)-[*1..2]->(x:C) RETURN count(*)", L(L(2))},
            {"<lower and upper bound path length> *..n", "MATCH (:A)-[*..1]->(x) RETURN count(*)", L(L(2))},
            {"<lower and upper bound path length> *m..", "MATCH (:A)-[*3..]->(x) RETURN x.name", L(L("d"))},
            // ── 标签表达式 ──
            {"<node label expression legacy> :A:B", "MATCH (n:A:P) RETURN n.name", L(L("a"))},
            {"<is label expression> IS", "MATCH (n IS B) RETURN n.name", L(L("b"))},
            {"<label expression> |", "MATCH (n:A|B) RETURN count(n)", L(L(2))},
            {"<label term> &", "MATCH (n:P&B) RETURN n.name", L(L("b"))},
            {"<label negation> !", "MATCH (n:P&!A) RETURN n.name", L(L("b"))},
            {"<parenthesized label expression>", "MATCH (n:(A|C)&!P) RETURN n.name", L(L("c"))},
            {"<wildcard label> %", "MATCH (n:%) RETURN count(n) > 0", L(L(true))},
            {"<relationship label expression legacy> :A|:B", "MATCH ()-[r:T|:U]->() RETURN count(r)", L(L(4))},
            {"<is relationship label expression> IS + !", "MATCH ()-[r IS !T]->() RETURN type(r)", L(L("U"))},
            {"<is labeled predicate part 2> n:A / n IS A", "MATCH (n:P) RETURN n.name, n:A, n IS B ORDER BY n.name", L(L("a", true, false), L("b", false, true))},
            // ── 表达式 ──
            {"<boolean value expression> OR / XOR / AND / NOT NOT", "RETURN true OR false, true XOR true, true AND NOT NOT true", L(L(true, false, true))},
            {"<comparison predicate> 链", "RETURN 1 < 2 <= 2 <> 3", L(L(true))},
            {"<simple comp op>", "RETURN 1 = 1, 1 <> 2, 1 < 2, 2 > 1, 1 <= 1, 1 >= 1", L(L(true, true, true, true, true, true))},
            {"<advanced comp op>", "RETURN 'ab' CONTAINS 'b', 1 IN [1], 'ab' =~ 'a.', 'ab' STARTS WITH 'a', 'ab' ENDS WITH 'b'", L(L(true, true, true, true, true))},
            {"<null predicate part 2>", "RETURN null IS NULL, 1 IS NOT NULL", L(L(true, true))},
            {"<arithmetic value expression>", "RETURN 1 + 2 - 3, 2 * 3 / 2 % 2, 2 ^ 3", L(L(0, 1, 8.0))},
            {"<arithmetic unary> + / -", "RETURN -(-1), +1", L(L(1, 1))},
            {"<static property reference>", "WITH {a: {b: 1}} AS m RETURN m.a.b", L(L(1))},
            {"<dynamic element reference>", "WITH {a: 1} AS m, [1, 2] AS l RETURN m['a'], l[1]", L(L(1, 2))},
            {"<slicing>", "WITH [1, 2, 3] AS l RETURN l[1..], l[..1], l[0..2]", L(L(L(2, 3), L(1), L(1, 2)))},
            {"<parenthesized value expression>", "RETURN (1 + 2) * 3", L(L(9))},
            {"<general parameter reference>", "RETURN $k, $`k`", L(L(2, 2))},
            {"<simple case> 多个 WHEN 值", "UNWIND [1, 2, 3] AS x RETURN CASE x WHEN 1, 2 THEN 'a' ELSE 'b' END", L(L("a"), L("a"), L("b"))},
            {"<search case>", "RETURN CASE WHEN false THEN 1 WHEN true THEN 2 END", L(L(2))},
            {"<count star>", "MATCH (n:P) RETURN count(*)", L(L(2))},
            {"<exists expression> 图模式", "MATCH (n:P) WHERE EXISTS { (n)-[:U]->() } RETURN n.name", L(L("a"))},
            {"<exists expression> 图模式 + WHERE", "MATCH (n:P) WHERE EXISTS { (n)-->(m) WHERE m.n > 2 } RETURN n.name ORDER BY n.name", L(L("a"), L("b"))},
            {"<exists expression> 子查询", "MATCH (n:P) WHERE EXISTS { MATCH (n)-->(m:C) RETURN m } RETURN n.name ORDER BY n.name", L(L("a"), L("b"))},
            {"<map projection>", "MATCH (n:A) WITH n, 1 AS v RETURN n {.name, k: 2, v, .*}['k']", L(L(2))},
            {"<list comprehension> 只有源", "RETURN [x IN [1, 2]]", L(L(L(1, 2)))},
            {"<list comprehension> 过滤 + 投影", "RETURN [x IN [1, 2, 3] WHERE x > 1 | x * 10]", L(L(L(20, 30)))},
            {"<list comprehension> 只有投影", "RETURN [x IN [1, 2] | x + 1]", L(L(L(2, 3)))},
            {"<pattern comprehension>", "MATCH (a:A) RETURN [p = (a)-[:T]->(x) WHERE x.n > 1 | length(p)]", L(L(L(1)))},
            {"<reduce expression>", "RETURN reduce(s = 0, x IN [1, 2, 3] | s + x)", L(L(6))},
            {"<quantifier expression>", "RETURN all(x IN [1] WHERE x > 0), any(x IN [1] WHERE x > 1), none(x IN [1] WHERE x > 1), single(x IN [1] WHERE x = 1)", L(L(true, false, true, true))},
            {"<trim function>", "RETURN trim('  a  ')", L(L("a"))},
            {"<function invocation> DISTINCT / ALL", "UNWIND [1, 1] AS x RETURN count(DISTINCT x), count(ALL x)", L(L(1, 2))},
            {"<function reference> 命名空间", "RETURN date.truncate('year', date('2020-05-17')) = date('2020-01-01')", L(L(true))},
            {"<shortest path expression>", "MATCH (a:A), (d:D) RETURN length(shortestPath((a)-[*]->(d)))", L(L(2))},
            {"<pattern expression> 谓词", "MATCH (n:P) WHERE (n)-[:U]->() RETURN n.name", L(L("a"))},
            {"<list value constructor> / <map value constructor>", "RETURN [1, 'a', [2]], {a: 1, b: [2]}", L(L(L(1, "a", L(2)), M("a", 1, "b", L(2))))},
            // ── 字面量 ──
            {"<signed numeric literal> INF / INFINITY / NAN", "RETURN INF > 1e308, -INFINITY < 0, NAN <> NAN", L(L(true, true, true))},
            {"<boolean literal> / <null literal>", "RETURN TRUE, FALSE, NULL", L(L(true, false, null))},
            {"<single quoted character sequence> 转义", "RETURN 'a\\tb', 'it''s', '\\\\', '\\u00e9', '\\U01F600'", L(L("a\tb", "it's", "\\", "é", "😀"))},
            {"<double quoted character sequence>", "RETURN \"say \"\"hi\"\"\", \"\\\"\"", L(L("say \"hi\"", "\""))},
            {"<unsigned decimal integer> 下划线", "RETURN 1_000_000", L(L(1000000))},
            {"<unsigned hexadecimal integer> 0x / 0X", "RETURN 0xff, 0X1_F", L(L(255, 31))},
            {"<unsigned octal integer>", "RETURN 0o17", L(L(15))},
            {"<approximate numeric literal>", "RETURN 1.5, .5, 1e3, 1.5E-1", L(L(1.5, 0.5, 1000.0, 0.15))},
            {"<approximate number suffix> F / D", "RETURN 1.5f, 2e1D, .5d", L(L(1.5, 20.0, 0.5))},
            {"<list literal> / <map literal>", "RETURN [], {}", L(L(L(), M()))},
            // ── 名字 ──
            {"<delimited identifier> 转义", "WITH 1 AS `a``b`, 2 AS `c\\`d` RETURN `a``b` + `c\\`d`", L(L(3))},
            {"<non-reserved word> 关键字当名字", "WITH 1 AS match, 2 AS order, 3 AS limit RETURN match + order + limit", L(L(6))},
            {"<regular identifier> Unicode", "WITH 1 AS 名字 RETURN 名字", L(L(1))},
            {"<parameter name> 以数字开头", "RETURN $1a", L(L(3))},
            {"<semicolon> 结尾", "RETURN 1;", L(L(1))},
            // keelson / bitcask 特有（文法外，与 bitcask 一致地保留）
            {"[keelson] ORDER BY … NULLS FIRST / LAST", "UNWIND [2, null, 1] AS x RETURN x ORDER BY x NULLS FIRST", L(L((Object) null), L(1), L(2))},
            {"[keelson] 空的反引号名字", "WITH 1 AS `` RETURN ``", L(L(1))},
    };

    /** openCypher 2024.3 文法里没有（keelson 曾作为 Neo4j 扩展支持过）。 */
    static final List<String> REJECTED = List.of(
            "MATCH (x) RETURN COUNT { (x)-->() }",
            "MATCH (x) RETURN COLLECT { MATCH (x)-->(y) RETURN y }",
            "CALL { RETURN 1 AS x } RETURN x",
            "FOREACH (x IN [1] | CREATE (:Z))",
            "RETURN filter(x IN [1] WHERE x > 0)",
            "MATCH (a)–[:T]–>(b) RETURN b",
            "MATCH (n:A {n: 1} WHERE n.n = 1) RETURN n",
            "RETURN all(x IN [1])",
            "RETURN '\\0'");

    @Test
    void grammarProductions() {
        assumeNative();
        Map<String, Object> params = M("p", M("k", 1), "k", 2, "1a", 3);
        try (Bitcask db = Bitcask.open(d)) {
            List<Executable> checks = new ArrayList<>();
            for (Object[] c : CASES) {
                String rule = (String) c[0], s = (String) c[1];
                Object expected = c[2];
                // 每条各自重建同一张图（失败也收集起来，最后一起报）
                q(db, "MATCH (n) DETACH DELETE n");
                q(db, SETUP);
                List<List<Object>> got = null;
                String errMsg = null;
                try {
                    got = q(db, s, params).rows();
                } catch (Exception e) {
                    errMsg = e.getMessage();
                }
                final List<List<Object>> g = got;
                final String em = errMsg;
                if (expected == OK) checks.add(() -> {
                    if (em != null) fail(rule + " — " + s + " — " + em);
                });
                else checks.add(() -> assertEquals(expected, g, rule + " — " + s + (em != null ? " — " + em : "")));
            }
            assertAll(checks);
        }
    }

    @Test
    void grammarRejections() {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            q(db, SETUP);
            for (String s : REJECTED) {
                String st = stage(() -> q(db, s));
                assertTrue("parse".equals(st) || "semantic".equals(st), s + " ⇒ " + st);
            }
        }
    }
}
