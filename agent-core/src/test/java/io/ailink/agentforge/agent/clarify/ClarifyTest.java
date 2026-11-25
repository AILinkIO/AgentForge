package io.ailink.agentforge.agent.clarify;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static org.junit.jupiter.api.Assertions.*;

class ClarifyTest {
    static JsonNode json(String s) { return AgentTool.JSON.readTree(s); }

    static final String TWO = """
            {"questions":[
              {"question":"数据怎么迁移？","header":"存储","options":[
                 {"label":"清空重建（推荐）","description":"快"},{"label":"写迁移脚本","description":"稳"}]},
              {"question":"要哪些平台？","header":"平台","multiple":true,"options":[
                 {"label":"Linux","description":""},{"label":"Windows","description":""},{"label":"macOS"}]}]}""";

    private String bad(String js) {
        return assertThrows(ToolException.class, () -> Clarify.parseQuestions(json(js))).getMessage();
    }

    @Test
    void parse() {
        List<ClarifyService.Question> qs = Clarify.parseQuestions(json(TWO));
        assertEquals(2, qs.size());
        assertEquals("存储", qs.get(0).header());
        assertEquals(List.of(new ClarifyService.Option("清空重建（推荐）", "快"), new ClarifyService.Option("写迁移脚本", "稳")), qs.get(0).options());
        assertTrue(qs.get(1).multiple());
        assertFalse(qs.get(0).multiple());
        assertEquals("", qs.get(1).options().get(2).description());
        assertEquals("一二三四五六七八九十一二…",
                Clarify.parseQuestions(json("{\"questions\":[{\"question\":\"一二三四五六七八九十一二三\",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"}]}]}")).getFirst().header());
    }

    @Test
    void parseErrors() {
        assertTrue(bad("{\"questions\":[]}").contains("上限"));
        assertTrue(bad("{}").contains("缺 questions"));
        String five = "{\"questions\":[" + String.join(",", java.util.Collections.nCopies(5,
                "{\"question\":\"q\",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"}]}")) + "]}";
        assertTrue(bad(five).contains("上限 4"));
        assertTrue(bad("{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"a\"}]}]}").contains("2–4"));
        assertTrue(bad("{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"},{\"label\":\"d\"},{\"label\":\"e\"}]}]}").contains("5 个选项"));
        assertTrue(bad("{\"questions\":[{\"header\":\"h\",\"options\":[]}]}").contains("question"));
        assertTrue(bad("{\"questions\":[{\"question\":\"q\",\"options\":[{\"label\":\" \"},{\"label\":\"b\"}]}]}").contains("没有 label"));
    }

    @Test
    void answersText() {
        List<ClarifyService.Question> qs = Clarify.parseQuestions(json(TWO));
        String t = Clarify.answersToText(qs, List.of(List.of("写迁移脚本"), List.of("Linux", "macOS")));
        assertEquals("用户回答了你的问题：\n「数据怎么迁移？」→ 写迁移脚本\n「要哪些平台？」→ Linux、macOS\n照着这些答案继续。", t);
        assertTrue(Clarify.answersToText(qs, List.of(List.of("x"))).contains("「要哪些平台？」→ （没答）"));
    }

    @Test
    void tool() {
        List<ClarifyService.Request> asked = new ArrayList<>();
        ClarifyService.Answer[] next = {new ClarifyService.Answered(List.of(List.of("清空重建（推荐）"), List.of("Linux")))};
        AgentTool t = Clarify.tool(req -> { asked.add(req); return next[0]; });
        tools.jackson.databind.node.ObjectNode args = (tools.jackson.databind.node.ObjectNode) json(TWO);
        ToolOutput ok = t.execute(io.ailink.agentforge.agent.tool.ToolEnv.of(new io.ailink.agentforge.agent.tool.Workspace(Path.of("."))), args);
        assertFalse(ok.error(), ok.text());
        assertTrue(ok.text().startsWith(Clarify.ANSWERED_HEAD));
        assertEquals("t0", asked.getFirst().toolUseId());
        assertEquals(2, asked.getFirst().questions().size());

        next[0] = new ClarifyService.Dismissed();
        ToolOutput d = t.execute(io.ailink.agentforge.agent.tool.ToolEnv.of(new io.ailink.agentforge.agent.tool.Workspace(Path.of("."))), args);
        assertTrue(d.error());
        assertTrue(d.text().contains("没有回答"));
        next[0] = new ClarifyService.Stopped();
        assertTrue(call(t, Path.of("."), "questions", List.of()).error());
        int before = asked.size();
        ToolOutput badArgs = call(t, Path.of("."), "questions", "nope");
        assertTrue(badArgs.error());
        assertEquals(before, asked.size(), "参数坏 → 报错在 ask 之前");
        ToolOutput s = t.execute(io.ailink.agentforge.agent.tool.ToolEnv.of(new io.ailink.agentforge.agent.tool.Workspace(Path.of("."))), args);
        assertTrue(s.text().contains("停掉了这一轮"));
        assertTrue(t.readOnly());
        assertFalse(t.concurrencySafe());
        assertThrows(NullPointerException.class, () -> Clarify.tool(null));
        assertTrue(t.schema().path("properties").path("questions").path("items").path("required").toString().contains("options"));
    }
}
