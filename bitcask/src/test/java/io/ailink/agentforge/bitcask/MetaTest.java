package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * meta 编解码。期望值移植自 libbitcask c_api_test.c test_doc_fields_and_meta 与 keel tests/bitcask.ss §K3 / §M，
 * 以及 keel 判据里手写的那段字节（kind=food、n=5）——跨语言字节级判据。
 */
class MetaTest {
    @TempDir
    Path d;

    static final Object NONE = new Object();

    static byte[] keelBytes() {
        int[] raw = {0x01, 0x82, 0x84, 0x6b, 0x69, 0x6e, 0x64, 0x04, 0x84, 0x66, 0x6f, 0x6f, 0x64, 0x81, 0x6e, 0x02, 0x05, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] b = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) b[i] = (byte) raw[i];
        return b;
    }

    static <V> Map.Entry<String, Object> e(String k, V v) {
        return new SimpleEntry<>(k, v);
    }

    static List<Map.Entry<String, Object>> entries(Object... kv) {
        List<Map.Entry<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) out.add(e((String) kv[i], kv[i + 1]));
        return out;
    }

    @Test
    void encodingMatchesKeelHandWrittenBytes() {
        assumeNative();
        // ⚠️ keel 手写字节在 int64 之后多一个 0x00（上游解码不看尾部）：编码器产物 = 它去掉末字节
        byte[] keel = keelBytes();
        assertArrayEquals(Arrays.copyOf(keel, keel.length - 1), Meta.encode(entries("n", 5L, "kind", "food")));
        assertEquals(entries("kind", "food", "n", 5L), Meta.decode(keel));
    }

    @Test
    void roundTripSortedByKey() {
        assumeNative();
        assertEquals(entries("kind", "food", "n", 3L, "ok", true, "z", "值"), Meta.decode(Meta.encode(entries("z", "值", "kind", "food", "n", 3, "ok", true))));
        assertEquals(entries("f", 1.5), Meta.decode(Meta.encode(Map.of("f", 1.5))));
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("a", null);
        assertEquals(entries("a", null), Meta.decode(Meta.encode(withNull)));
        assertEquals(Meta.Type.NULL, Meta.typeOf(Meta.decode(Meta.encode(withNull)).getFirst().getValue()));
        assertEquals(Map.of("kind", "food", "n", 3L), Meta.decodeMap(Meta.encode(Map.of("kind", "food", "n", 3))));
        assertEquals(Map.of("tags", List.of("a", "", "值")), Meta.decodeMap(Meta.encode(Map.of("tags", List.of("a", "", "值")))));
        assertEquals(Map.of("tags", List.of()), Meta.decodeMap(Meta.encode(Map.of("tags", new String[0]))));
    }

    @Test
    void twoHundredKeysCrossTheTwoByteCount() {
        assumeNative();
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 199; i >= 0; i--) m.put(String.format("k%03d", i), (long) i);
        var r = Meta.decode(Meta.encode(m));
        assertEquals(200, r.size());
        assertEquals(e("k000", 0L), r.getFirst());
        assertEquals(e("k199", 199L), r.getLast());
    }

    @Test
    void upstreamEncodeLookupIterate() {
        assumeNative();
        byte[] blob = Meta.encode(entries("price", 42, "author", "david", "draft", false));
        assertEquals(42L, Meta.lookup(blob, "price"));
        assertEquals(Meta.Type.INT64, Meta.typeOf(Meta.lookup(blob, "price")));
        assertEquals("david", Meta.lookup(blob, "author"));
        assertEquals(false, Meta.lookup(blob, "draft", NONE));
        assertSame(NONE, Meta.lookup(blob, "nope", NONE));
        assertSame(NONE, Meta.lookup(new byte[0], "price", NONE));
        assertEquals(List.of("author", "draft", "price"), Meta.decode(blob).stream().map(Map.Entry::getKey).toList());
    }

    @Test
    void encoderErrorSurface() {
        assumeNative();
        assertEquals("bitcask/meta-duplicate-key", code(() -> Meta.encode(entries("k", 1, "k", 2))));
        assertEquals(0, Meta.encode(List.of()).length);
        assertThrows(IllegalArgumentException.class, () -> Meta.encode(Map.of("k", new Object())));
    }

    @Test
    void decodeRejectsRawBytesButLookupDoesNot() {
        assumeNative();
        byte[] json = u("{\"kind\":\"food\"}");
        byte[] junk = new byte[8];
        Arrays.fill(junk, (byte) 7);
        assertEquals("bitcask/meta-version", code(() -> Meta.decode(json)));
        assertNotNull(code(() -> Meta.decode(junk)));
        assertSame(NONE, Meta.lookup(junk, "kind", NONE));
        assertEquals("bitcask/meta-truncated", code(() -> Meta.decode(new byte[]{1})));
        assertEquals(List.of(), Meta.decode(new byte[0]));
    }

    @Test
    void lookupAgreesWithDecodeForEveryKey() {
        assumeNative();
        byte[] blob = Meta.encode(entries("kind", "food", "n", 3, "ok", true, "no", false, "f", 1.5, "z", null));
        var all = Meta.decode(blob);
        assertEquals(6, all.size());
        for (var en : all) assertEquals(en.getValue(), Meta.lookup(blob, en.getKey(), NONE), en.getKey());
    }

    @Test
    void valueTypesFollowJavaValues() {
        assertEquals(Meta.Type.INT64, Meta.typeOf(3));
        assertEquals(Meta.Type.INT64, Meta.typeOf(3L));
        assertEquals(Meta.Type.FLOAT64, Meta.typeOf(3.0));
        assertEquals(Meta.Type.BOOL, Meta.typeOf(false));
        assertEquals(Meta.Type.STRING, Meta.typeOf("3"));
        assertEquals(Meta.Type.NULL, Meta.typeOf(null));
        assertEquals(Meta.Type.STRING_ARRAY, Meta.typeOf(List.of("a")));
        assertThrows(IllegalArgumentException.class, () -> Meta.typeOf(List.of(1)));
    }

    @Test
    void storedMetaReadsBackAndIsLookedUp() {
        assumeNative();
        try (var ix = BitcaskIndex.open(d)) {
            byte[] blob = Meta.encode(entries("kind", "food", "n", 3));
            ix.putDocument("m1", Document.builder().text("红烧牛肉面").meta(blob).build());
            ix.putDocument("m2", "拿铁咖啡");
            try (Record r = ix.readRecord("m1")) {
                assertArrayEquals(blob, r.meta());
                assertEquals("food", Meta.lookup(r.meta(), "kind"));
                assertEquals("food", Meta.lookup(r.metaSegment(), "kind")); // 零拷贝查
            }
            try (Record r = ix.readRecord("m2")) {
                assertEquals(0, r.meta().length);
                assertSame(NONE, Meta.lookup(r.metaSegment(), "kind", NONE));
                assertNull(Meta.lookup(r.metaSegment(), "kind"));
            }
        }
    }
}
