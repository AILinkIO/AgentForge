package io.ailink.agentforge.bitcask.cypher;

import java.util.Map;

import io.ailink.agentforge.bitcask.Bitcask;

/**
 * 过程拿到的句柄：{@link #db()} 是库；写模式过程还能 {@link #query} —— 在同一个语句事务里执行 Cypher（共用写入计数、统计与上限；
 * 出错 ⇒ 整条语句回滚）。只读过程调 query ⇒ 执行错误（只读过程不要写库）。
 */
public interface ProcedureContext {
    Bitcask db();

    CypherResult query(String cypher, Map<String, ?> params);
}
