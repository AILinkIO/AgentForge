package io.ailink.agentforge.agent.loop;

import java.util.Map;

/**
 * 一次请求的调参（与 provider 无关的那一层）：模型、输出上限、思考档位、prompt cache。
 * {@code extra} 留给具体 caller 的私有键。
 */
public record RequestOptions(String model, Integer maxTokens, String thinking, boolean promptCache,
                             Map<String, Object> extra) {
    public RequestOptions {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public static RequestOptions of(String model) { return new RequestOptions(model, null, null, true, Map.of()); }

    public RequestOptions withModel(String m) { return new RequestOptions(m, maxTokens, thinking, promptCache, extra); }

    public RequestOptions withMaxTokens(Integer n) { return new RequestOptions(model, n, thinking, promptCache, extra); }

    public RequestOptions withThinking(String t) { return new RequestOptions(model, maxTokens, t, promptCache, extra); }

    public RequestOptions withPromptCache(boolean on) { return new RequestOptions(model, maxTokens, thinking, on, extra); }
}
