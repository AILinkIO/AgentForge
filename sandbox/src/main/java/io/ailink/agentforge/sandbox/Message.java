package io.ailink.agentforge.sandbox;

import java.util.Objects;

/**
 * 带稳定错误码的文案（≙ C# KeelsonMessage）：{@code code} 给程序判别，{@code text} 给人 / 模型看（随界面语言变，⛔ 拿来比对）。
 *
 * @param code 稳定错误码，如 {@code sandbox/write-denied}
 * @param text 已渲染的文案
 */
public record Message(String code, String text) {
    public Message {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(text, "text");
    }

    /** 按当前语言渲染 {@code code} 的文案。 */
    public static Message of(String code, Object... args) {
        return Messages.message(code, args);
    }

    @Override
    public String toString() {
        return text;
    }
}
