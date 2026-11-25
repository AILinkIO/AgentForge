package io.ailink.agentforge.bitcask.cypher;

import java.util.List;

import io.ailink.agentforge.bitcask.Bitcask;

/** 用户函数：参数为 null 时照样调用；返回值按 Cypher 的值规范（整数 ⇒ Long、浮点 ⇒ Double、map 键 ⇒ 字符串）。 */
@FunctionalInterface
public interface UserFunction {
    Object apply(Bitcask db, List<Object> args);
}
