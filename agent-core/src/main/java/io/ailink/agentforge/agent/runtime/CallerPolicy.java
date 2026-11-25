package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.models.ApiKind;

/**
 * 每次请求用哪种 caller 的那一条策略（一个小开关，单独成类好让宿主换）。
 *
 * <p>默认：**Anthropic 线格式 + 开了思考 → 原生 {@code AnthropicHttpCaller}**，其余一律 Spring AI。理由：Spring AI 2.0
 * 不把 thinking 块（含签名）回传，而"开思考 + 工具循环"时 Anthropic 要求上一条 assistant 的 thinking 原样带回 ——
 * 走 Spring AI 的话第二轮就是 400。档位 {@code off} 不算开。
 */
@FunctionalInterface
public interface CallerPolicy {

    boolean nativeAnthropic(ApiKind api, String thinking);

    CallerPolicy DEFAULT = (api, thinking) -> api == ApiKind.ANTHROPIC && thinking != null && !thinking.equals("off");

    /** 一律 Spring AI（不在乎思考签名的宿主 / 测试）。 */
    CallerPolicy SPRING_AI_ONLY = (api, thinking) -> false;

    /** Anthropic 一律走原生（cache 断点、工具结果里的 PDF 也要它）。 */
    CallerPolicy NATIVE_ANTHROPIC = (api, thinking) -> api == ApiKind.ANTHROPIC;
}
