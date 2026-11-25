package io.ailink.agentforge.bitcask;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * meta 过滤（值；可反复用）。条件：{@link #eq} {@link #notEq} {@link #gt} {@link #gte} {@link #lt} {@link #lte} {@link #in}
 * {@link #exists} {@link #contains} {@link #containsAny}；树：{@link #and} {@link #or}（空树恒通过）；单个条件 = 只有它的 AND 树；
 * 检索 / 迭代的 filter 为 null = 无过滤。
 * <p>类型规则同上游：eq / notEq 同型比较；gt gte lt lte 只对整数 / 浮点；in 逐项 eq；exists 只看键在不在；
 * contains / containsAny（libbitcask 12.0.0 S62）：存的值是字符串数组 ⇒ 任一元素等于操作数，是字符串 ⇒ 等于操作数，操作数只能是字符串。
 * 既有运算碰到数组一律按类型不匹配（eq / in / gt … 不过，notEq 过）。操作数：null / Boolean / Number / String。
 * <p>⚠️ 过滤非空时，没有 meta 段（或 meta 不是 {@link Meta#encode} 编的）的文档一律不通过（含 notEq / exists 这类否定式）。
 * 嵌套超过 32 层 ⇒ 上游 {@code bitcask/invalid-option}。
 */
public sealed interface Filter permits Filter.Condition, Filter.Tree {

    /** 条件运算（序数 = C 侧 {@code bitcask_meta_op_t}）。 */
    enum Op { EQ, NOT_EQ, GT, GTE, LT, LTE, IN, EXISTS, CONTAINS, CONTAINS_ANY }

    /** 一个条件：{@code value} 给比较 / contains 用，{@code values} 给 in / containsAny 用。 */
    record Condition(Op op, String key, Object value, List<Object> values) implements Filter {
        public Condition {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(key, "key");
            values = values == null ? null : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(values));
        }
    }

    /** AND / OR 树。 */
    record Tree(boolean or, List<Filter> nodes) implements Filter {
        public Tree {
            nodes = List.copyOf(nodes);
        }
    }

    static Filter eq(String key, Object value) {
        return new Condition(Op.EQ, key, value, null);
    }

    static Filter notEq(String key, Object value) {
        return new Condition(Op.NOT_EQ, key, value, null);
    }

    static Filter gt(String key, Object value) {
        return new Condition(Op.GT, key, value, null);
    }

    static Filter gte(String key, Object value) {
        return new Condition(Op.GTE, key, value, null);
    }

    static Filter lt(String key, Object value) {
        return new Condition(Op.LT, key, value, null);
    }

    static Filter lte(String key, Object value) {
        return new Condition(Op.LTE, key, value, null);
    }

    static Filter in(String key, List<?> values) {
        return new Condition(Op.IN, key, null, new java.util.ArrayList<>(values));
    }

    static Filter in(String key, Object... values) {
        return in(key, Arrays.asList(values));
    }

    static Filter exists(String key) {
        return new Condition(Op.EXISTS, key, null, null);
    }

    static Filter contains(String key, String value) {
        return new Condition(Op.CONTAINS, key, value, null);
    }

    static Filter containsAny(String key, List<String> values) {
        return new Condition(Op.CONTAINS_ANY, key, null, new java.util.ArrayList<>(values));
    }

    static Filter containsAny(String key, String... values) {
        return containsAny(key, Arrays.asList(values));
    }

    static Filter and(Filter... nodes) {
        return new Tree(false, Arrays.asList(nodes));
    }

    static Filter and(List<Filter> nodes) {
        return new Tree(false, nodes);
    }

    static Filter or(Filter... nodes) {
        return new Tree(true, Arrays.asList(nodes));
    }

    static Filter or(List<Filter> nodes) {
        return new Tree(true, nodes);
    }

    /**
     * 校验：操作数类型不对 / contains 的操作数不是字符串 ⇒ {@code bitcask/filter-invalid}；
     * in / containsAny 没有候选 ⇒ {@code bitcask/filter-in-empty}。回原值。
     */
    static Filter validate(Filter f) {
        FilterEncoder.validate(f);
        return f;
    }
}
