package io.ailink.agentforge.bitcask.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/**
 * 每线程一份的原生暂存（单键热路径用：两个 slice、一个出参、一个 fault、一块按需长大的拷贝区），免得每次调用开关 arena。
 * 只在一次调用之内用；不可重入（调用期间不会回调进本库）。
 */
final class Scratch {
    private static final ThreadLocal<Scratch> LOCAL = ThreadLocal.withInitial(Scratch::new);

    private final Arena arena = Arena.ofAuto();
    final MemorySegment key = arena.allocate(NativeTypes.SLICE);
    final MemorySegment value = arena.allocate(NativeTypes.SLICE);
    final MemorySegment out = arena.allocate(ADDRESS);
    final MemorySegment fault = arena.allocate(NativeTypes.FAULT);
    private MemorySegment buf = arena.allocate(256);
    private MemorySegment batchBuf = arena.allocate(2048, 16);

    static Scratch get() {
        return LOCAL.get();
    }

    /** fault 复位（码 / errno / 详情首字节）。 */
    MemorySegment fault() {
        fault.set(JAVA_INT, NativeTypes.FAULT_CODE, 0);
        fault.set(JAVA_INT, NativeTypes.FAULT_ERRNUM, 0);
        fault.set(JAVA_BYTE, NativeTypes.FAULT_DETAIL, (byte) 0);
        return fault;
    }

    /** 小原子批的整块暂存（16 字节对齐，按需翻倍长大；内容不保证清零，调用方自己清要的区）。 */
    MemorySegment batch(long n) {
        if (batchBuf.byteSize() < n) batchBuf = Arena.ofAuto().allocate(Math.max(n, batchBuf.byteSize() * 2), 16);
        return batchBuf;
    }

    private MemorySegment buffer(long n) {
        if (buf.byteSize() < n) buf = Arena.ofAuto().allocate(Math.max(n, buf.byteSize() * 2));
        return buf;
    }

    /** key（与可选的 value）拷进暂存区并填好两个 slice；长度 0 ⇒ NULL / 0。 */
    void set(byte[] k, int ko, int kl, byte[] v, int vo, int vl) {
        Objects.checkFromIndexSize(ko, kl, k.length);
        if (v != null) Objects.checkFromIndexSize(vo, vl, v.length);
        MemorySegment b = buffer((long) kl + (v == null ? 0 : vl));
        MemorySegment.copy(k, ko, b, JAVA_BYTE, 0, kl);
        NativeTypes.setSlice(key, 0, kl == 0 ? null : b, kl);
        if (v != null) {
            MemorySegment.copy(v, vo, b, JAVA_BYTE, kl, vl);
            NativeTypes.setSlice(value, 0, vl == 0 ? null : b.asSlice(kl, vl), vl);
        }
    }
}
