package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.session.GalleryImage;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Seg;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** 不起运行时的小件：用量读数、排版、中文表。 */
class PiecesTest {

    private static String text(List<Seg> ss) {
        StringBuilder sb = new StringBuilder();
        for (Seg s : ss) sb.append(s.text());
        return sb.toString().strip();
    }

    @Test
    void usageReadoutSegmentsAndDropOrder() {
        Usage total = new Usage(1000, 4100, 102_400, 0, 0);
        UsageReadout.Parts p = UsageReadout.parts(total, 95_300L, 0.47, 0.0341, false);
        assertEquals("上下文剩余 53% · I/O 103.4k/4.1k · C 99% · $ ~0.034", text(p.fit(200)));
        assertEquals("success", p.c().face());
        assertNull(p.m().face());
        assertEquals("上下文剩余 53% · $ ~0.034", text(p.fit(26)), "窄了先丢 C，再丢 I/O");
        assertEquals("上下文剩余 53%", text(p.fit(14)), "上下文最后丢");
        assertEquals("warning", UsageReadout.parts(total, 1L, 0.85, null, false).m().face());
        assertEquals("error", UsageReadout.parts(total, 1L, 0.95, null, false).m().face());
        UsageReadout.Parts partial = UsageReadout.parts(new Usage(10, 1, 0, 0, 0), null, null, 0.00001, true);
        assertEquals("I/O 10/1 · $ <0.0001+", text(partial.fit(200)), "没窗口 / 没缓存的段不画；+ = 至少这么多");
        assertEquals("", text(UsageReadout.parts(Usage.ZERO, null, null, null, false).fit(200)), "一次没发过：整行空");
    }

    private static final Blocks.Ctx CTX = new Blocks.Ctx() {
        @Override public String fullResult(String id) { return null; }
        @Override public SubRun subRun(String id) { return null; }
        @Override public List<GalleryImage> images(String owner) { return List.of(); }
        @Override public void enterChild(Editor ed, String runId) { }
        @Override public void openImage(Editor ed, GalleryImage g) { }
    };

    @Test
    void approvalBlockForEveryKind() {
        for (ApprovalRequest.Kind k : ApprovalRequest.Kind.values()) {
            ApprovalRequest r = new ApprovalRequest(null, k, "s", "t", "run-command", "make test", k == ApprovalRequest.Kind.EXEC_RETRY
                    || k == ApprovalRequest.Kind.EXEC ? List.of("make", "test") : null, "/w", new Preview.Result(Preview.Kind.DIFF, "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new"),
                    "规则要求", k == ApprovalRequest.Kind.EXEC_RETRY ? "沙盒拦下：Permission denied" : null, "h.example", null, null, null);
            Chunk c = Blocks.render(Blocks.Live.approval(r, null), null, 0, 0, CTX);
            String t = c.text();
            assertTrue(t.contains("• 提议的改动"), t);
            assertTrue(t.contains("1 +new") && t.contains("1 -old"), "带行号的 diff：\n" + t);
            assertTrue(c.spans.stream().anyMatch(s -> "diff-added".equals(s.face())), "加行上色");
            StringBuilder panel = new StringBuilder();
            for (List<Seg> row : ApprovalPanel.details(r, 80, null)) panel.append(text(row)).append('\n');
            assertTrue(panel.toString().contains(Blocks.approvalHead(r)), "审批面板顶上写问句：\n" + panel);
            if (k == ApprovalRequest.Kind.EXEC || k == ApprovalRequest.Kind.EXEC_RETRY) assertTrue(panel.toString().contains("$ make test"), panel.toString());
            if (k == ApprovalRequest.Kind.EXEC_RETRY) assertTrue(panel.toString().contains("沙盒拦下") && Blocks.approvalHead(r).contains("沙盒外重跑"), panel.toString());
        }
    }

    @Test
    void groupHeadShowsOutcome() {
        Entry head = new Entry(Entry.Role.TOOL, "[task] 调用", Map.of("kind", "call", "tool-name", "task", "tool-use-id", "t9",
                "group", "t9", "args-preview", "找注册流程", "sub-who", "junior", "sub-steps", 3, "sub-status", "error", "sub-why", "HTTP 400"));
        String t = Blocks.render(head, null, 0, 0, CTX).text();
        assertTrue(t.strip().startsWith("• 子 agent [junior] 找注册流程"), t);
        assertTrue(t.contains("3 步") && t.contains("出错: HTTP 400"), t);
    }

