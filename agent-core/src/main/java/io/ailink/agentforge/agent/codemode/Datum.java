package io.ailink.agentforge.agent.codemode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本里的值。Java 对象直接当 Scheme 值用：
 *
 * <ul>
 *   <li>精确整数 {@link Long}、非精确数 {@link Double}（⚠️ 没有分数与大整数：{@code (/ 1 2)} 得 0.5，
 *       溢出转非精确 —— 脚本是拿来拼几次工具调用的，不是算数论）；</li>
 *   <li>字符串 {@link String}（不可变 —— 没有 {@code string-set!}）、布尔 {@link Boolean}、
 *       向量 {@code Object[]}；</li>
 *   <li>其余见本类的嵌套类型。</li>
 * </ul>
 */
final class Datum {
    private Datum() { }

    /** 符号（全局驻留，{@code eq?} 即同一对象）。 */
    static final class Sym {
        private static final ConcurrentHashMap<String, Sym> TABLE = new ConcurrentHashMap<>();
        final String name;

        private Sym(String name) { this.name = name; }

        static Sym of(String name) { return TABLE.computeIfAbsent(name, Sym::new); }

        @Override public String toString() { return name; }
    }

    static final class Pair {
        Object car;
        Object cdr;

        Pair(Object car, Object cdr) {
            this.car = car;
            this.cdr = cdr;
        }
    }

    /** 空表 {@code '()}。 */
    enum Nil { NIL }

    /** 未指定值（{@code (if #f #f)}）：顶层渲染成空串。 */
    enum Void { VOID }

    record SChar(int cp) { }

    /** {@code (values a b …)}，个数不是 1 时。 */
    record MultipleValues(Object[] vals) { }

    abstract static class Procedure {
        String name;
    }

    @FunctionalInterface
    interface Fn { Object apply(Interp in, Object[] args); }

    static final class Builtin extends Procedure {
        final int min;
        final int max;   // -1 = 不限
        final Fn fn;

        Builtin(String name, int min, int max, Fn fn) {
            this.name = name;
            this.min = min;
            this.max = max;
            this.fn = fn;
        }
    }

    static final class Closure extends Procedure {
        final Sym[] params;
        final Sym rest;
        final Object body;   // 表达式表（非空）
        final Env env;

        Closure(String name, Sym[] params, Sym rest, Object body, Env env) {
            this.name = name;
            this.params = params;
            this.rest = rest;
            this.body = body;
            this.env = env;
        }

        boolean accepts(int n) { return rest == null ? n == params.length : n >= params.length; }
    }

    static final class CaseLambda extends Procedure {
        final Closure[] clauses;

        CaseLambda(String name, Closure[] clauses) {
            this.name = name;
            this.clauses = clauses;
        }
    }

    /** 只能往外逃的续延（call/cc）：出了它的动态范围再调用就报错。 */
    static final class Continuation extends Procedure {
        boolean valid = true;
    }

    static final class ContinuationThrow extends RuntimeException {
        final Continuation k;
        final Object value;

        ContinuationThrow(Continuation k, Object value) {
            super(null, null, false, false);
            this.k = k;
            this.value = value;
        }
    }

    /** 散列表。键按等价方式包一层。 */
    static final class Hashtable {
        enum Kind { EQV, EQUAL, STRING_CI }

        final Kind kind;
        final boolean mutable;
        final Map<Object, Object[]> map;   // 包过的键 → [原键, 值]

        Hashtable(Kind kind, boolean mutable, Map<Object, Object[]> map) {
            this.kind = kind;
            this.mutable = mutable;
            this.map = map;
        }

        Hashtable(Kind kind) { this(kind, true, new LinkedHashMap<>()); }

        Object key(Object k) {
            return switch (kind) {
                case EQV -> new EqvKey(k);
                case EQUAL -> new EqualKey(k);
                case STRING_CI -> k instanceof String s ? s.toLowerCase() : new EqualKey(k);
            };
        }
    }

