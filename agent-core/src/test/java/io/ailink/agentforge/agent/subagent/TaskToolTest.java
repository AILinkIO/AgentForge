package io.ailink.agentforge.agent.subagent;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.runtime.RuntimeFixture;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.FakeCaller;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.agent.session.Recorder;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.transcript.SubRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolTest {
    static final Duration T = Duration.ofSeconds(10);

    @TempDir Path cfg;
    @TempDir Path root;

    @AfterEach
    void reset() { AgentPaths.override(null); }

    static boolean answered(ChatRequest req) { return req.messages().getLast().hasToolResults(); }

    static String resultText(Message m) {
        StringBuilder sb = new StringBuilder();
        for (Block.ToolResult r : m.toolResults()) sb.append(Block.joinText(r.content()));
        return sb.toString();
    }

    /** deep 子 agent：先读 a.txt，再报结论。 */
    static FakeCaller.Reply deepChild(ChatRequest req) {
        if (req.system() == null || !req.system().contains(Prompts.DEEP_SYSTEM_PROMPT)) return null;
        return answered(req) ? (q, d, c) -> FakeCaller.text("在 a.txt 里找到了 hello")
                : (q, d, c) -> FakeCaller.tool("c1", "read-file", "path", "a.txt");
    }

    @Test
    void dispatchesReadOnlyWorkToAChildSession() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello\n");
        FakeCaller fc = new FakeCaller().route(TaskToolTest::deepChild)
                .thenTool("t1", "task", "description", "找 hello", "prompt", "在仓库里找 hello 在哪个文件", "subagent_type", "deep")
                .thenText("子 agent 说在 a.txt");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("hello 在哪");
            assertTrue(s.awaitIdle(T));
            String result = resultText(s.history().get(2));
            assertTrue(result.contains("在 a.txt 里找到了 hello"), result);
            assertTrue(result.contains("任务 id：t1"));
            // 记录：一趟、派给 deep、步骤里有 read-file
            List<SubRun> runs = s.subLog();
            assertEquals(1, runs.size());
            assertEquals("completed", runs.getFirst().status());
            assertEquals("deep", runs.getFirst().who());
            assertEquals("read-file", runs.getFirst().allSteps().getFirst().name());
            assertEquals(1, rec.of(SessionEvent.SubStart.class).size());
            assertEquals(1, rec.of(SessionEvent.SubFinish.class).size());
            assertFalse(rec.of(SessionEvent.SubStep.class).isEmpty());
            // 子会话：认爹、有自己的历史、拿不到 task（深度 1）
            AgentSession child = s.child("t1");
            assertNotNull(child);
            assertEquals(s.id(), child.parentId());
            assertEquals(1, child.depth());
            assertEquals(4, child.history().size());
            assertTrue(child.tools().stream().noneMatch(t -> t.name().equals("task")));
            assertNotSame(s.workspace(), child.workspace());
            // 用量：主会话两次 + 子会话两次
            assertEquals(400, s.usage().input());
            // 记录区的 task 那一条带着子 agent 的读数
            assertTrue(s.transcript().stream().anyMatch(e -> "t1".equals(e.meta("group")) && "deep".equals(e.meta("sub-who"))));
            // 存盘：子会话挂在父对话底下
            Long conv = rt.conversationOf(s);
            assertEquals(1, rt.store().children(conv).size());
        }
    }

    @Test
    void depthLimitAndUnknownAgentAreReportedToTheModel() throws Exception {
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "task", "description", "x", "prompt", "y", "subagent_type", "nobody")
                .thenText("好吧");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            s.send("派");
            assertTrue(s.awaitIdle(T));
            String r = resultText(s.history().get(2));
            assertTrue(r.contains("没有叫 \"nobody\" 的子 agent"), r);
            assertTrue(s.history().get(2).toolResults().getFirst().error());

            rt.setSubagentsEnabled(false);
            fc.thenTool("t2", "task", "description", "x", "prompt", "y", "subagent_type", "deep").thenText("自己做");
            s.send("再派");
            assertTrue(s.awaitIdle(T));
            assertTrue(resultText(s.history().get(6)).contains("功能被关掉了"));
        }
    }

    @Test
    void visionNeedsImages() throws Exception {
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "task", "description", "看图", "prompt", "看看", "subagent_type", "vision")
                .thenText("好");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            s.send("看图");
            assertTrue(s.awaitIdle(T));
            assertTrue(resultText(s.history().get(2)).contains("必须在 images 里给出图片路径"));
        }
    }

    @Test
    void writingChildAsksTheParent() throws Exception {
        String juniorPrompt = Prompts.juniorSystemPrompt(Prompts.Host.CLI);
        FakeCaller fc = new FakeCaller().route(req -> {
            if (req.system() == null || !req.system().contains(juniorPrompt)) return null;
            return answered(req) ? (q, d, c) -> FakeCaller.text("写好了 j.txt")
                    : (q, d, c) -> FakeCaller.tool("c1", "write-file", "path", "j.txt", "content", "jj");
        }).thenTool("t1", "task", "description", "写 j", "prompt", "新建 j.txt 内容 jj", "subagent_type", "junior")
          .thenText("完成");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).approvalPolicy(ApprovalPolicy.UNLESS_TRUSTED).build()) {
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("让 junior 写");
            SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
            assertEquals("write-file", g.request().toolName());
            assertEquals(ApprovalRequest.Kind.PATCH, g.request().kind());
            assertEquals("t1", g.request().session(), "审批摆在父会话上，标着是哪段子会话问的");
            assertSame(g.request(), s.pendingApproval());
            s.resolveApproval(ReviewDecision.approved());
            assertTrue(s.awaitIdle(T));
            assertEquals("jj", Files.readString(root.resolve("j.txt")));
            assertTrue(resultText(s.history().get(2)).contains("写好了 j.txt"));
        }
    }

    @Test
    void backgroundRunWakesTheParentWithItsConclusion() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello\n");
        FakeCaller fc = new FakeCaller().route(req -> {
            FakeCaller.Reply r = deepChild(req);
            if (r != null) return r;
            return FakeCaller.lastUserText(req).contains("后台任务 t1") ? (q, d, c) -> FakeCaller.text("收到后台结论") : null;
        }).thenTool("t1", "task", "description", "后台找", "prompt", "找 hello", "subagent_type", "deep", "background", true)
          .thenText("先干别的");
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, fc).build()) {
            AgentSession s = rt.newSession();
            Recorder rec = new Recorder();
            s.addListener(rec);
            s.send("后台查一下");
            rec.await(SessionEvent.Finished.class, e -> "收到后台结论".equals(e.result().text()), T);
            assertTrue(s.awaitIdle(T));
            assertTrue(resultText(s.history().get(2)).contains("已经放到后台跑了"));
            assertTrue(s.subLog().getFirst().background());
            assertTrue(s.history().stream().anyMatch(m -> m.text().contains("在 a.txt 里找到了 hello")));
        }
    }

    @Test
    void schemaListsDispatchableAgents() {
        try (AgentRuntime rt = RuntimeFixture.builder(cfg, root, new FakeCaller()).build()) {
            AgentTool t = TaskTool.create(rt.subagentHost());
            var en = t.schema().path("properties").path("subagent_type").path("enum");
            assertEquals("junior", en.get(0).asString());
            assertTrue(t.schema().path("required").toString().contains("subagent_type"));
            assertTrue(t.concurrencySafe());
        }
    }
}
