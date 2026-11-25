package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.codemode.ExecuteTool;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** 对应 coxswain {@code tests/agent-mcp-test.ss}：连上 / 断开 / 重连 / 停用 / 超时 / 收摊 / 落盘 / 交互加删。 */
class McpManagerTest {
    @TempDir Path tmp;
    McpSpecs specs;
    McpState state;
    McpManager mgr;

    @BeforeEach
    void setUp() {
        specs = new McpSpecs();
        state = new McpState(tmp.resolve("state/mcp.json"));
        mgr = new McpManager(specs, state, McpTokens.inMemory());
    }

    @AfterEach
    void tearDown() { mgr.shutdown(); }

    static List<String> fake(String mode, String... extra) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("mcpport.testcp", System.getProperty("java.class.path"));
        List<String> argv = new ArrayList<>(List.of(java, "-Xshare:auto", "-XX:TieredStopAtLevel=1", "-cp", cp,
                FakeMcpServer.class.getName(), mode));
        argv.addAll(List.of(extra));
        return argv;
    }

    static Map<String, Object> cmd(List<String> argv, Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("command", argv);
        m.put("timeout-ms", 25_000L);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    McpServerInfo settle(String name) {
        long end = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        while (System.nanoTime() < end) {
            Optional<McpServerInfo> s = mgr.server(name);
            if (s.isPresent() && s.get().status() != McpStatus.CONNECTING) return s.get();
            try { Thread.sleep(30); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        return mgr.server(name).orElseThrow();
    }

    static List<String> names(List<AgentTool> ts) { return ts.stream().map(AgentTool::name).sorted().toList(); }

    static AgentTool named(List<AgentTool> ts, String n) {
        return ts.stream().filter(t -> t.name().equals(n)).findFirst().orElseThrow();
    }

    ToolOutput invoke(String name) {
        return named(mgr.tools(), name).execute(ToolEnv.of(new Workspace(tmp)), JsonNodeFactory.instance.objectNode());
    }

    // ── 连上 ─────────────────────────────────────────────────────────────

    @Test
    void connectsAndToolsCarryAnnotations() {
        specs.define("fk", cmd(fake("normal")));
        mgr.start();
        McpServerInfo s = settle("fk");
        assertEquals(McpStatus.CONNECTED, s.status(), s.error());
        assertEquals(List.of("fk_peek", "fk_poke", "fk_tap"), names(s.tools()));
        assertTrue(s.rejected().isEmpty());
        assertEquals(List.of("fk_peek", "fk_poke", "fk_tap"), names(mgr.tools()));

        AgentTool ro = named(mgr.tools(), "fk_peek");
        AgentTool rw = named(mgr.tools(), "fk_poke");
        AgentTool rw2 = named(mgr.tools(), "fk_tap");
        assertTrue(ro.readOnly());
        assertFalse(ro.sensitive());
        assertTrue(ro.concurrencySafe());
        // 没有标注就当会写（保守那一边）；显式 false 与缺席同判
        for (AgentTool t : List.of(rw, rw2)) {
            assertFalse(t.readOnly());
            assertTrue(t.sensitive());
            assertFalse(t.concurrencySafe());
        }
        ToolOutput out = invoke("fk_peek");
        assertFalse(out.error());
        assertEquals("pong", out.text());
        assertEquals(new McpManager.Counts(1, 1, false), mgr.counts());
        assertFalse(mgr.connecting());
    }

    @Test
    void disconnectKeepsToolsAndFailsReadably() {
        specs.define("fk", cmd(fake("normal")));
        mgr.start();
        assertEquals(McpStatus.CONNECTED, settle("fk").status());
        List<McpEvent> events = Collections.synchronizedList(new ArrayList<>());
        mgr.addListener(events::add);

        mgr.disconnect("fk");
        assertEquals(List.of("fk_peek", "fk_poke", "fk_tap"), names(mgr.tools()), "断开之后工具还在表里");
        ToolOutput out = invoke("fk_peek");
        assertTrue(out.error());
        assertTrue(out.text().contains("fk") && out.text().contains("没有连接") && out.text().contains("/mcp"), out.text());
        // 断开只发 STATUS，不发 CATALOG
        assertTrue(events.stream().anyMatch(e -> e.kind() == McpEvent.Kind.STATUS));
        assertTrue(events.stream().noneMatch(e -> e.kind() == McpEvent.Kind.CATALOG));
        assertEquals(new McpManager.Counts(0, 1, true), mgr.counts());

        // 重连：不换工具记录
        List<AgentTool> before = mgr.server("fk").orElseThrow().tools();
        events.clear();
        mgr.reconnect("fk");
        assertEquals(McpStatus.CONNECTED, settle("fk").status());
        List<AgentTool> after = mgr.server("fk").orElseThrow().tools();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), after.get(i), "重连不换工具对象");
        assertTrue(events.stream().noneMatch(e -> e.kind() == McpEvent.Kind.CATALOG), "目录没变不发 CATALOG");
        assertEquals("pong", invoke("fk_peek").text());

        // 停用：工具不给模型，发 CATALOG
        events.clear();
        mgr.setEnabled("fk", false);
        assertEquals(List.of(), mgr.tools());
        assertEquals(McpStatus.DISABLED, mgr.server("fk").orElseThrow().status());
        assertTrue(events.stream().anyMatch(e -> e.kind() == McpEvent.Kind.CATALOG));
        mgr.setEnabled("fk", true);
        assertEquals(McpStatus.CONNECTED, settle("fk").status());
        assertEquals(List.of("fk_peek", "fk_poke", "fk_tap"), names(mgr.tools()));
    }

    // ── 失败的几种 ───────────────────────────────────────────────────────

    @Test
    void handshakeTimeoutGivesSelfHelp() {
        specs.define("slow", cmd(fake("stubborn"), "timeout-ms", 1500L));
        mgr.start();
        McpServerInfo s = settle("slow");
        assertEquals(McpStatus.FAILED, s.status());
        assertTrue(s.error().contains("timeout-ms"), s.error());
        assertFalse(s.error().contains("握手失败"));
        assertEquals(List.of(), mgr.tools(), "连不上的服务器没有工具");
    }

    @Test
    void protocolErrorIsQuoted() {
        specs.define("rej", cmd(fake("reject")));
        mgr.start();
        McpServerInfo s = settle("rej");
        assertEquals(McpStatus.FAILED, s.status());
        assertTrue(s.error().startsWith("握手失败：") && s.error().contains("unsupported protocolVersion"), s.error());
    }

    @Test
    void missingExecutableSaysSo() {
        specs.define("nope", Map.of("command", List.of("agentforge-mcp-no-such-command-12345"), "timeout-ms", 2000L));
        specs.define("gone", Map.of("command", List.of("/nonexistent/dir/mcp-server"), "timeout-ms", 2000L));
        mgr.start();
        for (String n : List.of("nope", "gone")) {
            McpServerInfo s = settle(n);
            assertEquals(McpStatus.FAILED, s.status());
            assertTrue(s.error().contains("找不到可执行文件"), s.error());
        }
    }

    @Test
    void disabledFromStartIsNotStarted() {
        specs.define("off", cmd(fake("stubborn"), "enabled", false));
        mgr.start();
        assertEquals(McpStatus.DISABLED, mgr.server("off").orElseThrow().status());
        assertEquals(List.of(), mgr.tools());
        assertTrue(mgr.pid("off").isEmpty());
    }

    @Test
    void childGoneIsReportedWithExitCodeAndStderr() {
        specs.define("rich", cmd(fake("rich")));
        mgr.start();
        assertEquals(McpStatus.CONNECTED, settle("rich").status());
        ToolOutput out = invoke("rich_die");
        assertTrue(out.error(), out.text());
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (mgr.server("rich").orElseThrow().status() == McpStatus.CONNECTED && System.nanoTime() < end) {
            try { Thread.sleep(30); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        McpServerInfo s = mgr.server("rich").orElseThrow();
        assertEquals(McpStatus.FAILED, s.status());
        assertTrue(s.error().contains("退出码 3") && s.error().contains("数据库连不上"), s.error());
    }

    // ── 收摊 ─────────────────────────────────────────────────────────────

    @Test
    void shutdownReallyKillsTheChild() throws Exception {
        Path pidFile = tmp.resolve("pid.txt");
        specs.define("bye", cmd(fake("normal", pidFile.toString())));
        mgr.start();
        assertEquals(McpStatus.CONNECTED, settle("bye").status());
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertEquals(pid, mgr.pid("bye").orElseThrow());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        mgr.shutdown();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "收摊之后子进程真的没了");
    }

    // ── 落盘 ─────────────────────────────────────────────────────────────

    @Test
    void disableSurvivesRestartAndConfigShapeWins() {
        specs.define("keep", cmd(fake("normal")));
        mgr.start();
        assertEquals(McpStatus.CONNECTED, settle("keep").status());
        mgr.setEnabled("keep", false);
        assertEquals(List.of(), mgr.tools());
        ObjectNode expect = JsonNodeFactory.instance.objectNode().put("enabled", false);
        assertEquals(expect, state.ref("keep"), "写进了盘");

        // 模拟重启：新的 state 对象读同一个文件
        mgr.shutdown();
        McpState state2 = new McpState(state.path());
        mgr = new McpManager(specs, state2, McpTokens.inMemory());
        mgr.start();
        assertEquals(McpStatus.DISABLED, mgr.server("keep").orElseThrow().status(), "重启之后还是停用的");
        mgr.setEnabled("keep", true);
        assertEquals(McpStatus.CONNECTED, settle("keep").status());
        state2.clear();
        mgr.syncSpecs();
        assertNotEquals(McpStatus.DISABLED, mgr.server("keep").orElseThrow().status());

        // 配置里的形状它说了算
        specs.define("keep", cmd(fake("normal"), "timeout-ms", 4321L));
        mgr.syncSpecs();
        assertEquals(4321, mgr.server("keep").orElseThrow().spec().timeoutMs());
    }

    @Test
    void tokensOnlySayWhetherTheyExist() {
        specs.define("priv", Map.of("url", "https://example.invalid/mcp", "enabled", false));
        mgr.syncSpecs();
        assertFalse(mgr.hasToken("priv"));
        mgr.setToken("priv", "sk-secret-123");
        assertTrue(mgr.hasToken("priv"));
        assertTrue(mgr.server("priv").orElseThrow().hasToken());
        mgr.forgetToken("priv");
        assertFalse(mgr.hasToken("priv"));
    }

    @Test
    void interactiveAddAndRemove() {
        // 与配置同一条校验
        assertThrows(IllegalArgumentException.class, () -> mgr.addServer("x", Map.of("command", List.of("y"), "prefix", "x/")));
        assertTrue(mgr.servers().isEmpty());

        mgr.addServer("added", Map.of("command", fake("normal")));
        assertEquals(McpStatus.CONNECTED, settle("added").status());
        assertTrue(names(mgr.tools()).contains("added_peek"));
        assertFalse(mgr.server("added").orElseThrow().fromConfig());

        // 重启之后还在
        mgr.shutdown();
        mgr = new McpManager(specs, new McpState(state.path()), McpTokens.inMemory());
        mgr.syncSpecs();
        assertTrue(mgr.server("added").isPresent());

        assertTrue(mgr.removeServer("added"));
        assertTrue(mgr.server("added").isEmpty());
        assertNull(mgr.state().ref("added"));

        // 配置定义的删不掉
        specs.define("fromini", cmd(fake("normal"), "enabled", false));
        mgr.syncSpecs();
        assertFalse(mgr.removeServer("fromini"));
        assertTrue(mgr.server("fromini").isPresent());
        assertThrows(IllegalArgumentException.class, () -> mgr.addServer("fromini", Map.of("command", List.of("y"))));
    }

    // ── 结果的形状、消毒、拒收、instructions、execute ───────────────────

    @Test
    void richCatalogAndResults() {
        specs.define("rich", cmd(fake("rich")));
        mgr.start();
        McpServerInfo s = settle("rich");
        assertEquals(McpStatus.CONNECTED, s.status(), s.error());
        assertTrue(names(s.tools()).contains("rich_bad_name"), names(s.tools()).toString());
        assertEquals(1, s.rejected().size());
        assertEquals("arr", s.rejected().get(0).remoteName());
        assertEquals(Map.of("rich", "先调 peek 再调 poke。"), mgr.instructions());

        ToolOutput shot = invoke("rich_shot");
        assertFalse(shot.error());
        assertEquals(2, shot.content().size());
        assertInstanceOf(Block.Image.class, shot.content().get(1));
        assertEquals("image/png", ((Block.Image) shot.content().get(1)).mediaType());

        ToolOutput fail = invoke("rich_fail");
        assertTrue(fail.error());
        assertEquals("出事了", fail.text());

        // execute：一段脚本连着调两件
        AgentTool x = ExecuteTool.create(mgr.tools());
        assertNotNull(x);
        assertTrue(x.sensitive(), "目录里有会写的工具 ⇒ sensitive");
        assertTrue(x.description().contains("rich.echo  {q: string, n?: integer}  回声"), x.description());
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        in.put("code", "(define r (call \"rich.echo\" '((\"q\" . \"hi\") (\"n\" . 2))))\n"
                + "(log! \"r=~s\" r)\n"
                + "(string-append (ref r \"q\") \"/\" (call \"rich.peek\" '()))");
        ToolOutput out = x.execute(ToolEnv.of(new Workspace(tmp)), in);
        assertFalse(out.error(), out.text());
        assertTrue(out.text().startsWith("hi/pong"), out.text());
        assertTrue(out.text().contains("--- log ---") && out.text().contains("调了 2 次：rich.echo, rich.peek"), out.text());
    }
}
