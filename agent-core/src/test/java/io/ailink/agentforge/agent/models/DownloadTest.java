package io.ailink.agentforge.agent.models;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DownloadTest {
    static final String API = "{\"demo\":{\"id\":\"demo\",\"name\":\"Demo\",\"models\":{\"m\":{\"id\":\"m\",\"tool_call\":true,\"limit\":{\"context\":1000}}}}}";

    HttpServer server;
    String base;
    final AtomicInteger hits = new AtomicInteger();
    @TempDir Path dir;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // gzip：要了 gzip 才给压缩的
        server.createContext("/gz", ex -> {
            byte[] raw = API.getBytes(StandardCharsets.UTF_8);
            boolean gz = String.valueOf(ex.getRequestHeaders().getFirst("Accept-Encoding")).contains("gzip");
            byte[] body = raw;
            if (gz) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (GZIPOutputStream g = new GZIPOutputStream(bo)) { g.write(raw); }
                body = bo.toByteArray();
                ex.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
        // 前两次 503，第三次才成
        server.createContext("/flaky", ex -> {
            if (hits.incrementAndGet() < 3) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            byte[] b = API.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.createContext("/404", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(404, -1); ex.close(); });
        server.createContext("/html", ex -> {
            byte[] b = "<html>502</html>".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void gzipIsRequestedAndDecodedWithProgress() throws Exception {
        AtomicLong seen = new AtomicLong();
        byte[] b = Download.get(base + "/gz", seen::set);
        assertEquals(API, new String(b, StandardCharsets.UTF_8));
        assertTrue(seen.get() > 0 && seen.get() < API.length(), "进度按线上（压缩后）的字节数报");
    }

    @Test
    void syncRetriesTransientFailuresAndReportsReasons() {
        Catalog c = new Catalog(dir.resolve("models.json"));
        Catalog.SyncResult ok = c.sync(base + "/flaky", null);
        assertTrue(ok.ok(), String.valueOf(ok.error()));
        assertEquals(3, ok.attempts());
        assertTrue(c.providerIds().contains("demo"));

        hits.set(0);
        Catalog.SyncResult nf = c.sync(base + "/404", null);
        assertFalse(nf.ok());
        assertTrue(nf.error().contains("HTTP 404"), nf.error());
        assertEquals(1, hits.get(), "4xx 不重试");

        Catalog.SyncResult html = c.sync(base + "/html", null);
        assertFalse(html.ok());
        assertTrue(html.error().contains("不是"), html.error());
        assertTrue(c.providerIds().contains("demo"), "旧目录还在");

        Catalog.SyncResult down = c.sync("http://127.0.0.1:1/api.json", null);
        assertFalse(down.ok());
        assertTrue(down.error().contains("连不上") || down.error().contains("失败"), down.error());
    }

    @Test
    void proxyFromEnvironmentIsHonouredAndNoProxyExcludes() {
        java.util.Map<String, String> env = new java.util.HashMap<>(java.util.Map.of("HTTPS_PROXY", "http://proxy.example:3128", "NO_PROXY", "localhost,.internal"));
        EnvVars.override(env::get);
        try {
            var sel = Download.EnvProxy.selector();
            assertEquals("proxy.example:3128", Download.EnvProxy.describe());
            assertEquals(java.net.Proxy.Type.HTTP, sel.select(java.net.URI.create("https://models.dev/api.json")).getFirst().type());
            assertEquals(java.net.Proxy.NO_PROXY, sel.select(java.net.URI.create("http://localhost:8787/")).getFirst());
            assertEquals(java.net.Proxy.NO_PROXY, sel.select(java.net.URI.create("http://a.internal/")).getFirst());
        } finally {
            EnvVars.reset();
        }
    }

    @Test
    void writesAtomicallyNextToTheCache() throws Exception {
        Catalog c = new Catalog(dir.resolve("models.json"));
        assertTrue(c.sync(base + "/gz", null).ok());
        assertTrue(Files.readString(dir.resolve("models.json")).contains("\"demo\""));
        assertFalse(Files.exists(dir.resolve("models.json.tmp")));
    }
}
