package io.ailink.agentforge.bitcask.graph;

import java.nio.ByteBuffer;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * 图参数（g 键）。首次写入时写下，之后以盘上的为准。两种布局（keelson D-51，designs/graph-layout-2.md）：
 * <ul>
 * <li>布局 1：头超 {@code inlineMax} 字节分块、单块 ≤ {@code chunkMax}（超大组放宽到 组字节 / 1024）、块目录总在头里；
 *     g = &lt;&lt;1, InlineMax:32, ChunkMax:32, PropsMax:16&gt;&gt;（11 B），其余字段为 0。</li>
 * <li>布局 2（新库缺省）：块按条数切（{@code chunkEntries}），{@code chunkMax} 是字节安全上限；每组块数安全阀 {@code chunkCountMax}
 *     （超过后每块条数随组放大，0 = 不限）；块数超过 {@code dirInlineMax} 时块目录外置成 d 家族的 B+ 树，节点最多 {@code dirNodeMax} 项；
 *     g = &lt;&lt;2, InlineMax:32, ChunkMax:32, PropsMax:16, ChunkEntries:32, ChunkCountMax:32, DirInlineMax:16, DirNodeMax:16&gt;&gt;（23 B）。</li>
 * </ul>
 * 属性超 {@code propsMax} 落 p 家族。
 */
