package io.ailink.agentforge.agent.tools.shell;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 默认的 {@link CommandRunner}：{@link ProcessBuilder} + 两条 drain 线程 + 超时。
 *
 * <ul>
 *   <li>stdout / stderr 分开收（不并 {@code 2>&1}）；每流到 maxBytes 后继续读但丢弃 ——
 *       不读的话子进程写满管道会卡死，而触到上限**不杀进程**（命令照常跑完）。</li>
 *   <li>超时 / 按停：先杀子孙再杀自己（{@code make -j8} 的孩子们一起掐掉）。</li>
 *   <li>stdin 立即关掉：模型的命令等不来输入，读 stdin 的命令拿到 EOF 而不是挂到超时。</li>
 * </ul>
 */
public final class LocalCommandRunner implements CommandRunner {

    @Override
    public Result run(Request req) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(req.argv()).directory(req.cwd().toFile());
        Process p = pb.start();
        p.getOutputStream().close();
        Drain out = new Drain(p.getInputStream(), req.maxBytes());
        Drain err = new Drain(p.getErrorStream(), req.maxBytes());
        Thread to = Thread.ofVirtual().start(out);
        Thread te = Thread.ofVirtual().start(err);
        Runnable unhook = req.cancel() == null ? () -> { } : req.cancel().onCancel(() -> kill(p));
        boolean timedOut = false;
        try {
            if (!p.waitFor(Math.max(1, req.timeoutMs()), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                kill(p);
                p.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            kill(p);
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            unhook.run();
        }
        // 孙进程可能还拿着管道：给 drain 一点时间，然后不再等
        to.join(2000);
        te.join(2000);
        int code = p.isAlive() ? -1 : p.exitValue();
        return new Result(code, out.bytes(), err.bytes(), timedOut, out.truncated, err.truncated);
    }

    @Override
    public Background spawn(String command, List<String> argv, Path cwd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        p.getOutputStream().close();
        return new Background() {
            @Override public long pid() { return p.pid(); }

            @Override public CompletableFuture<Integer> onExit() { return p.onExit().thenApply(Process::exitValue); }
        };
    }

    static void kill(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) { }
        p.destroyForcibly();
    }

    private static final class Drain implements Runnable {
        private final InputStream in;
        private final long max;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile boolean truncated;

        Drain(InputStream in, long max) { this.in = in; this.max = max; }

        @Override public void run() {
            byte[] b = new byte[8192];
            try (in) {
                int n;
                while ((n = in.read(b)) > 0) {
                    synchronized (buf) {
                        long room = max - buf.size();
                        if (room > 0) buf.write(b, 0, (int) Math.min(room, n));
                        if (n > room) truncated = true;
                    }
                }
            } catch (IOException ignored) { }
        }

        byte[] bytes() { synchronized (buf) { return buf.toByteArray(); } }
    }
}
