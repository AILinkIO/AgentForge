package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.BatchOp.delete;
import static io.ailink.agentforge.bitcask.BatchOp.put;
import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 原子批的崩溃语义。移植自 libbitcask tests/atomic_batch_test.cpp（TornBatchInvisibleAndTruncated）的确定性手法：
 * 删派生文件（hint / keydir ckpt / OKI ⇒ 迫使 data fold）+ 掐 data 文件尾，不依赖杀进程的时序。
 */
class CrashTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    static Path maxDataFile(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".bitcask.data"))
                    .max(Comparator.comparingLong(p -> Long.parseUnsignedLong(p.getFileName().toString().split("\\.")[0]))).orElseThrow();
        }
    }

    static void dropDerived(Path dir, boolean bm25) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.toList()) {
                if (!Files.isRegularFile(f)) continue;
                String n = f.getFileName().toString();
                if (n.endsWith(".hint") || n.equals("kv.keydir.ckpt") || (bm25 && n.equals("bm25.ckpt")) || n.startsWith("kv.oki.")) Files.delete(f);
            }
        }
    }

    static void truncate(Path f, long len) throws IOException {
        try (var raf = new RandomAccessFile(f.toFile(), "rw")) {
            raf.setLength(len);
        }
    }

    /**
     * 模拟「上一个写者没有正常关闭」：libbitcask 12.1.0（S67）起恢复按写锁判断上一个写者是否崩溃——没有陈旧写锁 = 全部文件已封口，
     * 读不出来的字节按损坏处理（跳过、不截断）；只有留下陈旧写锁，锁里记的活跃文件才按「没写完的尾巴」截断。
     */
    static void plantStaleWriterLock(Path dir, Path active) throws IOException {
        Files.writeString(dir.resolve("bitcask.write.lock"), "999999999 " + active.toAbsolutePath() + "\n0\n");
    }

    @ParameterizedTest(name = "cut {0} crashed {1}")
    @CsvSource({"1,true", "1,false", "20,true", "20,false", "-1,true", "-1,false"})
    void tornAtomicBatchIsAllOrNothing(int cut, boolean crashed) throws IOException {
        long before;
        try (var kv = Bitcask.open(d)) {
            kv.put("pre", "kept");
            kv.sync();
            before = Files.size(maxDataFile(d));
            kv.writeAtomic(List.of(put("b1", "v1"), put("b2", "v2"), put("b3", "v3"), delete("pre-missing")));
        }
        Path data = maxDataFile(d);
        long full = Files.size(data);
        assertTrue(full > before);
        dropDerived(d, false);
        long torn = cut > 0 ? full - cut : before + 1;
        truncate(data, torn);
        if (crashed) plantStaleWriterLock(d, data);
        try (var kv = Bitcask.open(d)) {
            assertEquals("kept", kv.getString("pre"));
            assertNull(kv.getString("b1"));
            assertNull(kv.getString("b2"));
            assertNull(kv.getString("b3"));
        }
        // 写者崩溃：写打开恢复把撕裂的批截掉；正常关闭过：文件一个字节不动（读时跳过）
        assertEquals(crashed ? before : torn, Files.size(data));
    }

    @org.junit.jupiter.api.Test
    void intactAtomicBatchSurvivesForcedDataFold() throws IOException {
        try (var kv = Bitcask.open(d)) {
            kv.put("victim", "x");
            kv.writeAtomic(List.of(put("a", "va"), put("b", "vb"), delete("victim")));
        }
        dropDerived(d, false);
        try (var kv = Bitcask.open(d)) {
            assertEquals("va", kv.getString("a"));
            assertEquals("vb", kv.getString("b"));
            assertNull(kv.getString("victim"));
        }
    }
}
