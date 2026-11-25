package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Builtin;
import io.ailink.agentforge.agent.codemode.Datum.CaseLambda;
import io.ailink.agentforge.agent.codemode.Datum.Closure;
import io.ailink.agentforge.agent.codemode.Datum.Condition;
import io.ailink.agentforge.agent.codemode.Datum.Continuation;
import io.ailink.agentforge.agent.codemode.Datum.ContinuationThrow;
import io.ailink.agentforge.agent.codemode.Datum.Env;
import io.ailink.agentforge.agent.codemode.Datum.LimitError;
import io.ailink.agentforge.agent.codemode.Datum.MultipleValues;
import io.ailink.agentforge.agent.codemode.Datum.Nil;
import io.ailink.agentforge.agent.codemode.Datum.Pair;
import io.ailink.agentforge.agent.codemode.Datum.Procedure;
import io.ailink.agentforge.agent.codemode.Datum.SChar;
import io.ailink.agentforge.agent.codemode.Datum.SchemeError;
import io.ailink.agentforge.agent.codemode.Datum.Sym;
import io.ailink.agentforge.agent.codemode.Datum.Void;
import io.ailink.agentforge.agent.model.CancelToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

/**
 * 求值器：直接在数据上走（不预编译），尾调用不长栈（{@code (let loop () …)} 跑多久都不爆）。
 *
 * <p>围栏：环境里只有 {@link Builtins} 登记的纯函数 + 宿主给的 {@code call} / {@code log!}；
 * 没有文件、网络、进程、反射、{@code eval}。每次运行一份全新的全局帧，{@code define} 不跨次残留。
 *
 * <p>限额：步数（每次求值一步）、墙钟期限、分配单元数、按停 —— 撞线抛 {@link LimitError}，
 * 它不是 Scheme 异常，脚本里的 {@code guard} 接不住。
 */
final class Interp {
    private static final Sym QUOTE = Sym.of("quote");
    private static final Sym QUASIQUOTE = Sym.of("quasiquote");
    private static final Sym UNQUOTE = Sym.of("unquote");
    private static final Sym UNQUOTE_SPLICING = Sym.of("unquote-splicing");
    private static final Sym ELSE = Sym.of("else");
    private static final Sym ARROW = Sym.of("=>");
    private static final Object UNASSIGNED = new Object();

    private static final Set<String> SPECIAL = Set.of("quote", "quasiquote", "if", "define", "set!", "lambda",
            "case-lambda", "begin", "let", "let*", "letrec", "letrec*", "let-values", "let*-values", "cond", "case",
            "and", "or", "when", "unless", "do", "guard", "assert", "define-values");

    final Env global;
    private final long maxSteps;
    private final long maxCells;
    private final long deadlineNanos;
    final CancelToken cancel;
    volatile boolean abort;
    private long steps;
    private long cells;

    /** 宿主口子：{@code (call name args)} 与 {@code (log! …)}。 */
    interface Host {
        Object call(Interp in, String name, Object args);

        void log(String line);
    }

    final Host host;

    Interp(Host host, long maxSteps, long maxCells, long deadlineNanos, CancelToken cancel) {
        this.host = host;
        this.maxSteps = maxSteps;
        this.maxCells = maxCells;
        this.deadlineNanos = deadlineNanos;
        this.cancel = cancel;
        this.global = new Env(null, new HashMap<>(Builtins.table()));
    }

    // ═══ 限额 ═══════════════════════════════════════════════════════════

    void tick() {
        long n = ++steps;
        if (n > maxSteps) throw new LimitError("步数超过上限（" + maxSteps + " 步）");
        if ((n & 1023) == 0) checkClock();
    }

    void checkClock() {
        if (abort || System.nanoTime() > deadlineNanos) throw new LimitError("超时");
        if (cancel != null && cancel.isCancelled()) throw new CancelToken.CancelledException();
    }

    /** 记一笔分配（序对 / 向量槽 / 字符串字符按 8 个一单元）。 */
    void alloc(long n) {
        cells += n;
        if (cells > maxCells) throw new LimitError("内存配额用完（分配超过 " + maxCells + " 个单元）");
    }

    Pair cons(Object a, Object b) {
        alloc(1);
        return new Pair(a, b);
    }

    // ═══ 报错 ═══════════════════════════════════════════════════════════

    static SchemeError err(String who, String msg, Object... irritants) {
        return new SchemeError(new Condition("error", who == null ? Boolean.FALSE : Sym.of(who), msg,
                irritants.length == 0 ? List.of() : Arrays.asList(irritants)), false);
    }

