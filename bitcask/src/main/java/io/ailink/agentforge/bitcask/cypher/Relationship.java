package io.ailink.agentforge.bitcask.cypher;

import java.util.Map;

/** 结果里的边：type 是关系类型名（String；没注册名字的是 Long 整数），rank 区分同一对顶点间同类型的多条边。 */
public record Relationship(long src, Object type, long dst, long rank, Map<String, Object> properties) {
}
