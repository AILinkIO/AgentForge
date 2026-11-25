package io.ailink.agentforge.bitcask.cypher;

import java.util.List;
import java.util.Map;

/**
 * 语句结果：列名、行（每行与列一一对应）、统计（写语句：nodes-created / relationships-created / properties-set … 只列非零项）。
 * 值：null / Boolean / Long / Double / String / List / Map（键是字符串）/ {@link Node} / {@link Relationship} / {@link Path} /
 * java.time 的时间值、{@link io.ailink.agentforge.bitcask.value.Dur} / {@link io.ailink.agentforge.bitcask.value.Point}。
 */
public record CypherResult(List<String> columns, List<List<Object>> rows, Map<String, Object> stats) {
}
