package io.ailink.agentforge.bitcask.cypher;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.function.Executable;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.bitcask.Options;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.VertexInfo;

/**
 * Cypher 测试共用（clj 的 cypher_*_test.clj 里各自的 err / rows / col / one 小工具 + test_support）：
 * 原生库不在就跳过；期望值用 {@link #L} / {@link #M} 写（Integer ⇒ Long，与 clj 的整数口径一致）；
 * 报错捕获成 {@link CypherException}（stage / type / detail / cypherMessage / line / column）。
 */
final class CypherTestSupport {
    private CypherTestSupport() {
    }

    /** 原生库不在 ⇒ 跳过（打印理由）。 */
    static void assumeNative() {
        Assumptions.assumeTrue(BitcaskRuntime.isAvailable(), () -> "SKIP: libbitcask 不在（-Pbitcask.lib=…）：" + BitcaskRuntime.unavailableReason());
    }

    // ═══ 期望值构造 ═══

    /** 规范化：Integer / Short / Byte ⇒ Long，Float ⇒ Double；列表 / map 递归。 */
    static Object norm(Object x) {
        if (x instanceof Integer || x instanceof Short || x instanceof Byte) return ((Number) x).longValue();
        if (x instanceof Float f) return f.doubleValue();
        if (x instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), norm(v)));
            return out;
        }
        if (x instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object e : c) out.add(norm(e));
            return out;
        }
        return x;
    }

    /** clj 向量：允许 null 元素（单个 null 写 {@code L((Object) null)}）。 */
    static List<Object> L(Object... xs) {
        List<Object> out = new ArrayList<>(xs.length);
        for (Object x : xs) out.add(norm(x));
        return out;
    }

    /** clj map（键转字符串，值可为 null）：{@code M("k", 1, "j", null)}。 */
    static Map<String, Object> M(Object... kvs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) out.put(String.valueOf(kvs[i]), norm(kvs[i + 1]));
        return out;
    }

    /** 0..n-1 ⇒ Long 列表（clj range）。 */
    static List<Object> range(long from, long to) {
        List<Object> out = new ArrayList<>();
        for (long i = from; i < to; i++) out.add(i);
        return out;
    }

    // ═══ 执行 ═══

    /** 写语句冲突时不限次重跑（clj {:retries :infinity}）。 */
    static final Cypher.Options INFINITE = new Cypher.Options(-1, null);

    /** 一条写语句最多写 n 个顶点（clj {:max-write-vertices n}）。 */
    static Cypher.Options cap(long n) {
        return new Cypher.Options(8, n);
    }

    static CypherResult q(Bitcask db, String s) {
        return Cypher.execute(db, s, Map.of());
    }

    static CypherResult q(Bitcask db, String s, Map<String, ?> p) {
        return Cypher.execute(db, s, p == null ? Map.of() : p);
    }

    static CypherResult q(Bitcask db, String s, Map<String, ?> p, Cypher.Options o) {
        return Cypher.execute(db, s, p == null ? Map.of() : p, o);
    }

    static List<List<Object>> rows(Bitcask db, String s) {
        return q(db, s).rows();
    }

    static List<List<Object>> rows(Bitcask db, String s, Map<String, ?> p) {
        return q(db, s, p).rows();
    }

    /** 每行第一列。 */
    static List<Object> col(Bitcask db, String s) {
        return firsts(rows(db, s));
    }

    static List<Object> col(Bitcask db, String s, Map<String, ?> p) {
        return firsts(rows(db, s, p));
    }

    static List<Object> firsts(List<List<Object>> rs) {
        List<Object> out = new ArrayList<>(rs.size());
        for (List<Object> r : rs) out.add(r.getFirst());
        return out;
    }

    /** 第一行第一列（clj ffirst；没有行 ⇒ null）。 */
    static Object one(Bitcask db, String s) {
        List<List<Object>> rs = rows(db, s);
        return rs.isEmpty() ? null : rs.getFirst().getFirst();
    }

    static Object one(Bitcask db, String s, Map<String, ?> p) {
        List<List<Object>> rs = rows(db, s, p);
        return rs.isEmpty() ? null : rs.getFirst().getFirst();
    }

    /** 带 30 秒超时地取行（clj timed-rows）：超时 ⇒ null（并取消）。body 在另一个线程里跑。 */
    static List<List<Object>> timedRows(Bitcask db, String s) {
        return timed(() -> rows(db, s));
    }

    static <T> T timed(Callable<T> body) {
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<T> f = ex.submit(body);
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            if (e.getCause() instanceof Error er) throw er;
            throw new IllegalStateException(e.getCause());
        } finally {
            ex.shutdownNow();
        }
    }

    // ═══ 报错 ═══

    /** 语句的 Cypher 报错；不报错 ⇒ null（别的异常照常抛出，同 clj err 只认带 :cypher/stage 的 ex-info）。 */
    static CypherException err(Executable body) {
        try {
            body.execute();
            return null;
        } catch (CypherException e) {
            return e;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** 报错的阶段（parse / semantic / plan / exec）；不报错 ⇒ null。 */
    static String stage(Executable body) {
        CypherException e = err(body);
        return e == null ? null : e.stage();
    }

    /** 报错的英文报文；不报错 ⇒ null。 */
    static String message(Executable body) {
        CypherException e = err(body);
        return e == null ? null : e.cypherMessage();
    }

    /** 报错的细节代码；不报错 ⇒ null。 */
    static String detail(Executable body) {
        CypherException e = err(body);
        return e == null ? null : e.detail();
    }

    /** 期望抛 Cypher 错误 ⇒ 回它。 */
    static CypherException mustErr(Executable body) {
        CypherException e = err(body);
        assertNotNull(e, "期望抛 CypherException");
        return e;
    }

    // ═══ 内部数据（计划）═══

    static Kw kw(String n) {
        return Kw.of(n);
    }

    /** 规划的算子列表（clj {@code (:ops (c/explain q))}）。 */
    @SuppressWarnings("unchecked")
    static List<Object> ops(String query) {
        return (List<Object>) Clj.get(Cypher.prepare(query).plan, kw("ops"));
    }

    /** 各算子的头关键字的名字。 */
    static List<String> heads(List<?> ops) {
        List<String> out = new ArrayList<>();
        for (Object o : ops) out.add(((Kw) Clj.first(o)).name);
        return out;
    }

    /** 第一个以 :name 开头的算子；没有 ⇒ null。 */
    static List<?> opNamed(List<?> ops, String name) {
        for (Object o : ops) if (Clj.first(o) == kw(name)) return (List<?>) o;
        return null;
    }

    /** clj pr-str 式文本（map 键排序）。 */
    static String pr(Object x) {
        return Printer.pr(x);
    }

    /** 期望的 pr-str 文本：单引号写成双引号（省得在 Java 里转义）。 */
    static String clj(String s) {
        return s.replace('\'', '"');
    }

    // ═══ 图 ═══

    /** 图层的顶点视图 ⇒ Cypher 的 Node（便于与结果比较）。 */
    static Node vi(Graph g, long id) {
        VertexInfo v = g.vertexInfo(id);
        return v == null ? null : new Node(v.id(), v.labels(), v.properties());
    }

    static void okInv(Graph g) {
        Graph.InvariantReport r = g.checkInvariants();
        assertTrue(r.ok(), String.valueOf(r));
    }

    // ═══ 排序（clj sort / compare）═══

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int cmp(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return Integer.compare(x.size(), y.size());
            for (int i = 0; i < x.size(); i++) {
                int c = cmp(x.get(i), y.get(i));
                if (c != 0) return c;
            }
            return 0;
        }
        if (a instanceof Comparable ca && a.getClass() == b.getClass()) return ca.compareTo(b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** 排序后的拷贝（null 安全）。 */
    static <T> List<T> sorted(List<T> xs) {
        if (xs == null) return null;
        List<T> out = new ArrayList<>(xs);
        out.sort(CypherTestSupport::cmp);
        return out;
    }
}
