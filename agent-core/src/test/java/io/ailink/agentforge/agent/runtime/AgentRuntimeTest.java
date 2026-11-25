package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.FakeCaller;
import io.ailink.agentforge.agent.session.SessionStatus;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    static final Duration T = Duration.ofSeconds(10);

    @TempDir Path cfg;
    @TempDir Path root;

    @AfterEach
    void reset() { AgentPaths.override(null); }

    @Test
    void standardToolsetFollowsCanonicalOrder() {
        Toolset ts = Toolset.standard();
        List<String> names = ts.builtins().stream().map(AgentTool::name).toList();
        assertEquals(Toolset.ORDER, names);
        Toolset t2 = Toolset.standard().add(AgentTool.builder("host-tool").description("宿主的").readOnly().handler((e, a) -> "x").build());
        AgentTool clarify = AgentTool.builder("clarify").description("问").readOnly().handler((e, a) -> "x").build();
        AgentTool task = AgentTool.builder("task").description("派").handler((e, a) -> "x").build();
        List<String> all = t2.forSession(null, clarify, task).stream().map(AgentTool::name).toList();
        assertEquals("host-tool", all.get(Toolset.ORDER.size()));
        assertEquals(List.of("clarify", "task"), all.subList(Toolset.ORDER.size() + 1, all.size()));
        assertTrue(t2.readOnlyTools(null).stream().allMatch(AgentTool::readOnly));
        assertNotNull(t2.catalog().byName("host-tool"));
        // MCP 工具 + execute 接在最后
        AgentTool mcpTool = AgentTool.builder("srv.echo").description("回声").readOnly().handler((e, a) -> "x").build();
        t2.mcp(() -> List.of(mcpTool));
        List<String> withMcp = t2.forSession(null, null, null).stream().map(AgentTool::name).toList();
        assertEquals(List.of("srv.echo", "execute"), withMcp.subList(withMcp.size() - 2, withMcp.size()));
    }

    /** 冒烟：建一个运行时、用假模型跑一轮带工具的对话（read-file → 回答），落库、检索、重开接着聊。 */
    @Test
    void headlessRuntimeRunsToolTurnAndPersists() throws Exception {
        Files.writeString(root.resolve("README.txt"), "AgentForge 是一个 agent 框架\n");
        FakeCaller fc = new FakeCaller().thenTool("t1", "read-file", "path", "README.txt").thenText("这是一个 agent 框架的说明");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            List<String> names = s.tools().stream().map(AgentTool::name).toList();
            assertEquals("read-file", names.getFirst());
            assertTrue(names.contains("clarify"));
            assertTrue(names.contains("task"));
            assertTrue(s.system().contains("当前目录 " + root.toAbsolutePath().normalize()));
            assertNotSame(rt.workspace(), s.workspace(), "每段会话一份派生工作区");

            s.send("README 里写了什么？");
            assertTrue(s.awaitIdle(T));
            assertEquals(SessionStatus.DONE, s.status());
            assertEquals("这是一个 agent 框架的说明", s.history().getLast().text());
            // 第一次请求发了系统提示和工具表
            assertEquals(s.system(), fc.requests.getFirst().system());
            assertFalse(fc.requests.getFirst().tools().isEmpty());

            Long conv = rt.conversationOf(s);
            assertNotNull(conv, "自动存盘");
            assertNull(rt.lastStoreError());
            SessionStore.Conversation c = rt.conversations(0).getFirst();
            assertEquals("README 里写了什么？", c.title());
            assertEquals(4, c.messages());
            assertEquals(1, rt.search("agent 框架的说明", 5).size());

            AgentSession again = rt.openSession(conv);
            assertEquals(s.history(), again.history());
            fc.thenText("接着聊");
            again.send("还有呢？");
            assertTrue(again.awaitIdle(T));
            assertEquals(6, rt.store().messages(conv).size(), "重开的会话接着存进同一段对话");

            // reset 之后开新对话（不往旧对话里拼）
            s.reset();
            fc.thenText("新的");
            s.send("另起一段");
            assertTrue(s.awaitIdle(T));
            assertNotEquals(conv, rt.conversationOf(s));
        }
    }

    @Test
    void selectAgentSwapsPromptAndTools() {
        FakeCaller fc = new FakeCaller();
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            assertNull(s.activeTools());
            rt.selectAgent(s, "deep");
            assertEquals("deep", s.agent());
            assertEquals(List.of("search-text", "find-files", "read-file", "list-dir"), s.activeTools());
            assertTrue(s.system().endsWith(io.ailink.agentforge.agent.prompt.Prompts.DEEP_SYSTEM_PROMPT));
            assertThrows(IllegalArgumentException.class, () -> rt.selectAgent(s, "nobody"));
        }
    }

    @Test
    void permissionSettingsResolveAndPersist() {
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller()).build()) {
            assertEquals(io.ailink.agentforge.agent.approval.PermissionSettings.DEFAULT, rt.permissionSettings(), "缺省 = auto 预设");
            AgentSession s = rt.newSession();
            rt.applyPreset(io.ailink.agentforge.agent.approval.ApprovalPreset.READ_ONLY);
            assertEquals(io.ailink.agentforge.agent.sandbox.SandboxMode.READ_ONLY, s.permissions().settings().sandbox(), "活着的会话跟着换");
            assertEquals("read-only", rt.models().prefs().sandbox(), "记进 prefs");
            assertEquals("on-request", rt.models().prefs().approval());
        }
        // 重启：prefs 打底，builder 的单项盖上
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller())
                .approvalPolicy(io.ailink.agentforge.sandbox.approval.ApprovalPolicy.UNLESS_TRUSTED).networkAccess(true).build()) {
            var ps = rt.permissionSettings();
            assertEquals(io.ailink.agentforge.agent.sandbox.SandboxMode.READ_ONLY, ps.sandbox());
            assertEquals("untrusted", ps.approval().wire());
            assertTrue(ps.networkAccess());
            assertNull(ps.preset());
        }
    }

    @Test
    void approveAlwaysAppendsAnExecRuleSharedPerRoot() throws Exception {
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "printf hello").thenText("好");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc)
                .approvalPolicy(io.ailink.agentforge.sandbox.approval.ApprovalPolicy.UNLESS_TRUSTED).build()) {
            AgentSession s = rt.newSession();
            io.ailink.agentforge.agent.session.Recorder rec = new io.ailink.agentforge.agent.session.Recorder();
            s.addListener(rec);
            s.send("跑");
            var g = rec.await(io.ailink.agentforge.agent.session.SessionEvent.ApprovalRequested.class, T);
            assertEquals(io.ailink.agentforge.agent.approval.ApprovalRequest.Kind.EXEC, g.request().kind());
            assertEquals(new io.ailink.agentforge.agent.approval.Amendment.PrefixRule(List.of("printf", "hello")), g.request().amendment());
            s.resolveApproval(io.ailink.agentforge.agent.approval.ReviewDecision.approvedAlways());
            assertTrue(s.awaitIdle(T));
            Path rules = cfg.resolve("rules").resolve("default.rules");
            assertEquals("prefix_rule(pattern=[\"printf\", \"hello\"], decision=\"allow\")\n", Files.readString(rules), "规则写进用户的 default.rules");
            assertTrue(rt.execPolicy(root).alreadyAllowed(List.of("printf", "hello")));
            assertSame(s.permissions().execPolicy(), rt.newSession().permissions().execPolicy(), "同一个根的会话共用规则");
            assertEquals(1, rec.of(io.ailink.agentforge.agent.session.SessionEvent.PolicyAmended.class).size());
        }
    }

    @Test
    void projectAndBuiltinRulesAreLoaded() throws Exception {
        Files.createDirectories(root.resolve(".agentforge/rules"));
        Files.writeString(root.resolve(".agentforge/rules/p.rules"), "prefix_rule(pattern=[\"make\"], decision=\"forbidden\", justification=\"用 just\")");
        Files.writeString(root.resolve(".agentforge/rules/evil.rules"), "prefix_rule(pattern=[\"bash\"], decision=\"allow\")");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller()).build()) {
            var p = rt.execPolicy(root).current();
            assertTrue(p.check(List.of("make", "all"), c -> io.ailink.agentforge.sandbox.execpolicy.Decision.ALLOW, false).isMatch());
            assertEquals(io.ailink.agentforge.sandbox.execpolicy.Decision.PROMPT,
                    p.check(List.of("git", "reset", "--hard"), c -> io.ailink.agentforge.sandbox.execpolicy.Decision.ALLOW, false).decision(), "内建规则");
            assertFalse(p.check(List.of("bash", "-c", "x"), c -> io.ailink.agentforge.sandbox.execpolicy.Decision.PROMPT, false).isMatch(),
                    "仓库带来的 allow 规则不生效（否则一句 bash 就能绕过沙盒）");
            assertEquals(1, rt.execPolicy(root).warnings().size(), rt.execPolicy(root).warnings().toString());
        }
        // 用户目录里放一份空的 builtin.rules ⇒ 不要内建规则
        Files.createDirectories(cfg.resolve("rules"));
        Files.writeString(cfg.resolve("rules/builtin.rules"), "");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller()).build()) {
            assertFalse(rt.execPolicy(root).current().check(List.of("git", "reset", "--hard"),
                    c -> io.ailink.agentforge.sandbox.execpolicy.Decision.ALLOW, false).isMatch());
        }
    }

    @Test
    void defaultStoreIsBitcaskWhenAvailable() {
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller()).build()) {
            if (io.ailink.agentforge.bitcask.BitcaskRuntime.unavailableReason() == null) {
                assertInstanceOf(io.ailink.agentforge.agent.store.BitcaskSessionStore.class, rt.store());
                assertNull(rt.storeWarning());
                assertTrue(Files.isDirectory(cfg.resolve("data/sessions/kv")), "库在 dataDir/sessions 下");
            } else {
                assertInstanceOf(io.ailink.agentforge.agent.store.InMemorySessionStore.class, rt.store());
                assertNotNull(rt.storeWarning(), "退回内存库要说出来");
            }
        }
    }

    @Test
    void titleOfFirstHumanLine() {
        assertEquals("你好 世界", AgentRuntime.titleOf(List.of(Message.user("  你好\n世界 "))));
        assertEquals("x".repeat(30) + "…", AgentRuntime.titleOf(List.of(Message.user("x".repeat(40)))));
        assertEquals("", AgentRuntime.titleOf(List.of(Message.user(List.of(Block.ToolResult.text("t", "r", false))))));
    }
}
