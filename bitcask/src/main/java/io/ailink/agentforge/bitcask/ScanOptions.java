package io.ailink.agentforge.bitcask;

import java.nio.charset.StandardCharsets;

/**
 * keydir 快照扫描（无序）的选项：{@code prefix}（仍是 O(全表)，省的是读值）、{@code meta}、{@code filter}、{@code batch}（同 {@link RangeOptions}）。
 */
public final class ScanOptions {
    byte[] prefix;
    boolean meta;
    Filter filter;
    int batch = 256;

    public static ScanOptions all() {
        return new ScanOptions();
    }

    public static ScanOptions prefix(byte[] prefix) {
        return new ScanOptions().withPrefix(prefix);
    }

    public static ScanOptions prefix(String prefix) {
        return new ScanOptions().withPrefix(prefix.getBytes(StandardCharsets.UTF_8));
    }

    public ScanOptions withPrefix(byte[] v) {
        prefix = v;
        return this;
    }

    public ScanOptions meta(boolean v) {
        meta = v;
        return this;
    }

    public ScanOptions filter(Filter v) {
        filter = v;
        return this;
    }

    public ScanOptions batch(int v) {
        if (v <= 0) throw new IllegalArgumentException("batch");
        batch = v;
        return this;
    }
}
