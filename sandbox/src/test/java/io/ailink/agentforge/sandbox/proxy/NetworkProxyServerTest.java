package io.ailink.agentforge.sandbox.proxy;

import static io.ailink.agentforge.sandbox.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 回环代理：客户端用 JDK HttpClient（代理设到本地代理）与裸 SOCKS5 握手。 */
public class NetworkProxyServerTest {

    static byte[] readExact(InputStream in, int n) throws IOException {
        byte[] r = new byte[n];
        int read = 0;
        while (read < n) {
            int k = in.read(r, read, n - read);
            if (k <= 0) throw new IOException("closed");
            read += k;
        }
        return r;
    }

    // ── 回环上游：一次 accept 回一段 HTTP 响应 / 回显 ──

    public static ServerSocket listener() throws IOException {
        ServerSocket s = new ServerSocket();
        s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 2);
        return s;
    }

    private static byte[] readUntilDoubleCrlf(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        int cnt = 0;
        while (cnt < buf.length) {
            int n = in.read(buf, cnt, buf.length - cnt);
            if (n <= 0) break;
            cnt += n;
            if (new String(buf, 0, cnt, StandardCharsets.UTF_8).contains("\r\n\r\n")) break;
        }
        return Arrays.copyOf(buf, cnt);
    }

    /** 后台 accept 一次、回 body；future 的值是收到的请求头（文本）。 */
    public static CompletableFuture<String> serveHttp(ServerSocket l, String body) {
        return CompletableFuture.supplyAsync(() -> {
            try (Socket c = l.accept()) {
                byte[] req = readUntilDoubleCrlf(c.getInputStream());
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n" + body)
                        .getBytes(StandardCharsets.UTF_8));
                c.getOutputStream().flush();
                return new String(req, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static CompletableFuture<Void> serveEcho(ServerSocket l) {
        return CompletableFuture.runAsync(() -> {
            try (Socket c = l.accept()) {
                byte[] buf = new byte[512];
                int n = c.getInputStream().read(buf);
                c.getOutputStream().write(buf, 0, n);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static HttpClient client(URI endpoint) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(new InetSocketAddress(endpoint.getHost(), endpoint.getPort())))
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private static HttpResponse<String> get(HttpClient h, String uri) throws Exception {
        return h.send(HttpRequest.newBuilder(URI.create(uri)).header("X-Upstream", "yes").timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static NetworkProxyServer startProxy(List<String> allowed, HostPolicy.Mode mode) throws IOException {
        return new NetworkProxyServer(ProxyOptions.of(new HostPolicy(allowed, List.of(), mode, HostPolicy.Fallback.DENY)).withConnectTimeoutMs(2000)).start();
    }

    @Test
    void allowedHttpHostIsForwardedWithoutMitm() throws Exception {
        try (ServerSocket up = listener(); NetworkProxyServer p = startProxy(List.of("127.0.0.1"), HostPolicy.Mode.FULL);
             HttpClient h = client(p.httpEndpoint())) {
            CompletableFuture<String> task = serveHttp(up, "hello proxy");
            HttpResponse<String> r = get(h, "http://127.0.0.1:" + up.getLocalPort() + "/path");
            assertEquals(200, r.statusCode());
            assertEquals("hello proxy", r.body());
            String req = task.get(5, TimeUnit.SECONDS);
            String lower = req.toLowerCase(Locale.ROOT);
            assertTrue(req.startsWith("GET http://127.0.0.1:" + up.getLocalPort() + "/path HTTP/"), req);
            assertTrue(lower.contains("x-upstream: yes"), req);
            assertFalse(lower.contains("proxy-authorization"), req);
        }
    }

    @Test
    void deniedHttpHostGets403BeforeDnsOrConnection() throws Exception {
        try (NetworkProxyServer p = startProxy(List.of("allowed.invalid"), HostPolicy.Mode.FULL); HttpClient h = client(p.httpEndpoint())) {
            HttpResponse<String> r = get(h, "http://denied.invalid/path");
            assertEquals(403, r.statusCode());
            assertEquals("", r.body());
        }
    }

    @Test
    void limitedModeRejectsHttpPost() throws Exception {
        try (NetworkProxyServer p = startProxy(List.of("*"), HostPolicy.Mode.LIMITED); HttpClient h = client(p.httpEndpoint())) {
            HttpResponse<String> r = h.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/")).header("X-Upstream", "yes")
                    .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, r.statusCode());
        }
    }

    @Test
    void socks5TcpConnectHonorsHostPolicy() throws Exception {
        try (ServerSocket up = listener(); NetworkProxyServer p = startProxy(List.of("127.0.0.1"), HostPolicy.Mode.FULL);
             Socket c = new Socket(p.socksEndpoint().getHost(), p.socksEndpoint().getPort())) {
            CompletableFuture<Void> echo = serveEcho(up);
            var out = c.getOutputStream();
            var in = c.getInputStream();
            int pt = up.getLocalPort();
            out.write(new byte[] {5, 1, 0});
            assertArrayEquals(new byte[] {5, 0}, readExact(in, 2));
            out.write(new byte[] {5, 1, 0, 1, 127, 0, 0, 1, (byte) (pt >> 8), (byte) pt});
            assertEquals(0, readExact(in, 10)[1]);
            byte[] payload = "socks".getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            assertArrayEquals(payload, readExact(in, payload.length));
            echo.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void socks5DeniedHostGetsRuleFailure() throws Exception {
        try (NetworkProxyServer p = startProxy(List.of("allowed.invalid"), HostPolicy.Mode.FULL);
             Socket c = new Socket(p.socksEndpoint().getHost(), p.socksEndpoint().getPort())) {
            var out = c.getOutputStream();
            var in = c.getInputStream();
            out.write(new byte[] {5, 1, 0});
            readExact(in, 2);
            byte[] host = "denied.invalid".getBytes(StandardCharsets.US_ASCII);
            byte[] req = new byte[5 + host.length + 2];
            req[0] = 5;
            req[1] = 1;
            req[3] = 3;
            req[4] = (byte) host.length;
            System.arraycopy(host, 0, req, 5, host.length);
            req[req.length - 1] = 80;
            out.write(req);
            assertEquals(2, readExact(in, 10)[1]);
        }
    }

    @Test
    void askApprovalIsCachedByHostAndProtocol() throws Exception {
        FakeApprover approver = new FakeApprover(ApprovalDecision.APPROVE_FOR_SESSION);
        ApprovalCache.InMemory cache = ApprovalCache.inMemory();
        ProxyOptions opts = ProxyOptions.of(new HostPolicy(List.of(), List.of(), HostPolicy.Mode.FULL, HostPolicy.Fallback.ASK))
                .withApprover(approver, cache).withConnectTimeoutMs(2000);
        try (ServerSocket up = listener(); NetworkProxyServer p = new NetworkProxyServer(opts).start(); HttpClient h = client(p.httpEndpoint())) {
            CompletableFuture<String> first = serveHttp(up, "first");
            assertEquals("first", get(h, "http://127.0.0.1:" + up.getLocalPort() + "/one").body());
            first.get(5, TimeUnit.SECONDS);
            CompletableFuture<String> second = serveHttp(up, "second");
            assertEquals("second", get(h, "http://127.0.0.1:" + up.getLocalPort() + "/two").body());
            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, approver.requests.size());
            assertEquals("network:http:127.0.0.1", approver.requests.getFirst().key());
            assertEquals(Set.of("network:http:127.0.0.1"), cache.keys());
        }
    }

    @Test
    void listenersAreLoopbackAndDoubleStartIsRejected() throws Exception {
        try (NetworkProxyServer p = startProxy(List.of("127.0.0.1"), HostPolicy.Mode.FULL)) {
            assertTrue(InetAddress.getByName(p.httpEndpoint().getHost()).isLoopbackAddress());
            assertEquals("127.0.0.1", p.httpEndpoint().getHost());
            assertEquals("127.0.0.1", p.socksEndpoint().getHost());
            Throwable ex = thrown(p::start);
            assertEquals("sandbox/network-proxy-already-started", code(ex));
            assertEquals(ErrorKind.INVALID_OPERATION, kind(ex));
        }
    }

    @Test
    void connectionAuthorizerSeesEndpointsBeforeHttpPolicy() throws Exception {
        AtomicReference<ProxyOptions.ConnectionInfo> seen = new AtomicReference<>();
        ProxyOptions opts = ProxyOptions.of(HostPolicy.allow("127.0.0.1")).withConnectTimeoutMs(2000).withConnectionAuthorizer(info -> {
            seen.set(info);
            return true;
        });
        try (ServerSocket up = listener(); NetworkProxyServer p = new NetworkProxyServer(opts).start(); HttpClient h = client(p.httpEndpoint())) {
            CompletableFuture<String> task = serveHttp(up, "authorized");
            assertEquals("authorized", get(h, "http://127.0.0.1:" + up.getLocalPort() + "/path").body());
            ProxyOptions.ConnectionInfo info = seen.get();
            assertEquals(InetAddress.getByName("127.0.0.1"), info.source().getAddress());
            assertTrue(info.source().getPort() > 0);
            assertEquals(p.httpEndpoint().getPort(), info.destination().getPort());
            assertTrue(task.get(5, TimeUnit.SECONDS).startsWith("GET http://127.0.0.1:"));
        }
    }

    @Test
    void rejectedConnectionClosesBeforeHttpOrUpstream() throws Exception {
        ProxyOptions opts = ProxyOptions.of(HostPolicy.allow("127.0.0.1")).withConnectTimeoutMs(2000).withConnectionAuthorizer(info -> false);
        try (NetworkProxyServer p = new NetworkProxyServer(opts).start(); Socket c = new Socket(p.httpEndpoint().getHost(), p.httpEndpoint().getPort())) {
            c.getOutputStream().write("GET http://127.0.0.1:9/ HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertInstanceOf(IOException.class, thrown(() -> readExact(c.getInputStream(), 1)));
        }
    }

    @Test
    void badTimeoutIsRejected() {
        assertEquals(ErrorKind.ARGUMENT_OUT_OF_RANGE, kind(thrown(() -> new NetworkProxyServer(ProxyOptions.DEFAULT.withConnectTimeoutMs(0)))));
    }
}
