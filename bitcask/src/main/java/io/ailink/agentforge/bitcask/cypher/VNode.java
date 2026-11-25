package io.ailink.agentforge.bitcask.cypher;

/** 运行期的顶点值（clj 的 x/Node）：只有 vid；标签 / 属性按需经执行器读、查询内缓存。 */
record VNode(long vid) {
}
