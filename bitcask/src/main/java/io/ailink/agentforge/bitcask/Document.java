package io.ailink.agentforge.bitcask;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一篇待写文档（{@code bitcask_doc_input_ex_t}）：正文、命名字段（写入序）、向量、meta 段、绝对过期秒。
 * meta 可以是任意字节，但只有 {@link Meta#encode} 编出来的才被过滤认得。数组不拷贝。
 *
 * @param text      正文（null ⇒ 空串）
 * @param fields    命名字段（null ⇒ 无；保持写入序）
 * @param vector    向量（null ⇒ 无；长度须等于库的维度）
 * @param meta      meta 段（null ⇒ 无）
 * @param expiresAt 绝对过期纪元秒（0 = 永不）
 */
public record Document(String text, Map<String, String> fields, float[] vector, byte[] meta, long expiresAt) {
    public Document {
        if (text == null) text = "";
        fields = fields == null || fields.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        if (vector == null) vector = new float[0];
        if (meta == null) meta = new byte[0];
        if (expiresAt < 0) throw new IllegalArgumentException("expiresAt");
    }

    /** 只有正文。 */
    public static Document of(String text) {
        return new Document(text, null, null, null, 0);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 绝对过期时刻 ⇒ 纪元秒：null ⇒ 0（永不）；早于纪元 ⇒ 1（已过期）。 */
    public static long expirySeconds(Instant at) {
        return at == null ? 0 : Math.max(1, at.getEpochSecond());
    }

    /** 文档构造器（字段按调用序）。 */
    public static final class Builder {
        private String text = "";
        private final LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        private float[] vector;
        private byte[] meta;
        private long expiresAt;

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder field(String name, Object value) {
            fields.put(Objects.requireNonNull(name, "name"), String.valueOf(value));
            return this;
        }

        public Builder fields(Map<String, ?> fields) {
            fields.forEach(this::field);
            return this;
        }

        public Builder vector(float... vector) {
            this.vector = vector;
            return this;
        }

        /** 数列转 float。 */
        public Builder vector(double... vector) {
            float[] v = new float[vector.length];
            for (int i = 0; i < v.length; i++) v[i] = (float) vector[i];
            this.vector = v;
            return this;
        }

        /** 原样的 meta 段。 */
        public Builder meta(byte[] meta) {
            this.meta = meta;
            return this;
        }

        /** 结构化 meta（经 {@link Meta#encode(Map)} 编码）。 */
        public Builder meta(Map<String, ?> meta) {
            this.meta = Meta.encode(meta);
            return this;
        }

        public Builder expiresAt(Instant at) {
            this.expiresAt = expirySeconds(at);
            return this;
        }

        public Builder expiresAtEpochSecond(long epochSecond) {
            this.expiresAt = epochSecond <= 0 ? 0 : epochSecond;
            return this;
        }

        public Document build() {
            return new Document(text, fields, vector, meta, expiresAt);
        }
    }
}
