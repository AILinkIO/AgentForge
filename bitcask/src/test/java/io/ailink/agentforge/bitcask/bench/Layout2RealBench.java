package io.ailink.agentforge.bitcask.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.graph.Graph;
import io.ailink.agentforge.bitcask.graph.Graph.BatchOptions;
import io.ailink.agentforge.bitcask.graph.Graph.Direction;
import io.ailink.agentforge.bitcask.graph.Graph.EdgeInput;
import io.ailink.agentforge.bitcask.graph.GraphConfig;

/**
 * 布局 2 的真实实现 vs 布局 1（移植自 keelson clj-bitcask bench/layout2_real_bench.clj，D-51 L2-3 验收）：同一个大号逐条加入边，
 * 按最后 1/5 的插入算每条边写入；入度 ≥ 10 万先批量建到位（逐条建 30 万的布局 1 要写 4～5 GiB）再逐条加 3000 条。
 * 不是测试（不进 CI）。点查用 {@code Graph.edge}（clj 版直接调 adj/lookup；这里多一次属性解析，空属性时可忽略）。
 * <p>跑法：先 {@code ./gradlew :bitcask:testClasses}，再
 * {@code java -Xmx8g --enable-native-access=ALL-UNNAMED -Dagentforge.bitcask.lib=…/libbitcask.so -cp bitcask/build/classes/java/test:bitcask/build/classes/java/main:<jackson> io.ailink.agentforge.bitcask.bench.Layout2RealBench [入度 …]}
 * 库建在 /tmp/afl2b&lt;纳秒&gt; 下，跑完删掉。
 */
public final class Layout2RealBench {
    static final long HUB = 2000000000000L;

    private Layout2RealBench() {
    }

    static Path tmpDir() {
        return Path.of("/tmp/afl2b" + System.nanoTime());
    }

    static void rmRf(Path d) {
        if (!d.toString().matches("/tmp/afl2b\\d+") || !Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long dataBytes(Path d) {
        try (Stream<Path> s = Files.walk(d)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".bitcask.data")).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static long[] genSrcs(int n, long seed) {
        Random r = new Random(seed);
        Set<Long> seen = new HashSet<>();
        long[] out = new long[n];
        for (int i = 0; i < n; ) {
            long v = 1 + Math.floorMod(r.nextLong(), 1L << 40);
            if (seen.add(v)) out[i++] = v;
        }
        return out;
    }

    static double timeUs(LongConsumer f, long[] xs, int rounds) {
        for (long x : xs) f.accept(x);
        long t0 = System.nanoTime();
        for (int k = 0; k < rounds; k++) for (long x : xs) f.accept(x);
        return (System.nanoTime() - t0) / 1e3 / ((double) rounds * xs.length);
    }

    record Result(double bytes, double insUs, double lookUs, double topUs, double degUs, double fullMs, String note) {
    }

    static Result bench(long[] srcs, GraphConfig.Options opts) {
        Path d = tmpDir();
        int n = srcs.length;
        boolean bulk = n >= 100000;
        int w0 = bulk ? n - 3000 : n - n / 5;
        try (Bitcask db = Bitcask.open(d)) {
            Graph g = Graph.open(db);
            g.init(opts);
            if (bulk) {
                for (int from = 0; from < w0; from += 100000) {
                    List<EdgeInput> es = new ArrayList<>();
                    for (int i = from; i < Math.min(w0, from + 100000); i++) es.add(EdgeInput.of(srcs[i], 1, HUB));
                    g.putEdges(es, new BatchOptions(true, 1000000));
                }
            } else for (int i = 0; i < w0; i++) g.putEdge(srcs[i], 1, HUB);
            db.sync();
            long b0 = dataBytes(d);
            long t0 = System.nanoTime();
            for (int i = w0; i < n; i++) g.putEdge(srcs[i], 1, HUB);
            double us = (System.nanoTime() - t0) / 1e3 / (n - w0);
            db.sync();
            double bytes = (dataBytes(d) - b0) / (double) (n - w0);
            Random r = new Random(5);
            long[] probe = new long[2000];
            for (int i = 0; i < probe.length; i++) probe[i] = srcs[r.nextInt(n)];
            Graph.GroupSummary summ = g.vertexSummary(HUB).groups().stream().filter(x -> x.dir() == Direction.IN).findFirst().orElseThrow();
            if (g.inDegree(HUB) != n) throw new AssertionError("in-degree " + g.inDegree(HUB));
            long[] one = {0};
            return new Result(bytes, us,
                    timeUs(x -> g.edge(x, 1, HUB, 0), probe, 3),
                    timeUs(x -> g.inEdges(HUB, new Graph.AdjQuery(null, 100, false)).size(), one, 200),
                    timeUs(x -> g.inDegree(HUB), one, 2000),
                    timeUs(x -> g.neighbors(HUB, Direction.IN).size(), one, n > 100000 ? 2 : 10) / 1000.0,
                    summ.chunks() + " 块" + (summ.dirLevels() != null ? "，目录 " + summ.dirLevels() + " 层" : ""));
        } finally {
            rmRf(d);
        }
    }

    public static void main(String[] args) {
        int[] degs = args.length > 0 ? Stream.of(args).mapToInt(Integer::parseInt).toArray() : new int[]{3000, 30000, 300000, 3000000};
        System.out.println("入度 | 布局 | 每条边写入 B | 加边 µs | 点查 µs | 前100 µs | 入度 µs | 全读邻居 ms | 块 / 目录");
        for (int deg : degs) {
            long[] srcs = genSrcs(deg, 9);
            for (var nm : List.of("布局 1", "布局 2（缺省）")) {
                GraphConfig.Options o = nm.equals("布局 1") ? GraphConfig.options().layout(1) : GraphConfig.options();
                Result r = bench(srcs, o);
                System.out.printf("%d | %s | %.1f | %.1f | %.1f | %.1f | %.1f | %.1f | %s%n", deg, nm, r.bytes, r.insUs, r.lookUs, r.topUs, r.degUs, r.fullMs, r.note);
                System.out.flush();
            }
        }
    }
}
