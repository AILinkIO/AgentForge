package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.f;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 目录探测。⭐ 防漂移：probe 读的是 libbitcask 的内部 meta 格式 ⇒ 这里用真 libbitcask 按各种配置建库、逐项对账；升级 libbitcask 时
 * 格式一变这里就红。另含：inmem-int8 打开选项往返、向量配置不一致时错误里带两边的值、range-cursor 只读无 OKI 的实际行为。
 */
class ProbeTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    static Set<String> ls(Path d) throws IOException {
        try (Stream<Path> s = Files.list(d)) {
            return s.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    @Test
    void kvStore() {
        try (var db = Bitcask.open(d)) {
            db.put("k", "v");
        }
        ProbeResult p = Bitcask.probe(d);
        assertEquals(ProbeResult.Mode.KV, p.mode());
        assertTrue(p.metaVersion() == 5 || p.metaVersion() == 6, String.valueOf(p));
        assertNull(p.vector(), "KV 库不带向量配置");
    }

    @Test
    void atomicBatchLiftsMetaToV6() {
        try (var db = Bitcask.open(d)) {
            db.writeAtomic(List.of(BatchOp.put("a", "1")));
        }
        assertEquals(6, Bitcask.probe(d).metaVersion(), "原子批纪元：meta 懒升到 v6");
    }

    static final VectorConfig[] CONFIGS = {
            VectorConfig.NONE,
            new VectorConfig(4, Options.VectorMetric.L2, false, false, Options.VectorEngine.HNSW),
            new VectorConfig(8, Options.VectorMetric.DOT, true, false, Options.VectorEngine.HNSW),
            new VectorConfig(16, Options.VectorMetric.COSINE, false, true, Options.VectorEngine.HNSW),
            new VectorConfig(3, Options.VectorMetric.COSINE, false, false, Options.VectorEngine.DISK_ANN),
            new VectorConfig(300, Options.VectorMetric.DOT, false, false, Options.VectorEngine.IVF_RQ), // 磁盘引擎只收 cosine / dot
    };

    @Test
    void indexConfigsRoundTrip() {
        for (int i = 0; i < CONFIGS.length; i++) {
            VectorConfig cfg = CONFIGS[i];
            Path dir = d.resolve("c" + i);
            BitcaskIndex.open(dir, whitespace().vector(cfg)).close();
            ProbeResult p = Bitcask.probe(dir);
            assertEquals(ProbeResult.Mode.INDEX, p.mode());
            assertEquals(cfg, p.vector(), String.valueOf(p));
            assertTrue(p.icuMajor() > 0);
            assertEquals(Options.Analyzer.WHITESPACE, p.analyzer().analyzer());
            try (var db = BitcaskIndex.open(dir, whitespace().vector(p.vector()))) {
                assertTrue(db.isIndex(), "probe 的结果原样当选项就开得了");
            }
        }
    }

    @Test
    void probeDoesNotLockOrCreate() throws IOException {
        try (var db = BitcaskIndex.open(d, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.L2))) {
            db.putDocument("x", Document.builder().text("hello").vector(f(1, 0, 0, 0)).build());
            assertEquals(4, Bitcask.probe(d).vector().dimension(), "写者开着也能探（不拿锁）");
        }
        Set<String> before = ls(d);
        Bitcask.probe(d);
        assertEquals(before, ls(d), "⛔ 写任何东西");
    }

    @Test
    void inmemInt8StoresOpen() {
        Options int8 = whitespace().vectorDimension(8).vectorMetric(Options.VectorMetric.COSINE).vectorInmemInt8(true);
        BitcaskIndex.open(d, int8).close();
        try (var db = BitcaskIndex.open(d, int8)) {
            assertEquals(8, db.vectorDimension());
        }
        var e = thrown(() -> BitcaskIndex.open(d, whitespace().vectorDimension(8).vectorMetric(Options.VectorMetric.COSINE)));
        assertEquals(BitcaskError.MODE_MISMATCH, e.error());
        assertTrue(((VectorConfig) e.data().get("metaVector")).inmemInt8());
        assertFalse(((VectorConfig) e.data().get("requestedVector")).inmemInt8());
    }

