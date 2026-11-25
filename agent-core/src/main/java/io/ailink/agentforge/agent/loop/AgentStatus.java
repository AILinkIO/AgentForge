package io.ailink.agentforge.agent.loop;

/** 一趟循环怎么结束的。 */
public enum AgentStatus {
    /** 模型答完、没再调工具。 */
    COMPLETED,
    /** 用户在闸上按了停（或按停令牌翻了，但循环已正常收口）。 */
    STOPPED,
    /** 用满了轮数上限。 */
    MAX_ITERATIONS,
    /** 某个 stop-when 条件成立（例如上下文满了）。 */
    STOP_WHEN,
    /** 某件工具要求把结果直接返回。 */
    RETURN_DIRECT;

    public String wire() { return name().toLowerCase().replace('_', '-'); }
}
