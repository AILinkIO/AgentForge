package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GET_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_META;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_ORD;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_TSTAMP;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_VALUE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_VECTOR;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.GR_VECTOR_LEN;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SIZE_T;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.ref.Reference;
import java.util.function.Consumer;

import io.ailink.agentforge.bitcask.ffm.NativeHandle;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * 一次零拷贝读（{@link Bitcask#readRecord}）的结果，借 C 侧缓冲。{@code value()} 等拷出；{@code xxxSegment()} 是零拷贝只读视图，
 * {@link #close()} 之后再碰视图 ⇒ {@link IllegalStateException}；关闭后再调访问器 ⇒ {@code bitcask/disposed}。
 */
public final class Record implements AutoCloseable {
    private final NativeHandle.GetResultHandle handle;
    private Arena views;

    Record(NativeHandle.GetResultHandle handle) {
        this.handle = handle;
    }

    private MemorySegment result() {
        return handle.acquire().reinterpret(GET_RESULT.byteSize());
    }

    public byte[] value() {
        MemorySegment r = result();
        try {
            return NativeTypes.sliceBytes(r, GR_VALUE);
        } finally {
            handle.release();
        }
    }

    public byte[] meta() {
        MemorySegment r = result();
        try {
            return NativeTypes.sliceBytes(r, GR_META);
        } finally {
            handle.release();
        }
    }

    public float[] vector() {
        MemorySegment r = result();
        try {
            return NativeTypes.floats(r.get(ADDRESS, GR_VECTOR), r.get(SIZE_T, GR_VECTOR_LEN));
        } finally {
            handle.release();
        }
    }

    public MemorySegment valueSegment() {
        return view(GR_VALUE);
    }

    public MemorySegment metaSegment() {
        return view(GR_META);
    }

    public MemorySegment vectorSegment() {
        MemorySegment r = result();
        try {
            MemorySegment p = r.get(ADDRESS, GR_VECTOR);
            long n = r.get(SIZE_T, GR_VECTOR_LEN);
            if (p.equals(MemorySegment.NULL) || n == 0) return MemorySegment.NULL;
            return p.reinterpret(Math.multiplyExact(n, 4L), viewArena(), keepAlive()).asReadOnly();
        } finally {
            handle.release();
        }
    }

    private MemorySegment view(long sliceOffset) {
        MemorySegment r = result();
        try {
            MemorySegment v = NativeTypes.sliceView(r, sliceOffset);
            if (v.byteSize() == 0) return MemorySegment.NULL;
            return v.reinterpret(v.byteSize(), viewArena(), keepAlive()).asReadOnly();
        } finally {
            handle.release();
        }
    }

    /** 视图 → arena → 清理动作 → 本记录：视图活着时 Cleaner 不会替没 close 的记录释放 C 侧缓冲。 */
    private Consumer<MemorySegment> keepAlive() {
        return seg -> Reference.reachabilityFence(this);
    }

    private synchronized Arena viewArena() {
        if (views == null) views = Arena.ofShared();
        return views;
    }

    public long timestamp() {
        MemorySegment r = result();
        try {
            return r.get(JAVA_LONG, GR_TSTAMP);
        } finally {
            handle.release();
        }
    }

    public long ord() {
        MemorySegment r = result();
        try {
            return r.get(JAVA_LONG, GR_ORD);
        } finally {
            handle.release();
        }
    }

    public boolean isClosed() {
        return handle.isClosed();
    }

    /** 幂等：先让视图失效，再释放 C 侧缓冲。 */
    @Override
    public void close() {
        synchronized (this) {
            if (views != null) {
                views.close();
                views = null;
            }
        }
        handle.close();
    }
}
