package io.ailink.agentforge.bitcask;

/**
 * 检索选项：{@code k}（缺省 10）、{@code offset}（缺省 0；深分页成本线性；只有词袋 / 短语 / 布尔认）、{@code filter}（meta 过滤）、
 * {@code slop}（近邻，缺省 0 = 短语）、{@code maxEdits}（模糊，缺省 1）、{@code ef}（向量探索宽度，0 = max(k, 64)）。
 * ⚠️ 过滤非空时没有 meta 段的文档一律不通过；后过滤命中不足 k 时上游自动补取。
 */
public final class SearchOptions {
    int k = 10;
    int offset;
    Filter filter;
    int slop;
    int maxEdits = 1;
    int ef;

    public static SearchOptions defaults() {
        return new SearchOptions();
    }

    public static SearchOptions k(int k) {
        return new SearchOptions().withK(k);
    }

    private static int nat(int v, String name) {
        if (v < 0) throw new IllegalArgumentException(name);
        return v;
    }

    public SearchOptions withK(int v) {
        k = nat(v, "k");
        return this;
    }

    public SearchOptions offset(int v) {
        offset = nat(v, "offset");
        return this;
    }

    public SearchOptions filter(Filter v) {
        filter = v;
        return this;
    }

    public SearchOptions slop(int v) {
        slop = nat(v, "slop");
        return this;
    }

    public SearchOptions maxEdits(int v) {
        maxEdits = nat(v, "maxEdits");
        return this;
    }

    public SearchOptions ef(int v) {
        ef = nat(v, "ef");
        return this;
    }
}
