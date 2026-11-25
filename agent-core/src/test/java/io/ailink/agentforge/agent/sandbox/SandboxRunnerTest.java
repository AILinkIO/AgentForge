package io.ailink.agentforge.agent.sandbox;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.runtime.RuntimeFixture;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.FakeCaller;
import io.ailink.agentforge.agent.session.Recorder;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.store.InMemorySessionStore;
import io.ailink.agentforge.agent.tools.shell.ShellSettings;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** run-command 的命令审批 + 沙盒编排；带 bwrap 的几条只在有可用平台沙盒的 Linux 上跑。 */
class SandboxRunnerTest {
    static final Duration T = Duration.ofSeconds(30);

    @TempDir Path cfg;
    @TempDir Path root;
    Path outside;

    @AfterEach
    void cleanup() throws IOException {
        AgentPaths.override(null);
        if (outside != null) {
            try (var w = Files.walk(outside)) {
                for (Path p : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    /** 工作根之外、又不在 /tmp 下的目录（workspace-write 默认把 /tmp 设成可写）。 */
    private Path outsideDir() throws IOException {
        outside = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".af-sbx-test-");
        return outside;
    }

    @Test
    void parsesModes() {
        assertEquals(SandboxMode.DANGER_FULL_ACCESS, SandboxMode.parse("off"));
        assertEquals(SandboxMode.READ_ONLY, SandboxMode.parse("READ_ONLY"));
        assertEquals(SandboxMode.WORKSPACE_WRITE, SandboxMode.parse(" workspace-write "));
        assertThrows(IllegalArgumentException.class, () -> SandboxMode.parse("maybe"));
    }

    @Test
    void rewritesTheCwdTailAndTakesItBack() throws IOException {
        Path f = root.resolve("cwd file.txt");
        String wrapped = "cd sub; _code=$?; pwd -P >| '" + f + "'; exit $_code";
        SandboxedCommandRunner.Rewritten rw = SandboxedCommandRunner.rewrite(List.of("/bin/sh", "-c", wrapped));
        assertEquals(f, rw.cwdFile());
        assertTrue(rw.argv().get(2).startsWith("cd sub; _code=$?; printf"));
        assertFalse(rw.argv().get(2).contains(">|"), "沙盒里不再写 cwd 文件");
        String out = SandboxedCommandRunner.takeCwd("hello\n\n" + SandboxedCommandRunner.MARK + "/a/b\n", f);
        assertEquals("hello\n", out);
        assertEquals("/a/b", Files.readString(f).strip());
        assertNull(SandboxedCommandRunner.rewrite(List.of("cmd", "/c", "dir")).cwdFile(), "认不出就原样");
    }

    private static String toolText(Recorder rec, String id) {
        return rec.of(SessionEvent.ToolResult.class).stream().filter(r -> r.toolUseId().equals(id)).findFirst().orElseThrow()
                .content().stream().filter(b -> b instanceof Block.Text).map(b -> ((Block.Text) b).text()).collect(Collectors.joining());
    }

    private AgentRuntime runtime(FakeCaller fc, AgentRuntime.Builder extra) {
        return extra.store(new InMemorySessionStore()).build();
    }

    private AgentRuntime.Builder base(FakeCaller fc) {
        return RuntimeFixture.builder(cfg, root, fc);
    }

    /** 没有平台沙盒：已知安全的命令直接跑，未知命令先问；批了才在本机跑。 */
    @Test
    void withoutASandboxBackendUnknownCommandsAreAskedFirst() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux() || Platform.isMacOS(), "POSIX shell");
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "ls")
                .thenTool("t2", "run-command", "command", "printf made > m.txt")
                .thenTool("t3", "run-command", "command", "printf again > n.txt")
                .thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc).commandSandbox(CommandSandbox.none("测试：不用平台沙盒")))) {
            AgentSession s = rt.newSession();
            assertTrue(ShellSettings.of(s.workspace()).runner().approvesCommands());
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("跑");
            SessionEvent.ApprovalRequested g1 = rec.await(SessionEvent.ApprovalRequested.class, T);
            assertEquals("t2", g1.request().toolUseId(), "ls 是已知安全的，不问");
            assertEquals(ApprovalRequest.Kind.EXEC, g1.request().kind());
            assertEquals(List.of("bash", "-lc", "printf made > m.txt"), g1.request().command());
            s.resolveApproval(ReviewDecision.approved());
            SessionEvent.ApprovalRequested g2 = rec.await(SessionEvent.ApprovalRequested.class, e -> e != g1, T);
            s.resolveApproval(ReviewDecision.denied("先别写 n.txt"));
            assertTrue(s.awaitIdle(T));
            assertEquals("made", Files.readString(root.resolve("m.txt")));
            assertFalse(Files.exists(root.resolve("n.txt")));
            String t3 = toolText(rec, "t3");
            assertTrue(t3.contains("没有执行") && t3.contains("先别写 n.txt"), t3);
            assertNotNull(g2);
        }
    }

    /** on-request（默认）+ bwrap：根里能写、cd 记得住；写到根外被沙盒拦下但不问；模型带 require_escalated 重新申请 ⇒ 问，批了才在沙盒外跑。 */
    @Test
    void onRequestReportsDenialAndEscalatesOnlyWhenAsked() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux(), "只在 Linux 上测");
        Path target = outsideDir().resolve("e.txt");
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "mkdir -p sub && cd sub && echo hi > in.txt && cat in.txt")
                .thenTool("t2", "run-command", "command", "pwd")
                .thenTool("t3", "run-command", "command", "echo x > '" + target + "'")
                .thenTool("t4", "run-command", "command", "echo y > '" + target + "'", "sandbox_permissions", "require_escalated",
                        "justification", "要把结果写到家目录", "prefix_rule", List.of("echo", "y"))
                .thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc))) {
            Assumptions.assumeTrue(rt.sandbox().active(), "本机没有可用的平台沙盒：" + rt.sandboxStatus());
            AgentSession s = rt.newSession();
            assertInstanceOf(SandboxedCommandRunner.class, ShellSettings.of(s.workspace()).runner());
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("跑几条命令");
            SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
            assertEquals("t4", g.request().toolUseId(), "前三条都不问");
            assertEquals(ApprovalRequest.Kind.EXEC, g.request().kind());
            assertEquals("要把结果写到家目录", g.request().reason(), "模型的理由摆给用户");
            s.resolveApproval(ReviewDecision.approved());
            assertTrue(s.awaitIdle(T));

            assertTrue(toolText(rec, "t1").contains("hi"), toolText(rec, "t1"));
            assertTrue(Files.exists(root.resolve("sub/in.txt")), "工作根里能写");
            assertTrue(toolText(rec, "t2").contains(root.toRealPath().resolve("sub").toString()), "cd 记得住：" + toolText(rec, "t2"));
            assertFalse(toolText(rec, "t2").contains(SandboxedCommandRunner.MARK), "标记行不漏给模型");
            assertTrue(toolText(rec, "t3").contains("require_escalated"), "告诉模型怎么申请：" + toolText(rec, "t3"));
            assertEquals("y", Files.readString(target).strip(), "批了才在沙盒外跑");
            assertEquals(1, rec.of(SessionEvent.ApprovalRequested.class).size());
        }
    }

    /** on-failure + bwrap：不事先问；沙盒拦下后问「不带沙盒重跑吗」（带沙盒里那次的输出），拒了不跑、批了才跑。 */
    @Test
    void onFailureAsksToRetryWithoutTheSandbox() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux(), "只在 Linux 上测");
        Path target = outsideDir().resolve("e.txt");
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "echo x > '" + target + "'")
                .thenTool("t2", "run-command", "command", "echo y > '" + target + "'")
                .thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc).approvalPolicy(ApprovalPolicy.ON_FAILURE))) {
            Assumptions.assumeTrue(rt.sandbox().active(), "本机没有可用的平台沙盒：" + rt.sandboxStatus());
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("跑");
            SessionEvent.ApprovalRequested g1 = rec.await(SessionEvent.ApprovalRequested.class, T);
            assertEquals(ApprovalRequest.Kind.EXEC_RETRY, g1.request().kind());
            assertTrue(g1.request().isRetry());
            assertNotNull(g1.request().preview(), "给出沙盒里那一次的输出");
            s.resolveApproval(ReviewDecision.denied());
            SessionEvent.ApprovalRequested g2 = rec.await(SessionEvent.ApprovalRequested.class, e -> e != g1, T);
            assertEquals(ApprovalRequest.Kind.EXEC_RETRY, g2.request().kind());
            s.resolveApproval(ReviewDecision.approvedForSession());
            assertTrue(s.awaitIdle(T));
            assertTrue(toolText(rec, "t1").contains("[沙盒]"), toolText(rec, "t1"));
            assertTrue(toolText(rec, "t2").contains("沙盒外重跑"), toolText(rec, "t2"));
            assertEquals("y", Files.readString(target).strip());
        }
    }

    /** 只读预设：写被沙盒拦下（on-request 不问）；后台任务直接拒。 */
    @Test
    void readOnlySandboxRefusesWritesAndBackgroundTasks() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux(), "只在 Linux 上测");
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "echo hi > f.txt")
                .thenTool("t2", "run-command", "command", "sleep 1", "run-in-background", true)
                .thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc).permissions(ApprovalPreset.READ_ONLY.settings()))) {
            Assumptions.assumeTrue(rt.sandbox().active(), "本机没有可用的平台沙盒：" + rt.sandboxStatus());
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("试试");
            assertTrue(s.awaitIdle(T));
            assertTrue(rec.of(SessionEvent.ApprovalRequested.class).isEmpty());
            assertFalse(Files.exists(root.resolve("f.txt")), "只读沙盒里写不了");
            assertTrue(toolText(rec, "t1").contains("[沙盒]"), toolText(rec, "t1"));
            assertTrue(toolText(rec, "t2").contains("只读沙盒"), toolText(rec, "t2"));
        }
    }

    /** 完全放开：不进沙盒，写哪儿都不问。 */
    @Test
    void fullAccessRunsUnsandboxedWithoutAsking() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux() || Platform.isMacOS(), "POSIX shell");
        Path target = outsideDir().resolve("f.txt");
        FakeCaller fc = new FakeCaller().thenTool("t1", "run-command", "command", "echo z > '" + target + "'").thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc).permissions(ApprovalPreset.FULL_ACCESS.settings()))) {
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("写");
            assertTrue(s.awaitIdle(T));
            assertTrue(rec.of(SessionEvent.ApprovalRequested.class).isEmpty());
            assertEquals("z", Files.readString(target).strip());
        }
    }

    /** never 下申请出沙盒：直接回绝（Codex 同一句），规则 prompt 的命令也直接拒。 */
    @Test
    void neverRejectsEscalationAndPromptRules() throws Exception {
        Assumptions.assumeTrue(Platform.isLinux() || Platform.isMacOS(), "POSIX shell");
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "run-command", "command", "echo a", "sandbox_permissions", "require_escalated")
                .thenTool("t2", "run-command", "command", "git reset --hard")
                .thenText("好");
        try (AgentRuntime rt = runtime(fc, base(fc).approvalPolicy(ApprovalPolicy.NEVER).commandSandbox(CommandSandbox.none(null)))) {
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("跑");
            assertTrue(s.awaitIdle(T));
            assertTrue(rec.of(SessionEvent.ApprovalRequested.class).isEmpty());
            assertTrue(toolText(rec, "t1").contains("没有执行"), toolText(rec, "t1"));
            assertTrue(toolText(rec, "t2").contains("没有执行"), "内建规则要求审批、never 下直接拒：" + toolText(rec, "t2"));
        }
    }
}
