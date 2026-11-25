package io.ailink.agentforge.bitcask;

import java.util.Map;

/**
 * 读回的整篇文档（{@code bitcask_get_doc}；字段 id 已由 field.schema 翻回名字）。KV 库与检索库都能读。
 *
 * @param text   正文（纯 KV 写入的值按 UTF-8 解成正文）
 * @param meta   原样的 meta 段（没有 ⇒ null；要结构化再 {@link Meta#decodeMap}）
 * @param vector 向量（没有 ⇒ null）
 * @param fields 命名字段（写入序，只读）
 * @param tstamp 写入时间戳
 * @param ord    记录的 LSN（见 {@link Bitcask#getWithOrd}）
 */
public record StoredDocument(String text, byte[] meta, float[] vector, Map<String, String> fields, long tstamp, long ord) {
}
