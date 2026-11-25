package io.ailink.agentforge.bitcask;

/**
 * 高亮检索选项：{@code k}、{@code preTag} / {@code postTag}（null = 上游缺省 &lt;em&gt; / &lt;/em&gt;）、
 * {@code fragmentSize}（0 = 120 字节）、{@code maxFragments}（0 = 3 段）、{@code cold}（见 {@link ColdMode}）。
 */
public final class HighlightOptions {
    /**
     * 冷文档（原文不在 libbitcask 进程内 LRU 里）的处理：{@code SKIP} = 命中照返、片段为空；
     * {@code LOAD}（libbitcask 9.1.0）= 从存储读回正文、按本库分析器切片段（片段与热路径逐字相同；读回的不回填 LRU）。
     */
    public enum ColdMode { SKIP, LOAD }

    int k = 10;
    String preTag;
    String postTag;
    long fragmentSize;
    long maxFragments;
    ColdMode cold = ColdMode.SKIP;

    public static HighlightOptions defaults() {
        return new HighlightOptions();
    }

    public HighlightOptions k(int v) {
        if (v < 0) throw new IllegalArgumentException("k");
        k = v;
        return this;
    }

    public HighlightOptions tags(String pre, String post) {
        preTag = pre;
        postTag = post;
        return this;
    }

    public HighlightOptions fragmentSize(long v) {
        fragmentSize = Math.max(0, v);
        return this;
    }

    public HighlightOptions maxFragments(long v) {
        maxFragments = Math.max(0, v);
        return this;
    }

    public HighlightOptions cold(ColdMode v) {
        cold = v == null ? ColdMode.SKIP : v;
        return this;
    }
}
