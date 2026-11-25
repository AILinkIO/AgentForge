package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * 交给原生侧（或从原生侧拿来）释放的内存：POSIX 是 libc {@code malloc/free}，Windows 是 {@code CoTaskMemAlloc/CoTaskMemFree}。
 * ⛔ 用 {@link Arena} 分配这类缓冲（arena 关时会再释放一次）。句柄在首次用到时才绑定。
 */
public final class NativeMemory {
    private NativeMemory() {
    }

    private static final class H {
        static final MethodHandle ALLOC;
        static final MethodHandle FREE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup lib = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                    ? SymbolLookup.libraryLookup("ole32", Arena.global()) : linker.defaultLookup();
            boolean win = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
            ALLOC = linker.downcallHandle(lib.findOrThrow(win ? "CoTaskMemAlloc" : "malloc"), FunctionDescriptor.of(ADDRESS, JAVA_LONG));
            FREE = linker.downcallHandle(lib.findOrThrow(win ? "CoTaskMemFree" : "free"), FunctionDescriptor.ofVoid(ADDRESS));
        }
    }

    /** 分配 {@code size} 字节（至少 1）；失败 ⇒ {@link OutOfMemoryError}。 */
    public static MemorySegment allocForNative(long size) {
        long n = Math.max(1, size);
        MemorySegment p;
        try {
            p = (MemorySegment) H.ALLOC.invokeExact(n);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (p.equals(MemorySegment.NULL)) throw new OutOfMemoryError();
        return p.reinterpret(n);
    }

    public static void free(MemorySegment p) {
        if (p == null || p.equals(MemorySegment.NULL)) return;
        try {
            H.FREE.invokeExact(p);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }
}
