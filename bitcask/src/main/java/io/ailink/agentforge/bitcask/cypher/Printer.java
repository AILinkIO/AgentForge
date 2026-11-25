package io.ailink.agentforge.bitcask.cypher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部数据（AST / 计划）的文本形态，近似 clj 的 pr-str：关键字 :x、向量 [a b]、map {k v, …}、集合 #{…}、字符串带引号、null ⇒ nil。
 * map 与集合按元素文本排序（输出稳定，便于和 clj 版逐项对照）。
 */
final class Printer {
    private Printer() {
    }

    static String pr(Object x) {
        StringBuilder sb = new StringBuilder();
        pr(x, sb);
        return sb.toString();
    }

    private static void pr(Object x, StringBuilder sb) {
        switch (x) {
            case null -> sb.append("nil");
            case String s -> sb.append(Parser.prStr(s));
            case Kw k -> sb.append(k);
            case Double d -> sb.append(d.isNaN() ? "##NaN" : d.isInfinite() ? (d > 0 ? "##Inf" : "##-Inf") : d.toString());
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(' ');
                    pr(l.get(i), sb);
                }
                sb.append(']');
            }
            case Map<?, ?> m -> {
                List<String> es = new ArrayList<>();
                m.forEach((k, v) -> es.add(pr(k) + " " + pr(v)));
                es.sort(null);
                sb.append('{').append(String.join(", ", es)).append('}');
            }
            case Set<?> s -> {
                List<String> es = new ArrayList<>();
                for (Object e : s) es.add(pr(e));
                es.sort(null);
                sb.append("#{").append(String.join(" ", es)).append('}');
            }
            case Character c -> sb.append('\\').append(c);
            default -> sb.append(x);
        }
    }
}
