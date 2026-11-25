package io.ailink.agentforge.bitcask.cypher;

import java.util.List;
import java.util.Map;

/** 结果里的顶点：id（64 位无符号）、标签、属性（键是字符串；正文非空时暴露为 text）。 */
public record Node(long id, List<String> labels, Map<String, Object> properties) {
}