    static SchemeError syntax(Object form) { return err(null, "invalid syntax " + Printer.write(form)); }

    // ═══ 环境 ═══════════════════════════════════════════════════════════

    Object lookup(Sym s, Env env) {
        for (Env e = env; e != null; e = e.parent) {
            Object v = e.vars.get(s);
            if (v != null || e.vars.containsKey(s)) {
                if (v == UNASSIGNED) throw err(null, "attempt to reference undefined variable " + s.name);
                return v;
            }
        }
        throw err(null, "variable " + s.name + " is not bound");
    }

    private static boolean bound(Sym s, Env env) {
        for (Env e = env; e != null; e = e.parent) if (e.vars.containsKey(s)) return true;
        return false;
    }

    private void set(Sym s, Env env, Object v) {
        for (Env e = env; e != null; e = e.parent) {
            if (e.vars.containsKey(s)) {
                e.vars.put(s, v);
                return;
            }
        }
        throw err("set!", "variable " + s.name + " is not bound");
    }

    // ═══ 求值 ═══════════════════════════════════════════════════════════

    static boolean truthy(Object v) { return v != Boolean.FALSE; }

    Object eval(Object x, Env env) {
        while (true) {
            tick();
            if (x instanceof Sym s) return lookup(s, env);
            if (!(x instanceof Pair p)) {
                if (x == Nil.NIL) throw syntax(x);
                return x;
            }
            Object head = p.car;
            if (head instanceof Sym s && SPECIAL.contains(s.name) && !bound(s, env)) {
                switch (s.name) {
                    case "quote" -> { return arg1(p); }
                    case "quasiquote" -> { return quasi(arg1(p), 1, env); }
                    case "if" -> {
                        List<Object> f = form(p, 3, 4);
                        if (truthy(eval(f.get(1), env))) { x = f.get(2); continue; }
                        if (f.size() == 4) { x = f.get(3); continue; }
                        return Void.VOID;
                    }
                    case "define" -> { define(p, env); return Void.VOID; }
                    case "define-values" -> { defineValues(p, env); return Void.VOID; }
                    case "set!" -> {
                        List<Object> f = form(p, 3, 3);
                        if (!(f.get(1) instanceof Sym v)) throw syntax(p);
                        set(v, env, eval(f.get(2), env));
                        return Void.VOID;
                    }
                    case "lambda" -> {
                        if (!(p.cdr instanceof Pair rest) || !(rest.cdr instanceof Pair)) throw syntax(p);
                        return closure(null, rest.car, rest.cdr, env, p);
                    }
                    case "case-lambda" -> {
                        List<Object> cl = Datum.toList(p.cdr);
                        if (cl == null) throw syntax(p);
                        Closure[] cs = new Closure[cl.size()];
                        for (int k = 0; k < cs.length; k++) {
                            if (!(cl.get(k) instanceof Pair c) || !(c.cdr instanceof Pair)) throw syntax(p);
                            cs[k] = closure(null, c.car, c.cdr, env, p);
                        }
                        return new CaseLambda(null, cs);
                    }
                    case "begin" -> {
                        if (p.cdr == Nil.NIL) return Void.VOID;
                        x = seqButLast(p.cdr, env);
                        continue;
                    }
                    case "let" -> {
                        if (!(p.cdr instanceof Pair r1)) throw syntax(p);
                        if (r1.car instanceof Sym name) {
                            // 命名 let：(let loop ((v init) …) body …)
                            if (!(r1.cdr instanceof Pair r2) || !(r2.cdr instanceof Pair)) throw syntax(p);
                            List<Object[]> bs = bindings(r2.car, p);
                            Sym[] params = new Sym[bs.size()];
                            Object[] vals = new Object[bs.size()];
                            for (int k = 0; k < params.length; k++) {
                                params[k] = (Sym) bs.get(k)[0];
                                vals[k] = eval(bs.get(k)[1], env);
                            }
                            Env loopEnv = new Env(env);
                            Closure c = new Closure(name.name, params, null, r2.cdr, loopEnv);
                            loopEnv.vars.put(name, c);
                            env = bind(c, vals);
                            x = seqButLast(c.body, env);
                            continue;
                        }
                        if (!(r1.cdr instanceof Pair)) throw syntax(p);
                        List<Object[]> bs = bindings(r1.car, p);
                        Env e2 = new Env(env);
                        for (Object[] b : bs) e2.vars.put((Sym) b[0], eval(b[1], env));
                        env = e2;
                        x = seqButLast(r1.cdr, env);
                        continue;
                    }
                    case "let*" -> {
                        if (!(p.cdr instanceof Pair r1) || !(r1.cdr instanceof Pair)) throw syntax(p);
                        Env e2 = env;
                        for (Object[] b : bindings(r1.car, p)) {
                            Object v = eval(b[1], e2);
                            e2 = new Env(e2);
                            e2.vars.put((Sym) b[0], v);
                        }
                        env = new Env(e2);
                        x = seqButLast(r1.cdr, env);
                        continue;
                    }
                    case "letrec", "letrec*" -> {
                        if (!(p.cdr instanceof Pair r1) || !(r1.cdr instanceof Pair)) throw syntax(p);
                        List<Object[]> bs = bindings(r1.car, p);
                        Env e2 = new Env(env);
                        for (Object[] b : bs) e2.vars.put((Sym) b[0], UNASSIGNED);
                        for (Object[] b : bs) e2.vars.put((Sym) b[0], named(eval(b[1], e2), ((Sym) b[0]).name));
                        env = e2;
                        x = seqButLast(r1.cdr, env);
                        continue;
                    }
                    case "let-values", "let*-values" -> {
                        if (!(p.cdr instanceof Pair r1) || !(r1.cdr instanceof Pair)) throw syntax(p);
                        List<Object> bs = Datum.toList(r1.car);
                        if (bs == null) throw syntax(p);
                        boolean seq = s.name.equals("let*-values");
                        Env e2 = new Env(env);
                        for (Object b : bs) {
                            List<Object> bl = Datum.toList(b);
                            if (bl == null || bl.size() != 2) throw syntax(p);
                            Object v = eval(bl.get(1), seq ? e2 : env);
                            if (seq) e2 = new Env(e2);
                            bindFormals(bl.get(0), v instanceof MultipleValues mv ? mv.vals() : new Object[] {v}, e2, p);
                        }
                        env = new Env(e2);
                        x = seqButLast(r1.cdr, env);
                        continue;
                    }
                    case "cond" -> {
                        Object next = null;
                        boolean matched = false;
                        for (Object cl = p.cdr; cl instanceof Pair cp; cl = cp.cdr) {
                            if (!(cp.car instanceof Pair clause)) throw syntax(p);
                            Object t;
                            if (clause.car == ELSE && !bound(ELSE, env)) {
                                t = Boolean.TRUE;
                            } else {
                                t = eval(clause.car, env);
                            }
                            if (!truthy(t)) continue;
                            matched = true;
                            if (clause.cdr == Nil.NIL) return t;
                            if (clause.cdr instanceof Pair c2 && c2.car == ARROW) {
                                Object f = eval(arg1(c2), env);
                                return apply(f, new Object[] {t});
                            }
                            next = seqButLast(clause.cdr, env);
                            break;
                        }
                        if (!matched) return Void.VOID;
                        x = next;
                        continue;
                    }
                    case "case" -> {
                        if (!(p.cdr instanceof Pair r1)) throw syntax(p);
                        Object key = eval(r1.car, env);
                        Object next = null;
                        for (Object cl = r1.cdr; cl instanceof Pair cp; cl = cp.cdr) {
                            if (!(cp.car instanceof Pair clause)) throw syntax(p);
                            boolean hit = clause.car == ELSE;
                            if (!hit) {
                                for (Object d = clause.car; d instanceof Pair dp; d = dp.cdr) {
                                    if (isEqv(dp.car, key)) { hit = true; break; }
                                }
                            }
                            if (hit) {
                                if (clause.cdr instanceof Pair c2 && c2.car == ARROW) return apply(eval(arg1(c2), env), new Object[] {key});
                                next = seqButLast(clause.cdr, env);
                                break;
                            }
                        }
                        if (next == null) return Void.VOID;
                        x = next;
                        continue;
                    }
                    case "and" -> {
                        if (p.cdr == Nil.NIL) return Boolean.TRUE;
                        Object c = p.cdr;
                        while (c instanceof Pair cp && cp.cdr instanceof Pair) {
                            if (!truthy(eval(cp.car, env))) return Boolean.FALSE;
                            c = cp.cdr;
                        }
                        x = ((Pair) c).car;
                        continue;
                    }
                    case "or" -> {
                        if (p.cdr == Nil.NIL) return Boolean.FALSE;
                        Object c = p.cdr;
                        while (c instanceof Pair cp && cp.cdr instanceof Pair) {
                            Object v = eval(cp.car, env);
                            if (truthy(v)) return v;
                            c = cp.cdr;
                        }
                        x = ((Pair) c).car;
                        continue;
                    }
                    case "when", "unless" -> {
                        if (!(p.cdr instanceof Pair r1) || !(r1.cdr instanceof Pair)) throw syntax(p);
                        boolean t = truthy(eval(r1.car, env));
                        if (t != s.name.equals("when")) return Void.VOID;
                        x = seqButLast(r1.cdr, env);
                        continue;
                    }
                    case "do" -> {
                        List<Object> f = Datum.toList(p);
                        if (f == null || f.size() < 3) throw syntax(p);
                        List<Object> specs = Datum.toList(f.get(1));
                        List<Object> test = Datum.toList(f.get(2));
                        if (specs == null || test == null || test.isEmpty()) throw syntax(p);
                        Env e2 = new Env(env);
                        List<Object[]> steps = new ArrayList<>();
                        for (Object sp : specs) {
                            List<Object> l = Datum.toList(sp);
                            if (l == null || l.size() < 2 || l.size() > 3 || !(l.get(0) instanceof Sym v)) throw syntax(p);
                            e2.vars.put(v, eval(l.get(1), env));
                            if (l.size() == 3) steps.add(new Object[] {v, l.get(2)});
                        }
                        Object body = ((Pair) ((Pair) p.cdr).cdr).cdr;
                        while (true) {
                            tick();
                            if (truthy(eval(test.get(0), e2))) break;
                            for (Object b = body; b instanceof Pair bp; b = bp.cdr) eval(bp.car, e2);
                            Object[] nv = new Object[steps.size()];
                            for (int k = 0; k < nv.length; k++) nv[k] = eval(steps.get(k)[1], e2);
                            Env e3 = new Env(env);
                            e3.vars.putAll(e2.vars);
                            for (int k = 0; k < nv.length; k++) e3.vars.put((Sym) steps.get(k)[0], nv[k]);
                            e2 = e3;
                        }
                        if (test.size() == 1) return Void.VOID;
                        env = e2;
                        x = seqButLast(((Pair) f.get(2)).cdr, env);
                        continue;
                    }
                    case "guard" -> { return guard(p, env); }
                    case "assert" -> {
                        Object v = eval(arg1(p), env);
                        if (!truthy(v)) {
                            throw new SchemeError(new Condition("assertion", Sym.of("assert"), "failed assertion "
                                    + Printer.write(arg1(p)), List.of()), false);
                        }
                        return v;
                    }
                    default -> throw syntax(p);
                }
            }
            // 应用
            Object f = eval(head, env);
            List<Object> argForms = Datum.toList(p.cdr);
            if (argForms == null) throw syntax(p);
            Object[] args = new Object[argForms.size()];
            for (int k = 0; k < args.length; k++) args[k] = eval(argForms.get(k), env);
            if (f instanceof Closure c) {
                env = bind(c, args);
                x = seqButLast(c.body, env);
                continue;
            }
            if (f instanceof CaseLambda cl) {
                Closure c = pick(cl, args.length);
                env = bind(c, args);
                x = seqButLast(c.body, env);
                continue;
            }
            return applyNonClosure(f, args);
        }
    }

