package io.ailink.agentforge.agent.transcript;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranscriptTest {

    @Test
    void argsOneLineLeadsWithPathAndFlattensNewlines() {
        var in = AgentTool.JSON.createObjectNode().put("content", "a\nb").put("path", "x.txt");
        assertEquals("path=x.txt content=a⏎b", Transcript.argsOneLine(in, 80));
        assertEquals("path=x...", Transcript.argsOneLine(in, 9));
    }

    @Test
    void projectPairsCallsWithResults() {
        var in = AgentTool.JSON.createObjectNode().put("path", "a");
        List<Message> h = List.of(Message.user("问"),
                new Message(Role.ASSISTANT, List.of(new Block.Text("我看看"), new Block.ToolUse("t1", "read-file", in))),
                Message.user(List.of(Block.ToolResult.text("t1", "第一行\n第二行", false))),
                Message.assistant("答"));
        List<Entry> es = Transcript.project(h);
        assertEquals(5, es.size());
        assertEquals(Entry.Role.USER, es.get(0).role());
        assertEquals("我看看", es.get(1).text());
        assertTrue(es.get(2).isCall());
        assertEquals("第一行", es.get(2).meta("summary"));
        assertEquals("result", es.get(3).meta("kind"));
        assertEquals("答", es.get(4).text());
    }

    @Test
    void graftReusesEqualEntries() {
        List<Entry> old = List.of(Entry.user("a"), Entry.assistant("b"));
        List<Entry> fresh = List.of(Entry.user("a"), Entry.assistant("b"), Entry.user("c"));
        List<Entry> g = Transcript.graft(old, fresh);
        assertSame(old.get(0), g.get(0));
        assertSame(old.get(1), g.get(1));
    }

    @Test
    void failuresStayWhereTheyHappened() {
        List<Entry> es = List.of(Entry.user("一"), Entry.assistant("答一"), Entry.user("二"), Entry.assistant("答二"));
        List<Entry> out = Transcript.spliceFailures(es, List.of(new Transcript.Sealed(1, List.of(Entry.system("⏹ 停了", false)))));
        assertEquals(List.of("一", "答一", "⏹ 停了", "二", "答二"), out.stream().map(Entry::text).toList());
        List<Entry> tail = Transcript.spliceFailures(es, List.of(new Transcript.Sealed(5, List.of(Entry.system("错", true)))));
        assertEquals("错", tail.getLast().text());
    }

    @Test
    void subRunHeadCarriesReadings() {
        var in = AgentTool.JSON.createObjectNode().put("description", "找 foo");
        List<Entry> es = List.of(Transcript.toolCall("task", "t1", in, null));
        SubRun r = new SubRun("t1", "找 foo");
        r.who("deep");
        r.step(new SubStep("search-text", "pattern=foo", false, null));
        assertTrue(r.finish("completed", List.of(new SubStep("search-text", "pattern=foo", false, "3 处")), null));
        assertFalse(r.finish("stopped", List.of(), null), "收过尾的不再改");
        Entry head = Transcript.spliceSubagents(es, List.of(r)).getFirst();
        assertEquals("t1", head.meta("group"));
        assertEquals(1, head.meta("sub-steps"));
        assertEquals("deep", head.meta("sub-who"));
        assertEquals("completed", head.meta("sub-status"));
        assertEquals(1, Transcript.subSteps(r).size());
        assertTrue(r.ok());
    }
}
