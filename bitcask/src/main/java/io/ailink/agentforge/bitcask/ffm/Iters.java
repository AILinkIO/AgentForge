package io.ailink.agentforge.bitcask.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.MemorySegment;

/** 开迭代器（有序 range / keydir 扫描）⇒ 持有库一份引用的 {@link NativeHandle.IterHandle}。 */
public final class Iters {
    private Iters() {
    }

    /** {@code opts} = 填好的 {@code bitcask_range_options_t}；filter 可为 NULL。 */
    public static NativeHandle.IterHandle openRange(NativeHandle.CaskHandle h, MemorySegment opts, boolean wantMeta, MemorySegment filter) {
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            MemorySegment out = a.pointer();
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.rangeIterStartEx(cask, opts, wantMeta ? 1 : 0, filter, out, f), f, "bitcask_range_iter_start_ex");
            return new NativeHandle.IterHandle(out.get(ADDRESS, 0), h, true, "BitcaskRangeCursor");
        } finally {
            h.release();
        }
    }

    /** {@code prefix} = slice 结构体；filter 可为 NULL。 */
    public static NativeHandle.IterHandle openScan(NativeHandle.CaskHandle h, MemorySegment prefix, boolean wantMeta, MemorySegment filter) {
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            MemorySegment out = a.pointer();
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.iterStartEx(cask, -1, -1, 0, prefix, wantMeta ? 1 : 0, filter, out, f), f, "bitcask_iter_start_ex");
            return new NativeHandle.IterHandle(out.get(ADDRESS, 0), h, false, "BitcaskScanCursor");
        } finally {
            h.release();
        }
    }
}
