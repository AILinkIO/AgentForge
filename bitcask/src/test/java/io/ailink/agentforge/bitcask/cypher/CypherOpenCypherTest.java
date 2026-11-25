package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.L;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.M;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.cap;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.clj;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.detail;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.err;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.firsts;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.heads;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.kw;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.message;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.okInv;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.one;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.opNamed;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.ops;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.pr;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.q;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.rows;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.sorted;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.stage;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.timed;
import static io.ailink.agentforge.bitcask.cypher.CypherTestSupport.timedRows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.Meta;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.GraphModel;
import io.ailink.agentforge.bitcask.value.Point;

/**
 * D-31 起按 openCypher 对齐的语法与函数（移植自 clj-bitcask cypher_opencypher_test.clj）：词法、链式比较、列表推导、量词、reduce、
 * 映射投影、模式谓词与 exists()、模式推导、UNION、数学 / 百分位聚合；D-32 时间类型；D-33～D-50 语义对齐、用户过程 / 函数、错误代码、
 * 路径搜索（SHORTEST / ANY / ALL、量化路径、逐轮加深与剪枝）。
 */
class CypherOpenCypherTest {
    @TempDir
    Path d;

    @FunctionalInterface
    interface Body {
        void run(Bitcask db);
    }

    /** a(1) -T-> b(2) -T-> c(3)；a -K-> c；d(4) 孤立。 */
    static void graph(Bitcask db) {
        q(db, "CREATE (a:P {n: 1, name: 'a'})-[:T {w: 1}]->(b:P {n: 2, name: 'b'})-[:T {w: 2}]->(c:Q {n: 3, name: 'c'}), (a)-[:K]->(c), (:R {n: 4, name: 'd'})");
    }

