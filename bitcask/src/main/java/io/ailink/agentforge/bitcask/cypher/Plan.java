package io.ailink.agentforge.bitcask.cypher;

import static io.ailink.agentforge.bitcask.cypher.Clj.kw;
import static io.ailink.agentforge.bitcask.cypher.Clj.vec;

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
import java.util.function.Function;

/**
 * 逻辑计划（clj {@code cypher.plan}；Q-2，对照 bitcask（Erlang）{@code bitcask_plan}）。与 Cypher 语法无关：输入是
 * {@link Sema#analyze} 规范化后的子句，输出一条算子流水线 {@code {:ops [算子] :columns [列名]}}。执行时从「一行空 map」开始依次经过
 * 每个算子（取点算子对每个输入行各扫一遍，所以互不连通的模式就是嵌套循环）。
 * <p>算子：
 * <pre>
 *   [:node-by-id v e]（e 可为 [:id-list e]；第四项 :nocheck = 紧跟从 v 出发的一跳展开，省掉存在性点读）
 *   [:label-scan v 标签] / [:label-scan v 标签 {:push 下推条件 :fetch 后面要不要顶点数据}]
 *   [:all-nodes-scan v] / [:all-nodes-scan v 下推条件]（有下推时只扫 n 文档）
 *   [:fts-seek v 属性 qe] [:vector-range v qe te 标签] [:vector-topk v qe 标签 属性 合取项 skip limit]
 *   [:node-check v 标签 属性] [:expand from r 类型 方向 to into? 已绑定关系] [:var-expand from r 类型 方向 下限 上限 to into? 已绑定关系]
 *   [:rel-check r 属性] [:shortest-path p from r 类型 方向 下限 上限 to] [:all-shortest-paths …同上]（每条最短路一行）
 *   [:build-path p 起点变量 [[关系变量 节点变量] …]]（普通命名路径）[:call-proc 过程名 [[列 变量] …]]
 *   [:search-seek v 函数名 [参数e]]（fts.phrase / near / wildcard / fuzzy / fields、search.hybrid 是合取项）
 *   [:merge-pattern [查找子算子] chain on-create on-match]（整条模式的 MERGE：找不到就整条 CREATE）
 *   [:filter e] [:unwind e v] [:project 项 keep?] [:aggregate 项] [:distinct 名] [:sort 键] [:skip e] [:limit e] [:select 名]
 *   [:create chains] [:set 项] [:remove 项] [:delete detach? [e]]
 *   [:optional [子算子] 新变量]（OPTIONAL MATCH：每个输入行跑一遍子流水线；没有结果时交出原行，新变量为 null）
 *   [:merge-node v 标签 属性e on-create on-match] [:merge-rel from r 类型 属性e 方向 to on-create on-match]
 * </pre>
 * 规则（没有统计信息）：每个连通的模式选一个起点——已绑定的变量 > id(v) 条件 > 全文 @@ > 向量（top-k 提示 / 阈值）>
 * 有标签（标签扫描，其余标签做过滤）> 全量扫描；从起点沿模式逐跳展开，另一端已绑定时「展开到」；
 * WHERE 拆成合取项，变量一齐全就立刻过滤。
 * <p>条件下推（对照 Erlang 版第 7.3 节）：起点是标签扫描 / 全量扫描时，节点上其余的标签、内联属性，以及 WHERE 里只涉及
 * 这个变量、另一边是常量 / 参数的比较（= &lt; &lt;= &gt; &gt;=）、IN、IS NOT NULL 收进 push：
 * {@code [:has-label L] [:has-any-label [L …]]}（A|B；!A 不下推）{@code [:cmp 键 :eq|:lt|:le|:gt|:ge e] [:in 键 e] [:exists 键]}。
 * 执行器把它们放宽成引擎 meta 过滤的必要条件交给引擎，原来的过滤照常留着——下推只负责少读，结果不变。
 */
final class Plan {
    private Plan() {
    }

    private static final Kw OP = kw("op"), AND = kw("and"), FILTER = kw("filter"), NAMED_CHAIN = kw("named-chain"), CHAIN = kw("chain"),
            QPP = kw("qpp"), REL = kw("rel"), VAR = kw("var"), FROM = kw("from"), TO = kw("to"), TYPES = kw("types"), PROPS = kw("props"),
            DIR = kw("dir"), MIN = kw("min"), MAX = kw("max"), VARLEN_Q = kw("varlen?"), MN = kw("mn"), MX = kw("mx"), LABELS = kw("labels"),
            KIND = kw("kind"), ALL_SHORTEST = kw("all-shortest"), ALL_SHORTEST_PATHS = kw("all-shortest-paths"),
            SHORTEST_PATH = kw("shortest-path"), PATH = kw("path"), OUT = kw("out"), IN = kw("in"), BOTH = kw("both"),
            HAS_LABEL = kw("has-label"), HAS_ANY_LABEL = kw("has-any-label"), ANY = kw("any"), LABEL_SCAN = kw("label-scan"),
            VECTOR_TOPK = kw("vector-topk"), VECTOR_RANGE = kw("vector-range"), NODE_CHECK = kw("node-check"), CALL = kw("call"),
            EQ = kw("="), LT = kw("<"), LE = kw("<="), GT = kw(">"), GE = kw(">="), ID_LIST = kw("id-list"), SEARCH = kw("search"),
            AT_AT = kw("at-at"), VEC_SIM = kw("<=>"), PROP = kw("prop"), CMP = kw("cmp"), K_EQ = kw("eq"), K_LT = kw("lt"),
            K_LE = kw("le"), K_GT = kw("gt"), K_GE = kw("ge"), K_IN = kw("in"), EXISTS = kw("exists"), IS_NULL = kw("is-null"),
            HAS_LABELS = kw("has-labels"), MAP = kw("map"), SEARCH_SEEK = kw("search-seek"), FTS_SEEK = kw("fts-seek"),
            ALL_NODES_SCAN = kw("all-nodes-scan"), PUSH = kw("push"), FETCH = kw("fetch"), NODE_BY_ID = kw("node-by-id"),
            QPP_EXPAND = kw("qpp-expand"), VAR_EXPAND = kw("var-expand"), EXPAND = kw("expand"), GROUPS = kw("groups"),
            REL_CHECK = kw("rel-check"), SHORTEST = kw("shortest"), ALL = kw("all"), ANY_SHORTEST = kw("any-shortest"),
            SHORTEST_GROUPS = kw("shortest-groups"), PRE = kw("pre"), PREFIX = kw("prefix"), MODE = kw("mode"), FREE = kw("free"),
            BOUND = kw("bound"), REV = kw("rev?"), END_CONDS = kw("end-conds"), STEPS = kw("steps"), SPEC = kw("spec"), RELS = kw("rels"),
            N0 = kw("n0"), INTERNAL = kw("internal"), EXTERNAL = kw("external"), CYCLE = kw("cycle?"), START = kw("start"),
            END = kw("end"), IDS_DIRS = kw("ids-dirs"), PATH_SEARCH = kw("path-search"), BUILD_PATH = kw("build-path"),
            PATH_DEEPEN = kw("path-deepen"), PROJECT = kw("project"), PATH_SELECT = kw("path-select"), RETURN = kw("return"),
            WITH = kw("with"), ASC = kw("asc"), AGGREGATE = kw("aggregate"), DISTINCT = kw("distinct"), SORT = kw("sort"),
            SKIP = kw("skip"), LIMIT = kw("limit"), SELECT = kw("select"), OPTIONAL = kw("optional"), MERGE_PATTERN = kw("merge-pattern"),
            OPS = kw("ops"), COLUMNS = kw("columns"), QUERY = kw("query"), UNION = kw("union"), ALL_Q = kw("all?"), PARTS = kw("parts"),
            MATCH = kw("match"), OPTIONAL_MATCH = kw("optional-match"), CALL_PROC = kw("call-proc"), MERGE_NODE = kw("merge-node"),
            MERGE_REL = kw("merge-rel"), CREATE = kw("create"), SET = kw("set"), REMOVE = kw("remove"), DELETE = kw("delete"),
            UNWIND = kw("unwind"), NOCHECK = kw("nocheck");

    // ── 小工具 ──

    private static Object n(Object v, int i) {
        return Clj.nth(v, i);
    }

    private static Object g(Object m, Kw k) {
        return Clj.get(m, k);
    }

    private static boolean vecp(Object e) {
        return e instanceof List<?>;
    }

