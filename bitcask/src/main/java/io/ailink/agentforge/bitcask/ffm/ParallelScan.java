package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SIZE_T;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 并行扫描（{@code bitcask_parallel_scan_prefix}）的 upcall 分派：回调从多条原生线程并发进来。
 * copy = false ⇒ 回调拿只读视图（{@link MemorySegment}，回调结束即失效）；true ⇒ 拿拷贝（{@code byte[]}）。
 * 回调抛的第一个异常存下、之后的条目不再回调，扫描结束后在调用线程原样重抛。
 */
public final class ParallelScan {
    /** key / value：视图（MemorySegment）或拷贝（byte[]）。 */
    @FunctionalInterface
    public interface Callback {
        void accept(Object key, Object value);
    }

    private static final MethodHandle ON_SCAN;

    static {
        try {
            ON_SCAN = MethodHandles.lookup().findStatic(ParallelScan.class, "onScan",
                    MethodType.methodType(void.class, State.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ParallelScan() {
    }

    private static final class State {
        final Callback callback;
        final boolean copy;
        final AtomicReference<Throwable> error = new AtomicReference<>();

        State(Callback callback, boolean copy) {
            this.callback = callback;
            this.copy = copy;
        }
    }

    /** 返回遍历到的 key 数；threads = 0 ⇒ 硬件线程数；prefix 为 null ⇒ 全表。 */
    public static long run(NativeHandle.CaskHandle h, byte[] prefix, long threads, boolean copy, Callback callback) {
        if (threads < 0) throw new IllegalArgumentException("threads");
        var state = new State(callback, copy);
        long count;
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena(); Arena stubs = Arena.ofShared()) {
            MemorySegment stub = BitcaskLibrary.LINKER.upcallStub(ON_SCAN.bindTo(state), BitcaskNative.SCAN_FN, stubs);
            MemorySegment p = prefix == null ? a.emptySlice() : a.slice(prefix);
            MemorySegment countOut = a.alloc(SIZE_T);
            MemorySegment f = a.fault();
            int err = BitcaskNative.parallelScanPrefix(cask, threads, p, stub, MemorySegment.NULL, countOut, f);
            count = countOut.get(SIZE_T, 0);
            if (state.error.get() == null) Faults.check(err, f, "bitcask_parallel_scan_prefix");
        } finally {
            h.release();
        }
        Throwable t = state.error.get();
        if (t != null) {
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new IllegalStateException(t);
        }
        return count;
    }

    private static void onScan(State state, MemorySegment ctx, MemorySegment key, MemorySegment value) {
        if (state.error.get() != null) return;
        if (state.copy) {
            try {
                state.callback.accept(NativeTypes.sliceBytes(key, 0), NativeTypes.sliceBytes(value, 0));
            } catch (Throwable e) {
                state.error.compareAndSet(null, e);
            }
            return;
        }
        try (Arena views = Arena.ofConfined()) {
            state.callback.accept(view(key, views), view(value, views));
        } catch (Throwable e) {
            state.error.compareAndSet(null, e);
        }
    }

    private static MemorySegment view(MemorySegment slice, Arena views) {
        MemorySegment v = NativeTypes.sliceView(slice, 0);
        if (v.byteSize() == 0) return MemorySegment.NULL;
        return v.reinterpret(v.byteSize(), views, null).asReadOnly();
    }
}
