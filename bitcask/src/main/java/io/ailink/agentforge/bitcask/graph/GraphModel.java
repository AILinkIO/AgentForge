package io.ailink.agentforge.bitcask.graph;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongFunction;

import io.ailink.agentforge.bitcask.BatchOp;
import io.ailink.agentforge.bitcask.BatchResult;
import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.Condition;
import io.ailink.agentforge.bitcask.Cursor;
import io.ailink.agentforge.bitcask.Document;
import io.ailink.agentforge.bitcask.Entry;
import io.ailink.agentforge.bitcask.Meta;
import io.ailink.agentforge.bitcask.RangeOptions;
import io.ailink.agentforge.bitcask.StoredDocument;
import io.ailink.agentforge.bitcask.VersionedValue;
import io.ailink.agentforge.bitcask.value.Point;
import io.ailink.agentforge.bitcask.value.Temporals;

/**
 * 图的数据模型（对照 Erlang bitcask {@code graphdb_model}、clj-bitcask {@code graph.model}）：顶点 id 分配与高水位、标签与标签索引、
 * 顶点 / 边属性的 meta 编码。图层 API（{@link Graph}）与 Cypher 共用这一份——同一个 id 空间、同一份标签索引、同一种属性编码。
 * <ul>
 * <li>顶点 n 文档：meta 的保留键 {@code _labels}（字符串数组）存标签，其余键是属性；正文是文档的 text 段（Cypher 属性 {@code text}），
 *     向量是文档向量（Cypher {@code embedding}）。</li>
 * <li>属性值：null / 布尔 / int64 / 浮点 / 字符串 / 字符串列表；时间值、point、NaN / Infinity 与非字符串同类列表按文本存，
 *     类型记进保留键 {@code _types}（每项 {@code 属性名\t类型} 或 {@code 属性名\tlist:元素类型}）——盘上只有标量、字符串与字符串数组，
 *     两边互相读得懂。</li>
 * <li>id 高水位 {@code c}：值是「下一个可发的 id」（9 字节大端，2^64 = 耗尽）。分配在建顶点的那条原子批里带版本条件读写它，并对发出的 id
 *     带「n、a 都不存在」的条件——分配器永远不覆盖已有数据；删掉的 id 不再发出。</li>
 * </ul>
 * vid 是 64 位无符号（long 按无符号解释）。
 */
public final class GraphModel {
    public static final String LABELS_KEY = "_labels";
    public static final String TYPES_KEY = "_types";
    public static final long VID_MAX = -1L;
    static final byte[] EMPTY = new byte[0];
    private static final int BUMP_TRIES = 32;

    private GraphModel() {
    }

    static BitcaskException failArg(String code, Object arg) {
        Map<String, Object> d = new HashMap<>();
        d.put("bitcask/arg", arg);
        return BitcaskException.ofData(code, d, arg);
    }

    public static String vidText(long v) {
        return Long.toUnsignedString(v);
    }

    public static int ucmp(long a, long b) {
        return Long.compareUnsigned(a, b);
    }

    public static long umax(long a, long b) {
        return ucmp(a, b) < 0 ? b : a;
    }

    // ═══ 校验与编码 ═══

    /** 标签 ⇒ 字符串：非空、UTF-8 &lt; 65535 字节。不合规 ⇒ {@code bitcask/graph-label-invalid}。 */
    public static String normLabel(Object l) {
        if (!(l instanceof String s) || s.isEmpty() || s.getBytes(StandardCharsets.UTF_8).length >= 65535)
            throw failArg("bitcask/graph-label-invalid", String.valueOf(l));
        return s;
    }

