package io.ailink.agentforge.agent.models;

import java.util.List;

/**
 * models.dev 关于一条模型说了什么（只抄我们要的那些）。
 *
 * <p>⚠️ **null = 目录没写**，不是 0 / false：{@code context == null} 是"我们没读到"，
 * {@code context == 0} 是"这条模型没有窗口这个概念"（图像模型真的是 0）。两者的处置不同。
 *
 * <p>⚠️ 分层计价（tiers / context_over_200k）**不抄**：源数据自己就不自洽，抄进来等于把一个
 * 我们没法判真假的数字摆成事实。
 *
 * @param reasoningEffort    厂商给的档位名，原样、顺序照抄；源数据里混着的 null 已筛掉（否则选择器
 *                           里会多一行空白档位，选中之后发出去的是 null）
 * @param reasoningBudget    目录里有没有 {@code budget_tokens} 这一项（min/max 都可以没有）
 */
public record ModelFacts(
        String name,
        Long context,
        Long output,
        Double costInput,
        Double costOutput,
        Double costCacheRead,
        Double costCacheWrite,
        Boolean reasoning,
        boolean reasoningToggle,
        List<String> reasoningEffort,
        boolean reasoningBudget,
        Long reasoningBudgetMin,
        Long reasoningBudgetMax,
        Boolean toolCall,
        Boolean attachment,
        String releaseDate,
        String lastUpdated) {

    public ModelFacts {
        reasoningEffort = reasoningEffort == null || reasoningEffort.isEmpty() ? null : List.copyOf(reasoningEffort);
    }

    /** 测试 / 手写模型用的空白一条，配合 {@code with*} 拼。 */
    public static ModelFacts empty() {
        return new ModelFacts(null, null, null, null, null, null, null, null, false, null, false,
                null, null, null, null, null, null);
    }

    public boolean reasons() { return Boolean.TRUE.equals(reasoning); }

    public boolean callsTools() { return Boolean.TRUE.equals(toolCall); }

    public boolean takesAttachment() { return Boolean.TRUE.equals(attachment); }

    /** 能不能用来干活：有窗口且会调工具（目录里有一堆图像 / 嵌入模型，列进选择器只会撞错）。 */
    public boolean usable() { return context != null && context > 0 && callsTools(); }

    public ModelFacts withName(String v) { return new ModelFacts(v, context, output, costInput, costOutput, costCacheRead, costCacheWrite, reasoning, reasoningToggle, reasoningEffort, reasoningBudget, reasoningBudgetMin, reasoningBudgetMax, toolCall, attachment, releaseDate, lastUpdated); }

    public ModelFacts withLimits(Long ctx, Long out) { return new ModelFacts(name, ctx, out, costInput, costOutput, costCacheRead, costCacheWrite, reasoning, reasoningToggle, reasoningEffort, reasoningBudget, reasoningBudgetMin, reasoningBudgetMax, toolCall, attachment, releaseDate, lastUpdated); }

    public ModelFacts withCost(Double in, Double out, Double cacheRead, Double cacheWrite) { return new ModelFacts(name, context, output, in, out, cacheRead, cacheWrite, reasoning, reasoningToggle, reasoningEffort, reasoningBudget, reasoningBudgetMin, reasoningBudgetMax, toolCall, attachment, releaseDate, lastUpdated); }

    public ModelFacts withAbilities(Boolean reasons, Boolean tools, Boolean attach) { return new ModelFacts(name, context, output, costInput, costOutput, costCacheRead, costCacheWrite, reasons, reasoningToggle, reasoningEffort, reasoningBudget, reasoningBudgetMin, reasoningBudgetMax, tools, attach, releaseDate, lastUpdated); }

    public ModelFacts withReasoningOptions(boolean toggle, List<String> effort, boolean budget, Long budgetMin, Long budgetMax) { return new ModelFacts(name, context, output, costInput, costOutput, costCacheRead, costCacheWrite, reasoning, toggle, effort, budget, budgetMin, budgetMax, toolCall, attachment, releaseDate, lastUpdated); }
}
