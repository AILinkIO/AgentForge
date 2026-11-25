package io.ailink.agentforge.sandbox.proxy;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.ffm.Libc;
import io.ailink.agentforge.sandbox.ffm.SandboxNative;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 宿主侧的 netns 代理桥：在私有临时目录监听 AF_UNIX；沙盒内目标进程经 LD_PRELOAD（原生件）连进来，把它自己 netns 里的回环监听 fd 用
 * SCM_RIGHTS 送来。宿主接过这个 fd，再把流量转发给回环代理（{@link NetworkProxyServer}）。控制面只收 AF_INET / TCP / 已监听的 IPv4 回环 fd
 * （原生侧校验）。JDK 不能把裸 fd 包成套接字 ⇒ 控制面、收到的监听 fd 与沙盒侧连接都经 libc（{@link Libc} / {@link SandboxNative}），
 * 阻塞调用跑在平台线程上（⛔ 虚拟线程：会钉住载体线程）。
 */
public final class ProxyBridge implements Closeable {
    /** 桥控制 socket 路径的环境名（子进程侧原生常量同名，勿改）。 */
    public static final String CONTROL_PATH_VARIABLE = "KS_PROXY_BRIDGE_SOCKET";
    /** 原生构造函数跳过的可执行文件（bwrap 自己）。 */
    public static final String SKIP_EXECUTABLE_VARIABLE = "KS_PROXY_BRIDGE_SKIP_EXE";

    private final NetworkProxyServer proxy;
    private final Path directory;
    private final String controlPath;
    private final int listener;
    private final Set<Integer> fds = ConcurrentHashMap.newKeySet();
    private final Set<Thread> work = ConcurrentHashMap.newKeySet();
    private final Set<Socket> upstreams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopping;

    /** 造桥（代理须已启动）；绑好控制 socket，还没 listen。 */
    public ProxyBridge(NetworkProxyServer proxy) throws IOException {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        if (proxy.httpEndpoint() == null) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-bridge-proxy-not-started");
        this.directory = Files.createTempDirectory("ks-proxy-bridge-");
        this.controlPath = directory.resolve("control.sock").toString();
        int fd = Libc.unixListenSocket(controlPath);
        if (fd < 0) {
            deleteTree(directory);
            throw new IOException("AF_UNIX bind " + controlPath + ": errno " + (-fd));
        }
        this.listener = fd;
    }

    /** AF_UNIX 控制 socket 的宿主路径；bwrap 把它只读绑定到沙盒里同一路径。 */
    public String controlPath() {
        return controlPath;
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 尽力
        }
    }

    private void spawn(String name, Runnable f) {
        Thread t = Thread.ofPlatform().name(name).daemon(true).unstarted(() -> {
            try {
                f.run();
            } finally {
                work.remove(Thread.currentThread());
            }
        });
        work.add(t);
        t.start();
    }

    /** 开始等沙盒里的控制连接（第二次 ⇒ {@code sandbox/network-proxy-bridge-already-started}）。 */
    public ProxyBridge start() {
        if (!started.compareAndSet(false, true)) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-bridge-already-started");
        Libc.listen(listener, 1);
        spawn("proxy-bridge-accept", () -> {
            while (!stopping) {
                int control = Libc.accept(listener);
                if (control < 0) return;
                fds.add(control);
                spawn("proxy-bridge-control", () -> relayListener(control));
            }
        });
        return this;
    }

    private static InetSocketAddress endpoint(URI u) {
        String a = u == null ? null : u.getAuthority();
        int sep = a == null ? -1 : a.lastIndexOf(':');
        if (sep <= 0) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-bridge-bad-endpoint");
        try {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(a.substring(sep + 1)));
        } catch (NumberFormatException e) {
            throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-bridge-bad-endpoint");
        }
    }

    private void relayListener(int control) {
        try {
            int received = SandboxNative.bridgeReceiveListener(control);
            if (received < 0) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-bridge-bad-listener", received);
            fds.add(received);
            // 收到 fd、接管监听后才 ack；子进程在这之后才会跑主程序
            Libc.writeAll(control, new byte[] {'A'}, 1);
            spawn("proxy-bridge-listener", () -> relayLoop(received));
        } catch (RuntimeException ignored) {
            // 失败即断
        } finally {
            fds.remove(control);
            Libc.close(control);
        }
    }

    private void relayLoop(int listenerFd) {
        try {
            InetSocketAddress ep = endpoint(proxy.httpEndpoint());
            while (!stopping) {
                int client = Libc.accept(listenerFd);
                if (client < 0) break;
                fds.add(client);
                spawn("proxy-bridge-forward", () -> forward(client, ep));
            }
        } catch (RuntimeException ignored) {
            // 失败即断
        } finally {
            fds.remove(listenerFd);
            Libc.close(listenerFd);
        }
    }

    private void forward(int client, InetSocketAddress ep) {
        Socket up = new Socket();
        upstreams.add(up);
        Thread down = null;
        try {
            up.setTcpNoDelay(true);
            up.connect(ep);
            InputStream upIn = up.getInputStream();
            OutputStream upOut = up.getOutputStream();
            down = Thread.startVirtualThread(() -> {
                byte[] buf = new byte[16 * 1024];
                try {
                    int n;
                    while ((n = upIn.read(buf)) > 0) if (!Libc.writeAll(client, buf, n)) break;
                } catch (IOException ignored) {
                    // 上游断了
                }
                Libc.shutdown(client, Libc.SHUT_WR);
            });
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = Libc.read(client, buf, buf.length)) > 0) {
                upOut.write(buf, 0, n);
                upOut.flush();
            }
            // 一侧关了，另一侧尽快排空 / 关闭
            up.shutdownOutput();
            down.join(1000);
        } catch (IOException ignored) {
            // 断开即止
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            NetworkProxyServer.closeQuietly(up);
            upstreams.remove(up);
            if (down != null) {
                try {
                    down.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            fds.remove(client);
            Libc.close(client);
        }
    }

    @Override
    public void close() {
        stopping = true;
        // shutdown 解开阻塞在 accept / read 上的线程，再关
        Libc.shutdown(listener, Libc.SHUT_RDWR);
        for (int fd : new ArrayList<>(fds)) Libc.shutdown(fd, Libc.SHUT_RDWR);
        for (Socket s : new ArrayList<>(upstreams)) NetworkProxyServer.closeQuietly(s);
        try {
            Files.deleteIfExists(Path.of(controlPath));
        } catch (IOException ignored) {
            // 尽力
        }
        for (Thread t : new ArrayList<>(work)) {
            if (t == Thread.currentThread()) continue;
            try {
                t.join(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Libc.close(listener);
        for (int fd : new ArrayList<>(fds)) Libc.close(fd);
        fds.clear();
        deleteTree(directory);
    }

    /** 一套代理 + 桥（bwrap 的 PROXIED 网络用）；关闭时先桥后代理。 */
    public record Runtime(NetworkProxyServer proxy, ProxyBridge bridge) implements Closeable {
        @Override
        public void close() {
            NetworkProxyServer.closeQuietly(bridge);
            NetworkProxyServer.closeQuietly(proxy);
        }
    }

    /** 起一套代理 + 桥。 */
    public static Runtime runtime(ProxyOptions options) throws IOException {
        NetworkProxyServer p = new NetworkProxyServer(options);
        try {
            p.start();
            ProxyBridge b = new ProxyBridge(p).start();
            return new Runtime(p, b);
        } catch (Throwable t) {
            p.close();
            throw t;
        }
    }
}
