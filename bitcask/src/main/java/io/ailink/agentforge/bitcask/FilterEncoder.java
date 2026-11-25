package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/** {@link Filter} ⇒ {@code bitcask_meta_filter_t} 树（全部进 arena，调用期间有效）。 */
final class FilterEncoder {
    private static final long F_OR = NativeTypes.off(NativeTypes.META_FILTER, "logic_or");
    private static final long F_CONDS = NativeTypes.off(NativeTypes.META_FILTER, "conditions");
    private static final long F_CONDS_N = NativeTypes.off(NativeTypes.META_FILTER, "conditions_count");
    private static final long F_KIDS = NativeTypes.off(NativeTypes.META_FILTER, "children");
    private static final long F_KIDS_N = NativeTypes.off(NativeTypes.META_FILTER, "children_count");
    private static final long C_KEY = NativeTypes.off(NativeTypes.META_CONDITION, "key");
    private static final long C_OP = NativeTypes.off(NativeTypes.META_CONDITION, "op");
    private static final long C_VALUE = NativeTypes.off(NativeTypes.META_CONDITION, "value");
    private static final long C_VALUES = NativeTypes.off(NativeTypes.META_CONDITION, "values");
    private static final long C_VALUES_N = NativeTypes.off(NativeTypes.META_CONDITION, "values_count");

    private FilterEncoder() {
    }

    private static boolean scalar(Object v) {
        return v == null || v instanceof Boolean || v instanceof Number || v instanceof String;
    }

    static void validate(Filter f) {
        if (f == null) return;
        switch (f) {
            case Filter.Tree t -> {
                for (Filter n : t.nodes()) {
                    if (n == null) throw BitcaskException.of("bitcask/filter-invalid", "null node in " + t);
                    validate(n);
                }
            }
            case Filter.Condition c -> {
                switch (c.op()) {
                    case IN, CONTAINS_ANY -> {
                        if (c.values() == null || c.values().isEmpty()) throw BitcaskException.of("bitcask/filter-in-empty", c.key());
                        for (Object v : c.values()) {
                            if (c.op() == Filter.Op.CONTAINS_ANY ? !(v instanceof String) : !scalar(v))
                                throw BitcaskException.of("bitcask/filter-invalid", c);
                        }
                    }
                    case CONTAINS -> {
                        if (!(c.value() instanceof String)) throw BitcaskException.of("bitcask/filter-invalid", c);
                    }
                    case EXISTS -> {
                    }
                    default -> {
                        if (!scalar(c.value())) throw BitcaskException.of("bitcask/filter-invalid", c);
                    }
                }
            }
        }
    }

    /** 编进 arena；null ⇒ NULL。先校验。 */
    static MemorySegment toNative(Filter f, NativeArena a) {
        if (f == null) return MemorySegment.NULL;
        validate(f);
        Filter.Tree tree = f instanceof Filter.Tree t ? t : new Filter.Tree(false, List.of(f));
        MemorySegment root = a.alloc(NativeTypes.META_FILTER);
        fillTree(root, tree, a);
        return root;
    }

    private static void fillTree(MemorySegment out, Filter.Tree t, NativeArena a) {
        List<Filter.Condition> conds = new ArrayList<>();
        List<Filter.Tree> kids = new ArrayList<>();
        for (Filter n : t.nodes()) {
            if (n instanceof Filter.Tree k) kids.add(k);
            else conds.add((Filter.Condition) n);
        }
        long csize = NativeTypes.META_CONDITION.byteSize();
        long fsize = NativeTypes.META_FILTER.byteSize();
        MemorySegment cs = a.alloc(NativeTypes.META_CONDITION, conds.size());
        MemorySegment ks = a.alloc(NativeTypes.META_FILTER, kids.size());
        out.set(ValueLayout.JAVA_INT, F_OR, t.or() ? 1 : 0);
        for (int i = 0; i < conds.size(); i++) fillCondition(cs.asSlice(i * csize, csize), conds.get(i), a);
        out.set(ValueLayout.ADDRESS, F_CONDS, cs);
        out.set(ValueLayout.JAVA_LONG, F_CONDS_N, conds.size());
        for (int i = 0; i < kids.size(); i++) fillTree(ks.asSlice(i * fsize, fsize), kids.get(i), a);
        out.set(ValueLayout.ADDRESS, F_KIDS, ks);
        out.set(ValueLayout.JAVA_LONG, F_KIDS_N, kids.size());
    }

    private static void fillCondition(MemorySegment c, Filter.Condition n, NativeArena a) {
        c.set(ValueLayout.ADDRESS, C_KEY, a.utf8z(n.key()));
        c.set(ValueLayout.JAVA_INT, C_OP, n.op().ordinal());
        boolean multi = n.op() == Filter.Op.IN || n.op() == Filter.Op.CONTAINS_ANY;
        Meta.writeValue(c, C_VALUE, multi || n.op() == Filter.Op.EXISTS ? null : n.value(), a);
        if (multi) {
            List<Object> vs = n.values();
            long size = NativeTypes.META_VALUE.byteSize();
            MemorySegment arr = a.alloc(NativeTypes.META_VALUE, vs.size());
            for (int i = 0; i < vs.size(); i++) Meta.writeValue(arr, i * size, vs.get(i), a);
            c.set(ValueLayout.ADDRESS, C_VALUES, arr);
            c.set(ValueLayout.JAVA_LONG, C_VALUES_N, vs.size());
        }
    }
}
