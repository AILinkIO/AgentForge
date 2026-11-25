package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.agent.loop.GateDecision;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.agent.session.SessionConfig;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Deletion;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import io.ailink.agentforge.agent.tools.shell.CommandRunner;
import io.ailink.agentforge.agent.tools.shell.ShellSettings;
import io.ailink.agentforge.agent.tools.shell.ShellTools;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.execpolicy.ExecPolicyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 各类工具调用的审批判定（不跑工具，只看 gate 的决定与摆出来的请求）。 */
class ToolApprovalsTest {
    @TempDir Path root;
    @TempDir Path cfg;

    Workspace ws;
    SessionPermissions perms;
    final Deque<ReviewDecision> answers = new ArrayDeque<>();
    final List<ApprovalRequest> asked = new ArrayList<>();
    final List<String> autos = new ArrayList<>();
    final List<String> amended = new ArrayList<>();

    final ToolApprovals.Host host = new ToolApprovals.Host() {
        @Override public ReviewDecision ask(ApprovalRequest r) {
            asked.add(r);
            ReviewDecision d = answers.poll();
            return d == null ? ReviewDecision.abort() : d;
        }

        @Override public void auto(String toolName, String summary, boolean allowed, String why) { autos.add(toolName + ":" + why); }

        @Override public void amended(String description) { amended.add(description); }

        @Override public String sessionId() { return "s1"; }
    };

    @BeforeEach
    void setUp() {
        ws = new Workspace(root);
        perms = new SessionPermissions(PermissionSettings.DEFAULT,
                new ExecPolicyManager(null, null, List.of(cfg.resolve("rules")), cfg.resolve("rules/default.rules")), true);
    }

    private void settings(ApprovalPolicy ap, SandboxMode m) { perms.setSettings(PermissionSettings.of(ap, m)); }

