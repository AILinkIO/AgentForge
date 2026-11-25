package io.ailink.agentforge.bitcask.cypher;

import java.util.List;

/** 运行期的路径值（clj 的 x/Path）：nodes 是 {@link VNode} 列表，rels 是 {@link VRel} 列表。 */
record VPath(List<Object> nodes, List<Object> rels) {
}
