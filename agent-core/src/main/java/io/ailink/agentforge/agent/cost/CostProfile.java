package io.ailink.agentforge.agent.cost;

/**
 * 一份活的"四种 token 各多少"，与单价无关、只与口径有关。
 *
 * <p>比价成立的前提：一份在 Anthropic 上量出来的用量拿去问"同样的活换 DeepSeek 要多少钱"，归一必须**只做一次**，
 * 在这里做完 —— 口径是这份用量的性质，不是那条模型的性质。
 *
 * @param input      要按全价算的输入（没命中缓存的那部分）
 * @param cacheRead  读缓存命中的那部分
 * @param cacheWrite 写缓存的那部分
 * @param output     输出
 */
public record CostProfile(double input, double cacheRead, double cacheWrite, double output) {

    public static final CostProfile ZERO = new CostProfile(0, 0, 0, 0);

    public boolean isZero() { return input == 0 && cacheRead == 0 && cacheWrite == 0 && output == 0; }
}
