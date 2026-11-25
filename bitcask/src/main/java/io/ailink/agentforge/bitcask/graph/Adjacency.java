package io.ailink.agentforge.bitcask.graph;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongConsumer;

import io.ailink.agentforge.bitcask.BatchOp;
import io.ailink.agentforge.bitcask.Bitcask;
import io.ailink.agentforge.bitcask.BitcaskException;
import io.ailink.agentforge.bitcask.Condition;
import io.ailink.agentforge.bitcask.VersionedValue;

/**
 * 邻接表：盘上编解码 + 读原语 + 写会话。一个顶点两个 KV：顶点 {@code n} + 邻接表头 {@code a}（出 / 入边组 + 组内度数）；
 * 邻接表超过阈值的 hub 把最大的组拆成块。值的二进制格式与 Erlang bitcask 图层 v2、clj-bitcask 逐字节相同（大端定宽）：
 * <pre>
 * Head  = &lt;&lt;1:8, NGroups:16, Group × NGroups&gt;&gt;，组按 (Dir, Etype) 升序
 * Group = &lt;&lt;Dir:8, GFlags:8, Etype:32, Count:64, Body&gt;&gt;
 *         Body（内联）= Entry × Count
 *         Body（分块）= &lt;&lt;NChunks:32, &lt;&lt;Other:64, Rank:64&gt;&gt; × NChunks&gt;&gt;（各块下界，块 0 恒为 (0, 0)）
 * Chunk = &lt;&lt;1:8, GFlags:8, Count:32, Entry × Count&gt;&gt;
 * GFlags：bit0 有 rank ≠ 0、bit1 有属性、bit2 已分块
 * Entry = &lt;&lt;Other:64&gt;&gt; [&lt;&lt;Rank:64&gt;&gt;] [&lt;&lt;PLen:16, Props&gt;&gt;]；PLen = 0xFFFF ⇒ 属性在 p 家族
 * </pre>
 * 不变式：① 对称（a 的出边组有 (t, b, r) ⟺ b 的入边组有 (t, a, r)，同批写）；② 头即版本（邻接的任何修改都重写头 ⇒ 写会话只对头设前置条件）；
 * ③ Count = 实际条目数、块目录 = 实际块键。条目的属性是 byte[] 或哨兵 {@link #EXT}（按身份比较）。other / rank 按无符号排序。
 * <p>clj 版写会话里的「字节视图」优化没有移植（只影响速度，落盘字节相同）。
 */
public final class Adjacency {
    static final int F_RANK = 1, F_PROPS = 2, F_CHUNKED = 4;
    static final int PEXT = 0xFFFF;
    /** 外置属性哨兵：按身份（==）判别，不与任何空数组混用。 */
    static final byte[] EXT = new byte[0];
    static final byte[] EMPTY = GraphModel.EMPTY;
    static final LB LB0 = new LB(0, 0);

    private Adjacency() {
    }

    public static boolean isExt(byte[] p) {
        return p == EXT;
    }

    /** 条目：对端、rank、属性（byte[] 或 {@link #EXT}）。 */
    public record AdjEntry(long other, long rank, byte[] props) {
    }

    /** 块下界。 */
    public record LB(long o, long r) {
    }

    /** 带方向的条目（first-entries 用）。 */
    public record FullEntry(int dir, long etype, long other, long rank, byte[] props) {
    }

    /** 组的解码方式。 */
    public enum Want { DECODE, RAW, SKIP }

    @FunctionalInterface
    public interface WantFn {
        Want want(int dir, long etype);
    }

    /**
     * 组：内联组带 {@code entries}，分块组带 {@code chunks}（下界表）。RAW 解码的组不带条目：内联组 {@code bytes + raw}（条目区起点），
     * 分块组 {@code bytes + raw + nchunks}（块目录起点）；SKIP 的组只能拿来数 count。
     */
    public record Group(int dir, long etype, long count, int flags, List<AdjEntry> entries, List<LB> chunks, byte[] bytes, int raw,
                 long nchunks, boolean skipped) {
        static Group inline(int dir, long etype, long count, int flags, List<AdjEntry> es) {
            return new Group(dir, etype, count, flags, es, null, null, -1, -1, false);
        }

        static Group chunked(int dir, long etype, long count, int flags, List<LB> lbs) {
            return new Group(dir, etype, count, flags, null, lbs, null, -1, -1, false);
        }

        Group withEntries(List<AdjEntry> es, long cnt) {
            return new Group(dir, etype, cnt, flags, es, null, null, -1, -1, false);
        }

        Group withCount(long cnt, int fl) {
            return new Group(dir, etype, cnt, fl, entries, chunks, bytes, raw, nchunks, skipped);
        }

        Group withChunks(List<LB> lbs) {
            return new Group(dir, etype, count, flags, null, lbs, null, -1, -1, false);
        }

        boolean isRawChunked() {
            return nchunks >= 0;
        }

        boolean isRawInline() {
            return raw >= 0 && nchunks < 0;
        }
    }

    static String vidText(long v) {
        return Long.toUnsignedString(v);
    }

    /**
     * etype 规整成 32 位无符号（-1 ≡ 0xFFFFFFFF）：所有进本模块的 etype 都过它——内存里的组和盘上解回的组必须用同一个数比。
     * 超出 [-2^31, 2^32) 的不截断，抛 {@code bitcask/graph-etype-out-of-range}（截断会把两个 etype 悄悄并成一个）。
     */
    public static long u32(long t) {
        if (t < -2147483648L || t > 0xFFFFFFFFL) throw BitcaskException.of("bitcask/graph-etype-out-of-range", String.valueOf(t));
        return t & 0xFFFFFFFFL;
    }

    public static Long u32(Long t) {
        return t == null ? null : u32(t.longValue());
    }

    static int cmp(long o1, long r1, long o2, long r2) {
        int c = Long.compareUnsigned(o1, o2);
        return c != 0 ? c : Long.compareUnsigned(r1, r2);
    }

    // ═══ 条目编解码 ═══

    static int flagsOf(List<AdjEntry> es) {
        int f = 0;
        for (AdjEntry e : es) {
            if (e.rank != 0) f |= F_RANK;
            if (isExt(e.props) || e.props.length > 0) f |= F_PROPS;
            if (f == (F_RANK | F_PROPS)) break;
        }
        return f;
    }

