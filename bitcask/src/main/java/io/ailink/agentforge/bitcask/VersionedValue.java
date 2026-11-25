package io.ailink.agentforge.bitcask;

import java.nio.charset.StandardCharsets;

/**
 * 值 + key 当前记录的 LSN（{@link Bitcask#getWithOrd}）。ord 只在 key 被写入时改变（merge 搬迁、重开、索引重建都原样保留），
 * 可直接当版本号用作 {@link Condition#ord}。
 */
public record VersionedValue(byte[] value, long ord) {
    public String valueString() {
        return new String(value, StandardCharsets.UTF_8);
    }
}
