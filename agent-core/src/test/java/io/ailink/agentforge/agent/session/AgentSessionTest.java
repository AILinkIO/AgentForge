package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.clarify.Clarify;
import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.loop.AgentStatus;
import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.loop.ModelCallException;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.approval.SessionPermissions;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import io.ailink.agentforge.agent.transcript.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionTest {
    static final Duration T = Duration.ofSeconds(10);

    @TempDir Path root;

    /** {@code untrusted} = 每件会改东西的调用都问；否则缺省（auto：on-request + workspace-write）。 */
    static SessionPermissions perms(boolean untrusted) {
        return new SessionPermissions(untrusted ? PermissionSettings.of(ApprovalPolicy.UNLESS_TRUSTED, SandboxMode.WORKSPACE_WRITE)
                : PermissionSettings.DEFAULT, null, true);
    }

    AgentSession session(FakeCaller fc, boolean untrusted, List<AgentTool> tools) {
        return AgentSession.builder(ModelBackend.of(fc), new Workspace(root))
                .model("fake/m").system("sys").permissions(perms(untrusted)).tools(tools)
                .config(SessionConfig.defaults().retryInitialDelayMs(1)).build();
    }

    static List<AgentTool> files() { return FileToolset.tools(); }

    static String resultText(Message m) {
        StringBuilder sb = new StringBuilder();
        for (Block.ToolResult r : m.toolResults()) sb.append(Block.joinText(r.content()));
        return sb.toString();
    }

    @Test
    void toolUsingTurnEndToEnd() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello world\n");
        FakeCaller fc = new FakeCaller().thenTool("t1", "read-file", "path", "a.txt").thenText("文件里写的是 hello world");
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        assertEquals(AgentSession.SendResult.SENT, s.send("读一下 a.txt"));
        assertTrue(s.awaitIdle(T));
        assertEquals(SessionStatus.DONE, s.status());
        assertEquals(AgentStatus.COMPLETED, s.resultStatus());
        List<Message> h = s.history();
        assertEquals(4, h.size());
        assertTrue(resultText(h.get(2)).contains("hello world"));
        assertEquals("文件里写的是 hello world", h.get(3).text());
        assertEquals(List.of("Started", "ToolCall", "ToolResult", "Finished"), rec.names());
        // 第二次请求带着工具结果
        assertTrue(fc.requests.get(1).messages().get(2).hasToolResults());
        // 记录区：人话、调用、结果、回答
        List<Entry> es = s.transcript();
        assertEquals(Entry.Role.USER, es.get(0).role());
        assertTrue(es.get(1).isCall());
        assertTrue(es.get(1).meta("summary") != null);
        assertEquals("文件里写的是 hello world", es.getLast().text());
        // 用量累加（两次回答）；没单价 → 至少
        assertEquals(200, s.usage().input());
        assertNull(s.cost());
        assertTrue(s.costPartial());
    }

    @Test
    void runTurnBlockingReturnsResult() {
        FakeCaller fc = new FakeCaller().thenText("好");
        AgentSession s = session(fc, false, files());
        var r = s.runTurn("hi");
        assertEquals(AgentStatus.COMPLETED, r.status());
        assertEquals("好", r.text());
        assertEquals(SessionStatus.DONE, s.status());
    }

    @Test
    void toolApprovalDeniedWithFeedbackThenAlwaysRemembersTheTool() throws Exception {
        List<String> ran = java.util.Collections.synchronizedList(new ArrayList<>());
        AgentTool act = AgentTool.builder("act").description("跑一条").required("cmd", "string", "命令").sensitive()
                .handler((env, a) -> { ran.add(a.str("cmd")); return "ok " + a.str("cmd"); }).build();
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "act", "cmd", "rm -rf build")
                .thenTool("t2", "act", "cmd", "git status")
                .thenTool("t3", "act", "cmd", "git log")
                .thenText("完");
        AgentSession s = session(fc, true, List.of(act));
        List<String> remembered = new ArrayList<>();
        s.permissions().onAlwaysTool(remembered::add);
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("干");
        SessionEvent.ApprovalRequested g1 = rec.await(SessionEvent.ApprovalRequested.class, T);
        assertEquals(SessionStatus.AWAITING_APPROVAL, s.status());
        assertEquals("act", g1.request().toolName());
        assertEquals(ApprovalRequest.Kind.TOOL, g1.request().kind());
        assertTrue(g1.request().allows(ReviewDecision.Kind.APPROVED_ALWAYS), "工具审批有「以后都放行」");
        assertTrue(s.resolveApproval(ReviewDecision.denied("别删 build")));
        SessionEvent.ApprovalRequested g2 = rec.await(SessionEvent.ApprovalRequested.class, e -> "t2".equals(e.request().toolUseId()), T);
        s.resolveApproval(ReviewDecision.approvedAlways());
        assertTrue(s.awaitIdle(T));
        assertEquals(List.of("git status", "git log"), ran, "第三次不再问（以后都放行）");
        assertEquals(List.of("act"), remembered, "「以后都放行」交给宿主落盘");
        assertEquals(1, rec.of(SessionEvent.PolicyAmended.class).size());
        assertEquals(2, rec.of(SessionEvent.ApprovalRequested.class).size());
        assertEquals(1, rec.of(SessionEvent.AutoApproval.class).size());
        var denied = rec.of(SessionEvent.ToolResult.class).getFirst();
        assertEquals("denied", denied.kind());
        assertTrue(denied.error());
        assertTrue(Block.joinText(denied.content()).contains("别删 build"), "用户的话交给模型");
        assertNotNull(g2);
        // 换成完全放开：never + danger-full-access 下工具不问
        s.permissions().setSettings(ApprovalPreset.FULL_ACCESS.settings());
        AgentTool other = AgentTool.builder("other").description("另一件").sensitive().handler((env, a) -> { ran.add("other"); return "ok"; }).build();
        s.setTools(List.of(act, other));
        fc.thenTool("t4", "other").thenText("好");
        s.send("再来");
        assertTrue(s.awaitIdle(T));
        assertEquals("other", ran.getLast());
        assertEquals(2, rec.of(SessionEvent.ApprovalRequested.class).size());
    }

    @Test
    void secretFilesAreAskedAboutEvenInAutoMode() throws Exception {
        Files.writeString(root.resolve(".env"), "KEY=1\n");
        FakeCaller fc = new FakeCaller().thenTool("t1", "read-file", "path", ".env").thenText("不读了");
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("看 .env");
        SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
        assertEquals(ApprovalRequest.Kind.SECRET, g.request().kind());
        s.resolveApproval(ReviewDecision.denied());
        assertTrue(s.awaitIdle(T));
        assertFalse(resultText(s.history().get(2)).contains("KEY=1"));
        // never：不问，直接拒
        s.permissions().setSettings(ApprovalPreset.FULL_ACCESS.settings());
        fc.thenTool("t2", "read-file", "path", ".env").thenText("好");
        s.send("再看");
        assertTrue(s.awaitIdle(T));
        assertEquals(1, rec.of(SessionEvent.ApprovalRequested.class).size());
        assertTrue(resultText(s.history().get(6)).contains("密钥文件"), resultText(s.history().get(6)));
    }

    @Test
    void abortEndsTheRound() throws Exception {
        FakeCaller fc = new FakeCaller().thenTool("t1", "write-file", "path", "x.txt", "content", "one").thenText("不该到这");
        AgentSession s = session(fc, true, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("写");
        SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
        assertEquals(ApprovalRequest.Kind.PATCH, g.request().kind());
        assertNotNull(g.request().preview(), "给出改动预览");
        s.resolveApproval(ReviewDecision.abort());
        assertTrue(s.awaitIdle(T));
        assertEquals(AgentStatus.STOPPED, s.resultStatus());
        assertEquals("stopped", rec.of(SessionEvent.Incomplete.class).getFirst().reason());
        assertEquals(1, fc.requests.size());
        assertFalse(Files.exists(root.resolve("x.txt")));
    }

    @Test
    void writesInsideTheWorkspaceNeedNoApprovalInAutoMode() throws Exception {
        FakeCaller fc = new FakeCaller().thenTool("t1", "write-file", "path", "x.txt", "content", "one")
                .thenTool("t2", "write-file", "path", ".git/config", "content", "evil").thenText("好");
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("写");
        SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
        assertEquals("t2", g.request().toolUseId(), ".git 在可写根里也只读：要问");
        assertEquals("one", Files.readString(root.resolve("x.txt")), "工作根里直接写");
        s.resolveApproval(ReviewDecision.denied());
        assertTrue(s.awaitIdle(T));
        assertFalse(Files.exists(root.resolve(".git/config")));
    }

    @Test
    void interruptDuringModelCallKeepsUserMessage() throws Exception {
        FakeCaller fc = new FakeCaller().then(FakeCaller.blockUntilCancelled());
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("干活");
        while (fc.requests.isEmpty()) Thread.sleep(5);
        s.interrupt();
        rec.await(SessionEvent.Stopped.class, T);
        assertEquals(SessionStatus.IDLE, s.status());
        // 那条"被停下了"封存进记录区
        assertTrue(s.transcript().stream().anyMatch(e -> e.text().contains("这一轮被停下了")));
        // 工作线程收尾后，用户那句话留在历史里
        long deadline = System.currentTimeMillis() + 5000;
        while (s.history().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals(1, s.history().size());
        assertEquals("干活", s.history().getFirst().text());
        // 再发一句接得上
        fc.thenText("接着");
        s.send("继续");
        assertTrue(s.awaitIdle(T));
        assertEquals("接着", s.history().getLast().text());
        // 那一块钉在第一句之后、第二句之前
        List<Entry> es = s.transcript();
        int stopAt = -1, second = -1;
        for (int i = 0; i < es.size(); i++) {
            if (es.get(i).text().contains("被停下了")) stopAt = i;
            if (es.get(i).text().equals("继续")) second = i;
        }
        assertTrue(stopAt >= 0 && stopAt < second, es.toString());
    }

    @Test
    void interruptWhileApprovalPendingAnswersAbort() throws Exception {
        FakeCaller fc = new FakeCaller().thenTool("t1", "write-file", "path", "x.txt", "content", "one");
        AgentSession s = session(fc, true, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("写");
        rec.await(SessionEvent.ApprovalRequested.class, T);
        s.interrupt();
        rec.await(SessionEvent.ApprovalResolved.class, T);
        assertEquals(ReviewDecision.Kind.ABORT, rec.of(SessionEvent.ApprovalResolved.class).getFirst().decision().kind());
        assertEquals(SessionStatus.IDLE, s.status());
        assertNull(s.pendingApproval());
        assertFalse(Files.exists(root.resolve("x.txt")));
    }

    @Test
    void queuedMessageIsSteeredIntoNextRound() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AgentTool slow = AgentTool.builder("slow").description("等一下").readOnly()
                .handler((env, a) -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return "慢活做完"; }).build();
        FakeCaller fc = new FakeCaller().thenTool("t1", "slow").thenText("收到补充");
        AgentSession s = session(fc, false, List.of(slow));
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("开始");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals(AgentSession.SendResult.QUEUED, s.send("顺便看看 b"));
        assertEquals(List.of("顺便看看 b"), s.queue());
        release.countDown();
        assertTrue(s.awaitIdle(T));
        SessionEvent.Steered st = rec.await(SessionEvent.Steered.class, T);
        assertEquals("顺便看看 b", st.text());
        assertTrue(s.queue().isEmpty());
        // 第二次请求末尾就是那句排队的话
        ChatRequest second = fc.requests.get(1);
        assertEquals("顺便看看 b", second.messages().getLast().text());
        assertEquals(1, rec.of(SessionEvent.Finished.class).size(), "并进同一轮，不另起一轮");
    }

    @Test
    void queuedMessageAfterPlainAnswerStartsNextTurn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeCaller fc = new FakeCaller()
                .then((q, d, c) -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { } return FakeCaller.text("一"); })
                .thenText("二");
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("第一句");
        while (fc.requests.isEmpty()) Thread.sleep(5);
        assertEquals(AgentSession.SendResult.QUEUED, s.send("第二句"));
        assertEquals(AgentSession.SendResult.IGNORED, s.send(""));
        release.countDown();
        rec.await(SessionEvent.Finished.class, e -> e.result().text().equals("二"), T);
        assertTrue(s.awaitIdle(T));
        assertEquals(4, s.history().size());
        assertEquals("第二句", s.history().get(2).text());
    }

    @Test
    void retriesWorthyErrorsThenSucceeds() throws Exception {
        FakeCaller fc = new FakeCaller()
                .then((q, d, c) -> { throw new ModelCallException("http", 503, -1, "HTTP 503：overloaded", null); })
                .then((q, d, c) -> { throw new ModelCallException("io", 0, -1, "连不上", null); })
                .thenText("通了");
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("hi");
        assertTrue(s.awaitIdle(T));
        assertEquals(SessionStatus.DONE, s.status());
        List<SessionEvent.Retry> rs = rec.of(SessionEvent.Retry.class);
        assertEquals(2, rs.size());
        assertEquals(1, rs.get(0).attempt());
        assertEquals(3, rs.get(0).max());
        assertEquals("通了", s.history().getLast().text());
    }

    @Test
    void rateLimitIsNotRetriedAndErrorKeepsTurn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeCaller fc = new FakeCaller()
                .thenTool("t1", "list-dir", "path", ".")
                .then((q, d, c) -> {
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { }
                    throw new ModelCallException("http", 429, -1, "HTTP 429：rate limited", null);
                });
        AgentSession s = session(fc, false, files());
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("列目录");
        while (fc.requests.size() < 2) Thread.sleep(5);
        s.send("排着的");
        release.countDown();
        rec.await(SessionEvent.Error.class, T);
        assertEquals(SessionStatus.ERROR, s.status());
        assertTrue(rec.of(SessionEvent.Retry.class).isEmpty(), "429 不重试");
        // 那一轮留在历史里：人话 + 工具调用 + 结果
        assertEquals(3, s.history().size());
        assertTrue(s.history().get(2).hasToolResults());
        // 错封存进记录区；排着的话还回草稿
        assertTrue(s.transcript().stream().anyMatch(e -> e.role() == Entry.Role.SYSTEM && e.flag("error")));
        assertEquals("排着的", s.draft());
        assertTrue(s.errorLine().contains("429"));
        // 出错不锁：直接能发
        fc.thenText("好了");
        assertEquals(AgentSession.SendResult.SENT, s.send("继续"));
        assertTrue(s.awaitIdle(T));
        assertEquals(SessionStatus.DONE, s.status());
    }

    @Test
    void halfRoundIsDroppedOnError() {
        assertEquals(1, AgentSession.repairTail(List.of(Message.user("a"),
                new Message(io.ailink.agentforge.agent.model.Role.ASSISTANT,
                        List.of(new Block.ToolUse("t", "x", AgentTool.JSON.createObjectNode()))))).size());
    }

    @Test
    void clarifyQuestionBlocksUntilAnswered() throws Exception {
        FakeCaller fc = new FakeCaller()
                .then((q, d, c) -> {
                    var in = AgentTool.JSON.createObjectNode();
                    var qs = in.putArray("questions");
                    var q1 = qs.addObject().put("question", "用哪种？").put("header", "方案");
                    var os = q1.putArray("options");
                    os.addObject().put("label", "A").put("description", "甲");
                    os.addObject().put("label", "B").put("description", "乙");
                    return new io.ailink.agentforge.agent.loop.ModelTurn(new Message(io.ailink.agentforge.agent.model.Role.ASSISTANT,
                            List.of(new Block.ToolUse("q1", Clarify.NAME, in))), Usage.ZERO, "tool_use", "fake");
                })
                .thenText("按 B 做");
        List<AgentTool> tools = new ArrayList<>();
        AgentSession[] holder = new AgentSession[1];
        tools.add(Clarify.tool(req -> holder[0].askQuestion(req)));
        AgentSession s = session(fc, false, tools);
        holder[0] = s;
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("帮我选");
        SessionEvent.Question qe = rec.await(SessionEvent.Question.class, T);
        assertEquals(SessionStatus.AWAITING_QUESTION, s.status());
        assertEquals("用哪种？", qe.request().questions().getFirst().question());
        assertTrue(s.answerQuestion(new ClarifyService.Answered(List.of(List.of("B")))));
        assertTrue(s.awaitIdle(T));
        assertTrue(resultText(s.history().get(2)).contains("B"));
    }

    @Test
    void manualCompactionAppendsSummaryAndPointer() {
        FakeCaller fc = new FakeCaller().route(req -> FakeCaller.lastUserText(req).contains("交接材料")
                ? (q, d, c) -> FakeCaller.text("用户要 X；已改 a.c") : null);
        AgentSession s = session(fc, false, files());
        List<Message> h = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            h.add(Message.user("问题 " + i + " " + "很长的内容".repeat(200)));
            h.add(Message.assistant("回答 " + i + " " + "很长的回答".repeat(200)));
        }
        s.setHistory(h);
        Recorder rec = new Recorder();
        s.addListener(rec);
        assertTrue(s.compact());
        assertEquals(14, s.history().size(), "只追加两条");
        assertEquals(1, s.compactions().size());
        Compaction.Pointer p = s.compactions().getFirst();
        assertEquals(12, p.at());
        List<Message> eff = s.effectiveHistory();
        assertTrue(eff.get(0).text().contains("系统自动压缩"));
        assertTrue(eff.get(1).text().startsWith(Prompts.COMPACTION_HEADING));
        assertTrue(eff.get(1).text().contains("用户要 X"));
        assertTrue(eff.size() < 14);
        assertEquals(h.get(p.tail()), eff.get(2), "尾巴原样保留");
        assertEquals(1, rec.of(SessionEvent.Compacted.class).size());
        assertEquals(SessionStatus.IDLE, s.status());
    }

    @Test
    void contextFullStopsCompactsAndResumes() throws Exception {
        Files.writeString(root.resolve("a.txt"), "x\n");
        FakeCaller fc = new FakeCaller().route(req -> FakeCaller.lastUserText(req).contains("交接材料")
                ? (q, d, c) -> FakeCaller.text("摘要：前面在读 a.txt") : null);
        fc.then((q, d, c) -> FakeCaller.toolU(new Usage(12000, 50, 0, 0, 0), "t1", "read-file", "path", "a.txt"))
          .then((q, d, c) -> FakeCaller.text("读完了", new Usage(300, 10, 0, 0, 0)));
        AgentSession s = AgentSession.builder(ModelBackend.of(fc), new Workspace(root))
                .model("fake/m").system("sys").tools(files()).contextLimit(20000L).maxTokens(1000)
                .config(SessionConfig.defaults().retryInitialDelayMs(1)).build();
        List<Message> h = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            h.add(Message.user("旧问题 " + i + " " + "内容".repeat(600)));
            h.add(Message.assistant("旧回答 " + i + " " + "回答".repeat(600)));
        }
        s.setHistory(h);
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("读 a.txt");
        assertTrue(s.awaitIdle(T));
        assertEquals(SessionStatus.DONE, s.status());
        assertEquals(AgentStatus.COMPLETED, s.resultStatus());
        assertEquals(1, rec.of(SessionEvent.Compacted.class).size());
        assertEquals(1, s.compactions().size());
        // 续跑那一问发的是压缩后的那份：开头是通告 + 摘要
        ChatRequest resumed = fc.last();
        assertTrue(resumed.messages().get(0).text().contains("系统自动压缩"), resumed.messages().get(0).text());
        assertEquals("读完了", s.history().getLast().text());
        // 历史只增不减：通告 + 摘要追加在压缩那一刻的末尾
        assertTrue(s.history().size() >= h.size() + 5);
    }

    @Test
    void directDispatchSkipsTheModelForTheCall() throws Exception {
        Files.writeString(root.resolve("a.txt"), "hello\n");
        FakeCaller fc = new FakeCaller().thenText("总结：hello");
        AgentSession s = session(fc, false, files());
        s.setDirect(text -> text.startsWith("@read ")
                ? new AgentSession.DirectCall("read-file", AgentTool.JSON.createObjectNode().put("path", text.substring(6)))
                : null);
        s.send("@read a.txt");
        assertTrue(s.awaitIdle(T));
        List<Message> sent = fc.requests.getFirst().messages();
        assertEquals(4, sent.size());
        assertTrue(resultText(sent.get(2)).contains("hello"));
        assertEquals(Prompts.DIRECT_DISPATCH_PROMPT, sent.get(3).text());
    }

    @Test
    void atMentionAttachesFileContent() throws Exception {
        Files.writeString(root.resolve("notes.md"), "要点一\n");
        FakeCaller fc = new FakeCaller().thenText("看到了");
        AgentSession s = session(fc, false, files());
        s.send("看看 @notes.md 和 @missing.txt");
        assertTrue(s.awaitIdle(T));
        Message um = fc.requests.getFirst().messages().getLast();
        assertTrue(um.blocks().size() >= 2);
        String all = Block.joinText(um.blocks());
        assertTrue(all.contains("要点一"));
        assertTrue(all.contains("没附上：missing.txt"));
    }

    @Test
    void imagesAreForgottenAfterTheTurn() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        Files.write(root.resolve("p.png"), png);
        FakeCaller fc = new FakeCaller().thenTool("t1", "read-file", "path", "p.png").thenText("一个像素");
        AgentSession s = session(fc, false, files());
        s.send("看图");
        assertTrue(s.awaitIdle(T));
        // 第二次请求里图还在（那一轮）
        assertTrue(fc.requests.get(1).messages().get(2).toolResults().getFirst().content().stream().anyMatch(b -> b instanceof Block.Image));
        // 落地后摘掉、换成说明；画廊留着
        Block.ToolResult r = s.history().get(2).toolResults().getFirst();
        assertTrue(r.content().stream().noneMatch(b -> b instanceof Block.Image));
        assertTrue(Block.joinText(r.content()).contains(Prompts.TOOL_IMAGE_GONE_NOTE));
        assertEquals(1, s.gallery().size());
    }

    @Test
    void stagingReviewCommitsOrDiscards() throws Exception {
        FakeCaller fc = new FakeCaller().thenTool("t1", "write-file", "path", "s.txt", "content", "staged").thenText("写好了")
                .thenTool("t2", "write-file", "path", "r.txt", "content", "nope").thenText("又写了");
        AgentSession s = AgentSession.builder(ModelBackend.of(fc), new Workspace(root)).model("fake/m").tools(files())
                .permissions(perms(true)).config(SessionConfig.defaults().staging(true)).build();
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("写");
        SessionEvent.ApprovalRequested g = rec.await(SessionEvent.ApprovalRequested.class, T);
        assertEquals(ApprovalRequest.Kind.REVIEW, g.request().kind(), "可暂存的写不逐次问，整轮结束一次过目");
        assertFalse(Files.exists(root.resolve("s.txt")), "批准之前没落盘");
        assertTrue(g.request().preview().text().contains("+staged"));
        s.resolveApproval(ReviewDecision.approved());
        assertTrue(s.awaitIdle(T));
        assertEquals("staged", Files.readString(root.resolve("s.txt")));
        s.send("再写");
        rec.await(SessionEvent.ApprovalRequested.class, e -> e != g && e.request().kind() == ApprovalRequest.Kind.REVIEW, T);
        s.resolveApproval(ReviewDecision.denied("先别写 r.txt"));
        assertTrue(s.awaitIdle(T));
        assertFalse(Files.exists(root.resolve("r.txt")));
        assertEquals(1, s.notes().size(), "撤销的事排给下一轮的模型");
        assertTrue(s.notes().getFirst().contains("先别写 r.txt"));
    }

    @Test
    void repeatedFailuresGetANudge() throws Exception {
        FakeCaller fc = new FakeCaller();
        for (int i = 0; i < 3; i++) fc.thenTool("t" + i, "read-file", "path", "nope" + i + ".txt");
        fc.thenText("放弃");
        AgentSession s = session(fc, false, files());
        s.send("读");
        assertTrue(s.awaitIdle(T));
        Message third = s.history().get(6);
        assertTrue(third.text().contains("已经连着失败 3 次"), third.toString());
    }

    @Test
    void maxIterationsAsksForAWrapUp() throws Exception {
        FakeCaller fc = new FakeCaller().thenTool("t1", "list-dir", "path", ".").thenText("被截住了，做到这");
        AgentSession s = AgentSession.builder(ModelBackend.of(fc), new Workspace(root)).model("fake/m").tools(files())
                .config(SessionConfig.defaults().maxIterations(1)).build();
        Recorder rec = new Recorder();
        s.addListener(rec);
        s.send("干");
        assertTrue(s.awaitIdle(T));
        assertEquals(AgentStatus.MAX_ITERATIONS, s.resultStatus());
        assertTrue(fc.last().tools().isEmpty(), "收尾那一问不给工具");
        assertEquals("max-iterations", rec.of(SessionEvent.Incomplete.class).getFirst().reason());
    }

    @Test
    void resetClearsEverything() throws Exception {
        FakeCaller fc = new FakeCaller().thenText("a");
        AgentSession s = session(fc, false, files());
        s.send("x");
        assertTrue(s.awaitIdle(T));
        s.reset();
        assertTrue(s.history().isEmpty());
        assertTrue(s.transcript().isEmpty());
        assertEquals(Usage.ZERO, s.usage());
        assertEquals(SessionStatus.IDLE, s.status());
    }

    @Test
    void thinkingIsClampedToModelLevels() {
        AgentSession s = AgentSession.builder(ModelBackend.of(new FakeCaller()), new Workspace(root)).thinking("high").build();
        assertNull(s.thinking(), "不认识的模型没有档位");
        assertThrows(IllegalArgumentException.class, () -> s.setThinking("high"));
        assertEquals(Integer.valueOf(16000), AgentSession.defaultMaxTokens(64000L, 32000L));
        assertEquals(Integer.valueOf(32000), AgentSession.defaultMaxTokens(128000L, null));
    }

    @Test
    void permissionsAreSharedWithChildren() {
        SessionPermissions p = new SessionPermissions();
        AgentSession s = AgentSession.builder(ModelBackend.of(new FakeCaller()), new Workspace(root)).permissions(p).build();
        assertSame(p, s.permissions());
        AgentSession child = AgentSession.builder(ModelBackend.of(new FakeCaller()), new Workspace(root)).parent(s).build();
        assertSame(p, child.permissions(), "子会话缺省共用父会话的权限状态");
        assertEquals(PermissionSettings.DEFAULT, new SessionPermissions().settings());
    }
}