    static long entriesSize(List<AdjEntry> es, int f) {
        int base = (f & F_RANK) == 0 ? 8 : 16;
        if ((f & F_PROPS) == 0) return (long) base * es.size();
        long a = 0;
        for (AdjEntry e : es) a += base + 2 + (isExt(e.props) ? 0 : e.props.length);
        return a;
    }

    static long entrySize(AdjEntry e, int f) {
        return 8 + ((f & F_RANK) == 0 ? 0 : 8) + ((f & F_PROPS) == 0 ? 0 : isExt(e.props) ? 2 : 2 + e.props.length);
    }

    static long bodySize(List<AdjEntry> es) {
        return entriesSize(es, flagsOf(es));
    }

    private static void putEntries(ByteBuffer bb, List<AdjEntry> es, int f) {
        boolean rank = (f & F_RANK) != 0, props = (f & F_PROPS) != 0;
        for (AdjEntry e : es) {
            bb.putLong(e.other);
            if (rank) bb.putLong(e.rank);
            if (props) {
                if (isExt(e.props)) bb.putShort((short) PEXT);
                else bb.putShort((short) e.props.length).put(e.props);
            }
        }
    }

    private static List<AdjEntry> readEntries(ByteBuffer bb, long n, int f) {
        boolean rank = (f & F_RANK) != 0, props = (f & F_PROPS) != 0;
        List<AdjEntry> acc = new ArrayList<>((int) Math.min(n, 1 << 16));
        for (long i = 0; i < n; i++) {
            long o = bb.getLong();
            long r = rank ? bb.getLong() : 0;
            byte[] p = EMPTY;
            if (props) {
                int l = bb.getShort() & 0xFFFF;
                if (l == PEXT) p = EXT;
                else {
                    p = new byte[l];
                    bb.get(p);
                }
            }
            acc.add(new AdjEntry(o, r, p));
        }
        return acc;
    }

    // ═══ 头 / 块编解码 ═══

    private static int groupFlags(Group g) {
        return g.chunks != null ? (g.flags & 3) | F_CHUNKED : flagsOf(g.entries);
    }

    /** 组表 ⇒ 头的值。组数只有 16 位：超了报错，不截断写坏。 */
    static byte[] encodeHead(List<Group> groups) {
        int n = groups.size();
        if (n > 0xFFFF) throw BitcaskException.of("bitcask/graph-too-many-groups", n);
        int[] fs = new int[n];
        long size = 3;
        for (int i = 0; i < n; i++) {
            Group g = groups.get(i);
            fs[i] = groupFlags(g);
            size += 14 + (g.chunks != null ? 4 + 16L * g.chunks.size() : entriesSize(g.entries, fs[i]));
        }
        ByteBuffer bb = ByteBuffer.allocate(Math.toIntExact(size));
        bb.put((byte) 1).putShort((short) n);
        for (int i = 0; i < n; i++) {
            Group g = groups.get(i);
            bb.put((byte) g.dir).put((byte) fs[i]).putInt((int) g.etype).putLong(g.count);
            if (g.chunks != null) {
                bb.putInt(g.chunks.size());
                for (LB lb : g.chunks) bb.putLong(lb.o).putLong(lb.r);
            } else putEntries(bb, g.entries, fs[i]);
        }
        return bb.array();
    }

    private static void skipEntries(ByteBuffer bb, long n, int f) {
        int base = (f & F_RANK) == 0 ? 8 : 16;
        if ((f & F_PROPS) == 0) {
            long k = base * n;
            if (k > bb.remaining()) throw new BufferUnderflowException();
            bb.position(bb.position() + (int) k);
        } else {
            for (long i = 0; i < n; i++) {
                if (base + 2 > bb.remaining()) throw new BufferUnderflowException();
                bb.position(bb.position() + base);
                int l = bb.getShort() & 0xFFFF;
                if (l != PEXT) {
                    if (l > bb.remaining()) throw new BufferUnderflowException();
                    bb.position(bb.position() + l);
                }
            }
        }
    }

    static List<Group> decodeHead(byte[] b) {
        return decodeHead(b, null);
    }

