package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.Clj.kw;
import static io.ailink.agentforge.bitcask.cypher.Clj.vec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Cypher 子集的语义检查（clj {@code cypher.sema}；Q-1，对照 bitcask（Erlang）{@code bitcask_cypher_sema}）。
 * <p>输入 {@link Parser#parse} 的 AST，输出 {@code {:query 规范化子句 :writes? 布尔 :params #{参数名}}}：
 * 匿名变量已命名（两个空格开头，用户写不出来）、投影项都有列名 {@code [名 e]}、变长上下限已补齐、
 * 表达式去掉位置：{@code [:var n]} {@code [:param n]} {@code [:call 名 distinct? 参数]} {@code :count-star}；其余同 AST。
 * <p>规范化后的子句：
 * <pre>
 *   [:match parts where loc]（parts：[:chain 节点 步] | [:shortest 路径变量 n0 rel n1 loc]）
 *   [:unwind e 名 loc] [:with distinct? 项 有聚合? order skip limit where loc] [:return distinct? 项 有聚合? order skip limit loc]
 *   [:create parts loc] [:set 项 loc] [:remove 项 loc] [:delete detach? [e] loc]
 *   [:optional-match parts where loc]（OPTIONAL MATCH）
 *   [:merge-node 名 [标签] 属性e on-create on-match loc] [:merge-rel from r 类型 属性e 方向 to on-create on-match loc]
 *   [:merge-pattern chain on-create on-match loc]（整条模式的 MERGE）[:call-proc 过程名 [[列 变量] …] where loc]
 *   模式里另有 [:named-chain 路径变量 chain]（普通命名路径）、[:all-shortest 路径变量 n0 rel n1 loc]（allShortestPaths）
 * </pre>
 * 表达式另有 {@code [:case 主体 [[when then] …] else]}（去掉位置）。
 * 节点 {@code [:node 名 [标签] 属性e loc]}，关系 {@code [:rel 名 [类型] 属性e 方向 [下限 上限] loc]}。
 * 报错：{@link CypherError#fail} 的 semantic 阶段。
 * <p>移植说明：clj 的动态变量 {@code *st*} 改成 {@link #norm(Object, St)} 的显式参数；只有 SKIP / LIMIT 的规范化
 * （clj 里不重新绑定、沿用外层绑定）要看「当前绑定」，用 {@link #AMBIENT} 记着（只在 norm 进 EXISTS 子查询时设置）。
 */
final class Sema {
    private Sema() {
    }

    // ── 关键字 ──
    private static final Kw QUERY = kw("query"), UNION = kw("union"), VAR = kw("var"), PARAM = kw("param"), LIT = kw("lit"),
            CALL = kw("call"), COUNT_STAR = kw("count-star"), HAS_LABELS = kw("has-labels"), PROP = kw("prop"), LIST = kw("list"),
            MAP = kw("map"), OP = kw("op"), UNOP = kw("unop"), IS_NULL = kw("is-null"), INDEX = kw("index"), SLICE = kw("slice"),
            CASE = kw("case"), LIST_COMP = kw("list-comp"), QUANT = kw("quant"), REDUCE = kw("reduce"), MAP_PROJ = kw("map-proj"),
            ENTRY = kw("entry"), VAR_SEL = kw("var-sel"), PROP_SEL = kw("prop-sel"), ALL_PROPS = kw("all-props"),
            PATTERN_PRED = kw("pattern-pred"), PATTERN_COMP = kw("pattern-comp"), EXISTS_SUB = kw("exists-sub"),
            SHORTEST_EXPR = kw("shortest-expr"), NAMED_CHAIN = kw("named-chain"), CHAIN = kw("chain"), NODE = kw("node"),
            REL = kw("rel"), QPP = kw("qpp"), PART = kw("part"), SHORTEST = kw("shortest"), ALL_SHORTEST = kw("all-shortest"),
            ALL = kw("all"), ANY = kw("any"), NOT = kw("not"), AND = kw("and"), OR = kw("or"), XOR = kw("xor"),
            MINUS = kw("-"), PLUS = kw("+"), MUL = kw("*"), DIV = kw("/"), MOD = kw("%"), POW = kw("pow"), EQ = kw("="),
            NE = kw("<>"), LT = kw("<"), LE = kw("<="), GT = kw(">"), GE = kw(">="), IN = kw("in"), STARTS_WITH = kw("starts-with"),
            ENDS_WITH = kw("ends-with"), CONTAINS = kw("contains"), REGEX = kw("regex"), AT_AT = kw("at-at"), OUT = kw("out"),
            PATH = kw("path"), REL_LIST = kw("rel-list"), VALUE = kw("value"), SCALAR = kw("scalar"), ANON = kw("anon"),
            INT = kw("int"), FLOAT = kw("float"), STRING = kw("string"), BOOLEAN = kw("boolean"), NULL = kw("null"),
            SINGLE = kw("single"), MATCH = kw("match"), OPTIONAL_MATCH = kw("optional-match"), UNWIND = kw("unwind"),
            WITH = kw("with"), RETURN = kw("return"), CREATE = kw("create"), SET = kw("set"), REMOVE = kw("remove"),
            DELETE = kw("delete"), MERGE = kw("merge"), CALL_PROC = kw("call-proc"), MERGE_NODE = kw("merge-node"),
            MERGE_REL = kw("merge-rel"), MERGE_PATTERN = kw("merge-pattern"), PATTERN = kw("pattern"), STAR = kw("star"),
            ITEMS = kw("items"), ITEM = kw("item"), SET_PROP = kw("set-prop"), SET_PROPS = kw("set-props"),
            SET_LABELS = kw("set-labels"), REMOVE_PROP = kw("remove-prop"), REMOVE_LABELS = kw("remove-labels"),
            BUILTIN = kw("builtin?"), OUTER = kw("outer"), WRITES = kw("writes?"), PARAMS = kw("params"), ALL_Q = kw("all?"),
            PARTS = kw("parts"), NODES = kw("nodes"), STEPS = kw("steps"), WHERE = kw("where"), MN = kw("mn"), MX = kw("mx"),
            GROUPS = kw("groups"), PVAR = kw("pvar"), KIND = kw("kind"), SPEC = kw("spec"), TYPES = kw("types"), PE = kw("pe"),
            DIR = kw("dir"), VARLEN = kw("varlen"), K = kw("k"), PRE = kw("pre"), LOC = kw("loc");

    /** 变长关系不写上限时的上限：openCypher 里 `*` / `*n..` 不设上限（关系唯一性保证展开有尽头）。D-33 前是 10。 */
    static final long UNBOUNDED = Long.MAX_VALUE;

    static final Set<String> AGGS = Set.of("count", "sum", "avg", "min", "max", "collect", "percentilecont", "percentiledisc", "stdev",
            "stdevp");

    private static Set<Object> ar(long... ns) {
        Set<Object> s = new LinkedHashSet<>();
        for (long n : ns) s.add(n);
        return s;
    }

    private static final Map<String, Object> FUNS = new HashMap<>();
    private static final Map<String, Object> SPATIAL_FUNS = new HashMap<>();
    private static final Map<String, Object> TEMPORAL_FUNS = new HashMap<>();

    static {
        Object[] f = {"id", ar(1), "labels", ar(1), "type", ar(1), "properties", ar(1), "keys", ar(1), "coalesce", ANY, "size", ar(1),
                "length", ar(1), "nodes", ar(1), "relationships", ar(1), "startnode", ar(1), "endnode", ar(1), "tointeger", ar(1),
                "tofloat", ar(1), "tostring", ar(1), "toboolean", ar(1), "tolower", ar(1), "toupper", ar(1), "abs", ar(1), "score", ar(1),
                // 二期：字符串与列表函数
                "substring", ar(2, 3), "replace", ar(3), "split", ar(2), "trim", ar(1), "ltrim", ar(1), "rtrim", ar(1), "left", ar(2),
                "right", ar(2), "reverse", ar(1), "head", ar(1), "tail", ar(1), "last", ar(1), "range", ar(2, 3),
                // openCypher：谓词、数学、聚合
                "exists", ar(1), "timestamp", ar(0),
                "ceil", ar(1), "floor", ar(1), "round", ar(1), "sign", ar(1), "rand", ar(0), "sqrt", ar(1), "exp", ar(1), "log", ar(1),
                "log10", ar(1), "e", ar(0), "pi", ar(0), "sin", ar(1), "cos", ar(1), "tan", ar(1), "cot", ar(1), "asin", ar(1),
                "acos", ar(1), "atan", ar(1), "atan2", ar(2), "degrees", ar(1), "radians", ar(1), "haversin", ar(1), "isnan", ar(1),
                "percentilecont", ar(2), "percentiledisc", ar(2), "stdev", ar(1), "stdevp", ar(1),
                // 二期：检索的函数形式
                "fts.phrase", ar(2), "fts.near", ar(3), "fts.wildcard", ar(2), "fts.fuzzy", ar(2, 3), "fts.fields", ar(2),
                "search.hybrid", ar(3, 4),
                "count", ar(1), "sum", ar(1), "avg", ar(1), "min", ar(1), "max", ar(1), "collect", ar(1)};
        for (int i = 0; i < f.length; i += 2) FUNS.put((String) f[i], f[i + 1]);
        SPATIAL_FUNS.put("point", ar(1));
        SPATIAL_FUNS.put("point.distance", ar(2));
        SPATIAL_FUNS.put("distance", ar(2));
        SPATIAL_FUNS.put("point.withinbbox", ar(3));
        for (String ty : List.of("date", "localtime", "time", "localdatetime", "datetime")) {
            TEMPORAL_FUNS.put(ty, ar(0, 1));
            TEMPORAL_FUNS.put(ty + ".transaction", ar(0, 1));
            TEMPORAL_FUNS.put(ty + ".statement", ar(0, 1));
            TEMPORAL_FUNS.put(ty + ".realtime", ar(0, 1));
            TEMPORAL_FUNS.put(ty + ".truncate", ar(2, 3));
        }
        TEMPORAL_FUNS.put("datetime.fromepoch", ar(2));
        TEMPORAL_FUNS.put("datetime.fromepochmillis", ar(1));
        TEMPORAL_FUNS.put("duration", ar(1));
        TEMPORAL_FUNS.put("duration.between", ar(2));
        TEMPORAL_FUNS.put("duration.inmonths", ar(2));
        TEMPORAL_FUNS.put("duration.indays", ar(2));
        TEMPORAL_FUNS.put("duration.inseconds", ar(2));
    }

    /** 内置过程（CALL）：名字（小写）⇒ 输出列。 */
    static final Map<String, List<String>> PROCS = Map.of("db.labels", List.of("label"), "db.relationshiptypes",
            List.of("relationshipType"), "db.propertykeys", List.of("propertyKey"));

    /** 过程签名 {:inputs [{:name :type :nullable?}] :outputs [{:name …}] :builtin? 布尔}；没有 ⇒ 语义错误。 */
    static Object procSig(Object nm, Object loc) {
        List<String> cols = PROCS.get(nm);
        if (cols != null) {
            List<Object> outs = new ArrayList<>();
            for (String c : cols) outs.add(Clj.map(Procs.NAME, c, Procs.TYPE, "ANY", Procs.NULLABLE, true));
            return Clj.map(Procs.INPUTS, vec(), Procs.OUTPUTS, outs, BUILTIN, true);
        }
        Map<Object, Object> p = nm instanceof String s ? Procs.lookup(s) : null;
        if (p != null) return p;
        throw CypherError.fail("semantic", "unknown procedure `" + Clj.str(nm) + "`", loc, List.of("ProcedureError", "ProcedureNotFound"));
    }

    static final Set<String> SEARCH_FNS = Set.of("fts.phrase", "fts.near", "fts.wildcard", "fts.fuzzy", "fts.fields", "search.hybrid");

    /** 语义错误；code = [openCypher 错误类型 细节代码]，在抛错处给出。 */
    private static CypherError fail(Object msg, Object loc, String ty, String de) {
        throw CypherError.fail("semantic", msg, loc, List.of(ty, de));
    }

    static boolean isHidden(Object v) {
        return ((String) v).startsWith("  ");
    }

    // ── 小工具 ──

    private static Object n(Object v, int i) {
        return Clj.nth(v, i);
    }

    private static Kw h(Object e) {
        return Clj.head(e);
    }

    private static boolean vecp(Object e) {
        return e instanceof List<?>;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> L(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    private static boolean truthy(Object x) {
        return Clj.truthy(x);
    }

    private static boolean isColl(Object x) {
        return x instanceof Collection<?> || x instanceof Map<?, ?>;
    }

    /** clj 的 (seq coll)：map 的条目当成 [k v] 向量。 */
    private static Iterable<?> collSeq(Object x) {
        if (x instanceof Map<?, ?> m) {
            List<Object> out = new ArrayList<>();
            for (Map.Entry<?, ?> en : m.entrySet()) out.add(Arrays.asList(en.getKey(), en.getValue()));
            return out;
        }
        return (Collection<?>) x;
    }

    /** clj 的 (tree-seq coll? seq x)：先序。 */
    private static List<Object> treeSeq(Object x) {
        List<Object> out = new ArrayList<>();
        treeSeq(x, out);
        return out;
    }

    private static void treeSeq(Object x, List<Object> out) {
        out.add(x);
        if (isColl(x)) for (Object c : collSeq(x)) treeSeq(c, out);
    }

    // ── 作用域 ────────────────────────────────────────────

    private record Ent(String name, Kw kind) {
    }

    /** clj 的 st：{:scope [[名 类别] …] :anon 计数 :types {名 静态类型}}（不可变，改动返回新的）。 */
    private record St(List<Ent> scope, long anon, Map<String, Object> types) {
        static final St EMPTY = new St(List.of(), 0, new HashMap<>());

        St conj(String nm, Kw k) {
            List<Ent> s = new ArrayList<>(scope);
            s.add(new Ent(nm, k));
            return new St(s, anon, types);
        }

        St into(List<Ent> more) {
            List<Ent> s = new ArrayList<>(scope);
            s.addAll(more);
            return new St(s, anon, types);
        }

        St withScope(List<Ent> s) {
            return new St(s, anon, types);
        }

        St withAnon(long a) {
            return new St(scope, a, types);
        }

        St withType(String nm, Object t) {
            Map<String, Object> m = new HashMap<>(types);
            m.put(nm, t);
            return new St(scope, anon, m);
        }

        Set<Object> names() {
            Set<Object> s = new LinkedHashSet<>();
            for (Ent e : scope) s.add(e.name());
            return s;
        }
    }

    private record R2(Object v, St st) {
    }

    private record R3(Object v, St st, Set<Object> rels) {
    }

    private static Kw kind(Object v, St st) {
        if (st == null) return null;
        for (Ent e : st.scope()) if (Objects.equals(e.name(), v) && e.kind() != null) return e.kind();
        return null;
    }

    private static St declareVar(Object v, Kw k, Object loc, St st) {
        if (kind(v, st) != null) throw fail("variable `" + Clj.str(v) + "` already declared", loc, "SyntaxError", "VariableAlreadyBound");
        return st.conj((String) v, k);
    }

    private static R2 anon(St st) {
        String nm = "  anon" + st.anon();
        return new R2(nm, st.withAnon(st.anon() + 1).conj(nm, ANON));
    }

    // ── 表达式 ────────────────────────────────────────────

    static boolean isAggregate(Object e) {
        return e == COUNT_STAR || (vecp(e) && Clj.first(e) == COUNT_STAR)
                || (vecp(e) && Clj.first(e) == CALL && n(e, 1) instanceof String s && AGGS.contains(s));
    }

    static Object children(Object e) {
        if (!vecp(e)) return vec();
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "has-labels", "prop", "is-null":
                return vec(n(e, 1));
            case "call":
                return L(n(e, 3));
            case "list":
                return L(n(e, 1));
            case "map": {
                List<Object> out = new ArrayList<>();
                for (Object kv : L(n(e, 1))) out.add(n(kv, 1));
                return out;
            }
            case "op":
                return vec(n(e, 2), n(e, 3));
            case "unop":
                return vec(n(e, 2));
            case "index":
                return vec(n(e, 1), n(e, 2));
            case "slice": {
                List<Object> out = vec(n(e, 1));
                if (n(e, 2) != null) out.add(n(e, 2));
                if (n(e, 3) != null) out.add(n(e, 3));
                return out;
            }
            case "case": {
                Object subj = n(e, 1), alts = n(e, 2), els = n(e, 3);
                List<Object> out = subj != null ? vec(subj) : vec();
                for (Object a : L(alts)) out.addAll(L(a));
                if (truthy(els)) out.add(els);
                return out;
            }
            case "list-comp":
                return nonNil(n(e, 2), n(e, 3), n(e, 4));
            case "quant":
                return nonNil(n(e, 3), n(e, 4));
            case "reduce":
                return vec(n(e, 2), n(e, 4), n(e, 5));
            case "map-proj": {
                List<Object> out = new ArrayList<>();
                for (Object it : L(n(e, 2))) if (Clj.first(it) == ENTRY) {
                    Object x = n(it, 2);
                    if (x != null) out.add(x);
                }
                return out;
            }
            default:
                return vec();
        }
    }

    private static List<Object> nonNil(Object... xs) {
        List<Object> out = new ArrayList<>();
        for (Object x : xs) if (x != null) out.add(x);
        return out;
    }

    private static List<Object> kids(Object e) {
        return L(children(e));
    }

    static boolean isContainsAggregate(Object e) {
        if (isAggregate(e)) return true;
        for (Object c : kids(e)) if (isContainsAggregate(c)) return true;
        return false;
    }

    private static void noAggregate(Object e, String msg, Object loc) {
        if (isContainsAggregate(e)) throw fail(msg, loc, "SyntaxError", "InvalidAggregation");
    }

    private static boolean hasRand(Object x) {
        if (!vecp(x)) return false;
        if (Clj.first(x) == CALL && "rand".equals(Clj.second(x))) return true;
        for (Object c : kids(x)) if (hasRand(c)) return true;
        return false;
    }

    private static void checkAggNesting(Object e, Object loc) {
        if (isAggregate(e)) {
            for (Object c : kids(e))
                if (isContainsAggregate(c)) throw fail("aggregate functions cannot be nested", loc, "SyntaxError", "NestedAggregation");
            for (Object c : kids(e))
                if (hasRand(c))
                    throw fail("aggregate functions cannot take non-constant expressions such as rand()", loc, "SyntaxError",
                            "NonConstantExpression");
            return;
        }
        Kw hd = h(e);
        if (vecp(e) && (hd == LIST_COMP || hd == QUANT || hd == REDUCE)) {
            List<Object> xs = hd == LIST_COMP ? Arrays.asList(n(e, 3), n(e, 4)) : hd == QUANT ? Arrays.asList(n(e, 4)) : Arrays.asList(n(e, 5));
            for (Object x : xs)
                if (isContainsAggregate(x))
                    throw fail("aggregate functions are not allowed inside list comprehensions, quantifiers or reduce", loc, "SyntaxError",
                            "InvalidAggregation");
        }
        for (Object c : kids(e)) checkAggNesting(c, loc);
    }

    /** 模式里的变量：规范化后是字符串，原始 AST 是 [:var 名 loc] 或 nil。 */
    private static Object vname(Object v) {
        if (v == null) return null;
        if (vecp(v)) return Clj.second(v);
        return v;
    }

    /** 规范化的模式（chain / named-chain）里的命名变量（不含匿名）与属性表达式引用的变量。 */
    private static Set<Object> patternExprVars(Object part) {
        Object c = Clj.first(part) == NAMED_CHAIN ? n(part, 2) : part;
        Object n0 = n(c, 1);
        List<Object> nodes = vec(n0);
        List<Object> rels = new ArrayList<>();
        for (Object st : L(n(c, 2))) {
            nodes.add(Clj.second(st));
            rels.add(Clj.first(st));
        }
        List<Object> all = new ArrayList<>(nodes);
        all.addAll(rels);
        Set<Object> out = new LinkedHashSet<>();
        for (Object x : all) {
            Object nm = vname(Clj.second(x));
            if (nm != null && !isHidden(nm)) out.add(nm);
        }
        for (Object x : all) {
            Object pe = n(x, 3);
            if (truthy(pe)) out.addAll(exprVarsSet(pe));
        }
        return out;
    }

    /** 规范化（或原始）表达式引用的（外层）变量名集合；列表推导 / 量词 / reduce 的局部变量、模式推导新引入的变量不算。 */
    static Object exprVars(Object e) {
        return exprVarsSet(e);
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> exprVarsSet(Object e) {
        if (!vecp(e)) return new LinkedHashSet<>();
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "var": {
                Set<Object> s = new LinkedHashSet<>();
                s.add(n(e, 1));
                return s;
            }
            case "list-comp": {
                Object v = n(e, 1);
                Set<Object> inner = exprVarsSet(n(e, 3));
                inner.addAll(exprVarsSet(n(e, 4)));
                inner.remove(v);
                Set<Object> out = exprVarsSet(n(e, 2));
                out.addAll(inner);
                return out;
            }
            case "quant": {
                Object v = n(e, 2);
                Set<Object> inner = exprVarsSet(n(e, 4));
                inner.remove(v);
                Set<Object> out = exprVarsSet(n(e, 3));
                out.addAll(inner);
                return out;
            }
            case "reduce": {
                Object acc = n(e, 1), v = n(e, 3);
                Set<Object> out = exprVarsSet(n(e, 2));
                out.addAll(exprVarsSet(n(e, 4)));
                Set<Object> x = exprVarsSet(n(e, 5));
                x.remove(acc);
                x.remove(v);
                out.addAll(x);
                return out;
            }
            case "map-proj": {
                Object v = n(e, 1);
                Set<Object> out = new LinkedHashSet<>();
                out.add(vecp(v) ? Clj.second(v) : v);
                for (Object it : L(n(e, 2))) {
                    Kw ih = h(it);
                    if (ih == ENTRY) out.addAll(exprVarsSet(n(it, 2)));
                    else if (ih == VAR_SEL) {
                        Object x = Clj.second(it);
                        out.add(vecp(x) ? Clj.second(x) : x);
                    }
                }
                return out;
            }
            case "pattern-pred":
                return patternExprVars(Clj.second(e));
            case "exists-sub": {
                Object x = n(e, 1);
                return x instanceof Map<?, ?> m ? new LinkedHashSet<>((Collection<Object>) Clj.get(m, OUTER, Set.of())) : new LinkedHashSet<>();
            }
            case "shortest-expr": {
                Set<Object> s = new LinkedHashSet<>();
                if (n(e, 2) instanceof String) {
                    s.add(n(e, 2));
                    s.add(n(e, 3));
                }
                return s;
            }
            case "pattern-comp": {
                if (Clj.count(e) == 6) {
                    // 原始 AST [:pattern-comp pv chain w x loc]：新变量不知道，按全部模式变量算（只用于列名等不精确场合）
                    Set<Object> s = patternExprVars(n(e, 2));
                    s.addAll(exprVarsSet(n(e, 3)));
                    s.addAll(exprVarsSet(n(e, 4)));
                    return s;
                }
                Set<Object> s = patternExprVars(n(e, 1));
                s.addAll(exprVarsSet(n(e, 2)));
                s.addAll(exprVarsSet(n(e, 3)));
                s.removeAll(L(n(e, 4)));
                return s;
            }
            default: {
                Set<Object> s = new LinkedHashSet<>();
                for (Object k : kids(e)) s.addAll(exprVarsSet(k));
                return s;
            }
        }
    }

    /** 列表推导 / 量词 / reduce 的局部变量（可以遮住外层同名变量）。 */
    private static St addLocal(St st, Object v) {
        List<Ent> sc = new ArrayList<>();
        for (Ent e : st.scope()) if (!Objects.equals(v, e.name())) sc.add(e);
        sc.add(new Ent((String) v, VALUE));
        return st.withScope(sc);
    }

    private static final AtomicLong PATTERN_ANON = new AtomicLong();

    /** 模式表达式的匿名变量另起一段名字，不与外层 MATCH 的匿名变量撞名（子流水线按名字判断哪些已绑定）。 */
    private static St patternSt(St st) {
        return st.withAnon(1000 * PATTERN_ANON.incrementAndGet());
    }

    /** 模式谓词 / 模式推导的模式 ⇒ [规范化模式 作用域' 新变量]。 */
    private static Object[] checkPattern(Object pv, Object chain, St st) {
        R3 r = matchPart(vec(PART, null, chain), patternSt(st), new LinkedHashSet<>());
        Object c2 = r.v();
        St st2 = r.st();
        Set<Object> old = st.names();
        List<Object> nw = new ArrayList<>();
        for (Ent e : st2.scope()) if (!old.contains(e.name()) && !isHidden(e.name())) nw.add(e.name());
        if (pv != null) {
            Object pn = n(pv, 1), pl = n(pv, 2);
            nw.add(pn);
            return new Object[] {vec(NAMED_CHAIN, pn, c2), declareVar(pn, PATH, pl, st2), nw};
        }
        return new Object[] {c2, st2, nw};
    }

    private static final Map<String, Set<Kw>> BAD_ARG_KINDS = Map.of("type", Set.of(NODE, PATH, REL_LIST), "length", Set.of(NODE, REL),
            "size", Set.of(NODE, REL, PATH), "nodes", Set.of(NODE, REL), "relationships", Set.of(NODE, REL), "labels", Set.of(REL, PATH),
            "startnode", Set.of(NODE, PATH), "endnode", Set.of(NODE, PATH));

    private static void checkExpr(Object e, St st) {
        if (!vecp(e)) return;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "list-comp": {
                Object v = n(e, 1);
                St st2 = addLocal(st, v);
                checkExpr(n(e, 2), st);
                checkExpr(n(e, 3), st2);
                checkExpr(n(e, 4), st2);
                return;
            }
            case "quant":
                checkExpr(n(e, 3), st);
                checkExpr(n(e, 4), addLocal(st, n(e, 2)));
                return;
            case "reduce":
                checkExpr(n(e, 2), st);
                checkExpr(n(e, 4), st);
                checkExpr(n(e, 5), addLocal(addLocal(st, n(e, 1)), n(e, 3)));
                return;
            case "map-proj":
                checkExpr(n(e, 1), st);
                for (Object it : L(n(e, 2))) {
                    Kw ih = h(it);
                    if (ih == ENTRY) checkExpr(n(it, 2), st);
                    else if (ih == VAR_SEL) checkExpr(Clj.second(it), st);
                }
                return;
            case "pattern-pred": {
                Object loc = n(e, 2);
                List<Object> nw = L(checkPattern(null, n(e, 1), st)[2]);
                if (!nw.isEmpty())
                    throw fail("pattern predicates cannot introduce new variables (`" + Clj.str(nw.getFirst())
                            + "`); use MATCH or a pattern comprehension", loc, "SyntaxError", "UndefinedVariable");
                return;
            }
            case "pattern-comp": {
                St st2 = (St) checkPattern(n(e, 1), n(e, 2), st)[1];
                checkExpr(n(e, 3), st2);
                checkExpr(n(e, 4), st2);
                return;
            }
            case "var":
                if (kind(n(e, 1), st) == null)
                    throw fail("variable `" + Clj.str(n(e, 1)) + "` not defined", n(e, 2), "SyntaxError", "UndefinedVariable");
                return;
            case "param", "lit", "count-star":
                return;
            case "exists-sub":
                analyzeSub(n(e, 1), st, n(e, 2));
                return;
            case "shortest-expr": {
                // [_ _ [_ [_ a la pa] [[[_ rv _ rp] [_ b lb pb]]]] loc]
                Object chain = n(e, 2), loc = n(e, 3);
                Object n0 = n(chain, 1);
                Object step = Clj.first(n(chain, 2));
                Object rel = Clj.first(step), n1 = Clj.second(step);
                Object[][] ends = {{n(n0, 1), n(n0, 2), n(n0, 3)}, {n(n1, 1), n(n1, 2), n(n1, 3)}};
                for (Object[] end : ends) {
                    Object v = end[0];
                    Kw k = v == null ? null : kind(Clj.second(v), st);
                    if (!(v != null && (k == NODE || k == VALUE)))
                        throw fail("shortestPath(…) as an expression needs both end nodes to be bound variables", loc, "SyntaxError",
                                "InvalidArgumentType");
                    if (!L(end[1]).isEmpty() || truthy(end[2]))
                        throw fail("shortestPath(…) as an expression cannot add labels or properties to the end nodes", loc, "SyntaxError",
                                "InvalidArgumentType");
                }
                if (truthy(n(rel, 1)) || truthy(n(rel, 3)))
                    throw fail("shortestPath(…) as an expression cannot bind or filter the relationship", loc, "SyntaxError",
                            "InvalidArgumentType");
                return;
            }
            case "prop": {
                Object b = n(e, 1);
                if (vecp(b) && Clj.first(b) == VAR && kind(Clj.second(b), st) == PATH)
                    throw fail("properties cannot be read from the path `" + Clj.str(Clj.second(b)) + "`", n(b, 2), "SyntaxError",
                            "InvalidArgumentType");
                checkExpr(b, st);
                return;
            }
            case "call": {
                Object nm = n(e, 1), d = n(e, 2), loc = n(e, 4);
                List<Object> args = L(n(e, 3));
                Object ar = FUNS.get(nm);
                if (ar == null) ar = TEMPORAL_FUNS.get(nm);
                if (ar == null) ar = SPATIAL_FUNS.get(nm);
                if (ar == null && nm instanceof String s) ar = Clj.get(Procs.lookupFn(s), Procs.ARITY);
                Object a0 = args.isEmpty() ? null : args.getFirst();
                Kw k0 = vecp(a0) && Clj.first(a0) == VAR ? kind(Clj.second(a0), st) : null;
                if (ar == null) throw fail("unknown function `" + Clj.str(nm) + "`", loc, "SyntaxError", "UnknownFunction");
                else if (ar == ANY) {
                    if (args.isEmpty()) throw fail("`" + Clj.str(nm) + "` needs arguments", loc, "SyntaxError", "InvalidArgumentType");
                } else if (!((Set<?>) ar).contains((long) args.size()))
                    throw fail("wrong number of arguments to `" + Clj.str(nm) + "`", loc, "SyntaxError", "InvalidNumberOfArguments");
                // 已知类别的变量传错函数（type(节点)、length(关系)、size(路径) …）⇒ 编译期错误
                Set<Kw> bad = BAD_ARG_KINDS.get(nm);
                if (bad != null && k0 != null && bad.contains(k0))
                    throw fail("invalid argument type for " + Clj.str(nm) + "(): " + k0.name, loc, "SyntaxError", "InvalidArgumentType");
                if (truthy(d) && !AGGS.contains(nm))
                    throw fail("DISTINCT is only allowed in aggregate functions, not `" + Clj.str(nm) + "`", loc, "SyntaxError",
                            "InvalidAggregation");
                if ("score".equals(nm) && !(args.size() == 1 && Clj.first(args.getFirst()) == VAR))
                    throw fail("score() takes a node variable", loc, "SyntaxError", "InvalidArgumentType");
                if (Set.of("fts.phrase", "fts.near", "fts.wildcard", "fts.fuzzy").contains(nm)) {
                    Object a = a0;
                    if (!(vecp(a) && Clj.first(a) == PROP && Clj.first(Clj.second(a)) == VAR && "text".equals(n(a, 2))))
                        throw fail(Clj.str(nm) + "() takes the node text as its first argument, e.g. " + Clj.str(nm) + "(n.text, ...)", loc,
                                "SyntaxError", "InvalidArgumentType");
                }
                if (Set.of("fts.fields", "search.hybrid").contains(nm) && Clj.first(a0) != VAR)
                    throw fail(Clj.str(nm) + "() takes a node variable as its first argument", loc, "SyntaxError", "InvalidArgumentType");
                if ("exists".equals(nm) && !(Clj.first(a0) == PROP || Clj.first(a0) == PATTERN_PRED))
                    throw fail("exists() takes a property (n.prop) or a pattern", loc, "SyntaxError", "InvalidArgumentType");
                for (Object a : args) checkExpr(a, st);
                return;
            }
            default:
                for (Object k : kids(e)) checkExpr(k, st);
        }
    }

    /** EXISTS { … } 的子查询：在外层作用域里分析 ⇒ {:query 规范化子句 :outer 引用到的外层变量}。 */
    private static Object analyzeSub(Object clauses, St st, Object loc) {
        for (Object c : L(clauses))
            if (isWrite(c)) throw fail("EXISTS subqueries cannot contain updating clauses", loc, "SyntaxError", "InvalidClauseComposition");
        St st0 = new St(st.scope(), 100000 + st.anon(), new HashMap<>());
        List<Object> out = new ArrayList<>();
        St s = st0;
        for (Object c : L(clauses)) {
            R2 r = clause(c, s);
            out.add(r.v());
            s = r.st();
        }
        Set<Object> outer = st.names();
        Set<Object> used = new LinkedHashSet<>();
        for (Object x : treeSeq(out)) if (x instanceof String && outer.contains(x)) used.add(x);
        return Clj.map(QUERY, out, OUTER, used);
    }

    /** clj 的 *st* 当前绑定（只给 SKIP / LIMIT 的规范化用，见类注释）。 */
    private static final ThreadLocal<St> AMBIENT = new ThreadLocal<>();

    private static St ambient() {
        St s = AMBIENT.get();
        return s == null ? St.EMPTY : s;
    }

    /** 规范化表达式（clj 的 norm，作用域取当前绑定）。 */
    static Object norm(Object e) {
        return norm(e, ambient());
    }

    private static Object normOrNil(Object e, St st) {
        return e == null ? null : norm(e, st);
    }

    private static Object norm(Object e, St st) {
        if (!vecp(e)) return e;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "exists-sub": {
                St prev = AMBIENT.get();
                AMBIENT.set(st);
                try {
                    return vec(EXISTS_SUB, analyzeSub(n(e, 1), st, n(e, 2)));
                } finally {
                    AMBIENT.set(prev);
                }
            }
            case "shortest-expr": {
                Object kind = n(e, 1), chain = n(e, 2);
                Object a = n(n(chain, 1), 1);
                Object step = Clj.first(n(chain, 2));
                Object rel = Clj.first(step);
                Object types = n(rel, 2), dir = n(rel, 4), vl = n(rel, 5), rl = n(rel, 6);
                Object b = n(Clj.second(step), 1);
                return vec(SHORTEST_EXPR, kind, Clj.second(a), Clj.second(b), types, dir, Clj.subvec(varlen(vl, SHORTEST, rl), 0, 2));
            }
            case "list-comp": {
                Object v = n(e, 1);
                St st2 = addLocal(st, v);
                Object pn = normOrNil(n(e, 3), st2);
                Object xn = normOrNil(n(e, 4), st2);
                return vec(LIST_COMP, v, norm(n(e, 2), st), pn, xn);
            }
            case "quant": {
                Object v = n(e, 2);
                return vec(QUANT, n(e, 1), v, norm(n(e, 3), st), normOrNil(n(e, 4), addLocal(st, v)));
            }
            case "reduce": {
                Object acc = n(e, 1), v = n(e, 3);
                return vec(REDUCE, acc, norm(n(e, 2), st), v, norm(n(e, 4), st), norm(n(e, 5), addLocal(addLocal(st, acc), v)));
            }
            case "map-proj": {
                List<Object> items = new ArrayList<>();
                for (Object it : L(n(e, 2))) {
                    Kw ih = h(it);
                    if (ih == ENTRY) items.add(vec(ENTRY, Clj.second(it), norm(n(it, 2), st)));
                    else if (ih == VAR_SEL) items.add(vec(VAR_SEL, Clj.second(Clj.second(it))));
                    else items.add(it);
                }
                return vec(MAP_PROJ, Clj.second(n(e, 1)), items);
            }
            case "pattern-pred":
                return vec(PATTERN_PRED, checkPattern(null, Clj.second(e), st)[0]);
            case "pattern-comp": {
                Object[] r = checkPattern(n(e, 1), n(e, 2), st);
                St st2 = (St) r[1];
                return vec(PATTERN_COMP, r[0], normOrNil(n(e, 3), st2), norm(n(e, 4), st2), r[2]);
            }
            case "var":
                return vec(VAR, n(e, 1));
            case "param":
                return vec(PARAM, n(e, 1));
            case "lit":
                return e;
            case "has-labels":
                return vec(HAS_LABELS, norm(n(e, 1), st), n(e, 2));
            case "prop":
                return vec(PROP, norm(n(e, 1), st), n(e, 2));
            case "count-star":
                return COUNT_STAR;
            case "call": {
                List<Object> args = new ArrayList<>();
                for (Object a : L(n(e, 3))) args.add(norm(a, st));
                return vec(CALL, n(e, 1), n(e, 2), args);
            }
            case "list": {
                List<Object> xs = new ArrayList<>();
                for (Object a : L(n(e, 1))) xs.add(norm(a, st));
                return vec(LIST, xs);
            }
            case "map": {
                List<Object> xs = new ArrayList<>();
                for (Object kv : L(n(e, 1))) xs.add(vec(Clj.first(kv), norm(Clj.second(kv), st)));
                return vec(MAP, xs);
            }
            case "op":
                return vec(OP, n(e, 1), norm(n(e, 2), st), norm(n(e, 3), st));
            case "unop":
                return vec(UNOP, n(e, 1), norm(n(e, 2), st));
            case "is-null":
                return vec(IS_NULL, norm(n(e, 1), st), n(e, 2));
            case "index":
                return vec(INDEX, norm(n(e, 1), st), norm(n(e, 2), st));
            case "slice":
                return vec(SLICE, norm(n(e, 1), st), normOrNil(n(e, 2), st), normOrNil(n(e, 3), st));
            case "case": {
                List<Object> alts = new ArrayList<>();
                for (Object a : L(n(e, 2))) alts.add(vec(norm(Clj.first(a), st), norm(Clj.second(a), st)));
                return vec(CASE, normOrNil(n(e, 1), st), alts, normOrNil(n(e, 3), st));
            }
            default:
                throw new IllegalArgumentException("No matching clause: " + Clj.first(e));
        }
    }

    /**
     * 模式表达式只能当谓词用（openCypher TCK：WHERE、AND / OR / XOR / NOT、CASE WHEN、推导与量词的条件、exists()）；
     * 作返回值、SET 的右边、size() 的参数等 ⇒ 语法错误（要列表用模式推导）。
     */
    private static void checkPatternPositions(Object e, boolean bool) {
        if (!vecp(e)) return;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "pattern-pred":
                if (!bool)
                    throw fail("pattern expressions can only be used as predicates; use a pattern comprehension [(a)-->(b) | b] to get a list",
                            n(e, 2), "SyntaxError", "UnexpectedSyntax");
                return;
            case "op": {
                Object op = n(e, 1);
                boolean bl = op == AND || op == OR || op == XOR;
                checkPatternPositions(n(e, 2), bl);
                checkPatternPositions(n(e, 3), bl);
                return;
            }
            case "unop":
                checkPatternPositions(n(e, 2), n(e, 1) == NOT);
                return;
            case "case": {
                Object subj = n(e, 1);
                checkPatternPositions(subj, false);
                for (Object a : L(n(e, 2))) {
                    checkPatternPositions(Clj.first(a), subj == null);
                    checkPatternPositions(Clj.second(a), false);
                }
                checkPatternPositions(n(e, 3), false);
                return;
            }
            case "list-comp":
                checkPatternPositions(n(e, 2), false);
                checkPatternPositions(n(e, 3), true);
                checkPatternPositions(n(e, 4), false);
                return;
            case "quant":
                checkPatternPositions(n(e, 3), false);
                checkPatternPositions(n(e, 4), true);
                return;
            case "pattern-comp":
                checkPatternPositions(n(e, 3), true);
                checkPatternPositions(n(e, 4), false);
                return;
            case "exists-sub", "shortest-expr":
                return; // 子查询自己分析
            case "call": {
                boolean ex = "exists".equals(n(e, 1));
                for (Object a : L(n(e, 3))) checkPatternPositions(a, ex);
                return;
            }
            default: {
                List<Object> l = L(e);
                for (int i = 1; i < l.size(); i++) if (vecp(l.get(i))) checkPatternPositions(l.get(i), false);
            }
        }
    }

    // ── 静态类型（D-37）：能确定时在编译期报类型错误；判断不了的一律 :any ──────────

    private static final Map<String, Object> FN_TYPES = new HashMap<>();

    static {
        Object[] f = {"size", INT, "length", INT, "count", INT, "tostring", STRING, "toupper", STRING, "tolower", STRING, "trim", STRING,
                "ltrim", STRING, "rtrim", STRING, "substring", STRING, "replace", STRING, "left", STRING, "right", STRING, "type", STRING,
                "tointeger", INT, "tofloat", FLOAT, "toboolean", BOOLEAN, "sqrt", FLOAT, "exp", FLOAT, "log", FLOAT, "log10", FLOAT,
                "sin", FLOAT, "cos", FLOAT, "tan", FLOAT, "cot", FLOAT, "asin", FLOAT, "acos", FLOAT, "atan", FLOAT, "atan2", FLOAT,
                "degrees", FLOAT, "radians", FLOAT, "haversin", FLOAT, "isnan", BOOLEAN, "e", FLOAT, "pi", FLOAT, "rand", FLOAT,
                "exists", BOOLEAN, "properties", MAP, "split", List.of(LIST, STRING), "keys", List.of(LIST, STRING), "labels",
                List.of(LIST, STRING), "range", List.of(LIST, INT), "nodes", List.of(LIST, NODE), "relationships", List.of(LIST, REL),
                "collect", List.of(LIST, ANY)};
        for (int i = 0; i < f.length; i += 2) FN_TYPES.put((String) f[i], f[i + 1]);
    }

    private static boolean isListT(Object t) {
        return vecp(t) && Clj.first(t) == LIST;
    }

    private static boolean isNumT(Object t) {
        return t == INT || t == FLOAT;
    }

    /** 规范化表达式的静态类型：:int :float :string :boolean :null :map :node :rel :path [:list 元素类型] :any。 */
    private static Object stype(Object e, St st, Map<Object, Object> env) {
        if (!vecp(e)) return ANY;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "lit": {
                Object v = Clj.second(e);
                if (v == null) return NULL;
                if (v instanceof Boolean) return BOOLEAN;
                if (Clj.isInteger(v)) return INT;
                if (v instanceof Double || v instanceof Float) return FLOAT;
                if (v instanceof String) return STRING;
                return ANY;
            }
            case "list": {
                Set<Object> ts = new LinkedHashSet<>();
                for (Object x : L(Clj.second(e))) ts.add(stype(x, st, env));
                return List.of(LIST, ts.size() == 1 ? ts.iterator().next() : ANY);
            }
            case "map":
                return MAP;
            case "var": {
                Object v = Clj.second(e);
                Object t = env.get(v);
                if (truthy(t)) return t;
                t = st.types().get(v);
                if (truthy(t)) return t;
                Kw k = kind(v, st);
                if (k == NODE) return NODE;
                if (k == REL) return REL;
                if (k == PATH) return PATH;
                if (k == REL_LIST) return List.of(LIST, REL);
                if (k == LIST) return List.of(LIST, ANY);
                return ANY;
            }
            case "op": {
                Object op = n(e, 1);
                Object ta = stype(n(e, 2), st, env), tb = stype(n(e, 3), st, env);
                if (op == PLUS) {
                    if (ta == INT && tb == INT) return INT;
                    if (isNumT(ta) && isNumT(tb)) return FLOAT;
                    if (ta == STRING || tb == STRING) return isListT(ta) || isListT(tb) ? ANY : STRING;
                    if (isListT(ta) || isListT(tb)) return List.of(LIST, ANY);
                    return ANY;
                }
                if (op == MINUS || op == MUL || op == DIV || op == MOD) {
                    if (ta == INT && tb == INT) return INT;
                    if (isNumT(ta) && isNumT(tb)) return FLOAT;
                    return ANY;
                }
                if (op == POW) return isNumT(ta) && isNumT(tb) ? FLOAT : ANY;
                if (op == EQ || op == NE || op == LT || op == LE || op == GT || op == GE || op == AND || op == OR || op == XOR || op == IN
                        || op == STARTS_WITH || op == ENDS_WITH || op == CONTAINS || op == REGEX)
                    return BOOLEAN;
                return ANY;
            }
            case "unop": {
                Object op = n(e, 1);
                if (op == NOT) return BOOLEAN;
                if (op == MINUS) {
                    Object t = stype(n(e, 2), st, env);
                    return isNumT(t) ? t : ANY;
                }
                return ANY;
            }
            case "is-null", "has-labels", "exists-sub", "pattern-pred", "quant":
                return BOOLEAN;
            case "count-star":
                return INT;
            case "shortest-expr":
                return n(e, 1) == SINGLE ? PATH : List.of(LIST, PATH);
            case "call": {
                Object t = FN_TYPES.get(n(e, 1));
                return t == null ? ANY : t;
            }
            case "index": {
                Object t = stype(n(e, 1), st, env);
                return isListT(t) ? Clj.second(t) : ANY;
            }
            default:
                return ANY;
        }
    }

    private static final Set<Object> SCALAR_T = Set.of(INT, FLOAT, STRING, BOOLEAN);
    private static final Set<Object> ARITH_BAD_T = Set.of(STRING, BOOLEAN, MAP, NODE, REL, PATH);
    private static final Set<Object> IN_BAD_T = Set.of(INT, FLOAT, STRING, BOOLEAN, MAP, NODE, REL, PATH);
    private static final Set<Object> PLUS_BAD_T = Set.of(BOOLEAN, MAP, NODE, REL, PATH);

    private static Map<Object, Object> assocEnv(Map<Object, Object> env, Object... kvs) {
        Map<Object, Object> m = new HashMap<>(env);
        for (int i = 0; i + 1 < kvs.length; i += 2) m.put(kvs[i], kvs[i + 1]);
        return m;
    }

    private static boolean arithBad(Object t) {
        return ARITH_BAD_T.contains(t) || isListT(t);
    }

    /** 编译期类型检查：读非 map / 非图元素的属性、properties() 的参数、IN 的右边、算术运算的操作数。 */
    private static void checkTypes(Object e, St st, Map<Object, Object> env) {
        if (!vecp(e)) return;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "prop": {
                Object t = stype(n(e, 1), st, env);
                if (SCALAR_T.contains(t) || isListT(t))
                    throw fail("type mismatch: cannot read property `" + Clj.str(n(e, 2)) + "` of " + (isListT(t) ? "list" : ((Kw) t).name),
                            null, "TypeError", "InvalidArgumentType");
                checkTypes(n(e, 1), st, env);
                return;
            }
            case "call": {
                if ("properties".equals(n(e, 1))) {
                    Object t = stype(Clj.first(n(e, 3)), st, env);
                    if (SCALAR_T.contains(t) || isListT(t))
                        throw fail("invalid argument type for properties()", null, "SyntaxError", "InvalidArgumentType");
                }
                for (Object a : L(n(e, 3))) checkTypes(a, st, env);
                return;
            }
            case "op": {
                Object op = n(e, 1), a = n(e, 2), b = n(e, 3);
                Object ta = stype(a, st, env), tb = stype(b, st, env);
                if (op == IN) {
                    if (IN_BAD_T.contains(tb)) throw fail("IN needs a list on the right", null, "SyntaxError", "InvalidArgumentType");
                } else if (op == MINUS || op == MUL || op == DIV || op == MOD || op == POW) {
                    if (arithBad(ta) || arithBad(tb))
                        throw fail("type mismatch for operator " + ((Kw) op).name, null, "SyntaxError", "InvalidArgumentType");
                } else if (op == PLUS) {
                    if (!(isListT(ta) || isListT(tb) || ta == STRING || tb == STRING) && (PLUS_BAD_T.contains(ta) || PLUS_BAD_T.contains(tb)))
                        throw fail("type mismatch for operator +", null, "SyntaxError", "InvalidArgumentType");
                }
                checkTypes(a, st, env);
                checkTypes(b, st, env);
                return;
            }
            case "quant": {
                Object v = n(e, 2), src = n(e, 3), pred = n(e, 4);
                Object t = stype(src, st, env);
                checkTypes(src, st, env);
                checkTypes(pred, st, assocEnv(env, v, isListT(t) ? Clj.second(t) : ANY));
                return;
            }
            case "list-comp": {
                Object v = n(e, 1), src = n(e, 2);
                Object t = stype(src, st, env);
                Map<Object, Object> env2 = assocEnv(env, v, isListT(t) ? Clj.second(t) : ANY);
                checkTypes(src, st, env);
                checkTypes(n(e, 3), st, env2);
                checkTypes(n(e, 4), st, env2);
                return;
            }
            case "reduce": {
                Object acc = n(e, 1), v = n(e, 3), src = n(e, 4);
                Object t = stype(src, st, env);
                checkTypes(n(e, 2), st, env);
                checkTypes(src, st, env);
                checkTypes(n(e, 5), st, assocEnv(env, acc, ANY, v, isListT(t) ? Clj.second(t) : ANY));
                return;
            }
            default:
                for (Object k : kids(e)) checkTypes(k, st, env);
        }
    }

    private static Object typed(Object e, St st) {
        checkTypes(e, st, new HashMap<>());
        return e;
    }

    private static Object expr(Object e, St st) {
        checkExpr(e, st);
        checkBooleanLiterals(e);
        checkPatternPositions(e, false);
        return typed(norm(e, st), st);
    }

    private static boolean badBoolLit(Object x) {
        if (!vecp(x)) return false;
        Kw hd = h(x);
        if (hd == LIT && Clj.second(x) != null && !(Clj.second(x) instanceof Boolean)) return true;
        return hd == LIST || hd == MAP;
    }

    /** AND / OR / XOR / NOT 的操作数是非布尔的字面量（数、字符串、列表、map）⇒ 编译期类型错误。 */
    private static void checkBooleanLiterals(Object e) {
        if (!vecp(e)) return;
        Kw hd = h(e);
        if (hd == OP) {
            Object op = n(e, 1);
            if ((op == AND || op == OR || op == XOR) && (badBoolLit(n(e, 2)) || badBoolLit(n(e, 3))))
                throw fail(((Kw) op).name.toUpperCase(Locale.ROOT) + " expects boolean operands", null, "SyntaxError", "InvalidArgumentType");
        } else if (hd == UNOP) {
            if (n(e, 1) == NOT && badBoolLit(n(e, 2))) throw fail("NOT expects a boolean operand", null, "SyntaxError", "InvalidArgumentType");
        }
        for (Object k : kids(e)) checkBooleanLiterals(k);
    }

    private static Object exprWhere(Object e, St st) {
        if (vecp(e) && Clj.first(e) == VAR) {
            Kw k = kind(Clj.second(e), st);
            if (k == NODE || k == REL || k == PATH || k == REL_LIST)
                throw fail("WHERE expects a boolean, `" + Clj.str(Clj.second(e)) + "` is a " + k.name, n(e, 2), "SyntaxError",
                        "InvalidArgumentType");
        }
        checkExpr(e, st);
        checkBooleanLiterals(e);
        checkPatternPositions(e, true);
        return typed(norm(e, st), st);
    }

    private static final Object NONE = new Object() {
        @Override
        public String toString() {
            return ":im.ttalk.bitcask.cypher.sema/none";
        }
    };

    private static Object exprQuiet(Object e, St st) {
        try {
            return expr(e, st);
        } catch (CypherError x) {
            if (x.isParseError()) return NONE;
            throw x;
        }
    }

    // ── 列名：表达式的规范文本 ────────────────────────────

    private static String opText(Object op) {
        Kw k = (Kw) op;
        return switch (k.name) {
            case "and" -> "AND";
            case "or" -> "OR";
            case "xor" -> "XOR";
            case "in" -> "IN";
            case "starts-with" -> "STARTS WITH";
            case "ends-with" -> "ENDS WITH";
            case "contains" -> "CONTAINS";
            case "at-at" -> "@@";
            case "pow" -> "^";
            case "regex" -> "=~";
            default -> k.name;
        };
    }

    private static String labelTxt(Object l) {
        if (l instanceof String s) return s;
        Object f = Clj.first(l);
        if (f == ANY) return "(" + L(Clj.second(l)).stream().map(Sema::labelTxt).collect(Collectors.joining("|")) + ")";
        if (f == ALL) return "(" + L(Clj.second(l)).stream().map(Sema::labelTxt).collect(Collectors.joining("&")) + ")";
        if (f == NOT) return "!" + labelTxt(Clj.second(l));
        return "%";
    }

    private static String litText(Object v) {
        if (v == null) return "null";
        if (Boolean.TRUE.equals(v)) return "true";
        if (Boolean.FALSE.equals(v)) return "false";
        if (v instanceof String s) return "'" + s + "'";
        return Clj.str(v);
    }

    private static String visibleName(Object v) {
        Object nm = vname(v);
        return nm != null && !isHidden(nm) ? Clj.str(nm) : "";
    }

    /** 模式的规范文本（列名用）。 */
    private static String patternTxt(Object part) {
        Object pv, c;
        if (Clj.first(part) == NAMED_CHAIN) {
            pv = Clj.second(part);
            c = n(part, 2);
        } else {
            pv = null;
            c = part;
        }
        StringBuilder sb = new StringBuilder();
        if (truthy(pv)) sb.append(Clj.str(vname(pv))).append(" = ");
        sb.append(nodeTxt(n(c, 1)));
        for (Object st : L(n(c, 2))) sb.append(relTxt(Clj.first(st))).append(nodeTxt(Clj.second(st)));
        return sb.toString();
    }

    private static String nodeTxt(Object nd) {
        StringBuilder sb = new StringBuilder("(");
        sb.append(visibleName(n(nd, 1)));
        for (Object l : L(n(nd, 2))) sb.append(":").append(labelTxt(l));
        Object pe = n(nd, 3);
        if (truthy(pe)) sb.append(" ").append(txt(pe));
        return sb.append(")").toString();
    }

    private static String relTxt(Object r) {
        Object types = n(r, 2), pe = n(r, 3), dir = n(r, 4), rng = n(r, 5);
        Object mn = Clj.first(rng), mx = Clj.second(rng);
        StringBuilder inner = new StringBuilder(visibleName(n(r, 1)));
        if (!L(types).isEmpty()) inner.append(":").append(L(types).stream().map(Clj::str).collect(Collectors.joining("|")));
        if (!(Clj.eq(1L, mn) && Clj.eq(1L, mx))) inner.append("*").append(Clj.str(mn)).append("..").append(Clj.str(mx));
        if (truthy(pe)) inner.append(" ").append(txt(pe));
        String body = inner.isEmpty() ? "--" : "-[" + inner + "]-";
        if (dir == OUT) return body + ">";
        if (dir == IN) return "<" + body;
        return body;
    }

    private static String joinTxt(Object xs, String sep) {
        return L(xs).stream().map(Sema::txt).collect(Collectors.joining(sep));
    }

    static String txt(Object e) {
        if (e == COUNT_STAR) return "count(*)";
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        switch (c) {
            case "var":
                return Clj.str(n(e, 1));
            case "param":
                return "$" + Clj.str(n(e, 1));
            case "lit":
                return litText(n(e, 1));
            case "has-labels": {
                StringBuilder sb = new StringBuilder(txt(n(e, 1)));
                for (Object l : L(n(e, 2))) sb.append(":").append(labelTxt(l));
                return sb.toString();
            }
            case "index":
                return txt(n(e, 1)) + "[" + txt(n(e, 2)) + "]";
            case "slice":
                return txt(n(e, 1)) + "[" + (n(e, 2) == null ? "" : txt(n(e, 2))) + ".." + (n(e, 3) == null ? "" : txt(n(e, 3))) + "]";
            case "prop":
                return txt(n(e, 1)) + "." + Clj.str(n(e, 2));
            case "count-star":
                return "count(*)";
            case "call":
                return Clj.str(n(e, 1)) + "(" + (truthy(n(e, 2)) ? "DISTINCT " : "") + joinTxt(n(e, 3), ", ") + ")";
            case "list":
                return "[" + joinTxt(n(e, 1), ", ") + "]";
            case "map":
                return "{" + L(n(e, 1)).stream().map(kv -> Clj.str(Clj.first(kv)) + ": " + txt(Clj.second(kv))).collect(Collectors.joining(", "))
                        + "}";
            case "op":
                return txt(n(e, 2)) + " " + opText(n(e, 1)) + " " + txt(n(e, 3));
            case "unop":
                return n(e, 1) == NOT ? "NOT " + txt(n(e, 2)) : "-" + txt(n(e, 2));
            case "is-null":
                return txt(n(e, 1)) + (truthy(n(e, 2)) ? " IS NULL" : " IS NOT NULL");
            case "list-comp": {
                Object pred = n(e, 3), proj = n(e, 4);
                return "[" + Clj.str(n(e, 1)) + " IN " + txt(n(e, 2)) + (truthy(pred) ? " WHERE " + txt(pred) : "")
                        + (truthy(proj) ? " | " + txt(proj) : "") + "]";
            }
            case "quant": {
                Object pred = n(e, 4);
                return Clj.name(n(e, 1)) + "(" + Clj.str(n(e, 2)) + " IN " + txt(n(e, 3)) + (truthy(pred) ? " WHERE " + txt(pred) : "") + ")";
            }
            case "reduce":
                return "reduce(" + Clj.str(n(e, 1)) + " = " + txt(n(e, 2)) + ", " + Clj.str(n(e, 3)) + " IN " + txt(n(e, 4)) + " | "
                        + txt(n(e, 5)) + ")";
            case "map-proj": {
                Object v = n(e, 1);
                List<String> parts = new ArrayList<>();
                for (Object it : L(n(e, 2))) {
                    Kw ih = h(it);
                    String ihn = ih == null ? "" : ih.name;
                    parts.add(switch (ihn) {
                        case "prop-sel" -> "." + Clj.str(Clj.second(it));
                        case "all-props" -> ".*";
                        case "entry" -> Clj.str(Clj.second(it)) + ": " + txt(n(it, 2));
                        case "var-sel" -> {
                            Object x = Clj.second(it);
                            yield Clj.str(vecp(x) ? Clj.second(x) : x);
                        }
                        default -> throw new IllegalArgumentException("No matching clause: " + Clj.first(it));
                    });
                }
                return Clj.str(vecp(v) ? Clj.second(v) : v) + " {" + String.join(", ", parts) + "}";
            }
            case "pattern-pred":
                return patternTxt(Clj.second(e));
            case "exists-sub":
                return "EXISTS { … }";
            case "shortest-expr":
                return "shortestPath(…)";
            case "pattern-comp": {
                Object part, w, x;
                if (Clj.count(e) == 6) {
                    Object pv = n(e, 1), chain = n(e, 2);
                    part = truthy(pv) ? vec(NAMED_CHAIN, vname(pv), chain) : chain;
                    w = n(e, 3);
                    x = n(e, 4);
                } else {
                    part = n(e, 1);
                    w = n(e, 2);
                    x = n(e, 3);
                }
                return "[" + patternTxt(part) + (truthy(w) ? " WHERE " + txt(w) : "") + " | " + txt(x) + "]";
            }
            case "case": {
                Object subj = n(e, 1), els = n(e, 3);
                StringBuilder sb = new StringBuilder("CASE");
                if (truthy(subj)) sb.append(" ").append(txt(subj));
                for (Object a : L(n(e, 2))) sb.append(" WHEN ").append(txt(Clj.first(a))).append(" THEN ").append(txt(Clj.second(a)));
                if (truthy(els)) sb.append(" ELSE ").append(txt(els));
                return sb.append(" END").toString();
            }
            default:
                throw new IllegalArgumentException("No matching clause: " + Clj.first(e));
        }
    }

    // ── 模式 ──────────────────────────────────────────────

    private static Object propsExpr(Object pe, Object loc, St st) {
        if (pe == null) return null;
        if (Clj.first(pe) == PARAM) return expr(pe, st);
        noAggregate(pe, "aggregate functions are not allowed in patterns", loc);
        return expr(pe, st);
    }

    private static R2 nodePat(Object node, St st) {
        Object v = n(node, 1), labels = n(node, 2), props = n(node, 3), loc = n(node, 4);
        Object nm;
        if (v == null) {
            R2 a = anon(st);
            nm = a.v();
            st = a.st();
        } else {
            Object vn = n(v, 1), vl = n(v, 2);
            Kw k = kind(vn, st);
            if (k == null) {
                nm = vn;
                st = declareVar(vn, NODE, vl, st);
            } else if (k == NODE || k == VALUE) {
                // WITH / UNWIND 来的值：当作节点用，运行时不是节点就匹配不上
                nm = vn;
            } else if (k == SCALAR || k == LIST) {
                throw fail("variable `" + Clj.str(vn) + "` is bound to a value that is not a node (type conflict)", vl, "SyntaxError",
                        "VariableTypeConflict");
            } else {
                throw fail("variable `" + Clj.str(vn) + "` already declared as " + k.name, vl, "SyntaxError", "VariableTypeConflict");
            }
        }
        return new R2(vec(NODE, nm, labels, propsExpr(props, loc, st), loc), st);
    }

    private static List<Object> varlen(Object vl, Kw ctx, Object loc) {
        if (vl == null && ctx == SHORTEST) return vec(1L, UNBOUNDED);
        if (vl == null) return vec(1L, 1L);
        Object mn = Clj.first(vl), mx = Clj.second(vl);
        return vec(mn == null ? (Object) 1L : mn, mx == null ? (Object) UNBOUNDED : mx, true);
    }

    private static R3 relPat(Object rel, St st, Set<Object> rels, Kw ctx) {
        Object v = n(rel, 1), types = n(rel, 2), props = n(rel, 3), dir = n(rel, 4), vl = n(rel, 5), loc = n(rel, 6);
        List<Object> rng = varlen(vl, ctx, loc);
        Kw k = vl == null && ctx != SHORTEST ? REL : REL_LIST;
        Object nm;
        if (v == null) {
            R2 a = anon(st);
            nm = a.v();
            st = a.st();
        } else {
            Object vn = n(v, 1), vloc = n(v, 2);
            if (rels.contains(vn))
                throw fail("relationship variable `" + Clj.str(vn) + "` used twice in one MATCH", vloc, "SyntaxError",
                        "RelationshipUniquenessViolation");
            Kw kk = kind(vn, st);
            nm = vn;
            if (kk == null) st = declareVar(vn, k, vloc, st);
            else if (kk == VALUE) {
                if (ctx == CREATE) throw fail("variable `" + Clj.str(vn) + "` already declared", vloc, "SyntaxError", "VariableAlreadyBound");
            } else if (kk == SCALAR) {
                throw fail("variable `" + Clj.str(vn) + "` is bound to a value that is not a relationship (type conflict)", vloc,
                        "SyntaxError", "VariableTypeConflict");
            } else if (kk == LIST) {
                if (k != REL_LIST)
                    throw fail("variable `" + Clj.str(vn) + "` is a list, not a relationship (type conflict)", vloc, "SyntaxError",
                            "VariableTypeConflict");
            } else if (k == kk && ctx == MATCH) {
                // 已绑定的同类关系：照用
            } else if (k != kk) {
                throw fail("variable `" + Clj.str(vn) + "` already declared as " + kk.name + " (type conflict)", vloc, "SyntaxError",
                        "VariableTypeConflict");
            } else {
                throw fail("variable `" + Clj.str(vn) + "` already declared", vloc, "SyntaxError", "VariableAlreadyBound");
            }
        }
        Set<Object> rels2 = new LinkedHashSet<>(rels);
        rels2.add(nm);
        return new R3(vec(REL, nm, types, propsExpr(props, loc, st), dir, rng, loc), st, rels2);
    }

    /**
     * 量化路径 [:qpp 子模式 WHERE [mn mx] loc 子路径变量] ⇒ [[:qpp spec] st rels]。子模式的每一步可以是单条关系、变长关系或嵌套的
     * 量化路径；子模式里的变量（含子路径变量、嵌套的组变量）必须是新的：在 WHERE 里是单个值，在外面是组变量（每次迭代一个值的列表）；
     * 另记一个隐藏变量放这一段走过的全部关系（拼路径用）。
     */
    private static R3 qppPat(Object q, St st, Set<Object> rels) {
        Object sub = n(q, 1), w = n(q, 2), mnmx = n(q, 3), loc = n(q, 4), pvar = n(q, 5);
        Object mn = Clj.first(mnmx), mx = Clj.second(mnmx);
        Object s0 = n(sub, 1), ssteps = n(sub, 2), ssubs = n(sub, 3);
        if (!L(ssubs).isEmpty())
            throw fail("subpath variables of unquantified parenthesized paths are not allowed inside a quantified path pattern", loc,
                    "SyntaxError", "InvalidArgumentType");
        Set<Object> outer = st.names();
        for (Object x : treeSeq(sub)) {
            if (vecp(x) && (Clj.first(x) == NODE || Clj.first(x) == REL) && vecp(Clj.second(x))) {
                Object vn = n(Clj.second(x), 1), vl = n(Clj.second(x), 2);
                if (outer.contains(vn))
                    throw fail("variable `" + Clj.str(vn) + "` inside a quantified path pattern must be new (it becomes a group variable)", vl,
                            "SyntaxError", "InvalidArgumentType");
            }
        }
        if (pvar != null && outer.contains(Clj.second(pvar)))
            throw fail("variable `" + Clj.str(Clj.second(pvar)) + "` already declared", n(pvar, 2), "SyntaxError", "VariableAlreadyBound");
        R2 r0 = nodePat(s0, st);
        Object n0b = r0.v();
        St stIn = r0.st();
        List<Object[]> steps = new ArrayList<>(); // [step nb]
        for (Object rn : L(ssteps)) {
            Object r = Clj.first(rn), nd = Clj.second(rn);
            Object step;
            if (Clj.first(r) == QPP) {
                R3 qr = qppPat(r, stIn, new LinkedHashSet<>());
                step = Clj.map(KIND, QPP, SPEC, Clj.second(qr.v()));
                stIn = qr.st();
            } else {
                R3 rr = relPat(r, stIn, new LinkedHashSet<>(), MATCH);
                Object rb = rr.v();
                Object nm = n(rb, 1), types = n(rb, 2), pe = n(rb, 3), dir = n(rb, 4), rng = n(rb, 5);
                if (truthy(n(rng, 2)))
                    step = Clj.map(KIND, VARLEN, VAR, nm, TYPES, types, PE, pe, DIR, dir, MN, n(rng, 0), MX, n(rng, 1));
                else step = Clj.map(KIND, REL, VAR, nm, TYPES, types, PE, pe, DIR, dir);
                stIn = rr.st();
            }
            R2 nr = nodePat(nd, stIn);
            stIn = nr.st();
            steps.add(new Object[] {step, nr.v()});
        }
        if (pvar != null) stIn = declareVar(Clj.second(pvar), PATH, n(pvar, 2), stIn);
        Object w2 = null;
        if (truthy(w)) {
            noAggregate(w, "aggregate functions are not allowed in WHERE", loc);
            w2 = exprWhere(w, stIn);
        }
        List<Object> named = new ArrayList<>();
        for (Ent e : stIn.scope()) {
            String nm = e.name();
            if (!outer.contains(nm) && !isHidden(nm) && !named.contains(nm)) named.add(nm);
        }
        String hv = "  qpp" + stIn.anon();
        List<Ent> more = new ArrayList<>();
        more.add(new Ent(hv, REL_LIST));
        for (Object v : named) more.add(new Ent((String) v, LIST));
        St st2 = st.withAnon(stIn.anon() + 1).into(more);
        if (mx != null && ((Number) mn).longValue() > ((Number) mx).longValue())
            throw fail("quantifier lower bound is greater than upper bound", loc, "SyntaxError", "InvalidArgumentType");
        List<Object> nodes = new ArrayList<>();
        List<Object> allNodes = new ArrayList<>();
        allNodes.add(n0b);
        for (Object[] s : steps) allNodes.add(s[1]);
        for (Object nb : allNodes) nodes.add(vec(n(nb, 1), n(nb, 2), n(nb, 3)));
        List<Object> stepList = new ArrayList<>();
        for (Object[] s : steps) stepList.add(s[0]);
        Map<Object, Object> spec = Clj.map(NODES, nodes, STEPS, stepList, WHERE, w2, MN, mn, MX, mx == null ? (Object) UNBOUNDED : mx, VAR, hv,
                GROUPS, named, PVAR, pvar == null ? null : Clj.second(pvar));
        return new R3(vec(QPP, spec), st2, rels);
    }

    /**
     * 带路径搜索前缀的 path pattern ⇒ 照常匹配（没有路径变量就起一个隐藏的），之后按 (起点, 终点) 分组挑选（计划里的 :path-search 边搜边挑，
     * 或 :path-select 先列出再挑）。
     */
    private static R3 prefixedPart(Object part, St st, Set<Object> rels) {
        Object pv = n(part, 1), elem = n(part, 2), prefix = n(part, 3), pre = n(part, 4);
        Object kind = Clj.get(prefix, KIND), k = Clj.get(prefix, K), loc = Clj.get(prefix, LOC);
        if (Clj.first(elem) == SHORTEST)
            throw fail("path search prefixes cannot be combined with shortestPath()", loc, "SyntaxError", "InvalidArgumentType");
        // D-45：不再改写成 shortestPath（它不返回起点 = 终点的环路）；最短的情形由计划里的 :path-search 按点 BFS
        if (!truthy(pv)) pv = vec(VAR, "  path" + st.anon(), loc);
        st = st.withAnon(st.anon() + 1);
        R3 r = matchPart(vec(PART, pv, elem), st, rels);
        st = r.st();
        rels = r.rels();
        Object k2 = k == null ? null : Clj.isInteger(k) ? k : expr(k, st);
        Object pre2 = null;
        if (truthy(pre)) {
            noAggregate(pre, "aggregate functions are not allowed in WHERE", loc);
            pre2 = exprWhere(pre, st);
        }
        return new R3(Clj.conj(L(r.v()), Clj.map(KIND, kind, K, k2, PRE, pre2)), st, rels);
    }

    private static R3 matchPart(Object part, St st, Set<Object> rels) {
        Object prefix = n(part, 3);
        if (truthy(prefix)) return prefixedPart(part, st, rels);
        return matchPartStar(vec(PART, n(part, 1), n(part, 2)), st, rels);
    }

    private static R3 matchPartStar(Object part, St st, Set<Object> rels) {
        Object pv = n(part, 1), elem = n(part, 2);
        if (Clj.first(elem) == SHORTEST) {
            Object kindStar = n(elem, 1), chain = n(elem, 2), loc = n(elem, 3);
            Object n0 = n(chain, 1);
            List<Object> steps = L(n(chain, 2));
            if (steps.size() != 1)
                throw fail("shortestPath needs exactly one relationship pattern", loc, "SyntaxError", "InvalidArgumentType");
            Object rel = Clj.first(steps.getFirst()), n1 = Clj.second(steps.getFirst());
            R2 a = nodePat(n0, st);
            st = a.st();
            R2 b = nodePat(n1, st);
            st = b.st();
            R3 rr = relPat(rel, st, rels, SHORTEST);
            st = rr.st();
            rels = rr.rels();
            Object pvn = null;
            if (truthy(pv)) {
                pvn = n(pv, 1);
                st = declareVar(pvn, PATH, n(pv, 2), st);
            }
            return new R3(vec(kindStar == ALL ? ALL_SHORTEST : SHORTEST, pvn, a.v(), rr.v(), b.v(), loc), st, rels);
        }
        if (truthy(pv)) {
            // 普通命名路径：先照常检查模式，再声明路径变量
            R3 r = matchPartStar(vec(PART, null, elem), st, rels);
            Object pn = n(pv, 1), pl = n(pv, 2);
            return new R3(vec(NAMED_CHAIN, pn, r.v()), declareVar(pn, PATH, pl, r.st()), r.rels());
        }
        Object n0 = n(elem, 1);
        List<Object> rawSteps = L(n(elem, 2));
        List<Object> subs = L(n(elem, 3)), aliases = L(n(elem, 4));
        R2 a = nodePat(n0, st);
        Object n0b = a.v();
        st = a.st();
        List<Object> steps = new ArrayList<>();
        for (Object rn : rawSteps) {
            Object rel = Clj.first(rn), nd = Clj.second(rn);
            R3 rr = Clj.first(rel) == QPP ? qppPat(rel, st, rels) : relPat(rel, st, rels, MATCH);
            st = rr.st();
            rels = rr.rels();
            R2 nr = nodePat(nd, st);
            st = nr.st();
            steps.add(vec(rr.v(), nr.v()));
        }
        // 子路径 [p i j] ⇒ [p 起点变量 [[关系变量 节点变量] …]]（拼路径用）
        List<Object> subs2 = new ArrayList<>();
        for (Object s : subs) {
            Object pvv = Clj.first(s);
            Object pn = n(pvv, 1), pl = n(pvv, 2);
            int i = ((Number) n(s, 1)).intValue(), j = ((Number) n(s, 2)).intValue();
            Object start = Clj.second(i == 0 ? n0b : Clj.second(steps.get(i - 1)));
            List<Object> seg = new ArrayList<>();
            for (int kk = i; kk < j; kk++) {
                Object r = Clj.first(steps.get(kk)), nd = Clj.second(steps.get(kk));
                Object rname = Clj.first(r) == QPP ? Clj.get(Clj.second(r), VAR) : Clj.second(r);
                seg.add(vec(rname, Clj.second(nd)));
            }
            subs2.add(vec(pn, start, seg));
            st = declareVar(pn, PATH, pl, st);
        }
        // 别名：并排的节点模式起了不同的名字 ⇒ 第二个名字绑到同一个节点
        List<Object> aliases2 = new ArrayList<>();
        for (Object al : aliases) {
            Object av = Clj.first(al), tv = Clj.second(al);
            Object an = n(av, 1), aloc = n(av, 2), tn = n(tv, 1);
            aliases2.add(vec(an, tn));
            st = declareVar(an, NODE, aloc, st);
        }
        List<Object> out = vec(CHAIN, n0b, steps);
        if (!subs2.isEmpty() || !aliases2.isEmpty()) out.add(subs2);
        if (!aliases2.isEmpty()) out.add(aliases2);
        return new R3(out, st, rels);
    }

    private static R2 matchParts(Object parts, St st) {
        List<Object> out = new ArrayList<>();
        Set<Object> rels = new LinkedHashSet<>();
        for (Object part : L(parts)) {
            R3 r = matchPart(part, st, rels);
            out.add(r.v());
            st = r.st();
            rels = r.rels();
        }
        return new R2(out, st);
    }

    /** 写标签（CREATE / MERGE / SET / REMOVE）只能是普通标签。 */
    private static void plainLabels(Object labels, Object loc) {
        for (Object l : L(labels))
            if (!(l instanceof String))
                throw fail("label expressions (A|B, !A) can only be used for matching", loc, "SyntaxError", "InvalidArgumentType");
    }

    private static void mergePropsCheck(Object pe, Object loc) {
        if (pe == null) return;
        if (Clj.first(pe) == MAP) {
            for (Object kv : L(Clj.second(pe)))
                if ("embedding".equals(Clj.first(kv))) throw fail("MERGE cannot match on `embedding`", loc, "SyntaxError", "InvalidArgumentType");
            return;
        }
        throw fail("MERGE properties must be a map literal", loc, "SyntaxError", "InvalidParameterUse");
    }

    private static Object mergeEndpoint(Object nd, St st) {
        Object v = n(nd, 1), labels = n(nd, 2), props = n(nd, 3), loc = n(nd, 4);
        if (v == null)
            throw fail("MERGE of a relationship needs both ends to be already bound nodes", loc, "SyntaxError", "VariableAlreadyBound");
        Object vn = n(v, 1), vl = n(v, 2);
        if (kind(vn, st) == NODE) {
            if (L(labels).isEmpty() && props == null) return vn;
            throw fail("can't add labels or properties to the bound node `" + Clj.str(vn) + "` in MERGE", vl, "SyntaxError",
                    "VariableAlreadyBound");
        }
        throw fail("MERGE of a relationship needs `" + Clj.str(vn) + "` to be an already bound node", vl, "SyntaxError", "VariableAlreadyBound");
    }

    /** 整条模式的 MERGE：端点可以未绑定（带标签 / 属性）、可以多跳；整条匹配不上就整条建出来。 */
    private static R2 mergeWhole(Object chain, St st) {
        Object n0 = n(chain, 1);
        List<Object> steps = L(n(chain, 2));
        List<Object> nodes = vec(n0);
        for (Object s : steps) nodes.add(Clj.second(s));
        for (Object nd : nodes) {
            Object v = n(nd, 1), labels = n(nd, 2), props = n(nd, 3), nl = n(nd, 4);
            if (v != null) {
                Object vn = n(v, 1), vl = n(v, 2);
                Kw k = kind(vn, st);
                if (k == null) {
                    // 新变量
                } else if (k == NODE) {
                    if (!L(labels).isEmpty() || truthy(props))
                        throw fail("can't add labels or properties to the bound node `" + Clj.str(vn) + "` in MERGE", vl, "SyntaxError",
                                "VariableAlreadyBound");
                } else {
                    throw fail("variable `" + Clj.str(vn) + "` already declared as " + k.name, vl, "SyntaxError", "VariableTypeConflict");
                }
            }
            plainLabels(labels, nl);
            mergePropsCheck(props, nl);
        }
        for (Object s : steps) {
            Object r = Clj.first(s);
            Object rv = n(r, 1), types = n(r, 2), rprops = n(r, 3), vl = n(r, 5), rl = n(r, 6);
            if (rv != null && kind(n(rv, 1), st) != null)
                throw fail("variable `" + Clj.str(n(rv, 1)) + "` already declared", n(rv, 2), "SyntaxError", "VariableAlreadyBound");
            if (vl != null) throw fail("variable-length relationships are not allowed in MERGE", rl, "SyntaxError", "CreatingVarLength");
            if (L(types).size() != 1) throw fail("MERGE needs exactly one relationship type", rl, "SyntaxError", "NoSingleRelationshipType");
            mergePropsCheck(rprops, rl);
            if (rv != null && kind(n(rv, 1), st) != null)
                throw fail("variable `" + Clj.str(n(rv, 1)) + "` already declared", n(rv, 2), "SyntaxError", "VariableAlreadyBound");
        }
        R3 r = matchPart(vec(PART, null, chain), st, new LinkedHashSet<>());
        return new R2(vec(PATTERN, r.v()), r.st());
    }

    private static void noQpp(Object part, String what) {
        Object elem = n(part, 2), prefix = n(part, 3);
        if (truthy(prefix))
            throw fail("path search prefixes are not allowed in " + what, Clj.get(prefix, LOC), "SyntaxError", "InvalidArgumentType");
        if (Clj.first(elem) == CHAIN) {
            for (Object s : L(n(elem, 2))) {
                Object r = Clj.first(s);
                if (Clj.first(r) == QPP)
                    throw fail("quantified path patterns are not allowed in " + what, n(r, 4), "SyntaxError", "InvalidArgumentType");
            }
        }
    }

    private static boolean boundPlain(Object nd, St st) {
        Object v = n(nd, 1);
        return v != null && L(n(nd, 2)).isEmpty() && n(nd, 3) == null && kind(Clj.second(v), st) == NODE;
    }

    /** MERGE 的模式：单个节点 (v:L {…})，或两端都是已绑定节点的单条有向关系 (a)-[r:T {…}]->(b)。 */
    private static R2 mergePart(Object part, St st) {
        noQpp(part, "MERGE");
        Object pv = n(part, 1), elem = n(part, 2);
        if (Clj.first(elem) == SHORTEST) throw fail("shortestPath is not allowed in MERGE", n(elem, 3), "SyntaxError", "InvalidArgumentType");
        if (truthy(pv)) {
            Object pn = n(pv, 1), pl = n(pv, 2);
            if (kind(pn, st) != null) throw fail("variable `" + Clj.str(pn) + "` already declared", pl, "SyntaxError", "VariableAlreadyBound");
            R2 r = mergeWhole(elem, st);
            Object c2 = Clj.second(r.v());
            return new R2(vec(PATTERN, c2, pn), declareVar(pn, PATH, pl, r.st()));
        }
        Object nd = n(elem, 1);
        Object v = n(nd, 1), labels = n(nd, 2), props = n(nd, 3), nloc = n(nd, 4);
        List<Object> steps = L(n(elem, 2));
        if (steps.isEmpty()) {
            if (v != null) {
                Object vn = n(v, 1), vl = n(v, 2);
                if (kind(vn, st) != null)
                    throw fail("MERGE (" + Clj.str(vn) + ") would merge an already bound variable; use MATCH or SET", vl, "SyntaxError",
                            "VariableAlreadyBound");
            }
            mergePropsCheck(props, nloc);
            plainLabels(labels, nloc);
            R2 r = nodePat(nd, st);
            Object m = r.v();
            return new R2(vec(NODE, n(m, 1), n(m, 2), n(m, 3)), r.st());
        }
        if (steps.size() == 1 && boundPlain(nd, st) && boundPlain(Clj.second(steps.getFirst()), st)) {
            Object rel = Clj.first(steps.getFirst()), n1 = Clj.second(steps.getFirst());
            Object rv = n(rel, 1), types = n(rel, 2), rprops = n(rel, 3), dir = n(rel, 4), vl = n(rel, 5), rl = n(rel, 6);
            if (rv != null && kind(n(rv, 1), st) != null)
                throw fail("variable `" + Clj.str(n(rv, 1)) + "` already declared", n(rv, 2), "SyntaxError", "VariableAlreadyBound");
            Object from = mergeEndpoint(nd, st);
            Object to = mergeEndpoint(n1, st);
            if (vl != null) throw fail("variable-length relationships are not allowed in MERGE", rl, "SyntaxError", "CreatingVarLength");
            if (L(types).size() != 1) throw fail("MERGE needs exactly one relationship type", rl, "SyntaxError", "NoSingleRelationshipType");
            mergePropsCheck(rprops, rl);
            R3 r = relPat(rel, st, new LinkedHashSet<>(), CREATE);
            Object rb = r.v();
            return new R2(vec(REL, from, n(rb, 1), Clj.first(n(rb, 2)), n(rb, 3), dir, to), r.st());
        }
        return mergeWhole(elem, st);
    }

    private static R2 createNode(Object nd, St st) {
        Object v = n(nd, 1), labels = n(nd, 2), props = n(nd, 3), loc = n(nd, 4);
        if (v != null) {
            Object vn = n(v, 1), vl = n(v, 2);
            if (kind(vn, st) == NODE && (!L(labels).isEmpty() || truthy(props)))
                throw fail("can't add labels or properties to the already bound node `" + Clj.str(vn) + "` in CREATE; use SET", vl,
                        "SyntaxError", "VariableAlreadyBound");
        }
        plainLabels(labels, v != null ? n(v, 2) : loc);
        return nodePat(nd, st);
    }

    private static R2 createParts(Object parts, St st) {
        List<Object> acc = new ArrayList<>();
        for (Object part : L(parts)) {
            Object pv = n(part, 1), elem = n(part, 2);
            noQpp(part, "CREATE");
            if (Clj.first(elem) == SHORTEST)
                throw fail("shortestPath is not allowed in CREATE", n(elem, 3), "SyntaxError", "InvalidArgumentType");
            if (truthy(pv) && kind(n(pv, 1), st) != null)
                throw fail("variable `" + Clj.str(n(pv, 1)) + "` already declared", n(pv, 2), "SyntaxError", "VariableAlreadyBound");
            Object n0 = n(elem, 1);
            List<Object> rawSteps = L(n(elem, 2));
            Object v0 = n(n0, 1);
            if (v0 != null && rawSteps.isEmpty()) {
                Kw k = kind(Clj.second(v0), st);
                if (k == NODE || k == VALUE)
                    throw fail("variable `" + Clj.str(Clj.second(v0)) + "` is already bound; CREATE of a bare bound node creates nothing",
                            n(v0, 2), "SyntaxError", "VariableAlreadyBound");
            }
            R2 a = createNode(n0, st);
            Object n0b = a.v();
            st = a.st();
            List<Object> steps = new ArrayList<>();
            for (Object rn : rawSteps) {
                Object rel = Clj.first(rn), nd = Clj.second(rn);
                Object types = n(rel, 2), dir = n(rel, 4), vl = n(rel, 5), rl = n(rel, 6);
                Object rv = Clj.second(rel);
                if (truthy(rv)) {
                    Object rvn = n(rv, 1), rvl = n(rv, 2);
                    if (kind(rvn, st) != null)
                        throw fail("variable `" + Clj.str(rvn) + "` already declared", rvl, "SyntaxError", "VariableAlreadyBound");
                }
                if (vl != null) throw fail("variable-length relationships are not allowed in CREATE", rl, "SyntaxError", "CreatingVarLength");
                if (dir == kw("both")) throw fail("CREATE needs a directed relationship", rl, "SyntaxError", "RequiresDirectedRelationship");
                if (L(types).size() != 1)
                    throw fail("CREATE needs exactly one relationship type", rl, "SyntaxError", "NoSingleRelationshipType");
                R3 rr = relPat(rel, st, new LinkedHashSet<>(), CREATE);
                st = rr.st();
                R2 nr = createNode(nd, st);
                st = nr.st();
                steps.add(vec(rr.v(), nr.v()));
            }
            if (truthy(pv)) st = declareVar(n(pv, 1), PATH, n(pv, 2), st);
            List<Object> ch = vec(CHAIN, n0b, steps);
            if (truthy(pv)) ch.add(Clj.second(pv));
            acc.add(ch);
        }
        return new R2(acc, st);
    }

    // ── 投影 ──────────────────────────────────────────────

    private static boolean isVarOrVarProp(Object x) {
        return Clj.first(x) == VAR || (Clj.first(x) == PROP && vecp(n(x, 1)) && Clj.first(n(x, 1)) == VAR);
    }

    /**
     * 聚合投影里，含聚合的项在聚合之外引用的东西必须是分组键（某个不含聚合的投影项、或其中的变量），或常量 / 参数——
     * `RETURN n.x + count(*)` 报错，`RETURN n.x, n.x + count(*)`、`RETURN n, n.x + count(*)` 合法（openCypher）。
     */
    private static void checkImplicitGrouping(List<Object> items, Object loc) {
        boolean any = false;
        for (Object it : items) if (isContainsAggregate(Clj.second(it))) any = true;
        if (!any) return;
        Set<Object> keys = new java.util.HashSet<>();
        for (Object it : items) if (!isContainsAggregate(Clj.second(it))) keys.add(Clj.second(it));
        for (Object it : items) {
            Object nm = Clj.first(it), e = Clj.second(it);
            if (isContainsAggregate(e)) chkGrouping(e, Set.of(), keys, nm, loc);
        }
    }

    private static void chkGrouping(Object x, Set<Object> locals, Set<Object> keys, Object nm, Object loc) {
        if (!vecp(x)) return;
        if (isAggregate(x)) return;
        if (keys.contains(x)) {
            if (!isVarOrVarProp(x))
                throw fail("aggregation expression `" + Clj.str(nm) + "` uses the grouping expression `" + txt(x)
                        + "`; only variables and property accesses can be shared with grouping keys", loc, "SyntaxError",
                        "AmbiguousAggregationExpression");
            return;
        }
        Kw hd = h(x);
        if (hd == VAR) {
            if (!locals.contains(Clj.second(x)))
                throw fail("aggregation expression `" + Clj.str(nm) + "` uses `" + Clj.str(Clj.second(x)) + "`, which is not a grouping key"
                        + " (project it separately or wrap it in an aggregate)", loc, "SyntaxError", "AmbiguousAggregationExpression");
            return;
        }
        if (hd == LIST_COMP || hd == QUANT) {
            Object v = hd == LIST_COMP ? n(x, 1) : n(x, 2);
            Set<Object> l2 = new java.util.HashSet<>(locals);
            l2.add(v);
            for (Object c : kids(x)) chkGrouping(c, l2, keys, nm, loc);
            return;
        }
        if (hd == REDUCE) {
            Set<Object> l2 = new java.util.HashSet<>(locals);
            l2.add(n(x, 1));
            l2.add(n(x, 3));
            for (Object c : kids(x)) chkGrouping(c, l2, keys, nm, loc);
            return;
        }
        for (Object c : kids(x)) chkGrouping(c, locals, keys, nm, loc);
    }

    private static final Set<String> SCALAR_FNS = Set.of("count", "sum", "avg", "size", "length", "tostring", "tointeger", "tofloat",
            "toboolean", "toupper", "tolower", "trim", "ltrim", "rtrim", "substring", "replace", "split", "left", "right", "reverse", "keys",
            "labels", "type", "id", "abs", "ceil", "floor", "round", "sign", "sqrt", "exp", "log", "log10", "rand", "timestamp", "range",
            "nodes", "relationships", "collect", "percentilecont", "percentiledisc", "stdev", "stdevp", "properties", "exists", "e", "pi",
            "sin", "cos", "tan", "cot", "asin", "acos", "atan", "atan2", "degrees", "radians", "haversin", "isnan");
    private static final Set<String> LIST_FNS = Set.of("collect", "nodes", "relationships", "range", "split", "keys", "labels", "tail");

    /** 投影出来的变量的类别：变量照抄；非 null 字面量、列表、map、运算、返回标量的函数 ⇒ :scalar；看不出来的 ⇒ :value（运行时再查）。 */
    private static Kw itemKind(Object e, St st) {
        if (vecp(e) && Clj.first(e) == VAR) return kind(Clj.second(e), st);
        if (!vecp(e)) return VALUE;
        Kw hd = h(e);
        String c = hd == null ? "" : hd.name;
        return switch (c) {
            case "lit" -> Clj.second(e) == null ? VALUE : SCALAR;
            // 列表：可以当变长关系的关系列表用，不能当节点 / 单条关系
            case "list", "list-comp", "pattern-comp" -> LIST;
            case "map", "op", "unop", "is-null", "map-proj", "count-star", "quant", "exists-sub" -> SCALAR;
            case "call" -> LIST_FNS.contains(Clj.second(e)) ? LIST : SCALAR_FNS.contains(Clj.second(e)) ? SCALAR : VALUE;
            default -> VALUE;
        };
    }

    /** 投影 ⇒ [项 有聚合? 投影后的作用域 WITH 缺别名的表达式文本]。 */
    private static Object[] projection(Object proj, Kw which, Object loc, St st) {
        boolean star;
        Object items0;
        if (Clj.first(proj) == STAR) {
            star = true;
            items0 = Clj.second(proj);
        } else if (Clj.first(proj) == ITEMS) {
            star = false;
            items0 = Clj.second(proj);
        } else throw new IllegalArgumentException("No matching clause: " + Clj.first(proj));
        List<Object> starItems = new ArrayList<>();
        if (star) {
            for (Ent e : st.scope()) if (!isHidden(e.name())) starItems.add(vec(e.name(), vec(VAR, e.name())));
            if (starItems.isEmpty() && L(items0).isEmpty() && which == RETURN)
                throw fail("`RETURN *` with no variables in scope", loc, "SyntaxError", "NoVariablesInScope");
        }
        Object missing = null;
        List<Object> items = new ArrayList<>();
        for (Object it : L(items0)) {
            Object e = n(it, 1), alias = n(it, 2), text = n(it, 3);
            Object e2 = expr(e, st);
            Object nm;
            if (truthy(alias)) nm = Clj.second(alias);
            else if (Clj.first(e) == VAR) nm = Clj.second(e);
            else if (which == WITH) {
                missing = truthy(text) ? text : txt(e);
                nm = truthy(text) ? text : txt(e);
            } else nm = truthy(text) ? text : txt(e);
            items.add(vec(nm, e2));
        }
        List<Object> all = new ArrayList<>(starItems);
        all.addAll(items);
        Map<Object, Integer> freq = new LinkedHashMap<>();
        for (Object it : all) freq.merge(Clj.first(it), 1, Integer::sum);
        for (Map.Entry<Object, Integer> en : freq.entrySet())
            if (en.getValue() > 1) throw fail("duplicate column name `" + Clj.str(en.getKey()) + "`", loc, "SyntaxError", "ColumnNameConflict");
        for (Object it : all) checkAggNesting(Clj.second(it), loc);
        checkImplicitGrouping(all, loc);
        boolean agg = false;
        for (Object it : all) if (isContainsAggregate(Clj.second(it))) agg = true;
        List<Ent> scope = new ArrayList<>();
        Map<String, Object> types = new HashMap<>();
        for (Object it : all) {
            Object nm = Clj.first(it), e = Clj.second(it);
            scope.add(new Ent((String) nm, itemKind(e, st)));
            types.put((String) nm, stype(e, st, new HashMap<>()));
        }
        St st2 = new St(scope, st.anon(), types);
        return new Object[] {all, agg, st2, missing};
    }

    private static Object pagingExpr(Object e, Object loc) {
        if (e == null) return null;
        if (!exprVarsSet(e).isEmpty()) throw fail("SKIP / LIMIT must not reference variables", loc, "SyntaxError", "NonConstantExpression");
        if (isContainsAggregate(e)) throw fail("aggregate functions are not allowed in SKIP / LIMIT", loc, "SyntaxError", "InvalidAggregation");
        Object c;
        if (Clj.first(e) == LIT) c = Clj.second(e);
        else if (Clj.first(e) == UNOP && n(e, 1) == MINUS && Clj.first(n(e, 2)) == LIT && Clj.second(n(e, 2)) instanceof Number num)
            c = Clj.isInteger(num) ? (Object) Math.negateExact(num.longValue()) : (Object) (-num.doubleValue());
        else c = NONE;
        if (c != NONE) {
            if (!Clj.isInteger(c))
                throw fail("SKIP / LIMIT need an integer, got a non-integer value", loc, "SyntaxError", "InvalidArgumentType");
            if (((Number) c).longValue() < 0)
                throw fail("SKIP / LIMIT need a non-negative integer, got a negative number", loc, "SyntaxError", "NegativeIntegerArgument");
        }
        return norm(e, ambient());
    }

    /** 表达式里与投影项相同的子表达式换成 [:var 列名]。agg?：原表达式含聚合 ⇒ 被换掉的不含聚合的项只能是变量 / 属性（否则有歧义）。 */
    private static Object substItems(Object e, List<Object> items, boolean agg, Object loc) {
        Map<Object, Object> byExpr = new HashMap<>();
        for (Object it : items) byExpr.put(Clj.second(it), Clj.first(it));
        return subst(e, byExpr, agg, loc);
    }

    private static Object subst(Object x, Map<Object, Object> byExpr, boolean agg, Object loc) {
        Object nm = byExpr.get(x);
        if (nm != null) {
            if (agg && !isContainsAggregate(x) && !isVarOrVarProp(x))
                throw fail("ambiguous aggregation: `" + txt(x)
                        + "` is a grouping expression; only variables and property accesses can be used together with aggregates", loc,
                        "SyntaxError", "AmbiguousAggregationExpression");
            return vec(VAR, nm);
        }
        if (vecp(x)) {
            List<Object> out = new ArrayList<>();
            for (Object c : L(x)) out.add(subst(c, byExpr, agg, loc));
            return out;
        }
        return x;
    }

    private static Object orderExpr(Object e, List<Object> items, boolean restricted, Object loc, St stBefore, St stAfter) {
        Object nrm = exprQuiet(e, stBefore);
        Object hit = null;
        if (nrm != NONE) for (Object it : items) if (Clj.eq(Clj.second(it), nrm)) {
            hit = Clj.first(it);
            break;
        }
        if (hit != null) return vec(VAR, hit);
        if (restricted) {
            // 聚合 / DISTINCT 之后：与投影项相同的子表达式换成列名，剩下的只能引用列名、常量、参数
            St merged = stAfter.into(stBefore.scope());
            Object n2 = exprQuiet(e, merged);
            if (n2 == NONE) expr(e, stAfter);
            Object s = substItems(n2, items, isContainsAggregate(n2), loc);
            Set<Object> names = new LinkedHashSet<>();
            for (Object it : items) names.add(Clj.first(it));
            if (isContainsAggregate(s))
                throw fail("ORDER BY after aggregation can only use returned columns (or aggregates that are returned)", loc, "SyntaxError",
                        "UndefinedVariable");
            for (Object v : exprVarsSet(s))
                if (!names.contains(v))
                    throw fail("ORDER BY after aggregation / DISTINCT can only use returned columns; `" + Clj.str(v) + "` is not returned", loc,
                            "SyntaxError", "UndefinedVariable");
            return s;
        }
        noAggregate(e, "aggregate functions in ORDER BY must also be returned", loc);
        // 投影后的名字与投影前的变量都可见（同名时投影后的优先）
        return expr(e, stAfter.into(stBefore.scope()));
    }

    private static Object[] paging(Object order, Object skip, Object limit, List<Object> items, boolean restricted, Object loc, St stBefore,
            St stAfter) {
        List<Object> o = new ArrayList<>();
        for (Object od : L(order)) o.add(vec(orderExpr(Clj.first(od), items, restricted, loc, stBefore, stAfter), Clj.second(od)));
        return new Object[] {o, pagingExpr(skip, loc), pagingExpr(limit, loc)};
    }

    // ── 子句 ──────────────────────────────────────────────

    private static Object mustBe(Object var, Set<Kw> kinds, St st) {
        Object v = n(var, 1), l = n(var, 2);
        Kw k = kind(v, st);
        if (k == null) throw fail("variable `" + Clj.str(v) + "` not defined", l, "SyntaxError", "UndefinedVariable");
        if (!(kinds.contains(k) || k == VALUE))
            throw fail("variable `" + Clj.str(v) + "` is not a " + (kinds.equals(Set.of(NODE)) ? "node" : "node or relationship"), l,
                    "SyntaxError", "InvalidArgumentType");
        return v;
    }

    private static void reservedKey(Object kk, Object loc) {
        if (((String) kk).startsWith("_"))
            throw fail("property names starting with `_` are reserved: " + kk, loc, "SyntaxError", "InvalidArgumentType");
    }

    private static final Set<Kw> NODE_REL = Set.of(NODE, REL), NODE_ONLY = Set.of(NODE);

    @SuppressWarnings("unchecked")
    private static R2 clause(Object c, St st) {
        Kw hd = h(c);
        String cn = hd == null ? "" : hd.name;
        switch (cn) {
            case "merge": {
                Object part = n(c, 1), onC = n(c, 2), onM = n(c, 3), loc = n(c, 4);
                R2 r = mergePart(part, st);
                Object m = r.v();
                St st2 = r.st();
                List<Object> oc = new ArrayList<>(), om = new ArrayList<>();
                for (Object it : L(onC)) oc.add(setItem(it, loc, st2));
                for (Object it : L(onM)) om.add(setItem(it, loc, st2));
                Kw mh = h(m);
                Object out;
                if (mh == NODE) out = vec(MERGE_NODE, n(m, 1), n(m, 2), n(m, 3), oc, om, loc);
                else if (mh == REL) out = vec(MERGE_REL, n(m, 1), n(m, 2), n(m, 3), n(m, 4), n(m, 5), n(m, 6), oc, om, loc);
                else if (mh == PATTERN) out = vec(MERGE_PATTERN, Clj.second(m), oc, om, n(m, 2), loc);
                else throw new IllegalArgumentException("No matching clause: " + mh);
                return new R2(out, st2);
            }
            case "call-proc": {
                Object nm = n(c, 1), args = n(c, 2), yields = n(c, 3), where = n(c, 4), loc = n(c, 5);
                Object sig = procSig(nm, loc);
                List<Object> inputs = L(Clj.get(sig, Procs.INPUTS)), outputs = L(Clj.get(sig, Procs.OUTPUTS));
                List<Object> cols = new ArrayList<>();
                for (Object o : outputs) cols.add(Clj.get(o, Procs.NAME));
                if (yields == STAR) throw fail("YIELD * is only allowed in a standalone CALL", loc, "SyntaxError", "UnexpectedSyntax");
                if (args == null && !inputs.isEmpty())
                    throw fail("CALL " + Clj.str(nm) + " in the middle of a query needs explicit arguments", loc, "SyntaxError",
                            "InvalidArgumentPassingMode");
                if (yields == null && !outputs.isEmpty())
                    throw fail("CALL followed by other clauses needs YIELD", loc, "SyntaxError", "UndefinedVariable");
                Object args2 = procArgs(nm, inputs, args == null ? vec() : L(args), loc, st);
                List<Object> pairs = new ArrayList<>();
                for (Object y : L(yields)) {
                    Object cv = Clj.first(y), av = Clj.second(y);
                    Object cname = n(cv, 1), cl = n(cv, 2), an = n(av, 1), al = n(av, 2);
                    if (!cols.contains(cname))
                        throw fail("procedure `" + Clj.str(nm) + "` has no output `" + Clj.str(cname) + "`", cl, "SyntaxError",
                                "InvalidArgumentType");
                    pairs.add(vec(cname, an));
                    st = declareVar(an, VALUE, al, st);
                }
                if (truthy(where)) noAggregate(where, "aggregate functions are not allowed in WHERE", loc);
                return new R2(vec(CALL_PROC, nm, pairs, args2, truthy(where) ? exprWhere(where, st) : null, loc), st);
            }
            case "match": {
                Object opt = n(c, 1), parts = n(c, 2), where = n(c, 3), loc = n(c, 4);
                for (Object x : treeSeq(parts)) {
                    if (vecp(x) && (Clj.first(x) == NODE || Clj.first(x) == REL)) {
                        Object pe = n(x, 3);
                        if (vecp(pe) && Clj.first(pe) == PARAM)
                            throw fail("parameters cannot be used as property maps in MATCH (write {k: $p.k} instead)", loc, "SyntaxError",
                                    "InvalidParameterUse");
                    }
                }
                R2 r = matchParts(parts, st);
                St st2 = r.st();
                boolean prefixed = false;
                for (Object p : L(parts)) if (truthy(n(p, 3))) prefixed = true;
                Object w2 = null;
                if (truthy(where)) {
                    noAggregate(where, "aggregate functions are not allowed in WHERE", loc);
                    w2 = exprWhere(where, st2);
                }
                Kw k = truthy(opt) ? OPTIONAL_MATCH : MATCH;
                // 有路径搜索前缀：元素谓词在挑选之前、图模式的 WHERE 在挑选之后（GQL 的语义）
                return new R2(prefixed ? vec(k, r.v(), null, loc, w2) : vec(k, r.v(), w2, loc), st2);
            }
            case "unwind": {
                Object e = n(c, 1), var = n(c, 2), loc = n(c, 3);
                Object vn = n(var, 1), vl = n(var, 2);
                noAggregate(e, "aggregate functions are not allowed in UNWIND", loc);
                Object e2 = expr(e, st);
                Object t = stype(e2, st, new HashMap<>());
                St st2 = declareVar(vn, VALUE, vl, st);
                if (isListT(t)) st2 = st2.withType((String) vn, Clj.second(t));
                return new R2(vec(UNWIND, e2, vn, loc), st2);
            }
            case "with": {
                Object d = n(c, 1), proj = n(c, 2), order = n(c, 3), skip = n(c, 4), limit = n(c, 5), where = n(c, 6), loc = n(c, 7);
                Object[] p = projection(proj, WITH, loc, st);
                List<Object> items = (List<Object>) p[0];
                boolean agg = (Boolean) p[1];
                St st2 = (St) p[2];
                Object missing = p[3];
                Object[] pg = paging(order, skip, limit, items, agg || truthy(d), loc, st, st2);
                if (truthy(missing))
                    throw fail("expression in WITH must be aliased (use AS): " + Clj.str(missing), loc, "SyntaxError", "NoExpressionAlias");
                if (truthy(where)) noAggregate(where, "aggregate functions are not allowed in WHERE", loc);
                // 不聚合、不 DISTINCT：WHERE 也看得见投影前的变量（同名时投影后的优先）；聚合 / DISTINCT 之后：与投影项相同的
                // 子表达式换成列名，剩下的只能引用列名
                Object w2 = null;
                if (truthy(where)) {
                    if (agg || truthy(d)) {
                        St merged = st2.into(st.scope());
                        Object n2 = exprQuiet(where, merged);
                        if (n2 == NONE) w2 = exprWhere(where, st2);
                        else {
                            Object s = substItems(n2, items, false, loc);
                            Set<Object> names = new LinkedHashSet<>();
                            for (Object it : items) names.add(Clj.first(it));
                            for (Object v : exprVarsSet(s))
                                if (!names.contains(v))
                                    throw fail("variable `" + Clj.str(v)
                                            + "` not defined (WITH ... WHERE after aggregation / DISTINCT only sees the projected columns)", loc,
                                            "SyntaxError", "UndefinedVariable");
                            w2 = s;
                        }
                    } else w2 = exprWhere(where, st2.into(st.scope()));
                }
                return new R2(vec(WITH, d, items, agg, pg[0], pg[1], pg[2], w2, loc), st2);
            }
            case "return": {
                Object d = n(c, 1), proj = n(c, 2), order = n(c, 3), skip = n(c, 4), limit = n(c, 5), loc = n(c, 6);
                Object[] p = projection(proj, RETURN, loc, st);
                List<Object> items = (List<Object>) p[0];
                boolean agg = (Boolean) p[1];
                St st2 = (St) p[2];
                Object[] pg = paging(order, skip, limit, items, agg || truthy(d), loc, st, st2);
                return new R2(vec(RETURN, d, items, agg, pg[0], pg[1], pg[2], loc), st2);
            }
            case "create": {
                Object loc = n(c, 2);
                R2 r = createParts(n(c, 1), st);
                return new R2(vec(CREATE, r.v(), loc), r.st());
            }
            case "set": {
                Object loc = n(c, 2);
                List<Object> its = new ArrayList<>();
                for (Object it : L(n(c, 1))) its.add(setItem(it, loc, st));
                return new R2(vec(SET, its, loc), st);
            }
            case "remove": {
                Object loc = n(c, 2);
                List<Object> its = new ArrayList<>();
                for (Object it : L(n(c, 1))) {
                    Kw ih = h(it);
                    if (ih == REMOVE_PROP) {
                        Object v = n(it, 1), kk = n(it, 2);
                        mustBe(v, NODE_REL, st);
                        reservedKey(kk, n(v, 2));
                        its.add(vec(REMOVE_PROP, Clj.second(v), kk));
                    } else if (ih == REMOVE_LABELS) {
                        Object v = n(it, 1), ls = n(it, 2);
                        mustBe(v, NODE_ONLY, st);
                        plainLabels(ls, n(v, 2));
                        its.add(vec(REMOVE_LABELS, Clj.second(v), ls));
                    } else throw new IllegalArgumentException("No matching clause: " + ih);
                }
                return new R2(vec(REMOVE, its, loc), st);
            }
            case "delete": {
                Object detach = n(c, 1), loc = n(c, 3);
                List<Object> es = new ArrayList<>();
                for (Object e : L(n(c, 2))) {
                    noAggregate(e, "aggregate functions are not allowed in DELETE", loc);
                    Kw eh = h(e);
                    if (eh == HAS_LABELS) throw fail("DELETE cannot remove labels; use REMOVE n:Label", loc, "SyntaxError", "InvalidDelete");
                    if (eh == LIT || eh == OP || eh == UNOP || eh == MAP)
                        throw fail("DELETE needs nodes, relationships or paths", loc, "SyntaxError", "InvalidArgumentType");
                    es.add(expr(e, st));
                }
                return new R2(vec(DELETE, detach, es, loc), st);
            }
            default:
                throw new IllegalArgumentException("No matching clause: " + Clj.first(c));
        }
    }

    /** 字面量参数的类型（编译期检查用）；看不出来 ⇒ nil。 */
    private static Object literalType(Object e) {
        if (!vecp(e)) return null;
        Kw hd = h(e);
        if (hd == LIT) {
            Object v = Clj.second(e);
            if (v == null) return NULL;
            if (v instanceof Boolean) return "BOOLEAN";
            if (Clj.isInteger(v)) return "INTEGER";
            if (v instanceof Number) return "FLOAT";
            if (v instanceof String) return "STRING";
            return null;
        }
        if (hd == LIST) return "LIST";
        if (hd == MAP) return "MAP";
        return null;
    }

    /** 过程参数：个数、聚合、字面量的类型在编译期检查；其余类型在执行时查。 */
    private static Object procArgs(Object nm, List<Object> inputs, List<Object> args, Object loc, St st) {
        // 带缺省值的参数可省（必须排在后面）
        int required = 0;
        for (Object in : inputs) if (!truthy(Clj.get(in, Procs.OPTIONAL))) required++;
        if (!(required <= args.size() && args.size() <= inputs.size()))
            throw fail("procedure `" + Clj.str(nm) + "` takes "
                    + (required == inputs.size() ? String.valueOf(inputs.size()) : required + ".." + inputs.size()) + " argument(s), got "
                    + args.size(), loc, "SyntaxError", "InvalidNumberOfArguments");
        List<Object> all = new ArrayList<>(args);
        for (int i = args.size(); i < inputs.size(); i++) all.add(vec(LIT, Clj.get(inputs.get(i), Procs.DEFAULT)));
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(all.size(), inputs.size()); i++) {
            Object a = all.get(i), in = inputs.get(i);
            Object type = Clj.get(in, Procs.TYPE);
            boolean nullable = truthy(Clj.get(in, Procs.NULLABLE));
            noAggregate(a, "aggregate functions are not allowed in procedure arguments", loc);
            Object lt = literalType(a);
            if (lt != null) {
                if (!("ANY".equals(type) || Clj.eq(lt, type) || (lt == NULL && nullable)
                        || ("NUMBER".equals(type) && ("INTEGER".equals(lt) || "FLOAT".equals(lt))) || ("FLOAT".equals(type) && "INTEGER".equals(lt))))
                    throw fail("argument `" + Clj.str(Clj.get(in, Procs.NAME)) + "` of " + Clj.str(nm) + " must be " + Clj.str(type), loc,
                            "SyntaxError", "InvalidArgumentType");
            }
            out.add(expr(a, st));
        }
        return out;
    }

    private static Object setItem(Object it, Object loc, St st) {
        Kw ih = h(it);
        if (ih == SET_PROP) {
            Object v = n(it, 1), kk = n(it, 2), e = n(it, 3);
            mustBe(v, NODE_REL, st);
            reservedKey(kk, n(v, 2));
            noAggregate(e, "aggregate functions are not allowed in SET", loc);
            return vec(SET_PROP, Clj.second(v), kk, expr(e, st));
        }
        if (ih == SET_PROPS) {
            Object v = n(it, 1), e = n(it, 2), mode = n(it, 3);
            mustBe(v, NODE_REL, st);
            noAggregate(e, "aggregate functions are not allowed in SET", loc);
            return vec(SET_PROPS, Clj.second(v), expr(e, st), mode);
        }
        if (ih == SET_LABELS) {
            Object v = n(it, 1), ls = n(it, 2);
            mustBe(v, NODE_ONLY, st);
            plainLabels(ls, n(v, 2));
            return vec(SET_LABELS, Clj.second(v), ls);
        }
        throw new IllegalArgumentException("No matching clause: " + ih);
    }

    /** 更新子句：CREATE / SET / REMOVE / DELETE / MERGE，以及调用写模式（:mode :write）的用户过程。 */
    private static boolean isWrite(Object c) {
        Kw hd = h(c);
        if (hd == CREATE || hd == SET || hd == REMOVE || hd == DELETE || hd == MERGE) return true;
        if (hd == CALL_PROC) return Clj.get(Procs.lookup(Clj.str(n(c, 1)).toLowerCase(Locale.ROOT)), Procs.MODE) == Procs.WRITE;
        return false;
    }

    private static Object locOf(Object c) {
        return Clj.last(c);
    }

    private static void checkOrder(List<Object> clauses) {
        if (clauses.isEmpty()) throw new IllegalStateException("Can't pop empty vector");
        Object lst = clauses.getLast();
        for (Object c : clauses.subList(0, clauses.size() - 1))
            if (Clj.first(c) == RETURN) throw fail("RETURN must be the last clause", locOf(c), "SyntaxError", "InvalidClauseComposition");
        if (!(Clj.first(lst) == RETURN || isWrite(lst)))
            throw fail("query must end with RETURN or an updating clause", locOf(lst), "SyntaxError", "InvalidClauseComposition");
    }

    private static Set<Object> collectParams(Object x) {
        if (vecp(x) && Clj.first(x) == PARAM) {
            Set<Object> s = new LinkedHashSet<>();
            s.add(Clj.second(x));
            return s;
        }
        Set<Object> s = new LinkedHashSet<>();
        if (isColl(x)) for (Object c : collSeq(x)) s.addAll(collectParams(c));
        return s;
    }

    /**
     * 单独一条 CALL：不带 YIELD 或 YIELD * ⇒ 过程的全部输出列；YIELD a, b AS c ⇒ 这几列（等同后面跟一个 RETURN）；
     * 不带括号 ⇒ 参数取同名的查询参数；VOID 过程 ⇒ 没有行。
     */
    private static List<Object> standaloneCall(List<Object> clauses) {
        if (clauses.size() == 1 && Clj.first(clauses.getFirst()) == CALL_PROC) {
            Object c = clauses.getFirst();
            Object nm = n(c, 1), args = n(c, 2), yields = n(c, 3), where = n(c, 4), loc = n(c, 5);
            Object sig = procSig(nm, loc);
            List<Object> inputs = L(Clj.get(sig, Procs.INPUTS)), outputs = L(Clj.get(sig, Procs.OUTPUTS));
            List<Object> cols = new ArrayList<>();
            for (Object o : outputs) cols.add(Clj.get(o, Procs.NAME));
            if (args == null) {
                List<Object> as = new ArrayList<>();
                for (Object in : inputs) as.add(vec(PARAM, Clj.get(in, Procs.NAME), loc));
                args = as;
            }
            Object pairs;
            if (yields == null || yields == STAR) {
                List<Object> ps = new ArrayList<>();
                for (Object col : cols) {
                    List<Object> v = vec(VAR, col, loc);
                    ps.add(vec(v, v));
                }
                pairs = ps;
            } else pairs = yields;
            Object ret;
            if (cols.isEmpty()) ret = vec(RETURN, false, vec(ITEMS, vec()), vec(), null, vec(LIT, 0L), loc);
            else {
                List<Object> its = new ArrayList<>();
                for (Object p : L(pairs)) its.add(vec(ITEM, vec(VAR, Clj.second(Clj.second(p)), loc), null));
                ret = vec(RETURN, false, vec(ITEMS, its), vec(), null, null, loc);
            }
            return vec(vec(CALL_PROC, nm, args, pairs, where, loc), ret);
        }
        return clauses;
    }

    /** AST ⇒ {:query 规范化子句 :writes? :params}；UNION ⇒ {:union {:all? 布尔 :parts [分析结果 …]} :writes? :params}。 */
    @SuppressWarnings("unchecked")
    static Object analyze(Object ast) {
        if (Clj.first(ast) == UNION) {
            List<Object> alls = L(n(ast, 1));
            List<Object> parts = L(n(ast, 2));
            Object loc = n(ast, 3);
            if (new LinkedHashSet<>(alls).size() > 1)
                throw fail("Invalid combination of UNION and UNION ALL", loc, "SyntaxError", "InvalidClauseComposition");
            List<Object> as = new ArrayList<>();
            for (Object p : parts) as.add(analyzeQuery(p));
            List<Object> colsList = new ArrayList<>();
            for (Object a : as) {
                Object cols = unionCols(a);
                if (cols == null) throw fail("all parts of a UNION must end with RETURN", loc, "SyntaxError", "InvalidClauseComposition");
                colsList.add(cols);
            }
            if (new java.util.HashSet<>(colsList).size() > 1)
                throw fail("all sub queries in a UNION must have the same column names", loc, "SyntaxError", "DifferentColumnsInUnion");
            boolean writes = false;
            Set<Object> params = new LinkedHashSet<>();
            for (Object a : as) {
                if (truthy(Clj.get(a, WRITES))) writes = true;
                params.addAll((Collection<Object>) Clj.get(a, PARAMS));
            }
            return Clj.map(UNION, Clj.map(ALL_Q, alls.isEmpty() ? null : alls.getFirst(), PARTS, as), WRITES, writes, PARAMS, params);
        }
        return analyzeQuery(ast);
    }

    private static Object unionCols(Object a) {
        Object c = Clj.last(Clj.get(a, QUERY));
        if (Clj.first(c) != RETURN) return null;
        List<Object> cols = new ArrayList<>();
        for (Object it : L(n(c, 2))) cols.add(Clj.first(it));
        return cols;
    }

    private static Object analyzeQuery(Object q) {
        List<Object> clauses = standaloneCall(L(n(q, 1)));
        checkOrder(clauses);
        List<Object> out = new ArrayList<>();
        St st = new St(List.of(), 0, new HashMap<>());
        for (Object c : clauses) {
            R2 r = clause(c, st);
            out.add(r.v());
            st = r.st();
        }
        boolean writes = false;
        for (Object c : clauses) if (isWrite(c)) writes = true;
        return Clj.map(QUERY, out, WRITES, writes, PARAMS, collectParams(clauses));
    }
}
