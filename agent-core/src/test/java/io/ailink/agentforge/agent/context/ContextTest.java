package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextTest {

    static Message use(String id) {
        return new Message(Role.ASSISTANT, List.of(new Block.ToolUse(id, "read-file", AgentTool.JSON.createObjectNode().put("path", "a"))));
    }

    static Message result(String id, String text) { return Message.user(List.of(Block.ToolResult.text(id, text, false))); }

    @Test
    void groupsKeepToolPairsTogether() {
        List<Message> h = List.of(Message.user("q"), use("t1"), result("t1", "r"), Message.assistant("a"));
        List<List<Message>> g = ContextMath.groups(h);
        assertEquals(3, g.size());
        assertEquals(2, g.get(1).size());
    }

    @Test
    void trimDropsWholeGroupsFromTheMiddle() {
        List<Message> h = new ArrayList<>();
        h.add(Message.user("任务本身"));
        for (int i = 0; i < 10; i++) { h.add(use("t" + i)); h.add(result("t" + i, "x".repeat(1000))); }
        h.add(Message.assistant("最后"));
        ContextMath.Trimmed t = ContextMath.trim(h, 1500L, 0.5, 1);
        assertTrue(t.dropped() > 0);
        assertEquals("任务本身", t.kept().getFirst().text(), "开头那组保住");
        assertEquals("最后", t.kept().getLast().text());
        // 没有孤儿 tool_result
        for (int i = 1; i < t.kept().size(); i++) {
            if (t.kept().get(i).hasToolResults()) assertFalse(t.kept().get(i - 1).toolUses().isEmpty());
        }
        assertEquals(0, ContextMath.trim(h, null, 0.5, 1).dropped());
    }

    @Test
    void compressorCalibratesFromRealUsage() {
        List<Integer> trims = new ArrayList<>();
        TokenBudgetCompressor c = new TokenBudgetCompressor(100000, 1, (d, n) -> trims.add(d));
        List<Message> h = List.of(Message.user("x".repeat(1000)), Message.assistant("y".repeat(1000)));
        c.apply(h, null);
        c.apply(h, new Usage(2016, 0, 0, 0, 0));   // 2016 token / 2016 字符 = 1.0
        assertEquals(1.0, c.tokensPerChar(), 0.01);
        assertTrue(trims.isEmpty());
    }

    @Test
    void pointerRebuildsEffectiveHistory() {
        List<Message> h = new ArrayList<>();
        for (int i = 0; i < 6; i++) h.add(Message.user("m" + i));
        h.add(Message.user("通告"));
        h.add(Message.assistant("摘要"));
        h.add(Message.user("之后"));
        List<Message> eff = Compaction.apply(h, List.of(new Compaction.Pointer(6, 4)));
        assertEquals(List.of("通告", "摘要", "m4", "m5", "之后"), eff.stream().map(Message::text).toList());
        // 指针不合法 → 原样
        assertEquals(h, Compaction.apply(h, List.of(new Compaction.Pointer(8, 9))));
        var idx = Compaction.applyIndexed(h, List.of(new Compaction.Pointer(6, 4)));
        assertEquals(List.of(6, 7, 4, 5, 8), idx.index());
    }

    @Test
    void pruneClearsOldToolOutputsOnlyWhenWorthIt() {
        List<Message> h = new ArrayList<>();
        h.add(Message.user("开始"));
        for (int i = 0; i < 12; i++) { h.add(use("t" + i)); h.add(result("t" + i, "z".repeat(20000))); }   // 每条 1 万 token
        h.add(Message.user("现在这句"));
        List<Integer> ts = Compaction.pruneTargets(h);
        assertFalse(ts.isEmpty());
        // 量化：总 12 万，保 4 万 → 清 8 万 = 前 8 条结果
        assertEquals(8, ts.size());
        List<Message> p = Compaction.prune(h, null);
        assertEquals(Compaction.PRUNE_PLACEHOLDER, Block.joinText(p.get(2).toolResults().getFirst().content()));
        assertNotEquals(Compaction.PRUNE_PLACEHOLDER, Block.joinText(p.get(24).toolResults().getFirst().content()));
        // 少的时候不动
        assertTrue(Compaction.pruneTargets(h.subList(0, 5)).isEmpty());
    }

    @Test
    void splitKeepsRecentTurnsAndNeverStartsTailWithToolResult() {
        List<Message> h = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            h.add(Message.user("q" + i + "x".repeat(2000)));
            h.add(use("t" + i));
            h.add(result("t" + i, "r".repeat(2000)));
            h.add(Message.assistant("a" + i));
        }
        Compaction.Split sp = Compaction.split(h, 3000);
        assertNotNull(sp);
        assertFalse(sp.tail().getFirst().hasToolResults());
        assertEquals(h.size(), sp.head().size() + sp.tail().size());
        assertNull(Compaction.split(List.of(Message.user("q"), Message.assistant("a"), Message.user("r")), 3000), "头太小不压");
        assertEquals(2000, Compaction.preserveBudget(null));
        assertEquals(8000, Compaction.preserveBudget(100000L));
    }

    @Test
    void serializeAndAnchor() {
        List<Message> h = List.of(Message.user("帮我改 a.c"), use("t1"), result("t1", "内容"), Message.assistant("改好了"));
        String s = Compaction.serialize(h);
        assertTrue(s.contains("[用户]：帮我改 a.c"));
        assertTrue(s.contains("[助手调用工具]：read-file"));
        assertTrue(s.contains("[工具结果]：内容"));
        assertEquals("调用 read-file", Compaction.tailAnchor(List.of(use("t9"))));
    }

    @Test
    void reminderBlocksAreHidden() {
        Message m = Message.user(List.of(new Block.Text("看得见"), Reminder.block("看不见")));
        assertEquals("看得见", Reminder.shownText(m));
        assertTrue(Reminder.isReminder(m.blocks().get(1)));
    }

    @Test
    void nudgeCountsOnlyConsecutiveBusinessFailures() {
        Step bad = new Step("1", "edit-file", null, List.of(), true, "error", 1);
        Step denied = new Step("2", "edit-file", null, List.of(), true, "denied", 1);
        Step ok = new Step("3", "edit-file", null, List.of(), false, "ok", 1);
        assertNull(Nudge.roundNudge(List.of(bad, bad)));
        assertNotNull(Nudge.roundNudge(List.of(bad, bad, bad)));
        assertNull(Nudge.roundNudge(List.of(bad, bad, ok, bad)));
        assertNull(Nudge.roundNudge(List.of(bad, denied, bad, bad)));
        assertEquals(new Nudge.Run("edit-file", 3), Nudge.consecutiveFailures(List.of(ok, bad, bad, bad)));
    }

    @Test
    void backfillPathsSkipThoseReadInTail() {
        List<Message> head = List.of(Message.user("x"), use("t1"), result("t1", "r"));
        List<Message> tail = List.of(Message.user("y"));
        assertEquals(List.of("a"), Backfill.paths(head, tail));
        assertEquals(List.of(), Backfill.paths(head, List.of(use("t2"))));
    }
}