    /** 求值序列里除最后一个之外的全部，返回最后一个（交给调用方在尾位置继续）。 */
    private Object seqButLast(Object body, Env env) {
        Object b = body;
        while (b instanceof Pair bp && bp.cdr instanceof Pair) {
            eval(bp.car, env);
            b = bp.cdr;
        }
        if (!(b instanceof Pair last) || last.cdr != Nil.NIL) throw syntax(body);
        return last.car;
    }

    Object apply(Object f, Object[] args) {
        if (f instanceof Closure c) return evalBody(c.body, bind(c, args));
        if (f instanceof CaseLambda cl) {
            Closure c = pick(cl, args.length);
            return evalBody(c.body, bind(c, args));
        }
        return applyNonClosure(f, args);
    }

    private Object evalBody(Object body, Env env) { return eval(seqButLast(body, env), env); }

    private Object applyNonClosure(Object f, Object[] args) {
        if (f instanceof Builtin b) {
            tick();
            if (args.length < b.min || (b.max >= 0 && args.length > b.max)) {
                throw err(null, "incorrect number of arguments to " + Printer.write(b));
            }
            return b.fn.apply(this, args);
        }
        if (f instanceof Continuation k) {
            if (!k.valid) throw err(null, "这个续延已经出了它的范围（只支持往外逃的 call/cc）");
            throw new ContinuationThrow(k, args.length == 1 ? args[0] : new MultipleValues(args));
        }
        throw err(null, "attempt to apply non-procedure " + Printer.write(f));
    }

