package io.ailink.agentforge.sandbox.proxy;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 只听 IPv4 回环的本地代理：HTTP 绝对 URI / CONNECT 隧道，SOCKS5 TCP CONNECT。TLS 端到端转发（⛔ MITM）；策略在 DNS / 连接前判，
 * 拒绝的域名不会出网。每条连接一个虚拟线程；{@link #close()} 关监听、断开在跑的连接并等它们收尾。
 */
public final class NetworkProxyServer implements Closeable {
    private final ProxyOptions options;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile ServerSocket http;
    private volatile ServerSocket socks;
    private volatile URI httpEndpoint;
    private volatile URI socksEndpoint;
    private final Set<Thread> work = ConcurrentHashMap.newKeySet();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    /** 造代理（未启动）；{@code connectTimeoutMs} 必须 &gt; 0。 */
    public NetworkProxyServer(ProxyOptions options) {
        this.options = options == null ? ProxyOptions.DEFAULT : options;
        if (this.options.connectTimeoutMs() <= 0) throw SandboxException.of(ErrorKind.ARGUMENT_OUT_OF_RANGE, "sandbox/network-proxy-bad-timeout");
    }

    public ProxyOptions options() {
        return options;
    }

    /** HTTP 代理端点（{@code http://127.0.0.1:port/}）；没启动 ⇒ {@code null}。 */
    public URI httpEndpoint() {
        return httpEndpoint;
    }

    /** SOCKS5 端点（{@code socks5://127.0.0.1:port/}）。 */
    public URI socksEndpoint() {
        return socksEndpoint;
    }

    public NetworkProxyServer start() throws IOException {
        return start(0, 0);
    }

    /** 启动两个回环监听（端口 0 ⇒ 内核分配）；第二次 ⇒ {@code sandbox/network-proxy-already-started}。 */
    public NetworkProxyServer start(int httpPort, int socksPort) throws IOException {
        if (httpPort < 0 || socksPort < 0) throw SandboxException.of(ErrorKind.ARGUMENT_OUT_OF_RANGE, "sandbox/network-proxy-bad-timeout");
        if (!started.compareAndSet(false, true)) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/network-proxy-already-started");
        try {
            http = listener(httpPort);
            socks = listener(socksPort);
            httpEndpoint = URI.create("http://" + http.getInetAddress().getHostAddress() + ":" + http.getLocalPort() + "/");
            socksEndpoint = URI.create("socks5://" + socks.getInetAddress().getHostAddress() + ":" + socks.getLocalPort() + "/");
            ServerSocket h = http, s = socks;
            spawn("proxy-http-accept", () -> acceptLoop(h, this::handleHttp));
            spawn("proxy-socks-accept", () -> acceptLoop(s, this::handleSocks));
            return this;
        } catch (Throwable t) {
            close();
            throw t;
        }
    }

    private static ServerSocket listener(int port) throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port));
        return ss;
    }

    @Override
    public void close() {
        stopping = true;
        closeQuietly(http);
        closeQuietly(socks);
        http = null;
        socks = null;
        httpEndpoint = null;
        socksEndpoint = null;
        for (Socket s : new ArrayList<>(sockets)) closeQuietly(s);
        for (Thread t : new ArrayList<>(work)) {
            if (t == Thread.currentThread()) continue;
            try {
                t.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString() {
        return "NetworkProxyServer[" + httpEndpoint + ", " + socksEndpoint + "]";
    }

    /** 策略判定（单测 / 宿主预检用：不经过 DNS 与连接）。method 缺省时 HTTP 按 GET 算；LIMITED 下只有 HTTP 能过。 */
    public HostPolicy.Decision decide(String host, String method, ProxyProtocol protocol) {
        HostPolicy hosts = options.hosts();
        if (hosts.mode() == HostPolicy.Mode.LIMITED && protocol != ProxyProtocol.HTTP) return HostPolicy.Decision.DENY;
        return hosts.decide(host, method != null ? method : protocol == ProxyProtocol.HTTP ? "GET" : null);
    }

    private boolean authorize(ProxyProtocol protocol, String host, String method) {
        HostPolicy.Decision d = decide(host, method, protocol);
        if (d != HostPolicy.Decision.ASK) return d == HostPolicy.Decision.ALLOW;
        return options.approve(protocol, host);
    }

    private void spawn(String name, Runnable f) {
        Thread t = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                f.run();
            } finally {
                work.remove(Thread.currentThread());
            }
        });
        work.add(t);
        t.start();
    }

    static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
            // 收尾
        }
    }

    // ── 小件 ─────────────────────────────────────────────────────────

    static byte[] readExact(InputStream in, int n) throws IOException {
        if (n < 0) throw new IOException("SOCKS length overflow");
        byte[] r = new byte[n];
        int read = 0;
        while (read < n) {
            int k = in.read(r, read, n - read);
            if (k <= 0) throw new IOException("client closed");
            read += k;
        }
        return r;
    }

    private static void writeStatus(OutputStream out, int status) throws IOException {
        String reason = switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 502 -> "Bad Gateway";
            default -> "Error";
        };
        out.write(("HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private Socket connectUpstream(String host, int port) throws IOException {
        if (port < 1 || port > 65535) throw new IOException("invalid port");
        Socket up = new Socket();
        sockets.add(up);
        try {
            up.connect(new InetSocketAddress(host, port), (int) Math.min(Integer.MAX_VALUE, options.connectTimeoutMs()));
            up.setTcpNoDelay(true);
            return up;
        } catch (Throwable t) {
            sockets.remove(up);
            closeQuietly(up);
            throw t;
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // 一侧断了
        }
    }

    /** 双向转发：任一方向结束 ⇒ 收尾（另一方向随套接字关闭而停）。 */
    private static void relay(Socket a, Socket b) throws InterruptedException {
        Thread t1 = Thread.startVirtualThread(() -> copy(a, b));
        Thread t2 = Thread.startVirtualThread(() -> copy(b, a));
        try {
            while (t1.isAlive() && t2.isAlive()) {
                t1.join(50);
                if (t1.isAlive()) t2.join(50);
            }
        } finally {
            closeQuietly(a);
            closeQuietly(b);
            t1.join();
            t2.join();
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────

    private record Head(byte[] buf, int count) {
    }

    /** 读到空行为止（至多 64 KiB）；读不全 ⇒ {@code null}。 */
    private static Head readHead(InputStream in) throws IOException {
        int max = 64 * 1024;
        byte[] buf = new byte[4096];
        int cnt = 0;
        int search = 0;
        while (cnt < max) {
            int n = in.read(buf, cnt, buf.length - cnt);
            if (n <= 0) return null;
            cnt += n;
            for (int i = Math.max(search, 3); i < cnt; i++)
                if (buf[i - 3] == 13 && buf[i - 2] == 10 && buf[i - 1] == 13 && buf[i] == 10) return new Head(buf, cnt);
            if (cnt == buf.length) buf = Arrays.copyOf(buf, Math.min(2 * buf.length, max));
            search = Math.max(0, cnt - 3);
        }
        return null;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(String.valueOf(s).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** System.Version.TryParse：两到四段非负整数 ⇒ {major, minor}。 */
    private static int[] parseVersion(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length < 2 || parts.length > 4) return null;
        int[] v = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Integer x = parseInt(parts[i]);
            if (x == null || x < 0) return null;
            v[i] = x;
        }
        return new int[] {v[0], v[1]};
    }

    private record Request(String method, String target, URI uri, int major, int minor, List<String[]> headers, Integer contentLength,
                           int headLength) {
    }

    private static Request parseHead(byte[] bs, int cnt) {
        String text = new String(bs, 0, cnt, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\r\n\r\n");
        if (end < 0) return null;
        String[] lines = text.substring(0, end).split("\r\n", -1);
        String[] rl = lines[0].split(" ", -1);
        if (rl.length != 3) return null;
        int[] ver = parseVersion(rl[2].replace("HTTP/", ""));
        if (ver == null) return null;
        String method = rl[0], target = rl[1];
        List<String[]> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) return null;
            String n = line.substring(0, colon).trim(), v = line.substring(colon + 1).trim();
            if (n.equalsIgnoreCase("Transfer-Encoding")) return null; // 不支持分块请求体
            headers.add(new String[] {n, v});
        }
        Integer contentLength = null;
        for (int i = headers.size() - 1; i >= 0; i--) {
            if (headers.get(i)[0].equalsIgnoreCase("Content-Length")) {
                contentLength = parseInt(headers.get(i)[1]);
                break;
            }
        }
        boolean connect = method.equalsIgnoreCase("CONNECT");
        URI uri = null;
        if (!connect) {
            try {
                URI u = new URI(target);
                if (u.isAbsolute() && "http".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty()) uri = u;
            } catch (URISyntaxException e) {
                return null;
            }
            if (uri == null) return null;
        }
        return new Request(method, target, uri, ver[0], ver[1], headers, contentLength, end + 4);
    }

    private record Authority(String host, int port) {
    }

    /** CONNECT 目标 host:port（缺省 443）；不合法 ⇒ {@code null}。 */
    private static Authority parseAuthority(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        String h;
        Integer port;
        if (v.startsWith("[")) {
            int close = v.indexOf(']');
            if (close < 0 || close + 1 >= v.length() || v.charAt(close + 1) != ':') return null;
            port = parseInt(v.substring(close + 2));
            h = v.substring(1, close);
        } else {
            int colon = v.lastIndexOf(':');
            if (colon >= 0) {
                port = parseInt(v.substring(colon + 1));
                h = v.substring(0, colon);
            } else {
                port = 443;
                h = v;
            }
        }
        if (port == null || h.isEmpty() || port < 1 || port > 65535) return null;
        return new Authority(h, port);
    }

    private static final Set<String> HOP_HEADERS = Set.of("connection", "proxy-connection", "proxy-authorization");

    private static byte[] buildHead(Request r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.method()).append(' ').append(r.target()).append(" HTTP/").append(r.major()).append('.').append(r.minor()).append("\r\n");
        for (String[] h : r.headers())
            if (!HOP_HEADERS.contains(h[0].toLowerCase(Locale.ROOT))) sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] readBody(InputStream in, Head head, Request r) throws IOException {
        if (r.contentLength() == null) return null;
        long length = r.contentLength();
        if (length < 0 || length > 64L * 1024 * 1024) throw new IOException("HTTP body too large");
        byte[] body = new byte[(int) length];
        int available = (int) Math.min(length, head.count() - r.headLength());
        System.arraycopy(head.buf(), r.headLength(), body, 0, available);
        int read = available;
        while (read < length) {
            int n = in.read(body, read, (int) (length - read));
            if (n <= 0) throw new IOException("HTTP client closed");
            read += n;
        }
        return body;
    }

    private static String unbracket(String h) {
        return h.length() > 2 && h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }

    private void handleHttp(Socket client) throws IOException, InterruptedException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        Head head = readHead(in);
        Request req = head == null ? null : parseHead(head.buf(), head.count());
        if (req == null) {
            writeStatus(out, 400);
            return;
        }
        if (req.method().equalsIgnoreCase("CONNECT")) {
            Authority a = parseAuthority(req.target());
            if (a == null) {
                writeStatus(out, 400);
            } else if (!authorize(ProxyProtocol.HTTPS_CONNECT, a.host(), null)) {
                writeStatus(out, 403);
            } else {
                Socket up = connectUpstream(a.host(), a.port());
                try {
                    writeStatus(out, 200);
                    relay(client, up);
                } finally {
                    sockets.remove(up);
                    closeQuietly(up);
                }
            }
            return;
        }
        URI uri = req.uri();
        if (!authorize(ProxyProtocol.HTTP, uri.getHost(), req.method())) {
            writeStatus(out, 403);
            return;
        }
        byte[] body = readBody(in, head, req);
        Socket up = connectUpstream(unbracket(uri.getHost()), uri.getPort() >= 0 ? uri.getPort() : 80);
        try {
            OutputStream uo = up.getOutputStream();
            uo.write(buildHead(req));
            if (body != null) uo.write(body);
            uo.flush();
            up.getInputStream().transferTo(out);
            out.flush();
        } finally {
            sockets.remove(up);
            closeQuietly(up);
        }
    }

    // ── SOCKS5 ───────────────────────────────────────────────────────

    private static void socksReply(OutputStream out, int code) throws IOException {
        out.write(new byte[] {5, (byte) code, 0, 1, 127, 0, 0, 1, 0, 0});
        out.flush();
    }

    private void handleSocks(Socket client) throws IOException, InterruptedException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        byte[] greeting = readExact(in, 2);
        if (greeting[0] != 5) {
            out.write(new byte[] {5, -1});
            return;
        }
        byte[] methods = readExact(in, greeting[1] & 0xFF);
        boolean noAuth = false;
        for (byte m : methods) if (m == 0) noAuth = true;
        if (!noAuth) {
            out.write(new byte[] {5, -1});
            return;
        }
        out.write(new byte[] {5, 0});
        byte[] req = readExact(in, 4);
        if (req[0] != 5 || req[1] != 1) {
            socksReply(out, 7);
            return;
        }
        String host = switch (req[3]) {
            case 1 -> HostLiterals.format(InetAddress.getByAddress(readExact(in, 4)));
            case 3 -> new String(readExact(in, readExact(in, 1)[0] & 0xFF), StandardCharsets.UTF_8);
            case 4 -> HostLiterals.format(Inet6Address.getByAddress(null, readExact(in, 16), null));
            default -> null;
        };
        if (host == null) {
            socksReply(out, 8);
            return;
        }
        byte[] pb = readExact(in, 2);
        int port = ((pb[0] & 0xFF) << 8) | (pb[1] & 0xFF);
        if (!authorize(ProxyProtocol.SOCKS5_TCP, host, null)) {
            socksReply(out, 2);
            return;
        }
        Socket up = connectUpstream(host, port);
        try {
            socksReply(out, 0);
            relay(client, up);
        } finally {
            sockets.remove(up);
            closeQuietly(up);
        }
    }

    // ── 监听 ─────────────────────────────────────────────────────────

    private interface Handler {
        void handle(Socket client) throws IOException, InterruptedException;
    }

    private boolean authorized(Socket client) {
        var f = options.connectionAuthorizer();
        if (f == null) return true;
        SocketAddress src = client.getRemoteSocketAddress(), dst = client.getLocalSocketAddress();
        if (!(src instanceof InetSocketAddress s) || !(dst instanceof InetSocketAddress d)) return false;
        try {
            return f.test(new ProxyOptions.ConnectionInfo(s, d));
        } catch (Throwable t) {
            return false;
        }
    }

    private void runClient(Socket client, Handler handler) {
        sockets.add(client);
        try {
            client.setTcpNoDelay(true);
            handler.handle(client);
        } catch (IOException | SandboxException ignored) {
            // 断开即止
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sockets.remove(client);
            closeQuietly(client);
        }
    }

    private void acceptLoop(ServerSocket listener, Handler handler) {
        while (!stopping) {
            Socket client;
            try {
                client = listener.accept();
            } catch (IOException e) {
                return;
            }
            if (authorized(client)) spawn("proxy-client", () -> runClient(client, handler));
            else closeQuietly(client);
        }
    }
}