    record EqvKey(Object v) {
        @Override public boolean equals(Object o) { return o instanceof EqvKey k && Interp.isEqv(v, k.v); }

        @Override public int hashCode() {
            return v instanceof Long || v instanceof Double || v instanceof SChar || v instanceof Boolean
                    ? v.hashCode() : System.identityHashCode(v);
        }
    }

    record EqualKey(Object v) {
        @Override public boolean equals(Object o) { return o instanceof EqualKey k && Interp.isEqual(v, k.v); }

        @Override public int hashCode() { return equalHash(v, 0); }
    }

    static int equalHash(Object v, int depth) {
        if (depth > 8) return 7;
        if (v instanceof Pair p) return 31 * equalHash(p.car, depth + 1) + equalHash(p.cdr, depth + 1);
        if (v instanceof Object[] a) {
            int h = 17;
            for (int i = 0; i < Math.min(a.length, 16); i++) h = 31 * h + equalHash(a[i], depth + 1);
            return h;
        }
        if (v instanceof Long || v instanceof Double || v instanceof String || v instanceof SChar || v instanceof Boolean) {
            return v.hashCode();
        }
        if (v instanceof Nil) return 1;
        return System.identityHashCode(v);
    }

    /** R6RS 风格的条件对象（{@code error} / {@code assertion-violation} / 内部报错造的）。 */
    static final class Condition {
        final String kind;       // "error" / "assertion"
        final Object who;        // 符号 / 字符串 / #f
        final String message;
        final List<Object> irritants;

        Condition(String kind, Object who, String message, List<Object> irritants) {
            this.kind = kind;
            this.who = who;
            this.message = message;
            this.irritants = irritants == null ? List.of() : irritants;
        }
    }

    /** {@code raise} 抛出来的东西（guard 接得住）。内部报错也是它，携带一个 {@link Condition}。 */
    static final class SchemeError extends RuntimeException {
        final Object payload;
        final boolean continuable;

        SchemeError(Object payload, boolean continuable) {
            super(null, null, false, false);
            this.payload = payload;
            this.continuable = continuable;
        }

        @Override public String getMessage() { return Printer.conditionText(payload); }
    }

    /** 限额撞线（超时 / 步数 / 调用次数 / 内存）。⚠️ 不是 SchemeError —— guard 接不住，脚本没法吞掉它。 */
    static final class LimitError extends RuntimeException {
        LimitError(String message) { super(message, null, false, false); }
    }

    /** 词法环境的一帧。 */
    static final class Env {
        final HashMap<Sym, Object> vars;
        final Env parent;

        Env(Env parent) {
            this.parent = parent;
            this.vars = new HashMap<>(8);
        }

        Env(Env parent, HashMap<Sym, Object> vars) {
            this.parent = parent;
            this.vars = vars;
        }
    }

    // ── 表的小工具 ───────────────────────────────────────────────────────

    static Object list(Object... xs) {
        Object r = Nil.NIL;
        for (int i = xs.length - 1; i >= 0; i--) r = new Pair(xs[i], r);
        return r;
    }

    static Object fromList(List<?> xs) {
        Object r = Nil.NIL;
        for (int i = xs.size() - 1; i >= 0; i--) r = new Pair(xs.get(i), r);
        return r;
    }

    /** 真表 → Java 表；不是真表回 null。 */
    static List<Object> toList(Object x) {
        List<Object> out = new ArrayList<>();
        int guard = 0;
        while (x instanceof Pair p) {
            out.add(p.car);
            x = p.cdr;
            if (++guard > 50_000_000) return null;
        }
        return x == Nil.NIL ? out : null;
    }

    static boolean isList(Object x) {
        Object slow = x;
        while (true) {
            if (x == Nil.NIL) return true;
            if (!(x instanceof Pair p)) return false;
            x = p.cdr;
            if (x == Nil.NIL) return true;
            if (!(x instanceof Pair p2)) return false;
            x = p2.cdr;
            slow = ((Pair) slow).cdr;
            if (x == slow) return false;   // 环
        }
    }
}
