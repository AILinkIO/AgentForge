package io.ailink.agentforge.agent.models;

/**
 * 一家 provider 说哪种线格式。决定建哪种 Spring AI {@code ChatModel}、思考档位怎么发、
 * 用量怎么归一。
 *
 * <p>⚠️ 只有两种：coxswain 那边的 {@code deepseek} / {@code minimax} 是各自一个适配器，
 * 这里它们只是"OpenAI 兼容 + 默认端点"与"Anthropic 兼容 + 默认端点"（见 {@link Models} 的
 * 已知 provider 表）。本地 llama.cpp（{@code local}）没有移植。
 */
public enum ApiKind {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI 兼容");

    private final String label;

    ApiKind(String label) { this.label = label; }

    /** 选择器里显示"这条走哪家格式"。 */
    public String label() { return label; }

    /**
     * models.dev 的 {@code npm} 字段 → 格式。**只当线索**：我们一个 npm 包都不装，那个字段只是
     * "这家说哪种格式"最省事的一个说法。认不出 → null（那时用户必须自己写格式）。
     */
    public static ApiKind fromNpm(String npm) {
        if (npm == null) return null;
        return switch (npm) {
            case "@ai-sdk/anthropic" -> ANTHROPIC;
            case "@ai-sdk/openai", "@ai-sdk/openai-compatible", "@ai-sdk/deepseek" -> OPENAI;
            default -> null;
        };
    }

    /** 配置里写的名字（{@code "anthropic"} / {@code "openai"}，大小写不敏感）→ 格式；认不出 null。 */
    public static ApiKind parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase()) {
            case "anthropic", "minimax" -> ANTHROPIC;
            case "openai", "openai-compatible", "deepseek" -> OPENAI;
            default -> null;
        };
    }
}
