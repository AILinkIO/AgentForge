package io.ailink.agentforge.agent.cost;

import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.models.ModelFacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 哪条模型跑这活更便宜（对应 coxswain {@code cost.ss}）。
 *
 * <h2>⚠️ 这不是记账，是**比价**</h2>
 * 用户原话："我们并不为了统计用了多少钱，我们只是来预估哪个模型会更便宜。" 记账要的是准，比价要的是**同向**
 * —— 下面三处已知偏差在两条模型上是同向的，做除法时大部分抵消。所以选模型用 {@link #costRatio} /
 * {@link #rankByCost}，**不要**先各算一个绝对值再自己相减。绝对值（{@link #usageCost}）是副产品：一个数量级参考。
 *
 * <h2>已知偏差（两处偏高一处偏低）</h2>
 * <ol>
 *   <li>分层计价没算（目录的 tiers 不自洽，不抄）→ 长上下文**偏低**；</li>
 *   <li>缺 cache_read 单价时按 input 价算 → **偏高**；</li>
 *   <li>缺 cache_write 单价时按 input 价算 → **偏低**（写缓存通常更贵）。</li>
 * </ol>
 * ⚠️ 兜底一律选**已知的那个数**（input 价），不选 0：把不知道的单价当 0 等于"它免费"，在比价里会让一条我们
 * 一无所知的模型稳稳排在第一。
 *
 * <h2>⚠️ 没有单价就是**不知道**（null），不是 0，也不是"最便宜"</h2>
 *
 * <p>纯函数，离线可测。
 */
public final class Cost {

    /** 评估口径（用户定的）：缓存命中率 90%。 */
    public static final double DEFAULT_CACHE_HIT_RATE = 0.9;
    /** 输入:输出 = 1:2。⚠️ 对 agent 的活不典型（输入重、输出轻），这是定下来的口径，不是实测。 */
    public static final double DEFAULT_OUTPUT_RATIO = 2;
    /** 以 100 万输入 token 为基准。 */
    public static final double DEFAULT_BASIS_TOKENS = 1_000_000;

    private Cost() { }

    /** 四个单价（每百万 token 美元）。 */
    public record Prices(double input, double output, double cacheRead, double cacheWrite) { }

    /**
     * 目录里那四个单价，缺的按兜底补齐。⚠️ **input 或 output 缺一个就整体 null**：主体编不出来时算出来的数
     * 没有意义，而一个没有意义的数摆在状态栏上会被当成真的。
     */
    public static Prices perMillion(ModelFacts f) {
        if (f == null || f.costInput() == null || f.costOutput() == null) return null;
        double in = f.costInput();
        return new Prices(in, f.costOutput(),
                f.costCacheRead() != null ? f.costCacheRead() : in,
                f.costCacheWrite() != null ? f.costCacheWrite() : in);
    }

    /**
     * 一份会话层的 {@link Usage} → 画像。Usage 已经是 Anthropic 口径（{@code input} 不含缓存读写），直接对上。
     */
    public static CostProfile usageProfile(Usage u) {
        if (u == null) return CostProfile.ZERO;
        return new CostProfile(u.input(), u.cacheRead(), u.cacheWrite(), u.output());
    }

    /**
     * provider 原样报的用量（字符串键）→ 画像。认口径靠**哪个键在**，不靠 provider 名字：
     * Anthropic 形 {@code cache_read_input_tokens} 与 {@code input_tokens} **并列**；OpenAI 形 {@code cached_tokens}
     * **含在** {@code input_tokens} 里，要先减出来（不减就多收一遍缓存那部分的钱）。两个键都在时以 Anthropic 为准。
     */
    public static CostProfile usageProfile(Map<String, ? extends Number> usage) {
        if (usage == null) return CostProfile.ZERO;
        double input = num(usage, "input_tokens", 0);
        Number anth = usage.get("cache_read_input_tokens");
        Number oai = usage.get("cached_tokens");
        double in = anth != null ? input : Math.max(0, input - (oai != null ? oai.doubleValue() : 0));
        double cr = anth != null ? anth.doubleValue() : oai != null ? oai.doubleValue() : 0;
        return new CostProfile(in, cr, num(usage, "cache_creation_input_tokens", 0), num(usage, "output_tokens", 0));
    }

    private static double num(Map<String, ? extends Number> m, String k, double d) {
        Number v = m.get(k);
        return v == null ? d : v.doubleValue();
    }

    /** 分项（美元）。 */
    public record Breakdown(double total, double input, double cacheRead, double cacheWrite, double output) { }

    /** 一份画像在某条模型上的分项；不知道单价时 null。**比价就是这个函数换 facts 调两次。** */
    public static Breakdown profileCostBreakdown(CostProfile p, ModelFacts f) {
        Prices pr = perMillion(f);
        if (pr == null) return null;
        double ci = p.input() * pr.input() / 1e6;
        double cr = p.cacheRead() * pr.cacheRead() / 1e6;
        double cw = p.cacheWrite() * pr.cacheWrite() / 1e6;
        double co = p.output() * pr.output() / 1e6;
        return new Breakdown(ci + cr + cw + co, ci, cr, cw, co);
    }

    public static Double profileCost(CostProfile p, ModelFacts f) {
        Breakdown b = profileCostBreakdown(p, f);
        return b == null ? null : b.total();
    }

    /** 一份 Usage 的估价（美元），不知道单价时 null。⚠️ 是估算，显示时用 {@link #format}（带 ≈）。 */
    public static Double usageCost(Usage u, ModelFacts f) { return profileCost(usageProfile(u), f); }

    public static Breakdown usageCostBreakdown(Usage u, ModelFacts f) { return profileCostBreakdown(usageProfile(u), f); }

    /**
     * 评估画像：{@code n} 输入 token 里 {@code hit} 命中缓存，输出是输入的 {@code ratio} 倍，写缓存为 0（公式里没有
     * 这一项 —— 顺带绕开了 cache_write 那条兜底）。
     */
    public static CostProfile evaluationProfile(double inputTokens, double outputRatio, double cacheHitRate) {
        if (cacheHitRate < 0 || cacheHitRate > 1) throw new IllegalArgumentException("cacheHitRate 要在 0 和 1 之间：" + cacheHitRate);
        if (outputRatio < 0) throw new IllegalArgumentException("outputRatio 不能是负的：" + outputRatio);
        if (inputTokens <= 0) throw new IllegalArgumentException("inputTokens 要是正数：" + inputTokens);
        return new CostProfile(inputTokens * (1 - cacheHitRate), inputTokens * cacheHitRate, 0, inputTokens * outputRatio);
    }

    /** 标准画像：100 万输入、90% 命中、1:2。 */
    public static CostProfile standardProfile() {
        return evaluationProfile(DEFAULT_BASIS_TOKENS, DEFAULT_OUTPUT_RATIO, DEFAULT_CACHE_HIT_RATE);
    }

    /**
     * 给模型标价：跑一份标准画像要多少美元；null = 目录没给单价。
     * ⚠️ 90% 命中率把"没有 cached 单价"放大了：那些模型被系统性高估，名次偏后（倾向于不换过去 —— 保守那一边）。
     */
    public static Double modelPrice(ModelFacts f) { return profileCost(standardProfile(), f); }

    /** B 相对 A 的倍数（&lt;1 = B 更便宜）。任一边不知道单价、或基准是 0 → null（不做除以零，也不假装无穷便宜）。 */
    public static Double costRatio(CostProfile p, ModelFacts a, ModelFacts b) {
        Double ca = profileCost(p, a), cb = profileCost(p, b);
        return ca == null || cb == null || ca <= 0 ? null : cb / ca;
    }

    /** 排好的一条：标识 + 美元。 */
    public record Priced(String ref, double price) { }

    /**
     * 排序结果。⚠️ **没单价的单独交出来，不排进去也不悄悄扔掉**：调用方必须知道有哪些没参与排序，
     * 否则它看到的是一张"完整"的榜单。
     */
    public record Ranking(List<Priced> ranked, List<String> unknown) { }

    /** 便宜的在前；**稳定**排序（同价保持原序 —— 抖动会让人以为排序依据里还有别的东西）。 */
    public static Ranking rankByCost(CostProfile p, List<Candidate> candidates) {
        List<Priced> priced = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (Candidate c : candidates) {
            Double m = profileCost(p, c.facts());
            if (m != null) priced.add(new Priced(c.ref(), m));
            else unknown.add(c.ref());
        }
        priced.sort(Comparator.comparingDouble(Priced::price));   // List.sort 是稳定的
        return new Ranking(List.copyOf(priced), List.copyOf(unknown));
    }

    /**
     * 美元 → {@code "≈$0.0034"}；不是数 / 负数 → null。
     * ⚠️ {@code ≈} 是那三条已知偏差的标记，去掉它就等于说这是账单。⚠️ **绝不显示 {@code $0.00}**：小到显示不出来时
     * 说 {@code <$0.0001}。位数随大小变；**半数进位**（不是 banker's）—— 错的方向必须是偏高。
     */
    public static String format(Double x) {
        if (x == null || x.isNaN() || x < 0) return null;
        if (x == 0) return "≈$0";
        if (x >= 1) return "≈$" + fixed(x, 2);
        if (x >= 0.01) return "≈$" + fixed(x, 3);
        if (x >= 0.00005) return "≈$" + fixed(x, 4);
        return "<$0.0001";
    }

    private static String fixed(double x, int n) {
        return BigDecimal.valueOf(x).setScale(n, RoundingMode.HALF_UP).toPlainString();
    }
}
