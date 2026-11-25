package io.ailink.agentforge.bitcask;

import java.util.List;

/**
 * 字段检索的命中：附命中的字段名（去重、首次命中序；空串 = 默认字段 text）。
 *
 * @param key    文档 key
 * @param ord    记录的 LSN
 * @param score  BM25 分数
 * @param fields 命中的字段名
 */
public record FieldsHit(String key, long ord, double score, List<String> fields) {
}