    private static Entry call(String id, String name, String summary, boolean err, Object... kv) {
        var in = io.ailink.agentforge.agent.tool.AgentTool.JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) in.put((String) kv[i], (String) kv[i + 1]);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", "call");
        m.put("tool-name", name);
        m.put("tool-use-id", id);
        m.put("args-preview", "x");
        m.put("args", in);
        if (summary != null) { m.put("summary", summary); m.put("tool-error", err); }
        return new Entry(Entry.Role.TOOL, "[" + name + "] 调用", m);
    }

    private static Entry result(String id, String text, boolean err) {
        return new Entry(Entry.Role.TOOL, text, Map.of("kind", "result", "tool-use-id", id, "tool-error", err, "summary", "s"));
    }

    @Test
    void codexCells() {
        // 命令：Ran + └ 输出；多了只露头 2 尾 2，中间折起来
        StringBuilder out = new StringBuilder("退出码 0\n── stdout ──\n");
        for (int i = 1; i <= 9; i++) out.append("line ").append(i).append('\n');
        out.append("── stderr ──\n（没有输出）");
        List<Entry> cs = Blocks.cells(List.of(call("r1", "run-command", "退出码 0", false, "command", "seq 9"), result("r1", out.toString(), false)));
        assertEquals(1, cs.size(), "调用 + 结果并成一格");
        Chunk c = Blocks.render(cs.getFirst(), null, 0, 0, CTX);
        assertTrue(c.text().startsWith("• 跑了 seq 9\n  └ line 1\n    line 2\n"), c.text());
        assertEquals(1, c.folds.size(), "中间那几行折起来");
        assertTrue(c.folds.getFirst().summary().contains("另 5 行"), c.folds.getFirst().summary());
        // 失败的命令：红点 + 退出码
        Chunk f = Blocks.render(Blocks.cells(List.of(call("r2", "run-command", "退出码 1", false, "command", "false"),
                result("r2", "退出码 1\n── stdout ──\n（没有输出）\n── stderr ──\n（没有输出）", false))).getFirst(), null, 0, 0, CTX);
        assertTrue(f.text().contains("（退出码 1）") && f.text().contains("└ （没有输出）"), f.text());
        assertTrue(f.spans.stream().anyMatch(sp -> "agent-bullet-err".equals(sp.face())), "失败是红点");
        // 连着的只读工具并成 Explored，连着读的文件并成一行
        List<Entry> ex = Blocks.cells(List.of(call("a", "read-file", "ok", false, "path", "src/a.java"), result("a", "A", false),
                call("b", "read-file", "ok", false, "path", "src/b.java"), result("b", "B", false),
                call("c", "search-text", "ok", false, "pattern", "foo", "path", "src"), result("c", "hit", false)));
        assertEquals(1, ex.size());
        String et = Blocks.render(ex.getFirst(), null, 0, 0, CTX).text();
        assertTrue(et.startsWith("• 浏览了\n  └ 读 a.java, b.java\n"), et);
        assertTrue(et.contains("    搜 foo 于 src"), et);
        // 改文件：Edited path (+a −b) + 带行号的 diff
        String pt = Blocks.render(Blocks.cells(List.of(call("e", "edit-file", "ok", false, "path", "a.txt", "old", "x\n", "new", "y\nz\n"),
                result("e", "\"a.txt\" 已改：替换 1 处，首处在第 7 行。", false))).getFirst(), null, 0, 0, CTX).text();
        assertTrue(pt.startsWith("• 改了 a.txt (+2 −1)"), pt);
        assertTrue(pt.contains("    7 -x") && pt.contains("    7 +y") && pt.contains("    8 +z"), "行号从第 7 行起：\n" + pt);
        // 待办：Updated Plan + ✔ / □
        String plan = Blocks.render(Blocks.cells(List.of(call("t", "todo-write", "ok", false, "items", "- [x] 读\n- [>] 写\n- [ ] 测"))).getFirst(), null, 0, 0, CTX).text();
        assertEquals("• 更新了计划 · 完成 1/3\n  └ ✔ 读\n    □ 写\n    □ 测\n", plan);
        // 其他工具：Called name(args) + └ 结果；还在跑：Calling
        String other = Blocks.render(Blocks.cells(List.of(call("m", "mcp__x__y", null, false, "q", "1"))).getFirst(), null, 0, 0, CTX).text();
        assertTrue(other.startsWith("• 正在调用 mcp__x__y(x)"), other);
    }

    @Test
    void catalogPlaceholdersMatch() {
        Pattern p = Pattern.compile("%[sd]");
        for (var e : Msg.catalog().entrySet()) {
            assertEquals(count(p, e.getKey()), count(p, e.getValue()), "占位符个数不一致：" + e.getKey());
        }
        assertEquals("模型：%s", Msg.catalog().get("model: %s"));
        assertEquals("给模型的话（可空，RET 跳过）： ", Msg.catalog().get("Note to the model (optional, RET to skip): "), "行尾空格算数");
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
