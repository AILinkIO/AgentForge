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
 *         Body（分块、目录外置，布局 2）= &lt;&lt;NChunks:32, Levels:8&gt;&gt;（块目录是 d 家族的一棵 B+ 树）
 * Chunk = &lt;&lt;1:8, GFlags:8, Count:32, Entry × Count&gt;&gt;
 * DirNode = &lt;&lt;1:8, Level:8, Count:16, &lt;&lt;Other:64, Rank:64&gt;&gt; × Count&gt;&gt;（子节点下界，升序；首项 = 节点自己的下界）
 * GFlags：bit0 有 rank ≠ 0、bit1 有属性、bit2 已分块、bit3 目录外置（布局 2）
 * Entry = &lt;&lt;Other:64&gt;&gt; [&lt;&lt;Rank:64&gt;&gt;] [&lt;&lt;PLen:16, Props&gt;&gt;]；PLen = 0xFFFF ⇒ 属性在 p 家族
 * </pre>
 * 不变式：① 对称（a 的出边组有 (t, b, r) ⟺ b 的入边组有 (t, a, r)，同批写）；② 头即版本（邻接的任何修改都重写头 ⇒ 写会话只对头设前置条件）；
 * ③ Count = 实际条目数、块目录 = 实际块键。条目的属性是 byte[] 或哨兵 {@link #EXT}（按身份比较）。other / rank 按无符号排序。
 * <p>布局 2（keelson D-51，designs/graph-layout-2.md）：每一组边是 KV 上的一棵 B+ 树 —— 块是叶子，d 家族的目录节点是内部节点，
 * 头里只记块数与层数；加一条边 = 重写一个块 + 很小的头，目录节点只在块分裂 / 删空时才写，写入量与度数无关。
 * 节点靠 key（层, 下界）定位、不原地修改；删除不合并 / 再平衡，只删空块、空节点。并发仍是「头即版本」。
 * <p>clj 版写会话里的「字节视图」优化没有移植（只影响速度，落盘字节相同）；目录节点读路径保留了字节视图（{@link LbView}）。
 */
public final class Adjacency {
    static final int F_RANK = 1, F_PROPS = 2, F_CHUNKED = 4, F_XDIR = 8;
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

    /** 下界的无符号序。 */
    static final Comparator<LB> LB_ORDER = (a, b) -> cmp(a.o, a.r, b.o, b.r);

    /** 目录外置的组在头里的形态（布局 2）：块数、目录树层数（≥ 1）。 */
    public record XDir(long n, int levels) {
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
     * 组：内联组带 {@code entries}，分块组带 {@code chunks}（头里的下界表）或 {@code xdir}（布局 2 目录外置：任何解码方式都带上）。
     * RAW 解码的组不带条目：内联组 {@code bytes + raw}（条目区起点），分块组 {@code bytes + raw + nchunks}（块目录起点）；
     * SKIP 的组只能拿来数 count。
     */
    public record Group(int dir, long etype, long count, int flags, List<AdjEntry> entries, List<LB> chunks, XDir xdir, byte[] bytes, int raw,
                 long nchunks, boolean skipped) {
        static Group inline(int dir, long etype, long count, int flags, List<AdjEntry> es) {
            return new Group(dir, etype, count, flags, es, null, null, null, -1, -1, false);
        }

        static Group chunked(int dir, long etype, long count, int flags, List<LB> lbs) {
            return new Group(dir, etype, count, flags, null, lbs, null, null, -1, -1, false);
        }

        static Group external(int dir, long etype, long count, int flags, XDir x) {
            return new Group(dir, etype, count, flags, null, null, x, null, -1, -1, false);
        }

        Group withEntries(List<AdjEntry> es, long cnt) {
            return new Group(dir, etype, cnt, flags, es, null, null, null, -1, -1, false);
        }

        Group withCount(long cnt, int fl) {
            return new Group(dir, etype, cnt, fl, entries, chunks, xdir, bytes, raw, nchunks, skipped);
        }

        Group withChunks(List<LB> lbs) {
            return new Group(dir, etype, count, flags, null, lbs, null, null, -1, -1, false);
        }

        Group withXdir(XDir x) {
            return new Group(dir, etype, count, flags, null, null, x, null, -1, -1, false);
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

    /** 组写进头的 GFlags：分块组用维护着的 flags + 分块位（目录外置再加 bit3）；内联组按条目现算（删条目可能清掉某一位）。 */
    private static int groupFlags(Group g) {
        if (g.xdir != null) return (g.flags & 3) | F_CHUNKED | F_XDIR;
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
            size += 14 + (g.xdir != null ? 5 : g.chunks != null ? 4 + 16L * g.chunks.size() : entriesSize(g.entries, fs[i]));
        }
        ByteBuffer bb = ByteBuffer.allocate(Math.toIntExact(size));
        bb.put((byte) 1).putShort((short) n);
        for (int i = 0; i < n; i++) {
            Group g = groups.get(i);
            bb.put((byte) g.dir).put((byte) fs[i]).putInt((int) g.etype).putLong(g.count);
            if (g.xdir != null) bb.putInt((int) g.xdir.n).put((byte) g.xdir.levels);
            else if (g.chunks != null) {
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
                if ((f & F_XDIR) != 0) {
                    // 目录外置（布局 2）：头里只有块数与层数；任何解码方式都原样带上（只有 5 字节）
                    if ((f & F_CHUNKED) == 0) return null;
                    long nc = bb.getInt() & 0xFFFFFFFFL;
                    int lv = bb.get() & 0xFF;
                    if (nc <= 0 || lv <= 0) return null;
                    acc.add(new Group(d, t, c, f & 3, null, null, new XDir(nc, lv), null, -1, -1, w == Want.SKIP));
                } else if ((f & F_CHUNKED) == 0) {
                    if (c > bb.remaining() / 8) return null;
                    switch (w) {
                        case RAW -> {
                            int at = bb.position();
                            skipEntries(bb, c, f);
                            acc.add(new Group(d, t, c, f, null, null, null, b, at, -1, false));
                        }
                        case DECODE -> acc.add(Group.inline(d, t, c, f, readEntries(bb, c, f)));
                        case SKIP -> {
                            skipEntries(bb, c, f);
                            acc.add(new Group(d, t, c, f, null, null, null, null, -1, -1, true));
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
                        acc.add(w == Want.RAW ? new Group(d, t, c, f & 3, null, null, null, b, at, nc, false)
                                : new Group(d, t, c, f & 3, null, null, null, null, -1, -1, true));
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

    // ═══ 目录节点（布局 2）═══

    /** 目录节点 ⇒ 值：&lt;&lt;1, Level:8, Count:16, &lt;&lt;Other:64, Rank:64&gt;&gt; × Count&gt;&gt;。 */
    static byte[] encodeNode(int level, List<LB> lbs) {
        int n = lbs.size();
        ByteBuffer bb = ByteBuffer.allocate(4 + 16 * n);
        bb.put((byte) 1).put((byte) level).putShort((short) n);
        for (LB lb : lbs) bb.putLong(lb.o).putLong(lb.r);
        return bb.array();
    }

    /** 下界表的字节视图（不解码，按下标取）：节点里的下界表、点查只在字节上二分（整节点解码曾让大号的点查慢 8 倍）。 */
    static final class LbView extends java.util.AbstractList<LB> implements java.util.RandomAccess {
        private final byte[] b;
        private final int off, n;

        LbView(byte[] b, int off, int n) {
            this.b = b;
            this.off = off;
            this.n = n;
        }

        @Override
        public LB get(int i) {
            if (i < 0 || i >= n) throw new IndexOutOfBoundsException(i);
            ByteBuffer bb = ByteBuffer.wrap(b);
            int p = off + 16 * i;
            return new LB(bb.getLong(p), bb.getLong(p + 8));
        }

        @Override
        public int size() {
            return n;
        }
    }

    /** 解开的目录节点。 */
    public record Node(int level, List<LB> lbs) {
    }

    /** 目录节点的值 ⇒ 层号 + 下界视图；长度不符、Count = 0、层号 0、下界不是严格升序 ⇒ null。 */
    static Node nodeView(byte[] b) {
        if (b == null || b.length < 4 || b[0] != 1) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        int level = b[1] & 0xFF;
        int n = bb.getShort(2) & 0xFFFF;
        if (n <= 0 || level <= 0 || b.length != 4 + 16 * n) return null;
        for (int i = 1; i < n; i++) {
            int p = 4 + 16 * i;
            if (cmp(bb.getLong(p - 16), bb.getLong(p - 8), bb.getLong(p), bb.getLong(p + 8)) >= 0) return null;
        }
        return new Node(level, new LbView(b, 4, n));
    }

    /** 目录节点的值 ⇒ 层号 + 下界表（拷贝）；畸形 ⇒ null（见 {@link #nodeView}）。 */
    public static Node decodeNode(byte[] b) {
        Node v = nodeView(b);
        return v == null ? null : new Node(v.level, new ArrayList<>(v.lbs));
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

    /**
     * 顶点状态。布局 2 的目录节点按键缓存在 {@code nodes}（键 ⇒ 下界表）；改过的进 {@code ndirty}，要删的进 {@code ndels}；
     * 读到过、盘上确实有的块 / 节点进 {@code ondisk}（外置目录的组在头里没有块目录，删块时靠它判断盘上有没有）。
     * 读不到某个节点：只可能是读头之后有人改过这个顶点 ⇒ 标 {@code stale}，提交时整批当冲突重来（不在过期的树上继续写）。
     */
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
        final Map<String, List<LB>> nodes = new HashMap<>();
        final TreeSet<String> ndirty = new TreeSet<>();
        final TreeSet<String> ndels = new TreeSet<>();
        final Set<String> ondisk = new HashSet<>();
        boolean touched;
        boolean stale;
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
            vs.ondisk.add(k);
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

    // ═══ 目录树（布局 2）：会话内 ═══

    private static String nodeKey(long vid, int dir, long etype, int level, LB lb) {
        return GraphKeys.dirNodeKey(vid, dir, etype, level, lb.o, lb.r);
    }

    /** 读目录节点（缓存）⇒ 下界表；读不到 ⇒ 标过期、按 [(0,0)] 继续（这次计算不会被提交）。 */
    private static List<LB> getNode(Bitcask db, VertexState vs, String k) {
        List<LB> lbs = vs.nodes.get(k);
        if (lbs != null) return lbs;
        byte[] bv = db.get(k);
        if (bv == null) {
            vs.stale = true;
            return List.of(LB0);
        }
        Node nv = nodeView(bv);
        if (nv == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", k);
        vs.nodes.put(k, nv.lbs);
        vs.ondisk.add(k);
        return nv.lbs;
    }

    private static void setNode(VertexState vs, String k, List<LB> lbs) {
        vs.nodes.put(k, lbs);
        vs.ndirty.add(k);
        vs.ndels.remove(k);
    }

    private static void delNode(VertexState vs, String k) {
        vs.nodes.remove(k);
        vs.ndirty.remove(k);
        vs.ndels.add(k);
    }

    /** 路径上的一步：层、节点下界、节点的下界表。 */
    private record Step(int level, LB nlb, List<LB> lbs) {
    }

    /** 沿目录树走到 (o, r) 所在的块：路径（自根往下，最后一个是第 1 层）写进 path ⇒ 块下界。 */
    private static LB xwalk(Bitcask db, VertexState vs, int dir, long etype, int levels, long o, long r, List<Step> path) {
        LB nlb = LB0;
        for (int level = levels; ; level--) {
            List<LB> lbs = getNode(db, vs, nodeKey(vs.vid, dir, etype, level, nlb));
            path.add(new Step(level, nlb, lbs));
            LB c = locateLb(lbs, o, r);
            if (level == 1) return c;
            nlb = c;
        }
    }

    /** 整棵树 ⇒ 全部块下界（有序）；全部节点键追加进 nkeys。 */
    private static List<LB> xall(Bitcask db, VertexState vs, int dir, long etype, int levels, List<String> nkeys) {
        List<LB> nlbs = List.of(LB0);
        for (int level = levels; ; level--) {
            List<LB> children = new ArrayList<>();
            for (LB nlb : nlbs) {
                String k = nodeKey(vs.vid, dir, etype, level, nlb);
                children.addAll(getNode(db, vs, k));
                nkeys.add(k);
            }
            if (level == 1) return children;
            nlbs = children;
        }
    }

    /** 下界表里按序插入（已在就不动）⇒ 新表。 */
    private static List<LB> insLb(List<LB> lbs, LB lb) {
        int lo = 0, hi = lbs.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            LB m = lbs.get(mid);
            int c = cmp(m.o, m.r, lb.o, lb.r);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return lbs;
        }
        List<LB> n = new ArrayList<>(lbs.size() + 1);
        n.addAll(lbs);
        n.add(lo, lb);
        return n;
    }

    /**
     * 第 1 层节点（路径末尾）换成 lbs1，自底向上处理溢出（&gt; dirNodeMax 对半分裂、新节点下界登记进上一层；根分裂 ⇒ 长一层）⇒ 新层数。
     */
    private static int xputPath(VertexState vs, int dir, long etype, int levels, List<Step> path, List<LB> lbs1, GraphConfig cfg) {
        long vid = vs.vid;
        List<LB> lbs = lbs1;
        for (int i = path.size() - 1; ; i--) {
            Step st = path.get(i);
            String k = nodeKey(vid, dir, etype, st.level, st.nlb);
            if (lbs.size() <= cfg.dirNodeMax()) {
                setNode(vs, k, lbs);
                return levels;
            }
            int half = (lbs.size() + 1) / 2;
            List<LB> left = new ArrayList<>(lbs.subList(0, half)), right = new ArrayList<>(lbs.subList(half, lbs.size()));
            LB rlb = right.getFirst();
            setNode(vs, k, left);
            setNode(vs, nodeKey(vid, dir, etype, st.level, rlb), right);
            if (i == 0) {
                // 根分裂：新根在上一层，下界 (0,0)
                setNode(vs, nodeKey(vid, dir, etype, st.level + 1, LB0), List.of(LB0, rlb));
                return levels + 1;
            }
            Step up = path.get(i - 1);
            List<LB> plbs = vs.nodes.getOrDefault(nodeKey(vid, dir, etype, st.level + 1, up.nlb), up.lbs);
            lbs = insLb(plbs, rlb);
        }
    }

    /** 全部块下界 ⇒ 新建一棵树（节点装满到 dirNodeMax）⇒ 层数。 */
    private static int xbuild(VertexState vs, int dir, long etype, List<LB> lbs, GraphConfig cfg) {
        int m = (int) cfg.dirNodeMax();
        List<LB> cur = lbs;
        for (int level = 1; ; level++) {
            List<LB> firsts = new ArrayList<>();
            for (int i = 0; i < cur.size(); i += m) {
                List<LB> part = new ArrayList<>(cur.subList(i, Math.min(cur.size(), i + m)));
                setNode(vs, nodeKey(vs.vid, dir, etype, level, part.getFirst()), part);
                firsts.add(part.getFirst());
            }
            if (firsts.size() == 1) return level;
            cur = firsts;
        }
    }

    /**
     * 路径第 i 个节点里的子项 old 改名为 neu（old 是被改名的子节点的旧下界）；old 恰好是这个节点的首项（= 节点自己的下界）
     * ⇒ 这个节点也要改名，继续往上改。根的下界恒为 (0,0)，不会被改名。
     */
    private static void xrelabel(VertexState vs, int dir, long etype, List<Step> path, int i, LB old, LB neu) {
        Step st = path.get(i);
        String k = nodeKey(vs.vid, dir, etype, st.level, st.nlb);
        List<LB> cur = vs.nodes.get(k);
        List<LB> lbs = new ArrayList<>(cur == null ? 0 : cur.size());
        if (cur != null) for (LB x : cur) lbs.add(x.equals(old) ? neu : x);
        if (st.nlb.equals(old)) {
            delNode(vs, k);
            setNode(vs, nodeKey(vs.vid, dir, etype, st.level, neu), lbs);
            xrelabel(vs, dir, etype, path, i - 1, old, neu);
        } else setNode(vs, k, lbs);
    }

    /**
     * 从路径第 i 个节点里摘掉子项 x：摘空了 ⇒ 删这个节点，再从上一层摘掉它（最左一路的下界是 (0,0)、首项 (0,0) 的块从不删，所以摘不到根）；
     * x 是首项 ⇒ 节点改名为新的首项（上一层跟着改，必要时继续往上）；否则就地改。
     * 删到节点首块时改名而不是留空块撑着：大量删边后空块会撑着树、目录转不回内联。
     */
    private static void xremove(VertexState vs, int dir, long etype, List<Step> path, int i, LB x) {
        Step st = path.get(i);
        String k = nodeKey(vs.vid, dir, etype, st.level, st.nlb);
        List<LB> lbs = new ArrayList<>();
        List<LB> cur = vs.nodes.get(k);
        if (cur != null) for (LB y : cur) if (!y.equals(x)) lbs.add(y);
        if (lbs.isEmpty()) {
            delNode(vs, k);
            xremove(vs, dir, etype, path, i - 1, st.nlb);
        } else if (x.equals(st.nlb)) {
            LB nl = lbs.getFirst();
            delNode(vs, k);
            setNode(vs, nodeKey(vs.vid, dir, etype, st.level, nl), lbs);
            xrelabel(vs, dir, etype, path, i - 1, st.nlb, nl);
        } else setNode(vs, k, lbs);
    }

    /** 目录外置的组整组删空：删全部块与全部目录节点。块按「目录 = 实际块」的不变式视为盘上已有。 */
    private static void xteardown(Bitcask db, VertexState vs, int dir, long etype, int levels) {
        List<String> nkeys = new ArrayList<>();
        List<LB> lbs = xall(db, vs, dir, etype, levels, nkeys);
        for (LB lb : lbs) {
            String k = chunkKey(vs.vid, dir, etype, lb);
            vs.ondisk.add(k);
            delChunk(vs, k);
        }
        for (String k : nkeys) delNode(vs, k);
    }

    /** 分块组里 (o, r) 所在块的下界（目录内联：头里二分；外置：沿树走）。 */
    private static LB leafLb(Bitcask db, VertexState vs, int dir, long etype, Group g, long o, long r) {
        if (g.xdir != null) return xwalk(db, vs, dir, etype, g.xdir.levels, o, r, new ArrayList<>());
        return locateLb(g.chunks, o, r);
    }

    private static byte[] getEntry(Bitcask db, VertexState vs, int dir, long etype, long o, long r) {
        Group g = findGroup(vs.groups, dir, etype);
        if (g == null) return null;
        if (g.entries != null) return entFind(g.entries, o, r);
        return entFind(getChunk(db, vs, chunkKey(vs.vid, dir, etype, leafLb(db, vs, dir, etype, g, o, r))), o, r);
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
        String k = chunkKey(vs.vid, dir, etype, leafLb(db, vs, dir, etype, g, o, r));
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
        String k = chunkKey(vs.vid, dir, etype, leafLb(db, vs, dir, etype, g, o, r));
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
        // 外置目录的组先把块下界列出来（同 clj：在逐块读之前整树走一遍）
        Map<Group, List<LB>> lbsOf = new HashMap<>();
        for (Group g : List.copyOf(vs.groups))
            if (g.xdir != null) lbsOf.put(g, xall(db, vs, g.dir, g.etype, g.xdir.levels, new ArrayList<>()));
        Set<Long> seen = new HashSet<>();
        List<FullEntry> acc = new ArrayList<>();
        for (Group g : List.copyOf(vs.groups)) {
            List<LB> lbs = g.entries != null ? null : g.chunks != null ? g.chunks : lbsOf.get(g);
            int nUnits = g.entries != null ? 1 : lbs.size();
            for (int u = 0; u < nUnits; u++) {
                if (maxOthers >= 0 && seen.size() > maxOthers) {
                    done[0] = false;
                    return acc;
                }
                List<AdjEntry> es = g.entries != null ? g.entries : getChunk(db, vs, chunkKey(vs.vid, g.dir, g.etype, lbs.get(u)));
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

    /** 布局 1：超大组的块上限放宽到 组字节 / 1024，让块目录保持在约 1024 项内。 */
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

    /**
     * 布局 2 每块条数的有效上限：chunkEntries；组大到「块数安全阀 × 七成满」以上时随组放大（安全阀 0 = 不限）。
     * 缺省 65536 块：约 2300 万条以内不起作用，1 亿条时约 1500 条 / 块，目录最多三层。
     */
    private static long effEntries(long cnt, GraphConfig cfg) {
        long e = cfg.chunkEntries(), m = cfg.chunkCountMax();
        return m > 0 ? Math.max(e, (long) Math.ceil((double) cnt / (0.7 * (double) m))) : e;
    }

    /** 按条数与字节贪心切片（块头 6 B）：加上下一条会超过 maxe 条或 maxb 字节就切；每片至少一条。 */
    private static List<List<AdjEntry>> splitBy(List<AdjEntry> es, long maxe, long maxb) {
        int f = flagsOf(es);
        List<List<AdjEntry>> acc = new ArrayList<>();
        List<AdjEntry> cur = new ArrayList<>();
        long sz = 6;
        for (AdjEntry e : es) {
            long esz = entrySize(e, f);
            if (!cur.isEmpty() && (cur.size() >= maxe || sz + esz > maxb)) {
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

    /** 已有块超限就切（布局 1：按字节、超大组放宽；布局 2：条数 / 字节两个上限取先到的，切成约等大的 n 片）；没超 ⇒ [es]。 */
    private static List<List<AdjEntry>> splitChunk(List<AdjEntry> es, long cnt, GraphConfig cfg) {
        if (!cfg.layout2()) return splitBalanced(es, effChunkMax(cnt, cfg));
        long e = effEntries(cnt, cfg), b = cfg.chunkMax();
        long n = es.size(), size = 6 + bodySize(es);
        if (n <= e && size <= b) return List.of(es);
        long k = Math.max(2, Math.max((n + e - 1) / e, (size + b - 1) / b));
        return splitBy(es, (n + k - 1) / k, Math.max(b, 0));
    }

    /** 内联组转分块时的切片：布局 1 按字节装满；布局 2 按条数 / 字节装满。 */
    private static List<List<AdjEntry>> splitInitial(List<AdjEntry> es, long cnt, GraphConfig cfg) {
        return cfg.layout2() ? splitBy(es, effEntries(cnt, cfg), cfg.chunkMax()) : splitEntries(es, effChunkMax(cnt, cfg));
    }

    /**
     * 一个分块组的收尾：只处理本组改过的块（dlbs）——空块（非块 0）删掉、超限的切片；块目录只在有块被删 / 新增时改：
     * 内联目录重建，外置目录在树上逐个摘除 / 登记（节点溢出向上分裂），根只剩一个子节点 ⇒ 降一层。
     */
    private static void normalizeGroup(Bitcask db, VertexState vs, Group g, GraphConfig cfg, Set<LB> dlbs) {
        long vid = vs.vid;
        if (g.count == 0) {
            if (g.xdir != null) xteardown(db, vs, g.dir, g.etype, g.xdir.levels);
            else for (LB lb : g.chunks) delChunk(vs, chunkKey(vid, g.dir, g.etype, lb));
            dropGroup(vs.groups, g.dir, g.etype);
            return;
        }
        if (dlbs == null || dlbs.isEmpty()) return;
        Set<LB> removed = new HashSet<>();
        Map<LB, List<LB>> extra = new HashMap<>();
        for (LB lb : dlbs) {
            String k = chunkKey(vid, g.dir, g.etype, lb);
            List<AdjEntry> es = vs.chunks.getOrDefault(k, List.of());
            if (es.isEmpty() && !lb.equals(LB0)) {
                delChunk(vs, k);
                removed.add(lb);
            } else {
                List<List<AdjEntry>> pieces = splitChunk(es, g.count, cfg);
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
        if (g.xdir != null) {
            int levels = g.xdir.levels;
            // 摘除删掉的块（节点摘空就删、摘到首项就改名，见 xremove）
            List<LB> rs = new ArrayList<>(removed);
            rs.sort(LB_ORDER);
            for (LB lb : rs) {
                List<Step> path = new ArrayList<>();
                xwalk(db, vs, g.dir, g.etype, levels, lb.o, lb.r, path);
                xremove(vs, g.dir, g.etype, path, path.size() - 1, lb);
            }
            // 登记新块：逐个沿树找到所在的第 1 层节点插进去，溢出就向上分裂
            List<LB> adds = new ArrayList<>();
            for (List<LB> more : extra.values()) adds.addAll(more);
            adds.sort(LB_ORDER);
            for (LB nl : adds) {
                List<Step> path = new ArrayList<>();
                xwalk(db, vs, g.dir, g.etype, levels, nl.o, nl.r, path);
                levels = xputPath(vs, g.dir, g.etype, levels, path, insLb(path.getLast().lbs, nl), cfg);
            }
            long n = g.xdir.n + adds.size() - removed.size();
            // 树变矮：根只剩一个子节点（必是 (0,0) 那个）⇒ 删根、层数减一
            while (true) {
                String rk = nodeKey(vid, g.dir, g.etype, levels, LB0);
                List<LB> rl = getNode(db, vs, rk);
                if (levels > 1 && rl.size() == 1) {
                    delNode(vs, rk);
                    levels--;
                } else break;
            }
            storeGroup(vs.groups, g.withXdir(new XDir(n, levels)));
            return;
        }
        List<LB> lbs = new ArrayList<>();
        for (LB lb : g.chunks) {
            if (!removed.contains(lb)) lbs.add(lb);
            if (extra.containsKey(lb)) lbs.addAll(extra.get(lb));
        }
        storeGroup(vs.groups, g.withChunks(lbs));
    }

    private static void normalizeChunked(Bitcask db, VertexState vs, GraphConfig cfg) {
        Map<Long, Set<LB>> d = new HashMap<>();
        for (String k : vs.dirty) {
            GraphKeys.ChunkKey ck = GraphKeys.parseChunk(k);
            d.computeIfAbsent(((long) ck.dir() << 32) | ck.etype(), x -> new LinkedHashSet<>()).add(new LB(ck.other(), ck.rank()));
        }
        for (Group g : List.copyOf(vs.groups)) {
            if (g.chunks != null || g.xdir != null) normalizeGroup(db, vs, g, cfg, d.get(((long) g.dir << 32) | g.etype));
        }
    }

    /** 布局 2：内联目录的块数超过 dirInlineMax ⇒ 外置成树；外置目录的块数降到 dirInlineMax / 2 以下 ⇒ 转回内联（滞回）。 */
    private static void xrebalance(Bitcask db, VertexState vs, GraphConfig cfg) {
        if (!cfg.layout2()) return;
        for (Group g : List.copyOf(vs.groups)) {
            if (g.chunks != null && g.chunks.size() > cfg.dirInlineMax()) {
                int levels = xbuild(vs, g.dir, g.etype, g.chunks, cfg);
                vs.headBytes = null;
                storeGroup(vs.groups, g.withXdir(new XDir(g.chunks.size(), levels)));
            } else if (g.xdir != null && g.xdir.n < cfg.dirInlineMax() / 2) {
                List<String> nkeys = new ArrayList<>();
                List<LB> lbs = xall(db, vs, g.dir, g.etype, g.xdir.levels, nkeys);
                for (String k : nkeys) delNode(vs, k);
                vs.headBytes = null;
                storeGroup(vs.groups, g.withChunks(lbs));
            }
        }
    }

    /** 分块组缩到 inlineMax / 4 以下 ⇒ 转回内联（滞回：分块门是头 &gt; inlineMax）。外置目录的组连同全部目录节点一起删。 */
    private static void unchunkSmall(Bitcask db, VertexState vs, GraphConfig cfg) {
        long thresh = cfg.inlineMax() / 4;
        for (Group g : List.copyOf(vs.groups)) {
            if (g.chunks == null && g.xdir == null) continue;
            long minW = 8 + ((g.flags & F_RANK) == 0 ? 0 : 8) + ((g.flags & F_PROPS) == 0 ? 0 : 2);
            if (g.count * minW >= thresh) continue;
            List<String> nkeys = new ArrayList<>();
            List<LB> lbs = g.xdir != null ? xall(db, vs, g.dir, g.etype, g.xdir.levels, nkeys) : g.chunks;
            List<AdjEntry> es = loadGroupChunks(db, vs, g.dir, g.etype, lbs);
            if (bodySize(es) >= thresh) continue;
            for (LB lb : lbs) delChunk(vs, chunkKey(vs.vid, g.dir, g.etype, lb));
            for (String k : nkeys) delNode(vs, k);
            vs.headBytes = null;
            storeGroup(vs.groups, Group.inline(g.dir, g.etype, g.count, 0, es));
        }
    }

    private static void chunkify(VertexState vs, Group g, GraphConfig cfg) {
        List<List<AdjEntry>> pieces = splitInitial(g.entries, g.count, cfg);
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

    /** 头 op（带条件）+ 块删除（只删读到时盘上已有的块）+ 块写入 + 目录节点删除 / 写入 + p 家族 op。 */
    private static List<BatchOp> emitOps(VertexState vs) {
        List<BatchOp> ops = new ArrayList<>();
        byte[] hk = GraphKeys.ascii(GraphKeys.head(vs.vid));
        if (!vs.groups.isEmpty()) ops.add(BatchOp.put(hk, vs.headBytes != null ? vs.headBytes : encodeHead(vs.groups), vs.hcond));
        else if (vs.existed) ops.add(BatchOp.delete(hk, vs.hcond));
        for (String k : vs.dels) {
            boolean onDisk = vs.ondisk.contains(k);
            if (!onDisk) {
                GraphKeys.ChunkKey ck = GraphKeys.parseChunk(k);
                Group g = findGroup(vs.orig, ck.dir(), ck.etype());
                LB lb = new LB(ck.other(), ck.rank());
                onDisk = g != null && g.chunks != null && lb.equals(locateLb(g.chunks, lb.o, lb.r));
            }
            if (onDisk) ops.add(BatchOp.delete(GraphKeys.ascii(k)));
        }
        for (String k : vs.dirty) ops.add(BatchOp.put(GraphKeys.ascii(k), encodeChunk(vs.chunks.get(k))));
        for (String k : vs.ndels) if (vs.ondisk.contains(k)) ops.add(BatchOp.delete(GraphKeys.ascii(k)));
        for (String k : vs.ndirty) ops.add(BatchOp.put(GraphKeys.ascii(k), encodeNode(GraphKeys.parseDirNode(k).level(), vs.nodes.get(k))));
        ops.addAll(vs.extra);
        return ops;
    }

    private static List<BatchOp> finalizeVertex(Bitcask db, VertexState vs, GraphConfig cfg) {
        if (!vs.touched) return List.of();
        if (vs.groups.size() > 0xFFFF) throw BitcaskException.of("bitcask/graph-too-many-groups", vs.groups.size());
        normalizeChunked(db, vs, cfg);
        unchunkSmall(db, vs, cfg);
        xrebalance(db, vs, cfg);
        chunkOversized(vs, cfg);
        xrebalance(db, vs, cfg);
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

    /** 只读路径读一个目录节点 ⇒ 下界视图；不存在 ⇒ null（弱一致读：读头之后有人改过这个顶点）；畸形 ⇒ {@code bitcask/graph-adjacency-malformed}。 */
    private static List<LB> readNodeLbs(Bitcask db, long vid, int dir, long etype, int level, LB lb) {
        String k = nodeKey(vid, dir, etype, level, lb);
        byte[] bv = db.get(k);
        if (bv == null) return null;
        Node nv = nodeView(bv);
        if (nv == null) throw BitcaskException.of("bitcask/graph-adjacency-malformed", k);
        return nv.lbs;
    }

    /** 目录外置的组的全部块下界（惰性、按序）：自根深度优先，只读到用到的节点为止（取前 N 条时只走最左一路）。 */
    public static java.util.Iterator<LB> xleafIter(Bitcask db, long vid, int dir, long etype, int levels) {
        return new java.util.Iterator<>() {
            private final java.util.ArrayDeque<Object[]> stack = new java.util.ArrayDeque<>(); // [level, lbs, int[]{i}]
            private boolean started;
            private LB next;

            private void advance() {
                if (!started) {
                    started = true;
                    List<LB> root = readNodeLbs(db, vid, dir, etype, levels, LB0);
                    stack.push(new Object[]{levels, root == null ? List.of() : root, new int[]{0}});
                }
                while (next == null && !stack.isEmpty()) {
                    Object[] top = stack.peek();
                    int level = (Integer) top[0];
                    @SuppressWarnings("unchecked") List<LB> lbs = (List<LB>) top[1];
                    int[] i = (int[]) top[2];
                    if (i[0] >= lbs.size()) {
                        stack.pop();
                        continue;
                    }
                    LB x = lbs.get(i[0]++);
                    if (level == 1) next = x;
                    else {
                        List<LB> sub = readNodeLbs(db, vid, dir, etype, level - 1, x);
                        stack.push(new Object[]{level - 1, sub == null ? List.of() : sub, new int[]{0}});
                    }
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next != null;
            }

            @Override
            public LB next() {
                advance();
                if (next == null) throw new java.util.NoSuchElementException();
                LB x = next;
                next = null;
                return x;
            }
        };
    }

    /** 目录外置的组里 (o, r) 所在块的下界（逐层点读）；中途缺节点 ⇒ null。 */
    private static LB xlocate(Bitcask db, long vid, int dir, long etype, int levels, long o, long r) {
        LB lb = LB0;
        for (int level = levels; ; level--) {
            List<LB> lbs = readNodeLbs(db, vid, dir, etype, level, lb);
            if (lbs == null) return null;
            lb = locateLb(lbs, o, r);
            if (level == 1) return lb;
        }
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
                if ((f & F_XDIR) != 0) {
                    if ((f & F_CHUNKED) == 0) return false;
                    if ((bb.getInt() & 0xFFFFFFFFL) <= 0 || (bb.get() & 0xFF) <= 0) return false;
                } else if ((f & F_CHUNKED) == 0) {
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
            if (g.xdir != null) {
                for (var it = xleafIter(db, vid, dir, g.etype, g.xdir.levels); it.hasNext() && !(limit >= 0 && acc.size() >= limit); ) {
                    String k = chunkKey(vid, dir, g.etype, it.next());
                    byte[] bv = db.get(k);
                    if (bv != null) {
                        int[] fc = chunkArea(k, bv);
                        areaRows(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], g.etype, mk, acc, limit >= 0 ? limit - acc.size() : -1);
                    }
                }
            } else if (g.isRawChunked()) {
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
        if (g.xdir != null) {
            LB lb = xlocate(db, vid, dir, et, g.xdir.levels, o, r);
            if (lb == null) return null;
            String k = chunkKey(vid, dir, et, lb);
            byte[] bv = db.get(k);
            if (bv == null) return null;
            int[] fc = chunkArea(k, bv);
            return areaFind(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], o, r);
        }
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
            if (g.xdir != null) {
                for (var it = xleafIter(db, vid, g.dir, g.etype, g.xdir.levels); it.hasNext(); ) {
                    String k = chunkKey(vid, g.dir, g.etype, it.next());
                    byte[] bv = db.get(k);
                    if (bv != null) {
                        int[] fc = chunkArea(k, bv);
                        areaIds(bv, 6, fc[1] & 0xFFFFFFFFL, fc[0], sink, dedupe, st);
                    }
                }
            } else if (g.isRawChunked()) {
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

        /** 会话读目录树时遇到过缺失的节点（读头之后有人改过这个顶点）：这时算出来的 op 不能提交，按冲突重来。 */
        public boolean stale() {
            for (VertexState v : vs.values()) if (v.stale) return true;
            return false;
        }

        /** 提交 ⇒ OK | CONFLICT（有人先改了某个头，或读到一半的目录树已被别人改过）| NOOP。 */
        Commit commit(List<BatchOp> extraOps) {
            if (stale()) return Commit.CONFLICT;
            List<BatchOp> ops = commitOps(extraOps);
            if (ops == null) return Commit.NOOP;
            return db.tryWriteAtomic(ops).isConflict() ? Commit.CONFLICT : Commit.OK;
        }
    }

    // ═══ 目录树校验（checkInvariants 用）═══

    /** 一棵目录树的核对结果：块下界、走到的节点键、问题。 */
    record XdirCheck(List<LB> lbs, Set<String> nodes, List<List<Object>> problems) {
    }

    /**
     * 逐个节点核对一棵目录树：节点读得到、解得开、层号与键一致、首项 = 节点下界、下界严格升序（跨节点也是）、第 1 层的项数之和 = 头里记的块数 n。
     */
    static XdirCheck xdirCheck(Bitcask db, long vid, int dir, long etype, int levels, long n) {
        List<List<Object>> problems = new ArrayList<>();
        Set<String> nodes = new HashSet<>();
        List<LB> nlbs = List.of(LB0);
        List<LB> lbs;
        for (int level = levels; ; level--) {
            List<LB> children = new ArrayList<>();
            for (LB nlb : nlbs) {
                String k = nodeKey(vid, dir, etype, level, nlb);
                byte[] bv = db.get(k);
                Node nd = bv == null ? null : decodeNode(bv);
                nodes.add(k);
                List<Object> where = List.of(vid, dir, etype, level, List.of(nlb.o, nlb.r));
                if (bv == null) problems.add(cat("missing-dir-node", where));
                else if (nd == null) problems.add(cat("bad-dir-node", where));
                else {
                    if (nd.level != level) problems.add(cat("dir-node-level", where, nd.level));
                    else if (!nlb.equals(nd.lbs.getFirst())) problems.add(cat("dir-node-bound", where));
                    children.addAll(nd.lbs);
                }
            }
            if (level == 1) {
                lbs = children;
                break;
            }
            nlbs = children;
        }
        for (int i = 1; i < lbs.size(); i++) {
            if (LB_ORDER.compare(lbs.get(i - 1), lbs.get(i)) >= 0) {
                problems.add(List.of("dir-order", vid, dir, etype));
                break;
            }
        }
        if (n != lbs.size()) problems.add(List.of("dir-chunk-count", vid, dir, etype, n, (long) lbs.size()));
        return new XdirCheck(lbs, nodes, problems);
    }

    private static List<Object> cat(String tag, List<Object> where, Object... more) {
        List<Object> p = new ArrayList<>();
        p.add(tag);
        p.addAll(where);
        p.addAll(Arrays.asList(more));
        return p;
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
