package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.s;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KV 往返。期望值移植自 libbitcask tests/c_api_test.c（test_kv_basic / test_status_and_merge / test_iteration / test_version）。 */
class KvTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    @Test
    void versionIs13x() {
        var v = BitcaskRuntime.version();
        assertEquals(13, v.major());
        assertEquals(v.toString(), BitcaskRuntime.versionString());
    }

    @Test
    void putGetDeleteRoundtrip() {
        try (var kv = Bitcask.open(d)) {
            kv.put(u("hello"), u("world"));
            assertEquals("world", s(kv.get(u("hello"))));
            assertNull(kv.get(u("nokey")));
            kv.delete(u("hello"));
            assertNull(kv.get(u("hello")));
            assertFalse(kv.containsKey(u("hello")));
        }
    }

    @Test
    void containsKeyPaths() {
        try (var kv = Bitcask.open(d)) {
            kv.put("s", "v");
            kv.put(u("b"), new byte[0]);
            kv.put(u("past"), u("v"), Instant.now().minus(Duration.ofMinutes(1)));
            assertTrue(kv.containsKey("s"));
            assertTrue(kv.containsKey(u("s")));
            assertTrue(kv.containsKey("b"), "空值也算存在");
            assertFalse(kv.containsKey("nope"));
            assertFalse(kv.containsKey("past"), "过期 ⇒ 不存在");
            kv.delete("s");
            assertFalse(kv.containsKey("s"));
        }
    }

    @Test
    void stringKeysAndOverwrite() {
        try (var kv = Bitcask.open(d)) {
            kv.put("键", "值一");
            kv.put("键", "值二");
            assertEquals("值二", kv.getString("键"));
            kv.delete("键");
            assertNull(kv.getString("键"));
            String big = "x".repeat(100000);
            kv.put("big", big);
            assertEquals(big, kv.getString("big"));
        }
    }

    @Test
    void arrayRangeArities() {
        try (var kv = Bitcask.open(d)) {
            byte[] buf = u("__key__value__");
            kv.putRange(buf, 2, 3, buf, 7, 5);
            assertEquals("value", s(kv.get(u("key"))));
            try (Record r = kv.readRecord(buf, 2, 3)) {
                assertEquals("value", s(r.value()));
            }
        }
    }

    @Test
    void emptyValueRoundtrips() {
        try (var kv = Bitcask.open(d)) {
            kv.put(u("e"), new byte[0]);
            byte[] v = kv.get(u("e"));
            assertNotNull(v);
            assertEquals(0, v.length);
        }
    }

    @Test
    void readIsZeroCopyViewValidUntilClose() {
        try (var kv = Bitcask.open(d)) {
            kv.put(u("k"), u("payload"));
            Record rec = kv.readRecord(u("k"));
            assertNotNull(rec);
            assertEquals("payload", s(rec.value()));
            MemorySegment view = rec.valueSegment();
            assertEquals("payload", s(view));
            assertTrue(view.isReadOnly());
            assertEquals(0, rec.meta().length);
            assertEquals(0, rec.metaSegment().byteSize());
            assertEquals(0, rec.vector().length);
            assertTrue(rec.timestamp() > 0);
            rec.close();
            assertEquals("bitcask/disposed", code(rec::value));
            // 借出去的视图随记录失效：不会读到已释放的内存
            assertThrows(IllegalStateException.class, () -> s(view));
            rec.close();
        }
    }

    @Test
    void reopenAfterCloseKeepsData() {
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 100; i++) kv.put("key_" + i, "value_" + i);
            kv.delete("key_7");
        }
        try (var kv = Bitcask.open(d, Options.defaults().readOnly(true))) {
            assertEquals("value_0", kv.getString("key_0"));
            assertEquals("value_99", kv.getString("key_99"));
            assertNull(kv.getString("key_7"));
            assertEquals(99, kv.status().keyCount());
        }
    }

    @Test
    void statusCountsKeys() {
        try (var kv = Bitcask.open(d)) {
            assertTrue(kv.isEmpty());
            for (int i = 0; i < 100; i++) kv.put("key_" + i, "value_" + i);
            Status st = kv.status();
            assertEquals(100, st.keyCount());
            assertTrue(st.keyBytes() > 0);
            assertFalse(kv.isEmpty());
            kv.sync();
            kv.merge();
            kv.checkpoint();
            assertEquals(100, kv.status().keyCount());
        }
    }

    @Test
    void scanSeesEveryLiveKey() {
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 10; i++) kv.put("k" + i, "val" + i);
            kv.delete("k3");
            List<String> all = kv.scanEntries().stream().map(Entry::keyString).sorted().toList();
            assertEquals(9, all.size());
            assertFalse(all.contains("k3"));
            try (Cursor c = kv.scanCursor(ScanOptions.prefix("k1"))) {
                assertTrue(c.moveNext());
                assertEquals("k1", s(c.key()));
                assertEquals("val1", s(c.value()));
                assertEquals("val1", s(c.valueSegment()));
                assertFalse(c.moveNext());
            }
        }
    }

    @Test
    void expiredKeyIsInvisible() {
        try (var kv = Bitcask.open(d)) {
            kv.put(u("past"), u("v"), Instant.now().minus(Duration.ofMinutes(1)));
            kv.put(u("future"), u("v"), Instant.now().plus(Duration.ofHours(1)));
            assertNull(kv.get(u("past")));
            assertNotNull(kv.get(u("future")));
        }
    }

    @Test
    void unclosedRecordsAndCursorsAreCleanedUp() throws InterruptedException {
        // Cleaner 兜底：忘了 close 的记录 / 游标 / 库不泄漏、不崩
        var kv = Bitcask.open(d);
        kv.put("a", "1");
        for (int i = 0; i < 1000; i++) {
            kv.readRecord(u("a"));
            kv.scanCursor();
        }
        kv = null;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(50);
        }
        try (var again = Bitcask.open(d, Options.defaults().readOnly(true))) {
            assertEquals("1", again.getString("a"));
        }
    }

    @Test
    void collectionTraversalReleasesOnEarlyStop() {
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 50; i++) kv.put(String.format("k%02d", i), "v");
            List<String> first = new ArrayList<>();
            kv.forEachRange(RangeOptions.all().batch(4), e -> {
                first.add(e.keyString());
                return first.size() < 3;
            });
            assertEquals(List.of("k00", "k01", "k02"), first);
            assertThrows(ArithmeticException.class, () -> kv.forEachRange(null, e -> 1 / (e.key().length - e.key().length) > 0));
            kv.put("zz", "1");
            assertFalse(kv.isFrozen());
            assertEquals(51, kv.scanEntries().size());
            assertEquals(51, kv.forEachRangeCursor(null, c -> true));
            String[] last = new String[1];
            kv.forEachRangeCursor(RangeOptions.all().hi("k50"), c -> {
                last[0] = c.keyString();
                return true;
            });
            assertEquals("k49", last[0]);
            try (Stream<Entry> st = kv.rangeStream(RangeOptions.all().batch(7))) {
                assertEquals(List.of("k00", "k01", "k02"), st.limit(3).map(Entry::keyString).collect(Collectors.toList()));
            }
            try (Stream<Entry> st = kv.scanStream(ScanOptions.prefix("k4"))) {
                assertEquals(10, st.count());
            }
        }
    }

    @Test
    void getDocumentWorksOnKvStores() {
        try (var kv = Bitcask.open(d)) {
            kv.put("plain", "正文");
            StoredDocument doc = kv.getDocument("plain");
            assertEquals("正文", doc.text());
            assertNull(doc.meta());
            assertNull(doc.vector());
            assertTrue(doc.fields().isEmpty());
            assertNull(kv.getDocument("missing"));
            kv.writeAtomic(List.of(BatchOp.putDoc("doc", Document.builder().text("t").meta(java.util.Map.of("k", "v")).build())));
            assertEquals("v", Meta.decodeMap(kv.getDocument("doc").meta()).get("k"));
            assertArrayEquals(u("t"), kv.get("doc"));
        }
    }
}
