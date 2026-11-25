package io.ailink.agentforge.bitcask;

/**
 * 一条检索命中。
 *
 * @param key   文档 key（UTF-8 解码）
 * @param ord   记录的 LSN（按无符号）
 * @param score 分数（BM25 / 相似度 / RRF，越大越好）
 */
public record SearchHit(String key, long ord, double score) {
}
