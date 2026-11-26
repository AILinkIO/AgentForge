package io.ailink.agentforge.web;

import com.sun.net.httpserver.HttpServer;
import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：假的 Anthropic 网关（SSE）+ 真的 Jetty + 真的 AgentRuntime（配置目录在临时目录，不碰用户的 ~/.config/agentforge）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebApiTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static Path home;
    static Path root;
    static HttpServer gateway;

    private static final String SSE = """
            event: message_start
            data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"fake-model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好，"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"网页端。"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        home = Files.createTempDirectory("afweb-home");
        root = Files.createTempDirectory("afweb-root");
        AgentPaths.override(home);
        gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = SSE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "text/event-stream");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
        });
        gateway.start();
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        Files.writeString(home.resolve("providers.json"), """
                {"providers": {"fake": {"api": "anthropic", "baseUrl": "%s", "label": "Fake",
                   "models": {"fake-model": {"context": 100000, "maxOutput": 4096}}}},
                 "model": "fake/fake-model"}
                """.formatted(base));
        r.add("agentforge.web.root", () -> root.toString());
        r.add("agentforge.web.mcp", () -> "false");
        r.add("agentforge.web.preset", () -> "read-only");
        r.add("agentforge.web.catalog-auto-sync", () -> "false");      // 测试不联网
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (gateway != null) gateway.stop(0);
        AgentPaths.override(null);
        for (Path p : new Path[]{home, root}) {
            if (p == null) continue;
            try (Stream<Path> w = Files.walk(p)) {
                w.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
    }

    @LocalServerPort int port;
    @Autowired AgentRuntime rt;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    String url(String p) { return "http://127.0.0.1:" + port + p; }

    HttpResponse<String> get(String p) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(p))).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String p, String body, boolean header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(p)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (header) b.header(AccessFilter.HEADER, "1");
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> put(String p, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(p))).header("Content-Type", "application/json")
                .header(AccessFilter.HEADER, "1").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void infoReportsWorkspaceAndModel() throws Exception {
        HttpResponse<String> r = get("/api/info");
        assertEquals(200, r.statusCode(), r.body());
        JsonNode j = JSON.readTree(r.body());
        assertEquals(root.toAbsolutePath().normalize().toString(), j.path("root").asString());
        assertEquals("fake/fake-model", j.path("model").asString());
        assertTrue(j.path("permissions").asString().length() > 0);
    }

    @Test
    void mutatingRequestsNeedTheCsrfHeader() throws Exception {
        assertEquals(403, post("/api/sessions", "{}", false).statusCode());
        assertEquals(200, post("/api/sessions", "{}", true).statusCode());
    }

    @Test
    void foreignHostIsRejected() throws Exception {
        // HttpClient 不让改 Host 头：直接走一条原始连接
        try (var sock = new java.net.Socket("127.0.0.1", port)) {
            sock.getOutputStream().write(("GET /api/info HTTP/1.1\r\nHost: evil.example:" + port + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            String head = new String(sock.getInputStream().readNBytes(64), StandardCharsets.US_ASCII);
            assertTrue(head.startsWith("HTTP/1.1 403"), head);
        }
    }

    @Test
    void sendStreamsEventsAndLandsInTheTranscript() throws Exception {
        // 先订阅事件流
        List<String> events = new CopyOnWriteArrayList<>();
        HttpResponse<InputStream> sse = http.send(HttpRequest.newBuilder(URI.create(url("/api/events"))).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, sse.statusCode());
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(sse.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) if (line.startsWith("data:")) events.add(line.substring(5));
            } catch (IOException ignored) { }
        });

        HttpResponse<String> k = put("/api/providers/fake/key", "{\"key\":\"test-key\"}");
        assertEquals(200, k.statusCode(), k.body());
        assertTrue(!k.body().contains("test-key"), "回包不许带 key");
        JsonNode s = JSON.readTree(post("/api/sessions", "{}", true).body());
        String id = s.path("id").asString();
        assertNotNull(id);
        HttpResponse<String> sent = post("/api/sessions/" + id + "/messages", "{\"text\":\"打个招呼\"}", true);
        assertEquals(200, sent.statusCode(), sent.body());
        assertEquals("SENT", JSON.readTree(sent.body()).path("result").asString());

        JsonNode snap = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            snap = JSON.readTree(get("/api/sessions/" + id).body());
            if (!snap.path("session").path("busy").asBoolean() && snap.path("entries").size() >= 2) break;
            Thread.sleep(50);
        }
        assertNotNull(snap);
        List<String> texts = new ArrayList<>();
        for (JsonNode e : snap.path("entries")) texts.add(e.path("role").asString() + ":" + e.path("text").asString());
        assertTrue(texts.contains("USER:打个招呼"), texts.toString());
        assertTrue(texts.contains("ASSISTANT:你好，网页端。"), texts.toString() + " error=" + snap.path("error"));
        assertTrue(snap.path("usage").path("total").path("output").asLong() > 0, snap.path("usage").toString());

        deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && events.stream().noneMatch(e -> e.contains("\"finished\""))) Thread.sleep(20);
        reader.interrupt();
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"delta\"") && e.contains(id)), events.toString());
        assertTrue(events.stream().anyMatch(e -> e.contains("\"type\":\"finished\"")), events.toString());

        // 列表里有这段会话，标题取第一句话
        JsonNode list = JSON.readTree(get("/api/sessions").body());
        boolean found = false;
        for (JsonNode x : list) if (id.equals(x.path("id").asString())) { found = true; assertEquals("打个招呼", x.path("title").asString()); }
        assertTrue(found, list.toString());
    }

    @Test
    void unknownSessionIs404AndApprovalWithoutPendingIs409() throws Exception {
        assertEquals(404, get("/api/sessions/nope").statusCode());
        String id = JSON.readTree(post("/api/sessions", "{}", true).body()).path("id").asString();
        HttpResponse<String> r = post("/api/sessions/" + id + "/approval", "{\"decision\":\"APPROVED\"}", true);
        assertEquals(409, r.statusCode(), r.body());
    }

    @Test
    void compactingAShortConversationIsRefusedUpFront() throws Exception {
        String id = JSON.readTree(post("/api/sessions", "{}", true).body()).path("id").asString();
        HttpResponse<String> r = post("/api/sessions/" + id + "/compact", "{}", true);
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(r.body().contains("没有值得压的"), r.body());
        assertEquals(0, JSON.readTree(get("/api/sessions/" + id).body()).path("compactions").asInt(-1));
    }

    HttpResponse<String> delete(String p) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(p))).header(AccessFilter.HEADER, "1").DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startupRootIsSeededAsAProject() throws Exception {
        JsonNode ps = JSON.readTree(get("/api/projects").body());
        boolean found = false;
        for (JsonNode p : ps) for (JsonNode f : p.path("folders")) if (f.asString().equals(root.toAbsolutePath().normalize().toString())) found = true;
        assertTrue(found, ps.toString());
    }

    @Test
    void projectsPlaceSessionsAndConversations() throws Exception {
        Path a = Files.createDirectories(root.resolve("proj-a"));
        Path b = Files.createDirectories(root.resolve("proj-b"));
        put("/api/providers/fake/key", "{\"key\":\"test-key\"}");
        String body = JSON.writeValueAsString(java.util.Map.of("name", "演示", "folders", List.of(a.toString(), b.toString()),
                "instructions", "用文言文回答", "preset", "read-only"));
        HttpResponse<String> cr = post("/api/projects", body, true);
        assertEquals(200, cr.statusCode(), cr.body());
        String pid = JSON.readTree(cr.body()).path("id").asString();

        // 坏输入：没有文件夹 / 文件夹不存在 / 不认识的模型
        assertEquals(400, post("/api/projects", "{\"name\":\"x\",\"folders\":[]}", true).statusCode());
        assertEquals(400, post("/api/projects", "{\"name\":\"x\",\"folders\":[\"/no/such/dir\"]}", true).statusCode());
        assertEquals(400, post("/api/projects", "{\"name\":\"x\",\"folders\":[\"" + a + "\"],\"model\":\"nope/x\"}", true).statusCode());

        // 开在第二个源文件夹里
        JsonNode s = JSON.readTree(post("/api/sessions", "{\"project\":\"" + pid + "\",\"folder\":\"" + b + "\"}", true).body());
        String sid = s.path("id").asString();
        assertEquals(pid, s.path("project").asString());
        assertEquals(b.toString(), s.path("root").asString());
        assertEquals(404, post("/api/sessions", "{\"project\":\"nope\"}", true).statusCode());

        assertEquals("SENT", JSON.readTree(post("/api/sessions/" + sid + "/messages", "{\"text\":\"项目里的话\"}", true).body()).path("result").asString());
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode convs = null;
        while (System.currentTimeMillis() < deadline) {
            convs = JSON.readTree(get("/api/conversations?project=" + pid).body());
            if (convs.size() == 1) break;
            Thread.sleep(50);
        }
        assertNotNull(convs);
        assertEquals(1, convs.size(), "对话记在这个项目下：" + convs);
        assertEquals(b.toString(), convs.get(0).path("root").asString());
        long conv = convs.get(0).path("id").asLong();

        // 改项目、列表里的计数
        String upd = JSON.writeValueAsString(java.util.Map.of("name", "演示2", "folders", List.of(a.toString(), b.toString())));
        HttpResponse<String> ur = put("/api/projects/" + pid, upd);
        assertEquals(200, ur.statusCode(), ur.body());
        boolean listed = false;
        for (JsonNode p : JSON.readTree(get("/api/projects").body()))
            if (p.path("id").asString().equals(pid)) { listed = true; assertEquals("演示2", p.path("name").asString()); assertEquals(1, p.path("conversations").asInt()); }
        assertTrue(listed);

        // 有开着的会话时不许移除；关掉后可以，对话归到「未归类」
        assertEquals(409, delete("/api/projects/" + pid).statusCode());
        assertEquals(204, delete("/api/sessions/" + sid).statusCode());
        assertEquals(204, delete("/api/projects/" + pid).statusCode());
        boolean orphan = false;
        for (JsonNode c : JSON.readTree(get("/api/conversations?project=none").body())) if (c.path("id").asLong() == conv) orphan = true;
        assertTrue(orphan);
    }

    @Test
    void folderBrowserListsDirectoriesAndCreatesOne() throws Exception {
        Path d = Files.createDirectories(root.resolve("browse/child"));
        Files.createDirectories(root.resolve("browse/.hidden"));
        Files.createDirectories(root.resolve("browse/repo/.git"));
        Files.writeString(root.resolve("browse/file.txt"), "x");
        JsonNode r = JSON.readTree(get("/api/fs?path=" + java.net.URLEncoder.encode(root.resolve("browse").toString(), StandardCharsets.UTF_8)).body());
        List<String> names = new ArrayList<>();
        for (JsonNode e : r.path("dirs")) names.add(e.path("name").asString());
        assertEquals(List.of("child", "repo"), names, "只列目录、不列隐藏的");
        assertTrue(r.path("dirs").get(1).path("git").asBoolean());
        assertEquals(root.toAbsolutePath().normalize().toString(), r.path("parent").asString());
        assertEquals(400, get("/api/fs?path=" + java.net.URLEncoder.encode(d.resolve("nope").toString(), StandardCharsets.UTF_8)).statusCode());
        HttpResponse<String> mk = post("/api/fs/mkdir", JSON.writeValueAsString(java.util.Map.of("parent", d.toString(), "name", "新的")), true);
        assertEquals(200, mk.statusCode(), mk.body());
        assertTrue(Files.isDirectory(d.resolve("新的")));
        assertEquals(400, post("/api/fs/mkdir", JSON.writeValueAsString(java.util.Map.of("parent", d.toString(), "name", "../evil")), true).statusCode());
    }

    @Test
    void credentialsFromConfigAreWrittenToProvidersJsonAndReloaded() throws Exception {
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        put("/api/providers/fake/key", "{\"key\":\"test-key-1234\"}");
        JsonNode list = JSON.readTree(get("/api/credentials").body());
        JsonNode fake = null;
        for (JsonNode c : list) if (c.path("id").asString().equals("fake")) fake = c;
        assertNotNull(fake, list.toString());
        assertEquals("config", fake.path("source").asString());
        assertEquals("…1234", fake.path("keyHint").asString(), "只回末 4 位");
        assertFalse(list.toString().contains("test-key-1234"), "不回完整 key");

        String body = JSON.writeValueAsString(java.util.Map.of("source", "config", "id", "gw2", "label", "网关二", "api", "anthropic",
                "baseUrl", base, "key", "sk-gw2-abcdefgh", "models", List.of(java.util.Map.of("id", "m2", "context", 100000))));
        HttpResponse<String> cr = post("/api/credentials", body, true);
        assertEquals(200, cr.statusCode(), cr.body());
        assertTrue(Files.readString(home.resolve("providers.json")).contains("\"gw2\""), "写进了 providers.json");
        assertTrue(Files.readString(home.resolve("providers.json")).contains("\"fake\""), "别的 provider 原样留着");
        JsonNode models = JSON.readTree(get("/api/models").body()).path("models");
        boolean m2 = false;
        for (JsonNode m : models) if (m.path("ref").asString().equals("gw2/m2")) { m2 = true; assertEquals("config", m.path("source").asString()); }
        assertTrue(m2, models.toString());
        assertEquals(409, post("/api/credentials", body, true).statusCode(), "同 id 不能再加");
        assertEquals(400, post("/api/credentials", JSON.writeValueAsString(java.util.Map.of("source", "config", "id", "gw3", "api", "anthropic",
                "baseUrl", base, "models", List.of())), true).statusCode(), "没有模型");

        // 改：换一组模型
        String upd = JSON.writeValueAsString(java.util.Map.of("api", "anthropic", "baseUrl", base, "label", "网关二",
                "models", List.of(java.util.Map.of("id", "m3"))));
        assertEquals(200, put("/api/credentials/gw2", upd).statusCode());
        String refs = JSON.readTree(get("/api/models").body()).path("models").toString();
        assertTrue(refs.contains("gw2/m3") && !refs.contains("gw2/m2"), refs);

        // 删：文件里没了，模型也没了
        assertEquals(200, delete("/api/credentials/gw2").statusCode());
        assertFalse(Files.readString(home.resolve("providers.json")).contains("\"gw2\""));
        assertFalse(JSON.readTree(get("/api/models").body()).toString().contains("gw2/"));
    }

    @Test
    void settingTheDefaultModelBeatsTheConfigDefaultAndLeavesOpenSessionsAlone() throws Exception {
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        put("/api/providers/fake/key", "{\"key\":\"test-key-1234\"}");
        String body = JSON.writeValueAsString(java.util.Map.of("source", "config", "id", "gwd", "api", "anthropic",
                "baseUrl", base, "key", "sk-gwd-abcdefgh", "models", List.of(java.util.Map.of("id", "md"))));
        assertEquals(200, post("/api/credentials", body, true).statusCode());
        String open = JSON.readTree(post("/api/sessions", "{}", true).body()).path("id").asString();
        try {
            assertEquals("fake/fake-model", JSON.readTree(get("/api/info").body()).path("model").asString(), "providers.json 里写的默认");
            HttpResponse<String> r = put("/api/models/default", "{\"ref\":\"gwd/md\"}");
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("gwd/md", JSON.readTree(r.body()).path("current").asString());
            assertEquals("gwd/md", JSON.readTree(get("/api/info").body()).path("model").asString());
            assertTrue(Files.readString(home.resolve("providers.json")).contains("\"model\" : \"gwd/md\""), "写回了配置里的默认");
            assertEquals("fake/fake-model", JSON.readTree(get("/api/sessions/" + open).body()).path("session").path("model").asString(), "开着的会话不动");
            assertEquals("gwd/md", JSON.readTree(post("/api/sessions", "{}", true).body()).path("model").asString(), "新会话用新的默认");
        } finally {
            put("/api/models/default", "{\"ref\":\"fake/fake-model\"}");
            delete("/api/credentials/gwd");
        }
    }

    @Test
    void catalogSyncRunsInTheBackgroundAndReportsWhyItFailed() throws Exception {
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        assertEquals(400, post("/api/catalog/sync", "{\"url\":\"ftp://x\"}", true).statusCode());
        HttpResponse<String> r = post("/api/catalog/sync", "{\"url\":\"" + base + "/not-a-catalog\"}", true);
        assertEquals(202, r.statusCode(), r.body());
        JsonNode st = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            st = JSON.readTree(get("/api/catalog").body()).path("sync");
            if (!st.path("running").asBoolean()) break;
            Thread.sleep(100);
        }
        assertNotNull(st);
        assertFalse(st.path("running").asBoolean());
        assertFalse(st.path("ok").asBoolean(true));
        assertTrue(st.path("error").asString().contains("不是 models.dev 的 api.json"), st.toString());
        assertEquals(base + "/not-a-catalog", st.path("url").asString());
    }

    @Test
    void catalogImportAndAddingAProviderFromIt() throws Exception {
        String base = "http://127.0.0.1:" + gateway.getAddress().getPort();
        assertEquals(400, http.send(HttpRequest.newBuilder(URI.create(url("/api/catalog/import"))).header(AccessFilter.HEADER, "1")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("[]")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        String api = """
                {"democat": {"id": "democat", "name": "Demo Cat", "npm": "@ai-sdk/anthropic", "api": "%s", "env": ["DEMOCAT_API_KEY"],
                  "models": {"cat-1": {"id": "cat-1", "name": "Cat One", "tool_call": true, "limit": {"context": 64000, "output": 8000}},
                             "no-tools": {"id": "no-tools", "name": "x", "tool_call": false, "limit": {"context": 64000, "output": 8000}}}}}
                """.formatted(base);
        HttpResponse<String> im = http.send(HttpRequest.newBuilder(URI.create(url("/api/catalog/import"))).header(AccessFilter.HEADER, "1")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(api)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, im.statusCode(), im.body());
        JsonNode cat = JSON.readTree(im.body());
        assertEquals("CACHE", cat.path("source").asString());
        assertEquals(1, cat.path("providers").asInt());
        assertTrue(cat.path("syncedAt").asLong() > 0);
        boolean listed = false;
        for (JsonNode p : JSON.readTree(get("/api/catalog/providers").body()))
            if (p.path("id").asString().equals("democat")) { listed = true; assertEquals(2, p.path("models").asInt()); assertFalse(p.path("enabled").asBoolean()); }
        assertTrue(listed);

        HttpResponse<String> add = post("/api/credentials", "{\"source\":\"catalog\",\"provider\":\"democat\",\"key\":\"sk-demo-87654321\"}", true);
        assertEquals(200, add.statusCode(), add.body());
        String models = JSON.readTree(get("/api/models").body()).path("models").toString();
        assertTrue(models.contains("democat/cat-1"), models);
        assertFalse(models.contains("democat/no-tools"), "不会调工具的模型不进池子");
        assertTrue(models.contains("\"source\":\"models.dev\""));
        assertEquals(200, delete("/api/credentials/democat").statusCode());
        assertFalse(JSON.readTree(get("/api/models").body()).toString().contains("democat/"));
    }

    @Test
    void permissionsAndToolsAreListed() throws Exception {
        JsonNode p = JSON.readTree(get("/api/permissions").body());
        assertEquals("read-only", p.path("preset").asString());
        assertEquals(3, p.path("presets").size());
        JsonNode tools = JSON.readTree(get("/api/tools").body());
        List<String> names = new ArrayList<>();
        for (JsonNode t : tools) names.add(t.path("name").asString());
        assertTrue(names.contains("read-file"), names.toString());
    }

    @Test
    void spaRoutesServeIndexButAssetsStayAssets() throws Exception {
        HttpResponse<String> page = get("/chat/abc");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("<title>spa</title>"), page.body());
        HttpResponse<String> js = get("/assets/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"), js.headers().toString());
        assertTrue(js.body().contains("export const x"), js.body());
        assertEquals(404, get("/assets/missing.js").statusCode());
    }
}
