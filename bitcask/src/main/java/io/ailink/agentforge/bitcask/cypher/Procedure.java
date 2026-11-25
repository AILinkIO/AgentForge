package io.ailink.agentforge.bitcask.cypher;

import java.util.Map;

/** 用户过程的实现：参数 map（按输入名）⇒ 输出行（每行 {输出名 值}）。VOID 过程（没有输出）返回值被消费后丢弃。 */
@FunctionalInterface
public interface Procedure {
    Iterable<? extends Map<String, ?>> call(ProcedureContext ctx, Map<String, Object> args);
}
