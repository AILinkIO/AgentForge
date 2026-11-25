package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * meta 段的编解码（libbitcask {@code bitcask_meta_*}）。只有编码过的 meta 才被过滤（{@link Filter}）认得。纯函数、线程安全。
 * <p>值的类型按 Java 值定：null ⇒ NULL，{@link Boolean} ⇒ BOOL，整数（Long / Integer / Short / Byte / BigInteger）⇒ INT64，
 * 其它 {@link Number} ⇒ FLOAT64，{@link String} ⇒ STRING，字符串的 {@link List} / {@code String[]} ⇒ STRING_ARRAY
 * （libbitcask 12.0.0 S62；解码回只读 {@code List<String>}；过滤用 {@code contains} / {@code containsAny}）。
 * 解码出来是 Boolean / Long / Double / String / List&lt;String&gt; / null。
 * <p>⚠️ 把含数组的 meta 写进库时，库目录的 bitcask.meta 懒升 v7——≤ 11.2 的 libbitcask 从此打不开这个目录。
 */
public final class Meta {
    /** meta 值的类型。 */
    public enum Type { NULL, BOOL, INT64, FLOAT64, STRING, STRING_ARRAY }

    private static final long ENTRY_KEY = NativeTypes.off(NativeTypes.META_ENTRY_EX, "key");
    private static final long ENTRY_VALUE = NativeTypes.off(NativeTypes.META_ENTRY_EX, "value");
    private static final long ENTRY_ITEMS = NativeTypes.off(NativeTypes.META_ENTRY_EX, "items");
    private static final long ENTRY_ITEMS_N = NativeTypes.off(NativeTypes.META_ENTRY_EX, "items_count");
    private static final long V_TYPE = NativeTypes.off(NativeTypes.META_VALUE, "type");
    private static final long V_I64 = NativeTypes.off(NativeTypes.META_VALUE, "i64");
    private static final long V_F64 = NativeTypes.off(NativeTypes.META_VALUE, "f64");
    private static final long V_STR = NativeTypes.off(NativeTypes.META_VALUE, "str");
    private static final long VIEW_TYPE = NativeTypes.off(NativeTypes.META_VALUE_VIEW, "type");
    private static final long VIEW_I64 = NativeTypes.off(NativeTypes.META_VALUE_VIEW, "i64");
    private static final long VIEW_F64 = NativeTypes.off(NativeTypes.META_VALUE_VIEW, "f64");
    private static final long VIEW_STR = NativeTypes.off(NativeTypes.META_VALUE_VIEW, "str");
    private static final long ITER_REMAINING = NativeTypes.off(NativeTypes.META_ITER, "remaining");

    private Meta() {
    }

    /** meta 值 ⇒ 类型；不支持的类型 ⇒ IllegalArgumentException。 */
    public static Type typeOf(Object v) {
        if (v == null) return Type.NULL;
        if (v instanceof Boolean) return Type.BOOL;
        if (isIntegral(v)) return Type.INT64;
        if (v instanceof Number) return Type.FLOAT64;
        if (v instanceof String) return Type.STRING;
        if (v instanceof String[]) return Type.STRING_ARRAY;
        if (v instanceof List<?> l && l.stream().allMatch(x -> x instanceof String)) return Type.STRING_ARRAY;
        throw new IllegalArgumentException("meta 值只能是 null / 布尔 / 数 / 字符串 / 字符串数组：" + v);
    }