    private static Closure pick(CaseLambda cl, int n) {
        for (Closure c : cl.clauses) if (c.accepts(n)) return c;
        throw err(null, "incorrect number of arguments to " + Printer.write(cl));
    }

    private Env bind(Closure c, Object[] args) {
        if (!c.accepts(args.length)) throw err(null, "incorrect number of arguments to " + Printer.write(c));
        Env e = new Env(c.env);
        for (int k = 0; k < c.params.length; k++) e.vars.put(c.params[k], args[k]);
        if (c.rest != null) {
            Object r = Nil.NIL;
            for (int k = args.length - 1; k >= c.params.length; k--) r = cons(args[k], r);
            e.vars.put(c.rest, r);
        }
        return e;
    }

    private Closure closure(String name, Object formals, Object body, Env env, Object form) {
        List<Sym> ps = new ArrayList<>();
        Object f = formals;
        while (f instanceof Pair fp) {
            if (!(fp.car instanceof Sym s)) throw syntax(form);
            ps.add(s);
            f = fp.cdr;
        }
        Sym rest = null;
        if (f instanceof Sym s) rest = s;
        else if (f != Nil.NIL) throw syntax(form);
        return new Closure(name, ps.toArray(new Sym[0]), rest, body, env);
    }

    private static Object named(Object v, String name) {
        if (v instanceof Procedure p && p.name == null && !(v instanceof Builtin)) p.name = name;
        return v;
    }

