package io.ailink.agentforge.agent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 配置解析 / ${VAR} 展开 / state 文件 / 目录消毒 —— 零 I/O（或只碰临时目录）。 */
class McpSpecTest {
    @TempDir Path tmp;

    @Test
    void kindsAndDefaults() {
        McpSpecs specs = new McpSpecs();
        specs.define("a", Map.of("command", List.of("echo")));
        specs.define("b", Map.of("url", "https://x/mcp"));
        assertEquals(McpSpec.Kind.LOCAL, specs.ref("a").orElseThrow().kind());
        assertEquals(McpSpec.Kind.REMOTE, specs.ref("b").orElseThrow().kind());
        assertEquals("a_", specs.ref("a").orElseThrow().prefix());
        assertEquals(30_000, specs.ref("a").orElseThrow().timeoutMs());

        // 同名后来居上，位置保住
        specs.define("a", Map.of("command", List.of("echo", "2")));
        assertEquals(List.of("echo", "2"), specs.ref("a").orElseThrow().command());
        assertEquals("a", specs.all().get(0).name());
        specs.undefine("b");
        assertTrue(specs.ref("b").isEmpty());
    }

    @Test
    void rejectsWhatWouldMisbehaveSilently() {
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("command", List.of("x"), "url", "https://y")));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("commnad", List.of("x"))));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("command", List.of("x"), "prefix", "c/")));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("command", List.of("x"), "prefix", "")));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("command", List.of("x"), "timeout-ms", -1L)));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("c", Map.of("command", List.of())));
        assertThrows(IllegalArgumentException.class, () -> McpSpec.of("", Map.of("command", List.of("x"))));
        // 'env 在 Java 这边接得了（叠在父进程环境上）
        assertEquals(Map.of("K", "V"), McpSpec.of("c", Map.of("command", List.of("x"), "env", Map.of("K", "V"))).env());
    }

    @Test
    void authSchemeNullVsAbsent() {
        assertEquals("Bearer", McpSpec.of("c", Map.of("url", "https://x")).authScheme());
        java.util.HashMap<String, Object> m = new java.util.HashMap<>(Map.of("url", "https://x"));
        m.put("auth-scheme", null);
        assertNull(McpSpec.of("c", m).authScheme());
    }

    @Test
    void jsonRoundTrip() {
        McpSpec s = McpSpec.of("gh", Map.of("command", List.of("npx", "-y", "x"), "cwd", "/tmp",
                "headers", Map.of("A", "b"), "timeout-ms", 1234L, "enabled", false));
        McpSpec back = McpSpec.fromJson("gh", s.toJson());
        assertEquals(s, back);
        assertTrue(s.sameShape(back.withEnabled(true)));
    }

    @Test
    void envRefs() {
        Map<String, String> env = Map.of("TOKEN", "sk-fake");
        var x = EnvRefs.expand("Bearer ${TOKEN}", env::get);
        assertEquals("Bearer sk-fake", x.value());
        assertEquals(List.of(), x.missing());
        var y = EnvRefs.expand("Bearer ${NOPE}", env::get);
        assertEquals("Bearer ", y.value());
        assertEquals(List.of("NOPE"), y.missing());
        var z = EnvRefs.expand("没有收尾的 ${FOO", env::get);
        assertEquals("没有收尾的 ${FOO", z.value());
        assertEquals(List.of(), z.missing());
        var h = EnvRefs.expandAll(Map.of("K", "${TOKEN}-${X}"), env::get);
        assertEquals(Map.of("K", "sk-fake-"), h.value());
        assertEquals(List.of("X"), h.missing());
    }

    @Test
    void stateFilePersistsAndSkipsGarbage() throws Exception {
        Path p = tmp.resolve("s/mcp.json");
        McpState st = new McpState(p);
        assertNull(st.ref("x"));
        st.set("x", JsonNodeFactory.instance.objectNode().put("enabled", false));
        st.set("y", JsonNodeFactory.instance.objectNode().put("enabled", true));
        st.set("x", JsonNodeFactory.instance.objectNode().put("enabled", true));
        McpState again = new McpState(p);
        assertEquals(List.of("x", "y"), again.entries().stream().map(Map.Entry::getKey).toList(), "替换保住位置");
        assertTrue(again.ref("x").get("enabled").asBoolean());
        again.remove("y");
        assertNull(new McpState(p).ref("y"));

        // 读坏了当成空；坏条目跳过
        Files.writeString(p, "{ not json");
        assertTrue(new McpState(p).entries().isEmpty());
        Files.writeString(p, "{\"servers\":[{\"name\":\"ok\",\"enabled\":false},{\"nope\":1},3]}");
        assertEquals(1, new McpState(p).entries().size());
    }

    @Test
    void badStateEntryIsSkippedNotFatal() throws Exception {
        Path p = tmp.resolve("mcp.json");
        Files.writeString(p, "{\"servers\":[{\"name\":\"broken\"},{\"name\":\"good\",\"command\":[\"echo\"],\"enabled\":false}]}");
        McpManager m = new McpManager(new McpSpecs(), new McpState(p), McpTokens.inMemory());
        m.syncSpecs();
        assertTrue(m.server("broken").isEmpty());
        assertEquals(McpStatus.DISABLED, m.server("good").orElseThrow().status());
    }

    @Test
    void catalogSanitizesCapsDedupesAndRejects() {
        JsonMapper j = JsonMapper.builder().build();
        String longName = "x".repeat(80);
        var cat = McpTools.catalog("pw_", List.of(
                j.readTree("{\"name\":\"browser/click\",\"inputSchema\":{\"type\":\"object\"}}"),
                j.readTree("{\"name\":\"browser.click\"}"),
                j.readTree("{\"name\":\"" + longName + "\",\"inputSchema\":{\"type\":\"object\"},\"annotations\":{\"readOnlyHint\":true}}"),
                j.readTree("{\"name\":\"arr\",\"inputSchema\":{\"type\":\"array\"}}"),
                j.readTree("{\"description\":\"无名\"}")));
        assertEquals(List.of("pw_browser_click", "pw_browser_click_2", ("pw_" + longName).substring(0, 64)),
                cat.entries().stream().map(McpTools.Entry::localName).toList());
        assertEquals("object", cat.entries().get(1).schema().get("type").asString());
        assertTrue(cat.entries().get(1).schema().has("properties"));
        assertTrue(cat.entries().get(2).readOnly());
        assertFalse(cat.entries().get(0).readOnly());
        assertEquals(2, cat.rejected().size());
        assertEquals("arr", cat.rejected().get(0).remoteName());
    }

    @Test
    void resultBlocks() {
        JsonMapper j = JsonMapper.builder().build();
        var out = McpTools.toOutput(j.readTree("""
                {"content":[{"type":"text","text":"a"},
                  {"type":"resource","resource":{"uri":"file:///x.pdf","mimeType":"application/pdf","blob":"JVBERi0="}},
                  {"type":"resource","resource":{"uri":"file:///x.txt","text":"文本"}},
                  {"type":"resource_link","uri":"file:///y","name":"y"}]}"""));
        assertFalse(out.error());
        assertEquals(4, out.content().size());
        assertInstanceOf(io.ailink.agentforge.agent.model.Block.Document.class, out.content().get(1));
        assertEquals("a\n文本\n[资源链接] y file:///y", out.text());
        var sc = McpTools.toOutput(j.readTree("{\"content\":[],\"structuredContent\":{\"n\":1},\"isError\":true}"));
        assertTrue(sc.error());
        assertEquals("{\"n\":1}", sc.text());
        ObjectNode none = JsonNodeFactory.instance.objectNode();
        assertEquals("", McpTools.toOutput(none).text());
    }
}
