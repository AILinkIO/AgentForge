package io.ailink.agentforge.bitcask.cypher;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.ailink.agentforge.bitcask.value.Dur;
import io.ailink.agentforge.bitcask.value.Point;
import io.ailink.agentforge.bitcask.value.Temporals;
import io.ailink.agentforge.bitcask.value.ValueException;

/**
 * 查询表达式求值与值语义（Q-2；对照 bitcask（Erlang）{@code bitcask_expr}）。与语法无关：输入是 {@link Sema} 规范化后的表达式，
 * 行是 变量名 ⇒ 值 的 map。
 * <p>运行期的值：null | Boolean | Long | Double | String | List（列表）| Map（{字符串 值}）| {@link VNode}（顶点；标签 / 属性按需
 * 经执行器读，查询内缓存）| {@link VRel}（边；props 是属性字节，或 UNKNOWN——入边一侧不存属性，用到时再点查）| {@link VPath}
 * | 时间值 | {@link Dur} | {@link Point}。
 * 语义按 openCypher：三值逻辑（null 传播）；1 = 1.0 为真；不同类型的 = 为假、&lt; 为 null；排序用全序
 * （map &lt; node &lt; rel &lt; list &lt; path &lt; string &lt; boolean &lt; number，null 最后）。
 * <p>执行器的上下文实现 {@link Env}（参数、按需读顶点 / 边属性 / 类型名、全文与向量）。
 * <p>移植注记：clj 里 Dur / Point / Node / Rel / Path 都是 defrecord，{@code map?} 对它们为真；凡 clj 用 {@code map?} 判断、
 * 且这些值能走到的地方，这里用 {@link #cljMap} 照搬（{@link #isPlainMap} 才是只认真正的 map）。
 */
final class Expr {
    private Expr() {
    }

    private static final Kw ANY = Kw.of("any"), ALL = Kw.of("all"), NOT = Kw.of("not"), WILD = Kw.of("wild"),
            NONE_Q = Kw.of("none"), SINGLE = Kw.of("single");
    /** compare-values 的结果。 */
    static final Kw LT = Kw.of("lt"), GT = Kw.of("gt"), EQ = Kw.of("eq");
    /** clj 的 ::nan（比较里出现 NaN）。 */
    static final Kw NAN = Kw.of("expr/nan");
    private static final Kw BAD = Kw.of("expr/bad"), NONE = Kw.of("expr/none");
    private static final Kw DT = Kw.of("expr/dt"), NODE = Kw.of("expr/node"), REL = Kw.of("expr/rel"), PATH = Kw.of("expr/path");

    // 表达式头
    private static final Kw COUNT_STAR = Kw.of("count-star"), LIT = Kw.of("lit"), PARAM = Kw.of("param"), VAR = Kw.of("var"),
            AGG_REF = Kw.of("agg-ref"), PROP = Kw.of("prop"), HAS_LABELS = Kw.of("has-labels"), LIST = Kw.of("list"),
            MAP = Kw.of("map"), IS_NULL = Kw.of("is-null"), INDEX = Kw.of("index"), SLICE = Kw.of("slice"),
            LIST_COMP = Kw.of("list-comp"), QUANT = Kw.of("quant"), REDUCE = Kw.of("reduce"), MAP_PROJ = Kw.of("map-proj"),
            PATTERN_PRED = Kw.of("pattern-pred"), EXISTS_SUB = Kw.of("exists-sub"), SHORTEST_EXPR = Kw.of("shortest-expr"),
            PATTERN_COMP = Kw.of("pattern-comp"), CASE = Kw.of("case"), UNOP = Kw.of("unop"), CALL = Kw.of("call"), OP = Kw.of("op"),
            PROP_SEL = Kw.of("prop-sel"), ALL_PROPS = Kw.of("all-props"), ENTRY = Kw.of("entry"), VAR_SEL = Kw.of("var-sel");
    // 运算符
    private static final Kw AND = Kw.of("and"), OR = Kw.of("or"), XOR = Kw.of("xor"), AT_AT = Kw.of("at-at"), VDIST = Kw.of("<=>"),
            OP_EQ = Kw.of("="), OP_NE = Kw.of("<>"), OP_LT = Kw.of("<"), OP_LE = Kw.of("<="), OP_GT = Kw.of(">"), OP_GE = Kw.of(">="),
            IN = Kw.of("in"), STARTS_WITH = Kw.of("starts-with"), ENDS_WITH = Kw.of("ends-with"), CONTAINS = Kw.of("contains"),
            REGEX = Kw.of("regex"), PLUS = Kw.of("+"), MINUS = Kw.of("-"), TIMES = Kw.of("*"), DIV = Kw.of("/"), MOD = Kw.of("%"),
            POW = Kw.of("pow");

    /**
     * 标签表达式：字符串（有这个标签）、[:any [项 …]]（任一）、[:all [项 …]]（都）、[:not 项]、[:wild]（至少有一个标签，{@code %}）。
     * have 是标签集合（关系是 #{类型名}）。
     */
    static boolean isLabelOk(Object l, Object have) {
        Collection<?> h = (Collection<?>) have;
        if (l instanceof String) return h.contains(l);
        Object f = Clj.first(l);
        if (f == ANY) {
            for (Object x : (List<?>) Clj.second(l)) if (isLabelOk(x, have)) return true;
            return false;
        }
        if (f == ALL) {
            for (Object x : (List<?>) Clj.second(l)) if (!isLabelOk(x, have)) return false;
            return true;
        }
        if (f == NOT) return !isLabelOk(Clj.second(l), have);
        if (f == WILD) return !h.isEmpty();
        return false;
    }

    private static final Map<String, Pattern> REGEX_CACHE = new ConcurrentHashMap<>();

    /** =~ 匹配整个字符串（Java 正则语法）；编译结果按模式缓存。模式不合法 ⇒ 执行错误。 */
    private static boolean regexMatch(String s, String pat) {
        Pattern re = REGEX_CACHE.get(pat);
        if (re == null) {
            try {
                re = Pattern.compile(pat);
            } catch (PatternSyntaxException e) {
                throw CypherError.exec("invalid regular expression: ", e.getDescription());
            }
            if (REGEX_CACHE.size() > 1024) REGEX_CACHE.clear();
            REGEX_CACHE.put(pat, re);
        }
        return re.matcher(s).matches();
    }

    static boolean isNode(Object x) {
        return x instanceof VNode;
    }

    static boolean isRel(Object x) {
        return x instanceof VRel;
    }

    static boolean isPath(Object x) {
        return x instanceof VPath;
    }

    /** 真正的 map（clj 的 plain-map?：map 且不是 record）。 */
    static boolean isPlainMap(Object x) {
        return x instanceof Map<?, ?>;
    }

    /** clj 的 map?：真正的 map，或 clj 里是 defrecord 的值（Node / Rel / Path / Dur / Point）。 */
    private static boolean cljMap(Object x) {
        return x instanceof Map<?, ?> || isRecord(x);
    }

    private static boolean isRecord(Object x) {
        return x instanceof VNode || x instanceof VRel || x instanceof VPath || x instanceof Dur || x instanceof Point;
    }

    /** clj record 当 map 看的字段（关键字键，按字段顺序）。 */
    private static Map<Object, Object> recordMap(Object x) {
        Map<Object, Object> m = new LinkedHashMap<>();
        switch (x) {
            case VNode n -> m.put(Kw.of("vid"), n.vid());
            case VRel r -> {
                m.put(Kw.of("src"), r.src());
                m.put(Kw.of("etype"), r.etype());
                m.put(Kw.of("dst"), r.dst());
                m.put(Kw.of("rank"), r.rank());
                m.put(Kw.of("props"), r.props());
            }
            case VPath p -> {
                m.put(Kw.of("nodes"), p.nodes());
                m.put(Kw.of("rels"), p.rels());
            }
            case Dur d -> {
                m.put(Kw.of("months"), d.months());
                m.put(Kw.of("days"), d.days());
                m.put(Kw.of("seconds"), d.seconds());
                m.put(Kw.of("nanos"), d.nanos());
            }
            case Point p -> {
                m.put(Kw.of("srid"), p.srid());
                m.put(Kw.of("x"), p.x());
                m.put(Kw.of("y"), p.y());
                m.put(Kw.of("z"), p.z());
            }
            default -> throw new IllegalArgumentException("not a record: " + x);
        }
        return m;
    }

    /** clj 的 map? 值 ⇒ Map（record 转成关键字键的 map）。 */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> asMap(Object x) {
        return x instanceof Map<?, ?> m ? (Map<Object, Object>) m : recordMap(x);
    }

    /** 边的身份 [src etype dst rank]（clj 的 x/rel-id）。 */
    static Object relId(Object r) {
        return ((VRel) r).id();
    }

    // ── 三值逻辑 ──────────────────────────────────────────

    /** 布尔场合的值：模式表达式的路径列表 ⇒ 非空即真（openCypher）；其余原样。 */
    static Object coerceBool(Object v) {
        return v instanceof PatternList p ? (Object) !p.isEmpty() : v;
    }