    private void define(Pair p, Env env) {
        if (!(p.cdr instanceof Pair r1)) throw syntax(p);
        if (r1.car instanceof Pair sig) {
            // (define (f . args) body …)
            if (!(sig.car instanceof Sym name) || !(r1.cdr instanceof Pair)) throw syntax(p);
            env.vars.put(name, closure(name.name, sig.cdr, r1.cdr, env, p));
            return;
        }
        if (!(r1.car instanceof Sym name)) throw syntax(p);
        if (r1.cdr == Nil.NIL) {
            env.vars.put(name, Void.VOID);
            return;
        }
        if (!(r1.cdr instanceof Pair r2) || r2.cdr != Nil.NIL) throw syntax(p);
        env.vars.put(name, named(eval(r2.car, env), name.name));
    }

    private void defineValues(Pair p, Env env) {
        List<Object> f = form(p, 3, 3);
        Object v = eval(f.get(2), env);
        bindFormals(f.get(1), v instanceof MultipleValues mv ? mv.vals() : new Object[] {v}, env, p);
    }

    private void bindFormals(Object formals, Object[] vals, Env env, Object form) {
        int k = 0;
        Object f = formals;
        while (f instanceof Pair fp) {
            if (!(fp.car instanceof Sym s)) throw syntax(form);
            if (k >= vals.length) throw err(null, "incorrect number of values received");
            env.vars.put(s, vals[k++]);
            f = fp.cdr;
        }
        if (f instanceof Sym s) {
            Object r = Nil.NIL;
            for (int j = vals.length - 1; j >= k; j--) r = cons(vals[j], r);
            env.vars.put(s, r);
        } else if (f != Nil.NIL) {
            throw syntax(form);
        } else if (k != vals.length) {
            throw err(null, "incorrect number of values received");
        }
    }

    /** ((v init) …) → [sym, init] 表。 */
    private static List<Object[]> bindings(Object bs, Object form) {
        List<Object> l = Datum.toList(bs);
        if (l == null) throw syntax(form);
        List<Object[]> out = new ArrayList<>();
        for (Object b : l) {
            List<Object> bl = Datum.toList(b);
            if (bl == null || bl.size() != 2 || !(bl.get(0) instanceof Sym)) throw syntax(form);
            out.add(new Object[] {bl.get(0), bl.get(1)});
        }
        return out;
    }

    private static List<Object> form(Pair p, int min, int max) {
        List<Object> f = Datum.toList(p);
        if (f == null || f.size() < min || f.size() > max) throw syntax(p);
        return f;
    }

