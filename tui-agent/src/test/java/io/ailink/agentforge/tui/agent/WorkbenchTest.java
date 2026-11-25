package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.clarify.Clarify;
import io.ailink.agentforge.agent.loop.ModelTurn;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.session.SessionStatus;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.tui.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchTest {
    @TempDir Path tmp;

    private static FakeModel.Reply latched(CountDownLatch l, FakeModel.Reply then) {
        return (q, d, c) -> {
            try { l.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return then.reply(q, d, c);
        };
    }

    private static Harness untrusted(Path tmp, FakeModel fm) throws Exception {
        return new Harness(tmp, fm, b -> b.approvalPolicy(ApprovalPolicy.UNLESS_TRUSTED),
                new AgentWorkbench.Options().requireModel(false), 100, 36);
    }

    // ── 流式 ─────────────────────────────────────────────────────────────

    @Test
    void streamsThinkingAndTextThenFoldsThinking() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        FakeModel fm = new FakeModel().then((q, d, c) -> {
            d.onDelta("thinking", "先想一想用户要什么");
            d.onDelta("text", "你好");
            try { go.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            d.onDelta("text", "，世界");
            return FakeModel.text("你好，世界");
        });
        try (Harness h = new Harness(tmp, fm)) {
            h.type("hi").keys("RET");
            h.pump(() -> h.text().contains("你好"));
            String live = h.screen();
            assertTrue(live.contains("› hi"), live);
            assertTrue(h.text().contains("先想一想用户要什么"), "正在想的那段（已收进时间线）不折");
            assertTrue(live.contains("• 正在写 (") || live.contains("◦ 正在写 ("), "底栏状态行：\n" + live);
            assertEquals("", h.wb.promptBuffer().contents(), "发出去就清空输入框");
            long before = h.wb.transcriptView().renders();
            h.wb.refresh();
            assertEquals(before, h.wb.transcriptView().renders(), "没变就不动 buffer");
            go.countDown();
            h.idle();
            String done = h.screen();
            assertTrue(done.contains("你好，世界"), done);
            assertTrue(done.contains("• 想了"), done);
            assertFalse(done.contains("先想一想用户要什么"), "落地后思考段默认折起：\n" + done);
            assertFalse(done.contains("正在写"), "状态行退场");
            assertTrue(done.contains("I/O 100/20"), "用量读数：\n" + done);
        }
    }

    // ── 工具调用 + 折叠的结果 ─────────────────────────────────────────────

    @Test
    void toolCallLineAndFoldedResult() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "read-file", "path", "README.txt").thenText("读完了");
        try (Harness h = new Harness(tmp, fm)) {
            Files.writeString(h.root.resolve("README.txt"), "第一行\n第二行\n第三行 SECRET-LINE\n第四行\n");
            h.type("读一下").keys("RET").idle();
            String s = h.screen();
            assertTrue(s.contains("• 浏览了"), "只读工具并成 Explored 格：\n" + s);
            assertTrue(s.contains("└ 读 README.txt"), "每件一行：\n" + s);
            assertFalse(s.contains("SECRET-LINE"), "结果默认折起：\n" + s);
            assertTrue(s.contains("• 读完了"), "回答前面一个 •：\n" + s);
            // 去记录区（C-x o），把折叠打开
            h.keys("C-x o");
            assertSame(h.wb.transcriptWindow(), h.ed.selectedWindow());
            int at = h.text().indexOf("读 README.txt");
            assertTrue(at > 0);
            h.ed.goTo(at);
            h.keys("TAB");
            assertTrue(h.screen().contains("SECRET-LINE"), "TAB 展开：\n" + h.screen());
            // M-w 复制这一块的原文
            h.keys("M-w");
            assertTrue(h.ed.currentKill().contains("第三行 SECRET-LINE"), h.ed.currentKill());
            // 往回切：回输入框、记录区回到最新
            h.keys("C-x o");
            assertSame(h.wb.inputWindow(), h.ed.selectedWindow());
        }
    }

    // ── 审批 ─────────────────────────────────────────────────────────────

    @Test
    void execApprovalYesRunsTheCommand() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "run-command", "command", "printf hello-approved").thenText("跑完了");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("跑一下").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            String s = h.screen();
            assertTrue(s.contains("要执行这条命令吗"), s);
            assertTrue(s.contains("$ printf hello-approved"), s);
            assertTrue(s.contains("› 1. 是，执行 (y)"), s);
            assertEquals(ApprovalRequest.Kind.EXEC, h.wb.approvalPanel().request().kind());
            assertTrue(s.contains("等你审批"), "状态行：\n" + s);
            h.keys("y").idle();
            assertFalse(h.wb.approvalPanel().active());
            assertTrue(h.text().contains("✔ 你批准了：printf hello-approved"), "审批结果留一行：\n" + h.text());
            assertSame(h.wb.promptBuffer(), h.wb.inputWindow().buffer(), "答完输入框回来");
            assertTrue(h.text().contains("跑完了"));
            assertTrue(FakeModel.lastUserText(fm.last()).isEmpty());
            assertTrue(h.wb.session().history().toString().contains("hello-approved"));
        }
    }

    @Test
    void execApprovalAlwaysWritesARule() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "run-command", "command", "printf always").thenText("好");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("跑").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            h.keys("p").idle();
            assertTrue(Files.readString(h.cfg.resolve("rules/default.rules")).contains("\"printf\", \"always\""));
            assertTrue(h.text().contains("记下了"), h.text());
        }
    }

    @Test
    void patchApprovalShowsDiffAndForSessionChoice() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "write-file", "path", "a.txt", "content", "new-line\n").thenText("写好了");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("写").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            String s = h.screen();
            assertEquals(ApprovalRequest.Kind.PATCH, h.wb.approvalPanel().request().kind());
            assertTrue(s.contains("要改这些文件吗"), s);
            assertTrue(s.contains("提议的改动"), "diff 预览（提议的改动格）：\n" + s);
            assertTrue(s.contains("1 +new-line"), "带行号的 diff：\n" + s);
            // diff 的加行上了 diff-added 的色
            var g = h.ed.render();
            boolean colored = false;
            for (int r = 0; r < g.rows; r++) {
                int c = g.rowString(r).indexOf("+new-line");
                if (c >= 0 && g.face(r, c).equals(h.ed.resolveFace("diff-added"))) colored = true;
            }
            assertTrue(colored, "加行按 diff-added 上色");
            h.keys("a").idle();
            assertEquals("new-line\n", Files.readString(h.root.resolve("a.txt")));
            assertTrue(h.screen().contains("• 写了 a.txt (+1 −0)"), "改文件格：\n" + h.screen());
        }
    }

    @Test
    void denyWithFeedbackGoesBackToTheModel() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "write-file", "path", "b.txt", "content", "x\n").thenText("那就不写");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("写").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            h.keys("d");
            assertTrue(h.ed.minibufferActive(), "d 先问一句给模型的话");
            h.type("先别动文件").keys("RET").idle();
            assertFalse(Files.exists(h.root.resolve("b.txt")));
            String sent = fm.last().messages().toString();
            assertTrue(sent.contains("先别动文件"), sent);
            assertTrue(h.text().contains("那就不写"));
        }
    }

    @Test
    void networkDenyAlways() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "web-fetch", "url", "https://example.invalid/x").thenText("不抓了");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("抓").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            assertEquals(ApprovalRequest.Kind.NETWORK, h.wb.approvalPanel().request().kind());
            assertTrue(h.screen().contains("允许访问网络吗"), h.screen());
            assertTrue(h.screen().contains("(b)"), "网络审批有 b 一项");
            h.keys("b").idle();
            assertTrue(h.text().contains("不抓了"));
        }
    }

    @Test
    void abortWithFeedbackStopsAndSendsItNext() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "run-command", "command", "printf nope").thenText("好的，用 make");
        try (Harness h = untrusted(tmp, fm)) {
            h.type("跑").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            h.keys("n");
            h.type("用 make 吧").keys("RET");
            h.pump(() -> fm.requests.size() >= 2 && FakeModel.lastUserText(fm.last()).contains("用 make 吧"));
            h.idle();
            assertTrue(h.text().contains("› 用 make 吧"), h.text());
            assertTrue(h.text().contains("好的，用 make"));
        }
    }

    @Test
    void secretFileApprovalAndEscDenies() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "read-file", "path", ".env").thenText("没读");
        try (Harness h = new Harness(tmp, fm)) {
            Files.writeString(h.root.resolve(".env"), "KEY=1\n");
            h.type("读 env").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            assertEquals(ApprovalRequest.Kind.SECRET, h.wb.approvalPanel().request().kind());
            assertTrue(h.screen().contains("要碰密钥文件吗"), h.screen());
            h.keys("ESC").idle();
            assertTrue(h.text().contains("没读"), "Esc = 拒绝这一次，这一轮接着跑完");
            assertFalse(h.wb.session().history().toString().contains("KEY=1"));
        }
    }

    // ── 提问 ─────────────────────────────────────────────────────────────

    @Test
    void clarifyPanelReplacesInputAndAnswers() throws Exception {
        FakeModel fm = new FakeModel().then((q, d, c) -> {
            var in = AgentTool.JSON.createObjectNode();
            var qs = in.putArray("questions");
            var q1 = qs.addObject().put("question", "用哪种方案？").put("header", "方案");
            var os = q1.putArray("options");
            os.addObject().put("label", "A").put("description", "甲");
            os.addObject().put("label", "B").put("description", "乙");
            var q2 = qs.addObject().put("question", "要哪些检查？").put("header", "检查").put("multiple", true);
            var os2 = q2.putArray("options");
            os2.addObject().put("label", "lint");
            os2.addObject().put("label", "test");
            os2.addObject().put("label", "bench");
            return new ModelTurn(new Message(Role.ASSISTANT, List.of(new Block.ToolUse("q1", Clarify.NAME, in))), Usage.ZERO, "tool_use", "fake");
        }).thenText("按 B 做，跑 lint 和 bench");
        try (Harness h = new Harness(tmp, fm)) {
            h.type("帮我选").keys("RET");
            h.pump(() -> h.wb.questionPanel().active());
            String s = h.screen();
            assertTrue(s.contains("[方案] 用哪种方案？"), s);
            assertTrue(s.contains("1. A  — 甲"), s);
            assertTrue(s.contains("自己写"), s);
            assertSame(h.wb.questionPanel().buf, h.wb.inputWindow().buffer(), "面板顶替输入框");
            h.keys("2");
            assertTrue(h.screen().contains("要哪些检查？"), h.screen());
            h.keys("1").keys("3");
            assertTrue(h.screen().contains("[x] lint"), h.screen());
            h.keys("RET").idle();
            assertFalse(h.wb.questionPanel().active());
            String hist = h.wb.session().history().toString();
            assertTrue(hist.contains("B") && hist.contains("lint") && hist.contains("bench"), hist);
            assertTrue(h.text().contains("按 B 做"));
        }
    }

    // ── 排队 / 插话 ───────────────────────────────────────────────────────

    @Test
    void queuedMessageIsShownThenSteeredIn() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        FakeModel fm = new FakeModel()
                .then(latched(go, (q, d, c) -> FakeModel.tool("t1", "list-dir", "path", ".")))
                .then((q, d, c) -> FakeModel.text(q.messages().toString().contains("再看看 README") ? "收到补充" : "没看到补充"));
        try (Harness h = new Harness(tmp, fm)) {
            h.type("看看目录").keys("RET");
            h.pump(() -> h.wb.session().busy());
            h.type("再看看 README").keys("RET");
            h.pump(() -> h.screen().contains("↳ 再看看 README"));
            assertTrue(h.screen().contains("排队中"), "排队的话摆在输入框上面：\n" + h.screen());
            assertTrue(h.screen().contains("Esc Esc 中断"), "状态行：\n" + h.screen());
            go.countDown();
            h.idle();
            assertTrue(h.text().contains("收到补充"), h.text());
            assertTrue(h.text().contains("› 再看看 README"), "并进去之后成了一条真的用户消息");
            assertFalse(h.screen().contains("↳ 再看看"));
        }
    }

    // ── 按停 ─────────────────────────────────────────────────────────────

    @Test
    void escTwiceStopsAndInputStaysUsable() throws Exception {
        FakeModel fm = new FakeModel().then(FakeModel.blockUntilCancelled()).thenText("第二轮");
        try (Harness h = new Harness(tmp, fm)) {
            h.type("慢慢来").keys("RET");
            h.pump(() -> h.wb.session().status() == SessionStatus.THINKING && fm.requests.size() == 1);
            h.keys("ESC");
            assertTrue(h.wb.session().busy(), "一次 Esc 不停");
            assertTrue(h.screen().contains("再按一次"), h.screen());
            h.keys("ESC").idle();
            assertTrue(h.text().contains("■ 这一轮被停下了"), h.text());
            assertTrue(h.text().contains("› 慢慢来"), "那句话留在原位");
            h.type("继续").keys("RET").idle();
            assertTrue(h.text().contains("第二轮"));
        }
    }

    @Test
    void ctrlCStopsImmediatelyAndErrorDoesNotLockInput() throws Exception {
        FakeModel fm = new FakeModel().then(FakeModel.blockUntilCancelled())
                .then((q, d, c) -> { throw new IllegalStateException("对端炸了 [hc-start-tls]"); })
                .thenText("好了");
        try (Harness h = new Harness(tmp, fm)) {
            h.type("一").keys("RET");
            h.pump(() -> fm.requests.size() == 1 && h.wb.session().busy());
            h.keys("C-c C-c").idle();
            assertTrue(h.text().contains("■ 这一轮被停下了"));
            h.type("二").keys("RET").idle();
            assertEquals(SessionStatus.ERROR, h.wb.session().status());
            assertTrue(h.text().contains("对端炸了"), "错封存进记录区：\n" + h.text());
            assertTrue(h.screen().contains("■ ") && h.screen().contains("对端炸了"), h.screen());
            h.type("三").keys("RET").idle();
            assertTrue(h.text().contains("好了"), "出错不锁输入框");
        }
    }

    // ── / 命令表、@ 文件 ─────────────────────────────────────────────────

    @Test
    void slashMenuCompletesAndRunsCommands() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel())) {
            h.type("/");
            h.advance(500);
            assertTrue(h.ed.completionActive(), "打 / 弹命令表");
            List<String> labels = h.ed.completionCandidates().stream().map(CompletionItem::label).toList();
            assertTrue(labels.containsAll(List.of("/help", "/model", "/sessions", "/permissions", "/quit")), labels.toString());
            h.type("perm");
            assertEquals("/permissions", h.ed.completionCandidates().getFirst().label());
            h.keys("C-g").keys("C-a C-k");
            h.type("/help").keys("RET");
            assertTrue(h.text().contains("/sandbox"), h.text());
            assertTrue(h.text().contains("C-c m / k / p"), h.text());
            assertFalse(h.text().contains("C-c ↑") || h.text().contains("C-c T") || h.text().contains("C-c o"), "帮助里不提去掉的键：\n" + h.text());
            h.type("/permissions").keys("RET");
            assertTrue(h.wb.listPanel().active(), "不带参数弹底栏选择器");
            assertSame(h.wb.listPanel().buf, h.wb.inputWindow().buffer(), "选择器顶替输入框");
            assertTrue(h.wb.listPanel().labels().contains("read-only"), h.wb.listPanel().labels().toString());
            h.keys("C-g");
            assertSame(h.wb.promptBuffer(), h.wb.inputWindow().buffer());
            h.type("/nonsense").keys("RET");
            assertTrue(h.text().contains("不认识的命令 /nonsense"));
        }
    }

    @Test
    void atMentionCompletesFiles() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel())) {
            Files.createDirectories(h.root.resolve("src"));
            Files.writeString(h.root.resolve("src/Main.java"), "class Main {}\n");
            h.type("看 @");
            h.advance(500);
            assertTrue(h.ed.completionActive());
            List<String> labels = h.ed.completionCandidates().stream().map(CompletionItem::label).toList();
            assertTrue(labels.contains("@src/") && labels.contains("@src/Main.java"), labels.toString());
            h.type("Main");
            h.keys("TAB");
            assertEquals("看 @src/Main.java", h.wb.promptBuffer().contents());
        }
    }

    // ── 会话 ─────────────────────────────────────────────────────────────

    @Test
    void openRestoresTheTranscript() throws Exception {
        FakeModel fm = new FakeModel().thenText("第一段的回答");
        try (Harness h = new Harness(tmp, fm)) {
            h.type("第一段的问题").keys("RET").idle();
            Long conv = h.rt.conversationOf(h.wb.session());
            assertNotNull(conv);
            h.type("/new").keys("RET");
            assertFalse(h.text().contains("第一段的回答"), "新对话是空的");
            h.keys("C-c l");
            assertTrue(h.wb.listPanel().active());
            List<String> cands = h.wb.listPanel().labels();
            assertTrue(cands.getFirst().contains("开一段新对话"), cands.toString());
            assertTrue(cands.stream().anyMatch(c -> c.contains("#" + conv) && c.contains("第一段的问题")), cands.toString());
            h.keys("C-g");
            h.type("/open " + conv).keys("RET");
            assertTrue(h.text().contains("› 第一段的问题"), h.text());
            assertTrue(h.text().contains("第一段的回答"));
            assertTrue(h.text().contains("打开了对话 #" + conv));
            assertEquals(conv, h.rt.conversationOf(h.wb.session()));
        }
    }

    // ── 子 agent ─────────────────────────────────────────────────────────

    @Test
    void subAgentGroupAndNavigation() throws Exception {
        FakeModel fm = new FakeModel().route(req -> {
            if (req.system() == null || !req.system().contains(Prompts.DEEP_SYSTEM_PROMPT)) return null;
            boolean answered = req.messages().stream().anyMatch(m -> !m.toolResults().isEmpty());
            return answered ? (q, d, c) -> FakeModel.text("在 a.txt 里找到了 hello")
                    : (q, d, c) -> FakeModel.tool("c1", "read-file", "path", "a.txt");
        }).thenTool("t1", "task", "description", "找 hello", "prompt", "在仓库里找 hello", "subagent_type", "deep").thenText("子 agent 说在 a.txt");
        try (Harness h = new Harness(tmp, fm)) {
            Files.writeString(h.root.resolve("a.txt"), "hello\n");
            h.type("hello 在哪").keys("RET").idle();
            String s = h.screen();
            assertTrue(s.contains("• 子 agent [deep] 找 hello"), s);
            assertTrue(s.contains("1 步"), s);
            h.type("/agents").keys("RET");
            assertTrue(h.wb.listPanel().labels().getFirst().contains("看子 agent：deep · 找 hello"), h.wb.listPanel().labels().toString());
            h.keys("1");
            assertNotSame(h.wb.session(), h.wb.viewing(), "走进了子会话");
            String child = h.screen();
            assertTrue(child.contains("正在看子 agent 1/1"), child);
            assertTrue(child.contains("/agents 回主对话"), child);
            assertTrue(child.contains("在 a.txt 里找到了 hello"), child);
            assertTrue(child.contains("读 a.txt"), child);
            h.type("/agents").keys("RET");
            assertTrue(h.wb.listPanel().labels().getFirst().contains("回主对话"), h.wb.listPanel().labels().toString());
            h.keys("1");
            assertSame(h.wb.session(), h.wb.viewing());
            assertTrue(h.screen().contains("子 agent 说在 a.txt"));
        }
    }

    // ── 待办、主题、搜索、启动提示 ───────────────────────────────────────

    @Test
    void todoProgressShowsWhileIncomplete() throws Exception {
        FakeModel fm = new FakeModel().thenTool("t1", "todo-write", "items", "- [x] 读代码\n- [>] 改测试\n- [ ] 跑一遍").thenText("先到这");
        try (Harness h = new Harness(tmp, fm, b -> { }, new AgentWorkbench.Options().requireModel(false).sidePanel(true), 140, 32)) {
            h.type("干活").keys("RET").idle();
            String s = h.screen();
            assertTrue(s.contains("• 更新了计划 · 完成 1/3"), s);
            assertTrue(s.contains("✔ 读代码") && s.contains("□ 改测试") && s.contains("□ 跑一遍"), s);
            assertTrue(s.contains("待办 1/3"), "侧栏（/side 或 sidePanel(true) 才开）：\n" + s);
        }
    }

    @Test
    void themeSwitchSearchAndStartupGuide() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel(), b -> { }, new AgentWorkbench.Options(), 100, 30)) {
            String s = h.screen();
            assertTrue(s.contains("ANTHROPIC_API_KEY"), "没 key ⇒ 启动时就说怎么配：\n" + s);
            h.type("你好").keys("RET");
            assertTrue(h.wb.session().history().isEmpty(), "没模型不发");
            assertTrue(h.screen().contains("你好") , "输入框里的话留着");
            String before = h.ed.theme();
            h.keys("ESC");
            assertEquals("", h.wb.promptBuffer().contents(), "闲着时 Esc 清空输入");
            h.type("/theme").keys("RET");
            assertTrue(h.wb.listPanel().active(), "/theme 弹主题选择器");
            h.keys("<down>");
            assertNotEquals(before, h.ed.theme(), "挪到哪套先换上看看");
            h.keys("ESC");
            assertEquals(before, h.ed.theme(), "Esc 换回原来的");
            h.type("/theme paper-light").keys("RET");
            assertEquals("paper-light", h.ed.theme());
            h.keys("C-x o").keys("C-r");
            h.type("ANTHROPIC");
            assertNotNull(h.ed.isearch());
            assertFalse(h.ed.isearch().failing());
            h.keys("RET");
        }
    }

    @Test
    void modelPickerListsAndSelects() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel())) {
            h.keys("C-c m");
            assertFalse(h.wb.listPanel().active(), "没有可选模型时不弹空选择器");
            assertTrue(h.text().contains("ANTHROPIC_API_KEY") || h.text().contains("没有可选的模型"), h.text());
            h.keys("C-c p");
            assertTrue(h.wb.listPanel().active());
            h.type("read").keys("RET");
            assertEquals("read-only", h.rt.permissionSettings().sandbox().wire());
            assertNull(h.ed.globalMap().lookup(io.ailink.agentforge.tui.Keymap.kbd("C-c d")), "C-c d 已去掉");
            h.type("/agents").keys("RET");
            assertTrue(h.wb.listPanel().labels().contains("换 agent：deep"), h.wb.listPanel().labels().toString());
            h.type("agent：deep").keys("RET");
            assertEquals("deep", h.wb.session().agent());
            assertTrue(h.screen().contains("· deep"), "底栏右边写上非默认的 agent：\n" + h.screen());
            assertTrue(h.screen().contains("deep"), "会话头卡片也写着：\n" + h.screen());
        }
    }

    @Test
    void steerNowInterruptsAndSendsQueuedPlusInput() throws Exception {
        FakeModel fm = new FakeModel().then(FakeModel.blockUntilCancelled())
                .then((q, d, c) -> FakeModel.text("收到：" + FakeModel.lastUserText(q).replace('\n', '|')));
        try (Harness h = new Harness(tmp, fm)) {
            h.type("先做 A").keys("RET");
            h.pump(() -> fm.requests.size() == 1 && h.wb.session().busy());
            h.type("排着的 B").keys("RET");
            h.type("/steer 改做 C").keys("RET");
            h.idle();
            h.pump(() -> h.text().contains("收到："));
            h.idle();
            assertTrue(h.text().contains("收到：排着的 B|改做 C"), h.text());
            assertTrue(h.text().contains("■ 这一轮被停下了"));
        }
    }

    @Test
    void keyTableIsTrimmed() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel())) {
            var g = h.ed.globalMap();
            for (String k : List.of("C-x C-c", "C-x o", "C-c C-c", "C-c m", "C-c k", "C-c p", "C-c l"))
                assertNotNull(g.lookup(io.ailink.agentforge.tui.Keymap.kbd(k)), "留着：" + k);
            for (String k : List.of("C-c d", "C-c N", "C-c o", "C-c e", "C-c s", "C-c y", "C-c n", "C-c i", "C-c c", "C-c t", "C-c /", "C-c ?",
                    "C-c <up>", "C-c <down>", "C-c <left>", "C-c <right>", "C-c T", "C-x 2", "C-x 3", "C-x 0", "C-x 1", "C-x u", "C-x h",
                    "C-x C-x", "C-x z", "C-x C-q", "C-x x", "C-x (", "C-x e", "S-<left>"))
                assertNull(g.lookup(io.ailink.agentforge.tui.Keymap.kbd(k)), "去掉了：" + k);
            assertEquals("agent-toggle-focus", g.lookup(io.ailink.agentforge.tui.Keymap.kbd("C-x o")));
            h.keys("C-x o");
            assertSame(h.wb.transcriptWindow(), h.ed.selectedWindow());
            h.keys("C-x o");
            assertSame(h.wb.inputWindow(), h.ed.selectedWindow());
            // 会话头卡片 + 输入框的占位、底栏提示
            String s = h.screen();
            assertTrue(s.contains(">_ AgentForge (v"), s);
            assertTrue(s.contains("› 让 AgentForge 做点什么"), s);
            assertTrue(s.contains("⏎ 发送") && s.contains("/ 命令"), s);
            assertFalse(s.contains("C-c agent"), "提示里不提去掉的键：\n" + s);
        }
    }

    @Test
    void inputGrowsAndArrowsWalkHistoryAtTheEdges() throws Exception {
        FakeModel fm = new FakeModel().thenText("一").thenText("二");
        try (Harness h = new Harness(tmp, fm)) {
            h.type("第一句").keys("RET").idle();
            h.type("第二句").keys("RET").idle();
            int base = h.wb.group.fixedSize();
            h.type("多行").keys("C-j").type("第二行").keys("C-j").type("第三行");
            assertEquals(base + 2, h.wb.group.fixedSize(), "输入框跟着内容长高");
            h.keys("<up>");
            assertEquals("多行\n第二行\n第三行", h.wb.promptBuffer().contents(), "不在首行时 ↑ 只是移一行");
            h.keys("<up> <up>");
            assertEquals("第二句", h.wb.promptBuffer().contents(), "到首行之后 ↑ 翻历史");
            h.keys("<up>");
            assertEquals("第一句", h.wb.promptBuffer().contents());
            h.keys("<down>");
            assertEquals("第二句", h.wb.promptBuffer().contents());
        }
    }
}
