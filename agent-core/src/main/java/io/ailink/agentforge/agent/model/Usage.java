package io.ailink.agentforge.agent.model;

/**
 * 一次（或累计的）用量。
 *
 * <p>⚠️ 两家口径不同：Anthropic 的 {@code input} 不含缓存读写（三者并列），OpenAI 形的
 * {@code prompt_tokens} 含着 cached。编解码层统一成 Anthropic 口径：{@code input} 只是
 * 没命中缓存的那部分，{@link #totalInput()} 才是"这次发出去多少"。
 */
public record Usage(long input, long output, long cacheRead, long cacheWrite, long reasoning) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0, 0);

    public Usage plus(Usage o) {
        if (o == null) return this;
        return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead,
                cacheWrite + o.cacheWrite, reasoning + o.reasoning);
    }

    /** 这一次发出去的全部输入（含从缓存读回来的那部分）。 */
    public long totalInput() { return input + cacheRead + cacheWrite; }

    /** 缓存命中率（0..1）；没有输入时返回 -1。 */
    public double cacheHitRate() {
        long t = totalInput();
        return t == 0 ? -1 : (double) cacheRead / t;
    }

    public boolean isZero() { return input == 0 && output == 0 && cacheRead == 0 && cacheWrite == 0; }
}
