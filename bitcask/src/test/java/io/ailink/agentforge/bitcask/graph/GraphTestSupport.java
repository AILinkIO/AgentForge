package io.ailink.agentforge.bitcask.graph;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.function.Executable;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.BitcaskRuntime;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.RangeOptions;

/** 图层测试共用（父包的 TestSupport 是包私有的，这里抄一份小的）：原生库不在就跳过、字节小工具、取错误码。 */
final class GraphTestSupport {
    private GraphTestSupport() {
    }

    /** 原生库不在 ⇒ 跳过（打印理由）。 */
    static void assumeNative() {
        Assumptions.assumeTrue(BitcaskRuntime.isAvailable(), () -> "SKIP: libbitcask 不在（-Pbitcask.lib=…）：" + BitcaskRuntime.unavailableReason());
    }

    static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static byte[] b(int... xs) {
        byte[] out = new byte[xs.length];
        for (int i = 0; i < xs.length; i++) out[i] = (byte) xs[i];
        return out;
    }

    /** 大端 8 字节。 */
    static int[] u64(long x) {
        int[] out = new int[8];
        for (int i = 0; i < 8; i++) out[i] = (int) ((x >>> (56 - 8 * i)) & 0xFF);
        return out;
    }

    /** 若干 int 段拼成字节。 */
    static byte[] cat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] all = new int[n];
        int i = 0;
        for (int[] p : parts) for (int x : p) all[i++] = x;
        return b(all);
    }

    static int[] ints(int... xs) {
        return xs;
    }

    static String code(Executable body) {
        return assertThrows(BitcaskException.class, body).code();
    }

    static List<Long> dsts(List<Edge> es) {
        return es.stream().map(Edge::dst).toList();
    }

    static List<Long> srcs(List<Edge> es) {
        return es.stream().map(Edge::src).toList();
    }

    static List<String> pairs(List<Edge> es) {
        return es.stream().map(e -> e.src() + "->" + e.dst()).toList();
    }

    static String props(Edge e) {
        return s(e.props());
    }

    static List<Long> range(long from, long to) {
        List<Long> out = new ArrayList<>();
        for (long i = from; i < to; i++) out.add(i);
        return out;
    }

    static List<Long> range(long from, long to, long step) {
        List<Long> out = new ArrayList<>();
        for (long i = from; i < to; i += step) out.add(i);
        return out;
    }

    static <T> List<T> map(List<Long> xs, Function<Long, T> f) {
        return xs.stream().map(f).toList();
    }

    /** 全图不变式校验，断言 ok。 */
    static Graph.InvariantReport ok(Graph g) {
        Graph.InvariantReport r = g.checkInvariants();
        assertTrue(r.ok(), String.valueOf(r));
        return r;
    }

    /** vid 名下的全部块键（63 字节）。 */
    static List<byte[]> chunkKeys(Bitcask db, long vid) {
        byte[] lo = GraphKeys.ascii(GraphKeys.head(vid));
        return db.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo))).stream().map(Entry::key)
                .filter(k -> k.length == 63).toList();
    }

    static GraphConfig cfg(long inlineMax, long chunkMax, long propsMax) {
        return GraphConfig.of(inlineMax, chunkMax, propsMax);
    }
}
