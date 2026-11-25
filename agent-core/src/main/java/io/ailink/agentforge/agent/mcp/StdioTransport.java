package io.ailink.agentforge.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * 本地服务器：子进程 + stdio，NDJSON 分帧（一行一条）。
 *
 * <p>一台 = 一个子进程 + 两条读线程（stdout 协议帧、stderr 尾巴）。stdout 读到 EOF = 子进程没了 ⇒
 * 让所有在等的请求立刻失败，然后在<b>读线程上</b>取退出码交给 {@code onExit}（等进程收摊可能要几秒，
 * 不该落在调用方那条线程上）。
 *
 * <p>⚠️ <b>stderr 一定要自己收</b>：原样转到我们的 stderr 会把 TUI 写花；而且这段尾巴是排查
 * "服务器起不来"唯一的线索（{@code npx: not found} 就写在那儿）。有界 4 KB。
 *
 * <p>⚠️ <b>收摊要连子孙一起收</b>：{@code npx} / {@code uvx} / {@code sh -c} 是一层壳，真正的服务器是
 * 它的孩子 —— 光关 stdin 只让壳看到 EOF，孩子会活过我们自己的进程。Java 有
 * {@link ProcessHandle#descendants()}，所以不必像 coxswain 那样靠进程组（那边"detached 与 kill-group
 * 是一对"的耦合在这里不存在）。
 */
final class StdioTransport extends RpcTransport {
    static final int STDERR_CAP = 4096;

    private final Process process;
    private final OutputStream stdin;
    private final StringBuilder stderrTail = new StringBuilder();
    private final Object writeLock = new Object();

    private StdioTransport(Process p) {
        this.process = p;
        this.stdin = p.getOutputStream();
    }

    /**
     * 起子进程。{@code env} 叠在父进程环境上（opencode 同；coxswain 因 Chez 枚举不了环境而没做）。
     *
     * @param onExit 子进程没了之后调一次，参数是退出码（取不到为 -1）
     */
    static StdioTransport start(List<String> argv, Path cwd, Map<String, String> env, IntConsumer onExit)
            throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        if (cwd != null) pb.directory(cwd.toFile());
        if (env != null && !env.isEmpty()) pb.environment().putAll(env);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE).redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE);
        Process p = pb.start();
        StdioTransport t = new StdioTransport(p);
        Thread.ofPlatform().daemon().name("mcp-stdio-out-" + p.pid()).start(() -> t.readLoop(onExit));
        Thread.ofPlatform().daemon().name("mcp-stdio-err-" + p.pid()).start(t::errLoop);
        return t;
    }

    long pid() { return process.pid(); }

    ProcessHandle handle() { return process.toHandle(); }

    String stderrTail() {
        synchronized (stderrTail) { return stderrTail.toString().strip(); }
    }

    @Override
    protected void send(ObjectNode msg) throws IOException {
        if (isClosed()) throw new McpException.Closed(closedReason());
        byte[] line = (JSON.writeValueAsString(msg) + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            stdin.write(line);
            stdin.flush();
        }
    }

    private void readLoop(IntConsumer onExit) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg;
                try {
                    msg = JSON.readTree(line);
                } catch (RuntimeException e) {
                    // ⚠️ 服务器往 stdout 写了非协议的东西（日志）：记进 stderr 尾巴，别让整条流废掉
                    noteStderr("[stdout 非 JSON] " + line + "\n");
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException ignored) {
            // 流断了
        }
        int code = -1;
        try {
            if (process.waitFor(5, TimeUnit.SECONDS)) code = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        markClosed(code >= 0 ? "子进程退出了（退出码 " + code + "）" : "子进程退出了");
        if (onExit != null) {
            try { onExit.accept(code); } catch (RuntimeException ignored) { }
        }
    }

    private void errLoop() {
        byte[] buf = new byte[2048];
        try (InputStream in = process.getErrorStream()) {
            int n;
            while ((n = in.read(buf)) > 0) noteStderr(new String(buf, 0, n, StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 子进程没了
        }
    }

    private void noteStderr(String s) {
        synchronized (stderrTail) {
            stderrTail.append(s);
            int over = stderrTail.length() - STDERR_CAP;
            if (over > 0) stderrTail.delete(0, over);
        }
    }

    /**
     * 收摊：关 stdin（礼貌）→ 子孙与本体一起 SIGTERM → 等一下 → 还活着的 SIGKILL。
     * 杀不动一律吞掉：进程可能已经自己退了。
     */
    @Override
    public void close() {
        markClosed("连接已关闭");
        try { stdin.close(); } catch (IOException ignored) { }
        List<ProcessHandle> kids;
        try { kids = process.descendants().toList(); } catch (RuntimeException e) { kids = List.of(); }
        for (ProcessHandle k : kids) k.destroy();
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        for (ProcessHandle k : kids) if (k.isAlive()) k.destroyForcibly();
    }
}
