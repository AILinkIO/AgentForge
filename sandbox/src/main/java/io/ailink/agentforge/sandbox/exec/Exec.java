package io.ailink.agentforge.sandbox.exec;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 沙盒与执行后端之间的小件：
 * <ul>
 * <li>{@link #run}：起进程、写完 stdin 后关上、读完输出、等退出 ⇒ {@link ExecResult}（拒判看它）。请求给了
 *     {@code timeoutMs} 时到点杀进程树、保留已收到的部分输出、{@code timedOut = true}、退出码回 {@value #TIMEOUT_EXIT_CODE}。</li>
 * <li>{@link #rewriting}：不自己起进程的包装（bwrap / Landlock / 标记）——改写请求再交给内层，进程退出 / 关闭后调收尾。</li>
 * <li>{@link #withCleanup}：给进程挂收尾（只做一次）。</li>
 * </ul>
 */
public final class Exec {
    private Exec() {
    }

    /** 超时的退出码（同 coreutils {@code timeout}）。 */
    public static final int TIMEOUT_EXIT_CODE = 124;

    /** 本机、无沙盒的后端。 */
    public static ExecBackend localBackend() {
        return new LocalExecBackend();
    }

    public static ExecResult run(ExecBackend backend, ExecRequest request) throws IOException, InterruptedException {
        return run(backend, request, null);
    }

    /**
     * 经 {@code backend} 起进程并跑完（阻塞；被中断 ⇒ 进程随 close 被杀）。{@code stdin} 可为 {@code null}。
     */
    public static ExecResult run(ExecBackend backend, ExecRequest request, byte[] stdin) throws IOException, InterruptedException {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(request, "request");
        Long timeout = request.timeoutMs();
        if (timeout != null && timeout <= 0) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/exec-bad-timeout");
        ExecProcess p = backend.start(request);
        AtomicBoolean expired = new AtomicBoolean();
        Thread watcher = null;
        try {
            if (timeout != null) {
                long ms = timeout;
                watcher = Thread.startVirtualThread(() -> {
                    try {
                        Thread.sleep(ms);
                        if (expired.compareAndSet(false, true)) p.kill();
                    } catch (InterruptedException ignored) {
                        // 正常结束
                    }
                });
            }
            if (stdin != null) p.writeInput(stdin);
            p.closeInput();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (OutputChunk c; (c = p.nextOutput()) != null; ) {
                (c.stream() == OutputChunk.Stream.STDOUT ? out : err).writeBytes(c.data());
                all.writeBytes(c.data());
            }
            int code = p.waitForExit();
            boolean timedOut = expired.get();
            return new ExecResult(timedOut ? TIMEOUT_EXIT_CODE : code, out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8), all.toString(StandardCharsets.UTF_8), timedOut);
        } finally {
            if (watcher != null) watcher.interrupt();
            p.close();
        }
    }

    /** 请求改写的结果：改后的请求、可选收尾、要不要另起线程等退出（没人 wait / close 也能收尾）。 */
    public record Rewrite(ExecRequest request, Runnable release, boolean watch) {
        public static Rewrite of(ExecRequest request) {
            return new Rewrite(request, null, false);
        }
    }

    /** 包装：{@code rewrite} 改写请求；起的进程交给 {@code inner}，挂上收尾。改写后起进程失败 ⇒ 立刻收尾再抛。 */
    public static ExecBackend rewriting(ExecBackend inner, ExecCapabilities caps, Function<ExecRequest, Rewrite> rewrite) {
        Objects.requireNonNull(inner, "inner");
        return new ExecBackend() {
            @Override
            public ExecCapabilities capabilities() {
                return caps;
            }

            @Override
            public ExecProcess start(ExecRequest request) throws IOException {
                Rewrite r = rewrite.apply(request);
                if (r.release() == null) return inner.start(r.request());
                ExecProcess p;
                try {
                    p = inner.start(r.request());
                } catch (Throwable t) {
                    r.release().run();
                    throw t;
                }
                return withCleanup(p, r.release(), r.watch());
            }
        };
    }

    /** 进程退出（或被关闭）后调 {@code release}，只做一次；{@code watch} ⇒ 另起虚拟线程等退出。 */
    public static ExecProcess withCleanup(ExecProcess inner, Runnable release, boolean watch) {
        CleanupProcess p = new CleanupProcess(inner, release);
        if (watch) {
            Thread.startVirtualThread(() -> {
                try {
                    inner.waitForExit();
                } catch (Throwable ignored) {
                    // 照样收尾
                }
                p.release();
            });
        }
        return p;
    }

    /** 在请求的环境覆盖上并入 {@code extra}（不改原请求）。 */
    public static ExecRequest withEnvironment(ExecRequest request, Map<String, String> extra) {
        return request.mergeEnvironment(extra);
    }

    /** 收尾只做一次；输掉竞争的一方要等收尾做完再返回（同步方法），否则调用方以为已收尾。 */
    private static final class CleanupProcess implements ExecProcess {
        private final ExecProcess inner;
        private Runnable release;

        CleanupProcess(ExecProcess inner, Runnable release) {
            this.inner = inner;
            this.release = release;
        }

        synchronized void release() {
            Runnable r = release;
            release = null;
            if (r != null) r.run();
        }

        @Override
        public long pid() {
            return inner.pid();
        }

        @Override
        public Integer exitCode() {
            return inner.exitCode();
        }

        @Override
        public OutputChunk nextOutput() throws InterruptedException {
            return inner.nextOutput();
        }

        @Override
        public void writeInput(byte[] data) {
            inner.writeInput(data);
        }

        @Override
        public void closeInput() {
            inner.closeInput();
        }

        @Override
        public int waitForExit() throws InterruptedException {
            int c = inner.waitForExit();
            release();
            return c;
        }

        @Override
        public Integer waitForExit(java.time.Duration timeout) throws InterruptedException {
            Integer c = inner.waitForExit(timeout);
            if (c != null) release();
            return c;
        }

        @Override
        public boolean tryInterrupt() {
            return inner.tryInterrupt();
        }

        @Override
        public void kill() {
            inner.kill();
        }

        @Override
        public void close() {
            try {
                inner.close();
            } finally {
                release();
            }
        }
    }
}
