package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.OPT_LOG_CTX;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.OPT_LOG_FN;
import static java.lang.foreign.ValueLayout.ADDRESS;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 开库（{@code bitcask_open_ex2}）+ 日志回调的 upcall 桩。选项 / 策略 / 调优结构体由调用方（{@code Bitcask}）填好；
 * 日志桩放进 shared arena，跟着 {@link NativeHandle.CaskHandle} 走，真正的 {@code bitcask_close} 之后才关。
 */
public final class Open {
    /** 日志回调：level 0 = warn，1 = error。可能从任意原生线程调用；抛的异常被丢掉。 */
    @FunctionalInterface
    public interface LogSink {
        void log(int level, String message);
    }

    private static final MethodHandle ON_LOG;

    static {
        try {
            ON_LOG = MethodHandles.lookup().findStatic(Open.class, "onLog",
                    MethodType.methodType(void.class, LogSink.class, int.class, MemorySegment.class, MemorySegment.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Open() {
    }

    /** 失败 ⇒ {@link io.ailink.agentforge.bitcask.BitcaskException}（操作名沿用 {@code bitcask_open}）。policy / tuning 可为 NULL。 */
    public static NativeHandle.CaskHandle open(String directory, MemorySegment options, MemorySegment policy, MemorySegment tuning,
                                               LogSink log, String typeName) {
        Arena logArena = null;
        if (log != null) {
            logArena = Arena.ofShared();
            MemorySegment stub = BitcaskLibrary.LINKER.upcallStub(ON_LOG.bindTo(log), BitcaskNative.LOG_FN, logArena);
            options.set(ADDRESS, OPT_LOG_FN, stub);
            options.set(ADDRESS, OPT_LOG_CTX, MemorySegment.NULL);
        }
        boolean ok = false;
        try (var a = new NativeArena()) {
            MemorySegment out = a.pointer();
            MemorySegment fault = a.fault();
            int err = BitcaskNative.openEx2(a.utf8z(directory), options, policy, tuning, out, fault);
            if (err != 0) {
                MemorySegment p = out.get(ADDRESS, 0);
                if (!p.equals(MemorySegment.NULL)) BitcaskNative.close(p);
                throw Faults.from(err, fault, "bitcask_open");
            }
            var h = new NativeHandle.CaskHandle(out.get(ADDRESS, 0), logArena, typeName);
            ok = true;
            return h;
        } finally {
            if (!ok && logArena != null) logArena.close();
        }
    }

    private static void onLog(LogSink log, int level, MemorySegment msg, MemorySegment ctx) {
        try {
            log.log(level, NativeTypes.cString(msg));
        } catch (Throwable ignored) {
            // 异常不能穿过原生帧：丢掉
        }
    }
}
