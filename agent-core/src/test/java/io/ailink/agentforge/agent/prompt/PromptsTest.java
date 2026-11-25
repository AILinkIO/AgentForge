package io.ailink.agentforge.agent.prompt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptsTest {

    @Test
    void 主提示词() {
        String p = Prompts.agentSystemPrompt("/repo");
        assertTrue(p.startsWith("你是 AgentForge 里的编程助手。当前目录 /repo。"));
        assertTrue(p.contains("## 怎么派活") && p.contains("## 多步的活") && p.contains("## 怎么做事"));
        assertTrue(p.endsWith("简短回答。"));
        assertFalse(p.contains(ToolNames.CURRENT_BUFFER), "命令行宿主没有编辑器缓冲区");
        String e = Prompts.agentSystemPrompt("/repo", Prompts.Host.EDITOR);
        assertTrue(e.contains("coxswain 编辑器右侧的 agent 窗格") && e.contains(ToolNames.CURRENT_BUFFER));
        assertTrue(Prompts.agentStubSystemPrompt(null).endsWith("还没配模型。"));
    }

    @Test
    void 子agent提示词接通用那段() {
        assertTrue(Prompts.DEEP_SYSTEM_PROMPT.startsWith(Prompts.SUBAGENT_SYSTEM_PROMPT));
        assertTrue(Prompts.SENIOR_SYSTEM_PROMPT.startsWith(Prompts.SUBAGENT_SYSTEM_PROMPT));
        assertTrue(Prompts.JUNIOR_SYSTEM_PROMPT.startsWith(Prompts.SUBAGENT_SYSTEM_PROMPT));
        assertFalse(Prompts.VISION_SYSTEM_PROMPT.contains(Prompts.SUBAGENT_SYSTEM_PROMPT), "看图那条一件工具都没有");
    }

    @Test
    void 附件那几句() {
        assertEquals("", Prompts.attachedLine(null, null, null, null), "没附件一个字都不多");
        assertEquals("\n\n⚠️ 这条消息附了一张图：\n  1. a.png\n", Prompts.attachedLine(null, List.of("a.png"), null, null));
        Map<String, Boolean> files = new LinkedHashMap<>();
        files.put("src/A.java", false);
        files.put("docs/", true);
        String s = Prompts.attachedLine(files, List.of("x.png"), List.of(new Prompts.AttachFail("nope.txt", null)), new int[]{3, 10});
        assertTrue(s.contains("2 个文件、1 张图"));
        assertTrue(s.contains("  2. docs/（目录）") && s.contains("  3. x.png（图）"));
        assertTrue(s.contains("没附上：nope.txt（说不上为什么）"));
        assertTrue(s.contains("还有 3 个 @ 提及没有附：一次最多附 10 个"));
        assertTrue(Prompts.attachmentNote(List.of("a", "b")).contains("附了 2 张图"));
    }

    @Test
    void 压缩与收尾() {
        assertTrue(Prompts.compactionInPlacePrompt(null).contains("交接材料"));
        assertTrue(Prompts.compactionInPlacePrompt("锚点").contains("「锚点」**之前**"));
        assertTrue(Prompts.repeatNudgePrompt("edit-file", 3).startsWith("⚠️ `edit-file` 已经连着失败 3 次了。"));
    }
}
