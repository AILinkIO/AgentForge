package io.ailink.agentforge.sandbox.exec;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.ffm.Libc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本机、无沙盒的执行后端（≙ C# LocalExecBackend）：{@link ProcessBuilder} + 管道，不加任何限制。
 * 名字就是提醒：宿主要么显式选它，要么选沙盒后端（它包装的内层通常就是这一个）。不给伪终端。
 * 进程退出后至多再等 {@code drainGrace} 收尾输出：孙进程拿着管道不放时也不会挂住（参照 Codex 的 2 s）。
 */
public final class LocalExecBackend implements ExecBackend {
    private static final ExecCapabilities CAPS = new ExecCapabilities("local", false, false);
    private final Duration drainGrace;

    public LocalExecBackend() {
        this(Duration.ofSeconds(2));
    }

    public LocalExecBackend(Duration drainGrace) {
        this.drainGrace = Objects.requireNonNull(drainGrace, "drainGrace");
    }

    @Override
    public ExecCapabilities capabilities() {
        return CAPS;
    }

    @Override
    public ExecProcess start(ExecRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        List<String> argv = request.arguments();
        if (argv.isEmpty() || argv.getFirst() == null || argv.getFirst().isBlank())
            throw SandboxException.of(ErrorKind.ARGUMENT, "exec/empty-arguments");
        if (request.tty()) throw SandboxException.of(ErrorKind.NOT_SUPPORTED, "exec/tty-unsupported", CAPS.name());
        String prog = argv.getFirst();
        if (Platform.isWindows() && !request.allowBatchFile() && isBatchFile(prog))
            throw SandboxException.of(ErrorKind.ARGUMENT, "process/batch-file-refused", prog);
        ProcessBuilder pb = new ProcessBuilder(argv);
        Map<String, String> env = pb.environment();
        if (request.workingDirectory() != null) pb.directory(new File(request.workingDirectory()));
        if (!request.inheritEnvironment()) env.clear();
        request.environment().forEach((k, v) -> {
            if (v == null) env.remove(k);
            else env.put(k, v);
        });
        return new LocalProcess(pb.start(), drainGrace.toNanos());
    }

    static boolean isBatchFile(String program) {
        String n = program.toLowerCase(Locale.ROOT);
        return n.endsWith(".bat") || n.endsWith(".cmd");
    }

    /** 杀进程树：先子孙再自己（JDK 的 destroy 不管子进程）。 */
    public static void killTree(ProcessHandle process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
            // 枚举失败：至少杀自己
        }
        process.destroyForcibly();
    }

    private static final Object END = new Object();

    private static final class LocalProcess implements ExecProcess {
        private final Process process;
        private final long graceNs;
        private final LinkedBlockingQueue<Object> output = new LinkedBlockingQueue<>();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean disposed = new AtomicBoolean();
        private final AtomicInteger left = new AtomicInteger(2);
        private volatile boolean stop;
        private boolean ended;
        private final Thread out;
        private final Thread err;

        LocalProcess(Process process, long graceNs) {
            this.process = process;
            this.graceNs = graceNs;
            this.out = pump(process.getInputStream(), OutputChunk.Stream.STDOUT);
            this.err = pump(process.getErrorStream(), OutputChunk.Stream.STDERR);
            // 进程退出后过了宽限还没读到头（孙进程拿着管道）⇒ 不再等，收尾
            process.onExit().thenRunAsync(() -> {
                if (!joinPumps(graceNs)) stop = true;
                complete();
            }, r -> Thread.ofVirtual().name("exec-exit").start(r));
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) output.add(END);
        }

        private Thread pump(InputStream stream, OutputChunk.Stream which) {
            return Thread.ofPlatform().daemon(true).name("exec-pump").start(() -> {
                byte[] buf = new byte[16 * 1024];
                try {
                    while (true) {
                        int n = stream.read(buf);
                        if (n < 0 || stop) break;
                        if (n > 0) output.add(new OutputChunk(which, Arrays.copyOf(buf, n)));
                    }
                } catch (IOException ignored) {
                    // 收尾 / 管道断开：到此为止
                } finally {
                    if (left.decrementAndGet() == 0) complete();
                }
            });
        }

        private boolean joinPumps(long nanos) {
            long deadline = System.nanoTime() + nanos;
            try {
                out.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
                err.join(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return !out.isAlive() && !err.isAlive();
        }

        @Override
        public long pid() {
            return process.pid();
        }

        @Override
        public Integer exitCode() {
            return process.isAlive() ? null : process.exitValue();
        }

        @Override
        public synchronized OutputChunk nextOutput() throws InterruptedException {
            if (ended) return null;
            Object o = output.take();
            if (o == END) {
                ended = true;
                return null;
            }
            return (OutputChunk) o;
        }

        @Override
        public void writeInput(byte[] data) {
            try {
                OutputStream stdin = process.getOutputStream();
                stdin.write(data);
                stdin.flush();
            } catch (IOException ignored) {
                // 子进程已关 stdin / 已退出：丢弃
            }
        }

        @Override
        public void closeInput() {
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // 已关
            }
        }

        @Override
        public int waitForExit() throws InterruptedException {
            return process.waitFor();
        }

        @Override
        public Integer waitForExit(Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toNanos(), TimeUnit.NANOSECONDS) ? process.exitValue() : null;
        }

        @Override
        public boolean tryInterrupt() {
            // Ctrl-C 发给整个前台进程组；ProcessBuilder 不能在 exec 前 setpgid ⇒ 按进程树发，先子孙后自己：
            // dash 等 shell 等前台子进程时自己接住 SIGINT 不退，子进程收不到就一直挂着。
            if (Platform.isWindows() || !Libc.hasKill() || exitCode() != null) return false;
            process.descendants().forEach(h -> Libc.kill((int) h.pid(), Libc.SIGINT));
            return Libc.kill((int) process.pid(), Libc.SIGINT) == 0;
        }

        @Override
        public void kill() {
            killTree(process.toHandle());
        }

        @Override
        public void close() {
            if (!disposed.compareAndSet(false, true)) return;
            if (exitCode() == null) kill();
            if (!joinPumps(graceNs)) stop = true;
            complete();
            closeInput();
        }
    }
}
