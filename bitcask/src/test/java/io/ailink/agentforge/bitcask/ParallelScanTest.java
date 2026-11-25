package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.s;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 并行扫描与日志回调（都从原生线程回调）。期望值移植自 libbitcask c_api_test.c test_parallel_scan / test_status_ex_and_log
 * 与 keel tests/bitcask.ss「并行扫描收齐全库活键」。
 */
class ParallelScanTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    @Test
    void upstreamParallelScan() {
        try (var kv = Bitcask.open(d)) {
            int n = 500;
            long expected = 0;
            for (int i = 0; i < n; i++) {
                byte[] v = u("pv_" + i);
                kv.put(u("pk_" + i), v);
                for (byte b : v) expected += b;
            }
            for (int threads : new int[]{4, 0}) {
                AtomicLong cnt = new AtomicLong(), checksum = new AtomicLong();
                long visited = kv.parallelScanViews(null, threads, (k, v) -> {
                    cnt.incrementAndGet();
                    long sum = 0;
                    for (byte b : v.toArray(ValueLayout.JAVA_BYTE)) sum += b;
                    checksum.addAndGet(sum);
                });
                assertEquals(n, visited);
                assertEquals(n, cnt.get());
                assertEquals(expected, checksum.get());
            }
            // 前缀 pk_1 ⇒ 1、10-19、100-199 = 111 条
            AtomicLong pc = new AtomicLong();
            assertEquals(111, kv.parallelScan(u("pk_1"), 4, (k, v) -> pc.incrementAndGet()));
            assertEquals(111, pc.get());
        }
    }

    @Test
    void keelScanCollectsLiveKeys() {
        try (var ix = FilterTest.openKeelCorpus(d)) {
            List<String> ks = ix.rangeEntries().stream().map(Entry::keyString).toList();
            ConcurrentLinkedQueue<String> bag = new ConcurrentLinkedQueue<>();
            ix.parallelScan((k, v) -> bag.add(s(k)));
            assertEquals(ks, bag.stream().sorted().toList());
            bag.clear();
            ix.parallelScan(u("p/"), 0, (k, v) -> bag.add(s(k)));
            assertEquals(List.of("p/a", "p/b"), bag.stream().sorted().toList());
            AtomicReference<byte[]> got = new AtomicReference<>();
            ix.parallelScan(u("s"), 2, (k, v) -> {
                if ("s".equals(s(k))) got.set(v);
            });
            assertEquals("值", s(got.get()));
        }
    }

    @Test
    void callbackExceptionIsRethrownAfterTheScan() {
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 100; i++) kv.put("k" + i, "v");
            AtomicLong calls = new AtomicLong();
            var e = assertThrows(IllegalStateException.class, () -> kv.parallelScan(null, 1, (k, v) -> {
                calls.incrementAndGet();
                throw new IllegalStateException("stop");
            }));
            assertEquals("stop", e.getMessage());
            assertEquals(1, calls.get());
            assertEquals(100, kv.parallelScan((k, v) -> {
            }));
        }
    }

    @Test
    void scanViewsDieWithTheCallback() {
        try (var kv = Bitcask.open(d)) {
            kv.put("a", "1");
            AtomicReference<MemorySegment> leaked = new AtomicReference<>();
            kv.parallelScanViews(null, 1, (k, v) -> leaked.set(k));
            assertThrows(IllegalStateException.class, () -> s(leaked.get()));
        }
    }

    @Test
    void explicitAnalyzerDriftFailsWithLogCallbackInstalled() {
        // 挂上回调不影响正常路径；显式配置与库记录不符由本库硬报，不再等到上游指纹告警后静默换切词
        ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        try (var ix = BitcaskIndex.open(d, whitespace().log((lvl, msg) -> logs.add(lvl + " " + msg)))) {
            ix.putDocument("a", "hello world");
            ix.flush();
        }
        assertTrue(logs.isEmpty(), String.valueOf(logs));
        var e = thrown(() -> BitcaskIndex.open(d, whitespace().log((lvl, msg) -> logs.add(msg)).stemming(true)));
        assertEquals("bitcask/analyzer-mismatch", e.code());
        assertEquals(BitcaskError.ANALYZER_MISMATCH, e.error());
        assertTrue(logs.isEmpty(), "⛔ 上游先告警后静默换切词");
    }

    @Test
    void throwingLogCallbackIsSwallowed() {
        Options o = Options.defaults().log((lvl, msg) -> {
            throw new IllegalStateException("boom");
        });
        try (var ix = BitcaskIndex.open(d, o)) {
            ix.putDocument("a", "x");
        }
        try (var again = BitcaskIndex.open(d, o.copy().stemming(true))) {
            assertTrue(again.getString("a") != null);
        }
    }
}
