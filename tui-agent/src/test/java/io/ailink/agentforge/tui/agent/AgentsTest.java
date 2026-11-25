package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.loop.ModelTurn;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** agent 总览、子 agent 的活动行、审批收紧、底栏闪现、没过审批的改动 diff。 */
class AgentsTest {
    @TempDir Path tmp;

    private static String first(ChatRequest req) { return req.messages().isEmpty() ? "" : req.messages().getFirst().text(); }

    private static boolean answered(ChatRequest req) { return req.messages().stream().anyMatch(m -> !m.toolResults().isEmpty()); }

    private static ObjectNode args(Object... kv) {
        ObjectNode in = AgentTool.JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) in.set((String) kv[i], AgentTool.JSON.valueToTree(kv[i + 1]));
        return in;
    }

    /** 一次回答里并排派两趟子 agent。 */
    private static ModelTurn twoTasks() {
        List<Block> bs = new ArrayList<>();
        bs.add(new Block.ToolUse("tA", "task", args("description", "查 A", "prompt", "SUB-A 找 a", "subagent_type", "deep")));
        bs.add(new Block.ToolUse("tB", "task", args("description", "查 B", "prompt", "SUB-B 找 b", "subagent_type", "deep")));
        return new ModelTurn(new Message(Role.ASSISTANT, bs), FakeModel.USAGE, "tool_use", "fake");
    }

    /** 子 agent：先读一个文件，然后等 gate 放行才回结论。 */
    private static FakeModel parallel(CountDownLatch gate) {
        return new FakeModel().route(req -> {
            String f = first(req);
            if (!f.contains("SUB-")) return null;
            String tag = f.contains("SUB-A") ? "a" : "b";
            if (!answered(req)) return (q, d, c) -> FakeModel.tool("r" + tag, "read-file", "path", tag + ".txt");
            return (q, d, c) -> {
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return FakeModel.text("结论 " + tag);
            };
        }).then((q, d, c) -> twoTasks()).thenText("两边都查完了");
    }

    @Test
    void parallelSubAgentsShowLiveInStatusTranscriptAndOverview() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (Harness h = new Harness(tmp, parallel(gate), b -> { }, new AgentWorkbench.Options().requireModel(false), 120, 36)) {
            Files.writeString(h.root.resolve("a.txt"), "a\n");
            Files.writeString(h.root.resolve("b.txt"), "b\n");
            h.type("分头查").keys("RET");
            h.pump(() -> h.wb.roster.running().size() == 2 && h.wb.roster.running().stream().allMatch(r -> r.steps() == 1)
                    && h.wb.roster.running().stream().allMatch(r -> "正在想".equals(r.activity())));
            h.advance(200);
            h.wb.refresh();
            String s = h.screen();
            // 并排的两趟谁先登记不一定：称呼按看到的先后发，断言不假定 A 一定是 #1
            String la = h.wb.roster.label("tA"), lb = h.wb.roster.label("tB");
            assertEquals(java.util.Set.of("deep #1", "deep #2"), java.util.Set.of(la, lb));
            // 状态行：在等两个子 agent；下面一个子 agent 一行（称呼、用时 · 步数、在做什么、描述）
            assertTrue(s.contains("在等 2 个子 agent"), s);
            assertTrue(s.contains("└ ● deep #") && s.contains("1 步) 正在想 — 查"), s);
            assertTrue(s.contains("● " + la + " (") && s.contains("— 查 A"), s);
            assertTrue(s.contains("● " + lb + " (") && s.contains("— 查 B"), s);
            // 记录区：两格子 agent 都在跑，露出最近的步骤和这会儿在做什么
            String t = h.text();
            assertTrue(t.contains("子 agent " + la + " 查 A (1 步 · 在跑)"), t);
            assertTrue(t.contains("子 agent " + lb + " 查 B (1 步 · 在跑)"), t);
            assertTrue(t.contains("│ read-file path=a.txt"), t);
            assertTrue(t.contains("└ 正在想"), t);
            // 两趟颜色不同，记录区与状态行同色
            assertNotEquals(h.wb.roster.face("tA"), h.wb.roster.face("tB"));
            var g = h.ed.render();
            String face1 = null;
            for (int r = 0; r < g.rows; r++) {
                int c = g.rowString(r).indexOf("deep #1");
                if (c >= 0) {
                    if (face1 == null) face1 = String.valueOf(g.face(r, c));
                    else assertEquals(face1, String.valueOf(g.face(r, c)), "同一趟处处同色");
                }
            }
            assertEquals(String.valueOf(h.ed.resolveFace(h.wb.roster.face(la.equals("deep #1") ? "tA" : "tB"))), face1);
            // 总览：主会话 + 两趟，都在跑
            h.keys("C-c a");
            assertTrue(h.wb.overview.active());
            String ov = h.screen();
            assertTrue(ov.contains("全部 3") && ov.contains("在跑 3"), ov);
            assertTrue(ov.contains("deep #1") && ov.contains("deep #2") && ov.contains("主会话"), ov);
            h.keys("<down>");
            ov = h.screen();
            assertTrue(ov.contains("正在做") && ov.contains("最近几步") && ov.contains("SUB-"), "详情：\n" + ov);
            h.keys("TAB TAB TAB");
            assertTrue(h.screen().contains("（这一栏是空的）"), "完成那一栏还是空的：\n" + h.screen());
            h.keys("ESC");
            assertFalse(h.wb.overview.active());
            assertSame(h.wb.transcriptView().buffer(), h.wb.transcriptWindow().buffer());
            gate.countDown();
            h.idle();
            h.pump(() -> !h.wb.roster.anyRunning());
            h.wb.refresh();
            t = h.text();
            assertTrue(t.contains("子 agent " + la + " 查 A (1 步 · "), "跑完收成摘要：\n" + t);
            assertFalse(t.contains("└ 正在想"), "跑完不再写在做什么：\n" + t);
            int ia = t.indexOf("查 A (1 步"), ib = t.indexOf("查 B (1 步"), ra = t.indexOf("结论 a"), rb = t.indexOf("结论 b");
            assertTrue(ia < ra && ib < rb && (ia < ib ? ra < ib : rb < ia), "各自的结论跟在各自那格下面：\n" + t);
            assertFalse(h.screen().contains("在等 2 个子 agent"));
            h.keys("C-c a");
            assertTrue(h.screen().contains("完成 2"), h.screen());
        }
    }

    /** 一次回答里一趟前台 + 一趟后台。 */
    private static ModelTurn foreAndBack() {
        List<Block> bs = new ArrayList<>();
        bs.add(new Block.ToolUse("tB", "task", args("description", "后台整理", "prompt", "SUB-B 整理", "subagent_type", "deep", "background", true)));
        bs.add(new Block.ToolUse("tA", "task", args("description", "前台查", "prompt", "SUB-A 查", "subagent_type", "deep")));
        return new ModelTurn(new Message(Role.ASSISTANT, bs), FakeModel.USAGE, "tool_use", "fake");
    }

    @Test
    void backgroundRunsAreMarkedAndListedAfterForegroundOnes() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeModel fm = new FakeModel().route(req -> {
            String f = first(req);
            if (!f.contains("SUB-")) return null;
            String tag = f.contains("SUB-A") ? "a" : "b";
            if (!answered(req)) return (q, d, c) -> FakeModel.tool("r" + tag, "read-file", "path", tag + ".txt");
            return (q, d, c) -> {
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return FakeModel.text("结论 " + tag);
            };
        }).then((q, d, c) -> foreAndBack()).thenText("好").thenText("收到");
        try (Harness h = new Harness(tmp, fm, b -> { }, new AgentWorkbench.Options().requireModel(false), 120, 36)) {
            Files.writeString(h.root.resolve("a.txt"), "a\n");
            Files.writeString(h.root.resolve("b.txt"), "b\n");
            h.type("一前一后").keys("RET");
            h.pump(() -> h.wb.roster.running().size() == 2 && h.wb.roster.running().stream().allMatch(r -> r.steps() == 1));
            h.advance(200);
            h.wb.refresh();
            String s = h.screen();
            String fore = h.wb.roster.label("tA"), back = h.wb.roster.label("tB");
            assertTrue(s.contains("在等 " + fore + " （另有 1 个在后台）") || s.contains("在等 " + fore + "（另有 1 个在后台）")
                    || s.contains("（另有 1 个在后台）"), "状态行说清还有一个在后台：\n" + s);
            assertTrue(s.contains("● " + back + " [后台] ("), "后台那趟标出来：\n" + s);
            assertFalse(s.contains("● " + fore + " [后台]"), "前台那趟不标：\n" + s);
            assertTrue(s.indexOf("● " + fore + " (") < s.indexOf("● " + back + " [后台]"), "前台排在后台前面：\n" + s);
            h.keys("C-c a");
            String ov = h.screen();
            assertTrue(ov.contains("[后台] 后台整理"), "总览列表里也标出后台：\n" + ov);
            assertFalse(ov.contains("[后台] 前台查"), ov);
            h.keys("ESC");
            gate.countDown();
            h.pump(() -> !h.wb.roster.anyRunning());
        }
    }

    @Test
    void backgroundRunKeepsTheFeedAliveAfterTheTurnEnds() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        FakeModel fm = new FakeModel().route(req -> {
            if (!first(req).contains("SUB-")) return null;
            if (!answered(req)) return (q, d, c) -> FakeModel.tool("r1", "read-file", "path", "a.txt");
            return (q, d, c) -> {
                try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return FakeModel.text("后台结论");
            };
        }).thenTool("t1", "task", "description", "后台查", "prompt", "SUB-BG", "subagent_type", "deep", "background", true)
                .thenText("先放后台").thenText("收到后台结论");
        try (Harness h = new Harness(tmp, fm)) {
            Files.writeString(h.root.resolve("a.txt"), "a\n");
            h.type("后台").keys("RET");
            h.pump(() -> !h.wb.session().busy() && h.wb.roster.anyRunning() && h.text().contains("先放后台"));
            h.wb.refresh();
            String s = h.screen();
            assertTrue(s.contains("后台有 1 个子 agent 在跑"), s);
            assertTrue(s.contains("deep #1 [后台] ("), "状态区里后台那趟标出来：\n" + s);
            assertTrue(h.text().contains("子 agent deep #1 [后台] 后台查"), h.text());
            gate.countDown();
            h.pump(() -> !h.wb.roster.anyRunning());
            h.wb.refresh();
            assertFalse(h.screen().contains("后台有 1 个子 agent 在跑"));
        }
    }

    @Test
    void subAgentApprovalIsAttributedAndCompactOnSmallScreens() throws Exception {
        FakeModel fm = new FakeModel().route(req -> {
            if (!first(req).contains("SUB-W")) return null;
            if (!answered(req)) return (q, d, c) -> FakeModel.tool("w1", "run-command", "command",
                    "printf one && printf two && printf three && printf four && printf five && printf six && printf seven && printf eight");
            return (q, d, c) -> FakeModel.text("跑好了");
        }).thenTool("t1", "task", "description", "跑一下", "prompt", "SUB-W", "subagent_type", "junior").thenText("好");
        try (Harness h = new Harness(tmp, fm, b -> b.approvalPolicy(ApprovalPolicy.UNLESS_TRUSTED),
                new AgentWorkbench.Options().requireModel(false), 80, 24)) {
            h.type("派活").keys("RET");
            h.pump(() -> h.wb.approvalPanel().active());
            h.wb.refresh();
            String s = h.screen();
            assertTrue(s.contains("要执行这条命令吗？  ← junior #1 问的"), "审批写明是哪一趟子 agent 问的：\n" + s);
            assertTrue(h.wb.approvalPanel().compact(), "24 行的屏收紧");
            int panel = h.wb.group.fixedSize();
            assertTrue(panel <= 12, "收紧后的面板不超过 12 行（原来约 15 行），实际 " + panel + "：\n" + s);
            for (var c : h.wb.approvalPanel().request().choices()) assertTrue(s.contains(c.label()), "每个选项都在：" + c.label() + "\n" + s);
            assertTrue(s.contains("$ printf one"), s);
            assertTrue(s.contains("? 详情"), s);
            assertFalse(s.contains("在 /"), "收紧时不写目录：\n" + s);
            assertTrue(s.contains("等你审批"), "状态区的子 agent 行写着等你：\n" + s);
            h.keys("?");
            assertFalse(h.wb.approvalPanel().compact());
            assertTrue(h.screen().contains("收起"), h.screen());
            h.keys("y");
            h.idle();
            assertTrue(h.text().contains("✔ 你批准了（junior #1）：printf one"), "子 agent 的审批结果记在主对话里，写明是谁的：\n" + h.text());
        }
    }

    @Test
    void flashMessagesReplaceTheWholeFooterThenGoAway() throws Exception {
        try (Harness h = new Harness(tmp, new FakeModel())) {
            String foot = h.ed.render().rowString(h.ed.rows() - 1);
            assertTrue(foot.contains("⏎ 发送"), foot);
            h.ed.message("复制了 12 个字");
            foot = h.ed.render().rowString(h.ed.rows() - 1);
            assertTrue(foot.startsWith("  复制了 12 个字"), "闪现整条换掉提示：" + foot);
            assertFalse(foot.contains("⏎ 发送"), "不和提示挤在一行：" + foot);
            h.advance(AgentWorkbench.FLASH_MS + 100);
            foot = h.ed.render().rowString(h.ed.rows() - 1);
            assertTrue(foot.contains("⏎ 发送"), "几秒后换回提示：" + foot);
            // 读键提问照常整行
            h.ed.yOrN("真的？", y -> { });
            foot = h.ed.render().rowString(h.ed.rows() - 1);
            assertTrue(foot.startsWith(" 真的？"), foot);
            h.keys("n");
        }
    }

    @Test
    void unapprovedWritesAndPatchesShowRealDiffs() throws Exception {
        FakeModel fm = new FakeModel().thenTool("r1", "read-file", "path", "c.txt")
                .thenTool("w1", "write-file", "path", "c.txt", "content", "one\nTWO\nthree\nfour\n")
                .thenTool("p1", "apply-patch", "patchText", "*** Begin Patch\n*** Update File: c.txt\n@@\n three\n-four\n+4\n*** End Patch")
                .thenText("改好了");
        try (Harness h = new Harness(tmp, fm)) {
            Files.writeString(h.root.resolve("c.txt"), "one\ntwo\nthree\nfour\n");
            h.type("改").keys("RET").idle();
            String t = h.text();
            assertTrue(t.contains("写了 c.txt (+1 −1)"), "只算真改了的行：\n" + t);
            assertTrue(t.contains("2 -two") && t.contains("2 +TWO"), "真行号：\n" + t);
            assertFalse(t.contains("+one"), "没改的行不画成加行：\n" + t);
            assertTrue(t.contains("改了 c.txt (+1 −1)"), t);
            assertTrue(t.contains("4 -four") && t.contains("4 +4"), "补丁也有真行号：\n" + t);
        }
    }
}
