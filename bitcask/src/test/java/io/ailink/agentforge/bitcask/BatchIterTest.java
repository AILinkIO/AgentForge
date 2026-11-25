package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.entryKeys;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 批量迭代（*_next_batch_ex / batch_view）。期望值移植自 libbitcask tests/c_api_test.c test_range_iter 与 test_meta_filter_query。 */
class BatchIterTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    private static void fill(Bitcask kv) {
        for (int g = 0; g < 4; g++) for (int i = 0; i < 25; i++) kv.put(String.format("g%03d:k%03d", g, i), "v" + g + "-" + i);
        kv.delete("g001:k000");
        kv.delete("g001:k024");
    }

    private static List<Entry> drain(Cursor c, int n) {
        List<Entry> acc = new ArrayList<>();
        for (List<Entry> b = c.nextBatch(n); !b.isEmpty(); b = c.nextBatch(n)) acc.addAll(b);
        return acc;
    }

    @Test
    void rangeBatchReturnsWindowThenEmpty() {
        try (var kv = Bitcask.open(d)) {
            fill(kv);
            try (Cursor c = kv.rangeCursor(RangeOptions.between("g001:", "g002:"))) {
                List<Entry> batch = c.nextBatch(100);
                List<String> ks = entryKeys(batch);
                assertEquals(23, batch.size());
                assertTrue(ks.stream().allMatch(k -> k.startsWith("g001:")));
                assertEquals(ks.stream().sorted().toList(), ks);
                assertEquals("v1-1", batch.getFirst().valueString());
                assertTrue(c.nextBatch(100).isEmpty());
                assertFalse(c.moveNext());
            }
        }
    }

    @Test
    void scanBatchMixesWithNext() {
        try (var kv = Bitcask.open(d)) {
            fill(kv);
            try (Cursor c = kv.scanCursor()) {
                assertTrue(c.moveNext());
                Set<String> seen = new HashSet<>(Set.of(c.keyString()));
                for (List<Entry> b = c.nextBatch(8); !b.isEmpty(); b = c.nextBatch(8)) {
                    assertTrue(b.size() <= 8);
                    for (Entry e : b) assertTrue(seen.add(e.keyString()));
                }
                assertEquals(98, seen.size());
                assertEquals("bitcask/cursor-no-entry", code(c::key));
            }
        }
    }

    @Test
    void batchSizeMustBePositive() {
        try (var kv = Bitcask.open(d); Cursor c = kv.scanCursor()) {
            assertThrows(IllegalArgumentException.class, () -> c.nextBatch(0));
        }
    }

    @Test
    void batchWithMetaFilter() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            byte[] keep = Meta.encode(Map.of("tag", "keep")), drop = Meta.encode(Map.of("tag", "drop"));
            for (int i = 0; i < 200; i++) {
                String k = String.format("d%03d", i);
                ix.putDocument(k, Document.builder().text("apple pie").meta(k.endsWith("0") ? keep : drop).build());
            }
            ix.flush();
            Filter f = Filter.eq("tag", "keep");
            try (Cursor c = ix.scanCursor(ScanOptions.all().filter(f).meta(true))) {
                int n = 0;
                if (c.moveNext()) {
                    n++;
                    assertArrayEquals(keep, c.meta());
                }
                for (List<Entry> b = c.nextBatch(8); !b.isEmpty(); b = c.nextBatch(8)) {
                    for (Entry e : b) {
                        n++;
                        assertTrue(e.keyString().endsWith("0"));
                        assertArrayEquals(keep, e.meta());
                        assertEquals("apple pie".length(), e.value().length);
                    }
                }
                assertEquals(20, n);
            }
            for (int prefetch : new int[]{0, 64}) {
                try (Cursor r = ix.rangeCursor(RangeOptions.between("d050", "d150").filter(f).meta(true).prefetch(prefetch))) {
                    List<Entry> es = drain(r, 4);
                    for (Entry e : es) assertArrayEquals(keep, e.meta());
                    assertEquals(IntStream.range(0, 10).mapToObj(i -> String.format("d%03d", 50 + i * 10)).toList(), entryKeys(es));
                    assertFalse(r.moveNext());
                }
            }
        }
    }

    @Test
    void scanBatchViewCopiesEveryField() {
        // libbitcask 13.0.0（S75）：扫描批走免分配视图，切片只活到下一次推进——拷出来的每一批跨批都得完整、ord 与点查一致
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            for (int i = 0; i < 300; i++)
                ix.putDocument(String.format("d%03d", i), Document.builder().text("doc " + i).meta(Map.of("i", String.valueOf(i))).build());
            ix.flush();
            try (Cursor c = ix.scanCursor(ScanOptions.all().meta(true))) {
                List<Entry> es = drain(c, 7);
                assertEquals(300, es.size());
                assertEquals(IntStream.range(0, 300).mapToObj(i -> String.format("d%03d", i)).collect(Collectors.toSet()),
                        es.stream().map(Entry::keyString).collect(Collectors.toSet()));
                for (Entry e : es) {
                    String k = e.keyString();
                    var vo = ix.getWithOrd(k);
                    assertArrayEquals(vo.value(), e.value(), k);
                    assertEquals(vo.ord(), e.ord(), k);
                    assertArrayEquals(Meta.encode(Map.of("i", String.valueOf(Integer.parseInt(k.substring(1))))), e.meta(), k);
                }
            }
        }
    }

    @Test
    void rangeCursorIsASnapshot() {
        // libbitcask 13.1.0（S76）：range 迭代器的 key 集合与值都固定在开游标那一刻
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 50; i++) kv.put(String.format("k%03d", i), "old");
            try (Cursor c = kv.rangeCursor(RangeOptions.between("k", "l"))) {
                kv.delete("k010");
                kv.put("k020", "new");
                kv.put("k025x", "added");
                List<Entry> es = drain(c, 8);
                assertEquals(IntStream.range(0, 50).mapToObj(i -> String.format("k%03d", i)).toList(), entryKeys(es));
                assertTrue(es.stream().allMatch(e -> e.valueString().equals("old")));
            }
            assertEquals(50, kv.rangeEntries(RangeOptions.between("k", "l")).size());
        }
    }
}
