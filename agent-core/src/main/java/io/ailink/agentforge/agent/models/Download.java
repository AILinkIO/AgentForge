package io.ailink.agentforge.agent.models;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * 下载一个大文件（models.dev 的 api.json，几 MB）：
 * <ul>
 *   <li>认环境变量里的代理（{@code HTTPS_PROXY} / {@code HTTP_PROXY} / {@code ALL_PROXY}，{@code NO_PROXY} 排除）——
 *       JDK 的 HttpClient 默认不看它们，curl 能下而我们下不了多半就是这个；</li>
 *   <li>要 gzip（models.dev 压完约 1/6）；</li>
 *   <li>不设总时长上限，只看<b>停顿</b>：连续 {@link #STALL} 没收到一个字节才算断（慢网也能下完）；</li>
 *   <li>失败抛 {@link IOException}，消息说得出原因（超时 / HTTP 状态 / 连不上 / 被代理拒）。</li>
 * </ul>
 */
public final class Download {
    static final Duration CONNECT = Duration.ofSeconds(15);
    static final Duration HEADERS = Duration.ofSeconds(60);
    static final Duration STALL = Duration.ofSeconds(60);
    static final long MAX_BYTES = 256L << 20;

    private Download() { }

    public static byte[] get(String url, LongConsumer progress) throws IOException, InterruptedException {
        HttpClient c = HttpClient.newBuilder().connectTimeout(CONNECT).followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(EnvProxy.selector()).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(HEADERS)
                .header("User-Agent", "agentforge").header("Accept-Encoding", "gzip").GET().build();
        HttpResponse<InputStream> r;
        try {
            r = c.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new IOException("连 " + host(url) + " 超时（" + HEADERS.toSeconds() + " 秒没有响应）", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("连不上 " + host(url) + "（连接被拒绝或网络不通）" + proxyHint(), e);
        } catch (java.nio.channels.UnresolvedAddressException e) {
            throw new IOException("解析不了域名 " + host(url) + "（DNS 不通？）" + proxyHint(), e);
        } catch (IOException e) {
            throw new IOException("请求 " + host(url) + " 失败：" + reason(e) + proxyHint(), e);
        }
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException(host(url) + " 回了 HTTP " + r.statusCode());
        }
        boolean gz = r.headers().firstValue("Content-Encoding").map(v -> v.toLowerCase(Locale.ROOT).contains("gzip")).orElse(false);
        long[] got = {0};
        long[] last = {System.nanoTime()};
        InputStream raw = r.body();
        // 停顿看门狗：读阻塞时没人能打断它，只能关流
        Thread dog = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(1000);
                    if (System.nanoTime() - last[0] > STALL.toNanos()) { raw.close(); return; }
                }
            } catch (InterruptedException | IOException ignored) { }
        });
        try (InputStream counted = new InputStream() {
            @Override public int read() throws IOException {
                byte[] b = new byte[1];
                return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff;
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = raw.read(b, off, len);
                if (n > 0) {
                    got[0] += n;
                    last[0] = System.nanoTime();
                    if (progress != null) progress.accept(got[0]);
                }
                return n;
            }

            @Override public void close() throws IOException { raw.close(); }
        }; InputStream in = gz ? new GZIPInputStream(counted) : counted) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 << 10];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("文件大得不正常（超过 " + (MAX_BYTES >> 20) + " MB），停了");
            }
            return out.toByteArray();
        } catch (IOException e) {
            if (System.nanoTime() - last[0] > STALL.toNanos())
                throw new IOException("下载停住了：" + STALL.toSeconds() + " 秒没收到数据（已收 " + (got[0] >> 10) + " KB）", e);
            throw new IOException("下载中断（已收 " + (got[0] >> 10) + " KB）：" + reason(e), e);
        } finally {
            dog.interrupt();
        }
    }

    private static String host(String url) {
        try { return URI.create(url).getHost(); } catch (RuntimeException e) { return url; }
    }

    private static String reason(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isBlank())) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static String proxyHint() {
        return EnvProxy.describe() == null ? "（没用代理；需要代理的话设 HTTPS_PROXY 后重启）" : "（经代理 " + EnvProxy.describe() + "）";
    }

    /** 环境变量里的代理 → ProxySelector（没设就退回 JDK 缺省：系统属性 https.proxyHost 那套）。 */
    static final class EnvProxy {
        private EnvProxy() { }

        static String raw() {
            for (String k : List.of("HTTPS_PROXY", "https_proxy", "ALL_PROXY", "all_proxy", "HTTP_PROXY", "http_proxy")) {
                String v = EnvVars.get(k);
                if (v != null && !v.isBlank()) return v.strip();
            }
            return null;
        }

        static String describe() {
            String r = raw();
            if (r == null) return null;
            URI u = parse(r);
            return u == null ? r : u.getHost() + ":" + port(u);
        }

        static ProxySelector selector() {
            String r = raw();
            URI u = r == null ? null : parse(r);
            if (u == null || u.getHost() == null) return ProxySelector.getDefault();
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(u.getHost(), port(u)));
            List<String> skip = noProxy();
            return new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
                    for (String s : skip) if (s.equals("*") || h.equals(s) || h.endsWith(s.startsWith(".") ? s : "." + s)) return List.of(Proxy.NO_PROXY);
                    return List.of(proxy);
                }

                @Override public void connectFailed(URI uri, SocketAddress sa, IOException ioe) { }
            };
        }

        static List<String> noProxy() {
            String v = EnvVars.get("NO_PROXY");
            if (v == null) v = EnvVars.get("no_proxy");
            List<String> out = new ArrayList<>();
            if (v != null) for (String s : v.split(",")) if (!s.isBlank()) out.add(s.strip().toLowerCase(Locale.ROOT));
            return out;
        }

        private static URI parse(String r) {
            try { return URI.create(r.contains("://") ? r : "http://" + r); } catch (RuntimeException e) { return null; }
        }

        private static int port(URI u) {
            return u.getPort() > 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }
    }
}
