package io.ailink.agentforge.sandbox.windows;

import io.ailink.agentforge.sandbox.exec.ExecProcess;
import io.ailink.agentforge.sandbox.exec.OutputChunk;
import io.ailink.agentforge.sandbox.ffm.WinProcess;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Windows 沙盒进程的 {@link ExecProcess} 面：{@link WinProcess} 只给原生原语，这里起读管道的线程、排队输出、等退出。⚠ 未验证。
 */
public final class WindowsProcess implements ExecProcess {
    private static final Object END = new Object();
    private final WinProcess nativeProcess;
    private final LinkedBlockingQueue<Object> output = new LinkedBlockingQueue<>();
    private boolean ended;

    private WindowsProcess(WinProcess nativeProcess) {
        this.nativeProcess = nativeProcess;
    }

    /** 包装原生进程（起两条平台线程读 stdout / stderr：ReadFile 阻塞，⛔ 虚拟线程）。 */
    public static WindowsProcess wrap(WinProcess nativeProcess) {
        WindowsProcess p = new WindowsProcess(nativeProcess);
        AtomicInteger left = new AtomicInteger(2);
        p.pump(WinProcess.STDOUT, OutputChunk.Stream.STDOUT, left);
        p.pump(WinProcess.STDERR, OutputChunk.Stream.STDERR, left);
        return p;
    }

    private void pump(int stream, OutputChunk.Stream which, AtomicInteger left) {
        Thread.ofPlatform().daemon(true).name("win-sbx-pump").start(() -> {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = nativeProcess.read(stream, buf)) > 0) output.add(new OutputChunk(which, Arrays.copyOf(buf, n)));
            } finally {
                if (left.decrementAndGet() == 0) output.add(END);
            }
        });
    }

    @Override
    public long pid() {
        return nativeProcess.pid();
    }

    @Override
    public Integer exitCode() {
        return nativeProcess.waitExit(0);
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
        nativeProcess.write(data);
    }

    @Override
    public void closeInput() {
        nativeProcess.closeInput();
    }

    @Override
    public int waitForExit() throws InterruptedException {
        while (true) {
            Integer c = nativeProcess.waitExit(200);
            if (c != null) return c;
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    @Override
    public Integer waitForExit(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long leftMs = (deadline - System.nanoTime()) / 1_000_000;
            Integer c = nativeProcess.waitExit((int) Math.max(0, Math.min(200, leftMs)));
            if (c != null) return c;
            if (leftMs <= 0) return null;
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }

    @Override
    public boolean tryInterrupt() {
        return false; // 没有可用的跨控制台 Ctrl-C
    }

    @Override
    public void kill() {
        nativeProcess.kill();
    }

    @Override
    public void close() {
        nativeProcess.close();
    }
}
