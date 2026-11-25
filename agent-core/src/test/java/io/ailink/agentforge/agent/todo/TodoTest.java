package io.ailink.agentforge.agent.todo;

import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.model.CancelToken;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.ailink.agentforge.agent.util.ToolKit.call;
import static org.junit.jupiter.api.Assertions.*;

class TodoTest {
    private static List<Todo.Status> st(String s) { return Todo.parse(s).stream().map(Todo.Item::status).toList(); }

    private static List<String> tx(String s) { return Todo.parse(s).stream().map(Todo.Item::text).toList(); }

    @Test
    void parse() {
        assertEquals(List.of(), Todo.parse(""));
        assertEquals(List.of(), Todo.parse(null));
        assertEquals(List.of(Todo.Status.PENDING, Todo.Status.DOING, Todo.Status.DONE), st("- [ ] a\n- [>] b\n- [x] c"));
        assertEquals(List.of("a", "b", "c"), tx("- [ ] a\n- [>] b\n- [x] c"));
        assertEquals(List.of(Todo.Status.DONE), st("- [X] a"));
        assertEquals(List.of(Todo.Status.DONE), st("- [✓] a"));
        assertEquals(List.of(Todo.Status.PENDING), st("* [ ] a"));
        assertEquals(List.of(Todo.Status.DONE), st("+ [x] a"));
        assertEquals(List.of(Todo.Status.PENDING), st("    - [ ] a"));
        assertEquals(List.of(Todo.Status.PENDING), st("-   [ ] a"));
        assertEquals(List.of("a b"), tx("- [ ]    a b   "));
        assertEquals(List.of(Todo.Status.DOING), st("- [~] a"));
        assertEquals(List.of(Todo.Status.DOING), st("- [*] a"));
        assertEquals(List.of(), Todo.parse("下面是计划："));
        assertEquals(List.of(), Todo.parse("1. 读代码\n2. 改"));
        assertEquals(List.of(), Todo.parse("- 读代码"));
        assertEquals(List.of(), Todo.parse("- [?] a"));
        assertEquals(List.of(), Todo.parse("- [ ]"));
        assertEquals(List.of(), Todo.parse("- [ ]    "));
        assertEquals(List.of(), Todo.parse("-[ ] a"), "符号后必须有空白");
        assertEquals(List.of("a", "c"), tx("计划：\n- [x] a\n随便说点\n- [ ] c\r\n"));
    }

    @Test
    void store() {
        TodoStore s = new TodoStore();
        assertEquals(List.of(), s.items("t"));
        assertEquals(new TodoStore.Progress(0, 0), s.progress("t"));
        assertNull(s.current("t"));
        List<String> heard = new ArrayList<>();
        s.listen((k, items) -> heard.add(k + "=" + items.size()));
        assertEquals(4, s.set("t", "- [x] a\n- [>] b\n- [ ] c\n- [ ] d"));
        assertEquals(new TodoStore.Progress(1, 4), s.progress("t"));
        assertEquals("b", s.current("t").text());
        s.set("t", "- [>] a\n- [>] b");
        assertEquals("a", s.current("t").text(), "两条 doing 取第一条");
        assertEquals(0, s.set("t", "什么都不是"));
        assertEquals(List.of(), s.items("t"));
        s.set(null, "- [ ] main");
        s.set("sub", "- [ ] sub");
        assertEquals("main", s.items(null).getFirst().text());
        assertEquals("main", s.items(TodoStore.MAIN).getFirst().text());
        assertEquals("sub", s.items("sub").getFirst().text(), "子会话与主线各一份");
        s.clear("sub");
        assertEquals(List.of(), s.items("sub"));
        assertEquals(List.of("t=4", "t=2", "t=0", "#main=1", "sub=1", "sub=0"), heard);
    }

    @Test
    void tool() {
        Workspace ws = new Workspace(Path.of("."));
        ToolEnv env = new ToolEnv(ws, new CancelToken(), "tu1", "s1", null);
        ToolOutput o = call(TodoTool.tool(), env, "items", "- [x] a\n- [ ] b");
        assertEquals("记下了：2 条，完成 1 条。", o.text());
        assertEquals(2, TodoStore.of(ws).items("s1").size());
        assertTrue(call(TodoTool.tool(), env, "items", "1. x").text().startsWith("清单是空的"));
        assertFalse(TodoTool.tool().sensitive());
        assertFalse(TodoTool.tool().readOnly(), "不给子 agent");
    }
}
