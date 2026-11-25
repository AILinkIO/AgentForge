package io.ailink.agentforge.bitcask;

import java.util.List;

/**
 * 带高亮片段的命中（冷文档且没要 {@code ColdMode.LOAD} ⇒ 片段为空）。
 *
 * @param key      文档 key
 * @param ord      记录的 LSN
 * @param score    BM25 分数
 * @param snippets 高亮片段
 */
public record HighlightHit(String key, long ord, double score, List<String> snippets) {
}
