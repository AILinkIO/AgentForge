package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;

/**
 * ReAct 循环的配置（coxswain {@code make-react-loop}）。
 *
 * @param tools          这一趟给模型的工具
 * @param cancel         按停令牌：轮与轮之间、工具前后都看它
 * @param maxIterations  最多问模型几次（含最后那次收尾）
 * @param stopWhen       每跑完一轮工具、下一次问模型之前看一眼；返回非 null 即以 STOP_WHEN 结束（理由原样带出）
 * @param finalNudge     用满轮数时补发的那句话（叫模型收尾、不再调工具）；null = 不补
 * @param roundNudge     轮中提醒：返回非 null 就作为文本块并进这一轮的工具结果消息
 * @param parallel       相邻的 concurrency-safe 工具是否并发跑（子 agent 的内联引擎为 false）
 */
public record ReactLoop(List<AgentTool> tools, CancelToken cancel, int maxIterations, List<StopWhen> stopWhen,
                        String finalNudge, RoundNudge roundNudge, boolean parallel) {

    public ReactLoop {
        tools = tools == null ? List.of() : List.copyOf(tools);
        cancel = cancel == null ? new CancelToken() : cancel;
        stopWhen = stopWhen == null ? List.of() : List.copyOf(stopWhen);
        if (maxIterations <= 0) maxIterations = 50;
    }

    public static ReactLoop of(List<AgentTool> tools, CancelToken cancel, int maxIterations) {
        return new ReactLoop(tools, cancel, maxIterations, List.of(), null, null, true);
    }

    @FunctionalInterface
    public interface StopWhen {
        /** @param turn 上一次回答；@return 停下的理由，或 null 继续 */
        String check(ModelTurn turn, List<Message> history);
    }

    @FunctionalInterface
    public interface RoundNudge {
        /**
         * @param round 刚跑完第几轮（1 起）
         * @param steps 这一趟到此为止跑过的全部工具（按完成顺序）
         * @return 提醒文本或 null
         */
        String nudge(int round, int maxIterations, List<Message> history, List<Step> steps);
    }
}
