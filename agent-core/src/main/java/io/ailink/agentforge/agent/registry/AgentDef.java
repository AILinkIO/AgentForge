package io.ailink.agentforge.agent.registry;

import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一条业务 agent（对应 coxswain {@code agent-def}；照 opencode 的 {@code Agent.Info}）。
 *
 * <p>★ **没有 mode**（primary / subagent）：一条 agent 同时是两种 —— 选择器里列它（切进去 = 主提示词后面接它那一段
 * + 换成它那套工具），派活枚举里也列它。两处各按自己真正需要的判据筛（{@link AgentRegistry#visibleDefs()} /
 * {@link AgentRegistry#subagentDefs()}）。
 *
 * <p>⚠️ 这张表描述"谁"（人格：提示词 / 工具 / 模型），{@link TaskKind} 描述"什么活"（路由门槛）；两张都要，不该合。
 *
 * @param name           {@code task} 工具的 {@code subagent_type} 填的就是它
 * @param description    **给模型看的"什么时候派它"** —— 模型挑错人的唯一依据就是它
 * @param prompt         root → 它自己的系统提示词；**null = 由宿主现给**（main：要按当前目录现算，存一个算好的字符串进去，
 *                       切项目之后就是过期的那一份）
 * @param tools          **惰性**：工具表是运行期算出来的（宿主装配、MCP 后到），存成表的话注册表就得等工具都造好才能建
 * @param taskKind       路由用；null = 跟主会话
 * @param models         钉的模型，按优先顺序（备选链）；空 = 走路由。⚠️ 钉了不等于一定用：派活那一刻对当前可用池，
 *                       第一条用得了的赢，一条都用不了就当没钉**并说出来**
 * @param hidden         系统内部用途（压缩那一类）：不进选择器、不进派活枚举
 * @param allowSensitive 敏感工具的放行位：false = 子 agent 全只读那条硬闸照旧；true = 可以带写工具，代价是每一笔
 *                       敏感调用都过父会话的审批闸（而且**禁止后台跑** —— 那道闸在派活工具那边）
 */
public record AgentDef(
        String name,
        String description,
        Function<String, String> prompt,
        Supplier<List<AgentTool>> tools,
        TaskKind taskKind,
        List<String> models,
        boolean hidden,
        boolean allowSensitive) {

    public AgentDef {
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        tools = tools == null ? List::of : tools;
        models = models == null ? List.of() : List.copyOf(models);
    }

    /** 它的提示词；null = 宿主现给。 */
    public String promptFor(String root) { return prompt == null ? null : prompt.apply(root); }

    public List<AgentTool> toolList() {
        List<AgentTool> ts = tools.get();
        return ts == null ? List.of() : ts;
    }

    /**
     * 派得出去吗：**自足** —— 自己有工具，或者要看的东西随消息附上去（看图那条一件工具都没有，那是有意的）。
     * ⚠️ main 自然落在外面（表里一件工具都没有）—— 这不是名字特判，是判据本身。
     */
    public boolean dispatchable() {
        return !toolList().isEmpty() || (taskKind != null && taskKind.needAttachment());
    }
}
