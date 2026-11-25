package io.ailink.agentforge.bitcask.graph;

import java.util.List;
import java.util.Map;

/**
 * 顶点形态（{@link Graph#putVertex} / {@link Graph#createVertex}）：标签、属性、正文、向量，都可为 null。
 * 正文在检索库里进 BM25（顶点即文档）；属性值见 {@link GraphModel#normPropValue}。
 */
public record VertexSpec(List<String> labels, Map<String, ?> props, String text, float[] vector) {
    public static VertexSpec of(List<String> labels, Map<String, ?> props) {
        return new VertexSpec(labels, props, null, null);
    }

    public static VertexSpec labels(String... labels) {
        return new VertexSpec(List.of(labels), null, null, null);
    }

    public static VertexSpec text(String text) {
        return new VertexSpec(null, null, text, null);
    }
}