    static boolean isTruthy(Object v) {
        return Boolean.TRUE.equals(coerceBool(v));
    }

    /** WHERE 的判定：布尔 / null（null 当假）；别的值 ⇒ 类型错误（openCypher）。 */
    static boolean isPredicateTrue(Object v) {
        v = coerceBool(v);
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        throw CypherError.execc("TypeError", "InvalidArgumentType", "WHERE expects a boolean, got ", typeLabel(v));
    }

    private static Object boolArg(String op, Object v) {
        v = coerceBool(v);
        if (v == null || v instanceof Boolean) return v;
        throw CypherError.execc("TypeError", "InvalidArgumentType", op, " expects boolean operands, got ", typeLabel(v));
    }

    private static Object lnot(Object v) {
        v = coerceBool(v);
        if (v == null) return null;
        if (v instanceof Boolean b) return !b;
        throw CypherError.exec("NOT on a non-boolean value");
    }

    private static boolean isTrue(Object v) {
        return Boolean.TRUE.equals(v);
    }

    private static boolean isFalse(Object v) {
        return Boolean.FALSE.equals(v);
    }

    private static Object land(Object a, Object b) {
        a = boolArg("AND", a);
        b = boolArg("AND", b);
        if (isFalse(a) || isFalse(b)) return false;
        if (isTrue(a) && isTrue(b)) return true;
        if ((a == null || isTrue(a)) && (b == null || isTrue(b))) return null;
        throw CypherError.exec("AND on a non-boolean value");
    }

    private static Object lor(Object a, Object b) {
        a = boolArg("OR", a);
        b = boolArg("OR", b);
        if (isTrue(a) || isTrue(b)) return true;
        if (isFalse(a) && isFalse(b)) return false;
        if ((a == null || isFalse(a)) && (b == null || isFalse(b))) return null;
        throw CypherError.exec("OR on a non-boolean value");
    }

    private static Object lxor(Object a, Object b) {
        a = boolArg("XOR", a);
        b = boolArg("XOR", b);
        if (a == null || b == null) return null;
        if (a instanceof Boolean x && b instanceof Boolean y) return !x.equals(y);
        throw CypherError.exec("XOR on a non-boolean value");
    }

    // ── 相等与比较 ────────────────────────────────────────

    private static Object eqSeq(List<?> xs, List<?> ys) {
        if (xs.size() != ys.size()) return false;
        Object acc = true;
        for (int i = 0; i < xs.size(); i++) {
            Object r = eq(xs.get(i), ys.get(i));
            if (isFalse(r)) return false;
            if (r == null) acc = null;
        }
        return acc;
    }

    /** clj 的 ==（数值）：两边都是整数按 long，否则按 double。 */
    private static boolean numEquiv(Object a, Object b) {
        if (Clj.isInteger(a) && Clj.isInteger(b)) return ((Number) a).longValue() == ((Number) b).longValue();
        return ((Number) a).doubleValue() == ((Number) b).doubleValue();
    }

    /** clj 的 compare（数值）：两边都是整数按 long，否则按 double 的 &lt;（-0.0 与 0.0 相等）。 */
    private static int numCompare(Object a, Object b) {
        if (Clj.isInteger(a) && Clj.isInteger(b)) return Long.compare(((Number) a).longValue(), ((Number) b).longValue());
        double x = ((Number) a).doubleValue(), y = ((Number) b).doubleValue();
        return x < y ? -1 : y < x ? 1 : 0;
    }

    private static int numCompare(double x, double y) {
        return x < y ? -1 : y < x ? 1 : 0;
    }

    /** clj 对 Point record 的 =：各字段按 clj 的 =（double 按 ==）。 */
    private static boolean pointEq(Point a, Point b) {
        return a.srid() == b.srid() && a.x() == b.x() && a.y() == b.y()
                && (a.z() == null ? b.z() == null : b.z() != null && a.z().doubleValue() == b.z().doubleValue());
    }

