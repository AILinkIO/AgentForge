package io.ailink.agentforge.agent.registry;

import io.ailink.agentforge.agent.route.TaskKind;

import java.util.List;
import java.util.function.Function;

/**
 * 配置里定义 / 覆盖一条 agent 的选项（对应 coxswain {@code (agent-define! '名字 'description … 'tools 'read-only …)}）。
 *
 * <p>**没写的字段 = 没碰**（null）：{@link AgentRegistry#override} 只改写了的那几项，其余沿用内置 —— 内置的提示词与
 * 工具集于是继续跟着版本更新走（整条重定义的复制品从此是死的）。
 *
 * <p>工具集：{@link #tools(ToolPreset)} 或 {@link #tools(List)} 定底，{@link #toolsPlus} / {@link #toolsMinus} 增删。
 * 用**名字**而不是工具对象（配置里拿不到那些对象）；名字**当场校验**。⚠️ 预设为底再增删，是为了让新增内置工具时
 * 用户的配置自动跟上。⚠️ 先减后加：两边写了同一个名字时**加赢**。
 */
public final class AgentConfig {

    public enum ToolPreset { READ_ONLY, DEFAULT, NONE }

    String description;
    Function<String, String> prompt;
    ToolPreset preset;
    List<String> toolNames;
    List<String> toolsPlus;
    List<String> toolsMinus;
    List<String> models;
    TaskKind taskKind;
    boolean taskKindSet;
    Boolean allowSensitive;
    Boolean hidden;

    private AgentConfig() { }

    public static AgentConfig of() { return new AgentConfig(); }

    public AgentConfig description(String v) { this.description = v; return this; }

    public AgentConfig prompt(String v) { this.prompt = v == null ? null : root -> v; return this; }

    /** 收 root 返回提示词。 */
    public AgentConfig prompt(Function<String, String> v) { this.prompt = v; return this; }

    public AgentConfig tools(ToolPreset v) { this.preset = v; this.toolNames = null; return this; }

    public AgentConfig tools(List<String> names) { this.toolNames = List.copyOf(names); this.preset = null; return this; }

    public AgentConfig toolsPlus(List<String> names) { this.toolsPlus = List.copyOf(names); return this; }

    public AgentConfig toolsMinus(List<String> names) { this.toolsMinus = List.copyOf(names); return this; }

    /** 钉一条模型。 */
    public AgentConfig model(String ref) { this.models = ref == null ? null : List.of(ref); return this; }

    /** 钉一张按优先顺序排的模型表（备选链）。⚠️ 空表当场报：它读起来像"钉了但什么都没钉"。 */
    public AgentConfig models(List<String> refs) { this.models = refs == null ? null : List.copyOf(refs); return this; }

    public AgentConfig taskKind(TaskKind v) { this.taskKind = v; this.taskKindSet = true; return this; }

    /** ⚠️ 只认布尔：写个别的值等于"放行"的读法会让自以为是的写法悄悄变成放行。 */
    public AgentConfig allowSensitive(boolean v) { this.allowSensitive = v; return this; }

    /** 用户**不能**设 hidden（定义 / 覆盖都会报错）；留着这个口子只为了能当场说清为什么。 */
    public AgentConfig hidden(boolean v) { this.hidden = v; return this; }

    boolean toolsTouched() { return preset != null || toolNames != null || toolsPlus != null || toolsMinus != null; }
}
