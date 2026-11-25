package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.entryKeys;
import static io.ailink.agentforge.bitcask.TestSupport.s;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 有序 range（OKI）。期望值移植自 libbitcask tests/c_api_test.c（test_range_iter）。 */
class RangeTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    /** 数据形态 gNNN:kMMM（前缀组即 range 窗口），故意乱序写；删两条。 */
    static void seed(Bitcask kv) {
        List<int[]> ks = new ArrayList<>();
        for (int g = 0; g < 4; g++) for (int i = 0; i < 25; i++) ks.add(new int[]{g, i});
        Collections.shuffle(ks, new Random(42));
        for (int[] p : ks) kv.put(String.format("g%03d:k%03d", p[0], p[1]), "v" + p[0] + "-" + p[1]);
        kv.delete("g001:k000");
        kv.delete("g001:k024");
    }

    private static List<String> keysIn(Bitcask kv, RangeOptions o) {
        return entryKeys(kv.rangeEntries(o));
    }

    @Test
    void windowYieldsLiveKeysInStrictAscendingOrder() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
            try (Cursor c = kv.rangeCursor(RangeOptions.between("g001:", "g002:"))) {
                List<String> ks = new ArrayList<>();
                while (c.moveNext()) {
                    assertEquals(9, c.keySegment().byteSize());
                    assertTrue(c.keyString().startsWith("g001:"));
                    assertTrue(c.value().length > 0);
                    ks.add(c.keyString());
                }
                assertEquals(23, ks.size());
                assertEquals(ks.stream().sorted().toList(), ks);
                assertEquals(ks.size(), ks.stream().distinct().count());
                assertEquals("g001:k001", ks.getFirst());
                assertEquals("g001:k023", ks.getLast());
                assertFalse(c.moveNext());
            }
        }
    }

    @Test
    void cursorViewsExpireOnAdvance() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
            try (Cursor c = kv.rangeCursor(RangeOptions.between("g001:", "g002:"))) {
                assertTrue(c.moveNext());
                MemorySegment k = c.keySegment();
                assertEquals("g001:k001", s(k));
                assertTrue(c.moveNext());
                assertThrows(IllegalStateException.class, () -> s(k));
            }
        }
    }

    @Test
    void prefetchDoesNotChangeOutput() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
            List<String> plain = kv.rangeEntries(RangeOptions.between("g001:", "g002:")).stream()
                    .map(e -> e.keyString() + "=" + e.valueString()).toList();
            List<String> pre = new ArrayList<>();
            try (Cursor c = kv.rangeCursor(RangeOptions.between("g001:", "g002:").prefetch(8))) {
                while (c.moveNext()) pre.add(c.keyString() + "=" + c.valueString());
            }
            assertEquals(plain, pre);
        }
    }

    @Test
    void unboundedRangeIsWholeKeyspace() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
            List<String> all = keysIn(kv, null);
            assertEquals(98, all.size());
            assertEquals(all.stream().sorted().toList(), all);
            assertEquals(25 + 23, keysIn(kv, RangeOptions.all().hi("g002:")).size());
            assertEquals(50, keysIn(kv, RangeOptions.all().lo("g002:")).size());
            assertEquals(23, keysIn(kv, RangeOptions.prefix("g001:")).size());
        }
    }

    @Test
    void prefixScanMatchesRangeWindow() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
            List<String> scanned = kv.scanEntries(ScanOptions.prefix("g001:")).stream().map(Entry::keyString).sorted().toList();
            assertEquals(23, scanned.size());
            assertEquals(keysIn(kv, RangeOptions.between("g001:", "g002:")), scanned);
            assertEquals(98, kv.scanEntries().size());
        }
    }

    @Test
    void cursorKeepsStoreAliveUntilReleased() {
        var kv = Bitcask.open(d);
        seed(kv);
        Cursor c = kv.rangeCursor();
        kv.close(); // 真正的 close 推迟到游标释放之后
        int n = 0;
        while (c.moveNext()) n++;
        c.close();
        assertEquals(98, n);
        assertEquals("bitcask/disposed", code(() -> kv.put("x", "y")));
    }

    @Test
    void rangeSurvivesReopen() {
        try (var kv = Bitcask.open(d)) {
            seed(kv);
        }
        try (var kv = Bitcask.open(d)) {
            assertEquals(23, keysIn(kv, RangeOptions.between("g001:", "g002:")).size());
        }
    }

    @Test
    void successorCarries() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{1, 3}, RangeOptions.successor(new byte[]{1, 2}));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{2}, RangeOptions.successor(new byte[]{1, (byte) 0xFF}));
        org.junit.jupiter.api.Assertions.assertNull(RangeOptions.successor(new byte[]{(byte) 0xFF}));
    }
}
