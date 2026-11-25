package io.ailink.agentforge.bitcask.value;

/** 时间 / 空间值的构造、解析、运算出错（Cypher 层把它翻成语句错误）。消息是英文（同 openCypher / clj-bitcask）。 */
public class ValueException extends IllegalArgumentException {
    public ValueException(String message) {
        super(message);
    }
}
