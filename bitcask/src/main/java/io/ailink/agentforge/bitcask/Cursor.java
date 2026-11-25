package io.ailink.agentforge.bitcask;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;

import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.NativeHandle;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * 迭代游标（有序 range 或 keydir 扫描；由 {@link Bitcask#rangeCursor} / {@link Bitcask#scanCursor} 开）。{@code key()} / {@code value()} / {@code meta()} 拷出当前条目；
 * {@code xxxSegment()} 借 C 侧缓冲的只读视图，下一次 {@link #moveNext()} / {@link #nextBatch(int)} / {@link #close()} 起失效。
 * 一个游标一个线程。没 close 的由 Cleaner 兜底；游标持有库一份引用，库先关 ⇒ 真正的关库推迟到游标释放之后。{@link #nextBatch(int)} 一次跨 FFI 取一批并拷出（逐条热循环在这里）。
 */
public final class Cursor implements AutoCloseable {
    private static final Entry[] NONE = new Entry[0];

    private final NativeHandle.IterHandle iter;
    private final boolean range;
    private final NativeTypes.EntryShape shape;
    private final MemorySegment entry;
    private final MemorySegment fault;
    private MemorySegment batchBuf;
    private boolean hasEntry;
    private boolean done;
    private Arena views;

    private Cursor(NativeHandle.IterHandle iter, boolean range) {
        this.iter = iter;
        this.range = range;
        this.shape = range ? NativeTypes.RANGE_SHAPE : NativeTypes.ITER_SHAPE;
        Arena auto = Arena.ofAuto();
        this.entry = auto.allocate(shape.layout());
        this.fault = auto.allocate(NativeTypes.FAULT);
    }

    /** 包住已开的迭代器句柄（{@link io.ailink.agentforge.bitcask.ffm.Iters}）。 */
    static Cursor of(NativeHandle.IterHandle iter, boolean range) {
        return new Cursor(iter, range);
    }

    public boolean isRange() {
        return range;
    }

    /** 前进一条；没有更多 ⇒ false。 */
    public boolean moveNext() {
        checkOpen();
        freeEntry();
        if (done) return false;
        MemorySegment it = iter.acquire();
        int rc;
        try {
            fault.fill((byte) 0);
            rc = range ? BitcaskNative.rangeIterNextEx(it, entry, fault) : BitcaskNative.iterNextEx(it, entry, fault);
        } finally {
            iter.release();
        }
        if (rc < 0) throw Faults.iterError(fault, range ? "bitcask_range_iter_next_ex" : "bitcask_iter_next_ex");
        hasEntry = rc == 1;
        if (!hasEntry) done = true;
        return hasEntry;
    }

    /** 批量取最多 {@code max} 条并拷出；空表 = 结束。可与 {@link #moveNext()} 混用；调用后不在任何条目上。 */
    public java.util.List<Entry> nextBatch(int max) {
        return java.util.List.of(nextBatchArray(max));
    }

    Entry[] nextBatchArray(int max) {
        if (max <= 0) throw new IllegalArgumentException("max");
        checkOpen();
        freeEntry();
        if (done) return NONE;
        if (!range) return nextBatchView(max);
        long size = shape.layout().byteSize();
        if (batchBuf == null || batchBuf.byteSize() < size * max) batchBuf = Arena.ofAuto().allocate(shape.layout(), max);
        MemorySegment buf = batchBuf;
        buf.asSlice(0, size * max).fill((byte) 0);
        MemorySegment it = iter.acquire();
        int n;
        try {
            fault.fill((byte) 0);
            n = BitcaskNative.rangeIterNextBatchEx(it, buf, max, fault);
        } finally {
            iter.release();
        }
        if (n < 0) throw Faults.iterError(fault, "bitcask_range_iter_next_batch_ex");
        if (n == 0) {
            done = true;
            return NONE;
        }
        Entry[] out = new Entry[n];
        try {
            for (int i = 0; i < n; i++) out[i] = copy(buf.asSlice(i * size, size));
        } finally {
            for (int i = 0; i < n; i++) BitcaskNative.rangeEntryExFree(buf.asSlice(i * size, size));
        }
        return out;
    }

    /**
     * 扫描半边走 {@code bitcask_iter_next_batch_view}（libbitcask 13.0.0，S75）：切片借迭代器内部存储，
     * 只在下一次推进之前有效——这里当场拷出，所以不需要（也没有）free；整批原生侧零堆分配。
     */
    private Entry[] nextBatchView(int max) {
        NativeTypes.EntryShape vs = NativeTypes.VIEW_SHAPE;
        long size = vs.layout().byteSize();
        if (batchBuf == null || batchBuf.byteSize() < size * max) batchBuf = Arena.ofAuto().allocate(vs.layout(), max);
        MemorySegment buf = batchBuf;
        MemorySegment it = iter.acquire();
        try {
            fault.fill((byte) 0);
            int n = BitcaskNative.iterNextBatchView(it, buf, max, fault);
            if (n < 0) throw Faults.iterError(fault, "bitcask_iter_next_batch_view");
            if (n == 0) {
                done = true;
                return NONE;
            }
            Entry[] out = new Entry[n];
            for (int i = 0; i < n; i++) out[i] = copy(buf.asSlice(i * size, size), vs);
            return out;
        } finally {
            iter.release();
        }
    }

    private Entry copy(MemorySegment e) {
        return copy(e, shape);
    }

    private static Entry copy(MemorySegment e, NativeTypes.EntryShape s) {
        return new Entry(NativeTypes.sliceBytes(e, s.key()), NativeTypes.sliceBytes(e, s.value()),
                e.get(JAVA_LONG, s.tstamp()), e.get(JAVA_LONG, s.ord()), NativeTypes.sliceBytes(e, s.meta()));
    }

    private void checkOpen() {
        if (iter.isClosed()) throw NativeHandle.disposed(iter.typeName());
    }

    private void checkEntry() {
        checkOpen();
        if (!hasEntry) throw BitcaskException.of("bitcask/cursor-no-entry");
    }

    public boolean hasEntry() {
        return hasEntry && !iter.isClosed();
    }

    public byte[] key() {
        checkEntry();
        return NativeTypes.sliceBytes(entry, shape.key());
    }

    public byte[] value() {
        checkEntry();
        return NativeTypes.sliceBytes(entry, shape.value());
    }

    /** 当前 key 按 UTF-8 解码。 */
    public String keyString() {
        return new String(key(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 当前值按 UTF-8 解码。 */
    public String valueString() {
        return new String(value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 当前条目的 meta 段（没要 / 没有 ⇒ 空数组）。 */
    public byte[] meta() {
        checkEntry();
        return NativeTypes.sliceBytes(entry, shape.meta());
    }

    public MemorySegment keySegment() {
        return view(shape.key());
    }

    public MemorySegment valueSegment() {
        return view(shape.value());
    }

    public MemorySegment metaSegment() {
        return view(shape.meta());
    }

    private MemorySegment view(long sliceOffset) {
        checkEntry();
        MemorySegment v = NativeTypes.sliceView(entry, sliceOffset);
        if (v.byteSize() == 0) return MemorySegment.NULL;
        if (views == null) views = Arena.ofConfined();
        return v.reinterpret(v.byteSize(), views, seg -> Reference.reachabilityFence(this)).asReadOnly();
    }

    public long timestamp() {
        checkEntry();
        return entry.get(JAVA_LONG, shape.tstamp());
    }

    public long ord() {
        checkEntry();
        return entry.get(JAVA_LONG, shape.ord());
    }

    /** 当前条目拷出来。 */
    public Entry entry() {
        checkEntry();
        return copy(entry);
    }

    public boolean isClosed() {
        return iter.isClosed();
    }

    /** 幂等。库若已被关，真正的 close 此时才发生。 */
    @Override
    public void close() {
        if (iter.isClosed()) return;
        freeEntry();
        iter.close();
    }

    private void freeEntry() {
        if (views != null) {
            views.close();
            views = null;
        }
        if (hasEntry) {
            hasEntry = false;
            if (range) BitcaskNative.rangeEntryExFree(entry);
            else BitcaskNative.iterEntryExFree(entry);
            entry.fill((byte) 0);
        }
    }
}
