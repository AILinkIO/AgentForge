package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Builtin;
import io.ailink.agentforge.agent.codemode.Datum.Condition;
import io.ailink.agentforge.agent.codemode.Datum.Fn;
import io.ailink.agentforge.agent.codemode.Datum.Hashtable;
import io.ailink.agentforge.agent.codemode.Datum.MultipleValues;
import io.ailink.agentforge.agent.codemode.Datum.Nil;
import io.ailink.agentforge.agent.codemode.Datum.Pair;
import io.ailink.agentforge.agent.codemode.Datum.Procedure;
import io.ailink.agentforge.agent.codemode.Datum.SChar;
import io.ailink.agentforge.agent.codemode.Datum.SchemeError;
import io.ailink.agentforge.agent.codemode.Datum.Sym;
import io.ailink.agentforge.agent.codemode.Datum.Void;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.ailink.agentforge.agent.codemode.Interp.err;
import static io.ailink.agentforge.agent.codemode.Interp.isEqual;
import static io.ailink.agentforge.agent.codemode.Interp.isEqv;
import static io.ailink.agentforge.agent.codemode.Interp.truthy;

/**
 * 脚本看得见的全部名字。
 *
 * <p>对应 coxswain 的沙箱环境：{@code (rnrs base) (rnrs lists) (rnrs control) (rnrs hashtables) (rnrs unicode)
 * (rnrs sorting) (rnrs exceptions) (rnrs conditions) (rnrs mutable-pairs)} 里常用的纯函数 +
 * {@code (coxn agent codemode api)}：{@code call log! json-parse json-stringify format string-split string-join
 * string-trim string-contains? string-prefix? string-suffix? ref}（名字逐字保留，提示词照旧有效）。
 *
 * <p>⚠️ 加名字之前先问"它能碰到盘 / 网 / 进程 / 反射吗"——能就不加。没有 {@code display} / {@code write} /
 * 端口 / {@code eval} / {@code load} / {@code getenv}。
 */
final class Builtins {
    private Builtins() { }

    private static final Map<Sym, Object> TABLE = build();

    static Map<Sym, Object> table() { return TABLE; }

    private static Map<Sym, Object> m;

    private static void def(String name, int min, int max, Fn fn) {
        m.put(Sym.of(name), new Builtin(name, min, max, fn));
    }

    private static synchronized Map<Sym, Object> build() {
        m = new HashMap<>();
        numbers();
        predicates();
        lists();
        higherOrder();
        chars();
        strings();
        vectors();
        hashtables();
        control();
        conditions();
        api();
        Map<Sym, Object> out = Collections.unmodifiableMap(m);
        m = null;
        return out;
    }

    // ═══ 类型检查 ═══════════════════════════════════════════════════════

    static Object num(String who, Object x) {
        if (x instanceof Long || x instanceof Double) return x;
        throw err(who, Printer.write(x) + " is not a number");
    }

    static long integer(String who, Object x) {
        if (x instanceof Long l) return l;
        if (x instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return (long) (double) d;
        throw err(who, Printer.write(x) + " is not an integer");
    }

    static int index(String who, Object x) {
        if (x instanceof Long l && l >= 0 && l <= Integer.MAX_VALUE) return (int) (long) l;
        throw err(who, Printer.write(x) + " is not a valid index");
    }

    static String str(String who, Object x) {
        if (x instanceof String s) return s;
        throw err(who, Printer.write(x) + " is not a string");
    }

    static Pair pair(String who, Object x) {
        if (x instanceof Pair p) return p;
        throw err(who, Printer.write(x) + " is not a pair");
    }

    static List<Object> list(String who, Object x) {
        List<Object> l = Datum.toList(x);
        if (l == null) throw err(who, Printer.write(x) + " is not a proper list");
        return l;
    }

    static Object[] vec(String who, Object x) {
        if (x instanceof Object[] v) return v;
        throw err(who, Printer.write(x) + " is not a vector");
    }

    static int chr(String who, Object x) {
        if (x instanceof SChar c) return c.cp();
        throw err(who, Printer.write(x) + " is not a character");
    }

    static Object proc(String who, Object x) {
        if (x instanceof Procedure) return x;
        throw err(who, Printer.write(x) + " is not a procedure");
    }

    static Hashtable ht(String who, Object x) {
        if (x instanceof Hashtable h) return h;
        throw err(who, Printer.write(x) + " is not a hashtable");
    }

    static double dbl(Object x) { return x instanceof Long l ? (double) l : (Double) x; }

    static Object fromList(Interp in, List<?> xs) {
        in.alloc(xs.size());
        return Datum.fromList(xs);
    }

    // ═══ 数 ═════════════════════════════════════════════════════════════

    static Object add(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            long r = x + y;
            if (((x ^ r) & (y ^ r)) < 0) return (double) x + (double) y;
            return r;
        }
        return dbl(a) + dbl(b);
    }

