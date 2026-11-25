package io.ailink.agentforge.bitcask;

import java.nio.charset.StandardCharsets;

/**
 * 一条拷贝出来的迭代条目（meta 没要 / 没有 ⇒ 空数组）。
 *
 * @param key       key 字节
 * @param value     值字节
 * @param timestamp 写入时间戳
 * @param ord       记录的 LSN
 * @param meta      meta 段
 */
public record Entry(byte[] key, byte[] value, long timestamp, long ord, byte[] meta) {
    /** key 按 UTF-8 解码。 */
    public String keyString() {
        return new String(key, StandardCharsets.UTF_8);
    }

    /** 值按 UTF-8 解码。 */
    public String valueString() {
        return new String(value, StandardCharsets.UTF_8);
    }
}