    private static boolean truthy(Object x) {
        return Clj.truthy(x);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> L(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> exprVars(Object e) {
        return (Set<Object>) Sema.exprVars(e);
    }

    /** (some #{v} b)：v 为 nil 时为假。 */
    private static boolean has(Collection<?> b, Object v) {
        return v != null && b.contains(v);
    }

    private static List<Object> addBound(List<Object> b, Object v) {
        return has(b, v) ? b : Clj.conj(b, v);
    }

    private static boolean isSubset(Collection<?> vs, Collection<?> b) {
        Set<Object> s = new HashSet<>(b);
        for (Object v : vs) if (!has(s, v)) return false;
        return true;
    }

    private static List<Object> conjuncts(Object e) {
        if (e == null) return vec();
        if (vecp(e) && Clj.first(e) == OP && Clj.second(e) == AND) {
            List<Object> out = conjuncts(n(e, 2));
            out.addAll(conjuncts(n(e, 3)));
            return out;
        }
        return vec(e);
    }

    /** ⇒ [[[:filter c] …] 剩下的合取项]。 */
    private static List<Object>[] readyFilters(List<Object> cs, List<Object> bound) {
        List<Object> ready = new ArrayList<>(), rest = new ArrayList<>();
        for (Object c : cs) {
            if (isSubset(exprVars(c), bound)) ready.add(vec(FILTER, c));
            else rest.add(c);
        }
        @SuppressWarnings("unchecked")
        List<Object>[] r = new List[] {ready, rest};
        return r;
    }

    // ── MATCH ─────────────────────────────────────────────

    private static List<Object> addNode(List<Object> ns, Object node) {
        Object v = n(node, 1), labels = n(node, 2), props = n(node, 3);
        for (int i = 0; i < ns.size(); i++) {
            Map<Object, Object> nd = asMap(ns.get(i));
            if (Objects.equals(v, nd.get(VAR))) {
                List<Object> old = L(nd.get(LABELS));
                Set<Object> oldSet = new HashSet<>(old);
                List<Object> ls = new ArrayList<>(old);
                for (Object l : L(labels)) if (!oldSet.contains(l)) ls.add(l);
                List<Object> ps = L(nd.get(PROPS));
                if (truthy(props)) ps = Clj.conj(ps, props);
                List<Object> out = new ArrayList<>(ns);
                out.set(i, Clj.assoc(nd, LABELS, ls, PROPS, ps));
                return out;
            }
        }
        return Clj.conj(ns, Clj.map(VAR, v, LABELS, Clj.vecOf(L(labels)), PROPS, truthy(props) ? vec(props) : vec()));
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object o) {
        return (Map<Object, Object>) o;
    }

    private static Object nodeVar(Object nd) {
        return n(nd, 1);
    }

    /** ⇒ [节点 关系 最短路]。 */
    private static List<Object>[] collect(List<Object> parts) {
        List<Object> ns = vec(), rs = vec(), ss = vec();
        for (Object part0 : parts) {
            Object part = Clj.first(part0) == NAMED_CHAIN ? n(part0, 2) : part0;
            if (Clj.first(part) == CHAIN) {
                Object n0 = n(part, 1);
                ns = addNode(ns, n0);
                Object prev = nodeVar(n0);
                for (Object step : L(n(part, 2))) {
                    Object rel = Clj.first(step), nd = Clj.second(step);
                    ns = addNode(ns, nd);
                    if (Clj.first(rel) == QPP) {
                        // 量化路径：一段特殊的「关系」，变量是放全部关系的隐藏变量
                        Object spec = Clj.second(rel);
                        rs = Clj.conj(rs, Clj.map(VAR, g(spec, VAR), FROM, prev, TO, nodeVar(nd), QPP, spec, TYPES, vec(), PROPS, vec(), DIR, OUT,
                                MIN, g(spec, MN), MAX, g(spec, MX), VARLEN_Q, true));
                    } else {
                        Object rv = n(rel, 1), types = n(rel, 2), props = n(rel, 3), dir = n(rel, 4), rng = n(rel, 5);
                        rs = Clj.conj(rs, Clj.map(VAR, rv, FROM, prev, TO, nodeVar(nd), TYPES, types, PROPS, truthy(props) ? vec(props) : vec(),
                                DIR, dir, MIN, n(rng, 0), MAX, n(rng, 1), VARLEN_Q, truthy(n(rng, 2))));
                    }
                    prev = nodeVar(nd);
                }
            } else {
                Object kind = n(part, 0), pv = n(part, 1), n0 = n(part, 2), rel = n(part, 3), n1 = n(part, 4);
                Object rv = n(rel, 1), types = n(rel, 2), props = n(rel, 3), dir = n(rel, 4), rng = n(rel, 5);
                ns = addNode(addNode(ns, n0), n1);
                ss = Clj.conj(ss, Clj.map(KIND, kind == ALL_SHORTEST ? ALL_SHORTEST_PATHS : SHORTEST_PATH, PATH, pv, FROM, nodeVar(n0), TO,
                        nodeVar(n1), REL, Clj.map(VAR, rv, TYPES, types, PROPS, truthy(props) ? vec(props) : vec(), DIR, dir, MIN, n(rng, 0),
                                MAX, n(rng, 1))));
            }
        }
        @SuppressWarnings("unchecked")
        List<Object>[] r = new List[] {ns, rs, ss};
        return r;
    }

    private static List<Object> components(List<Object> vars, List<Object> rels) {
        Map<Object, List<Object>> adj = new LinkedHashMap<>();
        for (Object r : rels) {
            Object from = g(r, FROM), to = g(r, TO);
            adj.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            adj.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
        }
        List<Object> comps = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object v : vars) {
            if (seen.contains(v)) continue;
            List<Object> q = vec(v);
            seen.add(v);
            List<Object> acc = vec(v);
            while (!q.isEmpty()) {
                List<Object> nb = new ArrayList<>();
                for (Object x : new LinkedHashSet<>(adj.getOrDefault(q.getFirst(), List.of()))) if (!seen.contains(x)) nb.add(x);
                q = Clj.into(Clj.subvec(q, 1), nb);
                seen.addAll(nb);
                acc.addAll(nb);
            }
            comps.add(acc);
        }
        return comps;
    }

    private static Object flip(Object d) {
        if (d == OUT) return IN;
        if (d == IN) return OUT;
        if (d == BOTH) return BOTH;
        throw new IllegalArgumentException("No matching clause: " + d);
    }

    private static List<Object> plain(Object ls) {
        List<Object> out = new ArrayList<>();
        for (Object l : L(ls)) if (l instanceof String) out.add(l);
        return out;
    }

    /** 标签条件：普通标签 ⇒ :has-label；A|B ⇒ :has-any-label（引擎 contains-any）；!A 不下推。 */
    private static List<Object> labelPush(Object ls) {
        List<Object> out = new ArrayList<>();
        for (Object l : L(ls)) {
            if (l instanceof String) out.add(vec(HAS_LABEL, l));
            else if (Clj.first(l) == ANY && L(Clj.second(l)).stream().allMatch(x -> x instanceof String)) out.add(vec(HAS_ANY_LABEL, Clj.second(l)));
        }
        return out;
    }

    private static List<Object> nodeChecks(Object nd, List<Object> startOps) {
        Object var = g(nd, VAR), labels = g(nd, LABELS), props = g(nd, PROPS);
        Object op = startOps.isEmpty() ? null : startOps.getFirst();
        Set<Object> covered = new HashSet<>();
        Object oh = Clj.first(op);
        if (oh == LABEL_SCAN && Objects.equals(var, n(op, 1))) covered.add(n(op, 2));
        else if (oh == VECTOR_TOPK && Objects.equals(var, n(op, 1)) && !L(n(op, 3)).isEmpty()) covered.add(Clj.first(n(op, 3)));
        else if (oh == VECTOR_RANGE && Objects.equals(var, n(op, 1)) && truthy(n(op, 4))) covered.add(n(op, 4));
        List<Object> need = new ArrayList<>();
        for (Object l : L(labels)) if (!covered.contains(l)) need.add(l);
        if (need.isEmpty() && L(props).isEmpty()) return vec();
        return vec(vec(NODE_CHECK, var, need, props));
    }

    /** 找第一个 f 给出非 nil 的合取项 ⇒ [f 的结果 其余合取项]；没有 ⇒ null。 */
    private static Object[] findConj(Function<Object, Object> f, List<Object> cs) {
        for (int i = 0; i < cs.size(); i++) {
            Object x = f.apply(cs.get(i));
            if (truthy(x)) {
                List<Object> rest = new ArrayList<>(cs.subList(0, i));
                rest.addAll(cs.subList(i + 1, cs.size()));
                return new Object[] {x, rest};
            }
        }
        return null;
    }

    private static Object idOf(Object x) {
        if (vecp(x) && Clj.first(x) == CALL && "id".equals(n(x, 1)) && !truthy(n(x, 2)) && Clj.first(Clj.first(n(x, 3))) == VAR)
            return Clj.second(Clj.first(n(x, 3)));
        return null;
    }

    private static Object idPin(Object e) {
        if (!(vecp(e) && Clj.first(e) == OP)) return null;
        Object op = n(e, 1), a = n(e, 2), b = n(e, 3);
        if (op == EQ) {
            if (truthy(idOf(a))) return vec(idOf(a), b);
            if (truthy(idOf(b))) return vec(idOf(b), a);
            return null;
        }
        if (op == K_IN) return truthy(idOf(a)) ? vec(idOf(a), vec(ID_LIST, b)) : null;
        return null;
    }

    /** fts.phrase(n.text, …) 一类检索函数作合取项 ⇒ [:search v 函数名 其余参数]。 */
    private static Object searchPin(Object e, List<Object> vars, List<Object> b) {
        if (!(vecp(e) && Clj.first(e) == CALL && Sema.SEARCH_FNS.contains(Clj.second(e)))) return null;
        Object nm = n(e, 1);
        List<Object> args = L(n(e, 3));
        Object fst = args.isEmpty() ? null : args.getFirst();
        List<Object> more = args.isEmpty() ? vec() : Clj.subvec(args, 1);
        Object v = null;
        if (Clj.first(fst) == VAR) v = Clj.second(fst);
        else if (Clj.first(fst) == PROP && Clj.first(Clj.second(fst)) == VAR) v = Clj.second(Clj.second(fst));
        Set<Object> mv = new HashSet<>();
        for (Object m : more) mv.addAll(exprVars(m));
        if (v != null && has(vars, v) && isSubset(mv, b)) return vec(SEARCH, v, nm, more);
        return null;
    }

    private static Object ftsPin(Object e, List<Object> vars, List<Object> b) {
        if (!(vecp(e) && Clj.first(e) == OP && Clj.second(e) == AT_AT)) return null;
        Object lhs = n(e, 2), qe = n(e, 3);
        if (Clj.first(lhs) == PROP && Clj.first(Clj.second(lhs)) == VAR) {
            Object v = Clj.second(Clj.second(lhs));
            if (has(vars, v) && isSubset(exprVars(qe), b)) return vec(v, n(lhs, 2), qe);
        }
        return null;
    }

    private static List<Object> embeddingOf(Object x) {
        if (vecp(x) && Clj.first(x) == OP && Clj.second(x) == VEC_SIM) {
            Object lhs = n(x, 2), qe = n(x, 3);
            if (vecp(lhs) && Clj.first(lhs) == PROP && Clj.first(Clj.second(lhs)) == VAR && "embedding".equals(n(lhs, 2)))
                return vec(Clj.second(Clj.second(lhs)), qe);
        }
        return null;
    }

    private static Object vrangePin(Object e, List<Object> vars, List<Object> b) {
        if (vecp(e) && Clj.first(e) == OP && (Clj.second(e) == LT || Clj.second(e) == LE)) {
            List<Object> vq = embeddingOf(n(e, 2));
            if (vq != null) {
                Object v = vq.get(0), qe = vq.get(1), te = n(e, 3);
                Set<Object> vs = new HashSet<>(exprVars(qe));
                vs.addAll(exprVars(te));
                if (has(vars, v) && isSubset(vs, b)) return vec(v, qe, te);
            }
        }
        return null;
    }

    private static boolean isPushableKey(Object kk) {
        return kk instanceof String s && !s.equals("text") && !s.equals("embedding");
    }

    private static boolean isConst(Object e) {
        return exprVars(e).isEmpty();
    }

    private static Object propOf(Object e, Object v) {
        if (vecp(e) && Clj.first(e) == PROP && vec(VAR, v).equals(Clj.second(e))) return n(e, 2);
        return null;
    }

    private static Kw relOp(Object op) {
        if (op == LT) return K_LT;
        if (op == LE) return K_LE;
        if (op == GT) return K_GT;
        if (op == GE) return K_GE;
        return null;
    }

    private static Kw flipCmp(Kw k) {
        if (k == K_LT) return K_GT;
        if (k == K_LE) return K_GE;
        if (k == K_GT) return K_LT;
        if (k == K_GE) return K_LE;
        return null;
    }

    private static List<Object> conjPush(Object c, Object v) {
        if (!vecp(c)) return vec();
        Object hd = Clj.first(c);
        if (hd == OP) {
            Object op = n(c, 1), a = n(c, 2), b = n(c, 3);
            Object pa = propOf(a, v), pb = propOf(b, v);
            Kw r = relOp(op);
            if (op == EQ && truthy(pa) && isConst(b) && isPushableKey(pa)) return vec(vec(CMP, pa, K_EQ, b));
            if (op == EQ && truthy(pb) && isConst(a) && isPushableKey(pb)) return vec(vec(CMP, pb, K_EQ, a));
            if (r != null && truthy(pa) && isConst(b) && isPushableKey(pa)) return vec(vec(CMP, pa, r, b));
            if (r != null && truthy(pb) && isConst(a) && isPushableKey(pb)) return vec(vec(CMP, pb, flipCmp(r), a));
            if (op == K_IN && truthy(pa) && isConst(b) && isPushableKey(pa)) return vec(vec(K_IN, pa, b));
            return vec();
        }
        if (hd == IS_NULL && Boolean.FALSE.equals(n(c, 2)) && truthy(propOf(n(c, 1), v)) && isPushableKey(propOf(n(c, 1), v)))
            return vec(vec(EXISTS, propOf(n(c, 1), v)));
        if (hd == HAS_LABELS && vec(VAR, v).equals(n(c, 1))) return labelPush(n(c, 2));
        return vec();
    }

    /** 节点上能交给引擎的条件：其余标签、内联属性（字面映射）、只涉及这个变量与常量 / 参数的合取项。正文与向量不在 meta 里，不下推。 */
    private static List<Object> pushdown(Object nd, Object otherLabels, List<Object> cs) {
        Object v = g(nd, VAR);
        List<Object> out = labelPush(otherLabels);
        for (Object p : L(g(nd, PROPS))) {
            if (Clj.first(p) == MAP) {
                for (Object kv : L(Clj.second(p))) {
                    Object kk = Clj.first(kv), e = Clj.second(kv);
                    if (isPushableKey(kk) && isConst(e)) out.add(vec(CMP, kk, K_EQ, e));
                }
            }
        }
        for (Object c : cs) out.addAll(conjPush(c, v));
        return out;
    }

    private static Object byVar(List<Object> nodes, Object v) {
        for (Object nd : nodes) if (Objects.equals(v, g(nd, VAR))) return nd;
        return null;
    }

    /** ⇒ [起点算子 起点节点 剩下的合取项]。 */
    private static Object[] startSearch(List<Object> compNodes, List<Object> b, List<Object> cs, Object hint) {
        List<Object> vars = new ArrayList<>();
        for (Object nd : compNodes) vars.add(g(nd, VAR));
        Object[] found = findConj(c -> {
            Object x = ftsPin(c, vars, b);
            return truthy(x) ? x : searchPin(c, vars, b);
        }, cs);
        if (found != null) {
            Object x = found[0];
            @SuppressWarnings("unchecked")
            List<Object> cs2 = (List<Object>) found[1];
            if (Clj.first(x) == SEARCH) {
                Object v = n(x, 1);
                return new Object[] {vec(vec(SEARCH_SEEK, v, n(x, 2), n(x, 3))), byVar(compNodes, v), cs2};
            }
            Object v = n(x, 0);
            return new Object[] {vec(vec(FTS_SEEK, v, n(x, 1), n(x, 2))), byVar(compNodes, v), cs2};
        }
        if (Clj.first(hint) == VECTOR_TOPK && vars.equals(vec(Clj.second(hint)))) {
            Object v = n(hint, 1), qe = n(hint, 2), sk = n(hint, 3), li = n(hint, 4);
            Object nd = compNodes.getFirst();
            List<Object> bv = Clj.conj(b, v);
            List<Object> mine = new ArrayList<>();
            for (Object c : cs) if (isSubset(exprVars(c), bv)) mine.add(c);
            return new Object[] {vec(vec(VECTOR_TOPK, v, qe, plain(g(nd, LABELS)), g(nd, PROPS), mine, sk, li)), nd, cs};
        }
        Object[] vr = findConj(c -> vrangePin(c, vars, b), cs);
        if (vr != null) {
            Object v = n(vr[0], 0), qe = n(vr[0], 1), te = n(vr[0], 2);
            Object nd = byVar(compNodes, v);
            // 阈值条件留作过滤（检索只给候选）
            return new Object[] {vec(vec(VECTOR_RANGE, v, qe, te, Clj.first(plain(g(nd, LABELS))))), nd, cs};
        }
        Object withLabel = null;
        for (Object nd : compNodes) if (!plain(g(nd, LABELS)).isEmpty()) {
            withLabel = nd;
            break;
        }
        if (withLabel != null) {
            Object nd = withLabel;
            Object l0 = plain(g(nd, LABELS)).getFirst();
            // (split-with #(not= l0 %) labels)：去掉第一个 l0
            List<Object> ls = L(g(nd, LABELS));
            List<Object> others = new ArrayList<>();
            boolean dropped = false;
            for (Object l : ls) {
                if (!dropped && Objects.equals(l0, l)) {
                    dropped = true;
                    continue;
                }
                others.add(l);
            }
            return new Object[] {vec(vec(LABEL_SCAN, g(nd, VAR), l0, Clj.map(PUSH, pushdown(nd, others, cs), FETCH, false))), nd, cs};
        }
        Object nd = compNodes.getFirst();
        List<Object> push = pushdown(nd, g(nd, LABELS), cs);
        return new Object[] {vec(push.isEmpty() ? vec(ALL_NODES_SCAN, g(nd, VAR)) : vec(ALL_NODES_SCAN, g(nd, VAR), push)), nd, cs};
    }

    private static Object[] start(List<Object> compNodes, List<Object> b, List<Object> cs, Object hint) {
        for (Object nd : compNodes) if (has(b, g(nd, VAR))) return new Object[] {vec(), nd, cs};
        Set<Object> vars = new HashSet<>();
        for (Object nd : compNodes) vars.add(g(nd, VAR));
        Object[] found = findConj(c -> {
            Object ve = idPin(c);
            if (ve == null) return null;
            Object v = n(ve, 0), e = n(ve, 1);
            if (has(vars, v) && isSubset(exprVars(Clj.first(e) == ID_LIST ? Clj.second(e) : e), b)) return vec(v, e);
            return null;
        }, cs);
        if (found != null) {
            Object v = n(found[0], 0), e = n(found[0], 1);
            return new Object[] {vec(vec(NODE_BY_ID, v, e)), byVar(compNodes, v), found[1]};
        }
        return startSearch(compNodes, b, cs, hint);
    }

    private static Object[] pickRel(List<Object> rels, List<Object> b) {
        for (int i = 0; i < rels.size(); i++) {
            Object r = rels.get(i);
            if (has(b, g(r, FROM)) || has(b, g(r, TO))) {
                List<Object> rest = new ArrayList<>(rels.subList(0, i));
                rest.addAll(rels.subList(i + 1, rels.size()));
                return new Object[] {r, rest};
            }
        }
        return null;
    }

    /** ⇒ [算子 已绑定 剩下的合取项 here]。 */
    @SuppressWarnings("unchecked")
    private static Object[] expandAll(List<Object> rels, List<Object> nodes, List<Object> b, List<Object> cs, List<Object> here,
            List<Object> ops) {
        while (!rels.isEmpty()) {
            Object[] pr = pickRel(rels, b);
            // clj：ex-info（门面转成 plan 阶段的 SemanticError）；这里直接抛同样的错
            if (pr == null) throw CypherError.fail("plan", "internal: disconnected pattern component", null, Arrays.asList("SemanticError", null));
            Object r = pr[0];
            List<Object> rest = (List<Object>) pr[1];
            boolean rev = !has(b, g(r, FROM));
            Object from = rev ? g(r, TO) : g(r, FROM), to = rev ? g(r, FROM) : g(r, TO), dir = rev ? flip(g(r, DIR)) : g(r, DIR);
            boolean into = has(b, to);
            // 关系变量已绑定（前面的子句绑的）⇒ 展开时只认这一条 / 这一串
            boolean boundRel = has(b, g(r, VAR));
            Object op;
            if (truthy(g(r, QPP))) op = vec(QPP_EXPAND, from, g(r, VAR), g(r, QPP), to, into, here, rev);
            else if (truthy(g(r, VARLEN_Q)))
                // 反着展开（从模式右端出发）时关系列表要倒回模式的顺序
                op = vec(VAR_EXPAND, from, g(r, VAR), g(r, TYPES), dir, g(r, MIN), g(r, MAX), to, into, here, rev, boundRel);
            else op = vec(EXPAND, from, g(r, VAR), g(r, TYPES), dir, to, into, here, boundRel);
            List<Object> b2 = addBound(addBound(b, g(r, VAR)), to);
            for (Object gv : L(g(g(r, QPP), GROUPS))) b2 = addBound(b2, gv);
            List<Object> relChecks = new ArrayList<>();
            for (Object p : L(g(r, PROPS))) relChecks.add(vec(REL_CHECK, g(r, VAR), p));
            List<Object> nodeCks = vec();
            if (!into) {
                Object tn = null;
                for (Object nd : nodes) if (Objects.equals(to, g(nd, VAR))) {
                    tn = nd;
                    break;
                }
                nodeCks = nodeChecks(tn, vec());
            }
            List<Object>[] rf = readyFilters(cs, b2);
            rels = rest;
            b = b2;
            cs = rf[1];
            here = Clj.conj(here, g(r, VAR));
            ops = Clj.conj(ops, op);
            ops.addAll(relChecks);
            ops.addAll(nodeCks);
            ops.addAll(rf[0]);
        }
        return new Object[] {ops, b, cs, here};
    }

    @SuppressWarnings("unchecked")
    private static Object[] planComponent(List<Object> comp, List<Object> nodes, List<Object> rels, List<Object> b, List<Object> cs,
            List<Object> here, Object hint) {
        Set<Object> compSet = new HashSet<>(comp);
        List<Object> compNodes = new ArrayList<>(), compRels = new ArrayList<>();
        for (Object nd : nodes) if (has(compSet, g(nd, VAR))) compNodes.add(nd);
        for (Object r : rels) if (has(compSet, g(r, FROM))) compRels.add(r);
        Object[] s = start(compNodes, b, cs, hint);
        List<Object> startOps = (List<Object>) s[0];
        Object startN = s[1];
        List<Object> cs1 = (List<Object>) s[2];
        boolean boundBefore = has(b, g(startN, VAR));
        List<Object> b1 = addBound(b, g(startN, VAR));
        List<Object> checks = boundBefore ? vec() : nodeChecks(startN, startOps);
        List<Object>[] rf = readyFilters(cs1, b1);
        List<Object> ops = new ArrayList<>(startOps);
        ops.addAll(checks);
        ops.addAll(rf[0]);
        return expandAll(compRels, compNodes, b1, rf[1], here, ops);
    }

    private static final Set<Object> SEARCHABLE_KINDS = Set.of(SHORTEST, ANY, ANY_SHORTEST, ALL_SHORTEST, SHORTEST_GROUPS);

    /** 挑选前的条件里只引用「前面子句的变量 + 自由终点」的合取项（D-47）：先用在可达点上界上。 */
    private static List<Object> endConds(List<Object> cs, List<Object> bound, Object end) {
        List<Object> be = Clj.conj(bound, end);
        List<Object> out = new ArrayList<>();
        for (Object c : cs) {
            Set<Object> vs = exprVars(c);
            if (has(vs, end) && isSubset(vs, be)) out.add(c);
        }
        return out;
    }

    private static boolean isSearchableKind(Object prefix) {
        Object k = g(prefix, KIND);
        return k != null && SEARCHABLE_KINDS.contains(k);
    }

    private static boolean touches(List<Object> rels, Object v) {
        for (Object r : rels) if (Objects.equals(v, g(r, FROM)) || Objects.equals(v, g(r, TO))) return true;
        return false;
    }

    private static boolean plainNode(Object nd) {
        return L(g(nd, LABELS)).isEmpty() && L(g(nd, PROPS)).isEmpty();
    }

    /**
     * 能边搜边挑的带前缀 path pattern（D-43、D-44）：单段变长关系或单段量化路径，挑选前的条件只引用这条路径、它的关系 / 组变量、两端和
     * 前面子句的变量 ⇒ {路径变量 {:rel 关系 :prefix 前缀 :mode :bound|:free :rev? 布尔 :free 自由端 :end-conds 只引用自由终点的条件}}。
     * 自由端 = 没绑定、没标签 / 属性、不连别的关系的一端：搜索把它绑上（:free）；两端都不自由 ⇒ 两端各自先找出来（:bound）。
     */
    private static Map<Object, Object> pathSearches(List<Object> parts, List<Object> nodes, List<Object> rels, List<Object> cs,
            List<Object> bound) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Object part : parts) {
            if (Clj.first(part) != NAMED_CHAIN) continue;
            Object pv = n(part, 1), chain = n(part, 2), prefix = n(part, 3);
            Object kind0 = n(chain, 0);
            List<Object> steps = L(n(chain, 2));
            Object rel = steps.isEmpty() ? null : Clj.first(steps.getFirst());
            if (!(truthy(prefix) && isSearchableKind(prefix) && kind0 == CHAIN && steps.size() == 1
                    && (Clj.first(rel) == QPP || (Clj.first(rel) == REL && truthy(Clj.nth(n(rel, 5), 2, null))))))
                continue;
            Object rv = Clj.first(rel) == QPP ? g(Clj.second(rel), VAR) : Clj.second(rel);
            Object r = byVar(rels, rv);
            List<Object> groups = L(g(g(r, QPP), GROUPS));
            Set<Object> allowed = new HashSet<>(bound);
            allowed.addAll(Arrays.asList(pv, g(r, VAR), g(r, FROM), g(r, TO)));
            allowed.addAll(groups);
            Set<Object> own = new HashSet<>(Arrays.asList(pv, g(r, VAR)));
            own.addAll(groups);
            List<Object> others = new ArrayList<>();
            for (Object x : rels) if (!Objects.equals(rv, g(x, VAR))) others.add(x);
            Object rr = r;
            java.util.function.Predicate<Object> free = v -> !has(bound, v) && !Objects.equals(g(rr, FROM), g(rr, TO))
                    && plainNode(byVar(nodes, v)) && !touches(others, v);
            if (!(r != null && !has(bound, g(r, VAR)))) continue;
            boolean ok = true;
            for (Object c : cs) {
                Set<Object> vs = exprVars(c);
                boolean mentions = false;
                for (Object v : vs) if (has(own, v)) mentions = true;
                if (mentions && !isSubset(vs, allowed)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            Object info;
            if (free.test(g(r, TO)))
                info = Clj.map(REL, r, PREFIX, prefix, MODE, FREE, REV, false, FREE, g(r, TO), END_CONDS, endConds(cs, bound, g(r, TO)));
            else if (free.test(g(r, FROM)))
                info = Clj.map(REL, r, PREFIX, prefix, MODE, FREE, REV, true, FREE, g(r, FROM), END_CONDS, endConds(cs, bound, g(r, FROM)));
            else info = Clj.map(REL, r, PREFIX, prefix, MODE, BOUND, REV, false);
            out.put(pv, info);
        }
        return out;
    }

    /** 量化路径里全部关系步的 [类型 方向]（嵌套的也算）。 */
    private static List<Object> specIdsDirs(Object spec) {
        List<Object> out = new ArrayList<>();
        for (Object st : L(g(spec, STEPS))) {
            if (g(st, KIND) == QPP) out.addAll(specIdsDirs(g(st, SPEC)));
            else out.add(vec(g(st, TYPES), g(st, DIR)));
        }
        return out;
    }

    private static long mulSafe(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return (double) a * (double) b > 9.0E18 ? Long.MAX_VALUE : a * b;
    }

    private static long lng(Object x) {
        return ((Number) x).longValue();
    }

    /** 量化路径一次迭代的跳数 [下限 上限]（嵌套的量化路径按乘积；上限可能是 Long/MAX_VALUE）。 */
    private static long[] specHopBounds(Object spec) {
        long lo = 1, hi = 1;
        for (Object st : L(g(spec, STEPS))) {
            Object k = g(st, KIND);
            long l, h;
            if (k == REL) {
                l = 1;
                h = 1;
            } else if (k == kw("varlen")) {
                l = lng(g(st, MN));
                h = g(st, MX) == null ? Long.MAX_VALUE : lng(g(st, MX));
            } else if (k == QPP) {
                long[] lh = specHopBounds(g(st, SPEC));
                l = lh[0];
                h = lh[1];
            } else throw new IllegalArgumentException("No matching clause: " + k);
            lo = mulSafe(lo, l);
            hi = mulSafe(hi, h);
        }
        return new long[] {lo, hi};
    }

    /** 链上一步的跳数 [下限 上限]。 */
    private static long[] hopBounds(Object r) {
        if (truthy(g(r, QPP))) {
            long[] lh = specHopBounds(g(r, QPP));
            return new long[] {mulSafe(lng(g(g(r, QPP), MN)), lh[0]), mulSafe(lng(g(g(r, QPP), MX)), lh[1])};
        }
        if (truthy(g(r, VARLEN_Q)))
            return new long[] {g(r, MIN) == null ? 1 : lng(g(r, MIN)), g(r, MAX) == null ? Long.MAX_VALUE : lng(g(r, MAX))};
        return new long[] {1, 1};
    }

    /**
     * 能逐轮加深的带前缀多段 path pattern（D-45、D-47）：链上的关系变量都不重复、中间节点不互相重复也不等于首尾、关系都没绑定、挑选前的条件
     * 只引用这条链与前面子句的变量 ⇒ {路径变量 {:rels 链上的关系 :steps [[关系变量 节点变量] …] :n0 起点变量 :mode :bound|:free :start 搜索起点
     * :end 搜索终点 :free 自由端 :internal 中间节点 :external 其中在别的模式里出现 / 前面已绑定的（留给连通分量先绑上，展开到它时收口）
     * :cycle? 首尾同一变量}}。
     * 中间节点在别的模式里出现（D-47）：连通分量都排在 :path-deepen 之前，它一定先绑上。首尾同一变量（环路）：:bound，终点 = 起点。
     */
    private static Map<Object, Object> pathDeepens(List<Object> parts, List<Object> nodes, List<Object> rels, List<Object> cs,
            List<Object> bound, Map<Object, Object> taken) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Object part : parts) {
            if (!(Clj.first(part) == NAMED_CHAIN && !truthy(taken.get(Clj.second(part))))) continue;
            Object pv = n(part, 1), chain = n(part, 2), prefix = n(part, 3);
            Object kind0 = n(chain, 0), n0 = n(chain, 1);
            List<Object> steps = L(n(chain, 2));
            List<Object> ch = L(chain);
            boolean extraEmpty = true;
            for (int i = 3; i < ch.size(); i++) if (!Clj.isEmpty(ch.get(i))) extraEmpty = false;
            if (!(truthy(prefix) && isSearchableKind(prefix) && kind0 == CHAIN && steps.size() > 1 && extraEmpty)) continue;
            List<Object> rvs = new ArrayList<>();
            for (Object s : steps) {
                Object rel = Clj.first(s);
                rvs.add(Clj.first(rel) == QPP ? g(Clj.second(rel), VAR) : Clj.second(rel));
            }
            List<Object> rs = new ArrayList<>();
            for (Object rv : rvs) rs.add(byVar(rels, rv));
            List<Object> nvs = vec(nodeVar(n0));
            for (Object s : steps) nvs.add(nodeVar(Clj.second(s)));
            Object s0 = nvs.getFirst(), e = nvs.getLast();
            List<Object> internal = Clj.subvec(nvs, 1, nvs.size() - 1);
            boolean cycle = Objects.equals(s0, e);
            Set<Object> rset = new HashSet<>(rvs);
            List<Object> others = new ArrayList<>();
            for (Object x : rels) if (!has(rset, g(x, VAR))) others.add(x);
            List<Object> external = new ArrayList<>();
            for (Object v : internal) if (has(bound, v) || touches(others, v)) external.add(v);
            List<Object> groups = new ArrayList<>();
            for (Object r : rs) groups.addAll(L(g(g(r, QPP), GROUPS)));
            Set<Object> own = new HashSet<>();
            own.add(pv);
            own.addAll(rvs);
            own.addAll(groups);
            own.addAll(internal);
            Set<Object> allowed = new HashSet<>(bound);
            allowed.addAll(own);
            allowed.addAll(nvs);
            java.util.function.Predicate<Object> free = v -> !has(bound, v) && plainNode(byVar(nodes, v)) && !touches(others, v);
            boolean ok = true;
            for (Object r : rs) if (r == null) ok = false;
            if (ok && new HashSet<>(internal).size() != internal.size()) ok = false;
            if (ok && has(internal, s0)) ok = false;
            if (ok) for (Object rv : rvs) if (has(bound, rv)) ok = false;
            if (ok) for (Object c : cs) {
                Set<Object> vs = exprVars(c);
                boolean mentions = false;
                for (Object v : vs) if (has(own, v)) mentions = true;
                if (mentions && !isSubset(vs, allowed)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;
            List<Object> stepPairs = new ArrayList<>();
            for (int i = 0; i < rvs.size(); i++) stepPairs.add(vec(rvs.get(i), nvs.get(i + 1)));
            Map<Object, Object> base = Clj.map(RELS, rs, STEPS, stepPairs, N0, s0, INTERNAL, internal, EXTERNAL, external, CYCLE, cycle,
                    PREFIX, prefix);
            Object info;
            if (cycle) info = Clj.assoc(base, MODE, BOUND, START, s0, END, s0);
            else if (free.test(e)) info = Clj.assoc(base, MODE, FREE, START, s0, END, e, FREE, e, END_CONDS, endConds(cs, bound, e));
            else if (free.test(s0)) info = Clj.assoc(base, MODE, FREE, START, e, END, s0, FREE, s0, END_CONDS, endConds(cs, bound, s0));
            else info = Clj.assoc(base, MODE, BOUND, START, s0, END, e);
            out.put(pv, info);
        }
        return out;
    }

    private static Object relName(Object rel) {
        return Clj.first(rel) == QPP ? g(Clj.second(rel), VAR) : Clj.second(rel);
    }

    /** ⇒ [算子 已绑定]。 */
    @SuppressWarnings("unchecked")
    private static Object[] matchOps(List<Object> parts, Object where, List<Object> bound, Object hint, Object post) {
        List<Object>[] col = collect(parts);
        List<Object> nodes = col[0], rels = col[1], shorts = col[2];
        // 带前缀的 path pattern 的元素谓词：和 WHERE 一样尽早过滤（都在挑选之前）
        List<Object> cs = conjuncts(where);
        for (Object part : parts) if (Clj.first(part) == NAMED_CHAIN) cs.addAll(conjuncts(g(Clj.nth(part, 3, null), PRE)));
        Map<Object, Object> searches = pathSearches(parts, nodes, rels, cs, bound);
        Map<Object, Object> deepens = pathDeepens(parts, nodes, rels, cs, bound, searches);
        Set<Object> searchRels = new HashSet<>();
        for (Object s : searches.values()) searchRels.add(g(g(s, REL), VAR));
        for (Object d : deepens.values()) for (Object r : L(g(d, RELS))) searchRels.add(g(r, VAR));
        // 边搜边挑的关系不参与连通分量：两端各自先找出来，再在两点之间按长度分层地搜
        List<Object> crels = new ArrayList<>();
        for (Object r : rels) if (!searchRels.contains(g(r, VAR))) crels.add(r);
        // 首尾同一变量的环路：这个变量照常由连通分量找出来（不是自由端）
        Set<Object> freeEnds = new HashSet<>();
        for (Object s : searches.values()) if (g(s, FREE) != null) freeEnds.add(g(s, FREE));
        for (Object d : deepens.values()) if (g(d, FREE) != null) freeEnds.add(g(d, FREE));
        for (Object d : deepens.values()) {
            Set<Object> ext = new HashSet<>(L(g(d, EXTERNAL)));
            for (Object v : L(g(d, INTERNAL))) if (!ext.contains(v)) freeEnds.add(v);
        }
        List<Object> compVars = new ArrayList<>();
        for (Object nd : nodes) if (!freeEnds.contains(g(nd, VAR))) compVars.add(g(nd, VAR));
        List<Object> comps = components(compVars, crels);
        // 已绑定的节点：照常查标签 / 属性；孤立的（模式里只有它自己，没有关系从它展开）还要查它确实是节点
        // （WITH / UNWIND 来的值，不是节点就匹配不上）
        List<Object> ops = new ArrayList<>();
        for (Object nd : nodes) {
            if (!has(bound, g(nd, VAR))) continue;
            List<Object> cks = nodeChecks(nd, vec());
            if (cks.isEmpty() && !touches(crels, g(nd, VAR))) ops.add(vec(NODE_CHECK, g(nd, VAR), vec(), null));
            else ops.addAll(cks);
        }
        List<Object> b = bound;
        List<Object> here = vec();
        for (Object comp : comps) {
            Object[] r = planComponent(L(comp), nodes, crels, b, cs, here, hint);
            ops = Clj.into(ops, (List<Object>) r[0]);
            b = (List<Object>) r[1];
            cs = (List<Object>) r[2];
            here = (List<Object>) r[3];
        }
        // [:path-search 路径变量 起点 关系 类型 方向 下限 上限 终点 已绑定关系 前缀 每条候选上跑的算子 :bound|:free 反向? 量化路径]
        for (Map.Entry<Object, Object> en : searches.entrySet()) {
            Object pv = en.getKey(), info = en.getValue();
            Object r = g(info, REL), prefix = g(info, PREFIX), mode = g(info, MODE);
            boolean rev = truthy(g(info, REV));
            Object s = rev ? g(r, TO) : g(r, FROM), e = rev ? g(r, FROM) : g(r, TO);
            List<Object> b2 = b;
            for (Object v : Clj.into(vec(g(r, VAR), pv, e), L(g(g(r, QPP), GROUPS)))) b2 = addBound(b2, v);
            List<Object>[] rf = readyFilters(cs, b2);
            List<Object> inner = new ArrayList<>();
            for (Object p : L(g(r, PROPS))) inner.add(vec(REL_CHECK, g(r, VAR), p));
            inner.add(vec(BUILD_PATH, pv, g(r, FROM), vec(vec(g(r, VAR), g(r, TO)))));
            inner.addAll(rf[0]);
            ops = Clj.conj(ops, vec(PATH_SEARCH, pv, s, g(r, VAR), g(r, TYPES), rev ? flip(g(r, DIR)) : g(r, DIR), g(r, MIN), g(r, MAX), e,
                    Clj.vecOf(here), prefix, inner, mode, rev, g(r, QPP), mode == FREE ? Clj.vecOf(L(g(info, END_CONDS))) : null));
            b = b2;
            cs = rf[1];
        }
        // [:path-deepen 路径变量 搜索起点 搜索终点 :bound|:free 前缀 每轮跑的算子 链上的关系变量 [搜索顺序上每一步 {:ids-dirs :mn :mx}] 跳数上限 模式起点变量 只引用自由终点的条件]
        for (Map.Entry<Object, Object> en : deepens.entrySet()) {
            Object pv = en.getKey(), info = en.getValue();
            List<Object> drels = L(g(info, RELS));
            Object steps = g(info, STEPS), n0 = g(info, N0), mode = g(info, MODE), st = g(info, START), end = g(info, END),
                    prefix = g(info, PREFIX);
            Object[] ea = expandAll(drels, nodes, addBound(b, st), cs, here, vec());
            List<Object> eops = (List<Object>) ea[0];
            List<Object> b2 = (List<Object>) ea[1];
            List<Object> cs2 = (List<Object>) ea[2];
            List<Object> b3 = addBound(b2, pv);
            List<Object>[] rf = readyFilters(cs2, b3);
            // 从模式右端往左搜 ⇒ 步骤倒序、方向翻转
            boolean rev = !Objects.equals(st, n0);
            // 可达点上界用的每一步：单条 / 变长 / 量化路径（按并集类型、跳数 = 迭代次数 × 一次迭代的跳数）
            List<Object> order = new ArrayList<>(drels);
            if (rev) java.util.Collections.reverse(order);
            List<Object> wsteps = new ArrayList<>();
            for (Object r : order) {
                long[] lh = hopBounds(r);
                List<Object> idsDirs = truthy(g(r, QPP)) ? specIdsDirs(g(r, QPP)) : vec(vec(g(r, TYPES), g(r, DIR)));
                if (rev) {
                    List<Object> f = new ArrayList<>();
                    for (Object td : idsDirs) f.add(vec(Clj.first(td), flip(Clj.second(td))));
                    idsDirs = f;
                }
                wsteps.add(Clj.map(IDS_DIRS, idsDirs, MN, lh[0], MX, lh[1]));
            }
            long maxHops = 0;
            for (Object r : drels) {
                long m = hopBounds(r)[1];
                maxHops = (double) maxHops + (double) m > 9.0E18 ? Long.MAX_VALUE : maxHops + m;
            }
            List<Object> inner = new ArrayList<>(eops);
            inner.add(vec(BUILD_PATH, pv, n0, steps));
            inner.addAll(rf[0]);
            List<Object> rvars = new ArrayList<>();
            for (Object r : drels) rvars.add(g(r, VAR));
            ops = Clj.conj(ops, vec(PATH_DEEPEN, pv, st, end, mode, prefix, inner, rvars, wsteps, maxHops, n0,
                    mode == FREE ? Clj.vecOf(L(g(info, END_CONDS))) : null));
            b = b3;
            cs = rf[1];
        }
        for (Object sh : shorts) {
            Object rel = g(sh, REL), path = g(sh, PATH);
            List<Object> op = vec(g(sh, KIND), path, g(sh, FROM), g(rel, VAR), g(rel, TYPES), g(rel, DIR), g(rel, MIN), g(rel, MAX), g(sh, TO));
            List<Object> b3 = addBound(b, g(rel, VAR));
            if (truthy(path)) b3 = addBound(b3, path);
            List<Object> checks = new ArrayList<>();
            for (Object p : L(g(rel, PROPS))) checks.add(vec(REL_CHECK, g(rel, VAR), p));
            List<Object>[] rf = readyFilters(cs, b3);
            ops = Clj.conj(ops, op);
            ops.addAll(checks);
            ops.addAll(rf[0]);
            b = b3;
            cs = rf[1];
        }
        // 普通命名路径：模式都展开完之后拼出路径值；引用路径变量的条件随后过滤
        List<Object> paths = new ArrayList<>();
        for (Object part : parts) {
            if (Clj.first(part) == NAMED_CHAIN && !truthy(searches.get(Clj.second(part))) && !truthy(deepens.get(Clj.second(part)))) {
                Object pv = n(part, 1), chain = n(part, 2);
                List<Object> segs = new ArrayList<>();
                for (Object s : L(n(chain, 2))) segs.add(vec(relName(Clj.first(s)), nodeVar(Clj.second(s))));
                paths.add(vec(BUILD_PATH, pv, nodeVar(n(chain, 1)), segs));
            }
        }
        // 不带量词的括号路径的子路径变量
        for (Object part : parts) {
            Object ch = Clj.first(part) == NAMED_CHAIN ? n(part, 2) : part;
            if (Clj.first(ch) != CHAIN) continue;
            for (Object sp : L(Clj.nth(ch, 3, null))) paths.add(vec(BUILD_PATH, n(sp, 0), n(sp, 1), n(sp, 2)));
        }
        // 并排节点的别名：[:project [[别名 [:var 原名]]] true]
        List<Object> aliases = new ArrayList<>();
        for (Object part : parts) {
            Object ch = Clj.first(part) == NAMED_CHAIN ? n(part, 2) : part;
            if (Clj.first(ch) != CHAIN) continue;
            for (Object al : L(Clj.nth(ch, 4, null))) aliases.add(vec(n(al, 0), n(al, 1)));
        }
        if (!aliases.isEmpty()) {
            List<Object> items = new ArrayList<>();
            for (Object al : aliases) items.add(vec(n(al, 0), vec(VAR, n(al, 1))));
            ops = Clj.conj(ops, vec(PROJECT, items, true));
        }
        for (Object al : aliases) b = addBound(b, n(al, 0));
        List<Object>[] af = readyFilters(cs, b);
        cs = af[1];
        ops = Clj.into(ops, af[0]);
        for (Object p : paths) b = addBound(b, Clj.second(p));
        List<Object>[] pf = readyFilters(cs, b);
        cs = pf[1];
        List<Object> out = new ArrayList<>(ops);
        out.addAll(paths);
        out.addAll(pf[0]);
        for (Object c : cs) out.add(vec(FILTER, c));
        // 路径搜索前缀：过滤之后按 (起点, 终点) 分组挑选；ALL 前缀 = 不挑（同不带前缀）
        for (Object part : parts) {
            if (Clj.first(part) == NAMED_CHAIN && truthy(Clj.nth(part, 3, null)) && !truthy(searches.get(Clj.second(part)))
                    && !truthy(deepens.get(Clj.second(part))) && g(n(part, 3), KIND) != ALL)
                out.add(vec(PATH_SELECT, Clj.second(part), n(part, 3)));
        }
        // 有前缀时图模式的 WHERE 在挑选之后
        if (truthy(post)) out.add(vec(FILTER, post));
        return new Object[] {out, b};
    }

    // MATCH (v[:L] {…}) [WHERE …] 后面紧跟 RETURN / WITH … ORDER BY v.embedding <=> q LIMIT k（不聚合、不 DISTINCT）：
    // 起点换成向量 top-k 检索。
    private static Object resolveItem(Object kk, Object items) {
        if (Clj.first(kk) == VAR) {
            for (Object it : L(items)) if (Objects.equals(Clj.first(it), Clj.second(kk)) && truthy(Clj.second(it))) return Clj.second(it);
            return kk;
        }
        return kk;
    }

    private static Object vectorHint(List<Object> parts, List<Object> rest) {
        Object nxt = rest.isEmpty() ? null : rest.getFirst();
        if (!(parts.size() == 1 && Clj.first(parts.getFirst()) == CHAIN && L(n(parts.getFirst(), 2)).isEmpty() && truthy(nxt))) return null;
        Object v = nodeVar(Clj.second(parts.getFirst()));
        Object items = null, agg = null, order = null, sk = null, li = null, distinct = null;
        if (Clj.first(nxt) == RETURN || Clj.first(nxt) == WITH) {
            distinct = n(nxt, 1);
            items = n(nxt, 2);
            agg = n(nxt, 3);
            order = n(nxt, 4);
            sk = n(nxt, 5);
            li = n(nxt, 6);
        }
        if (truthy(li) && !truthy(agg) && !truthy(distinct) && !L(order).isEmpty() && Clj.second(L(order).getFirst()) == ASC) {
            List<Object> eq = embeddingOf(resolveItem(Clj.first(L(order).getFirst()), items));
            if (eq != null) {
                Object ev = eq.get(0), qe = eq.get(1);
                if (Objects.equals(ev, v) && exprVars(qe).isEmpty()) return vec(VECTOR_TOPK, v, qe, sk, li);
            }
        }
        return null;
    }

    /**
     * 投影 → ORDER BY → SKIP / LIMIT → WITH 的 WHERE → 只留投影列。不聚合、不 DISTINCT 时投影保留旧变量到最后，
     * ORDER BY 与 WHERE 都看得见投影前的变量（openCypher）。
     */
    private static List<Object> projection(Object distinct, Object items, Object agg, Object order, Object skip, Object limit, Object where) {
        List<Object> names = new ArrayList<>();
        for (Object it : L(items)) names.add(Clj.first(it));
        boolean d = truthy(distinct), a = truthy(agg);
        List<Object> out;
        if (a && !d) out = vec(vec(AGGREGATE, items));
        else if (a) out = vec(vec(AGGREGATE, items), vec(DISTINCT, names));
        else if (d) out = vec(vec(PROJECT, items, false), vec(DISTINCT, names));
        else out = vec(vec(PROJECT, items, true));
        if (!L(order).isEmpty()) out.add(vec(SORT, order));
        if (truthy(skip)) out.add(vec(SKIP, skip));
        if (truthy(limit)) out.add(vec(LIMIT, limit));
        if (truthy(where)) out.add(vec(FILTER, where));
        out.add(vec(SELECT, names));
        return out;
    }

    private static List<Object> chainPathOp(Object pv, Object chain) {
        List<Object> segs = new ArrayList<>();
        for (Object s : L(n(chain, 2))) segs.add(vec(Clj.second(Clj.first(s)), Clj.second(Clj.second(s))));
        return vec(BUILD_PATH, pv, Clj.second(n(chain, 1)), segs);
    }

    private static List<Object> chainVars(Object chain) {
        List<Object> out = vec(Clj.second(n(chain, 1)));
        for (Object s : L(n(chain, 2))) {
            out.add(Clj.second(Clj.first(s)));
            out.add(Clj.second(Clj.second(s)));
        }
        return out;
    }

    /** 后面的算子要不要顶点 v 的标签 / 属性（变量出现在 id(v) 之外的地方）。 */
    private static boolean usesVertexData(Object v, Object x) {
        if (vecp(x) && Clj.first(x) == CALL && "id".equals(Clj.second(x)) && vec(vec(VAR, v)).equals(n(x, 3))) return false;
        if (vec(VAR, v).equals(x)) return true;
        if (vecp(x) && Clj.first(x) == NODE_CHECK && Objects.equals(v, Clj.second(x))) return true;
        if (x instanceof Map<?, ?> m) {
            for (Object y : m.values()) if (usesVertexData(v, y)) return true;
            return false;
        }
        if (x instanceof Collection<?> c) {
            for (Object y : c) if (usesVertexData(v, y)) return true;
            return false;
        }
        return false;
    }

    private static List<Object> annotateScans(List<Object> ops) {
        return annotateScans(ops, vec());
    }

    /** tail = 本列表之后还要执行的算子（OPTIONAL MATCH 的子流水线之后还有外层的算子）。 */
    private static List<Object> annotateScans(List<Object> ops, Object tail) {
        List<Object> acc = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Object o = ops.get(i);
            List<Object> more = Clj.subvec(ops, i + 1);
            Object hd = Clj.first(o);
            if (hd == OPTIONAL) acc.add(vec(OPTIONAL, annotateScans(L(Clj.second(o)), vec(more, tail)), n(o, 2)));
            else if (hd == MERGE_PATTERN) {
                Object find = n(o, 1), chain = n(o, 2), oc = n(o, 3), om = n(o, 4);
                acc.add(vec(MERGE_PATTERN, annotateScans(L(find), vec(more, tail, oc, om)), chain, oc, om));
            } else if (hd == NODE_BY_ID) {
                Object nx = more.isEmpty() ? null : more.getFirst();
                if (nx != null && Clj.first(nx) == EXPAND && Objects.equals(Clj.second(o), Clj.second(nx)) && Clj.count(o) == 3)
                    acc.add(Clj.conj(L(o), NOCHECK));
                else acc.add(o);
            } else if (hd == LABEL_SCAN) {
                if (Clj.count(o) == 4) {
                    Object v = n(o, 1), l = n(o, 2), m = n(o, 3);
                    boolean fetch = usesVertexData(v, vec(more, tail));
                    if (L(g(m, PUSH)).isEmpty() && !fetch) acc.add(vec(LABEL_SCAN, v, l));
                    else acc.add(vec(LABEL_SCAN, v, l, Clj.assoc(asMap(m), FETCH, fetch)));
                } else acc.add(o);
            } else acc.add(o);
        }
        return acc;
    }

