package io.ailink.agentforge.agent.transcript;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记录区的一条（coxswain {@code message-entry}）：给 UI 画的投影，**不是**发给模型的那份历史。
 *
 * <p>⚠️ 与 {@code Message} 不是一回事：这里只有角色 + 文本 + 元数据，thinking 签名、tool_use id 的完整关联都不在 ——
 * 存盘必须存 history，不能存这个。
 *
 * <p>meta 常用键（值都是普通 Java 值）：{@code tool-name}、{@code kind}（call / result）、{@code tool-use-id}、
 * {@code args-preview}、{@code args}（ObjectNode）、{@code tool-error}、{@code summary}、{@code thinking}、{@code think-ms}、
 * {@code error}（系统条是一条错）、子 agent 那组：{@code group}、{@code sub-steps}、{@code sub-ms}、{@code sub-bg}、
 * {@code sub-who}、{@code sub-model}、{@code sub-status}、{@code sub-why}。
 */
public record Entry(Role role, String text, Map<String, Object> meta) {

    public enum Role { USER, ASSISTANT, TOOL, SYSTEM }

    public Entry {
        text = text == null ? "" : text;
        meta = meta == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(meta));
    }

    public Object meta(String k) { return meta.get(k); }

    public boolean flag(String k) { return Boolean.TRUE.equals(meta.get(k)); }

    public static Entry user(String text) { return new Entry(Role.USER, text, Map.of()); }

    public static Entry assistant(String text) { return new Entry(Role.ASSISTANT, text, Map.of()); }

    public static Entry system(String text, boolean error) {
        return new Entry(Role.SYSTEM, text, error ? Map.of("error", true) : Map.of());
    }

    public static Entry thinking(String text, Long ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thinking", true);
        if (ms != null) m.put("think-ms", ms);
        return new Entry(Role.ASSISTANT, text, m);
    }

    /** 是不是某次工具调用那一条（kind = call）。 */
    public boolean isCall() { return role == Role.TOOL && "call".equals(meta.get("kind")); }

    public String toolUseId() { return meta.get("tool-use-id") instanceof String s ? s : null; }
}
