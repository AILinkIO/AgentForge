package io.ailink.agentforge.bitcask.cypher;

import java.util.List;

/** 结果里的路径：节点比关系多一个。 */
public record Path(List<Node> nodes, List<Relationship> relationships) {
}