    /** EXISTS { … } 的子流水线：bound = 外层行里已有、子查询引用到的变量。 */
    static Object subqueryOps(Object q, Object bound) {
        return g(planQuery(Clj.map(QUERY, g(q, QUERY)), bound), OPS);
    }

    /** 模式表达式（模式谓词 / 模式推导）的子流水线：bound = 外层行里已有的变量（执行器按行算、按 [模式 bound] 缓存）。 */
    static Object patternOps(Object part, Object bound) {
        return matchOps(vec(part), null, Clj.vecOf((Iterable<?>) bound), null, null)[0];
    }

    private static Object planUnion(Object u) {
        List<Object> ps = new ArrayList<>();
        for (Object p : L(g(u, PARTS))) ps.add(plan(p));
        return Clj.map(UNION, Clj.map(ALL_Q, g(u, ALL_Q), PARTS, ps), COLUMNS, ps.isEmpty() ? null : g(ps.getFirst(), COLUMNS));
    }

    /** 规范化子句 ⇒ {:ops [算子] :columns [列名]}；UNION ⇒ {:union {:all? :parts [计划 …]} :columns}。 */
    static Object plan(Object a) {
        Object union = g(a, UNION);
        return truthy(union) ? planUnion(union) : planQuery(a, null);
    }

