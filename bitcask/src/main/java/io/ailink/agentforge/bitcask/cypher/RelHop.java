package io.ailink.agentforge.bitcask.cypher;

/** 邻接展开的一步：边 + 对端 vid（clj 的 [Rel 对端]）。 */
record RelHop(VRel rel, long other) {
}
