package io.ailink.agentforge.agent.models;

import java.util.List;

/**
 * 模型表里的一条：会话层要的元数据都在这里。
 *
 * @param contextWindow  窗口（token）；null = 不知道（会话层对"不知道"的正确反应是不作为，别当 0）
 * @param maxOutput      输出上限；null = 不知道
 * @param thinkingLevels 可选档位（见 {@link ThinkingLevels}）；**空表 = 知道，就是没有**；null = 不知道
 *                       （用户手写、没给的那种）
 * @param builtin        true = 从目录自动注册的（每次重算会撤掉重来）；false = 用户自己写的（重算不动）
 * @param facts          目录事实（价钱、会不会看图…）；用户手写的模型可能是 null
 */
public record ModelMeta(
        ModelRef ref,
        String label,
        Long contextWindow,
        Long maxOutput,
        List<String> thinkingLevels,
        boolean builtin,
        ModelFacts facts) {

    public ModelMeta {
        thinkingLevels = thinkingLevels == null ? null : List.copyOf(thinkingLevels);
        if (label == null) label = "";
    }

    public boolean takesAttachment() { return facts != null && facts.takesAttachment(); }

    public boolean reasons() { return facts != null && facts.reasons(); }

    /** 给路由 / 比价用的事实：手写模型没有目录事实时，用窗口与上限拼一份（其余未知）。 */
    public ModelFacts factsOrMinimal() {
        if (facts != null) return facts;
        return ModelFacts.empty().withName(label).withLimits(contextWindow, maxOutput);
    }
}
