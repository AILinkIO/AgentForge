package io.ailink.agentforge.bitcask.cypher;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * clj 版数据形态在 Java 里的小工具（AST / 计划 / 行都沿用 clj 的形态，便于逐行对照移植）：
 * 向量 ⇒ {@link List}（{@link ArrayList}，允许 null；当值用：改动一律先拷贝，见 {@link #conj} / {@link #assoc}），
 * 关键字 ⇒ {@link Kw}，map ⇒ {@link Map}（{@link LinkedHashMap}，允许 null 值），集合 ⇒ {@link Set}，
 * nil ⇒ null，整数 ⇒ Long，浮点 ⇒ Double。
 */
final class Clj {
    private Clj() {
    }

    static Kw kw(String name) {
        return Kw.of(name);
    }

    /** 新向量（可变 ArrayList，允许 null）。 */
    static List<Object> vec(Object... xs) {
        return new ArrayList<>(Arrays.asList(xs));
    }

    /** 拷贝成新向量（null ⇒ 空）。 */
    static List<Object> vecOf(Iterable<?> xs) {
        List<Object> out = new ArrayList<>();
        if (xs != null) for (Object x : xs) out.add(x);
        return out;
    }

    /** (conj v x …)：返回新向量，不改 v。 */
    static List<Object> conj(List<?> v, Object... xs) {
        List<Object> out = v == null ? new ArrayList<>() : new ArrayList<>(v);
        out.addAll(Arrays.asList(xs));
        return out;
    }

    /** (into v xs)：返回新向量。 */
    static List<Object> into(List<?> v, Iterable<?> xs) {
        List<Object> out = v == null ? new ArrayList<>() : new ArrayList<>(v);
        if (xs != null) for (Object x : xs) out.add(x);
        return out;
    }

    /** (nth v i nil)：越界 / 不是向量 ⇒ null。 */
    static Object nth(Object v, int i) {
        if (v instanceof List<?> l && i >= 0 && i < l.size()) return l.get(i);
        return null;
    }

    static Object nth(Object v, int i, Object notFound) {
        if (v instanceof List<?> l && i >= 0 && i < l.size()) return l.get(i);
        return notFound;
    }

    static Object first(Object v) {
        return nth(v, 0);
    }

    static Object second(Object v) {
        return nth(v, 1);
    }

    static Object last(Object v) {
        return v instanceof List<?> l && !l.isEmpty() ? l.getLast() : null;
    }

    /** (subvec v from)：新向量。 */
    static List<Object> subvec(List<?> v, int from) {
        return new ArrayList<>(v.subList(from, v.size()));
    }

    static List<Object> subvec(List<?> v, int from, int to) {
        return new ArrayList<>(v.subList(from, to));
    }

    /** (count x)：null ⇒ 0。 */
    static int count(Object x) {
        return switch (x) {
            case null -> 0;
            case Collection<?> c -> c.size();
            case Map<?, ?> m -> m.size();
            case String s -> s.length();
            case Object[] a -> a.length;
            default -> throw new IllegalArgumentException("count not supported on " + x.getClass());
        };
    }

    static boolean isEmpty(Object x) {
        return count(x) == 0;
    }

    /** AST 节点的头关键字（[:kw …] 形态）；不是 ⇒ null。 */
    static Kw head(Object v) {
        return v instanceof List<?> l && !l.isEmpty() && l.getFirst() instanceof Kw k ? k : null;
    }

    /** v 是不是以 k 开头的向量。 */
    static boolean is(Object v, Kw k) {
        return head(v) == k;
    }

    static boolean isVec(Object v) {
        return v instanceof List<?>;
    }

    /** Clojure 真值：null 与 false 为假。 */
    static boolean truthy(Object x) {
        return x != null && x != Boolean.FALSE && !(x instanceof Boolean b && !b);
    }

    /** {k v …}（保序，允许 null 值）。 */
    static Map<Object, Object> map(Object... kvs) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvs.length; i += 2) m.put(kvs[i], kvs[i + 1]);
        return m;
    }

    /** (assoc m k v …)：返回新 map，不改 m。 */
    @SuppressWarnings("unchecked")
    static <K, V> Map<K, V> assoc(Map<K, V> m, Object... kvs) {
        Map<K, V> out = m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        for (int i = 0; i + 1 < kvs.length; i += 2) out.put((K) kvs[i], (V) kvs[i + 1]);
        return out;
    }

    static <K, V> Map<K, V> dissoc(Map<K, V> m, Object... ks) {
        Map<K, V> out = m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
        for (Object k : ks) out.remove(k);
        return out;
    }

    /** (get m k)：m 不是 map ⇒ null。 */
    static Object get(Object m, Object k) {
        return m instanceof Map<?, ?> mm ? mm.get(k) : null;
    }

    static Object get(Object m, Object k, Object notFound) {
        if (m instanceof Map<?, ?> mm) {
            Object v = mm.get(k);
            return v != null || mm.containsKey(k) ? v : notFound;
        }
        return notFound;
    }

    static Set<Object> set(Object... xs) {
        return new LinkedHashSet<>(Arrays.asList(xs));
    }

    static Set<Object> setOf(Iterable<?> xs) {
        Set<Object> s = new LinkedHashSet<>();
        if (xs != null) for (Object x : xs) s.add(x);
        return s;
    }

    static boolean eq(Object a, Object b) {
        return Objects.equals(a, b);
    }

    /** clj 的 str：null ⇒ ""，其余 toString 拼接。 */
    static String str(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) if (p != null) sb.append(p);
        return sb.toString();
    }

    /** clj 的 integer?（引擎里整数都是 Long；也认 Integer / Short / Byte / BigInteger）。 */
    static boolean isInteger(Object x) {
        return x instanceof Long || x instanceof Integer || x instanceof Short || x instanceof Byte || x instanceof BigInteger;
    }

    static boolean isNumber(Object x) {
        return x instanceof Number;
    }

    /** 关键字或字符串 ⇒ 名字（clj 的 name）。 */
    static String name(Object x) {
        return x instanceof Kw k ? k.name : String.valueOf(x);
    }

    static Set<Object> hashSet() {
        return new HashSet<>();
    }
}
