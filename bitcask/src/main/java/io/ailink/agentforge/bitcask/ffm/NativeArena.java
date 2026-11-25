package io.ailink.agentforge.bitcask.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 一次 C 调用要用的原生内存：全部从这里分配，调用返回后一次关掉。
 * 底下是 confined {@link Arena}（分配即清零）。⚠️ 按值传的 slice 里装的是裸指针 ⇒ Java 堆上的 {@code byte[]} 一律先拷进来。
 */
public final class NativeArena implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();

    /** 一个清零的结构体。 */
    public MemorySegment alloc(MemoryLayout layout) {
        return arena.allocate(layout);
    }

    /** {@code count} 个清零的结构体；count = 0 ⇒ NULL。 */
    public MemorySegment alloc(MemoryLayout layout, long count) {
        if (count == 0) return MemorySegment.NULL;
        return arena.allocate(layout, count);
    }

    /** 一个 fault（清零 = OK、无详情）。 */
    public MemorySegment fault() {
        return arena.allocate(NativeTypes.FAULT);
    }

    /** 一个指针出参（初值 NULL）。 */
    public MemorySegment pointer() {
        return arena.allocate(ADDRESS);
    }

    /** 拷一份字节（空 ⇒ NULL）。 */
    public MemorySegment copy(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return MemorySegment.NULL;
        MemorySegment p = arena.allocate(length);
        MemorySegment.copy(bytes, offset, p, JAVA_BYTE, 0, length);
        return p;
    }

    public MemorySegment copy(byte[] bytes) {
        return copy(bytes, 0, bytes.length);
    }

    /** 拷一份 float（空 ⇒ NULL）。 */
    public MemorySegment copy(float[] values) {
        if (values.length == 0) return MemorySegment.NULL;
        MemorySegment p = arena.allocate(JAVA_FLOAT, values.length);
        MemorySegment.copy(values, 0, p, JAVA_FLOAT, 0, values.length);
        return p;
    }

    /** UTF-8 编码、NUL 结尾。 */
    public MemorySegment utf8z(String s) {
        Objects.requireNonNull(s);
        return arena.allocateFrom(s, StandardCharsets.UTF_8);
    }

    /** NUL 结尾 UTF-8，null ⇒ NULL。 */
    public MemorySegment utf8zOrNull(String s) {
        return s == null ? MemorySegment.NULL : utf8z(s);
    }

    /** 一个 slice 结构体，指向 {@code bytes[offset, offset+length)} 的拷贝。 */
    public MemorySegment slice(byte[] bytes, int offset, int length) {
        MemorySegment s = arena.allocate(NativeTypes.SLICE);
        NativeTypes.setSlice(s, 0, copy(bytes, offset, length), length);
        return s;
    }

    public MemorySegment slice(byte[] bytes) {
        return slice(bytes, 0, bytes.length);
    }

    /** 空 slice（NULL / 0）。 */
    public MemorySegment emptySlice() {
        return arena.allocate(NativeTypes.SLICE);
    }

    /** 往 {@code target + offset} 写一个指向 {@code s} 的 UTF-8（不带 NUL）拷贝的 slice；空串 ⇒ NULL / 0。 */
    public void setUtf8Slice(MemorySegment target, long offset, String s) {
        Objects.requireNonNull(s);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        NativeTypes.setSlice(target, offset, copy(b), b.length);
    }

    /** 往 {@code target + offset} 写一个指向 {@code bytes} 拷贝的 slice。 */
    public void setBytesSlice(MemorySegment target, long offset, byte[] bytes, int off, int length) {
        NativeTypes.setSlice(target, offset, copy(bytes, off, length), length);
    }

    @Override
    public void close() {
        arena.close();
    }
}
