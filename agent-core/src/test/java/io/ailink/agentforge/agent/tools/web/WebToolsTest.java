package io.ailink.agentforge.agent.tools.web;

import com.sun.net.httpserver.HttpServer;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static org.junit.jupiter.api.Assertions.*;

class WebToolsTest {
    @TempDir Path root;
    HttpServer server;
    String base;
    final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        route("/plain.txt", 200, "text/plain", "just text\n".getBytes());
        route("/index.html", 200, "text/html; charset=utf-8",
                "<html><head><title>Hi</title><script>x()</script></head><body><h1>Fetched</h1><p>段落一</p></body></html>".getBytes(StandardCharsets.UTF_8));
        route("/gbk.html", 200, "text/html", "<html><head><meta charset=\"gbk\"></head><body><p>中文</p></body></html>".getBytes("GBK"));
        route("/pic.png", 200, "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
        route("/v.svg", 200, "image/svg+xml", "<svg/>".getBytes());
        route("/missing", 404, "text/plain", "nope here".getBytes());
        route("/big", 200, "text/plain", new byte[WebFetch.MAX_BODY + 10]);
        server.createContext("/mcp", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = ("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"RESULT https://x.y\"}]}}\n\n").getBytes();
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        WebSearch.env = System::getenv;
    }

    private void route(String path, int status, String ct, byte[] body) {
        server.createContext(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", ct);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    // ── 纯函数 ──

    @Test
    void pureFunctions() {
        assertTrue(WebFetch.httpUrl("https://example.com/x"));
        assertTrue(WebFetch.httpUrl("http://127.0.0.1:8080/"));
        assertFalse(WebFetch.httpUrl("ftp://example.com/x"));
        assertFalse(WebFetch.httpUrl("example.com"));
        assertFalse(WebFetch.httpUrl("https://"));
        assertEquals(WebFetch.Format.MARKDOWN, WebFetch.format(""));
        assertEquals(WebFetch.Format.TEXT, WebFetch.format("text"));
        assertEquals(WebFetch.Format.HTML, WebFetch.format("html"));
        assertNull(WebFetch.format("json"));
        assertEquals("text/html", WebFetch.mime("text/html; charset=utf-8"));
        assertEquals("text/html", WebFetch.mime("Text/HTML"));
        assertEquals("", WebFetch.mime(null));
    }

    @Test
    void renderHtml() {
        byte[] html = ("<!doctype html><html><head><title>T</title><style>body{color:red}</style><script>alert(1)</script></head>"
                + "<body><h1>标题</h1><p>第一段 <b>粗</b> 文字 &amp; <a href=\"https://a.b/c\">链接</a>。</p>"
                + "<ul><li>甲</li><li>乙<ul><li>嵌套</li></ul></li></ul><ol><li>一</li><li>二</li></ol>"
                + "<pre>code here\n  indented</pre><blockquote><p>引用</p></blockquote>"
                + "<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table></body></html>").getBytes(StandardCharsets.UTF_8);
        String md = WebFetch.render(html, "text/html", WebFetch.Format.MARKDOWN).text();
        assertTrue(md.contains("# 标题"), md);
        assertTrue(md.contains("- 甲"), md);
        assertTrue(md.contains("  - 嵌套"), md);
        assertTrue(md.contains("1. 一\n2. 二"), md);
        assertTrue(md.contains("**粗**"));
        assertTrue(md.contains("[链接](https://a.b/c)"));
        assertTrue(md.contains("文字 & "));
        assertTrue(md.contains("```\ncode here\n  indented\n```"), md);
        assertTrue(md.contains("> 引用"));
        assertTrue(md.contains("| A | B |\n| --- | --- |\n| 1 | 2 |"), md);
        assertFalse(md.contains("alert"));
        assertFalse(md.contains("color:red"));
        assertEquals("HTML → markdown", WebFetch.render(html, "text/html", WebFetch.Format.MARKDOWN).how());

        String txt = WebFetch.render(html, "text/html", WebFetch.Format.TEXT).text();
        assertTrue(txt.contains("第一段") && txt.contains("乙"));
        assertFalse(txt.contains("<p>"));
        assertFalse(txt.contains("alert"));
        assertFalse((txt + "\n").contains("T\n"), "head 里的 title 没进来");

        assertTrue(WebFetch.render(html, "text/html", WebFetch.Format.HTML).text().contains("<script>"));
        assertEquals("{\"a\":1}", WebFetch.render("{\"a\":1}".getBytes(), "application/json", WebFetch.Format.MARKDOWN).text());
    }

    @Test
    void refusals() {
        ToolOutput a = call(WebFetch.tool(), root, "url", "example.com");
        assertTrue(a.error());
        assertTrue(a.text().contains("http://"));
        ToolOutput b = call(WebFetch.tool(), root, "url", "https://example.com/", "format", "pdf");
        assertTrue(b.text().contains("markdown / text / html"));
        assertTrue(call(WebFetch.tool(), root, "url", "https://example.com/", "timeout", -5).error());
    }

    @Test
    void deadPort() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        ToolOutput o = call(WebFetch.tool(), root, "url", "http://127.0.0.1:" + port + "/", "timeout", 5);
        assertTrue(o.error());
        assertTrue(o.text().contains("127.0.0.1:" + port), o.text());
    }

    @Test
    void fetchLocal() {
        ToolOutput p = call(WebFetch.tool(), root, "url", base + "/plain.txt");
        assertFalse(p.error(), p.text());
        assertTrue(p.text().startsWith("抓到 text/plain，10 字符（原样）"), p.text());
        assertTrue(p.text().contains("just text"));

        ToolOutput h = call(WebFetch.tool(), root, "url", base + "/index.html");
        assertTrue(h.text().contains("text/html") && h.text().contains("markdown"));
        assertTrue(h.text().contains("# Fetched"));
        assertFalse(h.text().contains("x()"));
        assertTrue(call(WebFetch.tool(), root, "url", base + "/index.html", "format", "html").text().contains("<h1>"));
        assertTrue(call(WebFetch.tool(), root, "url", base + "/gbk.html").text().contains("中文"), "meta charset");

        ToolOutput nf = call(WebFetch.tool(), root, "url", base + "/missing");
        assertTrue(nf.error());
        assertTrue(nf.text().contains("HTTP 404") && nf.text().contains("nope here"));

        ToolOutput big = call(WebFetch.tool(), root, "url", base + "/big");
        assertTrue(big.error());
        assertTrue(big.text().contains("超过 5 MB"), big.text());
    }

    @Test
    void fetchImage() {
        List<String> attached = new ArrayList<>();
        ToolEnv env = ToolEnv.of(new Workspace(root)).withService(ToolEnv.Attacher.class, (n, b) -> attached.add(n));
        ToolOutput o = call(WebFetch.tool(), env, "url", base + "/pic.png");
        assertFalse(o.error(), o.text());
        assertEquals(2, o.content().size());
        assertTrue(o.content().get(1) instanceof Block.Image img && img.mediaType().equals("image/png"));
        assertTrue(o.text().contains("抓到图片"));
        assertEquals(List.of(base + "/pic.png"), attached);
        ToolOutput svg = call(WebFetch.tool(), env, "url", base + "/v.svg");
        assertTrue(svg.text().contains("模型只吃 png / jpeg / gif / webp"));
    }

    // ── web-search ──

    @Test
    void searchPureFunctions() {
        assertEquals(WebSearch.Provider.EXA, WebSearch.provider("exa", false));
        assertEquals(WebSearch.Provider.PARALLEL, WebSearch.provider("parallel", true));
        assertEquals(WebSearch.Provider.EXA, WebSearch.provider(null, true));
        assertEquals(WebSearch.Provider.PARALLEL, WebSearch.provider(null, false));
        assertEquals("https://mcp.exa.ai/mcp?exaApiKey=k", WebSearch.endpoint(WebSearch.Provider.EXA, "k", null));
        assertEquals("https://search.parallel.ai/mcp", WebSearch.endpoint(WebSearch.Provider.PARALLEL, null, ""));
        assertEquals("http://x", WebSearch.endpoint(WebSearch.Provider.EXA, "k", "http://x"));

        ObjectNode exa = WebSearch.requestBody(WebSearch.Provider.EXA, "q", 0, "bogus", "deep", 500);
        assertEquals("web_search_exa", exa.path("params").path("name").asString());
        assertEquals(8, exa.path("params").path("arguments").path("numResults").asInt());
        assertEquals("fallback", exa.path("params").path("arguments").path("livecrawl").asString());
        assertEquals("deep", exa.path("params").path("arguments").path("type").asString());
        assertEquals(500, exa.path("params").path("arguments").path("contextMaxCharacters").asInt());
        ObjectNode par = WebSearch.requestBody(WebSearch.Provider.PARALLEL, "q", 3, "", "", 0);
        assertEquals("q", par.path("params").path("arguments").path("search_queries").get(0).asString());

        assertEquals("hi", WebSearch.parseResponse("{\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"));
        assertEquals("yo", WebSearch.parseResponse("event: m\ndata: {\"result\":{\"content\":[{\"text\":\"\"},{\"text\":\"yo\"}]}}\n"));
        assertEquals("（对面报错）bad", WebSearch.parseResponse("{\"error\":{\"message\":\"bad\"}}"));
        assertNull(WebSearch.parseResponse("not json"));
    }

    @Test
    void searchAgainstLocalEndpoint() {
        Map<String, String> fake = Map.of("AGENTFORGE_WEBSEARCH_URL", base + "/mcp", "AGENTFORGE_WEBSEARCH_PROVIDER", "parallel");
        WebSearch.env = fake::get;
        ToolOutput o = call(WebSearch.tool(), root, "query", "  java 25  ");
        assertFalse(o.error(), o.text());
        assertEquals("（Parallel）\nRESULT https://x.y", o.text());
        assertTrue(bodies.getFirst().contains("\"objective\":\"java 25\""), bodies.getFirst());
        assertTrue(call(WebSearch.tool(), root, "query", "  ").error());
    }

    @Test
    void flags() {
        assertTrue(WebFetch.tool().sensitive() && WebFetch.tool().concurrencySafe());
        assertTrue(WebSearch.tool().sensitive() && WebSearch.tool().concurrencySafe());
    }
}
