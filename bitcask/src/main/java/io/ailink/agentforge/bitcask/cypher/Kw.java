package io.ailink.agentforge.bitcask.cypher;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 内部 AST / 计划里的关键字（对应 clj 版的 Clojure 关键字）：按名字驻留，身份即相等（== 可比）。
 * 与字符串分开是因为 AST 里标签名、属性名等用户字符串与结构标记并存，要能区分（clj 里靠 keyword? / string?）。
 */
final class Kw implements Comparable<Kw> {
    private static final ConcurrentHashMap<String, Kw> TABLE = new ConcurrentHashMap<>();
    final String name;

    private Kw(String name) {
        this.name = name;
    }

    static Kw of(String name) {
        return TABLE.computeIfAbsent(name, Kw::new);
    }

    @Override
    public int compareTo(Kw o) {
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return ":" + name;
    }
}