    private static Object arg1(Pair p) {
        if (!(p.cdr instanceof Pair r) || r.cdr != Nil.NIL) throw syntax(p);
        return r.car;
    }

    /**
     * {@code (guard (e clause …) body …)}：body 里 raise 的东西绑到 e 上按 cond 的规矩挑一条；
     * 一条都不中就原样再抛。⚠️ 限额撞线（{@link LimitError}）与按停不是 Scheme 异常，这里接不住。
     */
    private Object guard(Pair p, Env env) {
        if (!(p.cdr instanceof Pair r1) || !(r1.car instanceof Pair spec) || !(spec.car instanceof Sym var)
                || !(r1.cdr instanceof Pair)) {
            throw syntax(p);
        }
        try {
            return evalBody(r1.cdr, new Env(env));
        } catch (SchemeError e) {
            Env ge = new Env(env);
            ge.vars.put(var, e.payload);
            for (Object cl = spec.cdr; cl instanceof Pair cp; cl = cp.cdr) {
                if (!(cp.car instanceof Pair clause)) throw syntax(p);
                Object t = clause.car == ELSE ? Boolean.TRUE : eval(clause.car, ge);
                if (!truthy(t)) continue;
                if (clause.cdr == Nil.NIL) return t;
                if (clause.cdr instanceof Pair c2 && c2.car == ARROW) return apply(eval(arg1(c2), ge), new Object[] {t});
                return evalBody(clause.cdr, ge);
            }
            throw e;
        }
    }

    private Object quasi(Object x, int depth, Env env) {
        if (x instanceof Pair p) {
            if (p.car == UNQUOTE) {
                if (depth == 1) return eval(arg1(p), env);
                return Datum.list(UNQUOTE, quasi(arg1(p), depth - 1, env));
            }
            if (p.car == QUASIQUOTE) return Datum.list(QUASIQUOTE, quasi(arg1(p), depth + 1, env));
            if (p.car instanceof Pair inner && inner.car == UNQUOTE_SPLICING && depth == 1) {
                Object spliced = eval(arg1(inner), env);
                Object rest = quasi(p.cdr, depth, env);
                List<Object> items = Datum.toList(spliced);
                if (items == null) throw err("unquote-splicing", "不是一个真表", spliced);
                Object r = rest;
                for (int k = items.size() - 1; k >= 0; k--) r = cons(items.get(k), r);
                return r;
            }
            return cons(quasi(p.car, depth, env), quasi(p.cdr, depth, env));
        }
        if (x instanceof Object[] v) {
            List<Object> items = Datum.toList(quasi(Datum.list(v), depth, env));
            return items == null ? v : items.toArray();
        }
        return x;
    }

    // ═══ 等价 ═══════════════════════════════════════════════════════════

    static boolean isEqv(Object a, Object b) {
        if (a == b) return true;
        if (a instanceof Long x && b instanceof Long y) return x.longValue() == y.longValue();
        if (a instanceof Double x && b instanceof Double y) return Double.compare(x, y) == 0;
        if (a instanceof SChar x && b instanceof SChar y) return x.cp() == y.cp();
        if (a instanceof Boolean x && b instanceof Boolean y) return x.booleanValue() == y.booleanValue();
        if (a instanceof String x && b instanceof String y) return x.isEmpty() && y.isEmpty();
        return false;
    }

    static boolean isEqual(Object a, Object b) {
        int guard = 0;
        while (true) {
            if (isEqv(a, b)) return true;
            if (a instanceof String x && b instanceof String y) return x.equals(y);
            if (a instanceof Object[] x && b instanceof Object[] y) {
                if (x.length != y.length) return false;
                for (int k = 0; k < x.length; k++) if (!isEqual(x[k], y[k])) return false;
                return true;
            }
            if (a instanceof Pair x && b instanceof Pair y) {
                if (!isEqual(x.car, y.car)) return false;
                a = x.cdr;
                b = y.cdr;
                if (++guard > 10_000_000) return false;
                continue;
            }
            return false;
        }
    }

    /** 跑一个 thunk，并把它的动态范围当成 call/cc 续延的有效期。 */
    Object callCC(Object f) {
        Continuation k = new Continuation();
        k.name = "continuation";
        try {
            return apply(f, new Object[] {k});
        } catch (ContinuationThrow t) {
            if (t.k == k) return t.value;
            throw t;
        } finally {
            k.valid = false;
        }
    }
}