    /** 标签序列 ⇒ 去重、保序。 */
    public static List<String> normLabels(Object ls) {
        if (ls == null) return List.of();
        if (!(ls instanceof Collection<?> c)) throw failArg("bitcask/graph-label-invalid", String.valueOf(ls));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object l : c) out.add(normLabel(l));
        return List.copyOf(out);
    }

    /** 属性名：下划线开头 ⇒ {@code bitcask/graph-property-reserved}，空 / 非字符串 ⇒ {@code bitcask/graph-property-key-invalid}。 */
    public static String normPropKey(Object k) {
        if (!(k instanceof String s) || s.isEmpty()) throw failArg("bitcask/graph-property-key-invalid", String.valueOf(k));
        if (s.startsWith("_")) throw failArg("bitcask/graph-property-reserved", s);
        return s;
    }

    static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte
                || (v instanceof BigInteger b && b.bitLength() < 64);
    }

    private static boolean isFloat(Object v) {
        return v instanceof Double || v instanceof Float;
    }

    /** 属性值 ⇒ meta 能存的形态（整数 ⇒ Long，浮点 ⇒ Double，列表 ⇒ 不可变 List）；别的 ⇒ {@code bitcask/graph-property-type}。 */
    public static Object normPropValue(String k, Object v) {
        if (v instanceof Boolean || v instanceof String) return v;
        if (isIntegral(v)) return ((Number) v).longValue();
        if (isFloat(v)) return ((Number) v).doubleValue();
        if (Temporals.isTemporal(v) || v instanceof Point) return v;
        if (v instanceof List<?> l) {
            if (l.stream().allMatch(x -> x instanceof String)) return List.copyOf(l);
            if (l.stream().allMatch(GraphModel::isIntegral)) return l.stream().map(x -> (Object) ((Number) x).longValue()).toList();
            if (l.stream().allMatch(x -> isFloat(x) || isIntegral(x)))
                return l.stream().map(x -> (Object) ((Number) x).doubleValue()).toList();
            if (l.stream().allMatch(x -> x instanceof Boolean)) return List.copyOf(l);
            if (Temporals.isTemporal(l.getFirst())) {
                String ty = Temporals.typeName(l.getFirst());
                if (l.stream().allMatch(x -> ty.equals(Temporals.typeName(x)))) return List.copyOf(l);
            }
            if (l.stream().allMatch(x -> x instanceof Point)) return List.copyOf(l);
        }
        throw failArg("bitcask/graph-property-type", k);
    }

    private static boolean nonFinite(Object x) {
        return x instanceof Double d && (d.isNaN() || d.isInfinite());
    }

    private static String extTag(Object v) {
        if (Temporals.isTemporal(v)) return Temporals.typeName(v);
        if (v instanceof Point) return "point";
        if (nonFinite(v)) return "float";
        return null;
    }

    private static String extText(Object v) {
        if (v instanceof Point p) return p.toText();
        if (v instanceof Double d && nonFinite(d)) return d.isNaN() ? "NaN" : d > 0 ? "Infinity" : "-Infinity";
        return Temporals.toIso(v);
    }

    private static String listKind(Object v) {
        if (!(v instanceof List<?> l) || l.isEmpty() || l.getFirst() instanceof String) return null;
        Object x = l.getFirst();
        if (x instanceof Long) return "int";
        if (x instanceof Double) return "float";
        if (x instanceof Boolean) return "bool";
        return extTag(x);
    }

    private static String elemText(String kind, Object x) {
        return switch (kind) {
            case "int", "bool" -> String.valueOf(x);
            case "float" -> nonFinite(x) ? extText(x) : String.valueOf(x);
            default -> extText(x);
        };
    }

    private static Object fromText(String tag, String s) {
        return switch (tag) {
            case "float" -> Double.parseDouble(s);
            case "int" -> Long.parseLong(s);
            case "bool", "boolean" -> switch (s) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException(s);
            };
            case "point" -> Point.parseText(s);
            default -> Temporals.parse(tag, s);
        };
    }

    /** 属性 map ⇒ {字符串 值}（保序）；值为 null 的键丢掉（= 不存在）。 */
    public static Map<String, Object> normProps(Object p) {
        if (p == null) return new LinkedHashMap<>();
        if (!(p instanceof Map<?, ?> m)) throw failArg("bitcask/graph-property-type", String.valueOf(p));
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String s = normPropKey(e.getKey());
            if (e.getValue() != null) out.put(s, normPropValue(s, e.getValue()));
        }
        return out;
    }

    /** 扩展类型的值 ⇒ 文本 + {@code _types}（与 bitcask graphdb_model 一致）。 */
    static Map<String, Object> encodeTyped(Map<String, Object> props) {
        List<String> ts = new ArrayList<>();
        for (var e : props.entrySet()) {
            String tag = extTag(e.getValue());
            if (tag != null) ts.add(e.getKey() + "\t" + tag);
            else {
                String k = listKind(e.getValue());
                if (k != null) ts.add(e.getKey() + "\tlist:" + k);
            }
        }
        if (ts.isEmpty()) return props;
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : props.entrySet()) {
            Object v = e.getValue();
            if (extTag(v) != null) out.put(e.getKey(), extText(v));
            else {
                String k = listKind(v);
                out.put(e.getKey(), k != null ? ((List<?>) v).stream().map(x -> elemText(k, x)).toList() : v);
            }
        }
        Collections.sort(ts);
        out.put(TYPES_KEY, ts);
        return out;
    }

    /**
     * 解开的 meta map ⇒ 属性 map：去掉 null、{@code _labels}、{@code _types}，按 {@code _types} 把文本还原（读不了的留原文）。
     * {@code _types} 认 bitcask 写法 {@code 属性名\t类型}，也认 keelson 早期写的 {@code 类型:属性名} / {@code list<类型>:属性名}。
     */
    public static Map<String, Object> decodeTyped(Map<String, Object> m) {
        Map<String, String> typed = null;
        if (m.get(TYPES_KEY) instanceof List<?> ts) {
            typed = new HashMap<>();
            for (Object o : ts) {
                if (!(o instanceof String s)) continue;
                int t = s.indexOf('\t');
                if (t > 0) typed.put(s.substring(0, t), s.substring(t + 1));
                else {
                    int i = s.indexOf(':');
                    if (i > 0) {
                        String ty = s.substring(0, i);
                        typed.put(s.substring(i + 1), ty.startsWith("list<") && ty.endsWith(">")
                                ? "list:" + ty.substring(5, ty.length() - 1) : ty);
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (v == null || k.equals(LABELS_KEY) || k.equals(TYPES_KEY)) continue;
            if (typed != null && typed.containsKey(k)) {
                String tag = typed.get(k);
                Object r = v;
                try {
                    if (tag.startsWith("list:") && v instanceof List<?> l && l.stream().allMatch(x -> x instanceof String)) {
                        String et = tag.substring(5);
                        List<Object> xs = new ArrayList<>(l.size());
                        for (Object x : l) xs.add(fromText(et, (String) x));
                        r = Collections.unmodifiableList(xs);
                    } else if (v instanceof String s) r = fromText(tag, s);
                } catch (RuntimeException ex) {
                    r = v;
                }
                out.put(k, r);
            } else out.put(k, v);
        }
        return out;
    }

    /** 标签 + 属性（都已规范化）⇒ meta 字节；两者都空 ⇒ null（不写 meta）。 */
    public static byte[] encodeMetaProps(List<String> labels, Map<String, Object> props) {
        Map<String, Object> p = encodeTyped(props);
        if (labels.isEmpty() && p.isEmpty()) return null;
        if (labels.isEmpty()) return Meta.encode(p);
        Map<String, Object> m = new LinkedHashMap<>(p);
        m.put(LABELS_KEY, List.copyOf(labels));
        return Meta.encode(m);
    }

    /** meta 字节 ⇒ map；空 ⇒ 空 map；解不开 ⇒ null。 */
    public static Map<String, Object> safeDecodeMeta(byte[] m) {
        if (m == null || m.length == 0) return new LinkedHashMap<>();
        try {
            return Meta.decodeMap(m);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> labelsOf(Map<String, Object> m) {
        if (m == null) return List.of();
        Object l = m.get(LABELS_KEY);
        if (l instanceof List<?> xs) return xs.stream().map(String::valueOf).toList();
        if (l instanceof String s) return List.of(s);
        return List.of();
    }

    /** meta 字节里的标签（没有 / 解不开 ⇒ 空）。 */
    public static List<String> labelsFromMeta(byte[] m) {
        return labelsOf(safeDecodeMeta(m));
    }

    private static Object utf8OrBytes(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return b;
        }
    }

    /** 边属性 ⇒ 字节：byte[] 原样、字符串 UTF-8（旧用法，图层不解释）；map 走 meta 编码（空 map ⇒ 空字节）。 */
    public static byte[] encodeEdgeProps(Object p) {
        if (p == null) return EMPTY;
        if (p instanceof byte[] b) return b;
        if (p instanceof String s) return s.getBytes(StandardCharsets.UTF_8);
        if (p instanceof Map<?, ?>) {
            Map<String, Object> pp = normProps(p);
            return pp.isEmpty() ? EMPTY : Meta.encode(encodeTyped(pp));
        }
        throw failArg("bitcask/graph-property-type", String.valueOf(p));
    }

    /** 边属性字节 ⇒ map：空 ⇒ 空；meta 编码 ⇒ 解开；别的 ⇒ {@code {"_raw" 字符串或字节}}。 */
    public static Map<String, Object> decodeEdgeProps(byte[] b) {
        if (b == null || b.length == 0) return new LinkedHashMap<>();
        Map<String, Object> m = safeDecodeMeta(b);
        if (m != null) return decodeTyped(m);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("_raw", utf8OrBytes(b));
        return r;
    }

    // ═══ 顶点文档 ═══

    /** 写给引擎的顶点值：{@code text} 非 null ⇒ 纯文本 put；否则 {@code doc} ⇒ put-doc。 */
    public record VertexValue(String text, Document doc, List<String> labels) {
    }

    /** 顶点形态 ⇒ 值 + 标签。 */
    public static VertexValue specToValue(Object spec) {
        if (spec instanceof String s) return new VertexValue(s, null, List.of());
        if (spec instanceof VertexSpec v) {
            List<String> ls = normLabels(v.labels());
            Map<String, Object> ps = normProps(v.props());
            byte[] m = encodeMetaProps(ls, ps);
            return new VertexValue(null, new Document(v.text() == null ? "" : v.text(), null, v.vector(), m, 0), ls);
        }
        throw BitcaskException.of("bitcask/graph-vertex-spec-invalid", String.valueOf(spec));
    }

    /** 值 ⇒ 批 op（条件 c 可为 null）。 */
    public static BatchOp valueOp(byte[] key, VertexValue v, Condition c) {
        return v.text() != null ? BatchOp.put(key, v.text().getBytes(StandardCharsets.UTF_8), c) : BatchOp.putDoc(key, v.doc(), c);
    }

    /** 读回的顶点文档。 */
    public record Doc(String text, byte[] meta, float[] vector, long ord) {
    }

    /** 读顶点文档；不存在 ⇒ null。 */
    public static Doc readDoc(Bitcask db, long vid) {
        StoredDocument d = db.getDocument(GraphKeys.nodeBytes(vid));
        if (d == null) return null;
        float[] v = d.vector();
        return new Doc(d.text(), d.meta(), v != null && v.length > 0 ? v : null, d.ord());
    }

    private static Map<String, Object> propsOf(String text, byte[] meta, Map<String, Object> m) {
        Map<String, Object> p;
        if (m != null) p = decodeTyped(m);
        else {
            p = new LinkedHashMap<>();
            p.put("_raw_meta", meta);
        }
        if (text == null || text.isEmpty() || p.containsKey("text")) return p;
        p.put("text", text);
        return p;
    }

    /** 文档 ⇒ 属性 map（去掉 _labels；正文非空且没有同名属性时暴露为 text）。 */
    public static Map<String, Object> docProps(Doc d) {
        return propsOf(d.text(), d.meta(), safeDecodeMeta(d.meta()));
    }

    /** 文档的模型视图（meta 只解一次）。 */
    public record DocInfo(List<String> labels, Map<String, Object> properties, float[] vector) {
    }

    public static final DocInfo EMPTY_INFO = new DocInfo(List.of(), Map.of(), null);

    public static DocInfo docInfo(Doc d) {
        Map<String, Object> m = safeDecodeMeta(d.meta());
        return new DocInfo(labelsOf(m), propsOf(d.text(), d.meta(), m), d.vector());
    }

    /** 顶点存在：有 n 文档，或有邻接表头（只写了边的顶点）。 */
    public static boolean vertexExists(Bitcask db, long vid) {
        return db.containsKey(GraphKeys.nodeBytes(vid)) || db.containsKey(GraphKeys.ascii(GraphKeys.head(vid)));
    }

    public static VertexInfo vertexInfo(Bitcask db, long vid) {
        Doc d = readDoc(db, vid);
        if (d != null) return new VertexInfo(vid, labelsFromMeta(d.meta()), docProps(d));
        if (db.containsKey(GraphKeys.ascii(GraphKeys.head(vid)))) return new VertexInfo(vid, List.of(), Map.of());
        return null;
    }

    // ═══ 标签索引 ═══

    /** 标签从 old 变成 new 时要写的索引 op（与顶点文档同批）。 */
    public static List<BatchOp> labelOps(long vid, List<String> old, List<String> neu) {
        Set<String> o = Set.copyOf(old), n = Set.copyOf(neu);
        List<BatchOp> ops = new ArrayList<>();
        for (String l : old) if (!n.contains(l)) ops.add(BatchOp.delete(GraphKeys.labelKey(l, vid)));
        for (String l : neu) if (!o.contains(l)) ops.add(BatchOp.put(GraphKeys.labelKey(l, vid), EMPTY));
        return ops;
    }

    /** 带标签的顶点 id（无符号升序）。limit &lt; 0 = 不限；after = null 从头扫，否则只要大于它的。 */
    public static List<Long> labelVids(Bitcask db, Object label, long limit, Long after) {
        String l = normLabel(label);
        byte[] pre = GraphKeys.labelPrefix(l);
        byte[] lo = after == null ? pre : after == VID_MAX ? null : GraphKeys.labelKey(l, after + 1);
        List<Long> out = new ArrayList<>();
        if (lo == null || limit == 0) return out;
        int batch = limit > 0 ? (int) Math.max(1, Math.min(1024, limit)) : 256;
        db.forEachRange(RangeOptions.between(lo, GraphKeys.successor(pre)).batch(batch), e -> {
            Long v = GraphKeys.vidFromTail(e.key());
            if (v != null) out.add(v);
            return limit < 0 || out.size() < limit;
        });
        return out;
    }

    // ═══ id 高水位 ═══

    /** 水位：下一个可发的 id，或耗尽（2^64）。 */
    public record Watermark(long next, boolean exhausted) {
        static final Watermark EXHAUSTED = new Watermark(0, true);

        static Watermark of(long n) {
            return new Watermark(n, false);
        }

        boolean above(Watermark o) {
            return !o.exhausted && (exhausted || ucmp(next, o.next) > 0);
        }
    }

    /** 9 字节大端 ⇒ 水位。别的 ⇒ {@code bitcask/graph-vid-counter-malformed}。 */
    public static Watermark decodeCounter(byte[] b) {
        if (b.length != 9) throw BitcaskException.of("bitcask/graph-vid-counter-malformed");
        byte hi = b[0];
        long lo = ByteBuffer.wrap(b, 1, 8).getLong();
        if (hi == 0) return Watermark.of(lo);
        if (hi == 1 && lo == 0) return Watermark.EXHAUSTED;
        throw BitcaskException.of("bitcask/graph-vid-counter-malformed");
    }

    public static byte[] encodeCounter(Watermark w) {
        ByteBuffer b = ByteBuffer.allocate(9);
        if (w.exhausted) b.put((byte) 1);
        else b.put((byte) 0).putLong(w.next);
        return b.array();
    }

    /** vid 之后的下一个水位值。 */
    public static Watermark afterVid(long vid) {
        return vid == VID_MAX ? Watermark.EXHAUSTED : Watermark.of(vid + 1);
    }

    /** 库句柄 ⇒ 读到过的水位（只增不减 ⇒ 当前水位的下界）：vid 低于它的指定 id 写入不必再读计数器。 */
    private static final Map<Bitcask, Watermark> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private static void noteWatermark(Bitcask db, Watermark nxt) {
        synchronized (KNOWN) {
            Watermark cur = KNOWN.get(db);
            if (cur == null || nxt.above(cur)) KNOWN.put(db, nxt);
        }
    }

    /** 水位 + 它的 LSN。 */
    public record Counter(Watermark next, long ord) {
    }

    /** ⇒ 水位或 null（还没建）。 */
    public static Counter readCounter(Bitcask db) {
        VersionedValue v = db.getWithOrd(GraphKeys.COUNTER);
        if (v == null) return null;
        Watermark n = decodeCounter(v.value());
        noteWatermark(db, n);
        return new Counter(n, v.ord());
    }

    public static boolean belowWatermark(long vid, Watermark next) {
        return next.exhausted || ucmp(vid, next.next) < 0;
    }

    /**
     * 指定 id 的写入推高水位：maxVid ≥ 水位时推到 maxVid + 1（带版本条件，冲突重读）。水位没建时不碰。与之后的数据写入不同批——
     * 只会让水位偏高（id 本来就不回收），不会让分配器发出已占用的 id（分配还带存在性条件）。
     */
    public static void bumpWatermark(Bitcask db, long maxVid) {
        Watermark k = KNOWN.get(db);
        if (k != null && belowWatermark(maxVid, k)) return;
        for (int n = BUMP_TRIES; ; n--) {
            if (n == 0) throw BitcaskException.of("bitcask/graph-conflict");
            Counter c = readCounter(db);
            if (c == null || belowWatermark(maxVid, c.next)) return;
            BatchResult r = db.tryWriteAtomic(List.of(BatchOp.put(GraphKeys.ascii(GraphKeys.COUNTER), encodeCounter(afterVid(maxVid)),
                    Condition.ord(c.ord))));
            if (r.isSuccess()) {
                noteWatermark(db, afterVid(maxVid));
                return;
            }
        }
    }

    private static boolean anyKey(Bitcask db, byte[] lo, byte[] hi) {
        try (Cursor c = db.rangeCursor(RangeOptions.between(lo, hi).batch(1))) {
            return c.moveNext();
        }
    }

    /** 家族 f（n / a）里最大的 vid；没有 ⇒ null。键里 vid 定宽 hex ⇒ 在 vid 上二分区间探测，至多 64 次。 */
    private static Long familyMax(Bitcask db, String f) {
        byte[] hi = GraphKeys.successor(GraphKeys.familyBytes(f));
        if (!anyKey(db, GraphKeys.familyVid(f, 0), hi)) return null;
        long lo = 0, h = VID_MAX;
        while (ucmp(lo, h) < 0) {
            long d = h - lo;
            long mid = lo + ((d >>> 1) + (d & 1));
            if (anyKey(db, GraphKeys.familyVid(f, mid), hi)) lo = mid;
            else h = mid - 1;
        }
        return lo;
    }

    /** 现有最大 vid（n 与 a 两个家族）；空库 ⇒ null。 */
    public static Long maxVid(Bitcask db) {
        Long a = familyMax(db, "n"), b = familyMax(db, "a");
        if (a == null) return b;
        if (b == null) return a;
        return umax(a, b);
    }

    /**
     * 分配新 id 并在同一条原子批里建顶点：{@code build(vid)} ⇒ op 表。计数器被并发推进 ⇒ 退避重来；发出的 id 已被占 ⇒ 越过它。
     * retries &lt; 0 = 不限；耗尽 ⇒ {@code bitcask/graph-conflict}；id 用尽 ⇒ {@code bitcask/graph-vid-exhausted}。
     */
    public static long allocate(Bitcask db, LongFunction<List<BatchOp>> build, int retries) {
        long floor = 0;
        for (int attempt = 0; ; attempt++) {
            if (retries >= 0 && attempt > retries) throw BitcaskException.of("bitcask/graph-conflict");
            Counter cur = readCounter(db);
            Watermark nxt;
            Condition c;
            if (cur != null) {
                nxt = cur.next;
                c = Condition.ord(cur.ord);
            } else {
                Long m = maxVid(db);
                nxt = m == null ? Watermark.of(1) : afterVid(m);
                c = Condition.absent();
            }
            if (nxt.exhausted) throw BitcaskException.of("bitcask/graph-vid-exhausted");
            long vid = umax(nxt.next, floor);
            List<BatchOp> ops = new ArrayList<>();
            ops.add(BatchOp.put(GraphKeys.ascii(GraphKeys.COUNTER), encodeCounter(afterVid(vid)), c));
            ops.add(BatchOp.check(GraphKeys.nodeBytes(vid), Condition.absent()));
            ops.add(BatchOp.check(GraphKeys.ascii(GraphKeys.head(vid)), Condition.absent()));
            ops.addAll(build.apply(vid));
            BatchResult r = db.tryWriteAtomic(ops);
            if (r.isSuccess()) {
                noteWatermark(db, afterVid(vid));
                return vid;
            }
            if (r.conflict() == 1 || r.conflict() == 2) {
                if (vid == VID_MAX) throw BitcaskException.of("bitcask/graph-vid-exhausted");
                floor = vid + 1;
            } else {
                sleepBackoff(attempt);
            }
        }
    }

    static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(1 << Math.min(attempt, 6)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ═══ 直写用的读改写 ═══

    /** 顶点 n 文档的当前版本与标签；cond 是提交时的前置条件。 */
    public record OldVertex(Condition cond, List<String> labels, Doc doc) {
    }

    public static OldVertex oldVertex(Bitcask db, long vid) {
        Doc d = readDoc(db, vid);
        if (d != null) return new OldVertex(Condition.ord(d.ord()), labelsFromMeta(d.meta()), d);
        return new OldVertex(Condition.absent(), List.of(), null);
    }

    /** 已有文档换上新标签 ⇒ 写回的文档（正文 / 属性 / 向量原样）。meta 解不开 ⇒ {@code bitcask/graph-vertex-meta-malformed}。 */
    public static Document relabelValue(long vid, Doc d, List<String> labels) {
        Map<String, Object> m = safeDecodeMeta(d.meta());
        if (m == null) throw BitcaskException.of("bitcask/graph-vertex-meta-malformed", vidText(vid));
        Map<String, Object> props = decodeTyped(m);
        return new Document(d.text() == null ? "" : d.text(), null, d.vector(), encodeMetaProps(labels, props), 0);
    }

    /** 删顶点文档与它的标签索引项（带文档版本条件）。 */
    public static List<BatchOp> removalOps(Bitcask db, long vid) {
        OldVertex o = oldVertex(db, vid);
        List<BatchOp> ops = new ArrayList<>();
        if (o.doc == null) {
            ops.add(BatchOp.check(GraphKeys.nodeBytes(vid), Condition.absent()));
            return ops;
        }
        ops.add(BatchOp.delete(GraphKeys.nodeBytes(vid), o.cond));
        ops.addAll(labelOps(vid, o.labels, List.of()));
        return ops;
    }

    static List<Entry> rangeAll(Bitcask db, byte[] lo, boolean meta, int batch) {
        return db.rangeEntries(RangeOptions.between(lo, GraphKeys.successor(lo)).meta(meta).batch(batch));
    }
}
