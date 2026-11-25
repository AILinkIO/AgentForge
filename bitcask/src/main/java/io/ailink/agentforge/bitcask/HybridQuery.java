package io.ailink.agentforge.bitcask;

/** RRF 混合检索的一条查询：{@code text} null = 纯向量；{@code vector} null / 空 = 纯文本（至少给一路）。 */
public record HybridQuery(String text, float[] vector) {
}