    private static ObjectNode in(Object... kv) {
        ObjectNode o = AgentTool.JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) o.set((String) kv[i], AgentTool.JSON.valueToTree(kv[i + 1]));
        return o;
    }

    private static AgentTool tool(String name) {
        for (AgentTool t : FileToolset.tools()) if (t.name().equals(name)) return t;
        if (name.equals("run-command")) return ShellTools.runCommand();
        return AgentTool.builder(name).description(name).sensitive().handler((e, a) -> "ok").build();
    }

    private GateDecision decide(String name, Object... kv) {
        return decide(SessionConfig.defaults(), name, kv);
    }

    private GateDecision decide(SessionConfig cfg, String name, Object... kv) {
        return ToolApprovals.decide(host, perms, ws, cfg, new Block.ToolUse("t" + asked.size(), name, in(kv)), tool(name));
    }

    // ── 写文件 ───────────────────────────────────────────────────────

    @Test
    void writesFollowTheSandboxPolicy() {
        assertEquals(GateDecision.ALLOW, decide("write-file", "path", "a.txt", "content", "x"), "auto：工作根里直接写");
        assertTrue(asked.isEmpty());
        answers.add(ReviewDecision.approvedForSession());
        assertEquals(GateDecision.ALLOW, decide("write-file", "path", ".git/hooks/pre-commit", "content", "x"));
        ApprovalRequest r = asked.getFirst();
        assertEquals(ApprovalRequest.Kind.PATCH, r.kind());
        assertTrue(r.reason().contains(".git/hooks/pre-commit"), r.reason());
        assertNotNull(r.preview());
        assertEquals("s1", r.session());
        assertFalse(r.allows(ReviewDecision.Kind.APPROVED_ALWAYS), "文件没有「以后都」");
        assertEquals(GateDecision.ALLOW, decide("edit-file", "path", ".git/hooks/pre-commit", "old", "x", "new", "y"));
        assertEquals(1, asked.size(), "本会话批过这个文件");
        assertEquals(1, autos.size());
        // never ⇒ 拒（越界的理由）
        settings(ApprovalPolicy.NEVER, SandboxMode.WORKSPACE_WRITE);
        GateDecision d = decide("write-file", "path", ".agentforge/rules/x.rules", "content", "x");
        assertInstanceOf(GateDecision.Deny.class, d, "宿主元数据目录不存在也受保护");
        // 只读 ⇒ 问；完全放开 ⇒ 放
        settings(ApprovalPolicy.ON_REQUEST, SandboxMode.READ_ONLY);
        answers.add(ReviewDecision.denied("别写"));
        GateDecision ro = decide("write-file", "path", "b.txt", "content", "x");
        assertTrue(((GateDecision.Deny) ro).reason().contains("别写"));
        settings(ApprovalPolicy.NEVER, SandboxMode.DANGER_FULL_ACCESS);
        assertEquals(GateDecision.ALLOW, decide("write-file", "path", ".git/config", "content", "x"));
        // untrusted ⇒ 每次都问；停下 ⇒ STOP
        settings(ApprovalPolicy.UNLESS_TRUSTED, SandboxMode.WORKSPACE_WRITE);
        answers.add(ReviewDecision.abort());
        assertEquals(GateDecision.STOP, decide("write-file", "path", "c.txt", "content", "x"));
    }

    @Test
    void stagingDefersWritesThatWouldAsk() {
        settings(ApprovalPolicy.UNLESS_TRUSTED, SandboxMode.WORKSPACE_WRITE);
        assertEquals(GateDecision.ALLOW, decide(SessionConfig.defaults().staging(true), "write-file", "path", "a.txt", "content", "x"));
        assertTrue(asked.isEmpty(), "攒着，整轮结束一次过目");
        settings(ApprovalPolicy.NEVER, SandboxMode.READ_ONLY);
        assertInstanceOf(GateDecision.Deny.class, decide(SessionConfig.defaults().staging(true), "write-file", "path", "a.txt", "content", "x"),
                "该拒的照样拒，不攒");
    }

    @Test
    void applyPatchJudgesEveryTargetIncludingMoves() {
        String patch = "*** Begin Patch\n*** Add File: ok.txt\n+x\n*** Update File: a.txt\n*** Move to: .git/a.txt\n@@\n-a\n+b\n*** End Patch";
        answers.add(ReviewDecision.approved());
        assertEquals(GateDecision.ALLOW, decide("apply-patch", "patchText", patch));
        assertEquals(1, asked.size(), "移到 .git 下要问");
        assertTrue(asked.getFirst().reason().contains(".git/a.txt"));
        assertEquals(GateDecision.ALLOW, decide("apply-patch", "patchText", "*** Begin Patch\n*** Add File: ok2.txt\n+x\n*** End Patch"));
        assertEquals(1, asked.size());
        assertEquals(GateDecision.ALLOW, decide("apply-patch", "patchText", "不是补丁"), "格式不对交给工具自己报");
    }

    @Test
    void deletePathResolvesSymlinksAndEscapes() throws Exception {
        // 根外、又不在 /tmp（workspace-write 下 /tmp 可写）
        Path outside = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".af-approval-test-");
        try {
            deleteChecks(outside);
        } finally {
            Files.deleteIfExists(outside.resolve("f.txt"));
            Files.deleteIfExists(outside);
        }
    }

    private void deleteChecks(Path outside) throws Exception {
        Files.writeString(outside.resolve("f.txt"), "x");
        Files.createSymbolicLink(root.resolve("link"), outside);
        // 软链：只删链接本身（在根里）⇒ 不问，放确认格
        assertEquals(GateDecision.ALLOW, decide("delete-path", "path", "link"));
        assertTrue(asked.isEmpty());
        Deletion.Confirmed c = Deletion.of(ws).take();
        assertEquals("link", c.origPath());
        assertEquals(root.resolve("link").toString(), c.target());
        // 经软链落到根外 ⇒ 问；批了按真实目标确认
        answers.add(ReviewDecision.approved());
        assertEquals(GateDecision.ALLOW, decide("delete-path", "path", "link/f.txt"));
        assertEquals(ApprovalRequest.Kind.PATCH, asked.getFirst().kind());
        assertTrue(asked.getFirst().title().contains("→"), asked.getFirst().title());
        assertEquals(outside.toRealPath().resolve("f.txt").toString(), Deletion.of(ws).take().target());
    }

    // ── 密钥文件 ─────────────────────────────────────────────────────

    @Test
    void secretFilesAreAskedAboutBeforeAnythingElse() {
        answers.add(ReviewDecision.approvedForSession());
        assertEquals(GateDecision.ALLOW, decide("read-file", "path", "config/.env"));
        assertEquals(ApprovalRequest.Kind.SECRET, asked.getFirst().kind());
        assertEquals(GateDecision.ALLOW, decide("read-file", "path", "config/.env"));
        assertEquals(1, asked.size(), "本会话批过");
        assertEquals(GateDecision.ALLOW, decide("read-file", "path", ".env.example"), "模板不算");
        assertEquals(1, asked.size());
        settings(ApprovalPolicy.NEVER, SandboxMode.DANGER_FULL_ACCESS);
        assertInstanceOf(GateDecision.Deny.class, decide("search-text", "pattern", "KEY", "path", ".env.local"));
    }

    @Test
    void secretFileDetection() {
        assertTrue(SecretFiles.mentions("path=.env"));
        assertTrue(SecretFiles.mentions("path=app/.env.production limit=20"));
        assertTrue(SecretFiles.mentions("command=cat .env"));
        assertTrue(SecretFiles.mentions("command=cat '.env';"));
        assertFalse(SecretFiles.mentions("path=.env.example"));
        assertFalse(SecretFiles.mentions("path=.env.sample"));
        assertFalse(SecretFiles.mentions("path=.envrc"));
        assertFalse(SecretFiles.mentions("path=environment.txt"));
        assertFalse(SecretFiles.mentions(null));
    }

    // ── 网络 ─────────────────────────────────────────────────────────

    @Test
    void networkHostsFollowTheSandboxSwitchAndRules() throws Exception {
        answers.add(ReviewDecision.approvedAlways());
        assertEquals(GateDecision.ALLOW, decide("web-fetch", "url", "https://Docs.Example.com/a"));
        ApprovalRequest r = asked.getFirst();
        assertEquals(ApprovalRequest.Kind.NETWORK, r.kind());
        assertEquals("docs.example.com", r.host());
        assertEquals(new Amendment.NetworkHost("docs.example.com"), r.amendment());
        assertTrue(Files.readString(cfg.resolve("rules/default.rules")).contains("network_rule(host=\"docs.example.com\", protocol=\"https\", decision=\"allow\")"));
        assertEquals(GateDecision.ALLOW, decide("web-fetch", "url", "https://docs.example.com/b"));
        assertEquals(1, asked.size(), "规则放行了");
        answers.add(ReviewDecision.deniedAlways());
        assertInstanceOf(GateDecision.Deny.class, decide("web-fetch", "url", "http://evil.example.net/"));
        assertInstanceOf(GateDecision.Deny.class, decide("web-fetch", "url", "http://evil.example.net/again"));
        assertEquals(2, asked.size(), "拉黑之后不再问");
        answers.add(ReviewDecision.approvedForSession());
        assertEquals(GateDecision.ALLOW, decide("web-fetch", "url", "https://a.example.org/"));
        assertEquals(GateDecision.ALLOW, decide("web-fetch", "url", "https://a.example.org/x"));
        assertEquals(3, asked.size());
        // 网络开着 ⇒ 不问（拉黑的照样拒）
        perms.setSettings(PermissionSettings.DEFAULT.withNetworkAccess(true));
        assertEquals(GateDecision.ALLOW, decide("web-fetch", "url", "https://new.example.org/"));
        assertInstanceOf(GateDecision.Deny.class, decide("web-fetch", "url", "http://evil.example.net/"));
        assertEquals(3, asked.size());
        // never 且断网 ⇒ 拒
        settings(ApprovalPolicy.NEVER, SandboxMode.WORKSPACE_WRITE);
        assertInstanceOf(GateDecision.Deny.class, decide("web-search", "query", "x"));
    }

    // ── 命令 ─────────────────────────────────────────────────────────

    @Test
    void commandsWithANonApprovingRunnerAreJudgedAtTheGate() throws Exception {
        settings(ApprovalPolicy.UNLESS_TRUSTED, SandboxMode.WORKSPACE_WRITE);
        assertEquals(GateDecision.ALLOW, decide("run-command", "command", "ls -la"), "已知安全");
        answers.add(ReviewDecision.approvedAlways());
        assertEquals(GateDecision.ALLOW, decide("run-command", "command", "make test"));
        ApprovalRequest r = asked.getFirst();
        assertEquals(ApprovalRequest.Kind.EXEC, r.kind());
        assertEquals(new Amendment.PrefixRule(List.of("make", "test")), r.amendment());
        assertTrue(r.allows(ReviewDecision.Kind.APPROVED_ALWAYS));
        assertTrue(Files.readString(cfg.resolve("rules/default.rules")).contains("prefix_rule(pattern=[\"make\", \"test\"], decision=\"allow\")"));
        assertEquals(GateDecision.ALLOW, decide("run-command", "command", "make test"));
        assertEquals(1, asked.size(), "规则放行了");
        settings(ApprovalPolicy.NEVER, SandboxMode.WORKSPACE_WRITE);
        GateDecision d = decide("run-command", "command", "rm -rf /");
        assertTrue(((GateDecision.Deny) d).reason().contains("rm -rf /"), ((GateDecision.Deny) d).reason());
    }

    @Test
    void approvingRunnersAreLeftToDoTheirOwnApprovals() {
        settings(ApprovalPolicy.UNLESS_TRUSTED, SandboxMode.WORKSPACE_WRITE);
        ShellSettings.of(ws).runner(new CommandRunner() {
            @Override public Result run(Request req) { return null; }

            @Override public Background spawn(String c, List<String> a, Path cwd) { return null; }

            @Override public boolean approvesCommands() { return true; }
        });
        assertEquals(GateDecision.ALLOW, decide("run-command", "command", "make test"));
        assertTrue(asked.isEmpty());
    }

    // ── 其他工具 ─────────────────────────────────────────────────────

    @Test
    void otherSensitiveToolsFollowCodexMcpApprovals() {
        List<String> remembered = new ArrayList<>();
        perms.onAlwaysTool(remembered::add);
        answers.add(ReviewDecision.approvedAlways());
        assertEquals(GateDecision.ALLOW, decide("mcp.fs.write", "path", "x"));
        assertEquals(ApprovalRequest.Kind.TOOL, asked.getFirst().kind());
        assertEquals(new Amendment.Tool("mcp.fs.write"), asked.getFirst().amendment());
        assertEquals(List.of("mcp.fs.write"), remembered);
        assertEquals(GateDecision.ALLOW, decide("mcp.fs.write", "path", "y"));
        assertEquals(1, asked.size());
        settings(ApprovalPolicy.NEVER, SandboxMode.WORKSPACE_WRITE);
        assertInstanceOf(GateDecision.Deny.class, decide("worktree-create", "name", "x"), "never 但不是全盘可写 ⇒ 拒");
        settings(ApprovalPolicy.NEVER, SandboxMode.DANGER_FULL_ACCESS);
        assertEquals(GateDecision.ALLOW, decide("worktree-create", "name", "x"));
        assertEquals(GateDecision.ALLOW, decide("worktree-enter", "name", "x"), "只换根，不问");
        assertEquals(GateDecision.ALLOW, decide("read-file", "path", "a.txt"), "只读工具直接放");
    }

    // ── 请求形状 ─────────────────────────────────────────────────────

    @Test
    void requestChoicesFollowCodexLabelsAndKeys() {
        ApprovalRequest exec = new ApprovalRequest(null, ApprovalRequest.Kind.EXEC, "s", "t", "run-command", "make", List.of("make"), null, null,
                null, null, null, new Amendment.PrefixRule(List.of("make")), null, null);
        assertEquals(List.of("y", "p", "a", "d", "n"), exec.choices().stream().map(ApprovalChoice::key).toList());
        assertEquals(ReviewDecision.Kind.APPROVED_ALWAYS, exec.choice("p").decision());
        assertTrue(exec.choice("p").label().contains("`make`"));
        ApprovalRequest net = new ApprovalRequest(null, ApprovalRequest.Kind.NETWORK, "s", "t", "web-fetch", "x", null, null, null, null, null,
                "h.example", new Amendment.NetworkHost("h.example"), null, null);
        assertTrue(net.allows(ReviewDecision.Kind.DENIED_ALWAYS));
        ApprovalRequest review = new ApprovalRequest(null, ApprovalRequest.Kind.REVIEW, "s", null, "apply-changes", "x", null, null, null, null,
                null, null, null, null, null);
        assertFalse(review.allows(ReviewDecision.Kind.APPROVED_FOR_SESSION));
        assertNotNull(review.id());
        assertNull(ReviewDecision.denied("  ").feedback());
    }

    @Test
    void presetsAndSettings() {
        assertEquals(ApprovalPreset.AUTO, PermissionSettings.DEFAULT.preset());
        assertEquals(ApprovalPreset.FULL_ACCESS, ApprovalPreset.parse("Full-Access"));
        assertNull(PermissionSettings.DEFAULT.withNetworkAccess(true).preset());
        assertTrue(ApprovalPreset.FULL_ACCESS.settings().policy(root).fullDiskWriteAccess());
        assertFalse(ApprovalPreset.READ_ONLY.settings().policy(root).fullDiskWriteAccess());
        assertTrue(PermissionSettings.DEFAULT.withWritableRoots(List.of("/srv/x")).policy(root).entries().stream()
                .anyMatch(e -> e.path().toString().contains("/srv/x")));
        assertTrue(PermissionSettings.DEFAULT.describe().contains("[auto]"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalPreset.parse("yolo"));
    }
}
