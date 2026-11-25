package io.ailink.agentforge.bitcask.cypher;

import java.util.List;
import java.util.Map;

/** 语句事务里读改写顶点用的模型（clj store 的 {:labels :props :text :vector}；props 不含正文与向量）。 */
record VModel(List<String> labels, Map<String, Object> props, String text, float[] vector) {
    static final VModel EMPTY = new VModel(List.of(), Map.of(), "", null);

    VModel withLabels(List<String> l) {
        return new VModel(l, props, text, vector);
    }

    VModel withProps(Map<String, Object> p) {
        return new VModel(labels, p, text, vector);
    }

    VModel withText(String t) {
        return new VModel(labels, props, t, vector);
    }

    VModel withVector(float[] v) {
        return new VModel(labels, props, text, v);
    }
}
