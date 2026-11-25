package io.ailink.agentforge.agent.models;

/**
 * Anthropic 形的 prompt cache 档位（对应 coxswain {@code (agent-prompt-cache '5m | '1h | #f)}，默认 5 分钟）。
 *
 * <p>agent 每一轮只在末尾追加，于是从第二轮起前面那一大段按缓存价（约 0.1×）计。写入比普通输入贵
 * （5 分钟 1.25×、1 小时 2×），第二次读到就回本。⚠️ OpenAI 形不需要这一项：前缀缓存是服务端自动做的。
 */
public enum PromptCache {
    /** 一个断点都不下。 */
    OFF,
    /** 工具 + 系统提示 + 消息末尾，都是 5 分钟。 */
    FIVE_MINUTES,
    /** 系统提示那段留 1 小时（写入 2×），消息末尾那个仍是 5 分钟（每轮都被新内容取代，拉长只多花钱）。 */
    ONE_HOUR
}
