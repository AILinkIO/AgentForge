package io.ailink.agentforge.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * 远程服务器的两种 HTTP 传输。
 *
 * <ul>
 *   <li>{@link Streamable}（2025-03-26 起）：每条消息一个 POST，应答在回包里（JSON 或 SSE 流）；
 *       {@code Mcp-Session-Id} 从 initialize 的回包头里拿，之后每次都带上。</li>
 *   <li>{@link LegacySse}（2024-11-05）：先 GET 一条 SSE 流，第一条 {@code endpoint} 事件给出 POST 地址；
 *       应答从那条流回来。Streamable 的 initialize 回 400/404/405 时由 {@link McpClient} 回落到这条。</li>
 * </ul>
 *
 * <p>401/403 ⇒ {@link McpException.HttpStatus#isAuth()}，状态归 NEEDS_AUTH（下一步是设 token，不是重连）。
 */
final class HttpTransport {
    private HttpTransport() { }

    static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    static String snippet(String body) {
        if (body == null) return "";
        String s = body.strip();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    static String statusLine(int status, String body) {
        String b = snippet(body);
        String auth = status == 401 ? " Unauthorized" : status == 403 ? " Forbidden" : "";
        return "HTTP " + status + auth + (b.isEmpty() ? "" : "：" + b);
    }

    /** SSE 解析：{@code event:} / {@code data:}（多行拼接）/ 空行分发。 */
    static void readSse(InputStream in, BiConsumer<String, String> onEvent) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data != null) onEvent.accept(event == null ? "message" : event, data.toString());
                    event = null;
                    data = null;
                    continue;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> {
                        if (data == null) data = new StringBuilder(); else data.append('\n');
                        data.append(value);
                    }
                    default -> { }
                }
            }
            if (data != null) onEvent.accept(event == null ? "message" : event, data.toString());
        }
    }

    // ═══ Streamable HTTP ═══════════════════════════════════════════════

    static final class Streamable extends RpcTransport {
        private final HttpClient http;
        private final URI uri;
        private final Map<String, String> headers;
        private volatile String sessionId;
        private volatile String protocolVersion;

        Streamable(HttpClient http, URI uri, Map<String, String> headers) {
            this.http = http;
            this.uri = uri;
            this.headers = headers;
        }

        @Override
        void protocolVersion(String v) { this.protocolVersion = v; }

        @Override
        protected void send(ObjectNode msg) {
            if (isClosed()) throw new McpException.Closed(closedReason());
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(msg)));
            headers.forEach(b::header);
            if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
            if (protocolVersion != null) b.header("MCP-Protocol-Version", protocolVersion);
            HttpRequest req = b.build();
            JsonNode id = msg.get("id");
            boolean isRequest = id != null && msg.has("method");
            // 每条消息一条虚拟线程：回包可能是一条长 SSE 流，不能卡住调用方。
            Thread.ofVirtual().name("mcp-http").start(() -> exchange(req, isRequest ? id.asLong() : -1));
        }

        private void exchange(HttpRequest req, long id) {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                resp.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
                int st = resp.statusCode();
                try (InputStream body = resp.body()) {
                    if (st == 202 || st == 204) return;
                    if (st / 100 != 2) {
                        String text = new String(body.readNBytes(4096), StandardCharsets.UTF_8);
                        if (id >= 0) failPending(id, new McpException.HttpStatus(st, statusLine(st, text)));
                        return;
                    }
                    String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
                    if (ct.contains("text/event-stream")) {
                        readSse(body, (ev, data) -> {
                            if (!"message".equals(ev) || data.isBlank()) return;
                            try { dispatch(JSON.readTree(data)); } catch (RuntimeException ignored) { }
                        });
                    } else {
                        String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                        if (!text.isBlank()) dispatch(JSON.readTree(text));
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (id >= 0) failPending(id, new McpException("HTTP 请求失败：" + describe(e), e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            markClosed("连接已关闭");
            String sid = sessionId;
            if (sid != null) {
                // 礼貌地结束会话；失败无所谓
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(uri).DELETE().header("Mcp-Session-Id", sid)
                            .timeout(Duration.ofSeconds(2));
                    headers.forEach(b::header);
                    http.sendAsync(b.build(), HttpResponse.BodyHandlers.discarding());
                } catch (RuntimeException ignored) { }
            }
        }
    }

    // ═══ legacy HTTP+SSE ═══════════════════════════════════════════════

    static final class LegacySse extends RpcTransport {
        private final HttpClient http;
        private final Map<String, String> headers;
        private volatile URI postUri;
        private volatile Thread reader;

        private LegacySse(HttpClient http, Map<String, String> headers) {
            this.http = http;
            this.headers = headers;
        }

        /** 开 SSE 流、等第一条 endpoint 事件。 */
        static LegacySse open(HttpClient http, URI uri, Map<String, String> headers, long timeoutMs) {
            LegacySse t = new LegacySse(http, headers);
            CountDownLatch ready = new CountDownLatch(1);
            RuntimeException[] failure = new RuntimeException[1];
            t.reader = Thread.ofVirtual().name("mcp-sse").start(() -> {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(uri).GET().header("Accept", "text/event-stream");
                    headers.forEach(b::header);
                    HttpResponse<InputStream> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() / 100 != 2) {
                        String text = new String(resp.body().readNBytes(4096), StandardCharsets.UTF_8);
                        resp.body().close();
                        failure[0] = new McpException.HttpStatus(resp.statusCode(), statusLine(resp.statusCode(), text));
                        ready.countDown();
                        return;
                    }
                    readSse(resp.body(), (ev, data) -> {
                        if ("endpoint".equals(ev)) {
                            t.postUri = uri.resolve(data.strip());
                            ready.countDown();
                        } else if ("message".equals(ev) && !data.isBlank()) {
                            try { t.dispatch(JSON.readTree(data)); } catch (RuntimeException ignored) { }
                        }
                    });
                    t.markClosed("SSE 流断了");
                } catch (IOException | RuntimeException e) {
                    failure[0] = new McpException("SSE 连接失败：" + describe(e), e);
                    t.markClosed("SSE 流断了：" + describe(e));
                } catch (InterruptedException e) {
                    t.markClosed("连接已关闭");
                } finally {
                    ready.countDown();
                }
            });
            try {
                if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    t.close();
                    throw new McpException.Timeout("SSE 流 " + timeoutMs + " ms 内没给出 endpoint");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                t.close();
                throw new McpException.Closed("被打断");
            }
            if (t.postUri == null) {
                t.close();
                throw failure[0] != null ? failure[0] : new McpException("SSE 流没给出 endpoint 就断了");
            }
            return t;
        }

        @Override
        protected void send(ObjectNode msg) {
            if (isClosed()) throw new McpException.Closed(closedReason());
            HttpRequest.Builder b = HttpRequest.newBuilder(postUri).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(msg)));
            headers.forEach(b::header);
            JsonNode id = msg.get("id");
            long rid = id != null && msg.has("method") ? id.asLong() : -1;
            Thread.ofVirtual().name("mcp-sse-post").start(() -> {
                try {
                    HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() / 100 != 2 && rid >= 0) {
                        failPending(rid, new McpException.HttpStatus(resp.statusCode(),
                                statusLine(resp.statusCode(), resp.body())));
                    }
                } catch (IOException | RuntimeException e) {
                    if (rid >= 0) failPending(rid, new McpException("HTTP 请求失败：" + describe(e), e));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        @Override
        public void close() {
            markClosed("连接已关闭");
            Thread r = reader;
            if (r != null) r.interrupt();
        }
    }

    static String describe(Throwable e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : "：" + m);
    }
}
