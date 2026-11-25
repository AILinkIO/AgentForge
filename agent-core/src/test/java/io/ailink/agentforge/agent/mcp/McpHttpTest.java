package io.ailink.agentforge.agent.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 远程传输：Streamable HTTP（JSON 与 SSE 两种回包、会话头）、401 ⇒ NEEDS_AUTH ⇒ 设 token 后连得上、
 * legacy HTTP+SSE 回落。服务端是进程内的回环 HttpServer（不出网）。
 */
class McpHttpTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path tmp;
    HttpServer server;
    ExecutorService pool;
    McpSpecs specs;
    McpManager mgr;
    final List<String> seenSession = new CopyOnWriteArrayList<>();
    final List<String> seenVersion = new CopyOnWriteArrayList<>();
    volatile OutputStream legacyStream;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/mcp", ex -> streamable(ex, false));
        server.createContext("/secure", ex -> streamable(ex, true));
        server.createContext("/legacy", this::legacy);
        server.start();
        specs = new McpSpecs();
        mgr = new McpManager(specs, new McpState(tmp.resolve("mcp.json")), McpTokens.inMemory());
    }

    @AfterEach
    void tearDown() {
        mgr.shutdown();
        server.stop(0);
        pool.shutdownNow();
    }

    String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    McpServerInfo settle(String name) {
        assertTrue(mgr.awaitSettled(Duration.ofSeconds(20)));
        return mgr.server(name).orElseThrow();
    }

    // ── 假服务端 ─────────────────────────────────────────────────────────

    static JsonNode answer(JsonNode msg) {
        String method = msg.path("method").asString("");
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", msg.get("id"));
        ObjectNode res = r.putObject("result");
        switch (method) {
            case "initialize" -> {
                res.put("protocolVersion", "2025-03-26");
                res.putObject("capabilities").putObject("tools");
                res.putObject("serverInfo").put("name", "http-fake").put("version", "1");
            }
            case "tools/list" -> {
                ObjectNode t = res.putArray("tools").addObject();
                t.put("name", "hello");
                t.put("description", "打招呼");
                t.putObject("inputSchema").put("type", "object").putObject("properties");
                t.putObject("annotations").put("readOnlyHint", true);
            }
            case "tools/call" -> res.putArray("content").addObject().put("type", "text").put("text", "hi from http");
            default -> { }
        }
        return r;
    }

    void streamable(HttpExchange ex, boolean secure) throws IOException {
        try (ex) {
            if (!ex.getRequestMethod().equals("POST")) {
                ex.sendResponseHeaders(ex.getRequestMethod().equals("DELETE") ? 200 : 405, -1);
                return;
            }
            if (secure && !"Bearer sk-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                byte[] b = "{\"error\":\"unauthorized\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(401, b.length);
                ex.getResponseBody().write(b);
                return;
            }
            JsonNode msg = JSON.readTree(ex.getRequestBody().readAllBytes());
            if (!msg.has("id")) {
                ex.sendResponseHeaders(202, -1);
                return;
            }
            String method = msg.path("method").asString("");
            if (!method.equals("initialize")) {
                seenSession.add(String.valueOf(ex.getRequestHeaders().getFirst("Mcp-Session-Id")));
                seenVersion.add(String.valueOf(ex.getRequestHeaders().getFirst("MCP-Protocol-Version")));
            } else {
                ex.getResponseHeaders().add("Mcp-Session-Id", "sess-42");
            }
            byte[] body;
            if (method.equals("tools/list")) {
                // 用 SSE 回：客户端两种回包都要认得
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                body = (": keepalive\n\nevent: message\ndata: " + answer(msg) + "\n\n").getBytes(StandardCharsets.UTF_8);
            } else {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                body = answer(msg).toString().getBytes(StandardCharsets.UTF_8);
            }
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        }
    }

    void legacy(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (ex.getRequestMethod().equals("GET") && path.equals("/legacy")) {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            synchronized (this) {
                os.write("event: endpoint\ndata: /legacy/messages?sid=1\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            legacyStream = os;
            return;   // 流保持打开
        }
        try (ex) {
            if (ex.getRequestMethod().equals("POST") && path.equals("/legacy/messages")) {
                JsonNode msg = JSON.readTree(ex.getRequestBody().readAllBytes());
                ex.sendResponseHeaders(202, -1);
                if (msg.has("id") && legacyStream != null) {
                    synchronized (this) {
                        legacyStream.write(("event: message\ndata: " + answer(msg) + "\n\n").getBytes(StandardCharsets.UTF_8));
                        legacyStream.flush();
                    }
                }
                return;
            }
            ex.sendResponseHeaders(405, -1);   // Streamable 的 POST 不认 ⇒ 客户端回落
        }
    }

    ToolOutput invoke(String name) {
        return mgr.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow()
                .execute(ToolEnv.of(new Workspace(tmp)), JsonNodeFactory.instance.objectNode());
    }

    // ── 判据 ─────────────────────────────────────────────────────────────

    @Test
    void streamableHttp() {
        specs.define("web", Map.of("url", url("/mcp"), "timeout-ms", 5000L));
        mgr.start();
        McpServerInfo s = settle("web");
        assertEquals(McpStatus.CONNECTED, s.status(), s.error());
        assertEquals(List.of("web_hello"), s.tools().stream().map(t -> t.name()).toList());
        assertTrue(s.tools().get(0).readOnly());
        assertEquals("hi from http", invoke("web_hello").text());
        assertTrue(seenSession.stream().allMatch("sess-42"::equals), seenSession.toString());
        assertTrue(seenVersion.stream().allMatch("2025-03-26"::equals), seenVersion.toString());
    }

    @Test
    void unauthorizedIsNeedsAuthUntilTokenSet() {
        specs.define("sec", Map.of("url", url("/secure"), "timeout-ms", 5000L));
        mgr.start();
        McpServerInfo s = settle("sec");
        assertEquals(McpStatus.NEEDS_AUTH, s.status());
        assertTrue(s.error().contains("401"), s.error());
        assertEquals(new McpManager.Counts(0, 1, true), mgr.counts());
        mgr.setToken("sec", "sk-1");
        mgr.connectAll();   // needs-auth 也重试
        assertEquals(McpStatus.CONNECTED, settle("sec").status());
        assertEquals("hi from http", invoke("sec_hello").text());
    }

    @Test
    void configuredHeaderWinsOverStoredToken() {
        specs.define("sec", Map.of("url", url("/secure"), "timeout-ms", 5000L,
                "headers", Map.of("authorization", "Bearer ${AF_TEST_TOK}")));
        mgr.setEnvLookup(Map.of("AF_TEST_TOK", "sk-1")::get);
        mgr.setToken("sec", "wrong");
        mgr.start();
        assertEquals(McpStatus.CONNECTED, settle("sec").status(), "配置里写了同名头（不分大小写）就不被 token 盖掉");
    }

    @Test
    void missingEnvVarIsSaidSeparately() {
        specs.define("e", Map.of("url", url("/mcp"), "headers", Map.of("X-Key", "${AF_SURELY_UNSET_VAR}")));
        mgr.setEnvLookup(k -> null);
        mgr.start();
        McpServerInfo s = settle("e");
        assertEquals(McpStatus.FAILED, s.status());
        assertTrue(s.error().contains("AF_SURELY_UNSET_VAR") && s.error().contains("环境变量没设"), s.error());
    }

    @Test
    void legacySseFallback() {
        specs.define("old", Map.of("url", url("/legacy"), "timeout-ms", 5000L));
        mgr.start();
        McpServerInfo s = settle("old");
        assertEquals(McpStatus.CONNECTED, s.status(), s.error());
        assertEquals("hi from http", invoke("old_hello").text());
    }
}