public record GraphConfig(int layout, long inlineMax, long chunkMax, long propsMax,
                          long chunkEntries, long chunkCountMax, long dirInlineMax, long dirNodeMax) {
    /** 新库的缺省 = 布局 2：头超 4 KiB 分块；每块 512 条、字节安全上限 64 KiB；安全阀 65536 块；块数 &gt; 16 目录外置，节点 ≤ 256 项。 */
    public static final GraphConfig DEFAULT = new GraphConfig(2, 4096, 65536, 128, 512, 65536, 16, 256);
    /** 布局 1 的缺省（块目录总在头里、单块 ≤ 4 KiB、超大组的块上限放宽到 组字节 / 1024）。 */
    public static final GraphConfig LAYOUT1 = new GraphConfig(1, 4096, 4096, 128, 0, 0, 0, 0);

    public boolean layout2() {
        return layout == 2;
    }

    /** 同 clj {@code init!} 的 {@code {:adj-inline-max i :adj-chunk-max c :props-inline-max p}}：布局 2，其余参数取缺省（带校验）。 */
    public static GraphConfig of(long inlineMax, long chunkMax, long propsMax) {
        return options().adjInlineMax(inlineMax).adjChunkMax(chunkMax).propsInlineMax(propsMax).build();
    }

    public static Options options() {
        return new Options();
    }

    /**
     * init / upgradeLayout 的选项（同 clj {@code init!} 的 opts；不给的取所在布局的缺省）。{@code adjChunkMax}：布局 1 是单块字节上限（缺省 4096），
     * 布局 2 是单块字节安全上限（缺省 65536）；{@code adjChunkEntries} / {@code adjChunkCountMax} / {@code adjDirInlineMax} / {@code adjDirNodeMax}
     * 只对布局 2 有意义。
     */
    public static final class Options {
        Integer layout;
        Long adjInlineMax, adjChunkMax, propsInlineMax, adjChunkEntries, adjChunkCountMax, adjDirInlineMax, adjDirNodeMax;

        public Options layout(int v) {
            layout = v;
            return this;
        }

        public Options adjInlineMax(long v) {
            adjInlineMax = v;
            return this;
        }

        public Options adjChunkMax(long v) {
            adjChunkMax = v;
            return this;
        }

        public Options propsInlineMax(long v) {
            propsInlineMax = v;
            return this;
        }

        public Options adjChunkEntries(long v) {
            adjChunkEntries = v;
            return this;
        }

        public Options adjChunkCountMax(long v) {
            adjChunkCountMax = v;
            return this;
        }

        public Options adjDirInlineMax(long v) {
            adjDirInlineMax = v;
            return this;
        }

        public Options adjDirNodeMax(long v) {
            adjDirNodeMax = v;
            return this;
        }

        Options copy() {
            Options o = new Options();
            o.layout = layout;
            o.adjInlineMax = adjInlineMax;
            o.adjChunkMax = adjChunkMax;
            o.propsInlineMax = propsInlineMax;
            o.adjChunkEntries = adjChunkEntries;
            o.adjChunkCountMax = adjChunkCountMax;
            o.adjDirInlineMax = adjDirInlineMax;
            o.adjDirNodeMax = adjDirNodeMax;
            return o;
        }

        /** 选项 ⇒ 参数（同 clj {@code cfg-from-opts}）：布局只收 1 / 2；各参数越界 ⇒ {@code bitcask/graph-config-invalid}。 */
        public GraphConfig build() {
            int l = layout == null ? 2 : layout;
            if (l != 1 && l != 2) throw BitcaskException.of("bitcask/graph-config-invalid", "layout", String.valueOf(l));
            GraphConfig d = l == 1 ? LAYOUT1 : DEFAULT;
            long im = or(adjInlineMax, d.inlineMax), cm = or(adjChunkMax, d.chunkMax), pm = or(propsInlineMax, d.propsMax);
            check("inline-max", im, 64, 0xFFFFFFFFL);
            check("chunk-max", cm, 64, 0xFFFFFFFFL);
            check("props-max", pm, 0, 0xFFFE);
            if (l == 1) return new GraphConfig(1, im, cm, pm, 0, 0, 0, 0);
            long ce = or(adjChunkEntries, d.chunkEntries), cc = or(adjChunkCountMax, d.chunkCountMax),
                    di = or(adjDirInlineMax, d.dirInlineMax), dn = or(adjDirNodeMax, d.dirNodeMax);
            check("chunk-entries", ce, 2, 0xFFFFFFFFL);
            check("chunk-count-max", cc, 0, 0xFFFFFFFFL);
            check("dir-inline-max", di, 2, 0xFFFF);
            check("dir-node-max", dn, 4, 0xFFFF);
            return new GraphConfig(2, im, cm, pm, ce, cc, di, dn);
        }

        private static long or(Long v, long d) {
            return v == null ? d : v;
        }
    }

    /** 已有参数按所在布局重新校验（{@code init(GraphConfig)} 用）。 */
    GraphConfig validated() {
        Options o = options().layout(layout).adjInlineMax(inlineMax).adjChunkMax(chunkMax).propsInlineMax(propsMax);
        if (layout == 2) o.adjChunkEntries(chunkEntries).adjChunkCountMax(chunkCountMax).adjDirInlineMax(dirInlineMax).adjDirNodeMax(dirNodeMax);
        return o.build();
    }

    private static void check(String k, long v, long lo, long hi) {
        if (v < lo || v > hi) throw BitcaskException.of("bitcask/graph-config-invalid", k, String.valueOf(v));
    }

    byte[] encode() {
        boolean two = layout2();
        ByteBuffer bb = ByteBuffer.allocate(two ? 23 : 11);
        bb.put((byte) (two ? 2 : 1)).putInt((int) inlineMax).putInt((int) chunkMax).putShort((short) propsMax);
        if (two) bb.putInt((int) chunkEntries).putInt((int) chunkCountMax).putShort((short) dirInlineMax).putShort((short) dirNodeMax);
        return bb.array();
    }

    /** g 的值 ⇒ 参数（带布局 1 / 2）；认不得的布局 ⇒ null。 */
    static GraphConfig decode(byte[] b) {
        if (b == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        if (b.length == 11 && b[0] == 1) {
            bb.get();
            long i = bb.getInt() & 0xFFFFFFFFL, c = bb.getInt() & 0xFFFFFFFFL, p = bb.getShort() & 0xFFFF;
            return new GraphConfig(1, i, c, p, 0, 0, 0, 0);
        }
        if (b.length == 23 && b[0] == 2) {
            bb.get();
            long i = bb.getInt() & 0xFFFFFFFFL, c = bb.getInt() & 0xFFFFFFFFL, p = bb.getShort() & 0xFFFF;
            long ce = bb.getInt() & 0xFFFFFFFFL, cc = bb.getInt() & 0xFFFFFFFFL, di = bb.getShort() & 0xFFFF, dn = bb.getShort() & 0xFFFF;
            return new GraphConfig(2, i, c, p, ce, cc, di, dn);
        }
        return null;
    }
}
