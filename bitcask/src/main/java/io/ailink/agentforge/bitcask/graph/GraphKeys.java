package io.ailink.agentforge.bitcask.graph;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * 图层的盘上键布局 V1（与 clj-bitcask {@code graph.keys}、Erlang bitcask 图层逐字节相同）。全 ASCII、定宽小写十六进制 ⇒
 * 字典序 = 数值序（vid / rank 按 64 位无符号、etype 按 32 位无符号）。所有图键都挂保留命名空间前缀 {@link #NS} = 0x01 {@code _g:}：
 * 首字节 0x01 普通文本 key 够不到，图键与业务键在字节层面隔离。
 * <pre>
 * n  vid:16                              顶点（值 = 文本，或带 meta 的文档：标签 _labels + 属性）
 * a  vid:16                              邻接表头
 * a  vid:16 dir:2 etype:8 other:16 rank:16   hub 的分块（(other, rank) 是块下界；dir 00 出 / 01 入）
 * p  src:16 etype:8 dst:16 rank:16       超过内联上限的边属性
 * g                                      图元信息（布局 1 / 2 + 分块参数）
 * t  etype:8                             etype 名字
 * tn len:4 name-utf8                     名字 ⇒ etype id（值 = u32 大端）
 * l  len:4 label-utf8 vid:16             标签索引项（值为空）
 * c                                      顶点 id 高水位（值 = 9 字节大端）
 * m  len:4 label-utf8                    Cypher MERGE 守卫（值 = u64 大端版本号）
 * r  src:16 etype:8 dst:16               删边 rank 下限（值 u64）
 * d  vid:16 dir:2 etype:8 level:2 other:16 rank:16   布局 2 的目录节点（keelson D-51；值 = 子节点下界表，见 Adjacency）
 * </pre>
 */
public final class GraphKeys {
    /** 图键保留命名空间前缀。 */
    public static final String NS = "\u0001_g:";
    private static final byte[] NS_BYTES = NS.getBytes(StandardCharsets.US_ASCII);
    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    /** 图元信息键。 */
    public static final String GRAPH_META = NS + "g";
    /** 顶点 id 高水位键。 */
    public static final String COUNTER = NS + "c";

    private GraphKeys() {
    }

    static void hex(StringBuilder sb, long n, int width) {
        char[] buf = new char[width];
        for (int i = width - 1; i >= 0; i--) {
            buf[i] = DIGITS[(int) (n & 0xF)];
            n >>>= 4;
        }
        sb.append(buf);
    }

    private static long u32(long v) {
        return v & 0xFFFFFFFFL;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static int familyOffset(byte[] k) {
        int n = NS_BYTES.length;
        return k.length >= n && Arrays.equals(NS_BYTES, 0, n, k, 0, n) ? n : k.length;
    }

    /** 家族扫描下界：前缀 + 家族字母（配合 {@link #successor} 罩住整个家族）。 */
    public static byte[] familyBytes(String fam) {
        return ascii(NS + fam);
    }

    /** 顶点键。 */
    public static String node(long vid) {
        StringBuilder sb = new StringBuilder(21).append(NS).append('n');
        hex(sb, vid, 16);
        return sb.toString();
    }

    public static byte[] nodeBytes(long vid) {
        return ascii(node(vid));
    }

    /** 邻接表头键。 */
    public static String head(long vid) {
        StringBuilder sb = new StringBuilder(21).append(NS).append('a');
        hex(sb, vid, 16);
        return sb.toString();
    }

    /** 分块键（63 格）。 */
    public static String chunkKey(long vid, int dir, long etype, long other, long rank) {
        StringBuilder sb = new StringBuilder(63).append(NS).append('a');
        hex(sb, vid, 16);
        hex(sb, dir, 2);
        hex(sb, u32(etype), 8);
        hex(sb, other, 16);
        hex(sb, rank, 16);
        return sb.toString();
    }

    /**
     * 布局 2 的目录节点键（65 格）：前缀 + d + vid + dir(2) + etype(8) + 层(2) + 下界 other(16) rank(16)。
     * 第 1 层节点的子节点是块，第 k 层的是第 k−1 层的节点；每层最左的节点下界恒为 (0, 0)，根 = (层数, (0, 0))。
     * 放进新家族 d 而不挂在 a 下：a vid 的前缀 range 仍恰好是「头 + 全部块」。
     */
    public static String dirNodeKey(long vid, int dir, long etype, int level, long other, long rank) {
        StringBuilder sb = new StringBuilder(65).append(NS).append('d');
        hex(sb, vid, 16);
        hex(sb, dir, 2);
        hex(sb, u32(etype), 8);
        hex(sb, level, 2);
        hex(sb, other, 16);
        hex(sb, rank, 16);
        return sb.toString();
    }

    /** 大属性键（61 格）。 */
    public static String propsKey(long src, long etype, long dst, long rank) {
        StringBuilder sb = new StringBuilder(61).append(NS).append('p');
        hex(sb, src, 16);
        hex(sb, u32(etype), 8);
        hex(sb, dst, 16);
        hex(sb, rank, 16);
        return sb.toString();
    }

    public static String etypeName(long id) {
        StringBuilder sb = new StringBuilder(13).append(NS).append('t');
        hex(sb, u32(id), 8);
        return sb.toString();
    }

    private static byte[] lenPrefixed(String fam, byte[] b) {
        StringBuilder sb = new StringBuilder().append(NS).append(fam);
        hex(sb, b.length, 4);
        byte[] head = ascii(sb.toString());
        byte[] k = Arrays.copyOf(head, head.length + b.length);
        System.arraycopy(b, 0, k, head.length, b.length);
        return k;
    }

    /** 名字 ⇒ tn 键。名字 ≥ 65535 字节 ⇒ {@code bitcask/graph-etype-name-too-long}。 */
    public static byte[] etypeByName(String nm) {
        byte[] b = nm.getBytes(StandardCharsets.UTF_8);
        if (b.length >= 65535) throw BitcaskException.of("bitcask/graph-etype-name-too-long", nm);
        return lenPrefixed("tn", b);
    }

    /** 删边 rank 下限键：删掉的关系 id 不再分给新关系（同 bitcask 的 r 键族）。 */
    public static String rankFloor(long src, long etype, long dst) {
        StringBuilder sb = new StringBuilder(45).append(NS).append('r');
        hex(sb, src, 16);
        hex(sb, u32(etype), 8);
        hex(sb, dst, 16);
        return sb.toString();
    }

    /** 家族字母 + vid（n / a 家族里 vid 的下界）。 */
    public static byte[] familyVid(String fam, long vid) {
        StringBuilder sb = new StringBuilder(21).append(NS).append(fam);
        hex(sb, vid, 16);
        return ascii(sb.toString());
    }

    /** 某个标签全部索引项的公共前缀。名字为空或 ≥ 65535 字节 ⇒ {@code bitcask/graph-label-invalid}。 */
    public static byte[] labelPrefix(String label) {
        byte[] b = label.getBytes(StandardCharsets.UTF_8);
        if (b.length == 0 || b.length >= 65535) throw BitcaskException.of("bitcask/graph-label-invalid", label);
        return lenPrefixed("l", b);
    }

    /** MERGE 守卫键（null 标签 = 没有标签的 MERGE）。 */
    public static byte[] mergeGuard(String label) {
        return lenPrefixed("m", label == null ? new byte[0] : label.getBytes(StandardCharsets.UTF_8));
    }

    /** 标签索引项键。 */
    public static byte[] labelKey(String label, long vid) {
        byte[] p = labelPrefix(label);
        StringBuilder sb = new StringBuilder(16);
        hex(sb, vid, 16);
        byte[] k = Arrays.copyOf(p, p.length + 16);
        System.arraycopy(ascii(sb.toString()), 0, k, p.length, 16);
        return k;
    }

    /** 字典序后继：去掉尾部 0xFF、末字节 +1；全 0xFF ⇒ null。 */
    public static byte[] successor(byte[] k) {
        for (int i = k.length - 1; i >= 0; i--) {
            if (k[i] != (byte) 0xFF) {
                byte[] r = Arrays.copyOf(k, i + 1);
                r[i]++;
                return r;
            }
        }
        return null;
    }

    /** 定宽 hex 字段 ⇒ long；有非小写 hex 字符 ⇒ -1 不可用，这里用 Long 返回 null。 */
    private static Long tryHex(byte[] s, int from, int len) {
        long v = 0;
        for (int i = from, end = from + len; i < end; i++) {
            int c = s[i];
            int d;
            if (c >= 48 && c <= 57) d = c - 48;
            else if (c >= 97 && c <= 102) d = c - 87;
            else return null;
            v = (v << 4) | d;
        }
        return v;
    }

    /** a 头键 ⇒ vid；不是 ⇒ null。 */
    public static Long parseHead(byte[] k) {
        int off = familyOffset(k);
        if (off + 17 == k.length && k[off] == 'a') return tryHex(k, off + 1, 16);
        return null;
    }

    /** 分块键的解析结果。 */
    public record ChunkKey(long vid, int dir, long etype, long other, long rank) {
    }

    public static ChunkKey parseChunk(byte[] k) {
        int off = familyOffset(k);
        if (off + 59 != k.length || k[off] != 'a') return null;
        Long v = tryHex(k, off + 1, 16), d = tryHex(k, off + 17, 2), t = tryHex(k, off + 19, 8),
                o = tryHex(k, off + 27, 16), r = tryHex(k, off + 43, 16);
        if (v == null || d == null || t == null || o == null || r == null || d < 0 || d > 1) return null;
        return new ChunkKey(v, d.intValue(), t, o, r);
    }

    public static ChunkKey parseChunk(String k) {
        return parseChunk(ascii(k));
    }

    /** 目录节点键的解析结果。 */
    public record DirNodeKey(long vid, int dir, long etype, int level, long other, long rank) {
    }

    /** d 目录节点键（带前缀，65 格）⇒ 字段；不是 ⇒ null。 */
    public static DirNodeKey parseDirNode(byte[] k) {
        int off = familyOffset(k);
        if (off + 61 != k.length || k[off] != 'd') return null;
        Long v = tryHex(k, off + 1, 16), d = tryHex(k, off + 17, 2), t = tryHex(k, off + 19, 8), l = tryHex(k, off + 27, 2),
                o = tryHex(k, off + 29, 16), r = tryHex(k, off + 45, 16);
        if (v == null || d == null || t == null || l == null || o == null || r == null || d < 0 || d > 1 || l <= 0) return null;
        return new DirNodeKey(v, d.intValue(), t, l.intValue(), o, r);
    }

    public static DirNodeKey parseDirNode(String k) {
        return parseDirNode(ascii(k));
    }

    /** p 键 ⇒ [src etype dst rank]；不是 ⇒ null。 */
    public static long[] parsePropsKey(byte[] k) {
        int off = familyOffset(k);
        if (off + 57 != k.length || k[off] != 'p') return null;
        Long s = tryHex(k, off + 1, 16), t = tryHex(k, off + 17, 8), d = tryHex(k, off + 25, 16), r = tryHex(k, off + 41, 16);
        if (s == null || t == null || d == null || r == null) return null;
        return new long[]{s, t, d, r};
    }

    /** n 顶点键 ⇒ vid；不是 ⇒ null。 */
    public static Long parseNode(byte[] k) {
        int off = familyOffset(k);
        if (off + 17 == k.length && k[off] == 'n') return tryHex(k, off + 1, 16);
        return null;
    }

    public static boolean isEtypeNameKey(byte[] k) {
        int off = familyOffset(k);
        if (k.length < off + 6 || k[off] != 't' || k[off + 1] != 'n') return false;
        Long n = tryHex(k, off + 2, 4);
        return n != null && k.length == off + 6 + n;
    }

    /** t 键 ⇒ etype id；不是 ⇒ null。 */
    public static Long parseEtypeKey(byte[] k) {
        int off = familyOffset(k);
        if (off + 9 == k.length && k[off] == 't') return tryHex(k, off + 1, 8);
        return null;
    }

    /** 标签索引项的解析结果。 */
    public record LabelKey(String label, long vid) {
    }

    public static LabelKey parseLabelKey(byte[] k) {
        int off = familyOffset(k);
        if (k.length < off + 5 + 16 || k[off] != 'l') return null;
        Long n = tryHex(k, off + 1, 4);
        if (n == null || k.length != off + 5 + n + 16) return null;
        Long vid = tryHex(k, (int) (off + 5 + n), 16);
        if (vid == null) return null;
        return new LabelKey(new String(k, off + 5, n.intValue(), StandardCharsets.UTF_8), vid);
    }

    /** 键尾 16 格 hex ⇒ vid；不是 ⇒ null。 */
    public static Long vidFromTail(byte[] k) {
        return k.length >= 16 ? tryHex(k, k.length - 16, 16) : null;
    }

    public static byte[] u64Bytes(long n) {
        return ByteBuffer.allocate(8).putLong(n).array();
    }

    public static byte[] u32Bytes(long n) {
        return ByteBuffer.allocate(4).putInt((int) n).array();
    }

    public static long bytesToU64(byte[] b) {
        return ByteBuffer.wrap(b).getLong();
    }

    public static long bytesToU32(byte[] b) {
        return ByteBuffer.wrap(b).getInt() & 0xFFFFFFFFL;
    }
}
