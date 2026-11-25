package io.ailink.agentforge.bitcask.graph;

import java.nio.ByteBuffer;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * 图参数（g 键，布局 1）：头超 {@code inlineMax} 字节分块、单块 ≤ {@code chunkMax}、属性超 {@code propsMax} 落 p 家族。
 * 首次写入时写下，之后以盘上的为准。
 */
public record GraphConfig(long inlineMax, long chunkMax, long propsMax) {
    public static final GraphConfig DEFAULT = new GraphConfig(4096, 4096, 128);

    /** 带校验的构造（同 clj-bitcask init! 的取值范围；盘上读回的不校验）。 */
    public static GraphConfig of(long inlineMax, long chunkMax, long propsMax) {
        check("inline-max", inlineMax, 64, 0xFFFFFFFFL);
        check("chunk-max", chunkMax, 64, 0xFFFFFFFFL);
        check("props-max", propsMax, 0, 0xFFFE);
        return new GraphConfig(inlineMax, chunkMax, propsMax);
    }

    private static void check(String k, long v, long lo, long hi) {
        if (v < lo || v > hi) throw BitcaskException.of("bitcask/graph-config-invalid", k, String.valueOf(v));
    }

    byte[] encode() {
        return ByteBuffer.allocate(11).put((byte) 1).putInt((int) inlineMax).putInt((int) chunkMax).putShort((short) propsMax).array();
    }

    /** g 的值 ⇒ 参数；不是布局 1 ⇒ null。 */
    static GraphConfig decode(byte[] b) {
        if (b == null || b.length != 11 || b[0] != 1) return null;
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.get();
        long i = bb.getInt() & 0xFFFFFFFFL, c = bb.getInt() & 0xFFFFFFFFL, p = bb.getShort() & 0xFFFF;
        return new GraphConfig(i, c, p);
    }
}
