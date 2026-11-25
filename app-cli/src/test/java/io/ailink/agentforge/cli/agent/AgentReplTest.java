package io.ailink.agentforge.cli.agent;

import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.approval.Amendment;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentReplTest {

    @Test
    void parsesClarifyChoices() {
        var q = new ClarifyService.Question("选哪个", "h", List.of(new ClarifyService.Option("甲", ""), new ClarifyService.Option("乙", "")), true);
        assertEquals(List.of("甲", "乙"), AgentRepl.parseChoice("1, 2", q));
        assertEquals(List.of("自己写的"), AgentRepl.parseChoice("自己写的", q));
        assertEquals(List.of("9"), AgentRepl.parseChoice("9", q), "越界的序号当自己填的答案");
        assertEquals(List.of(), AgentRepl.parseChoice("", q), "空 = 没答");
        var single = new ClarifyService.Question("选哪个", "h", q.options(), false);
        assertEquals(List.of("甲"), AgentRepl.parseChoice("1,2", single), "单选只取第一个");
    }

    @Test
    void approvalKeysAndAnswersFollowTheRequest() {
        var exec = new ApprovalRequest(null, ApprovalRequest.Kind.EXEC, "s1", "t", "run-command", "make", List.of("bash", "-lc", "make"), null, null,
                null, null, null, new Amendment.PrefixRule(List.of("make")), null, null);
        assertEquals("y/p/a/d/n", AgentRepl.approvalKeys(exec));
        assertEquals(ReviewDecision.approved(), AgentRepl.parseAnswer(exec, "y"));
        assertEquals(ReviewDecision.approvedAlways(), AgentRepl.parseAnswer(exec, "p"));
        assertEquals(ReviewDecision.denied("先别动 build"), AgentRepl.parseAnswer(exec, "d 先别动 build"));
        assertEquals(new ReviewDecision(ReviewDecision.Kind.ABORT, "换个办法"), AgentRepl.parseAnswer(exec, "n 换个办法"));
        assertEquals(ReviewDecision.abort(), AgentRepl.parseAnswer(exec, "stop"));
        assertNull(AgentRepl.parseAnswer(exec, "b"), "不在选项里");
        assertNull(AgentRepl.parseAnswer(exec, "maybe"));
        var patch = new ApprovalRequest(null, ApprovalRequest.Kind.PATCH, "s1", "t", "write-file", "x", null, null, null, null, null, null, null,
                null, null);
        assertEquals("y/a/d/n", AgentRepl.approvalKeys(patch));
        assertNull(AgentRepl.parseAnswer(patch, "p"), "文件审批没有「以后都」");
        var net = new ApprovalRequest(null, ApprovalRequest.Kind.NETWORK, "s1", "t", "web-fetch", "x", null, null, null, null, null, "h.example",
                new Amendment.NetworkHost("h.example"), null, null);
        assertEquals(ReviewDecision.deniedAlways(), AgentRepl.parseAnswer(net, "b"));
        assertEquals("要访问网络", AgentRepl.approvalHead(net));
    }

    @Test
    void humanNumbers() {
        assertEquals("999", AgentRepl.human(999));
        assertEquals("1.2k", AgentRepl.human(1234));
        assertEquals("3.4M", AgentRepl.human(3_400_000));
    }

    @Test
    void optionsParse() {
        AgentCommand c = new AgentCommand();
        new CommandLine(c).parseArgs("--root", "/tmp", "--model", "anthropic/x", "--agent", "deep", "-s", "read-only", "-a", "untrusted",
                "--no-network", "--no-mcp");
        assertEquals("anthropic/x", c.model);
        assertEquals("deep", c.agent);
        assertTrue(c.noMcp);
        var f = c.permissionFlags();
        assertNull(f.full(), "只给单项：没给的看 prefs");
        assertEquals(SandboxMode.READ_ONLY, f.sandbox());
        assertEquals(ApprovalPolicy.UNLESS_TRUSTED, f.approval());
        assertEquals(Boolean.FALSE, f.network());
    }

    @Test
    void permissionPresetFlags() {
        AgentCommand yolo = new AgentCommand();
        new CommandLine(yolo).parseArgs("--yolo");
        assertEquals(ApprovalPreset.FULL_ACCESS.settings(), yolo.permissionFlags().full());
        AgentCommand longYolo = new AgentCommand();
        new CommandLine(longYolo).parseArgs("--dangerously-bypass-approvals-and-sandbox");
        assertTrue(longYolo.yolo);
        AgentCommand auto = new AgentCommand();
        new CommandLine(auto).parseArgs("--full-auto", "--network", "--add-dir", "/srv/data", "-a", "on-failure");
        var f = auto.permissionFlags().full();
        assertEquals(SandboxMode.WORKSPACE_WRITE, f.sandbox());
        assertEquals(ApprovalPolicy.ON_FAILURE, f.approval());
        assertTrue(f.networkAccess());
        assertEquals(List.of("/srv/data"), f.writableRoots());
        AgentCommand preset = new AgentCommand();
        new CommandLine(preset).parseArgs("--preset", "read-only");
        assertEquals(ApprovalPreset.READ_ONLY.settings(), preset.permissionFlags().full());
        AgentCommand bad = new AgentCommand();
        new CommandLine(bad).parseArgs("--yolo", "-s", "read-only");
        assertThrows(IllegalArgumentException.class, bad::permissionFlags);
        AgentCommand both = new AgentCommand();
        new CommandLine(both).parseArgs("--yolo", "--full-auto");
        assertThrows(IllegalArgumentException.class, both::permissionFlags);
        AgentCommand typo = new AgentCommand();
        new CommandLine(typo).parseArgs("-a", "sometimes");
        assertThrows(IllegalArgumentException.class, typo::permissionFlags);
    }

    @Test
    void tuiTakesTheSameFlags() {
        TuiCommand c = new TuiCommand();
        new CommandLine(c).parseArgs("--agent", "deep", "--full-auto", "--no-mcp");
        assertEquals("deep", c.agent);
        assertTrue(c.noMcp);
        assertEquals(PermissionSettings.of(ApprovalPolicy.ON_REQUEST, SandboxMode.WORKSPACE_WRITE), c.permissionFlags().full());
    }
}
