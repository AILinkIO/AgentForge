package io.ailink.agentforge.bitcask.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Cleaner;
import java.util.concurrent.atomic.AtomicInteger;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * 原生句柄：引用计数 + 关闭标记。每次原生调用 {@link #acquire()} / {@link #release()} 一对
 * （调用期间 close 推迟到调用结束），{@link #close()} 幂等，计数归零且已关 ⇒ 真正释放（恰一次）。
 * {@link Cleaner} 兜底：句柄对象不可达而没 close ⇒ 清理线程代关。释放动作只引用 {@link State}。
 */
public abstract class NativeHandle implements AutoCloseable {
    static final Cleaner CLEANER = Cleaner.create();

    /** 计数状态 + 释放动作；也是 Cleaner 的清理动作（= 关闭）。 */
    abstract static class State implements Runnable {
        /** 低位 = 已关；其余 = 引用数 × 2（初值 2：句柄自己那份）。 */
        private final AtomicInteger bits = new AtomicInteger(2);
        final MemorySegment ptr;

        State(MemorySegment ptr) {
            this.ptr = ptr;
        }

        abstract void free();

        final boolean tryAcquire() {
            while (true) {
                int v = bits.get();
                if ((v & 1) != 0) return false;
                if (bits.compareAndSet(v, v + 2)) return true;
            }
        }

        final void addRef() {
            bits.addAndGet(2);
        }

        final void release() {
            if (bits.addAndGet(-2) == 1) free();
        }

        final boolean isClosed() {
            return (bits.get() & 1) != 0;
        }

        @Override
        public final void run() {
            while (true) {
                int v = bits.get();
                if ((v & 1) != 0) return;
                int next = (v | 1) - 2;
                if (bits.compareAndSet(v, next)) {
                    if (next == 1) free();
                    return;
                }
            }
        }
    }

    private final State state;
    private final Cleaner.Cleanable cleanable;
    private final String typeName;

    NativeHandle(State state, String typeName) {
        this.state = state;
        this.typeName = typeName;
        this.cleanable = CLEANER.register(this, state);
    }

    final State state() {
        return state;
    }

    /** 报错用的类型名（如 {@code BitcaskKv}）。 */
    public final String typeName() {
        return typeName;
    }

    /** 加一份引用并回原生指针；已关 ⇒ {@code bitcask/disposed}。 */
    public final MemorySegment acquire() {
        if (!state.tryAcquire()) throw disposed(typeName);
        return state.ptr;
    }

    public final void release() {
        state.release();
    }

    public final boolean isClosed() {
        return state.isClosed();
    }

    /** 幂等。 */
    @Override
    public final void close() {
        cleanable.clean();
    }

    public static BitcaskException disposed(String typeName) {
        return BitcaskException.of("bitcask/disposed", typeName);
    }

    // ═══ 具体句柄 ═══

    /** {@code bitcask_t*}。释放 = {@code bitcask_close}，之后才关日志 upcall 桩的 arena。迭代器对它加引用。 */
    public static final class CaskHandle extends NativeHandle {
        CaskHandle(MemorySegment cask, Arena logArena, String typeName) {
            super(new CaskState(cask, logArena), typeName);
        }

        private static final class CaskState extends State {
            private final Arena logArena;

            CaskState(MemorySegment ptr, Arena logArena) {
                super(ptr);
                this.logArena = logArena;
            }

            @Override
            void free() {
                BitcaskNative.close(ptr);
                if (logArena != null) logArena.close();
            }
        }
    }

    /** {@code bitcask_get_result_t*}。释放 = {@code bitcask_get_result_free}。 */
    public static final class GetResultHandle extends NativeHandle {
        GetResultHandle(MemorySegment result) {
            super(new ResultState(result), "BitcaskRecord");
        }

        private static final class ResultState extends State {
            ResultState(MemorySegment ptr) {
                super(ptr);
            }

            @Override
            void free() {
                BitcaskNative.getResultFree(ptr);
            }
        }
    }

    /** 迭代器句柄：持有 cask 一份引用，自己释放之后才放（上游要求迭代器先于 bitcask_close 释放）。须在持有 cask 引用期间构造。 */
    public static final class IterHandle extends NativeHandle {
        IterHandle(MemorySegment iter, CaskHandle cask, boolean range, String typeName) {
            super(new IterState(iter, cask.state(), range), typeName);
        }

        private static final class IterState extends State {
            private final State cask;
            private final boolean range;

            IterState(MemorySegment ptr, State cask, boolean range) {
                super(ptr);
                cask.addRef();
                this.cask = cask;
                this.range = range;
            }

            @Override
            void free() {
                try {
                    if (range) BitcaskNative.rangeIterRelease(ptr);
                    else BitcaskNative.iterRelease(ptr);
                } finally {
                    cask.release();
                }
            }
        }
    }
}
