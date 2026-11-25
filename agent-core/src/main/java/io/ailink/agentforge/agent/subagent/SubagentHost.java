package io.ailink.agentforge.agent.subagent;

import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.session.AgentSession;

import java.util.List;

/**
 * task 工具要宿主回答的几个问题（coxswain {@code make-subagent-tool} 的那些选项：defs / child / route / pin /
 * escalate / max-iterations / depth）。runtime 实现它；测试可以给一个最小的。
 */
public interface SubagentHost {

    /** 派活这个功能开着没有（关掉时工具还在表里，只是调用被挡回去 —— 为一个开关重建会话等于扔掉对话历史）。 */
    default boolean enabled() { return true; }

    /** 能套几层：默认 1 = 主 agent 派出去的那一趟就是最深的一层（它手里看不见 task）。代价是乘上去的。 */
    default int maxDepth() { return 1; }

    /** 每一趟最多绕几轮；null = 不限（用满时补一问叫它收尾）。 */
    default Integer maxIterations() { return null; }

    /** 派得出去的那些 agent（按注册顺序：枚举顺序要稳定，模型对它敏感）。 */
    List<AgentDef> defs();

    /** 为 {@code def} 建一段子会话（工具、系统提示、闸怎么走都在这里定）。{@code id} = 那次 task 调用的 tool_use_id。 */
    AgentSession newChild(AgentSession parent, AgentDef def, String desc, String id);

    /** 选模型：钉的 → 路由 → 主模型。 */
    record Choice(String model, String why) { }

    Choice chooseModel(AgentSession parent, AgentDef def, TaskKind kind);

    /** 降级跑砸了：换回哪条更强的重来一次；null = 不升档。 */
    default String escalate(AgentSession parent, String usedModel) {
        String main = parent.model();
        return main != null && !main.equals(usedModel) ? main : null;
    }
}
