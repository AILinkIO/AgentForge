package io.ailink.agentforge.agent.memory;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tools.Limit;

import java.util.List;

/**
 * 三件笔记工具（coxswain {@code tools/memory.ss}）。描述里写"什么时候该记"，不只写"干什么" ——
 * 只说"写一篇笔记"会得到两种极端：一条不记，或把每一轮都记下来。
 *
 * <p>memory-write 不 sensitive 也不 read-only（不给子 agent：几个子 agent 同写一篇是最后一个赢）。
 */
public final class MemoryTools {
    private MemoryTools() { }

    public static List<AgentTool> tools() { return List.of(memoryList(), memoryRead(), memoryWrite()); }

    public static AgentTool memoryList() {
        return AgentTool.builder("memory-list")
                .description("列出你为**当前项目**记过的笔记（markdown 文件名）。"
                        + "开始干活之前先看一眼：以前记过的结论能省掉一整轮摸索。")
                .readOnly().concurrencySafe()
                .handler((env, a) -> {
                    List<String> ns = Memory.list(env.workspace().root());
                    if (ns.isEmpty()) return "还没有笔记。";
                    StringBuilder b = new StringBuilder();
                    for (String n : ns) b.append(n).append('\n');
                    return b.toString();
                })
                .build();
    }

    public static AgentTool memoryRead() {
        return AgentTool.builder("memory-read")
                .description("读一篇笔记。名字来自 memory-list。")
                .required("name", "string", "笔记文件名，例如 build.md")
                .readOnly().concurrencySafe()
                .handler((env, a) -> {
                    String name = a.requireStr("name");
                    String text = Memory.read(env.workspace().root(), name);
                    return text != null ? Limit.truncateNote(text, 16000, "笔记 " + name) : "没有这篇笔记：" + name;
                })
                .build();
    }

    public static AgentTool memoryWrite() {
        return AgentTool.builder("memory-write")
                .description("记一篇笔记（markdown，整篇覆盖）。**只记跨会话还成立的结论**："
                        + "这个项目怎么构建 / 怎么跑测试、代码里的约定、踩过的坑、用户明确说过的"
                        + "偏好。⚠️ 不要记这一轮的过程、临时状态、或者从文件里读一遍就知道的东西"
                        + "（那些下次读一遍更准）。同名会整篇覆盖 —— 更新一篇就是把新版整篇写回去，"
                        + "所以先 memory-read 再改。")
                .required("name", "string", "文件名，要以 .md 结尾，例如 build.md")
                .required("content", "string", "整篇内容（markdown）")
                .handler((env, a) -> {
                    String name = a.requireStr("name");
                    Memory.write(env.workspace().root(), name, a.requireStr("content"));
                    return "记下了：" + name;
                })
                .build();
    }
}
