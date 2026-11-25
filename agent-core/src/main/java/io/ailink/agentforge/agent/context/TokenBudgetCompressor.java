package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;

import java.util.List;

/**
 * 发请求前的最后一道"裁"（coxswain {@code make-token-budget-compressor}）：超预算就从中间丢整组。
 *
 * <p>系数按上一次的真实 input 自校准：{@code tpc = 上次 input token / 上次发出去的字符数}。中文与代码的字符
 * token 比差好几倍，写死一个系数总有一边错。⚠️ 有状态（记着上一次发了多少字符），一趟循环一个实例。
 */
public final class TokenBudgetCompressor {
    /** 裁掉了多少：丢了几条 / 一共几条。 */
    @FunctionalInterface
    public interface OnTrim { void trimmed(int dropped, int total); }

    private final long maxTokens;
    private final int keepHead;
    private final OnTrim onTrim;
    private double tpc = ContextMath.DEFAULT_TOKENS_PER_CHAR;
    private long lastSentChars;

    public TokenBudgetCompressor(long maxTokens, int keepHead, OnTrim onTrim) {
        this.maxTokens = maxTokens;
        this.keepHead = keepHead;
        this.onTrim = onTrim;
    }

    public synchronized List<Message> apply(List<Message> history, Usage lastUsage) {
        if (lastUsage != null && lastUsage.totalInput() > 0 && lastSentChars > 0) {
            tpc = (double) lastUsage.totalInput() / lastSentChars;
        }
        ContextMath.Trimmed t = ContextMath.trim(history, maxTokens, tpc, keepHead);
        lastSentChars = ContextMath.historyChars(t.kept());
        if (onTrim != null && t.dropped() > 0) onTrim.trimmed(t.dropped(), history.size());
        return t.kept();
    }

    public synchronized double tokensPerChar() { return tpc; }
}