    /** 相等（三值）：null 参与 ⇒ null；数值按值；列表逐项；map 逐键；顶点 / 边 / 路径按身份；不同类型 ⇒ false。 */
    static Object eq(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Number && b instanceof Number) return numEquiv(a, b);
        if (a instanceof Dur) return b instanceof Dur && a.equals(b);
        if (a instanceof Point pa) return b instanceof Point pb && pointEq(pa, pb);
        if (b instanceof Point) return false;
        if (Temporals.isTemporal(a)) {
            Integer c = Temporals.compare(a, b);
            return c != null && c == 0;
        }
        if (Temporals.isTemporal(b)) return false;
        if (a instanceof VNode n) return b instanceof VNode m && n.vid() == m.vid();
        if (a instanceof VRel r) return b instanceof VRel s && r.id().equals(s.id());
        if (a instanceof VPath p)
            return b instanceof VPath q && isTrue(eq(p.nodes(), q.nodes())) && isTrue(eq(p.rels(), q.rels()));
        if (b instanceof VNode || b instanceof VRel || b instanceof VPath) return false;
        if (a instanceof List<?> xs && b instanceof List<?> ys) return eqSeq(xs, ys);
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) return false;
            List<Object> ks = sortedKeys(ma.keySet());
            List<Object> va = new ArrayList<>(), vb = new ArrayList<>();
            for (Object k : ks) {
                va.add(ma.get(k));
                vb.add(mb.get(k));
            }
            return eqSeq(va, vb);
        }
        return Objects.equals(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> sortedKeys(Collection<?> ks) {
        List<Object> out = new ArrayList<>(ks);
        out.sort((x, y) -> ((Comparable) x).compareTo(y));
        return out;
    }

    private static Kw cmp3(long c) {
        return c < 0 ? LT : c > 0 ? GT : EQ;
    }

    private static boolean isNan(Object x) {
        return x instanceof Double d && d.isNaN();
    }

    static String typeLabel(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean) return "a boolean";
        if (Clj.isInteger(v)) return "an integer";
        if (v instanceof Number) return "a float";
        if (v instanceof String) return "a string";
        if (v instanceof VNode) return "a node";
        if (v instanceof VRel) return "a relationship";
        if (v instanceof VPath) return "a path";
        if (cljMap(v)) return "a map";
        if (v instanceof List<?>) return "a list";
        return "a value";
    }

    /** &lt; 一族的比较：LT | EQ | GT | null（不可比）| NAN（有 NaN 参与）。 */
    static Object compareValues(Object a, Object b) {
        if (a == null || b == null) return null;
        if (a instanceof Number && b instanceof Number) return isNan(a) || isNan(b) ? NAN : cmp3(numCompare(a, b));
        if (a instanceof String x && b instanceof String y) return cmp3(x.compareTo(y));
        if (a instanceof Boolean x && b instanceof Boolean y) return cmp3(x.compareTo(y));
        if (Temporals.isTemporal(a) && !(a instanceof Dur)) {
            Integer c = Temporals.compare(a, b);
            return c == null ? null : cmp3(c);
        }
        if (a instanceof List<?> xs && b instanceof List<?> ys) {
            int i = 0;
            while (true) {
                boolean ex = i >= xs.size(), ey = i >= ys.size();
                if (ex && ey) return EQ;
                if (ex) return LT;
                if (ey) return GT;
                Object c = compareValues(xs.get(i), ys.get(i));
                if (c != EQ) return c;
                i++;
            }
        }
        return null;
    }

    /**
     * openCypher 的可排序性：map &lt; node &lt; rel &lt; list &lt; path &lt; point &lt; datetime &lt; localdatetime &lt; date
     * &lt; time &lt; localtime &lt; duration &lt; string &lt; boolean &lt; number &lt; null。
     */
    private static int rankOf(Object v) {
        if (v == null) return 16;
        if (v instanceof Number) return 15;
        if (v instanceof Boolean) return 14;
        if (v instanceof String) return 13;
        if (v instanceof Dur) return 12;
        if (v instanceof LocalTime) return 11;
        if (v instanceof OffsetTime) return 10;
        if (v instanceof LocalDate) return 9;
        if (v instanceof LocalDateTime) return 8;
        if (v instanceof ZonedDateTime) return 7;
        if (v instanceof Point) return 6;
        if (v instanceof VPath) return 5;
        if (v instanceof VNode) return 2;
        if (v instanceof VRel) return 3;
        if (v instanceof List<?>) return 4;
        if (cljMap(v)) return 1;
        return 17;
    }

    /** ORDER BY 的全序比较（升序）⇒ 负 / 0 / 正。 */
    static Long orderCompare(Object a, Object b) {
        return orderCmp(a, b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long orderCmp(Object a, Object b) {
        int ra = rankOf(a), rb = rankOf(b);
        if (ra != rb) return Integer.compare(ra, rb);
        switch (ra) {
            case 16:
                return 0;
            case 15: // NaN 排在数的最后
                if (isNan(a) && isNan(b)) return 0;
                if (isNan(a)) return 1;
                if (isNan(b)) return -1;
                return numCompare(a, b);
            case 14:
            case 13:
                return ((Comparable) a).compareTo(b);
            case 12: {
                Dur x = (Dur) a, y = (Dur) b;
                long c = Long.compare(x.months(), y.months());
                if (c == 0) c = Long.compare(x.days(), y.days());
                if (c == 0) c = Long.compare(x.seconds(), y.seconds());
                if (c == 0) c = Long.compare(x.nanos(), y.nanos());
                return c;
            }
            case 7, 8, 9, 10, 11:
                return Temporals.compare(a, b);
            case 6: {
                Point x = (Point) a, y = (Point) b;
                long c = Long.compare(x.srid(), y.srid());
                if (c == 0) c = numCompare(x.x(), y.x());
                if (c == 0) c = numCompare(x.y(), y.y());
                if (c == 0) c = numCompare(x.z() == null ? 0.0 : x.z(), y.z() == null ? 0.0 : y.z());
                return c;
            }
            case 5: {
                VPath x = (VPath) a, y = (VPath) b;
                return orderCmp(Clj.into(x.nodes(), x.rels()), Clj.into(y.nodes(), y.rels()));
            }
            case 4: {
                List<?> xs = (List<?>) a, ys = (List<?>) b;
                int i = 0;
                while (true) {
                    boolean ex = i >= xs.size(), ey = i >= ys.size();
                    if (ex && ey) return 0;
                    if (ex) return -1;
                    if (ey) return 1;
                    long c = orderCmp(xs.get(i), ys.get(i));
                    if (c != 0) return c;
                    i++;
                }
            }
            case 3: {
                List<Long> x = ((VRel) a).id(), y = ((VRel) b).id();
                for (int i = 0; i < 4; i++) {
                    int c = Long.compareUnsigned(x.get(i), y.get(i));
                    if (c != 0) return c;
                }
                return 0;
            }
            case 2:
                return Long.compareUnsigned(((VNode) a).vid(), ((VNode) b).vid());
            case 1:
                return orderCmp(sortedEntries(asMap(a)), sortedEntries(asMap(b)));
            default:
                return 0;
        }
    }

    /** (vec (sort-by key (seq m)))：[[k v] …]。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> sortedEntries(Map<?, ?> m) {
        List<Map.Entry<?, ?>> es = new ArrayList<>(m.entrySet());
        es.sort((x, y) -> ((Comparable) x.getKey()).compareTo(y.getKey()));
        List<Object> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : es) out.add(Arrays.asList(e.getKey(), e.getValue()));
        return out;
    }

    /** 分组 / 去重的键：数值 1 与 1.0 等价；顶点 / 边按身份。 */
    static Object groupKey(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1.0e18) return (long) (double) d;
        if (Temporals.isTemporal(v)) return v instanceof ZonedDateTime z ? List.of(DT, z.toInstant()) : v;
        if (v instanceof VNode n) return List.of(NODE, n.vid());
        if (v instanceof VRel r) return List.of(REL, r.id());
        if (v instanceof VPath p) return List.of(PATH, groupKeys(p.nodes()), groupKeys(p.rels()));
        if (v instanceof List<?> l) return groupKeys(l);
        if (cljMap(v)) {
            Map<Object, Object> out = new LinkedHashMap<>();
            asMap(v).forEach((k, x) -> out.put(k, groupKey(x)));
            return out;
        }
        return v;
    }

    private static List<Object> groupKeys(List<?> l) {
        List<Object> out = new ArrayList<>(l.size());
        for (Object x : l) out.add(groupKey(x));
        return out;
    }

    // ── 运算符 ────────────────────────────────────────────

    private interface KwPred {
        boolean test(Object c);
    }

    private static Object relCmp(Object a, Object b, KwPred f) {
        Object c = compareValues(a, b);
        if (c == NAN) return false;
        return c == null ? null : (Object) f.test(c);
    }

    private interface StrPred {
        boolean test(String a, String b);
    }

    private static Object strTest(Object a, Object b, StrPred f) {
        return a instanceof String x && b instanceof String y ? (Object) f.test(x, y) : null;
    }

    /** Clojure 的 (rem double double)（Numbers.remainder：商超出 long 范围或 NaN ⇒ 走 BigDecimal，NaN / 无穷会抛 NumberFormatException）。 */
    private static double cljRem(double n, double d) {
        if (d == 0) throw new ArithmeticException("Divide by zero");
        double q = n / d;
        if (q <= Long.MAX_VALUE && q >= Long.MIN_VALUE) return n - ((double) (long) q) * d;
        Number bq = new BigDecimal(q).toBigInteger();
        return n - bq.doubleValue() * d;
    }

    private static Object numOp(Kw op, Object a, Object b) {
        try {
            boolean ints = Clj.isInteger(a) && Clj.isInteger(b);
            if (op == PLUS) return ints ? (Object) Math.addExact(lng(a), lng(b)) : (Object) (dbl(a) + dbl(b));
            if (op == MINUS) return ints ? (Object) Math.subtractExact(lng(a), lng(b)) : (Object) (dbl(a) - dbl(b));
            if (op == TIMES) return ints ? (Object) Math.multiplyExact(lng(a), lng(b)) : (Object) (dbl(a) * dbl(b));
            if (op == DIV) {
                if (ints) {
                    if (lng(b) == 0) throw CypherError.execc("ArithmeticError", "DivisionByZero", "division by zero");
                    return lng(a) / lng(b);
                }
                // 浮点除以 0 ⇒ Infinity / NaN（IEEE 754，同 openCypher）；整数除以 0 才是错误
                return dbl(a) / dbl(b);
            }
            if (op == MOD) {
                if (ints) {
                    if (lng(b) == 0) throw CypherError.execc("ArithmeticError", "DivisionByZero", "division by zero");
                    return lng(a) % lng(b);
                }
                return dbl(b) == 0 ? Double.NaN : cljRem(dbl(a), dbl(b));
            }
            if (op == POW) return Math.pow(dbl(a), dbl(b));
            throw new IllegalArgumentException("No matching clause: " + op);
        } catch (ArithmeticException e) {
            throw CypherError.execc("ArithmeticError", "IntegerOverflow", "integer overflow");
        }
    }

    private static long lng(Object x) {
        return ((Number) x).longValue();
    }

    private static double dbl(Object x) {
        return ((Number) x).doubleValue();
    }

    static Object binop(Object op0, Object a, Object b) {
        Kw op = (Kw) op0;
        if (op == OP_EQ) return eq(a, b);
        if (op == OP_NE) return lnot(eq(a, b));
        if (op == OP_LT) return relCmp(a, b, c -> c == LT);
        if (op == OP_LE) return relCmp(a, b, c -> c != GT);
        if (op == OP_GT) return relCmp(a, b, c -> c == GT);
        if (op == OP_GE) return relCmp(a, b, c -> c != LT);
        if (op == IN) {
            if (b == null) return null;
            if (b instanceof List<?> l) {
                Object acc = false;
                for (Object x : l) {
                    Object r = eq(a, x);
                    if (isTrue(r)) return true;
                    if (r == null) acc = null;
                }
                return acc;
            }
            throw CypherError.execc("TypeError", "InvalidArgumentType", "IN needs a list on the right");
        }
        if (op == STARTS_WITH) return strTest(a, b, String::startsWith);
        if (op == ENDS_WITH) return strTest(a, b, String::endsWith);
        if (op == CONTAINS) return strTest(a, b, String::contains);
        if (op == REGEX) return a instanceof String x && b instanceof String y ? (Object) regexMatch(x, y) : null;
        if (a == null || b == null) return null;
        if (a instanceof Number && b instanceof Number) return numOp(op, a, b);
        // 时间：时间点 ± duration、duration ± duration、duration × / ÷ 数
        boolean pm = op == PLUS || op == MINUS;
        if (pm && a instanceof Dur x && b instanceof Dur y) return Temporals.durPlus(x, op == MINUS ? Temporals.durNeg(y) : y);
        if (pm && Temporals.isTemporal(a) && b instanceof Dur y) return Temporals.plus(a, op == MINUS ? Temporals.durNeg(y) : y);
        if (op == PLUS && a instanceof Dur x && Temporals.isTemporal(b)) return Temporals.plus(b, x);
        if (op == TIMES && a instanceof Dur x && b instanceof Number n) return Temporals.durScale(x, n);
        if (op == TIMES && a instanceof Number n && b instanceof Dur y) return Temporals.durScale(y, n);
        if (op == DIV && a instanceof Dur x && b instanceof Number n) {
            if (n.doubleValue() == 0) throw CypherError.execc("ArithmeticError", "DivisionByZero", "division by zero");
            return Temporals.durDiv(x, n);
        }
        if (op == PLUS && a instanceof String x && b instanceof String y) return x + y;
        if (op == PLUS && a instanceof List<?> x && b instanceof List<?> y) return Clj.into(x, y);
        if (op == PLUS && a instanceof List<?> x) return Clj.conj(x, b);
        if (op == PLUS && b instanceof List<?> y) {
            List<Object> out = new ArrayList<>();
            out.add(a);
            out.addAll(y);
            return out;
        }
        throw CypherError.execc("TypeError", "InvalidArgumentType", "type mismatch for operator ",
                Sema.txt(Clj.vec(OP, op, Clj.vec(VAR, "a"), Clj.vec(VAR, "b"))));
    }

    // ── 属性与函数 ────────────────────────────────────────

    static Object nodeInfo(Env ctx, Object n) {
        return ctx.nodeInfo(((VNode) n).vid());
    }

    static Object nodeProps(Env ctx, Object n) {
        NodeInfo i = (NodeInfo) nodeInfo(ctx, n);
        return i == null ? null : i.properties();
    }

    static Object nodeLabels(Env ctx, Object n) {
        NodeInfo i = (NodeInfo) nodeInfo(ctx, n);
        return i == null ? null : i.labels();
    }

    static Object floatsToVec(Object f) {
        if (f == null) return null;
        float[] a = (float[]) f;
        List<Object> out = new ArrayList<>(a.length);
        for (float x : a) out.add((double) x);
        return out;
    }

    /** 空间函数：spatial 的错误 ⇒ ArgumentError / InvalidArgumentValue。 */
    @SuppressWarnings("unchecked")
    private static Object pointConstruct(Object a) {
        try {
            if (a instanceof Map<?, ?> m) return Point.construct((Map<String, ?>) m);
            // clj：record 也是 map?，按字符串键取不到坐标
            if (isRecord(a)) return Point.construct(Map.of());
            throw new ValueException("point() needs a map");
        } catch (ValueException e) {
            throw CypherError.execc("ArgumentError", "InvalidArgumentValue", e.getMessage());
        }
    }

    static Object prop(Env ctx, Object x, Object kk) {
        if (x == null) return null;
        if (x instanceof Point p) return p.field((String) kk);
        if (x instanceof VPath) throw CypherError.exec("properties cannot be read from a path");
        if (Temporals.isTemporal(x)) return Temporals.field(x, (String) kk);
        if (x instanceof VNode n) {
            if ("embedding".equals(kk)) return floatsToVec(ctx.nodeVector(n.vid()));
            return Clj.get(nodeProps(ctx, x), kk);
        }
        if (x instanceof VRel r) return Clj.get(ctx.relProps(r), kk);
        if (x instanceof Map<?, ?> m) return m.get(kk);
        throw CypherError.execc("TypeError", "InvalidArgumentType", "cannot read property ", kk, " of a non-node value");
    }

    /** 浮点 ⇒ 截断成整数；NaN / 无穷 / 超出 int64 ⇒ null。 */
    private static Long doubleToLong(Object x) {
        double d = ((Number) x).doubleValue();
        return !Double.isNaN(d) && -9.223372036854775808E18 < d && d < 9.223372036854775807E18 ? (Long) (long) d : null;
    }

    private static final Pattern NON_E_LETTER = Pattern.compile("[a-zA-Z&&[^eE]]");

    /** clojure.string/trim（按 Character.isWhitespace）。 */
    private static String cljTrim(String s) {
        int st = 0, en = s.length();
        while (st < en && Character.isWhitespace(s.charAt(st))) st++;
        while (en > st && Character.isWhitespace(s.charAt(en - 1))) en--;
        return s.substring(st, en);
    }

    private static Object parseNumber(String s) {
        String t = cljTrim(s);
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            // 落到浮点
        }
        try {
            double d = Double.parseDouble(t);
            return Double.isNaN(d) || NON_E_LETTER.matcher(t).find() ? null : (Object) d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long codePoints(String s) {
        return s.codePointCount(0, s.length());
    }

    /** 字符串 ⇒ 码点数组。 */
    private static int[] cps(String s) {
        return s.codePoints().toArray();
    }

    private static String cpsToStr(int[] cs, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) sb.appendCodePoint(cs[i]);
        return sb.toString();
    }

    /** (some nil? xs)（不用 contains(null)：List.of 会抛 NPE）。 */
    private static boolean anyNull(List<?> xs) {
        for (Object x : xs) if (x == null) return true;
        return false;
    }

    private static boolean isNat(Object x) {
        return Clj.isInteger(x) && lng(x) >= 0;
    }

    /** 二期的字符串 / 列表函数；参数类型对不上 ⇒ BAD（由调用方决定 null 还是报错）；不是这一组 ⇒ NONE。 */
    private static Object strFn(String nm, List<?> args) {
        Object a = Clj.nth(args, 0), b = Clj.nth(args, 1), c = Clj.nth(args, 2);
        int argc = args.size();
        switch (nm) {
            case "substring": {
                if (a instanceof String s && isNat(b) && (argc == 2 || isNat(c))) {
                    int[] cs = cps(s);
                    long st = Math.min(lng(b), cs.length);
                    // 长度可能大到 st + c 溢出：先和剩余长度比
                    long en = argc == 2 || lng(c) >= cs.length - st ? cs.length : st + lng(c);
                    return cpsToStr(cs, (int) st, (int) en);
                }
                return BAD;
            }
            case "replace":
                if (a instanceof String s && b instanceof String x && c instanceof String y) return x.isEmpty() ? s : s.replace(x, y);
                return BAD;
            case "split":
                if (a instanceof String s && b instanceof String x) {
                    List<Object> out = new ArrayList<>();
                    if (x.isEmpty()) {
                        for (int cp : cps(s)) out.add(new String(Character.toChars(cp)));
                    } else {
                        out.addAll(Arrays.asList(s.split(Pattern.quote(x), -1)));
                    }
                    return out;
                }
                return BAD;
            case "trim":
                return a instanceof String s ? s.strip() : BAD;
            case "ltrim":
                return a instanceof String s ? s.stripLeading() : BAD;
            case "rtrim":
                return a instanceof String s ? s.stripTrailing() : BAD;
            case "left":
                if (a instanceof String s && isNat(b)) {
                    int[] cs = cps(s);
                    return cpsToStr(cs, 0, (int) Math.min(lng(b), cs.length));
                }
                return BAD;
            case "right":
                if (a instanceof String s && isNat(b)) {
                    int[] cs = cps(s);
                    return cpsToStr(cs, (int) Math.max(0, cs.length - lng(b)), cs.length);
                }
                return BAD;
            case "reverse":
                if (a instanceof String s) return new StringBuilder(s).reverse().toString();
                if (a instanceof List<?> l) {
                    List<Object> out = new ArrayList<>(l);
                    java.util.Collections.reverse(out);
                    return out;
                }
                return BAD;
            case "head":
                return a instanceof List<?> l ? (l.isEmpty() ? null : l.getFirst()) : BAD;
            case "tail":
                return a instanceof List<?> l ? (l.isEmpty() ? new ArrayList<>() : Clj.subvec(l, 1)) : BAD;
            case "last":
                return a instanceof List<?> l ? (l.isEmpty() ? null : l.getLast()) : BAD;
            case "range": {
                Object step = argc == 3 ? c : (Object) 1L;
                if (argc == 3 && Clj.isInteger(c) && lng(c) == 0)
                    throw CypherError.execc("ArgumentError", "NumberOutOfRange", "range() step must not be 0");
                if (Clj.isInteger(a) && Clj.isInteger(b) && Clj.isInteger(step)) {
                    long n;
                    try {
                        n = Math.incrementExact(Math.floorDiv(Math.subtractExact(lng(b), lng(a)), lng(step)));
                    } catch (ArithmeticException e) {
                        throw CypherError.execc("ArgumentError", "NumberOutOfRange", "range() would produce more than 10000000 elements");
                    }
                    if (n > 10000000)
                        throw CypherError.execc("ArgumentError", "NumberOutOfRange", "range() would produce more than 10000000 elements");
                    List<Object> out = new ArrayList<>();
                    if (n > 0) {
                        long a0 = lng(a), st = lng(step);
                        // clj：(range a (+ a (* n step)) step)；算终点时溢出照样抛 ArithmeticException
                        Math.addExact(a0, Math.multiplyExact(n, st));
                        for (long i = 0; i < n; i++) out.add(a0 + i * st);
                    }
                    return out;
                }
                return BAD;
            }
            default:
                return NONE;
        }
    }

    private static final Set<String> VALUE_ARG_FNS = Set.of("tofloat", "toboolean", "tostring", "tointeger", "type", "labels");

    /** 参数类型不对时的 openCypher 代码（按函数，同 TCK）。 */
    private static String[] badArgCode(String nm) {
        if (nm.equals("range")) return new String[]{"ArgumentError", "InvalidArgumentType"};
        if (VALUE_ARG_FNS.contains(nm)) return new String[]{"TypeError", "InvalidArgumentValue"};
        return new String[]{"TypeError", "InvalidArgumentType"};
    }

    private static CypherError failBadArg(String nm) {
        String[] c = badArgCode(nm);
        throw CypherError.execc(c[0], c[1], "invalid argument type for ", nm);
    }

    private static double numArg(String nm, Object x) {
        if (x instanceof Number n) return n.doubleValue();
        throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for ", nm);
    }

    /** openCypher 的数学函数；都不是 ⇒ 聚合用错位置 / 参数类型不对。 */
    private static Object mathFn(String nm, List<?> args) {
        Object a = Clj.nth(args, 0), b = Clj.nth(args, 1);
        switch (nm) {
            case "ceil": return Math.ceil(numArg(nm, a));
            case "floor": return Math.floor(numArg(nm, a));
            case "round": return Math.floor(numArg(nm, a) + 0.5);
            case "sign":
                if (a instanceof Number n) return (long) Math.signum(n.doubleValue());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for sign");
            case "sqrt": return Math.sqrt(numArg(nm, a));
            case "exp": return Math.exp(numArg(nm, a));
            case "log": return Math.log(numArg(nm, a));
            case "log10": return Math.log10(numArg(nm, a));
            case "sin": return Math.sin(numArg(nm, a));
            case "cos": return Math.cos(numArg(nm, a));
            case "tan": return Math.tan(numArg(nm, a));
            case "cot": return 1.0 / Math.tan(numArg(nm, a));
            case "asin": return Math.asin(numArg(nm, a));
            case "acos": return Math.acos(numArg(nm, a));
            case "atan": return Math.atan(numArg(nm, a));
            case "atan2":
                if (anyNull(args)) return null;
                return Math.atan2(numArg(nm, a), numArg(nm, b));
            case "degrees": return Math.toDegrees(numArg(nm, a));
            case "radians": return Math.toRadians(numArg(nm, a));
            case "haversin": return (1.0 - Math.cos(numArg(nm, a))) / 2.0;
            case "isnan":
                if (a instanceof Double d) return d.isNaN();
                if (a instanceof Number) return false;
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for isNaN");
            default:
                if (Sema.AGGS.contains(nm)) throw CypherError.exec("aggregate function ", nm, " used outside an aggregation");
                throw failBadArg(nm);
        }
    }

    private static final Set<String> TEMPORAL_TYPES = Set.of("date", "localtime", "time", "localdatetime", "datetime");

    private static boolean isTemporalFn(String nm) {
        if (TEMPORAL_TYPES.contains(nm) || nm.equals("duration")) return true;
        int i = nm.indexOf('.');
        if (i <= 0) return false;
        String p = nm.substring(0, i);
        return TEMPORAL_TYPES.contains(p) || p.equals("duration");
    }

    private interface Thunk {
        Object get();
    }

    /** 时间函数的错误 ⇒ ArgumentError / InvalidArgumentValue。 */
    private static Object tmWrap(Thunk f) {
        try {
            return f.get();
        } catch (ValueException e) {
            throw CypherError.execc("ArgumentError", "InvalidArgumentValue", e.getMessage());
        } catch (DateTimeException e) {
            throw CypherError.execc("ArgumentError", "InvalidArgumentValue", "invalid temporal value: ", e.getMessage());
        }
    }

    /** date() / datetime('…') / date.truncate(…) / duration(…) / duration.between(…) 一类。 */
    @SuppressWarnings("unchecked")
    private static Object temporalCall(Env ctx, String nm, List<?> args) {
        Object a = Clj.nth(args, 0), b = Clj.nth(args, 1), c = Clj.nth(args, 2);
        int i = nm.indexOf('.');
        String ty = i > 0 ? nm.substring(0, i) : nm;
        String sub = i > 0 ? nm.substring(i + 1) : null;
        Instant now = ctx.now();
        return tmWrap(() -> {
            if (ty.equals("duration") && sub == null) {
                if (a == null) return null;
                if (a instanceof String s) return Temporals.parseDur(s);
                if (a instanceof Map<?, ?> m) return Temporals.durFromMap((Map<String, ?>) m);
                if (isRecord(a)) {
                    // clj：record 也是 map?，按 record 的关键字键走 dur-from-map（键是 ":months" 一类 ⇒ 未知分量）
                    Map<String, Object> m = new LinkedHashMap<>();
                    recordMap(a).forEach((k, v) -> m.put(k.toString(), v));
                    return Temporals.durFromMap(m);
                }
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for duration");
            }
            if (ty.equals("duration")) {
                if (a == null || b == null) return null;
                return switch (sub) {
                    case "between" -> Temporals.between(a, b);
                    case "inmonths" -> Temporals.between(a, b, Temporals.BetweenMode.MONTHS);
                    case "indays" -> Temporals.between(a, b, Temporals.BetweenMode.DAYS);
                    case "inseconds" -> Temporals.between(a, b, Temporals.BetweenMode.SECONDS);
                    default -> throw CypherError.exec("unknown function `", nm, "`");
                };
            }
            if (sub == null) return args.size() == 1 && a == null ? null : Temporals.construct(ty, a, now);
            // 给了参数但是 null ⇒ null（同构造函数）
            if ((sub.equals("transaction") || sub.equals("statement") || sub.equals("realtime")) && args.size() == 1 && a == null)
                return null;
            if (sub.equals("transaction") || sub.equals("statement"))
                return Temporals.construct(ty, a != null ? Clj.map("timezone", a) : null, now);
            if (sub.equals("realtime")) return Temporals.construct(ty, a != null ? Clj.map("timezone", a) : null, Instant.now());
            if (sub.equals("truncate")) {
                if (b == null) return null;
                Object r = Temporals.truncate(ty, (String) a, b, now);
                if (c instanceof Map<?, ?> cm) {
                    // 附带的 map 覆盖截断结果的字段；其中的 timezone 只换时区、保留本地时间
                    Object rb = cm.containsKey("timezone") ? Temporals.localPart(r) : r;
                    Map<Object, Object> m = new LinkedHashMap<>(cm);
                    switch (ty) {
                        case "date" -> m.put("date", rb);
                        case "localtime", "time" -> m.put("time", rb);
                        case "localdatetime", "datetime" -> m.put("datetime", rb);
                        default -> throw new IllegalArgumentException("No matching clause: " + ty);
                    }
                    return Temporals.construct(ty, m, now);
                }
                return r;
            }
            if (ty.equals("datetime") && sub.equals("fromepoch"))
                return a == null || b == null ? null : Temporals.construct("datetime", Clj.map("epochSeconds", a, "nanosecond", b), now);
            if (ty.equals("datetime") && sub.equals("fromepochmillis"))
                return a != null ? Temporals.construct("datetime", Clj.map("epochMillis", a), now) : null;
            throw CypherError.exec("unknown function `", nm, "`");
        });
    }

    static Object call(Env ctx, Object nm0, Object args0) {
        String nm = (String) nm0;
        List<?> args = (List<?>) args0;
        Map<Object, Object> f = Procs.lookupFn(nm);
        // 用户函数：参数为 null 时照样调用（同 bitcask）
        if (f != null) return userCall(ctx, f, args);
        return callStar(ctx, nm, args);
    }

    private static Object userCall(Env ctx, Map<Object, Object> spec, List<?> args) {
        UserFunction f = (UserFunction) spec.get(Procs.FN);
        Object nm = spec.get(Procs.NAME);
        try {
            return Procs.normOut(f.apply(ctx.db(), new ArrayList<>(args)));
        } catch (CypherError e) {
            if (!e.isParseError()) throw e;
            throw CypherError.execc("ProcedureError", null, "function ", nm, " failed: ", e.getMessage());
        } catch (Exception e) {
            throw CypherError.execc("ProcedureError", null, "function ", nm, " failed: ", e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object callStar(Env ctx, String nm, List<?> args) {
        Object a = Clj.nth(args, 0);
        Object r = nm.equals("coalesce") || (args.size() == 1 && a == null) ? NONE : strFn(nm, args);
        if (nm.equals("coalesce")) {
            for (Object x : args) if (x != null) return x;
            return null;
        }
        if (nm.equals("timestamp")) return System.currentTimeMillis();
        // 空间：point({…})、point.distance / distance、point.withinbbox
        if (nm.equals("point")) return a != null ? pointConstruct(a) : null;
        if (nm.equals("point.distance") || nm.equals("distance")) {
            Object p = Clj.nth(args, 0), q = Clj.nth(args, 1);
            if (p == null || q == null) return null;
            if (p instanceof Point pp && q instanceof Point qq) return Point.distance(pp, qq);
            throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for ", nm);
        }
        if (nm.equals("point.withinbbox")) {
            Object p = Clj.nth(args, 0), l = Clj.nth(args, 1), u = Clj.nth(args, 2);
            if (p == null || l == null || u == null) return null;
            if (p instanceof Point pp && l instanceof Point ll && u instanceof Point uu) return Point.withinBBox(pp, ll, uu);
            throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for point.withinBBox");
        }
        if (isTemporalFn(nm)) return temporalCall(ctx, nm, args);
        if (nm.equals("rand")) return Math.random();
        if (nm.equals("e")) return Math.E;
        if (nm.equals("pi")) return Math.PI;
        if (args.size() == 1 && a == null) return null;
        // 多参数函数：任一参数为 null ⇒ null；否则是参数类型不对
        if (r == BAD) {
            if (args.size() > 1 && anyNull(args)) return null;
            throw failBadArg(nm);
        }
        if (r != NONE) return r;
        switch (nm) {
            case "id":
                if (a instanceof VNode n) return n.vid();
                if (a instanceof VRel rr) return rr.id();
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for id");
            case "labels":
                if (a instanceof VNode) return nodeLabels(ctx, a);
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for labels");
            case "type":
                if (a instanceof VRel rr) return ctx.typeName(rr.etype());
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for type");
            case "properties":
                if (a instanceof VNode) return nodeProps(ctx, a);
                if (a instanceof VRel rr) return ctx.relProps(rr);
                if (cljMap(a)) return a; // clj：record 也是 map?，原样返回
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for properties");
            case "keys": {
                Map<?, ?> m;
                if (a instanceof VNode) m = (Map<?, ?>) nodeProps(ctx, a);
                else if (a instanceof VRel rr) m = ctx.relProps(rr);
                else if (cljMap(a)) m = asMap(a);
                else throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for keys");
                return m == null ? new ArrayList<>() : sortedKeys(m.keySet());
            }
            case "size":
                if (a instanceof String s) return codePoints(s);
                if (a instanceof List<?> l) return (long) l.size();
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for size");
            case "length":
                if (a instanceof VPath p) return (long) p.rels().size();
                if (a instanceof String s) return codePoints(s);
                if (a instanceof List<?> l) return (long) l.size();
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for length");
            case "nodes":
                if (a instanceof VPath p) return Clj.vecOf(p.nodes());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for nodes");
            case "relationships":
                if (a instanceof VPath p) return Clj.vecOf(p.rels());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for relationships");
            case "startnode":
                if (a instanceof VRel rr) return new VNode(rr.src());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for startNode");
            case "endnode":
                if (a instanceof VRel rr) return new VNode(rr.dst());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for endNode");
            case "tointeger": {
                if (Clj.isInteger(a)) return a;
                if (a instanceof Number) {
                    Long l = doubleToLong(a);
                    if (l != null) return l;
                    throw CypherError.execc("ArgumentError", "NumberOutOfRange", "toInteger(): ", a, " is out of the integer range");
                }
                if (a instanceof Boolean bo) return bo ? 1L : 0L;
                // 字符串解析不了或超出范围 ⇒ null（同解析失败）
                if (a instanceof String s) {
                    Object x = parseNumber(s);
                    if (Clj.isInteger(x)) return x;
                    return x == null ? null : doubleToLong(x);
                }
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for toInteger");
            }
            case "tofloat": {
                if (a instanceof Number n) return n.doubleValue();
                if (a instanceof String s) {
                    Object x = parseNumber(s);
                    return x == null ? null : (Object) ((Number) x).doubleValue();
                }
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for toFloat");
            }
            case "tostring":
                if (a instanceof String) return a;
                if (a instanceof Number || a instanceof Boolean) return String.valueOf(a);
                if (Temporals.isTemporal(a)) return Temporals.toIso(a);
                if (a instanceof Point) return String.valueOf(a);
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for toString");
            case "toboolean":
                if (a instanceof Boolean) return a;
                if (a instanceof String s) {
                    return switch (s.toLowerCase()) {
                        case "true" -> true;
                        case "false" -> false;
                        default -> null;
                    };
                }
                throw CypherError.execc("TypeError", "InvalidArgumentValue", "invalid argument type for toBoolean");
            case "tolower":
                if (a instanceof String s) return s.toLowerCase();
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for toLower");
            case "toupper":
                if (a instanceof String s) return s.toUpperCase();
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for toUpper");
            case "abs":
                if (a instanceof Number n) return Clj.isInteger(a) ? (Object) Math.abs(n.longValue()) : (Object) Math.abs(n.doubleValue());
                throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type for abs");
            default:
                return mathFn(nm, args);
        }
    }

    // ── 求值 ──────────────────────────────────────────────

    private static Map<String, Object> assocRow(Map<String, Object> row, Object... kvs) {
        Map<String, Object> r = new LinkedHashMap<>(row);
        for (int i = 0; i + 1 < kvs.length; i += 2) r.put((String) kvs[i], kvs[i + 1]);
        return r;
    }

    private static List<Object> evalAll(List<?> es, Map<String, Object> row, Env ctx) {
        List<Object> out = new ArrayList<>(es.size());
        for (Object x : es) out.add(evalExpr(x, row, ctx));
        return out;
    }

    /** 表达式在行上的值。row 是 变量名 ⇒ 值 的 map（另有 [:agg-ref i] ⇒ 聚合结果的键）。 */
    @SuppressWarnings("unchecked")
    static Object evalExpr(Object e0, Object row0, Env ctx) {
        if (e0 == COUNT_STAR) throw CypherError.exec("count(*) outside an aggregation");
        Map<String, Object> row = (Map<String, Object>) row0;
        List<Object> e = (List<Object>) e0;
        Kw h = (Kw) e.getFirst();
        if (h == LIT) return e.get(1);
        if (h == PARAM) {
            Map<String, Object> ps = ctx.params();
            Object p = e.get(1);
            if (ps.containsKey(p)) return ps.get(p);
            throw CypherError.exec("missing parameter $", p);
        }
        if (h == VAR) return row.get(e.get(1));
        if (h == AGG_REF) return ((Map<Object, Object>) row0).get(e);
        if (h == PROP) return prop(ctx, evalExpr(e.get(1), row, ctx), e.get(2));
        if (h == HAS_LABELS) {
            Object x = evalExpr(e.get(1), row, ctx);
            if (x == null) return null;
            List<?> ls = (List<?>) e.get(2);
            if (x instanceof VNode) {
                Collection<?> lbls = (Collection<?>) nodeLabels(ctx, x);
                Set<Object> have = lbls == null ? new HashSet<>() : new HashSet<>(lbls);
                for (Object l : ls) if (!isLabelOk(l, have)) return false;
                return true;
            }
            // 关系：r:T 按类型判断（关系只有一个类型）
            if (x instanceof VRel r) {
                Set<Object> have = new HashSet<>();
                have.add(ctx.typeName(r.etype()));
                for (Object l : ls) if (!isLabelOk(l, have)) return false;
                return true;
            }
            throw CypherError.execc("TypeError", "InvalidArgumentType", "label test on a non-node value");
        }
        if (h == LIST) return evalAll((List<?>) e.get(1), row, ctx);
        if (h == MAP) {
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Object kv : (List<?>) e.get(1)) m.put(Clj.nth(kv, 0), evalExpr(Clj.nth(kv, 1), row, ctx));
            return m;
        }
        if (h == IS_NULL) return Objects.equals(evalExpr(e.get(1), row, ctx) == null, e.get(2));
        // 下标：列表按整数（负数从末尾数，越界 null）；映射 / 顶点 / 关系按字符串键取属性
        if (h == INDEX) {
            Object v = evalExpr(e.get(1), row, ctx), i = evalExpr(e.get(2), row, ctx);
            if (v == null || i == null) return null;
            if (v instanceof List<?> l && Clj.isInteger(i)) {
                long n = l.size(), j = lng(i) < 0 ? n + lng(i) : lng(i);
                return j >= 0 && j < n ? l.get((int) j) : null;
            }
            if (i instanceof String && (cljMap(v))) return prop(ctx, v, i);
            if (v instanceof List<?>)
                throw CypherError.execc("TypeError", "ListElementAccessByNonInteger", "list index must be an integer, got ", typeLabel(i));
            if (cljMap(v))
                throw CypherError.execc("TypeError", "MapElementAccessByNonString", "map key must be a string, got ", typeLabel(i));
            throw CypherError.execc("TypeError", "InvalidArgumentType", "invalid argument type: cannot index ", typeLabel(v));
        }
        // 切片 [from..to)：两端可省、可为负；越界截断
        if (h == SLICE) {
            Object v = evalExpr(e.get(1), row, ctx);
            Object fe = Clj.nth(e, 2), te = Clj.nth(e, 3);
            Object f = fe == null ? null : evalExpr(fe, row, ctx);
            Object to = te == null ? null : evalExpr(te, row, ctx);
            if (v == null) return null;
            if (fe != null && f == null) return null;
            if (te != null && to == null) return null;
            if (v instanceof List<?> l) {
                int n = l.size();
                long fr = sliceNorm(f, 0, n), t2 = sliceNorm(to, n, n);
                return t2 > fr ? Clj.subvec(l, (int) fr, (int) t2) : new ArrayList<>();
            }
            throw CypherError.execc("TypeError", "InvalidArgumentType", "slicing needs a list");
        }
        // openCypher：列表推导、量词（三值逻辑）、reduce、映射投影、模式谓词 / 模式推导
        if (h == LIST_COMP) {
            String v = (String) e.get(1);
            Object src = e.get(2), pred = Clj.nth(e, 3), proj = Clj.nth(e, 4);
            Object l = evalExpr(src, row, ctx);
            if (l == null) return null;
            if (l instanceof List<?> xs) {
                List<Object> out = new ArrayList<>();
                for (Object x : xs) {
                    Map<String, Object> r = assocRow(row, v, x);
                    if (pred == null || isTruthy(evalExpr(pred, r, ctx))) out.add(proj != null ? evalExpr(proj, r, ctx) : x);
                }
                return out;
            }
            throw CypherError.execc("TypeError", "InvalidArgumentType", "list comprehension needs a list");
        }
        if (h == QUANT) {
            Kw kind = (Kw) e.get(1);
            String v = (String) e.get(2);
            Object src = e.get(3), pred = Clj.nth(e, 4);
            Object l = evalExpr(src, row, ctx);
            if (l == null) return null;
            if (!(l instanceof List<?> xs))
                throw CypherError.execc("TypeError", "InvalidArgumentType", kind.name, "() needs a list");
            long nt = 0, nn = 0, nf = 0;
            for (Object x : xs) {
                Object r = pred != null ? coerceBool(evalExpr(pred, assocRow(row, v, x), ctx)) : (Object) true;
                if (isTrue(r)) nt++;
                else if (r == null) nn++;
                else if (isFalse(r)) nf++;
            }
            if (kind == ALL) return nf > 0 ? (Object) false : nn > 0 ? null : (Object) true;
            if (kind == ANY) return nt > 0 ? (Object) true : nn > 0 ? null : (Object) false;
            if (kind == NONE_Q) return nt > 0 ? (Object) false : nn > 0 ? null : (Object) true;
            if (kind == SINGLE) return nt > 1 ? (Object) false : nn > 0 ? null : (Object) (nt == 1);
            throw new IllegalArgumentException("No matching clause: " + kind);
        }
        if (h == REDUCE) {
            String acc = (String) e.get(1);
            Object init = e.get(2);
            String v = (String) e.get(3);
            Object src = e.get(4), x = e.get(5);
            Object l = evalExpr(src, row, ctx);
            if (l == null) return null;
            if (!(l instanceof List<?> xs)) throw CypherError.execc("TypeError", "InvalidArgumentType", "reduce() needs a list");
            Object a = evalExpr(init, row, ctx);
            for (Object item : xs) a = evalExpr(x, assocRow(row, acc, a, v, item), ctx);
            return a;
        }
        if (h == MAP_PROJ) {
            Object base = row.get(e.get(1));
            if (base == null) return null;
            Map<Object, Object> m = new LinkedHashMap<>();
            for (Object it0 : (List<?>) e.get(2)) {
                List<?> it = (List<?>) it0;
                Kw k = (Kw) it.getFirst();
                if (k == PROP_SEL) m.put(it.get(1), prop(ctx, base, it.get(1)));
                else if (k == ALL_PROPS) {
                    Map<?, ?> props;
                    if (base instanceof VNode) props = (Map<?, ?>) nodeProps(ctx, base);
                    else if (base instanceof VRel r) props = ctx.relProps(r);
                    else if (cljMap(base)) props = asMap(base);
                    else throw CypherError.execc("SemanticError", null, "map projection needs a node, relationship or map");
                    if (props != null) m.putAll(props);
                } else if (k == ENTRY) m.put(it.get(1), evalExpr(it.get(2), row, ctx));
                else if (k == VAR_SEL) m.put(it.get(1), row.get(it.get(1)));
                else throw new IllegalArgumentException("No matching clause: " + k);
            }
            return m;
        }
        if (h == PATTERN_PRED) return ctx.patternPaths(e.get(1), row);
        if (h == EXISTS_SUB) return ctx.exists(e.get(1), row);
        if (h == SHORTEST_EXPR) return ctx.shortest(e, row);
        if (h == PATTERN_COMP) {
            Object part = e.get(1), w = Clj.nth(e, 2), x = Clj.nth(e, 3);
            List<Object> out = new ArrayList<>();
            for (Map<String, Object> r : ctx.patternRows(part, row))
                if (w == null || isTruthy(evalExpr(w, r, ctx))) out.add(evalExpr(x, r, ctx));
            return out;
        }
        // CASE：通用形式取第一个条件为 true 的分支（null / false 都不算）；简单形式取第一个与主体相等（= 为 true）的分支；
        // 都不中取 ELSE，没有 ELSE 为 null。只求值用到的分支。
        if (h == CASE) {
            Object subj = e.get(1), els = Clj.nth(e, 3);
            List<?> alts = (List<?>) e.get(2);
            boolean simple = subj != null;
            Object sv = simple ? evalExpr(subj, row, ctx) : null;
            if (alts != null) {
                for (Object alt : alts) {
                    Object w = Clj.nth(alt, 0), th = Clj.nth(alt, 1);
                    boolean hit = simple ? isTrue(eq(sv, evalExpr(w, row, ctx))) : isTruthy(evalExpr(w, row, ctx));
                    if (hit) return evalExpr(th, row, ctx);
                }
            }
            return els != null ? evalExpr(els, row, ctx) : null;
        }
        if (h == UNOP) {
            Object x = evalExpr(e.get(2), row, ctx);
            if (e.get(1) == NOT) return lnot(x);
            if (x == null) return null;
            if (x instanceof Number n) {
                if (!Clj.isInteger(x)) return -n.doubleValue();
                try {
                    return Math.negateExact(n.longValue());
                } catch (ArithmeticException ex) {
                    throw CypherError.execc("ArithmeticError", "IntegerOverflow", "integer overflow");
                }
            }
            throw CypherError.exec("unary minus on a non-number");
        }
        if (h == CALL) {
            String nm = (String) e.get(1);
            List<?> args = (List<?>) e.get(3);
            if (nm.equals("score")) return ctx.ftsScore(Clj.second(args.getFirst()), row);
            if (nm.equals("exists")) {
                Object a = args.getFirst();
                return Clj.is(a, PATTERN_PRED) ? coerceBool(evalExpr(a, row, ctx)) : (Object) (evalExpr(a, row, ctx) != null);
            }
            if (Sema.SEARCH_FNS.contains(nm)) {
                Object fst = args.getFirst();
                String v = (String) (Clj.is(fst, VAR) ? Clj.second(fst) : Clj.second(Clj.second(fst)));
                Object x = row.get(v);
                if (x == null) return null;
                if (x instanceof VNode n) return ctx.searchMatch(v, n.vid(), nm, evalAll(args.subList(1, args.size()), row, ctx));
                throw CypherError.execc("SemanticError", null, nm, "() needs a node");
            }
            return call(ctx, nm, evalAll(args, row, ctx));
        }
        if (h == OP) {
            Kw op = (Kw) e.get(1);
            Object a = e.get(2), b = e.get(3);
            if (op == AND) {
                Object va = evalExpr(a, row, ctx);
                return isFalse(va) ? (Object) false : land(va, evalExpr(b, row, ctx));
            }
            if (op == OR) {
                Object va = evalExpr(a, row, ctx);
                return isTrue(va) ? (Object) true : lor(va, evalExpr(b, row, ctx));
            }
            if (op == XOR) return lxor(evalExpr(a, row, ctx), evalExpr(b, row, ctx));
            // 全文匹配：左边必须是 v.<属性>，按顶点判断（查询内对同一个查询串只检索一次）
            if (op == AT_AT) {
                if (Clj.is(a, PROP) && Clj.is(Clj.second(a), VAR)) {
                    String v = (String) Clj.second(Clj.second(a));
                    Object x = row.get(v), q = evalExpr(b, row, ctx);
                    if (x == null || q == null) return null;
                    if (!(x instanceof VNode n))
                        throw CypherError.execc("TypeError", "InvalidArgumentType", "the left side of @@ must be a node property");
                    if (!(q instanceof String))
                        throw CypherError.execc("TypeError", "InvalidArgumentType", "the right side of @@ must be a string");
                    return ctx.ftsMatch(v, n.vid(), (String) Clj.nth(a, 2), q);
                }
                throw CypherError.execc("TypeError", "InvalidArgumentType", "the left side of @@ must be a node property, e.g. n.text");
            }
            // 向量距离：引擎相似度分数的相反数（越小越近）
            if (op == VDIST) {
                if (Clj.is(a, PROP) && Clj.is(Clj.second(a), VAR) && "embedding".equals(Clj.nth(a, 2))) {
                    Object x = row.get(Clj.second(Clj.second(a))), q = evalExpr(b, row, ctx);
                    if (x == null || q == null) return null;
                    if (x instanceof VNode n) return ctx.vectorDistance(n.vid(), q);
                    throw CypherError.execc("TypeError", "InvalidArgumentType", "the left side of <=> must be a node's embedding");
                }
                throw CypherError.execc("TypeError", "InvalidArgumentType", "the left side of <=> must be n.embedding");
            }
            return binop(op, evalExpr(a, row, ctx), evalExpr(b, row, ctx));
        }
        throw new IllegalArgumentException("No matching clause: " + h);
    }

    private static long sliceNorm(Object x, long d, long n) {
        if (x == null) return d;
        if (!Clj.isInteger(x)) throw CypherError.execc("TypeError", "InvalidArgumentType", "slice bounds must be integers");
        long v = lng(x);
        return v < 0 ? Math.max(0, n + v) : Math.min(n, v);
    }

    // ── 聚合 ──────────────────────────────────────────────

    /**
     * 把表达式里的聚合调用按出现顺序换成 [:agg-ref i]（i 从 base 起）⇒ [新表达式 下一个编号]。按向量结构通走（任何表达式形态里的
     * 聚合都换掉，{@link #findAggs} 用同一次遍历，编号与聚合一一对应）。
     */
    static Object replaceAggs(Object e, Object base) {
        long b = lng(base);
        if (Sema.isAggregate(e)) return Clj.vec(Clj.vec(AGG_REF, b), b + 1);
        if (e instanceof List<?> l) {
            List<Object> acc = new ArrayList<>(l.size());
            Object n = b;
            for (Object x : l) {
                List<?> r = (List<?>) replaceAggs(x, n);
                acc.add(r.get(0));
                n = r.get(1);
            }
            return Clj.vec(acc, n);
        }
        return Clj.vec(e, b);
    }

    /** 表达式里的聚合调用（与 {@link #replaceAggs} 同序）。 */
    static Object findAggs(Object e) {
        List<Object> out = new ArrayList<>();
        findAggsInto(e, out);
        return out;
    }

    private static void findAggsInto(Object e, List<Object> out) {
        if (Sema.isAggregate(e)) out.add(e);
        else if (e instanceof List<?> l) for (Object x : l) findAggsInto(x, out);
    }

    /**
     * 只追加的不可变向量：多个后继共享同一缓冲区，只有「接在末尾」的那个原地追加，其余复制（clj 持久向量的 conj 语义，
     * 免得 collect 每步整份复制；聚合的初值在执行器里跨组共享，所以不能直接改）。
     */
    private static final class PVec {
        final ArrayList<Object> buf;
        final int n;

        PVec(ArrayList<Object> buf, int n) {
            this.buf = buf;
            this.n = n;
        }

        static PVec empty() {
            return new PVec(new ArrayList<>(), 0);
        }

        PVec conj(Object x) {
            if (buf.size() == n) {
                buf.add(x);
                return new PVec(buf, n + 1);
            }
            ArrayList<Object> b = new ArrayList<>(buf.subList(0, n));
            b.add(x);
            return new PVec(b, n + 1);
        }

        List<Object> toList() {
            return new ArrayList<>(buf.subList(0, n));
        }
    }

    /** 只追加的不可变集合（同 {@link PVec} 的共享办法；键 ⇒ 插入序号，序号 &lt; n 才算在集合里）。 */
    private static final class PSet {
        final HashMap<Object, Integer> idx;
        final ArrayList<Object> keys;
        final int n;

        PSet(HashMap<Object, Integer> idx, ArrayList<Object> keys, int n) {
            this.idx = idx;
            this.keys = keys;
            this.n = n;
        }

        static PSet empty() {
            return new PSet(new HashMap<>(), new ArrayList<>(), 0);
        }

        boolean contains(Object k) {
            Integer i = idx.get(k);
            return i != null && i < n;
        }

        PSet conj(Object k) {
            if (contains(k)) return this;
            if (keys.size() == n) {
                keys.add(k);
                idx.put(k, n);
                return new PSet(idx, keys, n + 1);
            }
            HashMap<Object, Integer> m = new HashMap<>();
            ArrayList<Object> ks = new ArrayList<>(keys.subList(0, n));
            for (int i = 0; i < n; i++) m.put(ks.get(i), i);
            ks.add(k);
            m.put(k, n);
            return new PSet(m, ks, n + 1);
        }
    }

    /** percentileCont / percentileDisc 的状态 {:xs [] :p nil}。 */
    private record Pct(PVec xs, Double p) {
    }

    /**
     * 聚合的累加器（clj 的 {:f :distinct? :seen :s}，不可变：agg-step 返回新的）。f 是函数名（String）或 :count-star。
     * s：count / sum ⇒ 数；avg ⇒ [和 个数]；min / max ⇒ 值；collect ⇒ PVec；percentile ⇒ Pct；stdev ⇒ [n 均值 M2]。
     */
    private record Acc(Object f, boolean distinct, PSet seen, Object s) {
    }

    static Object aggInit(Object a) {
        if (a == COUNT_STAR || Clj.first(a) == COUNT_STAR) return new Acc(COUNT_STAR, false, PSet.empty(), 0L);
        String nm = (String) Clj.nth(a, 1);
        boolean d = Clj.truthy(Clj.nth(a, 2));
        Object s = switch (nm) {
            case "count", "sum" -> 0L;
            case "avg" -> List.of(0L, 0L);
            case "min", "max" -> null;
            case "collect" -> PVec.empty();
            case "percentilecont", "percentiledisc" -> new Pct(PVec.empty(), null);
            case "stdev", "stdevp" -> List.of(0L, 0.0, 0.0);
            default -> throw new IllegalArgumentException("No matching clause: " + nm);
        };
        return new Acc(nm, d, PSet.empty(), s);
    }

    static Object aggStep(Object acc0, Object v0) {
        Acc acc = (Acc) acc0;
        Object f = acc.f(), s = acc.s();
        boolean pctFn = "percentilecont".equals(f) || "percentiledisc".equals(f);
        Object v = pctFn ? Clj.nth(v0, 0) : v0, pct = pctFn ? Clj.nth(v0, 1) : null;
        if (f == COUNT_STAR) return new Acc(f, acc.distinct(), acc.seen(), lng(s) + 1);
        if (v == null) return acc;
        if (acc.distinct() && acc.seen().contains(groupKey(v))) return acc;
        PSet seen = acc.distinct() ? acc.seen().conj(groupKey(v)) : acc.seen();
        String fn = (String) f;
        Object s2 = switch (fn) {
            case "count" -> lng(s) + 1;
            case "sum" -> {
                if (v instanceof Number) yield numOp(PLUS, s, v);
                throw CypherError.exec("sum() of a non-number");
            }
            case "avg" -> {
                if (!(v instanceof Number)) throw CypherError.exec("avg() of a non-number");
                List<?> sc = (List<?>) s;
                yield List.of(numOp(PLUS, sc.get(0), v), lng(sc.get(1)) + 1);
            }
            case "min" -> s == null || orderCmp(v, s) < 0 ? v : s;
            case "max" -> s == null || orderCmp(v, s) > 0 ? v : s;
            case "collect" -> ((PVec) s).conj(v);
            case "percentilecont", "percentiledisc" -> {
                if (!(v instanceof Number)) throw CypherError.exec(fn, "() of a non-number");
                if (!(pct instanceof Number pn && 0.0 <= pn.doubleValue() && pn.doubleValue() <= 1.0))
                    throw CypherError.execc("ArgumentError", "NumberOutOfRange", fn, "() needs a percentile between 0.0 and 1.0");
                yield new Pct(((Pct) s).xs().conj(v), ((Number) pct).doubleValue());
            }
            case "stdev", "stdevp" -> {
                if (!(v instanceof Number)) throw CypherError.exec(fn, "() of a non-number");
                // Welford：[n 均值 M2]
                List<?> st = (List<?>) s;
                long n = lng(st.get(0)) + 1;
                double d = dbl(v) - dbl(st.get(1));
                double mean = dbl(st.get(1)) + d / n;
                yield List.of(n, mean, dbl(st.get(2)) + d * (dbl(v) - mean));
            }
            default -> throw new IllegalArgumentException("No matching clause: " + fn);
        };
        return new Acc(f, acc.distinct(), seen, s2);
    }

    /** (vec (sort-by double xs))：稳定排序，按 double 的 &lt;（clj compare）。 */
    private static List<Object> sortByDouble(List<Object> xs) {
        List<Object> out = new ArrayList<>(xs);
        out.sort((x, y) -> numCompare(dbl(x), dbl(y)));
        return out;
    }

    static Object aggFinal(Object acc0) {
        Acc acc = (Acc) acc0;
        Object f = acc.f(), s = acc.s();
        if ("avg".equals(f)) {
            List<?> sc = (List<?>) s;
            return lng(sc.get(1)) > 0 ? (Object) (dbl(sc.get(0)) / dbl(sc.get(1))) : null;
        }
        if ("stdev".equals(f)) {
            List<?> st = (List<?>) s;
            long n = lng(st.get(0));
            return n > 1 ? Math.sqrt(dbl(st.get(2)) / (n - 1)) : 0.0;
        }
        if ("stdevp".equals(f)) {
            List<?> st = (List<?>) s;
            long n = lng(st.get(0));
            return n > 0 ? Math.sqrt(dbl(st.get(2)) / n) : 0.0;
        }
        if ("percentiledisc".equals(f)) {
            Pct pc = (Pct) s;
            List<Object> xs = sortByDouble(pc.xs().toList());
            int n = xs.size();
            if (n == 0) return null;
            return xs.get((int) Math.max(0, (long) Math.ceil(pc.p() * n) - 1));
        }
        if ("percentilecont".equals(f)) {
            Pct pc = (Pct) s;
            List<Object> xs = sortByDouble(pc.xs().toList());
            int n = xs.size();
            if (n == 0) return null;
            double pos = pc.p() * (n - 1);
            long lo = (long) Math.floor(pos), hi = (long) Math.ceil(pos);
            if (lo == hi) return dbl(xs.get((int) lo));
            return dbl(xs.get((int) lo)) + (pos - lo) * (dbl(xs.get((int) hi)) - dbl(xs.get((int) lo)));
        }
        if (s instanceof PVec pv) return pv.toList();
        return s;
    }
}
