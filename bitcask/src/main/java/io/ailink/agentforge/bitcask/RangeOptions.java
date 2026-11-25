package io.ailink.agentforge.bitcask;

import java.nio.charset.StandardCharsets;

/**
 * 有序 range 的选项：[lo, hi)（null / 空 = 不限）、{@code prefetch}（&gt; 1 时归并一批 key 后并发取值，只改时机）、
 * {@code meta}（带出 meta 段）、{@code filter}（meta 过滤；给了即带 meta）、{@code batch}（集合式遍历每次跨 FFI 取几条，缺省 256）。
 */
public final class RangeOptions {
    byte[] lo;
    byte[] hi;
    int prefetch;
    boolean meta;
    Filter filter;
    int batch = 256;

    public static RangeOptions all() {
        return new RangeOptions();
    }

    /** [lo, hi)。 */
    public static RangeOptions between(byte[] lo, byte[] hi) {
        return new RangeOptions().lo(lo).hi(hi);
    }

    public static RangeOptions between(String lo, String hi) {
        return new RangeOptions().lo(lo).hi(hi);
    }

    /** 以 {@code prefix} 开头的 key（hi = 前缀的字典序后继）。 */
    public static RangeOptions prefix(byte[] prefix) {
        return new RangeOptions().lo(prefix).hi(successor(prefix));
    }

    public static RangeOptions prefix(String prefix) {
        return prefix(prefix.getBytes(StandardCharsets.UTF_8));
    }

    /** 字典序上紧跟所有以 {@code p} 开头的串的那个串（末尾的 0xFF 进位）；全是 0xFF ⇒ null（不限）。 */
    public static byte[] successor(byte[] p) {
        int n = p.length;
        while (n > 0 && p[n - 1] == (byte) 0xFF) n--;
        if (n == 0) return null;
        byte[] out = java.util.Arrays.copyOf(p, n);
        out[n - 1]++;
        return out;
    }

    public RangeOptions lo(byte[] v) {
        lo = v;
        return this;
    }

    public RangeOptions lo(String v) {
        lo = v == null ? null : v.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public RangeOptions hi(byte[] v) {
        hi = v;
        return this;
    }

    public RangeOptions hi(String v) {
        hi = v == null ? null : v.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public RangeOptions prefetch(int v) {
        prefetch = Math.max(0, v);
        return this;
    }

    public RangeOptions meta(boolean v) {
        meta = v;
        return this;
    }

    public RangeOptions filter(Filter v) {
        filter = v;
        return this;
    }

    public RangeOptions batch(int v) {
        if (v <= 0) throw new IllegalArgumentException("batch");
        batch = v;
        return this;
    }
}