    /**
     * 头的值 ⇒ 组表；截断、尾随字节、组未排序、Count = 0、dir &gt; 1、块目录缺 (0,0) ⇒ null。{@code want}（只读路径用，null = 全部解码）
     * 对每个组回 DECODE（照常）、RAW（不解码只记位置，给点查 / 取 id 在字节上直接二分）、SKIP（只校验，只能数 count）。
     * 带 want 的组表不能回写。
     */
    static List<Group> decodeHead(byte[] b, WantFn want) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(b);
            if (bb.get() != 1) return null;
            int n = bb.getShort() & 0xFFFF;
            long prev = -1;
            List<Group> acc = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int d = bb.get();
                int f = bb.get() & 0xFF;
                long t = bb.getInt() & 0xFFFFFFFFL;
                long c = bb.getLong();
                long gk = ((long) d << 32) | t;
                Want w = want == null ? Want.DECODE : want.want(d, t);
                if (d < 0 || d > 1 || c <= 0 || gk <= prev) return null;
                prev = gk;
                if ((f & F_CHUNKED) == 0) {
                    if (c > bb.remaining() / 8) return null;
                    switch (w) {
                        case RAW -> {
                            int at = bb.position();
                            skipEntries(bb, c, f);
                            acc.add(new Group(d, t, c, f, null, null, b, at, -1, false));
                        }
                        case DECODE -> acc.add(Group.inline(d, t, c, f, readEntries(bb, c, f)));
                        case SKIP -> {
                            skipEntries(bb, c, f);
                            acc.add(new Group(d, t, c, f, null, null, null, -1, -1, true));
                        }
                    }
                } else {
                    long nc = bb.getInt() & 0xFFFFFFFFL;
                    if (nc <= 0 || nc > bb.remaining() / 16) return null;
                    if (w == Want.DECODE) {
                        List<LB> lbs = new ArrayList<>((int) nc);
                        for (long j = 0; j < nc; j++) lbs.add(new LB(bb.getLong(), bb.getLong()));
                        if (!lbs.getFirst().equals(LB0)) return null;
                        acc.add(Group.chunked(d, t, c, f & 3, lbs));
                    } else {
                        int at = bb.position();
                        if (bb.getLong() != 0 || bb.getLong() != 0) return null;
                        bb.position(at + (int) (16 * nc));
                        acc.add(w == Want.RAW ? new Group(d, t, c, f & 3, null, null, b, at, nc, false)
                                : new Group(d, t, c, f & 3, null, null, null, -1, -1, true));
                    }
                }
            }
            return bb.hasRemaining() ? null : acc;
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    static byte[] encodeChunk(List<AdjEntry> es) {
        int f = flagsOf(es);
        ByteBuffer bb = ByteBuffer.allocate(Math.toIntExact(6 + entriesSize(es, f)));
        bb.put((byte) 1).put((byte) f).putInt(es.size());
        putEntries(bb, es, f);
        return bb.array();
    }

    /** 块的值 ⇒ 条目表；畸形 ⇒ null。 */
    static List<AdjEntry> decodeChunk(byte[] b) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(b);
            if (bb.get() != 1) return null;
            int f = bb.get() & 0xFF;
            long c = bb.getInt() & 0xFFFFFFFFL;
            if (c > bb.remaining() / 8) return null;
            List<AdjEntry> es = readEntries(bb, c, f);
            return bb.hasRemaining() ? null : es;
        } catch (BufferUnderflowException e) {
            return null;
        }
    }

    // ═══ 有序条目表 ═══

    private static int entIndex(List<AdjEntry> es, long o, long r) {
        int lo = 0, hi = es.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            AdjEntry m = es.get(mid);
            int c = cmp(m.other, m.rank, o, r);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    private static byte[] entFind(List<AdjEntry> es, long o, long r) {
        int i = entIndex(es, o, r);
        return i < 0 ? null : es.get(i).props;
    }

    /** upsert ⇒ 旧属性或 null；新表写进 out[0]。 */
    private static byte[] entPut(List<AdjEntry> es, AdjEntry e, Object[] out) {
        int i = entIndex(es, e.other, e.rank);
        List<AdjEntry> n = new ArrayList<>(es.size() + 1);
        n.addAll(es);
        if (i < 0) {
            n.add(-(i + 1), e);
            out[0] = n;
            return null;
        }
        byte[] old = es.get(i).props;
        n.set(i, e);
        out[0] = n;
        return old;
    }

    private static byte[] entDel(List<AdjEntry> es, long o, long r, Object[] out) {
        int i = entIndex(es, o, r);
        if (i < 0) {
            out[0] = es;
            return null;
        }
        List<AdjEntry> n = new ArrayList<>(es);
        byte[] old = n.remove(i).props;
        out[0] = n;
        return old;
    }

    /** 下界表里最后一个 ≤ (o, r) 的（块 0 恒为 (0,0)，必有）。 */
    static LB locateLb(List<LB> lbs, long o, long r) {
        int lo = 0, hi = lbs.size() - 1, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LB m = lbs.get(mid);
            if (cmp(m.o, m.r, o, r) <= 0) {
                best = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return lbs.get(best);
    }

    // ═══ 组表 ═══

    static Group findGroup(List<Group> gs, int dir, long etype) {
        for (Group g : gs) if (g.dir == dir && g.etype == etype) return g;
        return null;
    }

    private static void storeGroup(List<Group> gs, Group g) {
        int lo = 0, hi = gs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Group x = gs.get(mid);
            int c = Integer.compare(x.dir, g.dir);
            if (c == 0) c = Long.compare(x.etype, g.etype);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else {
                gs.set(mid, g);
                return;
            }
        }
        gs.add(lo, g);
    }

    private static void dropGroup(List<Group> gs, int dir, long etype) {
        gs.removeIf(g -> g.dir == dir && g.etype == etype);
    }

    private static String chunkKey(long vid, int dir, long etype, LB lb) {
        return GraphKeys.chunkKey(vid, dir, etype, lb.o, lb.r);
    }

    // ═══ 顶点状态（写会话内可变；每次重试都新建）═══

    static final class VertexState {
        final long vid;
        final Condition hcond;
        final boolean existed;
        final List<Group> groups;
        final Map<String, List<AdjEntry>> chunks = new HashMap<>();
        final TreeSet<String> dirty = new TreeSet<>();
        final TreeSet<String> dels = new TreeSet<>();
        final List<Group> orig;
        final List<BatchOp> extra = new ArrayList<>();
        boolean touched;
        byte[] headBytes;

        VertexState(long vid, Condition hcond, boolean existed, List<Group> groups) {
            this.vid = vid;
            this.hcond = hcond;
            this.existed = existed;
            this.groups = new ArrayList<>(groups);
            this.orig = List.copyOf(groups);
        }
    }

    /** 读一个顶点的头（带 LSN）⇒ 顶点状态。头变形 ⇒ {@code bitcask/graph-adjacency-malformed}。 */
    static VertexState loadVertex(Bitcask db, long vid) {
        VersionedValue v = db.getWithOrd(GraphKeys.head(vid));
        if (v == null) return new VertexState(vid, Condition.absent(), false, List.of());
        List<Group> gs = decodeHead(v.value());
        if (gs == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", vidText(vid));
        return new VertexState(vid, Condition.ord(v.ord()), true, gs);
    }

    /** 读块（顶点状态内缓存）。块键缺失按空块：只可能是读头之后有人改过这个顶点，提交时头的条件必然不成立。 */
    private static List<AdjEntry> getChunk(Bitcask db, VertexState vs, String k) {
        List<AdjEntry> es = vs.chunks.get(k);
        if (es != null) return es;
        byte[] bv = db.get(k);
        if (bv == null) es = List.of();
        else {
            es = decodeChunk(bv);
            if (es == null) throw BitcaskException.of("bitcask/graph-chunk-malformed", k);
        }
        vs.chunks.put(k, es);
        return es;
    }

    private static void setChunk(VertexState vs, String k, List<AdjEntry> es) {
        vs.chunks.put(k, es);
        vs.dirty.add(k);
        vs.dels.remove(k);
    }

    private static void delChunk(VertexState vs, String k) {
        vs.chunks.remove(k);
        vs.dirty.remove(k);
        vs.dels.add(k);
    }

    private static byte[] getEntry(Bitcask db, VertexState vs, int dir, long etype, long o, long r) {
        Group g = findGroup(vs.groups, dir, etype);
        if (g == null) return null;
        if (g.entries != null) return entFind(g.entries, o, r);
        return entFind(getChunk(db, vs, chunkKey(vs.vid, dir, etype, locateLb(g.chunks, o, r))), o, r);
    }

    @SuppressWarnings("unchecked")
    private static byte[] putEntry(Bitcask db, VertexState vs, int dir, long etype, long o, long r, byte[] p) {
        AdjEntry e = new AdjEntry(o, r, p);
        Group g = findGroup(vs.groups, dir, etype);
        Object[] out = new Object[1];
        if (g == null) {
            storeGroup(vs.groups, Group.inline(dir, etype, 1, 0, List.of(e)));
            vs.touched = true;
            return null;
        }
        if (g.entries != null) {
            byte[] old = entPut(g.entries, e, out);
            storeGroup(vs.groups, g.withEntries((List<AdjEntry>) out[0], g.count + (old != null ? 0 : 1)));
            vs.touched = true;
            return old;
        }
        String k = chunkKey(vs.vid, dir, etype, locateLb(g.chunks, o, r));
        List<AdjEntry> es = getChunk(db, vs, k);
        byte[] old = entPut(es, e, out);
        storeGroup(vs.groups, g.withCount(g.count + (old != null ? 0 : 1), g.flags | flagsOf(List.of(e))));
        setChunk(vs, k, (List<AdjEntry>) out[0]);
        vs.touched = true;
        return old;
    }

    @SuppressWarnings("unchecked")
    private static byte[] delEntry(Bitcask db, VertexState vs, int dir, long etype, long o, long r) {
        Group g = findGroup(vs.groups, dir, etype);
        if (g == null) return null;
        Object[] out = new Object[1];
        if (g.entries != null) {
            byte[] old = entDel(g.entries, o, r, out);
            if (old == null) return null;
            List<AdjEntry> es = (List<AdjEntry>) out[0];
            if (es.isEmpty()) dropGroup(vs.groups, dir, etype);
            else storeGroup(vs.groups, g.withEntries(es, g.count - 1));
            vs.touched = true;
            return old;
        }
        String k = chunkKey(vs.vid, dir, etype, locateLb(g.chunks, o, r));
        List<AdjEntry> es = getChunk(db, vs, k);
        byte[] old = entDel(es, o, r, out);
        if (old == null) return null;
        storeGroup(vs.groups, g.withCount(g.count - 1, g.flags));
        setChunk(vs, k, (List<AdjEntry>) out[0]);
        vs.touched = true;
        return old;
    }

    private static List<AdjEntry> loadGroupChunks(Bitcask db, VertexState vs, int dir, long etype, List<LB> lbs) {
        List<AdjEntry> acc = new ArrayList<>();
        for (LB lb : lbs) acc.addAll(getChunk(db, vs, chunkKey(vs.vid, dir, etype, lb)));
        return acc;
    }

    /** 按组序读条目，直到不同的对端顶点数 &gt; maxOthers 或读完（maxOthers &lt; 0 = 全部；对端不计自己）。done[0] = 读完。 */
    private static List<FullEntry> vertexFirstEntries(Bitcask db, VertexState vs, long maxOthers, boolean[] done) {
        Set<Long> seen = new HashSet<>();
        List<FullEntry> acc = new ArrayList<>();
        for (Group g : List.copyOf(vs.groups)) {
            List<List<AdjEntry>> units = new ArrayList<>();
            if (g.entries != null) units.add(g.entries);
            int nUnits = g.entries != null ? 1 : g.chunks.size();
            for (int u = 0; u < nUnits; u++) {
                if (maxOthers >= 0 && seen.size() > maxOthers) {
                    done[0] = false;
                    return acc;
                }
                List<AdjEntry> es = g.entries != null ? g.entries : getChunk(db, vs, chunkKey(vs.vid, g.dir, g.etype, g.chunks.get(u)));
                for (AdjEntry e : es) {
                    if (e.other != vs.vid) seen.add(e.other);
                    acc.add(new FullEntry(g.dir, g.etype, e.other, e.rank, e.props));
                }
            }
        }
        done[0] = true;
        return acc;
    }

    // ═══ 收尾：分块维护 + 生成 op ═══

    /** 超大组的块上限放宽到 组字节 / 1024，让块目录保持在约 1024 项内。 */
    private static long effChunkMax(long cnt, GraphConfig cfg) {
        return Math.max(cfg.chunkMax(), (8 * cnt) / 1024);
    }

    /** 按字节贪心切片（块头 6 B），每片至少一条。 */
    private static List<List<AdjEntry>> splitEntries(List<AdjEntry> es, long mx) {
        int f = flagsOf(es);
        List<List<AdjEntry>> acc = new ArrayList<>();
        List<AdjEntry> cur = new ArrayList<>();
        long sz = 6;
        for (AdjEntry e : es) {
            long esz = entrySize(e, f);
            if (!cur.isEmpty() && sz + esz > mx) {
                acc.add(cur);
                cur = new ArrayList<>();
                sz = 6;
            }
            cur.add(e);
            sz += esz;
        }
        acc.add(cur);
        return acc;
    }

    /** 已有块超限：切成 max(2, ⌈字节/上限⌉) 片约等大的。 */
    private static List<List<AdjEntry>> splitBalanced(List<AdjEntry> es, long mx) {
        long size = 6 + bodySize(es);
        if (size <= mx) return List.of(es);
        long n = Math.max(2, (size + mx - 1) / mx);
        return splitEntries(es, size / n + 1);
    }

    private static void normalizeGroup(VertexState vs, Group g, GraphConfig cfg, Set<LB> dlbs) {
        long vid = vs.vid;
        if (g.count == 0) {
            for (LB lb : g.chunks) delChunk(vs, chunkKey(vid, g.dir, g.etype, lb));
            dropGroup(vs.groups, g.dir, g.etype);
            return;
        }
        if (dlbs == null || dlbs.isEmpty()) return;
        long mx = effChunkMax(g.count, cfg);
        Set<LB> removed = new HashSet<>();
        Map<LB, List<LB>> extra = new HashMap<>();
        for (LB lb : dlbs) {
            String k = chunkKey(vid, g.dir, g.etype, lb);
            List<AdjEntry> es = vs.chunks.getOrDefault(k, List.of());
            if (es.isEmpty() && !lb.equals(LB0)) {
                delChunk(vs, k);
                removed.add(lb);
            } else {
                List<List<AdjEntry>> pieces = splitBalanced(es, mx);
                setChunk(vs, k, pieces.getFirst());
                if (pieces.size() > 1) {
                    List<LB> more = new ArrayList<>();
                    for (List<AdjEntry> piece : pieces.subList(1, pieces.size())) {
                        AdjEntry f = piece.getFirst();
                        setChunk(vs, GraphKeys.chunkKey(vid, g.dir, g.etype, f.other, f.rank), piece);
                        more.add(new LB(f.other, f.rank));
                    }
                    extra.put(lb, more);
                }
            }
        }
        if (removed.isEmpty() && extra.isEmpty()) return;
        Group cur = findGroup(vs.groups, g.dir, g.etype);
        List<LB> lbs = new ArrayList<>();
        for (LB lb : g.chunks) {
            if (!removed.contains(lb)) lbs.add(lb);
            if (extra.containsKey(lb)) lbs.addAll(extra.get(lb));
        }
        storeGroup(vs.groups, new Group(cur.dir, cur.etype, cur.count, cur.flags, null, lbs, null, -1, -1, false));
    }

    private static void normalizeChunked(VertexState vs, GraphConfig cfg) {
        Map<Long, Set<LB>> d = new HashMap<>();
        for (String k : vs.dirty) {
            GraphKeys.ChunkKey ck = GraphKeys.parseChunk(k);
            d.computeIfAbsent(((long) ck.dir() << 32) | ck.etype(), x -> new LinkedHashSet<>()).add(new LB(ck.other(), ck.rank()));
        }
        for (Group g : List.copyOf(vs.groups)) {
            if (g.chunks != null) {
                Group cur = findGroup(vs.groups, g.dir, g.etype);
                normalizeGroup(vs, cur, cfg, d.get(((long) g.dir << 32) | g.etype));
            }
        }
    }

    /** 分块组缩到 inlineMax / 4 以下 ⇒ 转回内联（滞回：分块门是头 &gt; inlineMax）。 */
    private static void unchunkSmall(Bitcask db, VertexState vs, GraphConfig cfg) {
        long thresh = cfg.inlineMax() / 4;
        for (Group g : List.copyOf(vs.groups)) {
            if (g.chunks == null) continue;
            long minW = 8 + ((g.flags & F_RANK) == 0 ? 0 : 8) + ((g.flags & F_PROPS) == 0 ? 0 : 2);
            if (g.count * minW >= thresh) continue;
            List<AdjEntry> es = loadGroupChunks(db, vs, g.dir, g.etype, g.chunks);
            if (bodySize(es) >= thresh) continue;
            for (LB lb : g.chunks) delChunk(vs, chunkKey(vs.vid, g.dir, g.etype, lb));
            storeGroup(vs.groups, Group.inline(g.dir, g.etype, g.count, 0, es));
        }
    }

    private static void chunkify(VertexState vs, Group g, GraphConfig cfg) {
        List<List<AdjEntry>> pieces = splitEntries(g.entries, effChunkMax(g.count, cfg));
        setChunk(vs, chunkKey(vs.vid, g.dir, g.etype, LB0), pieces.getFirst());
        List<LB> lbs = new ArrayList<>();
        lbs.add(LB0);
        for (List<AdjEntry> piece : pieces.subList(1, pieces.size())) {
            AdjEntry f = piece.getFirst();
            lbs.add(new LB(f.other, f.rank));
            setChunk(vs, GraphKeys.chunkKey(vs.vid, g.dir, g.etype, f.other, f.rank), piece);
        }
        storeGroup(vs.groups, Group.chunked(g.dir, g.etype, g.count, flagsOf(g.entries), lbs));
    }

    /** 头超过 inlineMax ⇒ 把最大的内联组转为分块，直到放得下或没有可转的组（只转体积 &gt; inlineMax / 4 的组）。 */
    private static void chunkOversized(VertexState vs, GraphConfig cfg) {
        while (true) {
            byte[] hb = encodeHead(vs.groups);
            if (hb.length <= cfg.inlineMax()) {
                vs.headBytes = hb;
                return;
            }
            Group best = null;
            long bestSz = -1;
            for (Group g : vs.groups) {
                if (g.entries == null || g.entries.size() < 2) continue;
                long sz = bodySize(g.entries);
                if (sz > cfg.inlineMax() / 4 && sz >= bestSz) {
                    best = g;
                    bestSz = sz;
                }
            }
            if (best == null) {
                vs.headBytes = hb;
                return;
            }
            chunkify(vs, best, cfg);
        }
    }

    private static List<BatchOp> emitOps(VertexState vs) {
        List<BatchOp> ops = new ArrayList<>();
        byte[] hk = GraphKeys.ascii(GraphKeys.head(vs.vid));
        if (!vs.groups.isEmpty()) ops.add(BatchOp.put(hk, vs.headBytes != null ? vs.headBytes : encodeHead(vs.groups), vs.hcond));
        else if (vs.existed) ops.add(BatchOp.delete(hk, vs.hcond));
        for (String k : vs.dels) {
            GraphKeys.ChunkKey ck = GraphKeys.parseChunk(k);
            Group g = findGroup(vs.orig, ck.dir(), ck.etype());
            LB lb = new LB(ck.other(), ck.rank());
            if (g != null && g.chunks != null && lb.equals(locateLb(g.chunks, lb.o, lb.r))) ops.add(BatchOp.delete(GraphKeys.ascii(k)));
        }
        for (String k : vs.dirty) ops.add(BatchOp.put(GraphKeys.ascii(k), encodeChunk(vs.chunks.get(k))));
        ops.addAll(vs.extra);
        return ops;
    }

    private static List<BatchOp> finalizeVertex(Bitcask db, VertexState vs, GraphConfig cfg) {
        if (!vs.touched) return List.of();
        if (vs.groups.size() > 0xFFFF) throw BitcaskException.of("bitcask/graph-too-many-groups", vs.groups.size());
        normalizeChunked(vs, cfg);
        unchunkSmall(db, vs, cfg);
        chunkOversized(vs, cfg);
        return emitOps(vs);
    }

    // ═══ 读原语 ═══

    private static List<AdjEntry> readChunks(Bitcask db, long vid, int dir, long etype, List<LB> lbs, long limit) {
        List<AdjEntry> acc = new ArrayList<>();
        for (LB lb : lbs) {
            if (limit >= 0 && acc.size() >= limit) break;
            String k = chunkKey(vid, dir, etype, lb);
            byte[] bv = db.get(k);
            if (bv == null) continue;
            List<AdjEntry> es = decodeChunk(bv);
            if (es == null) throw BitcaskException.of("bitcask/graph-chunk-malformed", k);
            for (AdjEntry e : es) {
                if (limit >= 0 && acc.size() >= limit) break;
                acc.add(e);
            }
        }
        return acc;
    }

    /** 只读路径的组过滤：dir null = 两个方向，etype null = 全部类型（etype 须已过 u32）。 */
    public static WantFn wantFn(Integer dir, Long etype) {
        return (d, t) -> (dir == null || dir == d) && (etype == null || etype == t) ? Want.DECODE : Want.SKIP;
    }

    /** 同 {@link #wantFn}，但选中的组回 RAW。 */
    public static WantFn rawFn(Integer dir, Long etype) {
        return (d, t) -> (dir == null || dir == d) && (etype == null || etype == t) ? Want.RAW : Want.SKIP;
    }

    static final WantFn WANT_NONE = (d, t) -> Want.SKIP;

    /** 只读路径读头 ⇒ 组表。不存在 ⇒ 空；头变形 ⇒ {@code bitcask/graph-adjacency-malformed}。 */
    public static List<Group> headGroups(Bitcask db, long vid, WantFn want) {
        byte[] bv = db.get(GraphKeys.head(vid));
        if (bv == null) return List.of();
        List<Group> gs = decodeHead(bv, want);
        if (gs == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", vidText(vid));
        return gs;
    }

    public static long countGroups(List<Group> gs, int dir, Long etype) {
        long n = 0;
        for (Group g : gs) if (g.dir == dir && (etype == null || etype == g.etype)) n += g.count;
        return n;
    }

    private static boolean headCounts(byte[] b, Long etype, long[] out) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(b);
            if (bb.get() != 1) return false;
            int n = bb.getShort() & 0xFFFF;
            long prev = -1;
            for (int i = 0; i < n; i++) {
                int d = bb.get();
                int f = bb.get() & 0xFF;
                long t = bb.getInt() & 0xFFFFFFFFL;
                long c = bb.getLong();
                long gk = ((long) d << 32) | t;
                if (d < 0 || d > 1 || c <= 0 || gk <= prev) return false;
                if ((f & F_CHUNKED) == 0) {
                    if (c > bb.remaining() / 8) return false;
                    skipEntries(bb, c, f);
                } else {
                    long nc = bb.getInt() & 0xFFFFFFFFL;
                    int at = bb.position();
                    if (nc <= 0 || nc > bb.remaining() / 16) return false;
                    if (bb.getLong() != 0 || bb.getLong() != 0) return false;
                    bb.position(at + (int) (16 * nc));
                }
                if (etype == null || etype == t) out[d] += c;
                prev = gk;
            }
            return !bb.hasRemaining();
        } catch (BufferUnderflowException e) {
            return false;
        }
    }

    /** 出 / 入两个方向（etype null = 全部，须已过 u32）的条目总数；一次读头、只走组头。 */
    static long[] dirCounts(Bitcask db, long vid, Long etype) {
        long[] out = new long[2];
        byte[] bv = db.get(GraphKeys.head(vid));
        if (bv != null && !headCounts(bv, etype, out)) throw BitcaskException.of("bitcask/graph-adjacency-malformed", vidText(vid));
        return out;
    }

    /** 逐条造行。 */
    @FunctionalInterface
    public interface RowMaker<T> {
        T make(long etype, long other, long rank, byte[] props);
    }

    private static <T> void areaRows(byte[] b, int at, long n, int f, long etype, RowMaker<T> mk, List<T> acc, long left) {
        long m = left >= 0 ? Math.min(n, left) : n;
        boolean rank = (f & F_RANK) != 0, props = (f & F_PROPS) != 0;
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.position(at);
        for (long i = 0; i < m; i++) {
            long o = bb.getLong();
            long r = rank ? bb.getLong() : 0;
            byte[] p = EMPTY;
            if (props) {
                int l = bb.getShort() & 0xFFFF;
                if (l == PEXT) p = EXT;
                else {
                    p = new byte[l];
                    bb.get(p);
                }
            }
            acc.add(mk.make(etype, o, r, p));
        }
    }

    /**
     * 从已读的组表取 dir 方向（etype null = 全部）的条目，逐条 {@code mk} ⇒ 列表，组序 × 条目序；limit &lt; 0 = 不限。
     * RAW 组直接从字节读、读到 limit 为止，分块组按块目录逐块点读（缺块按空）。
     */
    public static <T> List<T> groupRows(Bitcask db, long vid, List<Group> groups, int dir, Long etype, long limit, RowMaker<T> mk) {
        Long et = u32(etype);
        List<T> acc = new ArrayList<>();
        for (Group g : groups) {
            if (limit >= 0 && acc.size() >= limit) break;
            if (g.dir != dir || (et != null && et != g.etype)) continue;
            long left = limit >= 0 ? limit - acc.size() : -1;
            if (g.isRawChunked()) {
                for (long i = 0; i < g.nchunks && !(limit >= 0 && acc.size() >= limit); i++) {
                    String k = chunkKey(vid, dir, g.etype, rawLb(g, i));
                    byte[] bv = db.get(k);
                    if (bv != null) {
                        int[] fc = chunkArea(k, bv);
                        areaRows(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], g.etype, mk, acc, limit >= 0 ? limit - acc.size() : -1);
                    }
                }
            } else if (g.isRawInline()) {
                areaRows(g.bytes, g.raw, g.count, g.flags, g.etype, mk, acc, left);
            } else {
                List<AdjEntry> es = g.entries != null ? g.entries : readChunks(db, vid, dir, g.etype, g.chunks, left);
                for (AdjEntry e : es) {
                    if (left >= 0 && left-- <= 0) break;
                    acc.add(mk.make(g.etype, e.other, e.rank, e.props));
                }
            }
        }
        return acc;
    }

    /** 顶点在 dir 方向的条目，组序 × 条目序。 */
    static List<long[]> entries(Bitcask db, long vid, int dir, Long etype, long limit) {
        Long et = u32(etype);
        return groupRows(db, vid, headGroups(db, vid, rawFn(dir, et)), dir, et, limit, (t, o, r, p) -> new long[]{t, o, r});
    }

    private static int rawWidth(int f) {
        return (f & F_RANK) == 0 ? 8 : 16;
    }

    private static byte[] areaFind(byte[] b, int at, long n, int f, long o, long r) {
        if ((f & F_PROPS) == 0) {
            ByteBuffer bb = ByteBuffer.wrap(b);
            int w = rawWidth(f);
            long lo = 0, hi = n - 1;
            while (lo <= hi) {
                long mid = (lo + hi) >>> 1;
                int p = (int) (at + mid * w);
                int c = cmp(bb.getLong(p), w == 16 ? bb.getLong(p + 8) : 0, o, r);
                if (c < 0) lo = mid + 1;
                else if (c > 0) hi = mid - 1;
                else return EMPTY;
            }
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.position(at);
        return entFind(readEntries(bb, n, f), o, r);
    }

    /** 块的值 ⇒ [标志 条数]；畸形 ⇒ {@code bitcask/graph-chunk-malformed}。 */
    private static int[] chunkArea(String k, byte[] bv) {
        if (bv.length < 6 || bv[0] != 1) throw BitcaskException.of("bitcask/graph-chunk-malformed", k);
        int f = bv[1] & 0xFF;
        long c = ByteBuffer.wrap(bv).getInt(2) & 0xFFFFFFFFL;
        if ((f & F_PROPS) == 0) {
            if (bv.length != 6 + c * rawWidth(f)) throw BitcaskException.of("bitcask/graph-chunk-malformed", k);
        } else if (decodeChunk(bv) == null) throw BitcaskException.of("bitcask/graph-chunk-malformed", k);
        return new int[]{f, (int) c};
    }

    private static LB rawLb(Group g, long i) {
        ByteBuffer bb = ByteBuffer.wrap(g.bytes);
        int p = (int) (g.raw + 16 * i);
        return new LB(bb.getLong(p), bb.getLong(p + 8));
    }

    private static LB rawLocateLb(Group g, long o, long r) {
        long lo = 0, hi = g.nchunks - 1, best = 0;
        while (lo <= hi) {
            long mid = (lo + hi) >>> 1;
            LB m = rawLb(g, mid);
            if (cmp(m.o, m.r, o, r) <= 0) {
                best = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        return rawLb(g, best);
    }

    /** 点查一条条目的属性（byte[] 或 {@link #EXT}）；没有 ⇒ null。头里只定位目标组，定宽区直接在字节上二分，分块组只点读一块。 */
    static byte[] lookup(Bitcask db, long vid, int dir, long etype, long o, long r) {
        long et = u32(etype);
        Group g = findGroup(headGroups(db, vid, rawFn(dir, et)), dir, et);
        if (g == null) return null;
        if (g.isRawChunked()) {
            String k = chunkKey(vid, dir, et, rawLocateLb(g, o, r));
            byte[] bv = db.get(k);
            if (bv == null) return null;
            int[] fc = chunkArea(k, bv);
            return areaFind(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], o, r);
        }
        return areaFind(g.bytes, g.raw, g.count, g.flags, o, r);
    }

    private static void areaIds(byte[] b, int at, long n, int f, LongConsumer sink, boolean dedupe, long[] st) {
        ByteBuffer bb = ByteBuffer.wrap(b);
        if ((f & F_PROPS) == 0) {
            int w = rawWidth(f);
            for (long i = 0; i < n; i++) emitId(bb.getLong((int) (at + i * w)), sink, dedupe, st);
        } else {
            boolean rank = (f & F_RANK) != 0;
            bb.position(at);
            for (long i = 0; i < n; i++) {
                long o = bb.getLong();
                if (rank) bb.position(bb.position() + 8);
                int l = bb.getShort() & 0xFFFF;
                if (l != PEXT) bb.position(bb.position() + l);
                emitId(o, sink, dedupe, st);
            }
        }
    }

    private static void emitId(long o, LongConsumer sink, boolean dedupe, long[] st) {
        if (dedupe && st[0] == 1 && st[1] == o) return;
        sink.accept(o);
        st[0] = 1;
        st[1] = o;
    }

    /** 组表（须以 {@link #rawFn} 读的头）里 dir 方向、etype 的对端 id 依次交给 sink。dedupe ⇒ 组内同一对端的多个 rank 只出一次。 */
    static void addIds(Bitcask db, long vid, List<Group> groups, int dir, Long etype, boolean dedupe, LongConsumer sink) {
        Long et = u32(etype);
        for (Group g : groups) {
            if (g.dir != dir || (et != null && et != g.etype)) continue;
            long[] st = new long[2];
            if (g.isRawChunked()) {
                for (long i = 0; i < g.nchunks; i++) {
                    String k = chunkKey(vid, g.dir, g.etype, rawLb(g, i));
                    byte[] bv = db.get(k);
                    if (bv != null) {
                        int[] fc = chunkArea(k, bv);
                        areaIds(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], sink, dedupe, st);
                    }
                }
            } else if (g.isRawInline()) {
                areaIds(g.bytes, g.raw, g.count, g.flags, sink, dedupe, st);
            } else if (g.entries != null) {
                for (AdjEntry e : g.entries) emitId(e.other, sink, dedupe, st);
            }
        }
    }

    /** 各组 [dir etype count]（只解组头）。 */
    static List<long[]> counts(Bitcask db, long vid) {
        List<long[]> out = new ArrayList<>();
        for (Group g : headGroups(db, vid, WANT_NONE)) out.add(new long[]{g.dir, g.etype, g.count});
        return out;
    }

    /** 头的版本（consistent 读用）：ord 或 -1（不存在）。 */
    static long headOrd(Bitcask db, long vid) {
        VersionedValue v = db.getWithOrd(GraphKeys.head(vid));
        return v == null ? Long.MIN_VALUE : v.ord();
    }

    // ═══ 写会话 ═══

    /** 提交结果。 */
    enum Commit { OK, CONFLICT, NOOP }

    /**
     * 写会话：同一批里改若干顶点，提交时每个改过的头带一个前置条件。{@code metaOps}：随批提交的额外 op（如首次写入时的 g 键）。
     * {@code checks} 是没改头、但参与存在性判断的端点读集：并发把「读到不存在」改成「存在」时必须让本批冲突重来。
     */
    public static final class Session {
        final Bitcask db;
        final GraphConfig cfg;
        final Map<Long, VertexState> vs = new HashMap<>();
        final List<BatchOp> checks = new ArrayList<>();
        private final Set<String> checkSeen = new HashSet<>();
        final List<BatchOp> metaOps;

        Session(Bitcask db, GraphConfig cfg, List<BatchOp> metaOps) {
            this.db = db;
            this.cfg = cfg;
            this.metaOps = List.copyOf(metaOps);
        }

        VertexState vertex(long vid) {
            VertexState v = vs.get(vid);
            if (v == null) {
                v = loadVertex(db, vid);
                vs.put(vid, v);
            }
            return v;
        }

        public void checkHead(long vid) {
            VertexState v = vertex(vid);
            String sig = vid + "/" + v.hcond;
            if (checkSeen.add(sig)) checks.add(BatchOp.check(GraphKeys.ascii(GraphKeys.head(vid)), v.hcond));
        }

        /** 加边（upsert；原样存在不写）。 */
        public void putEdge(long src, long etype, long dst, long rank, byte[] props) {
            long et = u32(etype);
            byte[] pstore = props.length > cfg.propsMax() ? EXT : props;
            VertexState v = vertex(src);
            byte[] cur = getEntry(db, v, 0, et, dst, rank);
            if (cur != null && !isExt(cur) && !isExt(pstore) && Arrays.equals(cur, pstore)) {
                checkHead(src);
                checkHead(dst);
                return;
            }
            byte[] old = putEntry(db, v, 0, et, dst, rank, pstore);
            if (isExt(pstore)) v.extra.add(BatchOp.put(GraphKeys.ascii(GraphKeys.propsKey(src, et, dst, rank)), props));
            else if (old != null && isExt(old)) v.extra.add(BatchOp.delete(GraphKeys.ascii(GraphKeys.propsKey(src, et, dst, rank))));
            if (old != null) checkHead(dst);
            else putEntry(db, vertex(dst), 1, et, src, rank, EMPTY);
        }

        /** 删边（幂等）。 */
        public void delEdge(long src, long etype, long dst, long rank) {
            long et = u32(etype);
            VertexState v = vertex(src);
            byte[] old = delEntry(db, v, 0, et, dst, rank);
            if (old == null) {
                checkHead(src);
                return;
            }
            if (isExt(old)) v.extra.add(BatchOp.delete(GraphKeys.ascii(GraphKeys.propsKey(src, et, dst, rank))));
            VertexState dv = vertex(dst);
            delEntry(db, dv, 1, et, src, rank);
            if (!dv.touched) checkHead(dst);
        }

        /** 顶点靠前的一段条目，够 maxOthers 个以上不同的对端就停（&lt; 0 = 全部）；done[0] = 读完。只读入用到的块。 */
        public List<FullEntry> firstEntries(long vid, long maxOthers, boolean[] done) {
            return vertexFirstEntries(db, vertex(vid), maxOthers, done);
        }

        public List<FullEntry> allEntries(long vid) {
            return firstEntries(vid, -1, new boolean[1]);
        }

        /** 会话里点查一条条目（看得到本会话之前的写）⇒ 属性（byte[] / EXT）或 null。 */
        public byte[] entry(long vid, int dir, long etype, long o, long r) {
            return getEntry(db, vertex(vid), dir, u32(etype), o, r);
        }

        /** 会话里顶点当前有没有邻接表。 */
        public boolean headExists(long vid) {
            return !vertex(vid).groups.isEmpty();
        }

        /** 提交 op（头 op 带条件 + 读集 + metaOps + extraOps）；什么都没改且 extra 为空 ⇒ null。 */
        public List<BatchOp> commitOps(List<BatchOp> extraOps) {
            TreeMap<Long, VertexState> sorted = new TreeMap<>(Long::compareUnsigned);
            sorted.putAll(vs);
            List<BatchOp> ops = new ArrayList<>();
            for (VertexState v : sorted.values()) ops.addAll(finalizeVertex(db, v, cfg));
            if (ops.isEmpty() && extraOps.isEmpty()) return null;
            ops.addAll(checks);
            ops.addAll(metaOps);
            ops.addAll(extraOps);
            return ops;
        }

        Commit commit(List<BatchOp> extraOps) {
            List<BatchOp> ops = commitOps(extraOps);
            if (ops == null) return Commit.NOOP;
            return db.tryWriteAtomic(ops).isConflict() ? Commit.CONFLICT : Commit.OK;
        }
    }

    // ═══ 迁移用 ═══

    /**
     * 从完整条目表直接建一个顶点的 op（目标库是新的、无条件）：out = [etype dst rank props]，in = [etype src rank]，
     * 各自已按 (etype, other, rank) 升序。⇒ op 表。
     */
    static List<BatchOp> buildVertex(long vid, List<Object[]> out, List<long[]> in, GraphConfig cfg) {
        List<BatchOp> ps = new ArrayList<>();
        Map<Long, List<AdjEntry>> byGroup = new LinkedHashMap<>();
        for (Object[] o : out) {
            long t = (Long) o[0], d = (Long) o[1], r = (Long) o[2];
            byte[] p = (byte[]) o[3];
            if (p.length > cfg.propsMax()) {
                ps.add(BatchOp.put(GraphKeys.ascii(GraphKeys.propsKey(vid, t, d, r)), p));
                p = EXT;
            }
            byGroup.computeIfAbsent(t, x -> new ArrayList<>()).add(new AdjEntry(d, r, p));
        }
        for (long[] i : in) byGroup.computeIfAbsent((1L << 32) | i[0], x -> new ArrayList<>()).add(new AdjEntry(i[1], i[2], EMPTY));
        List<Group> gs = new ArrayList<>();
        byGroup.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).forEach(e -> {
            long k = e.getKey();
            gs.add(Group.inline((int) (k >>> 32), k & 0xFFFFFFFFL, e.getValue().size(), 0, e.getValue()));
        });
        VertexState vs = new VertexState(vid, null, false, gs);
        vs.touched = true;
        chunkOversized(vs, cfg);
        List<BatchOp> ops = gs.isEmpty() ? new ArrayList<>() : emitOps(vs);
        ops.addAll(ps);
        return ops;
    }
}
