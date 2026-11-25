package io.ailink.agentforge.bitcask.cypher;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.ailink.agentforge.bitcask.Bitcask;

/** 执行器交给求值（{@link Expr}）的上下文（clj 的 x/Env 协议）。行是 变量名 ⇒ 值 的 map。 */
interface Env {
    /** 参数 map。 */
    Map<String, Object> params();

    /** vid ⇒ 标签 / 属性 / 向量（本语句删掉的节点 ⇒ EntityNotFound/DeletedEntityAccess）。 */
    NodeInfo nodeInfo(long vid);

    /** vid ⇒ float[] 或 null。 */
    float[] nodeVector(long vid);

    /** 边 ⇒ 属性 map。 */
    Map<String, Object> relProps(VRel r);

    /** etype ⇒ 名字（String；没注册名字的是 Long）。 */
    Object typeName(long etype);

    /** 全文匹配。 */
    boolean ftsMatch(Object v, long vid, String prop, Object q);

    /** score(v)。 */
    Object ftsScore(Object v, Map<String, Object> row);

    /** 向量距离。 */
    Object vectorDistance(long vid, Object q);

    /** 检索函数（fts.* / search.hybrid）按顶点判断 ⇒ Boolean 或 null。 */
    Object searchMatch(Object v, long vid, String fnName, List<Object> vals);

    /** 模式表达式在当前行上的匹配结果（行的序列，惰性）。 */
    Iterable<Map<String, Object>> patternRows(Object part, Map<String, Object> row);

    /** 语句时钟（date() / datetime() 一类在一条语句里取同一时刻）。 */
    Instant now();

    /** 模式表达式的值：路径列表。 */
    PatternList patternPaths(Object part, Map<String, Object> row);

    /** EXISTS { … } 子查询在当前行上有没有结果。 */
    boolean exists(Object q, Map<String, Object> row);

    /** shortestPath(…) / allShortestPaths(…) 表达式：路径 / 路径列表；两端不是节点 ⇒ null。 */
    Object shortest(Object e, Map<String, Object> row);

    /** 库句柄（用户函数用）。 */
    Bitcask db();
}