    static Object sub(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            long r = x - y;
            if (((x ^ y) & (x ^ r)) < 0) return (double) x - (double) y;
            return r;
        }
        return dbl(a) - dbl(b);
    }

    static Object mul(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            long hi = Math.multiplyHigh(x, y);
            long lo = x * y;
            if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) return lo;
            return (double) x * (double) y;
        }
        return dbl(a) * dbl(b);
    }

    static Object div(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            if (y == 0) throw err("/", "undefined for 0");
            if (x % y == 0) return x / y;
            return (double) x / y;
        }
        return dbl(a) / dbl(b);
    }

    static int cmp(Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) return Long.compare(x, y);
        double x = dbl(a);
        double y = dbl(b);
        return x < y ? -1 : x > y ? 1 : x == y ? 0 : 2;   // 2 = NaN，所有比较都假
    }

    private interface Cmp { boolean ok(int c); }

    private static void compare(String name, Cmp ok) {
        def(name, 1, -1, (in, a) -> {
            for (Object x : a) num(name, x);
            for (int k = 0; k + 1 < a.length; k++) {
                int c = cmp(a[k], a[k + 1]);
                if (c == 2 || !ok.ok(c)) return false;
            }
            return true;
        });
    }

    private static Object exactInt(BigInteger b) {
        return b.bitLength() < 64 ? (Object) b.longValue() : (Object) b.doubleValue();
    }

    private static void numbers() {
        def("+", 0, -1, (in, a) -> {
            Object r = 0L;
            for (Object x : a) r = add(r, num("+", x));
            return r;
        });
        def("*", 0, -1, (in, a) -> {
            Object r = 1L;
            for (Object x : a) r = mul(r, num("*", x));
            return r;
        });
        def("-", 1, -1, (in, a) -> {
            if (a.length == 1) return sub(0L, num("-", a[0]));
            Object r = num("-", a[0]);
            for (int k = 1; k < a.length; k++) r = sub(r, num("-", a[k]));
            return r;
        });
        def("/", 1, -1, (in, a) -> {
            if (a.length == 1) return div(1L, num("/", a[0]));
            Object r = num("/", a[0]);
            for (int k = 1; k < a.length; k++) r = div(r, num("/", a[k]));
            return r;
        });
        compare("=", c -> c == 0);
        compare("<", c -> c < 0);
        compare(">", c -> c > 0);
        compare("<=", c -> c <= 0);
        compare(">=", c -> c >= 0);
        def("number?", 1, 1, (in, a) -> a[0] instanceof Long || a[0] instanceof Double);
        def("complex?", 1, 1, (in, a) -> a[0] instanceof Long || a[0] instanceof Double);
        def("real?", 1, 1, (in, a) -> a[0] instanceof Long || a[0] instanceof Double);
        def("rational?", 1, 1, (in, a) -> a[0] instanceof Long || (a[0] instanceof Double d && Double.isFinite(d)));
        def("integer?", 1, 1, (in, a) -> a[0] instanceof Long || (a[0] instanceof Double d && d == Math.rint(d) && Double.isFinite(d)));
        def("exact?", 1, 1, (in, a) -> num("exact?", a[0]) instanceof Long);
        def("inexact?", 1, 1, (in, a) -> num("inexact?", a[0]) instanceof Double);
        def("exact-integer?", 1, 1, (in, a) -> a[0] instanceof Long);
        def("nan?", 1, 1, (in, a) -> num("nan?", a[0]) instanceof Double d && d.isNaN());
        def("infinite?", 1, 1, (in, a) -> num("infinite?", a[0]) instanceof Double d && d.isInfinite());
        def("finite?", 1, 1, (in, a) -> !(num("finite?", a[0]) instanceof Double d) || Double.isFinite(d));
        def("zero?", 1, 1, (in, a) -> cmp(num("zero?", a[0]), 0L) == 0);
        def("positive?", 1, 1, (in, a) -> cmp(num("positive?", a[0]), 0L) == 1);
        def("negative?", 1, 1, (in, a) -> cmp(num("negative?", a[0]), 0L) == -1);
        def("even?", 1, 1, (in, a) -> integer("even?", a[0]) % 2 == 0);
        def("odd?", 1, 1, (in, a) -> integer("odd?", a[0]) % 2 != 0);
        def("max", 1, -1, (in, a) -> minmax("max", a, 1));
        def("min", 1, -1, (in, a) -> minmax("min", a, -1));
        def("abs", 1, 1, (in, a) -> {
            Object x = num("abs", a[0]);
            return cmp(x, 0L) < 0 ? sub(0L, x) : x;
        });
        def("quotient", 2, 2, (in, a) -> intOp("quotient", a, 'q'));
        def("remainder", 2, 2, (in, a) -> intOp("remainder", a, 'r'));
        def("modulo", 2, 2, (in, a) -> intOp("modulo", a, 'm'));
        def("div", 2, 2, (in, a) -> intOp("div", a, 'd'));
        def("mod", 2, 2, (in, a) -> intOp("mod", a, 'M'));
        def("gcd", 0, -1, (in, a) -> {
            BigInteger g = BigInteger.ZERO;
            for (Object x : a) g = g.gcd(BigInteger.valueOf(integer("gcd", x)));
            return exactInt(g);
        });
        def("lcm", 0, -1, (in, a) -> {
            BigInteger l = BigInteger.ONE;
            for (Object x : a) {
                BigInteger v = BigInteger.valueOf(integer("lcm", x)).abs();
                if (v.signum() == 0) return 0L;
                l = l.multiply(v).divide(l.gcd(v));
            }
            return exactInt(l);
        });
        def("floor", 1, 1, (in, a) -> round("floor", a[0], Math::floor));
        def("ceiling", 1, 1, (in, a) -> round("ceiling", a[0], Math::ceil));
        def("round", 1, 1, (in, a) -> round("round", a[0], Math::rint));
        def("truncate", 1, 1, (in, a) -> round("truncate", a[0], d -> d < 0 ? Math.ceil(d) : Math.floor(d)));
        def("exact", 1, 1, (in, a) -> exact(a[0]));
        def("inexact->exact", 1, 1, (in, a) -> exact(a[0]));
        def("inexact", 1, 1, (in, a) -> dbl(num("inexact", a[0])));
        def("exact->inexact", 1, 1, (in, a) -> dbl(num("exact->inexact", a[0])));
        def("sqrt", 1, 1, (in, a) -> {
            Object x = num("sqrt", a[0]);
            if (x instanceof Long l && l >= 0) {
                long r = (long) Math.sqrt(l);
                while (r * r > l) r--;
                while ((r + 1) * (r + 1) <= l) r++;
                if (r * r == l) return r;
            }
            return Math.sqrt(dbl(x));
        });
        def("expt", 2, 2, (in, a) -> {
            Object b = num("expt", a[0]);
            Object e = num("expt", a[1]);
            if (b instanceof Long bl && e instanceof Long el && el >= 0) {
                if (el > 4096) return Math.pow(bl, el);
                return exactInt(BigInteger.valueOf(bl).pow((int) (long) el));
            }
            return Math.pow(dbl(b), dbl(e));
        });
        def("exp", 1, 1, (in, a) -> Math.exp(dbl(num("exp", a[0]))));
        def("log", 1, 2, (in, a) -> a.length == 1 ? Math.log(dbl(num("log", a[0])))
                : Math.log(dbl(num("log", a[0]))) / Math.log(dbl(num("log", a[1]))));
        def("sin", 1, 1, (in, a) -> Math.sin(dbl(num("sin", a[0]))));
        def("cos", 1, 1, (in, a) -> Math.cos(dbl(num("cos", a[0]))));
        def("tan", 1, 1, (in, a) -> Math.tan(dbl(num("tan", a[0]))));
        def("asin", 1, 1, (in, a) -> Math.asin(dbl(num("asin", a[0]))));
        def("acos", 1, 1, (in, a) -> Math.acos(dbl(num("acos", a[0]))));
        def("atan", 1, 2, (in, a) -> a.length == 1 ? Math.atan(dbl(num("atan", a[0])))
                : Math.atan2(dbl(num("atan", a[0])), dbl(num("atan", a[1]))));
        def("number->string", 1, 2, (in, a) -> {
            Object x = num("number->string", a[0]);
            int radix = a.length > 1 ? (int) integer("number->string", a[1]) : 10;
            if (radix != 10 && x instanceof Long l) return Long.toString(l, radix);
            return Printer.number(x);
        });
        def("string->number", 1, 2, (in, a) -> {
            int radix = a.length > 1 ? (int) integer("string->number", a[1]) : 10;
            Object v = Reader.parseNumber(str("string->number", a[0]), radix);
            return v == null ? Boolean.FALSE : v;
        });
    }

    private static Object minmax(String who, Object[] a, int sign) {
        Object best = num(who, a[0]);
        boolean inexact = best instanceof Double;
        for (int k = 1; k < a.length; k++) {
            Object x = num(who, a[k]);
            if (x instanceof Double) inexact = true;
            int c = cmp(x, best);
            if (c == 2) return Double.NaN;
            if (c == sign) best = x;
        }
        return inexact ? (Object) dbl(best) : best;
    }

    private static Object round(String who, Object x, java.util.function.DoubleUnaryOperator f) {
        Object n = num(who, x);
        if (n instanceof Long) return n;
        return f.applyAsDouble((Double) n);
    }

    private static Object exact(Object x) {
        Object n = num("exact", x);
        if (n instanceof Long) return n;
        double d = (Double) n;
        if (d == Math.rint(d) && Double.isFinite(d) && Math.abs(d) < 9.2e18) return (long) d;
        throw err("exact", "no exact representation for " + Printer.number(d) + "（这个解释器没有分数）");
    }

    private static Object intOp(String who, Object[] a, char op) {
        boolean inexact = a[0] instanceof Double || a[1] instanceof Double;
        long x = integer(who, a[0]);
        long y = integer(who, a[1]);
        if (y == 0) throw err(who, "undefined for 0");
        long r = switch (op) {
            case 'q' -> x / y;
            case 'r' -> x % y;
            case 'm' -> Math.floorMod(x, y);
            case 'd' -> {
                long q = Math.floorDiv(x, y);
                yield y < 0 && Math.floorMod(x, y) != 0 ? q + 1 : q;   // R6RS div：余数恒非负
            }
            default -> {
                long md = Math.floorMod(x, y);
                yield md < 0 ? md - y : md;
            }
        };
        return inexact ? (Object) (double) r : (Object) r;
    }

    // ═══ 谓词 / 等价 / 符号 ═════════════════════════════════════════════

    private static void predicates() {
        def("not", 1, 1, (in, a) -> a[0] == Boolean.FALSE);
        def("boolean?", 1, 1, (in, a) -> a[0] instanceof Boolean);
        def("boolean=?", 2, -1, (in, a) -> {
            for (int k = 0; k + 1 < a.length; k++) if (!a[k].equals(a[k + 1])) return false;
            return true;
        });
        def("eq?", 2, 2, (in, a) -> a[0] == a[1] || (a[0] instanceof Long && isEqv(a[0], a[1]))
                || (a[0] instanceof SChar && isEqv(a[0], a[1])) || (a[0] instanceof Boolean && a[0].equals(a[1])));
        def("eqv?", 2, 2, (in, a) -> isEqv(a[0], a[1]));
        def("equal?", 2, 2, (in, a) -> isEqual(a[0], a[1]));
        def("symbol?", 1, 1, (in, a) -> a[0] instanceof Sym);
        def("symbol->string", 1, 1, (in, a) -> {
            if (a[0] instanceof Sym s) return s.name;
            throw err("symbol->string", Printer.write(a[0]) + " is not a symbol");
        });
        def("string->symbol", 1, 1, (in, a) -> Sym.of(str("string->symbol", a[0])));
        def("symbol=?", 2, -1, (in, a) -> {
            for (int k = 0; k + 1 < a.length; k++) if (a[k] != a[k + 1]) return false;
            return true;
        });
        def("procedure?", 1, 1, (in, a) -> a[0] instanceof Procedure);
    }

    // ═══ 表 ═════════════════════════════════════════════════════════════

    private static void lists() {
        def("cons", 2, 2, (in, a) -> in.cons(a[0], a[1]));
        def("car", 1, 1, (in, a) -> pair("car", a[0]).car);
        def("cdr", 1, 1, (in, a) -> pair("cdr", a[0]).cdr);
        for (String path : List.of("aa", "ad", "da", "dd", "aaa", "aad", "ada", "add", "daa", "dad", "dda", "ddd",
                "addd", "dddd")) {
            String name = "c" + path + "r";
            def(name, 1, 1, (in, a) -> {
                Object x = a[0];
                for (int k = path.length() - 1; k >= 0; k--) {
                    Pair p = pair(name, x);
                    x = path.charAt(k) == 'a' ? p.car : p.cdr;
                }
                return x;
            });
        }
        def("set-car!", 2, 2, (in, a) -> { pair("set-car!", a[0]).car = a[1]; return Void.VOID; });
        def("set-cdr!", 2, 2, (in, a) -> { pair("set-cdr!", a[0]).cdr = a[1]; return Void.VOID; });
        def("list", 0, -1, (in, a) -> fromList(in, Arrays.asList(a)));
        def("cons*", 1, -1, (in, a) -> {
            Object r = a[a.length - 1];
            for (int k = a.length - 2; k >= 0; k--) r = in.cons(a[k], r);
            return r;
        });
        def("list?", 1, 1, (in, a) -> Datum.isList(a[0]));
        def("pair?", 1, 1, (in, a) -> a[0] instanceof Pair);
        def("null?", 1, 1, (in, a) -> a[0] == Nil.NIL);
        def("length", 1, 1, (in, a) -> (long) list("length", a[0]).size());
        def("append", 0, -1, (in, a) -> {
            if (a.length == 0) return Nil.NIL;
            Object r = a[a.length - 1];
            for (int k = a.length - 2; k >= 0; k--) {
                List<Object> l = list("append", a[k]);
                for (int j = l.size() - 1; j >= 0; j--) r = in.cons(l.get(j), r);
            }
            return r;
        });
        def("reverse", 1, 1, (in, a) -> {
            Object r = Nil.NIL;
            for (Object x : list("reverse", a[0])) r = in.cons(x, r);
            return r;
        });
        def("list-tail", 2, 2, (in, a) -> {
            Object x = a[0];
            for (int k = index("list-tail", a[1]); k > 0; k--) x = pair("list-tail", x).cdr;
            return x;
        });
        def("list-ref", 2, 2, (in, a) -> {
            Object x = a[0];
            for (int k = index("list-ref", a[1]); k > 0; k--) x = pair("list-ref", x).cdr;
            return pair("list-ref", x).car;
        });
        def("memq", 2, 2, (in, a) -> mem(a[0], a[1], 0));
        def("memv", 2, 2, (in, a) -> mem(a[0], a[1], 1));
        def("member", 2, 2, (in, a) -> mem(a[0], a[1], 2));
        def("assq", 2, 2, (in, a) -> ass(a[0], a[1], 0));
        def("assv", 2, 2, (in, a) -> ass(a[0], a[1], 1));
        def("assoc", 2, 2, (in, a) -> ass(a[0], a[1], 2));
    }

    private static boolean same(Object a, Object b, int mode) {
        return switch (mode) {
            case 0, 1 -> isEqv(a, b);
            default -> isEqual(a, b);
        };
    }

    private static Object mem(Object x, Object lst, int mode) {
        for (Object l = lst; l instanceof Pair p; l = p.cdr) if (same(x, p.car, mode)) return p;
        return Boolean.FALSE;
    }

    private static Object ass(Object x, Object lst, int mode) {
        for (Object l = lst; l instanceof Pair p; l = p.cdr) {
            if (p.car instanceof Pair e && same(x, e.car, mode)) return e;
        }
        return Boolean.FALSE;
    }

    // ═══ 高阶 ═══════════════════════════════════════════════════════════

    /** 多个等长表 → 每一步的参数（长度按最短的）。 */
    private static List<Object[]> zip(String who, Object[] a, int from) {
        List<List<Object>> ls = new ArrayList<>();
        int n = Integer.MAX_VALUE;
        for (int k = from; k < a.length; k++) {
            List<Object> l = list(who, a[k]);
            ls.add(l);
            n = Math.min(n, l.size());
        }
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object[] row = new Object[ls.size()];
            for (int k = 0; k < row.length; k++) row[k] = ls.get(k).get(i);
            out.add(row);
        }
        return out;
    }

    private static void higherOrder() {
        def("apply", 1, -1, (in, a) -> {
            Object f = proc("apply", a[0]);
            List<Object> args = new ArrayList<>(Arrays.asList(a).subList(1, a.length - 1));
            if (a.length > 1) args.addAll(list("apply", a[a.length - 1]));
            return in.apply(f, args.toArray());
        });
        def("map", 2, -1, (in, a) -> {
            Object f = proc("map", a[0]);
            List<Object> out = new ArrayList<>();
            for (Object[] row : zip("map", a, 1)) out.add(in.apply(f, row));
            return fromList(in, out);
        });
        def("for-each", 2, -1, (in, a) -> {
            Object f = proc("for-each", a[0]);
            for (Object[] row : zip("for-each", a, 1)) in.apply(f, row);
            return Void.VOID;
        });
        def("filter", 2, 2, (in, a) -> {
            List<Object> out = new ArrayList<>();
            for (Object x : list("filter", a[1])) if (truthy(in.apply(a[0], new Object[] {x}))) out.add(x);
            return fromList(in, out);
        });
        def("remp", 2, 2, (in, a) -> {
            List<Object> out = new ArrayList<>();
            for (Object x : list("remp", a[1])) if (!truthy(in.apply(a[0], new Object[] {x}))) out.add(x);
            return fromList(in, out);
        });
        def("remove", 2, 2, (in, a) -> rem(in, a, 2));
        def("remv", 2, 2, (in, a) -> rem(in, a, 1));
        def("remq", 2, 2, (in, a) -> rem(in, a, 0));
        def("partition", 2, 2, (in, a) -> {
            List<Object> yes = new ArrayList<>();
            List<Object> no = new ArrayList<>();
            for (Object x : list("partition", a[1])) (truthy(in.apply(a[0], new Object[] {x})) ? yes : no).add(x);
            return new MultipleValues(new Object[] {fromList(in, yes), fromList(in, no)});
        });
        def("fold-left", 3, -1, (in, a) -> {
            Object acc = a[1];
            for (Object[] row : zip("fold-left", a, 2)) {
                Object[] args = new Object[row.length + 1];
                args[0] = acc;
                System.arraycopy(row, 0, args, 1, row.length);
                acc = in.apply(a[0], args);
            }
            return acc;
        });
        def("fold-right", 3, -1, (in, a) -> {
            Object acc = a[1];
            List<Object[]> rows = zip("fold-right", a, 2);
            for (int i = rows.size() - 1; i >= 0; i--) {
                Object[] row = rows.get(i);
                Object[] args = Arrays.copyOf(row, row.length + 1);
                args[row.length] = acc;
                acc = in.apply(a[0], args);
            }
            return acc;
        });
        def("find", 2, 2, (in, a) -> {
            for (Object x : list("find", a[1])) if (truthy(in.apply(a[0], new Object[] {x}))) return x;
            return Boolean.FALSE;
        });
        def("exists", 2, -1, (in, a) -> {
            for (Object[] row : zip("exists", a, 1)) {
                Object v = in.apply(a[0], row);
                if (truthy(v)) return v;
            }
            return Boolean.FALSE;
        });
        def("for-all", 2, -1, (in, a) -> {
            Object v = Boolean.TRUE;
            for (Object[] row : zip("for-all", a, 1)) {
                v = in.apply(a[0], row);
                if (!truthy(v)) return Boolean.FALSE;
            }
            return v;
        });
        def("memp", 2, 2, (in, a) -> {
            for (Object l = a[1]; l instanceof Pair p; l = p.cdr) if (truthy(in.apply(a[0], new Object[] {p.car}))) return p;
            return Boolean.FALSE;
        });
        def("assp", 2, 2, (in, a) -> {
            for (Object l = a[1]; l instanceof Pair p; l = p.cdr) {
                if (p.car instanceof Pair e && truthy(in.apply(a[0], new Object[] {e.car}))) return e;
            }
            return Boolean.FALSE;
        });
        def("list-sort", 2, 2, (in, a) -> {
            List<Object> l = new ArrayList<>(list("list-sort", a[1]));
            sort(in, a[0], l);
            return fromList(in, l);
        });
        def("vector-sort", 2, 2, (in, a) -> {
            List<Object> l = new ArrayList<>(Arrays.asList(vec("vector-sort", a[1])));
            sort(in, a[0], l);
            in.alloc(l.size());
            return l.toArray();
        });
        def("vector-sort!", 2, 2, (in, a) -> {
            Object[] v = vec("vector-sort!", a[1]);
            List<Object> l = new ArrayList<>(Arrays.asList(v));
            sort(in, a[0], l);
            for (int k = 0; k < v.length; k++) v[k] = l.get(k);
            return Void.VOID;
        });
    }

    private static Object rem(Interp in, Object[] a, int mode) {
        List<Object> out = new ArrayList<>();
        for (Object x : list("remove", a[1])) if (!same(a[0], x, mode)) out.add(x);
        return fromList(in, out);
    }

    /** 稳定归并排序（比较器是脚本里的过程，不能交给 TimSort：它对不一致的比较器会抛）。 */
    private static void sort(Interp in, Object less, List<Object> l) {
        proc("sort", less);
        Object[] a = l.toArray();
        Object[] tmp = new Object[a.length];
        for (int width = 1; width < a.length; width *= 2) {
            for (int lo = 0; lo < a.length - width; lo += 2 * width) {
                int mid = lo + width;
                int hi = Math.min(lo + 2 * width, a.length);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    tmp[k++] = truthy(in.apply(less, new Object[] {a[j], a[i]})) ? a[j++] : a[i++];
                }
                while (i < mid) tmp[k++] = a[i++];
                while (j < hi) tmp[k++] = a[j++];
                System.arraycopy(tmp, lo, a, lo, hi - lo);
            }
        }
        for (int k = 0; k < a.length; k++) l.set(k, a[k]);
    }

    // ═══ 字符 ═══════════════════════════════════════════════════════════

    private static void chars() {
        def("char?", 1, 1, (in, a) -> a[0] instanceof SChar);
        def("char->integer", 1, 1, (in, a) -> (long) chr("char->integer", a[0]));
        def("integer->char", 1, 1, (in, a) -> {
            long n = integer("integer->char", a[0]);
            if (n < 0 || n > 0x10FFFF) throw err("integer->char", n + " is not a valid code point");
            return new SChar((int) n);
        });
        charCmp("char=?", c -> c == 0, false);
        charCmp("char<?", c -> c < 0, false);
        charCmp("char>?", c -> c > 0, false);
        charCmp("char<=?", c -> c <= 0, false);
        charCmp("char>=?", c -> c >= 0, false);
        charCmp("char-ci=?", c -> c == 0, true);
        charCmp("char-ci<?", c -> c < 0, true);
        charCmp("char-ci>?", c -> c > 0, true);
        def("char-upcase", 1, 1, (in, a) -> new SChar(Character.toUpperCase(chr("char-upcase", a[0]))));
        def("char-downcase", 1, 1, (in, a) -> new SChar(Character.toLowerCase(chr("char-downcase", a[0]))));
        def("char-foldcase", 1, 1, (in, a) -> new SChar(Character.toLowerCase(Character.toUpperCase(chr("char-foldcase", a[0])))));
        def("char-alphabetic?", 1, 1, (in, a) -> Character.isLetter(chr("char-alphabetic?", a[0])));
        def("char-numeric?", 1, 1, (in, a) -> Character.isDigit(chr("char-numeric?", a[0])));
        def("char-whitespace?", 1, 1, (in, a) -> Character.isWhitespace(chr("char-whitespace?", a[0])));
        def("char-upper-case?", 1, 1, (in, a) -> Character.isUpperCase(chr("char-upper-case?", a[0])));
        def("char-lower-case?", 1, 1, (in, a) -> Character.isLowerCase(chr("char-lower-case?", a[0])));
    }

    private static void charCmp(String name, Cmp ok, boolean ci) {
        def(name, 1, -1, (in, a) -> {
            for (int k = 0; k + 1 < a.length; k++) {
                int x = chr(name, a[k]);
                int y = chr(name, a[k + 1]);
                if (ci) { x = Character.toLowerCase(x); y = Character.toLowerCase(y); }
                if (!ok.ok(Integer.compare(x, y))) return false;
            }
            return true;
        });
    }

    // ═══ 字符串 ═════════════════════════════════════════════════════════

    static final int MAX_STRING = 8_000_000;

    static String checkLen(Interp in, String s) {
        if (s.length() > MAX_STRING) throw new Datum.LimitError("字符串太长（超过 " + MAX_STRING + " 字符）");
        in.alloc(s.length() / 8 + 1);
        return s;
    }

    private static void strings() {
        def("string?", 1, 1, (in, a) -> a[0] instanceof String);
        def("make-string", 1, 2, (in, a) -> {
            int n = index("make-string", a[0]);
            if (n > MAX_STRING) throw new Datum.LimitError("字符串太长（超过 " + MAX_STRING + " 字符）");
            int c = a.length > 1 ? chr("make-string", a[1]) : ' ';
            return checkLen(in, new String(Character.toChars(c)).repeat(n));
        });
        def("string", 0, -1, (in, a) -> {
            StringBuilder b = new StringBuilder();
            for (Object x : a) b.appendCodePoint(chr("string", x));
            return checkLen(in, b.toString());
        });
        def("string-length", 1, 1, (in, a) -> {
            String s = str("string-length", a[0]);
            return (long) s.codePointCount(0, s.length());
        });
        def("string-ref", 2, 2, (in, a) -> {
            String s = str("string-ref", a[0]);
            int k = index("string-ref", a[1]);
            if (k >= s.codePointCount(0, s.length())) throw err("string-ref", k + " is not a valid index for " + Printer.write(s));
            return new SChar(s.codePointAt(s.offsetByCodePoints(0, k)));
        });
        def("substring", 2, 3, (in, a) -> {
            String s = str("substring", a[0]);
            int n = s.codePointCount(0, s.length());
            int from = index("substring", a[1]);
            int to = a.length > 2 ? index("substring", a[2]) : n;
            if (from > to || to > n) throw err("substring", from + " and " + to + " are not valid start/end indices for " + Printer.write(s));
            return s.substring(s.offsetByCodePoints(0, from), s.offsetByCodePoints(0, to));
        });
        def("string-append", 0, -1, (in, a) -> {
            StringBuilder b = new StringBuilder();
            for (Object x : a) {
                b.append(str("string-append", x));
                if (b.length() > MAX_STRING) throw new Datum.LimitError("字符串太长（超过 " + MAX_STRING + " 字符）");
            }
            return checkLen(in, b.toString());
        });
        def("string-copy", 1, 1, (in, a) -> str("string-copy", a[0]));
        def("string->list", 1, 1, (in, a) -> {
            List<Object> out = new ArrayList<>();
            str("string->list", a[0]).codePoints().forEach(c -> out.add(new SChar(c)));
            return fromList(in, out);
        });
        def("list->string", 1, 1, (in, a) -> {
            StringBuilder b = new StringBuilder();
            for (Object x : list("list->string", a[0])) b.appendCodePoint(chr("list->string", x));
            return checkLen(in, b.toString());
        });
        def("string-for-each", 2, 2, (in, a) -> {
            str("string-for-each", a[1]).codePoints().forEach(c -> in.apply(a[0], new Object[] {new SChar(c)}));
            return Void.VOID;
        });
        strCmp("string=?", c -> c == 0, false);
        strCmp("string<?", c -> c < 0, false);
        strCmp("string>?", c -> c > 0, false);
        strCmp("string<=?", c -> c <= 0, false);
        strCmp("string>=?", c -> c >= 0, false);
        strCmp("string-ci=?", c -> c == 0, true);
        strCmp("string-ci<?", c -> c < 0, true);
        strCmp("string-ci>?", c -> c > 0, true);
        def("string-upcase", 1, 1, (in, a) -> str("string-upcase", a[0]).toUpperCase());
        def("string-downcase", 1, 1, (in, a) -> str("string-downcase", a[0]).toLowerCase());
        def("string-foldcase", 1, 1, (in, a) -> str("string-foldcase", a[0]).toUpperCase().toLowerCase());
    }

    private static void strCmp(String name, Cmp ok, boolean ci) {
        def(name, 1, -1, (in, a) -> {
            for (int k = 0; k + 1 < a.length; k++) {
                String x = str(name, a[k]);
                String y = str(name, a[k + 1]);
                int c = ci ? x.compareToIgnoreCase(y) : x.compareTo(y);
                if (!ok.ok(Integer.signum(c))) return false;
            }
            return true;
        });
    }

    // ═══ 向量 ═══════════════════════════════════════════════════════════

    static final int MAX_VECTOR = 4_000_000;

    private static void vectors() {
        def("vector?", 1, 1, (in, a) -> a[0] instanceof Object[]);
        def("make-vector", 1, 2, (in, a) -> {
            int n = index("make-vector", a[0]);
            if (n > MAX_VECTOR) throw new Datum.LimitError("向量太长（超过 " + MAX_VECTOR + " 个）");
            in.alloc(n);
            Object[] v = new Object[n];
            Arrays.fill(v, a.length > 1 ? a[1] : 0L);
            return v;
        });
        def("vector", 0, -1, (in, a) -> {
            in.alloc(a.length);
            return a.clone();
        });
        def("vector-length", 1, 1, (in, a) -> (long) vec("vector-length", a[0]).length);
        def("vector-ref", 2, 2, (in, a) -> {
            Object[] v = vec("vector-ref", a[0]);
            int k = index("vector-ref", a[1]);
            if (k >= v.length) throw err("vector-ref", k + " is not a valid index for " + Printer.write(v));
            return v[k];
        });
        def("vector-set!", 3, 3, (in, a) -> {
            Object[] v = vec("vector-set!", a[0]);
            int k = index("vector-set!", a[1]);
            if (k >= v.length) throw err("vector-set!", k + " is not a valid index for " + Printer.write(v));
            v[k] = a[2];
            return Void.VOID;
        });
        def("vector->list", 1, 1, (in, a) -> fromList(in, Arrays.asList(vec("vector->list", a[0]))));
        def("list->vector", 1, 1, (in, a) -> {
            List<Object> l = list("list->vector", a[0]);
            in.alloc(l.size());
            return l.toArray();
        });
        def("vector-fill!", 2, 2, (in, a) -> { Arrays.fill(vec("vector-fill!", a[0]), a[1]); return Void.VOID; });
        def("vector-map", 2, -1, (in, a) -> {
            Object[][] vs = new Object[a.length - 1][];
            int n = Integer.MAX_VALUE;
            for (int k = 1; k < a.length; k++) { vs[k - 1] = vec("vector-map", a[k]); n = Math.min(n, vs[k - 1].length); }
            in.alloc(n);
            Object[] out = new Object[n];
            for (int i = 0; i < n; i++) {
                Object[] row = new Object[vs.length];
                for (int k = 0; k < vs.length; k++) row[k] = vs[k][i];
                out[i] = in.apply(a[0], row);
            }
            return out;
        });
        def("vector-for-each", 2, -1, (in, a) -> {
            Object[][] vs = new Object[a.length - 1][];
            int n = Integer.MAX_VALUE;
            for (int k = 1; k < a.length; k++) { vs[k - 1] = vec("vector-for-each", a[k]); n = Math.min(n, vs[k - 1].length); }
            for (int i = 0; i < n; i++) {
                Object[] row = new Object[vs.length];
                for (int k = 0; k < vs.length; k++) row[k] = vs[k][i];
                in.apply(a[0], row);
            }
            return Void.VOID;
        });
    }

    // ═══ 散列表 ═════════════════════════════════════════════════════════

    private static void hashtables() {
        def("make-eq-hashtable", 0, 1, (in, a) -> new Hashtable(Hashtable.Kind.EQV));
        def("make-eqv-hashtable", 0, 1, (in, a) -> new Hashtable(Hashtable.Kind.EQV));
        // (make-hashtable hash equiv)：认得 string-ci-hash 就按不分大小写；其余一律按 equal? 比（够用且安全）
        def("make-hashtable", 2, 3, (in, a) -> {
            proc("make-hashtable", a[0]);
            proc("make-hashtable", a[1]);
            boolean ci = a[0] instanceof Builtin b && b.name.equals("string-ci-hash");
            boolean eqv = a[1] instanceof Builtin b && (b.name.equals("eq?") || b.name.equals("eqv?"));
            return new Hashtable(ci ? Hashtable.Kind.STRING_CI : eqv ? Hashtable.Kind.EQV : Hashtable.Kind.EQUAL);
        });
        def("equal-hash", 1, 1, (in, a) -> (long) Datum.equalHash(a[0], 0));
        def("string-hash", 1, 1, (in, a) -> (long) str("string-hash", a[0]).hashCode());
        def("string-ci-hash", 1, 1, (in, a) -> (long) str("string-ci-hash", a[0]).toLowerCase().hashCode());
        def("symbol-hash", 1, 1, (in, a) -> (long) a[0].hashCode());
        def("hashtable?", 1, 1, (in, a) -> a[0] instanceof Hashtable);
        def("hashtable-size", 1, 1, (in, a) -> (long) ht("hashtable-size", a[0]).map.size());
        def("hashtable-ref", 3, 3, (in, a) -> {
            Hashtable h = ht("hashtable-ref", a[0]);
            Object[] e = h.map.get(h.key(a[1]));
            return e == null ? a[2] : e[1];
        });
        def("hashtable-contains?", 2, 2, (in, a) -> {
            Hashtable h = ht("hashtable-contains?", a[0]);
            return h.map.containsKey(h.key(a[1]));
        });
        def("hashtable-set!", 3, 3, (in, a) -> {
            Hashtable h = mutableHt("hashtable-set!", a[0]);
            in.alloc(2);
            h.map.put(h.key(a[1]), new Object[] {a[1], a[2]});
            return Void.VOID;
        });
        def("hashtable-delete!", 2, 2, (in, a) -> {
            Hashtable h = mutableHt("hashtable-delete!", a[0]);
            h.map.remove(h.key(a[1]));
            return Void.VOID;
        });
        def("hashtable-update!", 4, 4, (in, a) -> {
            Hashtable h = mutableHt("hashtable-update!", a[0]);
            Object k = h.key(a[1]);
            Object[] e = h.map.get(k);
            Object v = in.apply(a[2], new Object[] {e == null ? a[3] : e[1]});
            in.alloc(2);
            h.map.put(k, new Object[] {a[1], v});
            return Void.VOID;
        });
        def("hashtable-clear!", 1, 2, (in, a) -> { mutableHt("hashtable-clear!", a[0]).map.clear(); return Void.VOID; });
        def("hashtable-copy", 1, 2, (in, a) -> {
            Hashtable h = ht("hashtable-copy", a[0]);
            in.alloc(h.map.size() * 2L);
            return new Hashtable(h.kind, a.length > 1 && truthy(a[1]), new LinkedHashMap<>(h.map));
        });
        def("hashtable-mutable?", 1, 1, (in, a) -> ht("hashtable-mutable?", a[0]).mutable);
        def("hashtable-keys", 1, 1, (in, a) -> {
            Hashtable h = ht("hashtable-keys", a[0]);
            in.alloc(h.map.size());
            return h.map.values().stream().map(e -> e[0]).toArray();
        });
        def("hashtable-entries", 1, 1, (in, a) -> {
            Hashtable h = ht("hashtable-entries", a[0]);
            in.alloc(h.map.size() * 2L);
            return new MultipleValues(new Object[] {h.map.values().stream().map(e -> e[0]).toArray(),
                    h.map.values().stream().map(e -> e[1]).toArray()});
        });
    }

    private static Hashtable mutableHt(String who, Object x) {
        Hashtable h = ht(who, x);
        if (!h.mutable) throw err(who, Printer.write(x) + " is not mutable");
        return h;
    }

    // ═══ 控制 ═══════════════════════════════════════════════════════════

    private static void control() {
        def("values", 0, -1, (in, a) -> a.length == 1 ? a[0] : new MultipleValues(a.clone()));
        def("call-with-values", 2, 2, (in, a) -> {
            Object v = in.apply(a[0], new Object[0]);
            return in.apply(a[1], v instanceof MultipleValues mv ? mv.vals() : new Object[] {v});
        });
        def("dynamic-wind", 3, 3, (in, a) -> {
            in.apply(a[0], new Object[0]);
            try {
                return in.apply(a[1], new Object[0]);
            } finally {
                in.apply(a[2], new Object[0]);
            }
        });
        def("call-with-current-continuation", 1, 1, (in, a) -> in.callCC(proc("call/cc", a[0])));
        def("call/cc", 1, 1, (in, a) -> in.callCC(proc("call/cc", a[0])));
    }

    // ═══ 异常与条件 ═════════════════════════════════════════════════════

    private static void conditions() {
        def("raise", 1, 1, (in, a) -> { throw new SchemeError(a[0], false); });
        def("raise-continuable", 1, 1, (in, a) -> { throw new SchemeError(a[0], true); });
        def("error", 1, -1, (in, a) -> {
            // (error who msg irritant …) 或 Chez 惯用的 (error "msg" …)
            Object who;
            int mi;
            if (a.length >= 2 && (a[0] instanceof Sym || a[0] instanceof Boolean || a[0] instanceof String) && a[1] instanceof String) {
                who = a[0];
                mi = 1;
            } else {
                who = Boolean.FALSE;
                mi = 0;
            }
            String msg = a[mi] instanceof String s ? s : Printer.write(a[mi]);
            throw new SchemeError(new Condition("error", who, msg,
                    new ArrayList<>(Arrays.asList(a).subList(mi + 1, a.length))), false);
        });
        def("assertion-violation", 2, -1, (in, a) -> {
            throw new SchemeError(new Condition("assertion", a[0], str("assertion-violation", a[1]),
                    new ArrayList<>(Arrays.asList(a).subList(2, a.length))), false);
        });
        // ⚠️ 简化：处理器在 with-exception-handler 这一层调用（不是在 raise 那一点恢复执行）。
        //    raise-continuable 时处理器的返回值就是整个 thunk 的值；raise 时处理器返回即再报错。
        def("with-exception-handler", 2, 2, (in, a) -> {
            Object handler = proc("with-exception-handler", a[0]);
            try {
                return in.apply(a[1], new Object[0]);
            } catch (SchemeError e) {
                Object v = in.apply(handler, new Object[] {e.payload});
                if (e.continuable) return v;
                throw err(null, "handler returned from non-continuable raise", e.payload);
            }
        });
        def("condition?", 1, 1, (in, a) -> a[0] instanceof Condition);
        def("error?", 1, 1, (in, a) -> a[0] instanceof Condition c && c.kind.equals("error"));
        def("error-object?", 1, 1, (in, a) -> a[0] instanceof Condition);
        def("violation?", 1, 1, (in, a) -> a[0] instanceof Condition c && c.kind.equals("assertion"));
        def("assertion-violation?", 1, 1, (in, a) -> a[0] instanceof Condition c && c.kind.equals("assertion"));
        def("serious-condition?", 1, 1, (in, a) -> a[0] instanceof Condition);
        def("message-condition?", 1, 1, (in, a) -> a[0] instanceof Condition);
        def("who-condition?", 1, 1, (in, a) -> a[0] instanceof Condition c && c.who != Boolean.FALSE);
        def("irritants-condition?", 1, 1, (in, a) -> a[0] instanceof Condition);
        def("condition-message", 1, 1, (in, a) -> cond("condition-message", a[0]).message);
        def("error-object-message", 1, 1, (in, a) -> cond("error-object-message", a[0]).message);
        def("condition-who", 1, 1, (in, a) -> cond("condition-who", a[0]).who);
        def("condition-irritants", 1, 1, (in, a) -> fromList(in, cond("condition-irritants", a[0]).irritants));
        def("error-object-irritants", 1, 1, (in, a) -> fromList(in, cond("error-object-irritants", a[0]).irritants));
    }

    private static Condition cond(String who, Object x) {
        if (x instanceof Condition c) return c;
        throw err(who, Printer.write(x) + " is not a condition");
    }

    // ═══ api.ss ═════════════════════════════════════════════════════════

    private static void api() {
        def("call", 1, 2, (in, a) -> {
            if (!(a[0] instanceof String name)) throw err("call", "工具名要是字符串，像 \"server.tool\"");
            return in.host.call(in, name, a.length > 1 && Datum.isList(a[1]) ? a[1] : Nil.NIL);
        });
        def("log!", 1, -1, (in, a) -> {
            String line;
            if (a.length == 1) line = a[0] instanceof String s ? s : Printer.write(a[0]);
            else line = format(str("log!", a[0]), Arrays.copyOfRange(a, 1, a.length));
            in.host.log(line);
            return Void.VOID;
        });
        def("json-parse", 1, 1, (in, a) -> SJson.parse(in, str("json-parse", a[0])));
        def("json-stringify", 1, 1, (in, a) -> checkLen(in, SJson.stringify(a[0])));
        def("format", 1, -1, (in, a) -> {
            int start = 0;
            if (a[0] == Boolean.FALSE) start = 1;
            else if (a[0] == Boolean.TRUE) throw err("format", "这里只能造串（不能写到端口）");
            if (start >= a.length) throw err("format", "缺格式串");
            return checkLen(in, format(str("format", a[start]), Arrays.copyOfRange(a, start + 1, a.length)));
        });
        def("string-split", 2, 2, (in, a) -> {
            String s = str("string-split", a[0]);
            String sep = a[1] instanceof SChar c ? new String(Character.toChars(c.cp())) : str("string-split", a[1]);
            List<Object> out = new ArrayList<>();
            if (sep.isEmpty()) {
                out.add(s);
            } else {
                int start = 0;
                int i;
                while ((i = s.indexOf(sep, start)) >= 0) {
                    out.add(s.substring(start, i));
                    start = i + sep.length();
                }
                out.add(s.substring(start));
            }
            return fromList(in, out);
        });
        def("string-join", 1, 2, (in, a) -> {
            List<Object> l = list("string-join", a[0]);
            String sep = a.length > 1 ? (a[1] instanceof SChar c ? new String(Character.toChars(c.cp())) : str("string-join", a[1])) : " ";
            StringBuilder b = new StringBuilder();
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(sep);
                b.append(str("string-join", l.get(k)));
                if (b.length() > MAX_STRING) throw new Datum.LimitError("字符串太长（超过 " + MAX_STRING + " 字符）");
            }
            return checkLen(in, b.toString());
        });
        def("string-trim", 1, 1, (in, a) -> str("string-trim", a[0]).strip());
        def("string-contains?", 2, 2, (in, a) -> str("string-contains?", a[0]).contains(str("string-contains?", a[1])));
        def("string-prefix?", 2, 2, (in, a) -> str("string-prefix?", a[1]).startsWith(str("string-prefix?", a[0])));
        def("string-suffix?", 2, 2, (in, a) -> str("string-suffix?", a[1]).endsWith(str("string-suffix?", a[0])));
        def("ref", 2, 3, (in, a) -> {
            Object dflt = a.length > 2 ? a[2] : Boolean.FALSE;
            if (!Datum.isList(a[0])) return dflt;
            Object e = ass(a[1], a[0], 2);
            return e instanceof Pair p ? p.cdr : dflt;
        });
    }

    /** Chez {@code format} 的常用子集：~a ~s ~d ~b ~o ~x ~c ~% ~n ~~。 */
    static String format(String fmt, Object[] args) {
        StringBuilder b = new StringBuilder();
        int ai = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '~') { b.append(c); continue; }
            if (++i >= fmt.length()) throw err("format", "格式串以 ~ 结尾");
            char d = Character.toLowerCase(fmt.charAt(i));
            switch (d) {
                case '%', 'n' -> b.append('\n');
                case '~' -> b.append('~');
                case 'a', 's', 'd', 'b', 'o', 'x', 'c' -> {
                    if (ai >= args.length) throw err("format", "too few arguments for control string " + Printer.write(fmt));
                    Object v = args[ai++];
                    switch (d) {
                        case 'a' -> Printer.print(v, b, false, 0);
                        case 's' -> Printer.print(v, b, true, 0);
                        case 'd' -> Printer.print(v, b, false, 0);
                        case 'c' -> b.appendCodePoint(chr("format", v));
                        default -> {
                            int radix = d == 'b' ? 2 : d == 'o' ? 8 : 16;
                            b.append(Long.toString(integer("format", v), radix));
                        }
                    }
                }
                default -> throw err("format", "不认识的格式指令 ~" + fmt.charAt(i));
            }
            if (b.length() > MAX_STRING) throw new Datum.LimitError("字符串太长（超过 " + MAX_STRING + " 字符）");
        }
        if (ai < args.length) throw err("format", "too many arguments for control string " + Printer.write(fmt));
        return b.toString();
    }
}
