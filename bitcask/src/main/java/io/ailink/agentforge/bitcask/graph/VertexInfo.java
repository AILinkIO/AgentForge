package io.ailink.agentforge.bitcask.graph;

import java.util.List;
import java.util.Map;

/** 顶点的模型视图：只有邻接表、没有 n 文档的顶点没有标签与属性；正文非空时暴露为属性 {@code text}。 */
public record VertexInfo(long id, List<String> labels, Map<String, Object> properties) {
}