    void withGraph(Body body) {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            graph(db);
            body.run(db);
        }
    }

    void withDb(Body body) {
        assumeNative();
        try (Bitcask db = Bitcask.open(d)) {
            body.run(db);
        }
    }

    static List<Object> lens(Bitcask db, String s) {
        return sorted(firsts(rows(db, s)));
    }

    /** 盘上的 meta（clj (meta/decode-map (:meta (model/read-doc db vid))))。 */
    static Map<String, Object> diskMeta(Bitcask db, long vid) {
        return Meta.decodeMap(GraphModel.readDoc(db, vid).meta());
    }

    @Test
    void lexical() {
        withGraph(db -> {
            assertEquals(L(L(31, 15, 255, 0.5, 150.0, 0.025, 1000.0)), rows(db, "RETURN 0x1F, 0o17, 0xff, .5, 1.5e2, .25e-1, 1E3"));
            assertEquals(L(L("😀", "é")), rows(db, "RETURN '\\U01F600', '\\u00e9'"));
            assertEquals(L(L(1, 2)), rows(db, "WITH 1 AS 名字, 2 AS größe RETURN 名字, größe"));
            assertEquals(L(L(3)), rows(db, "WITH [1, 2, 3] AS l RETURN l[2]"));
            assertEquals(L(L(L(2, 3))), rows(db, "WITH [1, 2, 3] AS l RETURN l[1..]"), "`..` 后面紧跟数字也不当成 .5 浮点");
            assertEquals("parse", stage(() -> q(db, "RETURN 0x")));
        });
    }

    @Test
    void chainedComparisons() {
        withGraph(db -> {
            assertEquals(L(L("b")), rows(db, "MATCH (n) WHERE 1 < n.n <= 2 RETURN n.name"));
            assertEquals(L(L(true, false, null)), rows(db, "RETURN 1 < 2 < 3, 1 < 3 < 2, 1 < null < 3"));
            assertEquals(L(L(true)), rows(db, "RETURN 1 = 1 <> 2"));
            assertEquals(List.of("1 < x <= 2"), q(db, "WITH 2 AS x RETURN 1 < x <= 2").columns(), "列名是源文本");
        });
    }

    @Test
    void listComprehension() {
        withGraph(db -> {
            assertEquals(L(4, 16, 36, 64, 100), one(db, "RETURN [x IN range(1, 10) WHERE x % 2 = 0 | x * x]"));
            assertEquals(L(2, 4), one(db, "RETURN [x IN [1, 2, 3, 4] WHERE x % 2 = 0]"));
            assertEquals(L(2, 4, 6), one(db, "RETURN [x IN [1, 2, 3] | x * 2]"));
            assertEquals(L(1, 2), one(db, "RETURN [x IN [1, 2]]"), "[x IN l] 是推导，不是布尔列表");
            assertNull(one(db, "RETURN [x IN null | x]"));
            assertEquals(L(L(1, L(10, 20))), rows(db, "WITH 1 AS x RETURN x, [x IN [10, 20] | x]"), "局部变量遮住外层同名变量、不外泄");
            assertEquals(L(L("a", L("b", "c"))), rows(db, "MATCH (n {name: 'a'}) RETURN n.name, [m IN [(n)-->(o) | o] | m.name]"));
            assertEquals("exec", stage(() -> q(db, "RETURN [x IN 1 | x]")));
        });
    }

    @Test
    void quantifiers() {
        withGraph(db -> {
            assertEquals(L(L(true, false, true, true)), rows(db, "RETURN all(x IN [1, 2] WHERE x > 0), all(x IN [1, -1] WHERE x > 0), all(x IN [] WHERE x > 0), any(x IN [1, 2] WHERE x = 2)"));
            assertEquals(L(L(null, false, null, true)), rows(db, "RETURN all(x IN [1, null] WHERE x > 0), any(x IN [] WHERE x > 0), any(x IN [1, null] WHERE x > 1), none(x IN [1, 2] WHERE x > 5)"));
            assertEquals(L(L(true, false, false, null)), rows(db, "RETURN single(x IN [1, 2, 3] WHERE x = 2), single(x IN [1, 2, 2] WHERE x = 2), single(x IN [] WHERE x = 2), single(x IN [2, null] WHERE x = 3)"));
            assertEquals(L(L((Object) null)), rows(db, "RETURN any(x IN null WHERE x > 0)"));
            assertEquals(L(L("a"), L("b")), rows(db, "MATCH (n:P) WHERE any(r IN [(n)-[t]->() | t.w] WHERE r IS NOT NULL) RETURN n.name ORDER BY n.name"));
        });
    }

    @Test
    void reduceAndMapProjection() {
        withGraph(db -> {
            assertEquals(6L, one(db, "RETURN reduce(acc = 0, x IN [1, 2, 3] | acc + x)"));
            assertEquals("abc", one(db, "RETURN reduce(s = '', x IN ['a', 'b', 'c'] | s + x)"));
            assertNull(one(db, "RETURN reduce(acc = 0, x IN null | acc + x)"));
            assertEquals(L(L(M("name", "a", "n", 1, "two", 2)), L(M("name", "b", "n", 2, "two", 4))),
                    rows(db, "MATCH (n:P) RETURN n {.name, .*, two: n.n * 2} ORDER BY n.n"));
            assertEquals(L(L(M("w", 1, "src", "a"))), rows(db, "MATCH (a {name: 'a'})-[r:T]->() WITH r, a.name AS src RETURN r {.w, src}"));
            assertEquals(M("x", 1, "z", null), one(db, "WITH {x: 1, y: 2} AS m RETURN m {.x, .z}"));
            assertEquals(L(L((Object) null)), rows(db, "OPTIONAL MATCH (n:Nope) RETURN n {.name}"));
        });
    }

    @Test
    void patternPredicatesAndExists() {
        withGraph(db -> {
            assertEquals(L(L("b")), rows(db, "MATCH (n:P) WHERE (n)-[:T]->(:Q) RETURN n.name"));
            assertEquals(L(L("c"), L("d")), rows(db, "MATCH (n) WHERE NOT (n)-->() RETURN n.name ORDER BY n.name"));
            assertEquals(L(L("a", "c")), rows(db, "MATCH (a), (b) WHERE (a)-[:K]->(b) RETURN a.name, b.name"), "两端都已绑定");
            assertEquals(L(L("a")), rows(db, "MATCH (n) WHERE (n)-[:T {w: 1}]->() RETURN n.name"));
            assertEquals(L(L("a"), L("b")), rows(db, "MATCH (n) WHERE exists((n)-[:T]->()) RETURN n.name ORDER BY n.name"));
            assertEquals(L(L("a"), L("b"), L("c"), L("d")), rows(db, "MATCH (n) WHERE exists(n.n) RETURN n.name ORDER BY n.name"));
            assertEquals(L(L(2, 0)), rows(db, "MATCH (a {name: 'a'}), (d {name: 'd'}) RETURN size([(a)-->() | 1]), size([(d)-->() | 1])"), "要个数用模式推导");
            assertEquals("semantic", stage(() -> q(db, "MATCH (a {name: 'a'}) RETURN (a)-->()")), "D-36：模式表达式只能当谓词（TCK）");
            assertEquals(L(L(3)), rows(db, "RETURN (1 + 2)"), "普通括号不受影响");
            assertEquals(L(L(-1)), rows(db, "WITH 1 AS a, 2 AS b RETURN (a) - (b)"), "括号里的变量减法不当成模式");
            assertEquals("semantic", stage(() -> q(db, "MATCH (a) WHERE (a)-->(b) RETURN a")));
        });
    }

    @Test
    void patternComprehension() {
        withGraph(db -> {
            assertEquals(L(L("a", L("b")), L("b", L("c")), L("c", L())), rows(db, "MATCH (n) WHERE n.n <= 3 RETURN n.name, [(n)-[:T]->(m) | m.name] ORDER BY n.name"));
            assertEquals(L(L("a", L("c"))), rows(db, "MATCH (n {name: 'a'}) RETURN n.name, [(n)-->(m) WHERE m.n > 2 | m.name]"));
            assertEquals(L(L(L(2, 1))), rows(db, "MATCH (n {name: 'a'}) RETURN [p = (n)-[*1..2]->(:Q) | length(p)]"));
            assertEquals(L(L("a", 2), L("b", 1), L("c", 0), L("d", 0)), rows(db, "MATCH (n) RETURN n.name, size([(n)-->(x) | x]) ORDER BY n.name"));
            assertEquals(L(L(2)), rows(db, "MATCH (n {name: 'a'}) WITH [(n)-->(m) | m] AS ms RETURN size(ms)"), "推导里的 m 不外泄");
            assertEquals(L(L(3)), rows(db, "MATCH (n:P) RETURN sum(size([(n)-->(x) | x]))"));
        });
    }

    @Test
    void union() {
        withGraph(db -> {
            assertEquals(L(L("a"), L("b"), L("c")), rows(db, "MATCH (n:P) RETURN n.name AS x UNION MATCH (n:Q) RETURN n.name AS x"));
            assertEquals(L(L(1)), rows(db, "RETURN 1 AS x UNION RETURN 1 AS x"));
            assertEquals(L(L(1), L(1.0)), rows(db, "RETURN 1 AS x UNION ALL RETURN 1.0 AS x"));
            assertEquals(L(L(1)), rows(db, "RETURN 1 AS x UNION RETURN 1.0 AS x"), "去重时 1 与 1.0 同一行");
            assertEquals(List.of("x"), q(db, "RETURN 1 AS x UNION RETURN 2 AS x").columns());
            CypherResult r = q(db, "CREATE (:U {k: 1}) RETURN 1 AS x UNION CREATE (:U {k: 2}) RETURN 2 AS x");
            assertEquals(L(L(1), L(2)), r.rows());
            assertEquals(2L, r.stats().get("nodes-created"), "写语句的 UNION：一个事务、统计相加");
            assertEquals(L(L(2)), rows(db, "MATCH (u:U) RETURN count(u)"));
            assertEquals(L(L(1), L(2)), rows(db, "CREATE (:A)-[:NEWT]->(:B) RETURN 1 AS x UNION CREATE (:C)-[:NEWU]->(:D) RETURN 2 AS x"),
                    "各部分里新出现的关系类型都在语句前注册");
            assertEquals(L(L("NEWT"), L("NEWU")), rows(db, "MATCH ()-[r:NEWT|NEWU]->() RETURN type(r) ORDER BY type(r)"));
            CypherCursor cur = Cypher.cursor(db, "MATCH (n:P) RETURN n.name AS x UNION ALL MATCH (n:Q) RETURN n.name AS x", null);
            CypherCursor.Batch b1 = cur.fetch(2);
            assertEquals(L(L("a"), L("b")), b1.rows());
            assertTrue(b1.more());
            CypherCursor.Batch b2 = cur.fetch(5);
            assertEquals(L(L("c")), b2.rows());
            assertFalse(b2.more());
            assertTrue(message(() -> q(db, "MATCH (n) RETURN n.name AS x UNION MATCH (n) SET n.y = 1")).contains("end with RETURN"));
        });
    }

    @Test
    void mathAndAggregates() {
        withGraph(db -> {
            assertEquals(L(L(3.0, 2.0, 3.0, -2.0, -1, 1, 0, 4.0, 1.0)), rows(db, "RETURN ceil(2.1), floor(2.9), round(2.5), round(-2.5), sign(-3), sign(0.5), sign(0), sqrt(16), exp(0)"));
            assertEquals(L(L(true, true, true, true)), rows(db, "RETURN abs(log(e()) - 1) < 1e-12, abs(log10(1000) - 3) < 1e-12, abs(sin(pi() / 2) - 1) < 1e-12, abs(degrees(pi()) - 180) < 1e-9"));
            assertEquals(L(L(true, true, true)), rows(db, "RETURN abs(atan2(1, 1) - pi() / 4) < 1e-12, abs(haversin(pi()) - 1) < 1e-12, abs(radians(180) - pi()) < 1e-12"));
            assertEquals(L(L(true, true)), rows(db, "RETURN 0 <= rand() < 1, timestamp() > 1700000000000"));
            assertEquals(L(L(null, null)), rows(db, "RETURN sqrt(null), atan2(null, 1)"));
            assertEquals(L(L(2.5, 2, 1.2909944487358056, 1.118033988749895)),
                    rows(db, "UNWIND [1, 2, null, 3, 4] AS x RETURN percentileCont(x, 0.5), percentileDisc(x, 0.5), stDev(x), stDevP(x)"), "null 不计");
            assertEquals(L(L(1, 4, 1.0, 4.0)), rows(db, "UNWIND [1, 2, 3, 4] AS x RETURN percentileDisc(x, 0.0), percentileDisc(x, 1.0), percentileCont(x, 0.0), percentileCont(x, 1.0)"));
            assertEquals(L(L(null, 0.0)), rows(db, "UNWIND [] AS x RETURN percentileCont(x, 0.5), stDev(x)"));
            assertEquals("exec", stage(() -> q(db, "UNWIND [1] AS x RETURN percentileCont(x, 1.5)")));
        });
    }

    // ── D-32：列名、反引号、时间类型 ───────────────────────

    @Test
    void columnNamesAreSourceText() {
        withGraph(db -> {
            assertEquals(List.of("0x1F", "toUpper('a')", "percentileCont(x, 0.5)", "n.name", "count( * )"),
                    q(db, "UNWIND [1] AS x MATCH (n {name: 'a'}) RETURN 0x1F, toUpper('a'), percentileCont(x, 0.5), n.name, count( * )").columns());
            assertEquals(List.of("a + b"), q(db, "WITH 1 AS a, 2 AS b RETURN a + b").columns());
            assertEquals(List.of("v"), q(db, "RETURN 1 + 1 AS v").columns());
        });
    }

    @Test
    void backtickEscapes() {
        withGraph(db -> {
            assertEquals(L(L(1)), rows(db, "WITH 1 AS `a``b` RETURN `a``b`"));
            assertEquals(List.of("a`b"), q(db, "WITH 1 AS `a``b` RETURN `a``b`").columns());
            q(db, "CREATE (:`My Label` {`weird``key`: 1})");
            assertEquals(L(L(1)), rows(db, "MATCH (n:`My Label`) RETURN n.`weird``key`"));
        });
    }

    static LocalDate date(int y, int m, int dd) {
        return LocalDate.of(y, m, dd);
    }

    @Test
    void temporalValues() {
        withGraph(db -> {
            // 构造与字段
            assertEquals(L(L(date(2015, 7, 21), 2015, 7, 21, 3, 30, 2, 202)),
                    rows(db, "WITH date('2015-07-21') AS d RETURN d, d.year, d.month, d.day, d.quarter, d.week, d.dayOfWeek, d.ordinalDay"));
            assertEquals(L(L(date(2015, 7, 21), date(2015, 7, 20), date(2015, 7, 21), date(2015, 1, 1))),
                    rows(db, "RETURN date({year: 2015, month: 7, day: 21}), date('2015-W30'), date('2015-202'), date('2015')"));
            assertEquals(L(L(12, 31, 14, 142, 142000000)), rows(db, "WITH localtime('12:31:14.142') AS t RETURN t.hour, t.minute, t.second, t.millisecond, t.nanosecond"));
            assertEquals(L(L("+01:00", 60)), rows(db, "WITH time('12:31:14+01:00') AS t RETURN t.offset, t.offsetMinutes"));
            assertEquals(L(L("Europe/Berlin", 2015, 21)), rows(db, "WITH datetime('2015-07-21T21:40:32[Europe/Berlin]') AS dt RETURN dt.timezone, dt.year, dt.hour"));
            assertEquals(L(L(1437514832000L)), rows(db, "RETURN datetime('2015-07-21T21:40:32Z').epochMillis"));
            assertEquals(L(L(LocalDateTime.of(2015, 7, 21, 21, 40))), rows(db, "RETURN localdatetime({year: 2015, month: 7, day: 21, hour: 21, minute: 40})"));
            assertEquals(L(L("2015-07-21T21:40:32Z")), rows(db, "RETURN toString(datetime.fromepoch(1437514832, 0))"));
            assertEquals(L(L((Object) null)), rows(db, "RETURN date(null)"));
            // 语句时钟：同一条语句里两次 datetime() 相同
            assertEquals(L(L(true, true)), rows(db, "RETURN datetime() = datetime(), date() <= date.realtime()"));
            // 截断
            assertEquals(L(L(date(2015, 7, 1), date(2015, 7, 20), date(2010, 1, 1), date(2015, 7, 1))),
                    rows(db, "WITH date('2015-07-21') AS d RETURN date.truncate('month', d), date.truncate('week', d), date.truncate('decade', d), date.truncate('quarter', d)"));
            assertEquals(L(L(LocalDateTime.of(2015, 7, 21, 21, 0))), rows(db, "RETURN localdatetime.truncate('hour', localdatetime('2015-07-21T21:40:32'))"));
            assertEquals(L(L(date(2015, 7, 5))), rows(db, "RETURN date.truncate('month', date('2015-07-21'), {day: 5})"));
            // duration
            assertEquals(L(L("P1Y2M10DT2H30M")), rows(db, "RETURN toString(duration('P1Y2M10DT2H30M'))"));
            assertEquals(L(L("P14DT16H12M")), rows(db, "RETURN toString(duration({weeks: 2, days: 0.5, hours: 4, minutes: 12}))"));
            assertEquals(L(L(14, 1, 2, 10)), rows(db, "WITH duration('P1Y2M10D') AS d RETURN d.months, d.years, d.monthsOfYear, d.days"));
            assertEquals(L(L("PT0S", "P-1D")), rows(db, "RETURN toString(duration('PT0S')), toString(duration('-P1D'))"), "同 Neo4j：分量各自带符号");
            assertEquals(L(L("P1Y1M11D")), rows(db, "RETURN toString(duration.between(date('2015-07-21'), date('2016-09-01')))"));
            assertEquals(L(L("P1Y1M", "P408D", "PT9792H")),
                    rows(db, "WITH date('2015-07-21') AS a, date('2016-09-01') AS b RETURN toString(duration.inMonths(a, b)), toString(duration.inDays(a, b)), toString(duration.inSeconds(a, b))"));
            // 运算、比较、排序
            assertEquals(L(L(date(2016, 8, 1), date(2015, 6, 21))), rows(db, "WITH date('2015-07-21') AS d RETURN d + duration('P1Y11D'), d - duration('P1M')"));
            assertEquals(L(L("P2D", "PT6H", "PT3H")), rows(db, "RETURN toString(duration('P1D') + duration('P1D')), toString(duration('PT2H') * 3), toString(duration('PT6H') / 2)"));
            assertEquals(L(L(true, false, null, true)),
                    rows(db, "RETURN date('2015-01-01') < date('2016-01-01'), date('2015-01-01') = localdatetime('2015-01-01T00:00'), date('2015-01-01') < '2016', datetime('2015-07-21T21:40:32+02:00') = datetime('2015-07-21T19:40:32Z')"));
            assertEquals(L(L(L(date(2014, 1, 1), date(2015, 1, 1), null))),
                    rows(db, "UNWIND [date('2015-01-01'), null, date('2014-01-01')] AS d WITH d ORDER BY d RETURN collect(d) + [null]"));
            assertEquals(L(L(date(2015, 1, 1))), rows(db, "UNWIND [date('2015-01-01'), date('2015-01-01')] AS d RETURN DISTINCT d"));
            // 坏输入是执行错误
            assertEquals("exec", stage(() -> q(db, "RETURN date('2015-13-45')")));
            assertEquals("exec", stage(() -> q(db, "RETURN duration('nope')")));
            assertEquals("exec", stage(() -> q(db, "RETURN datetime({year: 2015, timezone: 'Not/AZone'})")));
        });
    }

    @Test
    void temporalPropertiesRoundtrip() {
        withDb(db -> {
            Graph g = Graph.open(db);
            q(db, "CREATE (:E {name: 'x', born: date('1990-05-17'), at: datetime('2020-01-02T03:04:05.006+08:00'), t: localtime('10:00'), dur: duration('P1DT2H')})");
            List<Object> r = rows(db, "MATCH (e:E) RETURN e.born, e.at, e.t, e.dur").getFirst();
            assertEquals(date(1990, 5, 17), r.get(0), "读回来仍是 date");
            assertEquals("2020-01-02T03:04:05.006+08:00", String.valueOf(r.get(1)));
            assertEquals(LocalTime.of(10, 0), r.get(2));
            assertEquals("P1DT2H", String.valueOf(r.get(3)));
            assertEquals(L(L("x")), rows(db, "MATCH (e:E) WHERE e.born < date('2000-01-01') AND e.born.year = 1990 RETURN e.name"));
            assertEquals(L(L(M("name", "x", "born", date(1990, 5, 17)))), rows(db, "MATCH (e:E) RETURN e {.name, .born}"));
            // 盘上只有字符串与字符串数组（bitcask 照常能读），类型记在 _types
            long vid = (Long) one(db, "MATCH (e:E) RETURN id(e)");
            Map<String, Object> m = diskMeta(db, vid);
            assertEquals("1990-05-17", m.get("born"));
            assertEquals("P1DT2H", m.get("dur"));
            assertEquals(List.of("at\tdatetime", "born\tdate", "dur\tduration", "t\tlocaltime"), m.get("_types"), "D-42：同 bitcask 的写法");
            assertEquals(Set.of("name", "born", "at", "t", "dur"), g.vertexInfo(vid).properties().keySet());
            assertFalse(rows(db, "CALL db.propertyKeys()").contains(L("_types")), "_types 不当成属性名");
            q(db, "MATCH (e:E) SET e.born = date('1991-01-01'), e.name = 'y'");
            assertEquals(L(L(date(1991, 1, 1), "y")), rows(db, "MATCH (e:E) RETURN e.born, e.name"));
            q(db, "MATCH (e:E) SET e += {at: null}");
            assertEquals(L(L(null, LocalTime.of(10, 0))), rows(db, "MATCH (e:E) RETURN e.at, e.t"), "删掉一个时间属性，其余类型照旧");
            q(db, "MATCH (e:E) CREATE (e)-[:AT {since: date('2020-02-02')}]->(:F)");
            assertEquals(L(L(date(2020, 2, 2))), rows(db, "MATCH ()-[r:AT]->() RETURN r.since"), "边属性同样");
            assertEquals(L(L(2)), rows(db, "MATCH (e:E) WITH e, $d AS d RETURN d.month", M("d", date(2020, 2, 3))), "参数可以直接给 java.time 值");
            okInv(g);
        });
    }

    // ── D-33：语义对齐 ───────────────────────────────────

    @Test
    void unboundedVarLength() {
        withGraph(db -> {
            q(db, "CREATE (a:C {i: 0})");
            q(db, "MATCH (a:C {i: 0}) UNWIND range(1, 15) AS i WITH a, i MERGE (:C {i: i})");
            q(db, "MATCH (x:C), (y:C) WHERE y.i = x.i + 1 CREATE (x)-[:N]->(y)");
            assertEquals(L(L(15)), rows(db, "MATCH (:C {i: 0})-[:N*]->(z) RETURN max(z.i)"), "`*` 不再停在 10 跳");
            assertEquals(L(L(5)), rows(db, "MATCH (:C {i: 0})-[:N*11..]->(z) RETURN count(z)"));
            assertEquals(L(L(15)), rows(db, "MATCH p = shortestPath((:C {i: 0})-[:N*]->(:C {i: 15})) RETURN length(p)"));
        });
    }

    @Test
    void patternExpressionPositions() {
        // D-33 曾按 openCypher 9 让模式表达式的值是路径列表；D-36 按 openCypher TCK 收回：只能当谓词用，要列表 / 个数用模式推导
        withGraph(db -> {
            q(db, "CREATE (a:X {name: 'a'})-[:T]->(:X {name: 'b'}), (a)-[:T]->(:X {name: 'c'})");
            assertEquals(L(L("a", 2), L("b", 0), L("c", 0)), rows(db, "MATCH (n:X) RETURN n.name, size([(n)-[:T]->() | 1]) ORDER BY n.name"));
            assertEquals(L(L("a")), rows(db, "MATCH (n:X) WHERE (n)-[:T]->() RETURN n.name"));
            assertEquals(L(L("b"), L("c")), rows(db, "MATCH (n:X) WHERE NOT (n)-[:T]->() AND true RETURN n.name ORDER BY n.name"));
            assertEquals(L(L("a", "out")), rows(db, "MATCH (n:X {name: 'a'}) RETURN n.name, CASE WHEN (n)-->() THEN 'out' ELSE 'none' END"));
            assertEquals(L(L(true)), rows(db, "MATCH (n:X {name: 'a'}) RETURN exists((n)-->())"));
            assertEquals(L(L(L(true, false, false))), rows(db, "MATCH (n:X) WITH n ORDER BY n.name RETURN collect(exists((n)-->()))"));
            for (String s : List.of("MATCH (n:X) RETURN size((n)-[:T]->())", "MATCH (n:X) RETURN (n)-[:T]->()", "MATCH (n:X) WITH (n)-->() AS p RETURN p",
                    "MATCH (n:X) SET n.p = (n)-->()"))
                assertEquals("semantic", stage(() -> q(db, s)), s);
        });
    }

    @Test
    void minIntLiteral() {
        withGraph(db -> {
            assertEquals(L(L(Long.MIN_VALUE)), rows(db, "RETURN -9223372036854775808"));
            assertEquals(L(L(Long.MIN_VALUE)), rows(db, "RETURN -0x8000000000000000"));
            assertEquals("parse", stage(() -> q(db, "RETURN 9223372036854775808")));
        });
    }

    @Test
    void typedListProperties() {
        withDb(db -> {
            q(db, "CREATE (:L {ints: [1, 2, 3], fs: [1.5, 2], bs: [true, false], ds: [date('2020-01-01'), date('2021-02-03')], ss: ['a'], e: []})");
            assertEquals(L(L(L(1, 2, 3), L(1.5, 2.0), L(true, false), L(date(2020, 1, 1), date(2021, 2, 3)), L("a"), L())),
                    rows(db, "MATCH (n:L) RETURN n.ints, n.fs, n.bs, n.ds, n.ss, n.e"));
            assertEquals(L(L(6)), rows(db, "MATCH (n:L) RETURN reduce(s = 0, x IN n.ints | s + x)"));
            // 盘上是字符串数组 + _types
            long vid = (Long) one(db, "MATCH (n:L) RETURN id(n)");
            Map<String, Object> m = diskMeta(db, vid);
            assertEquals(List.of("1", "2", "3"), m.get("ints"));
            assertEquals(List.of("bs\tlist:bool", "ds\tlist:date", "fs\tlist:float", "ints\tlist:int"), m.get("_types"), "D-42：同 bitcask 的写法");
            assertEquals("exec", stage(() -> q(db, "MATCH (n:L) SET n.x = [1, 'a']")), "异类列表仍不行");
            assertEquals("exec", stage(() -> q(db, "MATCH (n:L) SET n.x = [[1]]")));
        });
    }

    @Test
    void implicitGrouping() {
        withGraph(db -> {
            q(db, "CREATE (:G {k: 1, v: 10}), (:G {k: 1, v: 20}), (:G {k: 2, v: 5})");
            assertEquals(L(L(1, 31), L(2, 7)), rows(db, "MATCH (n:G) RETURN n.k, n.k + sum(n.v) ORDER BY n.k"));
            assertEquals(L(L(1, L(31)), L(2, L(7))), rows(db, "MATCH (n:G) RETURN n.k, [n.k + sum(n.v)] ORDER BY n.k"), "聚合套在列表 / 下标 / 推导里也行");
            assertEquals(L(L(20)), rows(db, "MATCH (n:G) RETURN collect(n.v)[1]"));
            assertEquals(L(L(3)), rows(db, "MATCH (n:G) WITH n RETURN count(n) + 0"));
            assertEquals(L(L(6)), rows(db, "MATCH (n:G) RETURN size(collect(n)) * 2"));
            assertEquals(3, rows(db, "MATCH (n:G) RETURN n, n.v + count(*)").size(), "变量本身是分组键时，它的属性可以用");
            assertEquals(L(L(2, 3)), rows(db, "MATCH (n:G) RETURN count(*) - 1, $p + count(*)", M("p", 0)), "常量 / 参数可以");
            assertEquals("semantic", stage(() -> q(db, "MATCH (n:G) RETURN n.k + count(*)")));
            assertEquals("semantic", stage(() -> q(db, "MATCH (n:G) RETURN n.k, n.v + count(*)")));
            assertEquals(L(L(L(10, 20, 5))), rows(db, "MATCH (n:G) RETURN [x IN collect(n.v) | x]"), "推导的局部变量不算外部引用");
        });
    }

    // ── D-34 ─────────────────────────────────────────────

    @Test
    void createAndMergeNamedPaths() {
        withGraph(db -> {
            assertEquals(L(L(2, L("Z", "Z", "Z"))), rows(db, "CREATE p = (:Z {i: 1})-[:T]->(:Z {i: 2})<-[:T]-(:Z {i: 3}) RETURN length(p), [n IN nodes(p) | labels(n)[0]]"));
            assertEquals(L(L(1, 2)), rows(db, "CREATE p = (a:Z2), q = (a)-[:T]->(:Z2) RETURN length(q) + length(p), size(nodes(q))"));
            // MERGE p = …：第一次建、第二次匹配，路径一样长
            assertEquals(L(L(1, 1)), rows(db, "MATCH (a:Z {i: 1}) MERGE p = (a)-[:U]->(:Y {k: 1}) RETURN length(p), size(relationships(p))"));
            assertEquals(L(L(1)), rows(db, "MATCH (a:Z {i: 1}) MERGE p = (a)-[:U]->(:Y {k: 1}) RETURN length(p)"));
            assertEquals(L(L(1)), rows(db, "MATCH (:Y) RETURN count(*)"));
            assertEquals(L(L(0)), rows(db, "MERGE p = (:Solo {k: 1}) RETURN length(p)"));
            assertEquals("semantic", stage(() -> q(db, "MATCH (p) CREATE p = (:A)")));
        });
    }

    @Test
    void undirectedMerge() {
        withGraph(db -> {
            q(db, "CREATE (:U {n: 1})-[:K]->(:U {n: 2}), (:U {n: 3})");
            // 已有反方向的边 ⇒ 匹配上，不新建
            assertEquals(L(L("K", 1)), rows(db, "MATCH (a:U {n: 2}), (b:U {n: 1}) MERGE (a)-[r:K]-(b) RETURN type(r), startNode(r).n"));
            assertEquals(L(L(1)), rows(db, "MATCH (:U)-[r:K]-(:U) RETURN count(DISTINCT r)"));
            // 没有 ⇒ 从左到右建
            assertEquals(L(L(3, 1)), rows(db, "MATCH (a:U {n: 3}), (b:U {n: 1}) MERGE (a)-[r:K]-(b) RETURN startNode(r).n, endNode(r).n"));
            assertEquals(L(L(3, 1)), rows(db, "MATCH (a:U {n: 3}), (b:U {n: 1}) MERGE (a)-[r:K]-(b) RETURN startNode(r).n, endNode(r).n"));
            // 整条模式的 MERGE 也可以无方向
            assertEquals(L(L(1)), rows(db, "MERGE (:V {n: 1})-[:L]-(:V {n: 2}) RETURN 1"));
            assertEquals(L(L(1)), rows(db, "MERGE (:V {n: 2})-[:L]-(:V {n: 1}) RETURN 1"));
            assertEquals(L(L(2)), rows(db, "MATCH (v:V) RETURN count(v)"), "第二次按反方向匹配上了");
            assertEquals("semantic", stage(() -> q(db, "MATCH (a:U {n: 1}), (b:U {n: 2}) CREATE (a)-[:K]-(b)")), "CREATE 仍要方向");
        });
    }

    @Test
    void callForms() {
        withGraph(db -> {
            q(db, "CREATE (:Lab1), (:Lab2)");
            Set<Object> labs = new HashSet<>(firsts(rows(db, "CALL db.labels")));
            assertTrue(labs.containsAll(List.of("Lab1", "Lab2")), "不带括号");
            assertEquals(labs, new HashSet<>(firsts(rows(db, "CALL db.labels() YIELD *"))));
            assertEquals(labs, new HashSet<>(firsts(rows(db, "CALL db.labels YIELD label"))), "单独 CALL … YIELD 不用 RETURN");
            assertEquals(List.of("l"), q(db, "CALL db.labels() YIELD label AS l").columns());
            assertEquals("semantic", stage(() -> q(db, "CALL db.labels YIELD * RETURN count(label)")), "查询中间不能 YIELD *（TCK）");
            assertEquals(L(L(labs.size())), rows(db, "CALL db.labels YIELD label RETURN count(label)"));
        });
    }

    @Test
    void listFunctionsAndOperators() {
        withGraph(db -> {
            // D-40：filter() / extract()、字符串 + 数 不在 openCypher 2024.3 里
            assertEquals("parse", stage(() -> q(db, "RETURN filter(x IN [1, 2, 3] WHERE x > 1)")));
            assertEquals("exec", stage(() -> q(db, "WITH 1 AS n RETURN 'a' + n")));
            assertEquals(L(L("ab", L(1, 2, 3), L(0, 1))), rows(db, "RETURN 'a' + 'b', [1, 2] + 3, 0 + [1]"));
            List<Object> r = rows(db, "RETURN 1.0 / 0, -1 / 0.0, 0.0 / 0, 1.5 % 0").getFirst();
            assertEquals(Double.POSITIVE_INFINITY, r.get(0));
            assertEquals(Double.NEGATIVE_INFINITY, r.get(1));
            assertTrue(Double.isNaN((Double) r.get(2)));
            assertTrue(Double.isNaN((Double) r.get(3)));
            assertEquals("exec", stage(() -> q(db, "RETURN 1 / 0")), "整数除以 0 仍是错误");
        });
    }

    @Test
    void unicodeDashesAndArrows() {
        withGraph(db -> {
            q(db, "CREATE (:W {n: 1})-[:T]->(:W {n: 2})");
            // D-40：openCypher 2024.3 文法里关系模式只用 ASCII 的 < - >
            for (String s : List.of("MATCH (a:W)–[:T]–＞(b) RETURN b.n", "MATCH (b:W)⟨−[:T]−(a) RETURN b.n"))
                assertEquals("parse", stage(() -> q(db, s)), s);
            assertEquals(L(L("a–b")), rows(db, "RETURN 'a–b'"), "字符串里随便写");
        });
    }

    // ── D-35 ─────────────────────────────────────────────

    @Test
    void powerAndNumericEdges() {
        withGraph(db -> {
            assertEquals(L(L(64.0, 4.0, 0.5)), rows(db, "RETURN 2 ^ 3 ^ 2, -2 ^ 2, 2 ^ -1"), "^ 左结合；一元负号比 ^ 紧");
            assertEquals(L(L(null, 7, 1, null)), rows(db, "RETURN toInteger('99999999999999999999'), toInteger(' 7 '), toInteger('1.9'), toInteger('1e30')"));
            for (String s : List.of("RETURN toInteger(1e30)", "RETURN toInteger(0.0 / 0)", "RETURN range(-9223372036854775807, 9223372036854775807)"))
                assertEquals("exec", stage(() -> q(db, s)), s);
            assertEquals(L(L("bc", "abc")), rows(db, "RETURN substring('abc', 1, 9223372036854775807), left('abc', 9223372036854775807)"));
        });
    }

    // ── D-36（openCypher TCK）────────────────────────────

    static boolean parseOrSemantic(String stage) {
        return "parse".equals(stage) || "semantic".equals(stage);
    }

    @Test
    void existentialSubqueries() {
        withGraph(db -> {
            q(db, "CREATE (a:E {n: 1})-[:K]->(:E {n: 2})-[:K]->(:E {n: 3}), (:E {n: 4})");
            assertEquals(L(L(1), L(2)), rows(db, "MATCH (x:E) WHERE EXISTS { (x)-[:K]->() } RETURN x.n ORDER BY x.n"));
            assertEquals(L(L(1)), rows(db, "MATCH (x:E) WHERE EXISTS { (x)-[:K]->(y) WHERE y.n = 2 } RETURN x.n"));
            assertEquals(L(L(1)), rows(db, "MATCH (x:E) WHERE EXISTS { MATCH (x)-[:K]->(y) MATCH (y)-[:K]->(z) RETURN z } RETURN x.n"));
            assertEquals(L(L(2)), rows(db, "MATCH (x:E) WHERE EXISTS { MATCH (x)-[:K]->(y) WITH y WHERE EXISTS { (y)-[:K]->() } RETURN 1 } = false AND EXISTS { (x)-->() } RETURN x.n"));
            assertEquals(L(L(4, false)), rows(db, "MATCH (x:E {n: 4}) RETURN x.n, EXISTS { (x)--() }"));
            assertTrue(parseOrSemantic(stage(() -> q(db, "MATCH (x) WHERE EXISTS { CREATE (x)-[:K]->() } RETURN x"))), "子查询里不能写");
        });
    }

    @Test
    void rebindingInMatch() {
        withGraph(db -> {
            q(db, "CREATE (a:R {n: 1})-[:K]->(b:R {n: 2})-[:K]->(c:R {n: 3})");
            assertEquals(L(L(1, 2)), rows(db, "MATCH (a:R)-[r:K]->(b) WITH r, a, b WHERE a.n = 1 MATCH (x)-[r]->(y) RETURN x.n, y.n"), "已绑定的关系变量当约束");
            assertEquals(L(L(3)), rows(db, "MATCH (:R {n: 1})-[rs:K*2]->() WITH rs MATCH (a)-[rs*]->(z) RETURN z.n"), "关系列表");
            assertEquals(L(L(L(1, 2))), rows(db, "MATCH (:R {n: 1})-[rs:K*2]->(:R {n: 3}) RETURN [r IN rs | startNode(r).n]"), "反向展开时列表仍按模式顺序");
            assertEquals(L(L(L(L(1, 2)))), rows(db, "MATCH p = (a:R {n: 1})-[*1..1]->() RETURN [r IN relationships(p) | [startNode(r).n, endNode(r).n]] AS x"));
            assertEquals(L(L(1)), rows(db, "MATCH (a:R {n: 1})-[r*1..1]->() RETURN size(r)"), "*1..1 仍是列表");
            assertEquals(L(), rows(db, "MATCH (a)-[*3..1]->(b) RETURN b"), "空区间匹配不到");
            assertEquals(L(), rows(db, "UNWIND [1] AS x MATCH (x) RETURN x"), "来路不明的值：不是节点就匹配不上");
            assertEquals("semantic", stage(() -> q(db, "WITH [1] AS x MATCH (x) RETURN x")));
        });
    }

    @Test
    void withWhereAndOrderScopes() {
        withGraph(db -> {
            q(db, "CREATE (:W {name: 'A', k: 1}), (:W {name: 'A', k: 2}), (:W {name: 'B', k: 3})");
            assertEquals(L(L("A")), rows(db, "MATCH (a:W) WITH a.name AS name WHERE a.k = 2 RETURN name"), "WHERE 看得见投影前的变量");
            assertEquals(L(L("B")), rows(db, "MATCH (a:W) WITH DISTINCT a.name AS name WHERE a.name = 'B' RETURN name"), "DISTINCT 后：与投影项相同的表达式");
            assertEquals(L(L("A", 2)), rows(db, "MATCH (a:W) WITH a.name AS name, count(*) AS c ORDER BY a.name LIMIT 1 RETURN name, c"));
            assertEquals(L(L(2.0)), rows(db, "MATCH (a:W) RETURN avg(a.k) AS v ORDER BY $p + avg(a.k)", M("p", 1)));
            assertEquals("semantic", stage(() -> q(db, "MATCH (a:W) RETURN a.k + a.k, count(*) ORDER BY a.k + a.k + count(*)")), "歧义的聚合表达式");
        });
    }

    @Test
    void typeChecksAndNan() {
        withGraph(db -> {
            for (String s : List.of("RETURN 123 AND true", "RETURN false AND 'x'", "RETURN NOT 1", "MATCH (n) RETURN type(n)", "MATCH p = ()-->() RETURN p.x",
                    "MATCH (n) WHERE (n) RETURN n", "RETURN count(rand())", "MATCH (n) DELETE n:L", "MATCH (a) CREATE (a)", "RETURN 1e999",
                    "RETURN [x IN [1] | count(x)]", "RETURN 1 – 2"))
                assertTrue(parseOrSemantic(stage(() -> q(db, s))), s);
            assertEquals(L(L(false, false, false, false)), rows(db, "WITH 0.0 / 0.0 AS n RETURN n > 1, n >= 1, n < 1, n <= 1"));
            List<List<Object>> r = rows(db, "UNWIND [null, 0.0 / 0.0, 1.5] AS x RETURN x ORDER BY x");
            // NaN 排在数的最后
            assertEquals(1.5, r.get(0).getFirst());
            assertTrue(Double.isNaN((Double) r.get(1).getFirst()));
            assertNull(r.get(2).getFirst());
        });
    }

    @Test
    void deletedEntityAccess() {
        withGraph(db -> {
            q(db, "CREATE (:D {name: 'x'})-[:DT {w: 1}]->(:D)");
            assertEquals("exec", stage(() -> q(db, "MATCH (n:D {name: 'x'}) DETACH DELETE n RETURN n.name")));
            assertEquals("exec", stage(() -> q(db, "MATCH ()-[r:DT]->() DELETE r RETURN r.w")));
            assertEquals(L(L(1, 2)), rows(db, "MATCH ()-[r:DT]->() WITH count(r) AS c MATCH (n:D) RETURN c, count(n)"), "上面两条都整条回滚");
            assertEquals(L(L(0)), rows(db, "MATCH (:D)-[r:DT]->(:D) DELETE r MERGE (a:D {name: 'x'})-[r2:DT]->(b:D {name: 'y'}) RETURN count(r2) - 1"), "删了再建");
        });
    }

    // ── D-37：用户过程、编译期类型 ──────────────────────

    @Test
    void userProcedures() {
        withGraph(db -> {
            Cypher.registerProcedure("my.cities", List.of(ProcParam.of("name", "STRING?"), ProcParam.of("id", "INTEGER?")),
                    List.of(ProcParam.of("city", "STRING?"), ProcParam.of("code", "INTEGER?")),
                    (ctx, args) -> {
                        Object name = args.get("name"), id = args.get("id");
                        if ("Stefan".equals(name) && Long.valueOf(1).equals(id)) return List.of(Map.of("city", "Berlin", "code", 49L));
                        if ("Stefan".equals(name)) return List.of(Map.of("city", "München", "code", 49)); // Integer ⇒ 引擎规范成 Long
                        return List.of();
                    });
            Cypher.registerProcedure("my.scale", List.of(ProcParam.of("x", "FLOAT")), List.of(ProcParam.of("y", "FLOAT")),
                    (ctx, args) -> List.of(Map.of("y", 2 * (Double) args.get("x"))));
            AtomicInteger seen = new AtomicInteger();
            Cypher.registerProcedure("my.touch", List.of(), List.of(), (ctx, args) -> {
                seen.incrementAndGet();
                return List.of();
            });
            try {
                assertEquals(L(L("Berlin", 49)), rows(db, "CALL my.cities('Stefan', 1) YIELD city, code RETURN city, code"));
                assertEquals(List.of("city", "code"), q(db, "CALL my.cities('Stefan', 2)").columns(), "单独 CALL：全部输出列");
                assertEquals(L(L("Berlin", 49)), rows(db, "CALL my.cities", M("name", "Stefan", "id", 1)), "隐式参数取同名的查询参数");
                assertEquals(L(L("a", "Berlin")), rows(db, "UNWIND ['a'] AS k CALL my.cities('Stefan', 1) YIELD city AS c RETURN k, c"));
                assertEquals(L(L(4.0)), rows(db, "CALL my.scale(2) YIELD y RETURN y"), "INTEGER 传给 FLOAT 参数 ⇒ 转成浮点");
                assertEquals(L(L(3)), rows(db, "UNWIND [1, 2, 3] AS i CALL my.touch() RETURN count(*)"), "VOID：原样放行各行");
                assertEquals(3, seen.get());
                assertEquals(L(), rows(db, "CALL my.touch()"), "单独调 VOID 过程：没有行");
                String[][] cases = {
                        {"CALL my.cities('Stefan') YIELD city RETURN city", "semantic"},
                        {"CALL my.cities(1, 1) YIELD city RETURN city", "semantic"},
                        {"CALL my.cities YIELD city RETURN city", "semantic"},
                        {"CALL my.cities('a', 1) YIELD * RETURN city", "semantic"},
                        {"CALL my.cities('a', 1) RETURN 1", "semantic"},
                        {"CALL my.cities('a', 1) YIELD city, code AS city RETURN city", "semantic"},
                        {"CALL my.nope()", "semantic"},
                        {"WITH 'x' AS n CALL my.scale(n) YIELD y RETURN y", "exec"},
                        {"CALL my.cities", "semantic"}};
                for (String[] c : cases) assertEquals(c[1], stage(() -> q(db, c[0])), c[0]);
            } finally {
                for (String n : List.of("my.cities", "my.scale", "my.touch")) Cypher.unregisterProcedure(n);
            }
            assertEquals("semantic", stage(() -> q(db, "CALL my.cities('a', 1)")), "注销之后");
        });
    }

    @Test
    void compileTimeTypes() {
        withGraph(db -> {
            for (String s : List.of("WITH 123 AS x RETURN x.num", "RETURN 'a'.x", "RETURN properties(1)", "RETURN 1 IN 2", "RETURN 1 IN {x: 1}",
                    "RETURN none(x IN ['a', 'b'] WHERE x % 2 = 0)", "RETURN [x IN [true] | x * 2]", "RETURN 'a' - 1", "RETURN true + 1",
                    "UNWIND ['a'] AS s RETURN s / 2", "MATCH (n) RETURN n SKIP -1", "MATCH (n) RETURN n LIMIT 1.5", "MATCH (n $p) RETURN n"))
                assertEquals("semantic", stage(() -> q(db, s, M("p", M()))), s);
            // 类型判断不了的不误报
            assertEquals(L(L(3)), rows(db, "WITH {num: 3} AS m RETURN m.num"));
            assertEquals(L(L("ab")), rows(db, "RETURN 'a' + 'b'"));
            assertEquals(L(L(L(1, 2, 3))), rows(db, "RETURN [1, 2] + 3"));
            assertEquals(L(L(true)), rows(db, "WITH [1, 2] AS l RETURN 2 IN l"));
            assertEquals(L(L(2)), rows(db, "UNWIND [{k: 2}] AS m RETURN m.k"));
            assertEquals(L(L(1)), rows(db, "WITH $x AS v RETURN v.a", M("x", M("a", 1))));
            assertEquals(L(L(0.9)), rows(db, "UNWIND [0.9] AS p RETURN percentileDisc(p, p)"), "百分位可以是表达式");
        });
    }

    // ── D-38 / D-40 ──────────────────────────────────────

    @Test
    void removedNeo4jExtensions() {
        // D-40：openCypher 2024.3 文法里没有 ⇒ 不再支持（D-38 / D-39 加过）
        withGraph(db -> {
            for (String s : List.of("MATCH (x) RETURN COUNT { (x)-->() }", "MATCH (x) RETURN COLLECT { MATCH (x)-->(y) RETURN y }",
                    "FOREACH (x IN [1] | CREATE (:A))", "CALL { RETURN 1 AS x } RETURN x", "MATCH (n) CALL (n) { RETURN 1 AS x } RETURN x"))
                assertTrue(parseOrSemantic(stage(() -> q(db, s))), s);
        });
    }

    @Test
    void inlinePatternWhere() {
        withGraph(db -> {
            q(db, "CREATE (a:IW {n: 1})-[:K {w: 5}]->(:IW {n: 2}), (a)-[:K {w: 1}]->(:IW {n: 3}), (:IW {n: 4})");
            assertEquals(L(L(2), L(3)), rows(db, "MATCH (x:IW WHERE x.n > 1 AND x.n < 4) RETURN x.n ORDER BY x.n"));
            assertEquals(L(L(2)), rows(db, "MATCH (:IW {n: 1})-[r:K WHERE r.w > 2]->(y) RETURN y.n"));
            assertEquals(L(L(3)), rows(db, "MATCH (x:IW WHERE x.n = 1)-->(y WHERE y.n <> 2) WHERE true RETURN y.n"), "与外面的 WHERE 合并");
            assertEquals(L(L(1, null), L(4, null)), rows(db, "MATCH (x:IW) WHERE x.n IN [1, 4] OPTIONAL MATCH (x)-->(y WHERE y.n > 10) RETURN x.n, y ORDER BY x.n"),
                    "OPTIONAL MATCH：条件属于匹配本身");
            assertEquals(L(L(L(2))), rows(db, "MATCH (x:IW {n: 1}) RETURN [(x)-[r WHERE r.w > 2]->(y) | y.n]"));
            assertEquals(L(L(1)), rows(db, "MATCH (x:IW) WHERE EXISTS { (x)-->(y WHERE y.n = 3) } RETURN x.n"));
            assertEquals(List.of("x.n"), q(db, "MATCH (x:IW WHERE x.n = 1) RETURN x.n").columns());
            for (String s : List.of("CREATE (n:IW WHERE n.n = 1)", "MERGE (n:IW {n: 9} WHERE n.n = 9)", "MATCH (a)-[r:K* WHERE r.w > 1]->(b) RETURN b",
                    "MATCH (a:IW) WHERE (a)-->(b WHERE b.n = 2) RETURN a"))
                assertTrue(parseOrSemantic(stage(() -> q(db, s))), s);
        });
    }

    // ── D-42：与 bitcask 同步 ─────────────────────────────

    @Test
    void typedStorageCompatibleWithBitcask() {
        withDb(db -> {
            q(db, "CREATE (:TS {k: 1, d: date('2020-01-02'), nan: 0.0 / 0.0, inf: 1.0 / 0, xs: [1.5, 0.0 / 0.0], p: point({x: 1, y: 2})})");
            long vid = (Long) one(db, "MATCH (n:TS) RETURN id(n)");
            Map<String, Object> m = diskMeta(db, vid);
            assertEquals(List.of("d\tdate", "inf\tfloat", "nan\tfloat", "p\tpoint", "xs\tlist:float"), m.get("_types"));
            assertEquals(List.of("NaN", "Infinity", "7203;1.0;2.0"), L(m.get("nan"), m.get("inf"), m.get("p")), "NaN / Infinity / point 存成文本");
            List<Object> r = rows(db, "MATCH (n:TS) RETURN n.nan, n.inf, n.xs, n.p").getFirst();
            assertTrue(Double.isNaN((Double) r.get(0)));
            assertEquals(Double.POSITIVE_INFINITY, r.get(1));
            List<?> xs = (List<?>) r.get(2);
            assertEquals(1.5, xs.get(0));
            assertTrue(Double.isNaN((Double) xs.get(1)));
            assertEquals(2.0, ((Point) r.get(3)).y());
            // 读得懂 keelson 以前（D-32～D-41）的写法
            Map<String, Object> old = new LinkedHashMap<>(M("b", "1990-05-17", "xs", L("1", "2"), "_types", L("date:b", "list<int>:xs")));
            assertEquals(M("b", date(1990, 5, 17), "xs", L(1, 2)), GraphModel.decodeTyped(old));
            // 读得懂 bitcask 写的
            Map<String, Object> bc = new LinkedHashMap<>(M("t", "12:00", "bs", L("true", "false"), "f", "-Infinity", "_types", L("bs\tlist:bool", "f\tfloat", "t\tlocaltime")));
            assertEquals(M("t", LocalTime.of(12, 0), "bs", L(true, false), "f", Double.NEGATIVE_INFINITY), GraphModel.decodeTyped(bc));
        });
    }

    @Test
    void spatialPoints() {
        withGraph(db -> {
            assertEquals(L(L(7203, 1.0, 2.0, "cartesian")), rows(db, "WITH point({x: 1, y: 2}) AS p RETURN p.srid, p.x, p.y, p.crs"));
            assertEquals(L(L(4326, 13.4, 52.5)), rows(db, "WITH point({longitude: 13.4, latitude: 52.5}) AS p RETURN p.srid, p.longitude, p.latitude"));
            assertEquals(L(L(9157, 3.0)), rows(db, "WITH point({x: 1, y: 2, z: 3}) AS p RETURN p.srid, p.z"));
            assertEquals(L(L(5.0)), rows(db, "RETURN point.distance(point({x: 0, y: 0}), point({x: 3, y: 4}))"));
            double dist = ((Number) one(db, "RETURN distance(point({longitude: 13.4, latitude: 52.5}), point({longitude: 2.35, latitude: 48.86}))")).doubleValue();
            assertTrue(870000 < dist && dist < 885000, "柏林—巴黎大圆距离约 878 km");
            assertEquals(L(L((Object) null)), rows(db, "RETURN distance(point({x: 0, y: 0}), point({longitude: 0, latitude: 0}))"), "坐标系不同 ⇒ null");
            assertEquals(L(L(true, false, true)), rows(db, "WITH point({x: 0, y: 0}) AS ll, point({x: 2, y: 2}) AS ur RETURN point.withinBBox(point({x: 1, y: 1}), ll, ur), point.withinBBox(point({x: 3, y: 1}), ll, ur), point.withinBBox(point({longitude: 179, latitude: 0}), point({longitude: 170, latitude: -10}), point({longitude: -170, latitude: 10}))"));
            assertEquals(L(L(true, false, null)), rows(db, "RETURN point({x: 1, y: 2}) = point({x: 1.0, y: 2.0}), point({x: 1, y: 2}) = point({x: 1, y: 3}), point({x: null, y: 2})"));
            q(db, "CREATE (:PT {p: point({x: 1, y: 2}), ps: [point({x: 0, y: 0}), point({x: 1, y: 1})]})");
            assertEquals(L(L(2.0, 2)), rows(db, "MATCH (n:PT) RETURN n.p.y, size(n.ps)"));
            assertEquals(L(L("point({x: 1.0, y: 2.0, crs: 'cartesian'})")), rows(db, "RETURN toString(point({x: 1, y: 2}))"));
        });
    }

    @Test
    void userFunctionsAndDefaults() {
        withGraph(db -> {
            Cypher.registerFunction("my.slug", 1, 1, (b, args) -> args.get(0) == null ? null : String.valueOf(args.get(0)).toLowerCase().replace(" ", "-"));
            Cypher.registerFunction("my.add", 1, 2, (b, args) -> {
                Object bb = args.size() > 1 ? args.get(1) : null;
                return (Long) args.get(0) + (bb == null ? 10L : (Long) bb);
            });
            Cypher.registerProcedure("my.range", List.of(ProcParam.of("n", "INTEGER"), ProcParam.withDefault("step", "INTEGER", 1L)),
                    List.of(ProcParam.of("i", "INTEGER")), (ctx, args) -> {
                        long n = (Long) args.get("n"), step = (Long) args.get("step");
                        List<Map<String, Object>> out = new ArrayList<>();
                        for (long i = 1; i < n + 1; i += step) out.add(Map.of("i", i));
                        return out;
                    });
            try {
                assertEquals(L(L("hello-world", null, 11, 3)), rows(db, "RETURN my.slug('Hello World'), my.slug(null), my.add(1), my.add(1, 2)"));
                assertEquals(L(L(L(1, 2, 3), L(1, 3))), rows(db, "CALL my.range(3) YIELD i WITH collect(i) AS a CALL my.range(3, 2) YIELD i RETURN a, collect(i)"));
                Map<String, List<String>> reg = Cypher.registered();
                assertEquals(List.of("my.range"), reg.get("procedures"));
                assertEquals(List.of("my.add", "my.slug"), reg.get("functions"));
                assertThrows(IllegalArgumentException.class, () -> Cypher.registerFunction("date.mine", 1, 1, (b, args) -> args.get(0)), "内置命名空间不行");
                assertEquals("semantic", stage(() -> q(db, "RETURN my.add(1, 2, 3)")));
                assertEquals("exec", stage(() -> q(db, "RETURN my.add('x')")), "函数自己出错 ⇒ 执行错误");
            } finally {
                for (String n : List.of("my.slug", "my.add", "my.range")) Cypher.unregisterFunction(n);
            }
            assertEquals("UnknownFunction", detail(() -> q(db, "RETURN my.slug('x')")), "注销之后");
        });
    }

    @Test
    void errorTypesAndDetails() {
        withGraph(db -> {
            String[][] cases = {
                    {"RETURN x", "SyntaxError", "UndefinedVariable"},
                    {"MATCH (a) MATCH ()-[a]->() RETURN a", "SyntaxError", "VariableTypeConflict"},
                    {"RETURN 1 / 0", "ArithmeticError", "DivisionByZero"},
                    {"RETURN $nope", "ParameterMissing", "MissingParameter"},
                    {"CALL no.such()", "ProcedureError", "ProcedureNotFound"},
                    {"RETURN nosuch(1)", "SyntaxError", "UnknownFunction"},
                    {"RETURN 1 +", "SyntaxError", "UnexpectedSyntax"},
                    {"RETURN 12abc", "SyntaxError", "InvalidNumberLiteral"},
                    {"RETURN [1][\"a\"]", "TypeError", "ListElementAccessByNonInteger"},
                    {"MATCH (n) RETURN count(count(*))", "SyntaxError", "NestedAggregation"}};
            for (String[] c : cases) {
                CypherException e = err(() -> q(db, c[0]));
                assertNotNull(e, c[0]);
                assertEquals(List.of(c[1], c[2]), java.util.Arrays.asList(e.type(), e.detail()), c[0]);
            }
        });
    }

    @Test
    void relationshipIdsNotReused() {
        withGraph(db -> {
            q(db, "CREATE (:RI {n: 1})-[:K]->(:RI {n: 2})");
            List<?> id0 = (List<?>) one(db, "MATCH (:RI {n: 1})-[r:K]->() RETURN id(r)");
            q(db, "MATCH (:RI {n: 1})-[r:K]->() DELETE r");
            q(db, "MATCH (a:RI {n: 1}), (b:RI {n: 2}) CREATE (a)-[:K]->(b)");
            List<?> id1 = (List<?>) one(db, "MATCH (:RI {n: 1})-[r:K]->() RETURN id(r)");
            assertEquals(id0.subList(0, 3), id1.subList(0, 3));
            assertEquals((Long) id0.get(3) + 1, id1.get(3), "跨语句也不复用删掉的 rank");
        });
    }

    @Test
    void writeCapOption() {
        withGraph(db -> {
            CypherException e = err(() -> q(db, "UNWIND range(1, 20) AS i CREATE (:WC {i: i})", null, cap(10)));
            assertNotNull(e);
            assertEquals("SemanticError", e.type());
            assertTrue(e.cypherMessage().contains("at most 10"), e.cypherMessage());
            assertEquals(L(L(0)), rows(db, "MATCH (n:WC) RETURN count(n)"), "整条回滚");
            assertEquals(20L, q(db, "UNWIND range(1, 20) AS i CREATE (:WC {i: i})").stats().get("nodes-created"), "缺省不限");
        });
    }

    // ── D-43 ─────────────────────────────────────────────

    @Test
    void isNanFunction() {
        withGraph(db -> {
            assertEquals(L(L(true, false, false, null)), rows(db, "RETURN isNaN(0.0 / 0.0), isNaN(1.5), isNaN(3), isNaN(null)"));
            assertEquals("InvalidArgumentType", detail(() -> q(db, "RETURN isNaN('x')")));
        });
    }

    @Test
    void writeModeProcedures() {
        withGraph(db -> {
            Cypher.registerWriteProcedure("my.tag", List.of(ProcParam.of("label", "STRING"), ProcParam.of("n", "INTEGER")),
                    List.of(ProcParam.of("made", "INTEGER")), (ctx, args) -> {
                        Object label = args.get("label");
                        CypherResult r = ctx.query("UNWIND range(1, $n) AS i CREATE (x:WP {label: $label, i: i}) RETURN count(x)",
                                M("label", label, "n", args.get("n")));
                        if ("boom".equals(label)) ctx.query("RETURN 1 / 0", Map.of());
                        return List.of(M("made", r.rows().getFirst().getFirst()));
                    });
            try {
                assertTrue(Cypher.writes("CALL my.tag('a', 3)"));
                CypherResult r = q(db, "CALL my.tag('a', 3) YIELD made RETURN made");
                assertEquals(L(L(3)), r.rows());
                assertEquals(3L, r.stats().get("nodes-created"), "内层写入计进外层的统计");
                assertEquals(L(L(3)), rows(db, "MATCH (n:WP) RETURN count(n)"));
                assertEquals("DivisionByZero", detail(() -> q(db, "CALL my.tag('boom', 5) YIELD made RETURN made")));
                assertEquals(L(L(3)), rows(db, "MATCH (n:WP) RETURN count(n)"), "出错 ⇒ 过程里写的也回滚");
                assertNotNull(err(() -> q(db, "CALL my.tag('a', 20) YIELD made RETURN made", null, cap(10))), "写入上限也管过程里的写");
                // clj 版还测 {:mode :admin} ⇒ IllegalArgumentException；Java 用两个注册方法区分读 / 写，没有非法 mode 可传
            } finally {
                Cypher.unregisterFunction("my.tag");
            }
        });
    }

    static final String SP_EDGES = "UNWIND [[1,2],[2,3],[3,6],[1,4],[4,6],[1,5],[5,6],[2,4]] AS e\n";

    @Test
    void layeredPathSearch() {
        withGraph(db -> {
            q(db, "UNWIND range(1, 6) AS i CREATE (:SP {n: i})");
            q(db, SP_EDGES + "             MATCH (a:SP {n: e[0]}), (b:SP {n: e[1]}) CREATE (a)-[:K]->(b)");
            assertTrue(heads(ops("MATCH p = SHORTEST 2 (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN p")).contains("path-search"));
            assertEquals(L(2), lens(db, "MATCH p = SHORTEST 1 (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(2, 2), lens(db, "MATCH p = SHORTEST 2 (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(2, 2, 3), lens(db, "MATCH p = SHORTEST 3 (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(2, 2, 3, 3), lens(db, "MATCH p = SHORTEST 2 GROUPS (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(2, 2), lens(db, "MATCH p = ALL SHORTEST (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(2), lens(db, "MATCH p = ANY SHORTEST (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) RETURN length(p)"));
            assertEquals(L(3, 3), lens(db, "MATCH p = SHORTEST 2 (a:SP {n: 1})-[:K*3..]->(b:SP {n: 6}) RETURN length(p)"), "下限");
            assertEquals(L(), lens(db, "MATCH p = SHORTEST 2 (a:SP {n: 1})-[:K*..1]->(b:SP {n: 6}) RETURN length(p)"), "上限内没有");
            assertEquals(L(L(L(1, 5, 6))), rows(db, "MATCH p = SHORTEST 1 ((a:SP {n: 1})-[:K*]->(b:SP {n: 6}) WHERE none(x IN nodes(p) WHERE x.n = 4))\n                                 RETURN [x IN nodes(p) | x.n]"),
                    "挑选前的条件在搜索里过滤");
            assertEquals(L(), rows(db, "MATCH p = SHORTEST 1 (a:SP {n: 1})-[:K*]->(b:SP {n: 6}) WHERE length(p) > 2 RETURN p"), "MATCH 的 WHERE 在挑选之后");
            assertEquals(L(2, 2), lens(db, "MATCH (a:SP {n: 1}), (b:SP {n: 6}) MATCH p = SHORTEST 2 (a)-[:K*]->(b) RETURN length(p)"), "两端是前面绑的");
            assertEquals(L(L(2), L(2)), rows(db, "MATCH p = SHORTEST 1 (a:SP {n: 1})-[:K*]->(b:SP) WHERE b.n IN [3, 6] RETURN length(p) ORDER BY b.n"),
                    "终点没锚定 ⇒ 仍按 (起点, 终点) 分组挑");
        });
    }

    @Test
    void layeredPathSearchDenseGraph() {
        withGraph(db -> {
            // 9 个点的完全有向图：路径（关系不重复）多到列不完，分层搜索第一层就停
            q(db, "UNWIND range(0, 8) AS i CREATE (:CG {n: i})");
            q(db, "MATCH (a:CG), (b:CG) WHERE a <> b CREATE (a)-[:E]->(b)");
            assertEquals(L(L(1), L(2), L(2)), timedRows(db, "MATCH p = SHORTEST 3 (a:CG {n: 0})-[:E*]->(b:CG {n: 8}) RETURN length(p)"));
        });
    }

    // ── D-44 ─────────────────────────────────────────────

    @Test
    void pathSearchFreeEndsAndQpp() {
        withGraph(db -> {
            q(db, "UNWIND range(1, 6) AS i CREATE (:SQ {n: i})");
            q(db, SP_EDGES + "             MATCH (a:SQ {n: e[0]}), (b:SQ {n: e[1]}) CREATE (a)-[:K]->(b)");
            java.util.function.Function<String, List<?>> search = s -> opNamed(ops(s), "path-search");
            // 终点自由：搜索把它绑上，按终点分组
            assertEquals(kw("free"), Clj.nth(search.apply("MATCH p = SHORTEST 2 (a:SQ {n: 1})-[:K*]->(b) RETURN p"), 12));
            assertEquals(L(L(2, 1), L(3, 2), L(4, 1), L(4, 2), L(5, 1), L(6, 2), L(6, 2)),
                    rows(db, "MATCH p = SHORTEST 2 (a:SQ {n: 1})-[:K*]->(b) RETURN b.n, length(p) ORDER BY b.n, length(p)"));
            assertEquals(L(L(4, 1), L(4, 2), L(6, 2), L(6, 2)),
                    rows(db, "MATCH p = SHORTEST 2 (a:SQ {n: 1})-[:K*]->(b) WHERE b.n IN [4, 6] RETURN b.n, length(p) ORDER BY b.n, length(p)"),
                    "MATCH 的 WHERE 在挑选之后");
            // 起点自由：从右端反着搜，路径仍是模式的方向
            assertEquals(Boolean.TRUE, Clj.nth(search.apply("MATCH p = SHORTEST 2 (a)-[:K*]->(b:SQ {n: 6}) RETURN p"), 13));
            assertEquals(L(L(1, 2), L(1, 2), L(2, 2), L(2, 2), L(3, 1), L(4, 1), L(5, 1)),
                    rows(db, "MATCH p = SHORTEST 2 (a)-[:K*]->(b:SQ {n: 6}) RETURN a.n, length(p) ORDER BY a.n, length(p)"));
            assertEquals(L(L(L(1, 2, 3, 6)), L(L(1, 4, 6)), L(L(1, 5, 6))),
                    rows(db, "MATCH p = SHORTEST 3 (a)-[:K*]->(b:SQ {n: 6}) WHERE a.n = 1 RETURN [x IN nodes(p) | x.n] ORDER BY [x IN nodes(p) | x.n]"),
                    "反着搜：路径按模式方向拼");
            assertEquals(L(L(L(1, 4, 6))), rows(db, "MATCH p = ANY SHORTEST (a {n: 1})-[:K*]->(b:SQ {n: 6}) WHERE a.n = 1 AND 4 IN [x IN nodes(p) | x.n] RETURN [x IN nodes(p) | x.n]"),
                    "(a {n: 1}) 有属性 ⇒ 两端都先找出来；WHERE 在挑选之后");
            // 量化路径：逐轮加深
            assertNotNull(search.apply("MATCH p = SHORTEST 2 (a:SQ {n: 1}) ((x)-[:K]->(y)){1,} (b:SQ {n: 6}) RETURN p"));
            assertEquals(L(L(2, L(1, 4)), L(2, L(1, 5))),
                    rows(db, "MATCH p = SHORTEST 2 (a:SQ {n: 1}) ((x)-[:K]->(y)){1,} (b:SQ {n: 6}) RETURN length(p), [v IN x | v.n] ORDER BY [v IN x | v.n]"));
            assertEquals(L(L(3, L(1, 2, 3))),
                    rows(db, "MATCH p = SHORTEST 2 (a:SQ {n: 1}) ((x)-[:K]->(y) WHERE x.n < 4){1,} (b:SQ {n: 6}) WHERE length(p) > 0 RETURN length(p), [v IN x | v.n] ORDER BY [v IN x | v.n]"),
                    "量化路径的 WHERE 在搜索里过滤");
            assertEquals(L(L(2, 1), L(3, 2), L(4, 1), L(4, 2), L(5, 1), L(6, 2), L(6, 2)),
                    rows(db, "MATCH p = SHORTEST 2 (a:SQ {n: 1}) ((x)-[:K]->(y)){1,} (b) RETURN b.n, length(p) ORDER BY b.n, length(p)"),
                    "量化路径 + 自由终点");
            // ALL = 不挑
            List<String> hs = heads(ops("MATCH p = ALL (a:SQ {n: 1})-[:K*]->(b:SQ {n: 6}) RETURN p"));
            assertFalse(hs.contains("path-select") || hs.contains("path-search"));
            assertEquals(L(2, 2, 3, 3), lens(db, "MATCH p = ALL (a:SQ {n: 1})-[:K*]->(b:SQ {n: 6}) RETURN length(p)"));
        });
    }

    /** clj (if (vector? r) (filterv #(#{…} (first %)) r) r)。 */
    static List<List<Object>> keepFirstIn(List<List<Object>> r, long... keep) {
        if (r == null) return null;
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : r)
            for (long k : keep)
                if (Long.valueOf(k).equals(row.getFirst())) {
                    out.add(row);
                    break;
                }
        return out;
    }

    @Test
    void pathSearchDenseFreeEnd() {
        withGraph(db -> {
            q(db, "UNWIND range(0, 8) AS i CREATE (:CF {n: i})");
            q(db, "MATCH (a:CF), (b:CF) WHERE a <> b CREATE (a)-[:E]->(b)");
            assertEquals(L(L(0, 2), L(0, 2), L(1, 1), L(1, 2), L(8, 1), L(8, 2)),
                    keepFirstIn(timedRows(db, "MATCH p = SHORTEST 2 (a:CF {n: 0})-[:E*]->(b) RETURN b.n, length(p) ORDER BY b.n, length(p)"), 0, 1, 8),
                    "自由终点：每个可达点都凑够就停（起点自己经环路 2 跳）");
            assertEquals(L(L(1), L(2)), timedRows(db, "MATCH p = SHORTEST 2 (a:CF {n: 0}) ((x)-[:E]->(y)){1,} (b:CF {n: 8}) RETURN length(p) ORDER BY length(p)"),
                    "量化路径：逐轮加深，长度 2 就停");
        });
    }

    @Test
    void qppSubpathVariableInWhere() {
        withGraph(db -> {
            q(db, "CREATE (:QW {n:1})-[:K]->(:QW {n:2})-[:K]->(:QW {n:3})-[:K]->(:QW {n:4})");
            assertEquals(L(L(1, 2), L(2, 3), L(3, 4)), rows(db, "MATCH (:QW {n:1}) (s = (a)-[:K]->(b) WHERE length(s) = 1){1,3} (x) RETURN size(s), x.n ORDER BY x.n"));
        });
    }

    @Test
    void writeProcedureStatementCache() {
        withGraph(db -> {
            AtomicInteger n = new AtomicInteger();
            Cypher.registerWriteProcedure("my.bump", List.of(), List.of(), (ctx, args) -> {
                int i = n.incrementAndGet();
                ctx.query("CREATE (:QC {i: $i})", M("i", i));
                return List.of();
            });
            try {
                q(db, "UNWIND range(1, 5) AS i CALL my.bump()");
                assertEquals(L(L(5)), rows(db, "MATCH (n:QC) RETURN count(n)"));
            } finally {
                Cypher.unregisterProcedure("my.bump");
            }
        });
    }

    // ── D-45 ─────────────────────────────────────────────

    @Test
    void shortestPrefixReturnsCycles() {
        withGraph(db -> {
            q(db, "CREATE (a:TC {n: 1})-[:K]->(:TC {n: 2})-[:K]->(:TC {n: 3})-[:K]->(a)");
            List<String> hs = heads(ops("MATCH p = ANY SHORTEST (a:TC {n: 1})-[:K*]->(b) RETURN p"));
            assertTrue(hs.contains("path-search"));
            assertFalse(hs.contains("shortest-path") || hs.contains("all-shortest-paths"), "不再改写成 shortestPath");
            assertEquals(L(L(1, 3), L(2, 1), L(3, 2)), rows(db, "MATCH p = ANY SHORTEST (a:TC {n: 1})-[:K*]->(b) RETURN b.n, length(p) ORDER BY b.n"),
                    "终点自由：起点经环路回到自己也算（GQL）");
            assertEquals(L(L(3)), rows(db, "MATCH (a:TC {n: 1}) MATCH p = ALL SHORTEST (a)-[:K*]->(a) RETURN length(p)"), "两端是同一个点");
            assertEquals(L(L(0)), rows(db, "MATCH (a:TC {n: 1}) MATCH p = ANY SHORTEST (a)-[:K*0..]->(a) RETURN length(p)"), "下限 0 ⇒ 长度 0");
        });
    }

    @Test
    void shortestPrefixNodeBfs() {
        withGraph(db -> {
            // 61 个点的链，相邻两点之间两条平行边：路径数 2^60，按点 BFS 即刻得到最短
            q(db, "UNWIND range(0, 60) AS i CREATE (:LC {n: i})");
            q(db, "MATCH (a:LC), (b:LC) WHERE b.n = a.n + 1 CREATE (a)-[:K {ok: true}]->(b), (a)-[:K {ok: a.n <> 30}]->(b)");
            assertEquals(L(L(60)), timedRows(db, "MATCH p = ANY SHORTEST (a:LC {n: 0})-[:K*]->(b:LC {n: 60}) RETURN length(p)"));
            assertEquals(L(L(60)), timedRows(db, "MATCH p = SHORTEST 1 (a:LC {n: 0})-[:K* {ok: true}]->(b:LC {n: 60}) RETURN length(p)"), "关系属性在 BFS 里过滤");
            assertEquals(L(L(true)), timedRows(db, "MATCH p = ANY SHORTEST (a:LC {n: 0})-[:K* {ok: true}]->(b:LC {n: 60}) RETURN all(r IN relationships(p) WHERE r.ok)"));
            assertEquals(L(L(60)), timedRows(db, "MATCH p = ANY SHORTEST (a:LC {n: 0})-[:K*]->(b:LC) RETURN count(p)"), "终点自由：每个点一条（没有环路 ⇒ 不含起点自己）");
        });
    }

    @Test
    void pathSearchDeadEndPruning() {
        withGraph(db -> {
            // 起点连着一个 12 点的完全有向图（到不了终点），另有两条长 8 的路到终点：不剪枝第 8 层的部分路径上亿条
            q(db, "CREATE (:DE {n: 0}), (:DE {n: 99})");
            q(db, "UNWIND range(1, 12) AS i CREATE (:DD {n: i})");
            q(db, "MATCH (a:DD), (b:DD) WHERE a <> b CREATE (a)-[:K]->(b)");
            q(db, "MATCH (s:DE {n: 0}), (d:DD) CREATE (s)-[:K]->(d)");
            q(db, "UNWIND [1, 2] AS w MATCH (s:DE {n: 0}), (t:DE {n: 99})\n             CREATE (s)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(:DP)-[:K]->(t)");
            assertEquals(L(L(8), L(8)), timedRows(db, "MATCH p = SHORTEST 2 (a:DE {n: 0})-[:K*]->(b:DE {n: 99}) RETURN length(p)"));
        });
    }

    @Test
    void multiStepPathDeepening() {
        withGraph(db -> {
            q(db, "UNWIND range(0, 8) AS i CREATE (:MG {n: i})");
            q(db, "MATCH (a:MG), (b:MG) WHERE a <> b CREATE (a)-[:E]->(b)");
            q(db, "MATCH (g:MG {n: 0}) CREATE (:MS)-[:R]->(g)");
            assertTrue(heads(ops("MATCH p = SHORTEST 2 (s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN p")).contains("path-deepen"));
            assertEquals(L(L(2), L(3)), timedRows(db, "MATCH p = SHORTEST 2 (s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN length(p) ORDER BY length(p)"),
                    "完全图：列全部路径做不完，逐轮加深到长度 3 就停");
            assertEquals(L(L(3, L(0, 3, 8))), timedRows(db, "MATCH p = SHORTEST 1 ((s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8}) WHERE any(v IN nodes(p) WHERE v.n = 3))\n                                           RETURN length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n]"),
                    "挑选前的条件");
            List<List<Object>> r = timedRows(db, "MATCH p = SHORTEST 1 (s:MS)-[:R]->(x)-[:E*]->(b) RETURN b.n, length(p) ORDER BY b.n");
            assertEquals(L(L(0, 3), L(1, 2), L(8, 2)), keepFirstIn(r, 0, 1, 8), "终点自由：按终点分组");
            assertNotNull(r);
            assertEquals(9, r.size());
            assertEquals(L(L(2, L(0, 8))), timedRows(db, "MATCH p = SHORTEST 1 (s)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n]"),
                    "起点自由：从右端反着加深");
        });
    }

    // ── D-47 ─────────────────────────────────────────────

    static List<?> pdOp(String s) {
        return opNamed(ops(s), "path-deepen");
    }

    @Test
    void pathDeepenCycles() {
        withGraph(db -> {
            q(db, "CREATE (a:CY {n: 1})-[:K]->(b:CY {n: 2})-[:K]->(a), (b)-[:K]->(:CY {n: 3})-[:K]->(a)");
            List<?> pd = pdOp("MATCH p = SHORTEST 2 (a:CY)-[:K]->(b)-[:K]->(a) RETURN p");
            assertNotNull(pd, "首尾同一变量（环路）也逐轮加深");
            assertTrue(java.util.Objects.equals(pd.get(2), pd.get(3)) && kw("bound") == pd.get(4), "终点 = 起点的 :bound 模式");
            assertEquals(L(L(L(1, 2, 1), 2), L(L(2, 1, 2), 2)),
                    timedRows(db, "MATCH p = SHORTEST 2 (a:CY)-[:K]->(b)-[:K]->(a) RETURN [v IN nodes(p) | v.n], length(p) ORDER BY a.n"));
            assertEquals(L(L(2)), timedRows(db, "MATCH p = ANY SHORTEST (a:CY)-[:K]->(b)-[:K]->(a) RETURN count(p)"));
            assertEquals(L(L(L(1, 2, 1), 2)),
                    timedRows(db, "MATCH (b:CY {n: 2}), p = SHORTEST 1 (a:CY)-[:K]->(b)-[:K]->(a) RETURN [v IN nodes(p) | v.n], length(p) ORDER BY a.n"),
                    "中间节点先由别的模式绑定");
        });
    }

    /** MS -R-> 0 / 3；MG 是 9 个点的完全有向图。 */
    static void msGraph(Bitcask db) {
        q(db, "UNWIND range(0, 8) AS i CREATE (:MG {n: i})");
        q(db, "MATCH (a:MG), (b:MG) WHERE a <> b CREATE (a)-[:E]->(b)");
        q(db, "CREATE (:MS)");
        q(db, "MATCH (s:MS), (g:MG) WHERE g.n IN [0, 3] CREATE (s)-[:R]->(g)");
    }

    @Test
    void pathDeepenInternalNodeElsewhere() {
        withGraph(db -> {
            msGraph(db);
            String s = "MATCH (x:MG {n: 3}) WITH x MATCH p = SHORTEST 1 (s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n]";
            assertNotNull(pdOp(s), "中间节点前面已绑定：仍逐轮加深");
            assertEquals(L(L(2, L(3, 8))), timedRows(db, s));
            assertEquals(L(L(2, L(3, 8))),
                    timedRows(db, "MATCH (x:MG {n: 3}), p = SHORTEST 1 (s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n]"),
                    "中间节点在同一 MATCH 的另一个模式里：那个模式先绑上它，展开到它时收口");
            assertEquals(L(L(2, L(0, 8))),
                    timedRows(db, "MATCH (x:MG {n: 0}), p = SHORTEST 1 (s)-[:R]->(x)-[:E*]->(b:MG {n: 8}) RETURN length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n]"),
                    "起点自由：从右端反着加深");
            // y -E-> x：x 是 0 或 3（没有自环）
            assertEquals(L(L(0, 2, L(3, 8)), L(1, 2, L(0, 8)), L(1, 2, L(3, 8)), L(2, 2, L(0, 8)), L(2, 2, L(3, 8)), L(3, 2, L(0, 8)), L(4, 2, L(0, 8)), L(4, 2, L(3, 8)),
                            L(5, 2, L(0, 8)), L(5, 2, L(3, 8)), L(6, 2, L(0, 8)), L(6, 2, L(3, 8)), L(7, 2, L(0, 8)), L(7, 2, L(3, 8)), L(8, 2, L(0, 8)), L(8, 2, L(3, 8))),
                    rows(db, "MATCH (y:MG)-[:E]->(x), p = SHORTEST 1 (s:MS)-[:R]->(x)-[:E*]->(b:MG {n: 8})\n                         RETURN y.n, length(p), [v IN nodes(p) WHERE v.n IS NOT NULL | v.n] ORDER BY y.n, x.n"));
        });
    }

    @Test
    void pathSearchFreeEndConditions() {
        withGraph(db -> {
            msGraph(db);
            q(db, "CREATE (a:EN {n: 0})-[:K]->(:EN {n: 1})-[:K]->(:EN {n: 2})-[:K]->(:EN {n: 3})");
            List<?> ps = opNamed(ops("MATCH p = SHORTEST 1 ((a:EN {n: 0})-[:K*]->(b) WHERE b.n >= 2) RETURN b.n, length(p)"), "path-search");
            assertEquals(clj("[[:op :>= [:prop [:var 'b'] 'n'] [:lit 2]]]"), pr(ps.getLast()), "单段：只引用自由终点的挑选前条件收进可达点上界");
            assertEquals(L(L(2, 2), L(3, 3)), timedRows(db, "MATCH p = SHORTEST 1 ((a:EN {n: 0})-[:K*]->(b) WHERE b.n >= 2) RETURN b.n, length(p) ORDER BY b.n"));
            assertEquals(L(L(2, 2), L(3, 3)), timedRows(db, "MATCH p = SHORTEST 1 ((a:EN {n: 0})-[:K*]->(b) WHERE b.n >= 2 AND a.n = 0) RETURN b.n, length(p) ORDER BY b.n"),
                    "还引用起点的条件不收");
            assertEquals(clj("[[:op :> [:prop [:var 'b'] 'n'] [:lit 6]]]"), pr(pdOp("MATCH p = SHORTEST 1 ((s:MS)-[:R]->(x)-[:E*]->(b) WHERE b.n > 6) RETURN b.n").getLast()));
            assertEquals(L(L(7, 2), L(8, 2)), timedRows(db, "MATCH p = SHORTEST 1 ((s:MS)-[:R]->(x)-[:E*]->(b) WHERE b.n > 6) RETURN b.n, length(p) ORDER BY b.n"));
            assertEquals(L(L(7, 0, 2), L(8, 0, 2)),
                    timedRows(db, "MATCH p = SHORTEST 1 ((s:MS)-[:R]->(x)-[:E*]->(b) WHERE b.n > 6 AND x.n < 2) RETURN b.n, x.n, length(p) ORDER BY b.n"),
                    "多段：只收终点那一条，其余的照常在候选上判断");
        });
    }

    @Test
    void pathDeepenContinuationRounds() {
        withGraph(db -> {
            // 链 + 跨 3 的捷径：到 9 最短 4 跳、第二短 6 跳——接续式加深要一轮一跳地走上六七轮
            q(db, "UNWIND range(0, 9) AS i CREATE (:CH {n: i})");
            q(db, "UNWIND range(0, 8) AS i MATCH (a:CH {n: i}), (b:CH {n: i + 1}) CREATE (a)-[:N]->(b)");
            q(db, "UNWIND range(0, 6) AS i MATCH (a:CH {n: i}), (b:CH {n: i + 3}) CREATE (a)-[:N]->(b)");
            q(db, "MATCH (a:CH {n: 0}) CREATE (:CS)-[:R]->(a)");
            String nodes = "[v IN nodes(p) WHERE v.n IS NOT NULL | v.n]";
            assertEquals(L(L(4), L(6)), timedRows(db, "MATCH p = SHORTEST 2 (s:CS)-[:R]->(x)-[:N*]->(b:CH {n: 9}) RETURN length(p) ORDER BY length(p)"));
            assertEquals(L(L(4, L(0, 3, 6, 9))), timedRows(db, "MATCH p = SHORTEST 1 (s:CS)-[:R]->(x)-[:N*]->(b:CH {n: 9}) RETURN length(p), " + nodes));
            assertEquals(L(L(4, L(0, 3, 6, 9))), timedRows(db, "MATCH p = SHORTEST 1 (s:CS)-[:R]->(x)-[:N* 2..4]->(b:CH {n: 9}) RETURN length(p), " + nodes), "有下限的变长段");
            assertEquals(L(L(4, L(0, 3, 6, 9))),
                    timedRows(db, "MATCH p = SHORTEST 1 (s:CS)-[:R]->(x)((u)-[:N]->(v) WHERE u.n <> 5){1,4}(b:CH {n: 9}) RETURN length(p), " + nodes),
                    "量化路径：续体接着走时再登记续体");
            assertEquals(L(L(1, 2), L(2, 3), L(3, 2), L(4, 3), L(5, 4), L(6, 3), L(7, 4), L(8, 5), L(9, 4)),
                    timedRows(db, "MATCH p = SHORTEST 1 (s:CS)-[:R]->(x)((u)-[:N]->(v) WHERE u.n <> 5){1,4}(b:CH) RETURN b.n, length(p) ORDER BY b.n"),
                    "量化路径、终点自由");
        });
    }

    // ── D-48 / D-49 / D-50：剪枝 ─────────────────────────

    /** s → d0(98) → d(99) 死胡同；s → c1 接 8 点完全图（类型 E）。 */
    static void pruneGraph(Bitcask db, String label) {
        q(db, "CREATE (s:" + label + " {n: 0})-[:E]->(:" + label + " {n: 98})-[:E]->(:" + label + " {n: 99}), (s)-[:E]->(:" + label + " {n: 1})");
        q(db, "UNWIND range(2, 8) AS i CREATE (:" + label + " {n: i})");
        q(db, "MATCH (a:" + label + "), (b:" + label + ") WHERE a <> b AND 1 <= a.n <= 8 AND 1 <= b.n <= 8 CREATE (a)-[:E]->(b)");
    }

    /** [[1 2] … [8 2]] + extra。 */
    static List<Object> clique(List<?>... extra) {
        List<Object> out = new ArrayList<>();
        for (int i = 1; i < 9; i++) out.add(L(i, 2));
        for (List<?> e : extra) out.add(e);
        return out;
    }

    @Test
    void pathSearchPrunesExhaustedEnds() {
        // s → d0 → d 是死胡同（d0、d 各只有一条路径，永远凑不够 2 条）；s → c1 接 8 个点的完全图（简单路径多到走不完）。
        withGraph(db -> {
            pruneGraph(db, "PB");
            assertEquals(clique(L(98, 1), L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PB {n: 0})-[:E*]->(b) RETURN b.n, count(*)")),
                    "单段变长：死胡同的终点去掉");
            assertEquals(clique(L(98, 1), L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PB {n: 0}) ((x)-[:E]->(y)){1,} (b) RETURN b.n, count(*)")),
                    "量化路径：截断处的点同样用来剪");
            assertEquals(clique(L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PB {n: 0})-[:E]->(m)-[:E*]->(b) RETURN b.n, count(*)")),
                    "多段逐轮加深：终点自由");
            assertEquals(L(L(2)), timedRows(db, "MATCH (s:PB {n: 0}), (d:PB {n: 99}) MATCH p = SHORTEST 2 (s)-[:E]->(m)-[:E*]->(d) RETURN length(p)"),
                    "多段逐轮加深：终点已绑定、只有一条路径 ⇒ 前沿到不了它就停");
            assertTrue(heads(ops("MATCH p = SHORTEST 2 (s:PB {n: 0})-[:E]->(m)-[:E*]->(b) RETURN b.n")).contains("path-deepen"));
        });
    }

    @Test
    void pathSearchPrunesPerPartialPath() {
        // 同 D-48 的图，但按无方向匹配：全局可达剪不掉；逐条排除已用的关系（桥 s – c1）后就到不了 ⇒ 去掉
        withGraph(db -> {
            pruneGraph(db, "PU");
            assertEquals(clique(L(98, 1), L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PU {n: 0})-[:E*]-(b) RETURN b.n, count(*)")),
                    "单段变长、无方向：起点 s 自己与死胡同都去掉");
            assertEquals(clique(L(98, 1), L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PU {n: 0}) ((x)-[:E]-(y)){1,} (b) RETURN b.n, count(*)")),
                    "量化路径、无方向");
            assertEquals(clique(L(99, 1)), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PU {n: 0})-[:E]-(m)-[:E*]-(b) RETURN b.n, count(*)")),
                    "多段、无方向、终点自由");
            assertEquals(L(L(2)), timedRows(db, "MATCH (s:PU {n: 0}), (d:PU {n: 99}) MATCH p = SHORTEST 2 (s)-[:E]-(m)-[:E*]-(d) RETURN length(p)"),
                    "多段、无方向、终点已绑定");
        });
    }

    @Test
    void pathSearchPrunesWithTrailWitness() {
        // 至少 60 跳：关系互不重复的接续最多 59 跳 ⇒ 立即返回空；至少 3 跳：d、t 只有重走 d – t 才凑得够，深搜确认没有
        withGraph(db -> {
            pruneGraph(db, "PW");
            assertEquals(L(), timedRows(db, "MATCH p = SHORTEST 2 (s:PW {n: 0})-[:E*60..]-(b) RETURN b.n"), "单段变长");
            assertEquals(L(), timedRows(db, "MATCH p = SHORTEST 2 (s:PW {n: 0}) ((x)-[:E]-(y)){60,} (b) RETURN b.n"), "量化路径（下限 = 迭代下限 × 一次迭代的跳数）");
            assertEquals(L(), timedRows(db, "MATCH p = SHORTEST 2 (s:PW {n: 0})-[:E]-(m)-[:E*59..]-(b) RETURN b.n"), "多段、终点自由");
            assertEquals(L(), timedRows(db, "MATCH (s:PW {n: 0}), (d:PW {n: 99}) MATCH p = SHORTEST 2 (s)-[:E*60..]-(d) RETURN length(p)"), "单段、终点已绑定");
            assertEquals(L(), timedRows(db, "MATCH (s:PW {n: 0}), (d:PW {n: 99}) MATCH p = SHORTEST 2 (s)-[:E]-(m)-[:E*59..]-(d) RETURN length(p)"), "多段、终点已绑定");
            assertEquals(clique(), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PW {n: 0})-[:E*3..]-(b) RETURN b.n, count(*)")),
                    "至少 3 跳：得把同一条关系走两遍才凑得够的 d、t 去掉");
            assertEquals(clique(), sorted(timedRows(db, "MATCH p = SHORTEST 2 (s:PW {n: 0})-[:E]-(m)-[:E*2..]-(b) RETURN b.n, count(*)")),
                    "多段：同上");
        });
    }

    @Test
    void pathSearchPrunesGroupedFallback() {
        // 预算调成 0：逐条剪一开始就超 ⇒ 按（第几步, 点）分组、不走组内共同用过的关系——照样剪得掉。
        // clj 用 (binding [exec/*prune-budget* [0 0]] …)，future 会带上绑定；Java 的 ThreadLocal 不跨线程，所以在执行线程里设置
        withGraph(db -> {
            pruneGraph(db, "PG");
            String[] qs = {
                    "MATCH p = SHORTEST 2 (s:PG {n: 0})-[:E*]-(b) RETURN b.n, count(*)",
                    "MATCH p = SHORTEST 2 (s:PG {n: 0}) ((x)-[:E]-(y)){1,} (b) RETURN b.n, count(*)",
                    "MATCH p = SHORTEST 2 (s:PG {n: 0})-[:E]-(m)-[:E*]-(b) RETURN b.n, count(*)"};
            List<Object> want0 = clique(L(98, 1), L(99, 1)), want2 = clique(L(99, 1));
            List<Object>[] wants = new List[]{want0, want0, want2};
            for (int i = 0; i < qs.length; i++) {
                String s = qs[i];
                List<List<Object>> got = timed(() -> {
                    List<Object> prev = ExecPaths.PRUNE_BUDGET.get();
                    ExecPaths.PRUNE_BUDGET.set(List.of(0L, 0L));
                    try {
                        return rows(db, s);
                    } finally {
                        ExecPaths.PRUNE_BUDGET.set(prev);
                    }
                });
                assertEquals(wants[i], sorted(got), s);
            }
        });
    }
}
