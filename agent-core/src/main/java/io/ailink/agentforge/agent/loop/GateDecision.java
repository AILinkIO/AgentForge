package io.ailink.agentforge.agent.loop;

import tools.jackson.databind.node.ObjectNode;

/**
 * 审批闸的答案：放行 / 放行但改了参数（例如删除确认把 path 改写成确认过的目标）/
 * 拒绝（附一句给模型的话）/ 停掉这一轮。
 */
public sealed interface GateDecision {
    record Allow(ObjectNode input) implements GateDecision { }

    record Deny(String reason) implements GateDecision { }

    record Stop() implements GateDecision { }

    GateDecision ALLOW = new Allow(null);
    GateDecision STOP = new Stop();

    static GateDecision deny(String reason) { return new Deny(reason); }
}