    @SuppressWarnings("unchecked")
    private static Object planQuery(Object a, Object bound0) {
        List<Object> query = L(g(a, QUERY));
        List<Object> bound = Clj.vecOf((Iterable<?>) bound0);
        List<Object> ops = new ArrayList<>();
        Object cols = null;
        for (int i = 0; i < query.size(); i++) {
            Object c = query.get(i);
            List<Object> rest = Clj.subvec(query, i + 1);
            Object hd = Clj.first(c);
            String cn = hd instanceof Kw k ? k.name : "";
            switch (cn) {
                case "match": {
                    Object parts = n(c, 1), where = n(c, 2), post = n(c, 4);
                    Object[] r = matchOps(L(parts), where, bound, vectorHint(L(parts), rest), post);
                    ops.addAll((List<Object>) r[0]);
                    bound = (List<Object>) r[1];
                    break;
                }
                case "optional-match": {
                    Object parts = n(c, 1), where = n(c, 2), post = n(c, 4);
                    Object[] r = matchOps(L(parts), where, bound, null, post);
                    List<Object> b = (List<Object>) r[1];
                    Set<Object> bs = new HashSet<>(bound);
                    List<Object> nw = new ArrayList<>();
                    for (Object v : b) if (!bs.contains(v)) nw.add(v);
                    ops.add(vec(OPTIONAL, r[0], nw));
                    bound = b;
                    break;
                }
                case "call-proc": {
                    Object nm = n(c, 1), pairs = n(c, 2), args = n(c, 3), where = n(c, 4);
                    for (Object p : L(pairs)) bound = addBound(bound, Clj.second(p));
                    ops.add(vec(CALL_PROC, nm, pairs, args));
                    if (truthy(where)) ops.add(vec(FILTER, where));
                    break;
                }
                case "merge-pattern": {
                    Object chain = n(c, 1), oc = n(c, 2), om = n(c, 3), pv = n(c, 4);
                    Object[] r = matchOps(vec(chain), null, bound, null, null);
                    List<Object> b = (List<Object>) r[1];
                    bound = truthy(pv) ? addBound(b, pv) : b;
                    ops.add(vec(MERGE_PATTERN, r[0], chain, oc, om));
                    if (truthy(pv)) ops.add(chainPathOp(pv, chain));
                    break;
                }
                case "merge-node": {
                    Object v = n(c, 1);
                    bound = addBound(bound, v);
                    ops.add(vec(MERGE_NODE, v, n(c, 2), n(c, 3), n(c, 4), n(c, 5)));
                    break;
                }
                case "merge-rel": {
                    Object r = n(c, 2);
                    bound = addBound(bound, r);
                    ops.add(vec(MERGE_REL, n(c, 1), r, n(c, 3), n(c, 4), n(c, 5), n(c, 6), n(c, 7), n(c, 8)));
                    break;
                }
                case "create": {
                    List<Object> parts = L(n(c, 1));
                    // CREATE p = …：chain 第 4 个元素是路径变量；建完再拼路径
                    List<Object> paths = new ArrayList<>();
                    for (Object ch : parts) {
                        Object pv = Clj.nth(ch, 3, null);
                        if (truthy(pv)) paths.add(chainPathOp(pv, ch));
                    }
                    for (Object ch : parts) for (Object v : chainVars(ch)) bound = addBound(bound, v);
                    for (Object p : paths) bound = addBound(bound, Clj.second(p));
                    List<Object> chains = new ArrayList<>();
                    for (Object ch : parts) chains.add(Clj.subvec(L(ch), 0, 3));
                    ops.add(vec(CREATE, chains));
                    ops.addAll(paths);
                    break;
                }
                case "set":
                    ops.add(vec(SET, Clj.second(c)));
                    break;
                case "remove":
                    ops.add(vec(REMOVE, Clj.second(c)));
                    break;
                case "delete":
                    ops.add(vec(DELETE, n(c, 1), n(c, 2)));
                    break;
                case "unwind": {
                    Object e = n(c, 1), v = n(c, 2);
                    bound = addBound(bound, v);
                    ops.add(vec(UNWIND, e, v));
                    break;
                }
                case "with": {
                    Object d = n(c, 1), items = n(c, 2), agg = n(c, 3), o = n(c, 4), sk = n(c, 5), li = n(c, 6), where = n(c, 7);
                    List<Object> names = new ArrayList<>();
                    for (Object it : L(items)) names.add(Clj.first(it));
                    bound = names;
                    ops.addAll(projection(d, items, agg, o, sk, li, where));
                    break;
                }
                case "return": {
                    Object d = n(c, 1), items = n(c, 2), agg = n(c, 3), o = n(c, 4), sk = n(c, 5), li = n(c, 6);
                    List<Object> names = new ArrayList<>();
                    for (Object it : L(items)) names.add(Clj.first(it));
                    bound = names;
                    ops.addAll(projection(d, items, agg, o, sk, li, null));
                    cols = names;
                    break;
                }
                default:
                    throw new IllegalArgumentException("No matching clause: " + hd);
            }
        }
        return Clj.map(OPS, annotateScans(ops), COLUMNS, cols == null ? vec() : cols);
    }
}