    @Test
    void vectorMismatchReportsBothSides() {
        BitcaskIndex.open(d, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.L2)).close();
        var e = thrown(() -> BitcaskIndex.open(d, whitespace().vectorDimension(8)));
        assertEquals("bitcask/mode-mismatch", e.code(), "错误码不变：调用方照旧按 mode-mismatch 分支");
        assertEquals(new VectorConfig(4, Options.VectorMetric.L2, false, false, Options.VectorEngine.HNSW), e.data().get("metaVector"));
        VectorConfig want = (VectorConfig) e.data().get("requestedVector");
        assertEquals(8, want.dimension());
        assertEquals(Options.VectorMetric.COSINE, want.metric(), "缺省补齐后的那份");
        assertTrue(e.getMessage().contains("dimension=4"), e.getMessage());
    }

    @Test
    void kvIndexMismatchIsLeftAlone() {
        Bitcask.open(d).close();
        var e = thrown(() -> BitcaskIndex.open(d, whitespace()));
        assertEquals(BitcaskError.MODE_MISMATCH, e.error());
        assertFalse(e.data().containsKey("metaVector"), "不是向量分支 ⇒ 不补");
    }

    static void rewriteMeta(Path d, Consumer<byte[]> f) throws IOException {
        Path p = d.resolve("bitcask.meta");
        byte[] b = Files.readAllBytes(p);
        f.accept(b);
        Files.write(p, b);
    }

    static void fixCrc(byte[] b) {
        CRC32 c = new CRC32();
        c.update(b, 0, 14);
        long v = c.getValue();
        for (int i = 0; i < 4; i++) b[14 + i] = (byte) (v >>> (8 * i));
    }

    @Test
    void errors() throws IOException {
        Path dir = d.resolve("x");
        assertEquals("bitcask/probe-not-directory", code(() -> Bitcask.probe(dir)));
        assertFalse(Files.exists(dir), "⛔ 像 open 那样「不存在则创建」");
        Files.createDirectories(dir);
        assertEquals("bitcask/probe-not-bitcask", code(() -> Bitcask.probe(dir)));
        try (var db = Bitcask.open(dir)) {
            db.put("k", "v");
        }
        rewriteMeta(dir, b -> b[7] = 9);
        assertEquals("bitcask/probe-corrupt", code(() -> Bitcask.probe(dir)), "CRC 对不上");
        rewriteMeta(dir, b -> {
            b[7] = 0;
            b[4] = 4;
            fixCrc(b);
        });
        var e = thrown(() -> Bitcask.probe(dir));
        assertEquals("bitcask/probe-unsupported", e.code());
        assertTrue(e.getMessage().contains("hintord"), "v4 告诉怎么迁：" + e.getMessage());
        rewriteMeta(dir, b -> b[0] = 0x58);
        assertEquals("bitcask/probe-corrupt", code(() -> Bitcask.probe(dir)), "magic 不对");
    }

    @Test
    void readOnlyRangeWithoutOkiBuildsAMemoryView() throws IOException {
        try (var db = Bitcask.open(d)) {
            for (int i = 0; i < 20; i++) db.put(String.format("k%02d", i), "v");
        }
        for (String f : ls(d)) if (f.contains("oki")) Files.delete(d.resolve(f));
        assertTrue(ls(d).stream().noneMatch(f -> f.contains("oki")));
        try (var db = Bitcask.open(d, Options.defaults().readOnly(true))) {
            assertEquals(20, db.rangeEntries().size(), "⛔ NO_INDEX");
        }
        assertTrue(ls(d).stream().noneMatch(f -> f.contains("oki")), "只读：内存视图，⛔ 写盘");
    }

    @Test
    void analyzerConfigAndStoredOpen() {
        Options[] cfgs = {Options.defaults(), whitespace(), Options.defaults().analyzer(Options.Analyzer.NGRAM).ngramMin(2).ngramMax(4)};
        for (int i = 0; i < cfgs.length; i++) {
            Options cfg = cfgs[i];
            Path dir = d.resolve("a" + i);
            try (var ix = BitcaskIndex.open(dir, cfg)) {
                ix.putDocument("a", "quick fox running");
            }
            ProbeResult p = Bitcask.probe(dir);
            Options.Analyzer expected = cfg.analyzer() == null ? Options.Analyzer.NGRAM : cfg.analyzer();
            assertNotNull(p.analyzer());
            assertEquals(expected, p.analyzer().analyzer());
            if (expected == Options.Analyzer.NGRAM) {
                assertEquals(cfg.ngramMin() == null ? 2 : cfg.ngramMin(), p.analyzer().ngramMin());
                assertEquals(cfg.ngramMax() == null ? 3 : cfg.ngramMax(), p.analyzer().ngramMax());
            }
            try (var ix = BitcaskIndex.open(dir, Options.defaults().vector(p.vector()))) {
                assertFalse(ix.searchHighlighted("fox", HighlightOptions.defaults().cold(HighlightOptions.ColdMode.LOAD)).getFirst().snippets().isEmpty(),
                        "缺省 stored：不传分词器也能按库配置切片段");
            }
        }
    }

    @Test
    void explicitAnalyzerMismatchFails() {
        BitcaskIndex.open(d, whitespace()).close();
        var e = thrown(() -> BitcaskIndex.open(d, Options.defaults().analyzer(Options.Analyzer.NGRAM)));
        assertEquals("bitcask/analyzer-mismatch", e.code());
        assertEquals(BitcaskError.ANALYZER_MISMATCH, e.error());
        try (var ix = BitcaskIndex.open(d, Options.defaults())) {
            assertTrue(ix.isIndex(), "STORED 与库记录一致照常开");
        }
    }
}
