package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GET_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_ORD;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_VALUE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.DOC_FIELD_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.DFR_NAME;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.DFR_VALUE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GET_DOC_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_FIELDS;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_FIELDS_COUNT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_META;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_ORD;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_TSTAMP;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_VALUE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_VECTOR;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GDR_VECTOR_LEN;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SIZE_T;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * 单键热路径（put / get / delete / 零拷贝 read / 写文档）：每次调用 acquire / release 一对，失败 ⇒ {@link io.ailink.agentforge.bitcask.BitcaskException}，
 * 不存在 ⇒ null。暂存走 {@link Scratch}（每线程一份）。
 */
public final class Kv {
    private Kv() {
    }

    public static void put(NativeHandle.CaskHandle h, byte[] k, int ko, int kl, byte[] v, int vo, int vl) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, ko, kl, v, vo, vl);
            MemorySegment f = s.fault();
            Faults.check(BitcaskNative.put(cask, s.key, s.value, 0, f), f, "bitcask_put");
        } finally {
            h.release();
        }
    }

    public static void put(NativeHandle.CaskHandle h, byte[] k, byte[] v) {
        put(h, k, 0, k.length, v, 0, v.length);
    }

    /** 带绝对过期秒（≥ 1）。 */
    public static void putEx(NativeHandle.CaskHandle h, byte[] k, byte[] v, long expiryAt) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, 0, k.length, v, 0, v.length);
            MemorySegment f = s.fault();
            Faults.check(BitcaskNative.putEx(cask, s.key, s.value, 0, expiryAt, f), f, "bitcask_put_ex");
        } finally {
            h.release();
        }
    }

    /** 写墓碑；key 不存在不报错。 */
    public static void delete(NativeHandle.CaskHandle h, byte[] k) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, 0, k.length, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.delete(cask, s.key, 0, f);
            if (err != Faults.NOT_FOUND) Faults.check(err, f, "bitcask_delete");
        } finally {
            h.release();
        }
    }

    /** get 并拷出值；不存在 ⇒ null。 */
    public static byte[] get(NativeHandle.CaskHandle h, byte[] k, int ko, int kl) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, ko, kl, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.get(cask, s.key, s.out, f);
            MemorySegment r = s.out.get(ADDRESS, 0);
            if (err == 0) {
                try {
                    return NativeTypes.sliceBytes(r.reinterpret(GET_RESULT.byteSize()), GR_VALUE);
                } finally {
                    BitcaskNative.getResultFree(r);
                }
            }
            if (!r.equals(MemorySegment.NULL)) BitcaskNative.getResultFree(r);
            if (err == Faults.NOT_FOUND) return null;
            throw Faults.from(err, f, "bitcask_get");
        } finally {
            h.release();
        }
    }

    public static byte[] get(NativeHandle.CaskHandle h, byte[] k) {
        return get(h, k, 0, k.length);
    }

    /**
     * 判存在（D-26）：一次 C 调用，命中即释放结果——不拷值、不拷值、不建记录。
     * C API 没有只查索引的入口，所以仍走 {@code bitcask_get}。
     */
    public static boolean contains(NativeHandle.CaskHandle h, byte[] k) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, 0, k.length, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.get(cask, s.key, s.out, f);
            MemorySegment r = s.out.get(ADDRESS, 0);
            if (!r.equals(MemorySegment.NULL)) BitcaskNative.getResultFree(r);
            if (err == 0) return true;
            if (err == Faults.NOT_FOUND) return false;
            throw Faults.from(err, f, "bitcask_get");
        } finally {
            h.release();
        }
    }

    /**
     * 读值并带回 key 当前记录的 ord（D-25）：一次 C 调用、值拷进 byte[]、ord 写进 {@code ordOut[0]}，结果当场释放——
     * 不建带引用计数的记录。不存在 ⇒ null。
     */
    public static byte[] getWithOrd(NativeHandle.CaskHandle h, byte[] k, long[] ordOut) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, 0, k.length, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.get(cask, s.key, s.out, f);
            MemorySegment r = s.out.get(ADDRESS, 0);
            if (err == 0) {
                try {
                    MemorySegment rr = r.reinterpret(GET_RESULT.byteSize());
                    ordOut[0] = rr.get(JAVA_LONG, GR_ORD);
                    return NativeTypes.sliceBytes(rr, GR_VALUE);
                } finally {
                    BitcaskNative.getResultFree(r);
                }
            }
            if (!r.equals(MemorySegment.NULL)) BitcaskNative.getResultFree(r);
            if (err == Faults.NOT_FOUND) return null;
            throw Faults.from(err, f, "bitcask_get");
        } finally {
            h.release();
        }
    }

    /** 零拷贝读：借 C 侧缓冲的结果句柄（用完 close）；不存在 ⇒ null。 */
    public static NativeHandle.GetResultHandle read(NativeHandle.CaskHandle h, byte[] k, int ko, int kl) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, ko, kl, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.get(cask, s.key, s.out, f);
            MemorySegment r = s.out.get(ADDRESS, 0);
            if (err == 0) return new NativeHandle.GetResultHandle(r);
            if (!r.equals(MemorySegment.NULL)) BitcaskNative.getResultFree(r);
            if (err == Faults.NOT_FOUND) return null;
            throw Faults.from(err, f, "bitcask_get");
        } finally {
            h.release();
        }
    }

    public static NativeHandle.GetResultHandle read(NativeHandle.CaskHandle h, byte[] k) {
        return read(h, k, 0, k.length);
    }

    /** {@code bitcask_put_doc_ex}。 */
    public static void putDoc(NativeHandle.CaskHandle h, byte[] key, io.ailink.agentforge.bitcask.Document doc) {
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            MemorySegment d = DocMarshal.toNative(doc, a);
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.putDocEx(cask, a.slice(key), d, 0, f), f, "bitcask_put_doc_ex");
        } finally {
            h.release();
        }
    }

    /** 一次 get_doc 的结果（全量拷出；fields 保持写入序）。 */
    public record DocView(String text, byte[] meta, float[] vector, LinkedHashMap<String, String> fields,
                          long tstamp, long ord) {
    }

    /** 读回整篇文档（含 fields，field id 已由 field.schema 翻译回名字）；不存在 ⇒ null。 */
    public static DocView getDoc(NativeHandle.CaskHandle h, byte[] k) {
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            s.set(k, 0, k.length, null, 0, 0);
            MemorySegment f = s.fault();
            int err = BitcaskNative.getDoc(cask, s.key, s.out, f);
            MemorySegment r = s.out.get(ADDRESS, 0);
            if (err == 0) {
                try {
                    MemorySegment res = r.reinterpret(GET_DOC_RESULT.byteSize());
                    int fn = Math.toIntExact(res.get(SIZE_T, GDR_FIELDS_COUNT));
                    LinkedHashMap<String, String> fields = new LinkedHashMap<>(fn);
                    if (fn > 0) {
                        long fsz = DOC_FIELD_RESULT.byteSize();
                        MemorySegment fa = res.get(ADDRESS, GDR_FIELDS).reinterpret(fn * fsz);
                        for (int i = 0; i < fn; i++) {
                            long base = i * fsz;
                            fields.put(NativeTypes.cString(fa.get(ADDRESS, base + DFR_NAME)),
                                    new String(NativeTypes.sliceBytes(fa, base + DFR_VALUE), StandardCharsets.UTF_8));
                        }
                    }
                    return new DocView(new String(NativeTypes.sliceBytes(res, GDR_VALUE), StandardCharsets.UTF_8),
                            NativeTypes.sliceBytes(res, GDR_META),
                            NativeTypes.floats(res.get(ADDRESS, GDR_VECTOR), res.get(SIZE_T, GDR_VECTOR_LEN)),
                            fields, res.get(JAVA_LONG, GDR_TSTAMP), res.get(JAVA_LONG, GDR_ORD));
                } finally {
                    BitcaskNative.getDocResultFree(r);
                }
            }
            if (!r.equals(MemorySegment.NULL)) BitcaskNative.getDocResultFree(r);
            if (err == Faults.NOT_FOUND) return null;
            throw Faults.from(err, f, "bitcask_get_doc");
        } finally {
            h.release();
        }
    }
}