    static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte || v instanceof BigInteger;
    }

    static long toLong(Object v) {
        return v instanceof BigInteger b ? b.longValueExact() : ((Number) v).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        return v instanceof String[] a ? Arrays.asList(a) : (List<String>) v;
    }

    /**
     * 往 {@code target + offset} 写一个 {@code bitcask_meta_value_t}（字符串进 arena）。标量专用（过滤操作数）：
     * 数组 ⇒ IllegalArgumentException。
     */
    static void writeValue(MemorySegment target, long offset, Object v, NativeArena a) {
        Type t = typeOf(v);
        if (t == Type.STRING_ARRAY) throw new IllegalArgumentException("这里要标量（过滤操作数不能是数组）：" + v);
        target.set(ValueLayout.JAVA_INT, offset + V_TYPE, t.ordinal());
        target.set(ValueLayout.JAVA_LONG, offset + V_I64, t == Type.BOOL ? ((Boolean) v ? 1 : 0) : t == Type.INT64 ? toLong(v) : 0);
        target.set(ValueLayout.JAVA_DOUBLE, offset + V_F64, t == Type.FLOAT64 ? ((Number) v).doubleValue() : 0.0);
        target.set(ValueLayout.ADDRESS, offset + V_STR, t == Type.STRING ? a.utf8z((String) v) : MemorySegment.NULL);
    }

    /**
     * 编码（{@code bitcask_meta_encode_ex}）：无需预排序，上游按 key 升序排。没有条目 ⇒ 空数组（= 不带 meta）。
     * 重复键 ⇒ {@code bitcask/meta-duplicate-key}。
     */
    public static byte[] encode(Map<String, ?> entries) {
        return encode(new ArrayList<>(entries.entrySet()));
    }

    /** 同 {@link #encode(Map)}，条目表形式（可含重复键 ⇒ 报错）。 */
    public static byte[] encode(List<? extends Map.Entry<String, ?>> entries) {
        if (entries.isEmpty()) return new byte[0];
        Set<String> seen = new HashSet<>();
        for (var e : entries) {
            if (e.getKey() == null) throw new IllegalArgumentException("meta 键不能是 null");
            if (!seen.add(e.getKey())) throw BitcaskException.of("bitcask/meta-duplicate-key", e.getKey());
            typeOf(e.getValue());
        }
        try (var a = new NativeArena()) {
            int n = entries.size();
            MemorySegment nat = a.alloc(NativeTypes.META_ENTRY_EX, n);
            long size = NativeTypes.META_ENTRY_EX.byteSize();
            long ssize = NativeTypes.SLICE.byteSize();
            for (int i = 0; i < n; i++) {
                var e = entries.get(i);
                long base = i * size;
                nat.set(ValueLayout.ADDRESS, base + ENTRY_KEY, a.utf8z(e.getKey()));
                Object v = e.getValue();
                if (typeOf(v) == Type.STRING_ARRAY) {
                    List<String> xs = strings(v);
                    MemorySegment items = a.alloc(NativeTypes.SLICE, Math.max(1, xs.size()));
                    for (int j = 0; j < xs.size(); j++) {
                        byte[] b = xs.get(j).getBytes(StandardCharsets.UTF_8);
                        NativeTypes.setSlice(items, j * ssize, b.length > 0 ? a.copy(b) : MemorySegment.NULL, b.length);
                    }
                    nat.set(ValueLayout.JAVA_INT, base + ENTRY_VALUE + V_TYPE, Type.STRING_ARRAY.ordinal());
                    nat.set(ValueLayout.ADDRESS, base + ENTRY_ITEMS, items);
                    nat.set(ValueLayout.JAVA_LONG, base + ENTRY_ITEMS_N, xs.size());
                } else {
                    writeValue(nat, base + ENTRY_VALUE, v, a);
                }
            }
            MemorySegment blob = a.alloc(NativeTypes.SLICE);
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.metaEncodeEx(nat, n, blob, f), f, "bitcask_meta_encode_ex");
            try {
                return NativeTypes.sliceBytes(blob, 0);
            } finally {
                BitcaskNative.metaBlobFree(blob);
            }
        }
    }

    /**
     * 全量解码 ⇒ 条目表（key 升序；值可为 null）。空 blob ⇒ 空表。
     * 不是编码过的 meta ⇒ {@code bitcask/meta-version} / {@code bitcask/meta-truncated}。
     */
    public static List<Map.Entry<String, Object>> decode(byte[] blob) {
        int n = blob.length;
        if (n == 0) return List.of();
        if (n < 2) throw BitcaskException.of("bitcask/meta-truncated");
        if (blob[0] != 1) throw BitcaskException.of("bitcask/meta-version", blob[0] & 0xFF);
        try (var a = new NativeArena()) {
            MemorySegment slice = a.slice(blob);
            MemorySegment it = a.alloc(NativeTypes.META_ITER);
            if (BitcaskNative.metaIterBegin(slice, it) == 0) throw BitcaskException.of("bitcask/meta-truncated");
            MemorySegment k = a.alloc(NativeTypes.SLICE);
            MemorySegment view = a.alloc(NativeTypes.META_VALUE_VIEW);
            List<Map.Entry<String, Object>> out = new ArrayList<>();
            while (BitcaskNative.metaIterNext(slice, it, k, view) != 0) {
                out.add(new AbstractMap.SimpleImmutableEntry<>(new String(NativeTypes.sliceBytes(k, 0), StandardCharsets.UTF_8), readView(view)));
            }
            if (it.get(ValueLayout.JAVA_LONG, ITER_REMAINING) != 0) throw BitcaskException.of("bitcask/meta-truncated");
            return Collections.unmodifiableList(out);
        }
    }

    /** 解码成 map（key 升序；值可为 null）。 */
    public static Map<String, Object> decodeMap(byte[] blob) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (var e : decode(blob)) m.put(e.getKey(), e.getValue());
        return m;
    }

    /** 单键查询（不全量解码）；没有这个键、blob 空或损坏 ⇒ null（上游口径「非法即未命中」，不抛）。 */
    public static Object lookup(byte[] blob, String key) {
        return lookup(blob, key, null);
    }

    /** 同 {@link #lookup(byte[], String)}，未命中回 {@code notFound}（区分「值为 null」与「没有」）。 */
    public static Object lookup(byte[] blob, String key, Object notFound) {
        if (blob == null || blob.length == 0) return notFound;
        try (var a = new NativeArena()) {
            return lookup(a.slice(blob), key, notFound, a);
        }
    }

    /** 零拷贝查：原生段直接交给 C 侧（堆段先拷进来）。 */
    public static Object lookup(MemorySegment blob, String key, Object notFound) {
        if (blob == null || blob.byteSize() == 0) return notFound;
        try (var a = new NativeArena()) {
            MemorySegment s = a.alloc(NativeTypes.SLICE);
            if (blob.isNative()) NativeTypes.setSlice(s, 0, blob, blob.byteSize());
            else NativeTypes.setSlice(s, 0, a.copy(blob.toArray(ValueLayout.JAVA_BYTE)), blob.byteSize());
            return lookup(s, key, notFound, a);
        }
    }

    public static Object lookup(MemorySegment blob, String key) {
        return lookup(blob, key, null);
    }

    private static Object lookup(MemorySegment slice, String key, Object notFound, NativeArena a) {
        MemorySegment view = a.alloc(NativeTypes.META_VALUE_VIEW);
        if (BitcaskNative.metaLookup(slice, a.utf8z(key), view) == 0) return notFound;
        return readView(view);
    }

    private static Object readView(MemorySegment v) {
        return switch (v.get(ValueLayout.JAVA_INT, VIEW_TYPE)) {
            case 1 -> v.get(ValueLayout.JAVA_LONG, VIEW_I64) != 0;
            case 2 -> v.get(ValueLayout.JAVA_LONG, VIEW_I64);
            case 3 -> v.get(ValueLayout.JAVA_DOUBLE, VIEW_F64);
            case 4 -> new String(NativeTypes.sliceBytes(v, VIEW_STR), StandardCharsets.UTF_8);
            case 5 -> readArray(v);
            default -> null;
        };
    }

    /** 数组视图 ⇒ 字符串表（{@code bitcask_meta_array_next} 逐个取；元素区损坏 ⇒ {@code bitcask/meta-truncated}）。 */
    private static List<String> readArray(MemorySegment v) {
        long n = v.get(ValueLayout.JAVA_LONG, VIEW_I64);
        try (var a = new NativeArena()) {
            MemorySegment region = v.asSlice(VIEW_STR, NativeTypes.SLICE.byteSize());
            MemorySegment cur = a.alloc(NativeTypes.SIZE_T);
            MemorySegment item = a.alloc(NativeTypes.SLICE);
            List<String> out = new ArrayList<>();
            while (BitcaskNative.metaArrayNext(region, cur, item) != 0) {
                out.add(new String(NativeTypes.sliceBytes(item, 0), StandardCharsets.UTF_8));
            }
            if (n != out.size()) throw BitcaskException.of("bitcask/meta-truncated");
            return Collections.unmodifiableList(out);
        }
    }
}
