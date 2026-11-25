package io.ailink.agentforge.agent.registry;

import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.ailink.agentforge.agent.prompt.ToolNames.*;
import static org.junit.jupiter.api.Assertions.*;

/** 内置 agent / 定义 / 覆盖 / 屏蔽 / 默认 / 派活枚举。工具用假的（只有名字与性质）。 */
class AgentRegistryTest {

    static AgentTool tool(String name, boolean readOnly, boolean sensitive) {
        return AgentTool.builder(name).description(name).readOnly(readOnly).sensitive(sensitive)
                .handler((env, args) -> "ok").build();
    }

    static final List<AgentTool> TOOLS = List.of(
            tool(READ_FILE, true, false), tool(LIST_DIR, true, false), tool(SEARCH_TEXT, true, false),
            tool(FIND_FILES, true, false), tool("read-form", true, false),
            tool(WRITE_FILE, false, true), tool(EDIT_FILE, false, true), tool(REPLACE_FORM, false, true),
            tool(RUN_COMMAND, false, true), tool(DELETE_PATH, false, true));

    AgentRegistry reg;

    @BeforeEach
    void setUp() {
        reg = new AgentRegistry();
        reg.toolCatalog(() -> TOOLS);
    }

    static List<String> names(List<AgentTool> ts) { return ts.stream().map(AgentTool::name).toList(); }

    @Test
    void 内置那几条() {
        assertEquals(List.of("main", "junior", "deep", "senior", "vision", "compaction"),
                reg.defs().stream().map(AgentDef::name).toList(), "按注册顺序");
        assertNull(reg.def("main").promptFor("/x"), "main 的提示词由宿主现给");
        assertEquals(List.of(SEARCH_TEXT, FIND_FILES, READ_FILE, LIST_DIR), names(reg.def("deep").toolList()));
        List<String> junior = names(reg.def("junior").toolList());
        assertTrue(junior.containsAll(List.of(READ_FILE, WRITE_FILE, EDIT_FILE, REPLACE_FORM, RUN_COMMAND)));
        assertFalse(junior.contains(DELETE_PATH), "junior 不给删除");
        assertTrue(reg.def("junior").allowSensitive());
        assertEquals(TaskKind.VISION, reg.def("vision").taskKind());
        assertTrue(reg.def("vision").toolList().isEmpty());
        assertTrue(reg.def("compaction").hidden());
        assertEquals(List.of("junior", "deep", "senior", "vision"), reg.subagentNames(), "main 没工具，compaction hidden");
        assertTrue(reg.subagentDescriptions().startsWith("- junior："));
        assertEquals("main", reg.defaultAgent());
        assertTrue(reg.def("deep").promptFor("/x").contains("search-text"));
    }

    @Test
    void 没装工具目录时只有看图那条派得出去() {
        AgentRegistry bare = new AgentRegistry();
        assertEquals(List.of("vision"), bare.subagentNames());
    }

    @Test
    void 定义一条() {
        AgentDef d = reg.define("tester", AgentConfig.of().description("写测试").prompt("你写测试")
                .toolsPlus(List.of(RUN_COMMAND)).toolsMinus(List.of("read-form")).allowSensitive(true).model("a/b"));
        assertTrue(names(d.toolList()).contains(RUN_COMMAND));
        assertFalse(names(d.toolList()).contains("read-form"));
        assertEquals(List.of("a/b"), d.models());
        assertEquals("你写测试", d.promptFor("/"));
        assertTrue(reg.subagentNames().contains("tester"));
        assertThrows(IllegalArgumentException.class,
                () -> reg.define("bad", AgentConfig.of().toolsPlus(List.of(EDIT_FILE))), "写工具要显式放行");
        assertThrows(IllegalArgumentException.class,
                () -> reg.define("bad", AgentConfig.of().toolsPlus(List.of("没这件"))), "名字当场校验");
        assertThrows(IllegalArgumentException.class, () -> reg.define("bad", AgentConfig.of().hidden(true)));
        assertThrows(IllegalArgumentException.class, () -> reg.define("bad", AgentConfig.of().models(List.of())));
        AgentDef plusWins = reg.define("p", AgentConfig.of().tools(List.of(READ_FILE)).toolsMinus(List.of(LIST_DIR)).toolsPlus(List.of(LIST_DIR)));
        assertEquals(List.of(READ_FILE, LIST_DIR), names(plusWins.toolList()), "先减后加：加赢");
        AgentDef none = reg.define("n", AgentConfig.of().tools(AgentConfig.ToolPreset.NONE));
        assertFalse(none.dispatchable());
        assertFalse(reg.subagentNames().contains("n"));
    }

    @Test
    void 逐字段覆盖() {
        AgentDef d = reg.override("senior", AgentConfig.of().models(List.of("openai/gpt-5", "deepseek/x")));
        assertEquals(List.of("openai/gpt-5", "deepseek/x"), d.models());
        assertEquals(TaskKind.SENIOR, d.taskKind(), "没写的沿用");
        assertTrue(d.promptFor("/").contains("想不清楚"));
        assertEquals(List.of("main", "junior", "deep", "senior", "vision", "compaction"),
                reg.defs().stream().map(AgentDef::name).toList(), "位置不变");
        assertThrows(IllegalArgumentException.class, () -> reg.override("没有", AgentConfig.of()));
        assertThrows(IllegalArgumentException.class, () -> reg.override("deep", AgentConfig.of().hidden(false)));
        assertThrows(IllegalArgumentException.class, () -> reg.override("deep", AgentConfig.of().toolsPlus(List.of(WRITE_FILE))));
        assertDoesNotThrow(() -> reg.override("junior", AgentConfig.of().toolsPlus(List.of(WRITE_FILE))), "junior 本来就放行");
    }

    @Test
    void 屏蔽与默认() {
        reg.block("deep");
        assertFalse(reg.subagentNames().contains("deep"), "屏蔽真正生效的地方是派活枚举");
        assertFalse(reg.visibleDefs().stream().anyMatch(d -> d.name().equals("deep")));
        assertThrows(IllegalArgumentException.class, () -> reg.block("compaction"));
        assertThrows(IllegalArgumentException.class, () -> reg.block("没有"));
        reg.block("main");
        assertEquals("junior", reg.defaultAgent(), "main 被屏蔽 → 第一条看得见的");
        reg.setDefault("senior");
        assertEquals("senior", reg.defaultAgent());
        assertThrows(IllegalArgumentException.class, () -> reg.setDefault("compaction"));
        reg.reset();
        assertTrue(reg.blocked().isEmpty());
        assertEquals("main", reg.defaultAgent());
    }
}
