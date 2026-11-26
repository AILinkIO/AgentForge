package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.models.ApiKind;

/**
 * 每次请求用哪种 caller 的那一条策略（一个小开关，单独成类好让宿主换）。
 *
 * <p>默认：**Anthropic 线格式一律走原生 {@code AnthropicHttpCaller}**，其余（OpenAI 线格式）走 Spring AI。理由：
 * <ul>
 *   <li>Spring AI 2.0 不把 thinking 块（含签名）回传，而"开思考 + 工具循环"时 Anthropic 要求上一条 assistant 的 thinking
 *       原样带回 —— 走 Spring AI 第二轮就是 400；</li>
 *   <li>Spring AI 2.0 的流式只从 message_start 记下 inputTokens，缓存读写数丢在框架里：只在 message_start 报缓存的端点
 *       命中率永远是 0（见 StartUsageTest）；</li>
 *   <li>cache_control 断点、工具结果里的图片 / PDF 也只有原生 caller 带得全。</li>
 * </ul>
 */
@FunctionalInterface
public interface CallerPolicy {

    boolean nativeAnthropic(ApiKind api, String thinking);

    CallerPolicy DEFAULT = (api, thinking) -> api == ApiKind.ANTHROPIC;

    /** 一律 Spring AI（不在乎思考签名的宿主 / 测试）。 */
    CallerPolicy SPRING_AI_ONLY = (api, thinking) -> false;

    /** 旧的默认：只有 Anthropic + 开了思考才走原生（档位 {@code off} 不算开），其余 Spring AI。 */
    CallerPolicy NATIVE_WHEN_THINKING = (api, thinking) -> api == ApiKind.ANTHROPIC && thinking != null && !thinking.equals("off");

    /** 同 {@link #DEFAULT}（保留这个名字给已有的宿主）。 */
    CallerPolicy NATIVE_ANTHROPIC = DEFAULT;
}
