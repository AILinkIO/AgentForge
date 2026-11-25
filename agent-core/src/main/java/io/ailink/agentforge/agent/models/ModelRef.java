package io.ailink.agentforge.agent.models;

import java.util.Objects;

/**
 * 一条模型的引用：{@code provider/模型 id}。
 *
 * <p>⚠️ 按**第一个**斜杠分段：模型 id 自己可以带斜杠（openrouter 那种 {@code "a/b/c"} 是
 * a 家的 {@code "b/c"}），provider id 不行。发给服务端的是 {@link #modelId()}，不是整串 ——
 * 发整串过去服务端收到的是一个它不认识的名字，回 400，而消息里那个名字看起来还挺对。
 */
public record ModelRef(String providerId, String modelId) {

    public ModelRef {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        if (providerId.isEmpty() || modelId.isEmpty()) throw new IllegalArgumentException("provider id 与模型 id 都不能是空的");
        if (providerId.indexOf('/') >= 0) throw new IllegalArgumentException("provider id 不能含 `/`：" + providerId);
    }

    /** {@code "p/m"} → ModelRef；不是两段式时 null（不抛：调用方拿它来试）。 */
    public static ModelRef parse(String ref) {
        if (ref == null) return null;
        int i = ref.indexOf('/');
        if (i <= 0 || i == ref.length() - 1) return null;
        return new ModelRef(ref.substring(0, i), ref.substring(i + 1));
    }

    /** 拼出来的 ref：两段都不许带斜杠（{@code "a/b" + "c"} 会变成 a 家的 {@code "b/c"}，归错了家比报错难查）。 */
    public static ModelRef of(String providerId, String modelId) {
        if (providerId == null || providerId.isEmpty()) throw new IllegalArgumentException("provider id 要是非空字符串");
        if (modelId == null || modelId.isEmpty()) throw new IllegalArgumentException("模型 id 要是非空字符串");
        if (providerId.contains("/") || modelId.contains("/"))
            throw new IllegalArgumentException("provider id 与模型 id 都不能含 `/` —— ref 由这两段拼出来");
        return new ModelRef(providerId, modelId);
    }

    @Override
    public String toString() { return providerId + "/" + modelId; }
}
