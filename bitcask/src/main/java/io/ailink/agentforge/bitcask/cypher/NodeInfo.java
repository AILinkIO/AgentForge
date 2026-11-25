package io.ailink.agentforge.bitcask.cypher;

import java.util.List;
import java.util.Map;

/**
 * 顶点的模型视图（clj 的 {:labels :properties :vector}）：vector 是 float[]、null，或 {@link #UNKNOWN}
 * （range 批量读回的文档不带向量，真用到时再单独读）。
 */
record NodeInfo(List<String> labels, Map<String, Object> properties, Object vector) {
    static final Kw UNKNOWN = Kw.of("unknown-vector");
    static final NodeInfo EMPTY = new NodeInfo(List.of(), Map.of(), null);
}
