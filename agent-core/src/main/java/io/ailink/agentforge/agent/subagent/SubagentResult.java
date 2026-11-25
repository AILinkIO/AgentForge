package io.ailink.agentforge.agent.subagent;

import io.ailink.agentforge.agent.loop.AgentStatus;
import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;

import java.util.List;

/**
 * 一趟子 agent 的结果（coxswain {@code subagent-result}）。
 *
 * @param text     交还给派活那一方的话（已按状态加了"没做完 / 被截断"的标注）
 * @param status   循环怎么结束的；出错 / 被停时 null（那时 text 是原因）
 * @param model    实际跑在哪条模型上（可能是路由 / 升档换过的）
 */
public record SubagentResult(String text, AgentStatus status, Usage usage, List<Step> steps, List<Message> messages,
                             String model) {
    public boolean completed() { return status == AgentStatus.COMPLETED || status == AgentStatus.RETURN_DIRECT; }
}
