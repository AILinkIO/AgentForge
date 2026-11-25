package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.Kv;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;
import io.ailink.agentforge.bitcask.ffm.Results;

/**
 * 检索库：在 KV 之上写文档、BM25（词袋 / 短语 / 布尔 / 字段 / 近邻 / 模糊 / 通配 / 高亮）、向量 ANN、按给定 key 精确排序、
 * RRF 混合、批量检索。写入对检索是 near-real-time 的：要确定的可见点调 {@link #flush()}。
 * <p>已有的库缺省按库里记的分词器配置开（{@code STORED}，libbitcask 10.0.0）：不传分词器也能开，检索与高亮都跟建库时一致；
 * 显式传了与库不符的分词器 ⇒ {@code bitcask/analyzer-mismatch}。已有的库要用建库时<b>完全一致</b>的向量配置开，
 * 否则 {@code bitcask/mode-mismatch}（{@link BitcaskException#data()} 带 {@code metaVector} / {@code requestedVector} 两边的
 * {@link VectorConfig}）；不知道建库配置 ⇒ 先 {@link Bitcask#probe}。
 */
public final class BitcaskIndex extends Bitcask {
    private static final int DEFAULT_K = 10;

    private BitcaskIndex(io.ailink.agentforge.bitcask.ffm.NativeHandle.CaskHandle h, Path dir, int dim) {
        super(h, dir, dim, true);
    }

    // ═══ 打开 ═══

    public static BitcaskIndex open(Path dir) {
        return open(dir, null);
    }

    public static BitcaskIndex open(String dir, Options opts) {
        return open(Path.of(dir), opts);
    }

    /** 打开（不存在则创建）一个检索库（KV 选项 + 检索选项同一份 {@link Options}）。 */
    public static BitcaskIndex open(Path dir, Options opts) {
        checkDirectory(dir);
        Options o = opts == null ? Options.defaults() : opts;
        boolean exists = Files.exists(dir.resolve(Probe.META_FILE));
        Options.Analyzer analyzer = o.analyzer() != null ? o.analyzer() : exists ? Options.Analyzer.STORED : Options.Analyzer.NGRAM;
        ProbeResult probed = exists ? tryProbe(dir) : null;
        checkAnalyzer(dir, o, analyzer, probed);
        int dim = o.vectorDimension();
        if (dim < 0 || dim > 0xFFFF) throw BitcaskException.of("bitcask/vector-dimension-range");
        String jieba = needsJieba(analyzer, probed) ? resolveJiebaDict(o.jiebaDictPath()) : o.jiebaDictPath();
        try (var a = new NativeArena()) {
            MemorySegment no = a.alloc(NativeTypes.OPTIONS);
            BitcaskNative.optionsInit(no);
            o.kvToNative(no);
            o.indexToNative(no, analyzer, jieba, a);
            return new BitcaskIndex(openHandle(dir, o, no, a, "BitcaskIndex"), dir, dim);
        } catch (BitcaskException e) {
            throw withVectorValues(e, dir, o);
        }
    }

    private static ProbeResult tryProbe(Path dir) {
        try {
            return Probe.probe(dir);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 要不要 jieba 词典：显式 JIEBA 要；STORED 时库里记的不是 jieba（probe 说得清）就不要——
     * 否则（记的是 jieba / 没记录 / 探不出）照样要，因为缺词典时 cppjieba 会 abort 整个进程。
     */
    private static boolean needsJieba(Options.Analyzer analyzer, ProbeResult probed) {
        if (analyzer == Options.Analyzer.JIEBA) return true;
        if (analyzer != Options.Analyzer.STORED) return false;
        return probed == null || probed.analyzer() == null || probed.analyzer().analyzer() == Options.Analyzer.JIEBA;
    }

    /** jieba 目录：显式给的优先，否则缺省的。交给 C 侧之前逐个核文件（缺一个 cppjieba 会 abort 整个进程）。 */
    private static String resolveJiebaDict(String explicit) {
        String dir = explicit != null ? explicit : BitcaskRuntime.defaultJiebaDictPath();
        int invalid = BitcaskError.INVALID_OPTION.value();
        if (dir == null) throw BitcaskException.fault(invalid, "bitcask_open", "bitcask/jieba-no-dict", BitcaskRuntime.JIEBA_RELATIVE_PATH);
        List<String> missing = BitcaskRuntime.missingJiebaDictFiles(dir);
        if (!missing.isEmpty())
            throw BitcaskException.fault(invalid, "bitcask_open", "bitcask/jieba-dict-partial", dir, String.join(", ", missing));
        return dir;
    }

    /**
     * 显式分词器与库记录不符 ⇒ 稳定错误码（libbitcask 对指纹不符只经 log 告警；但 10.0.0 已把配置记进 meta，
     * 调用方明确要求不要静默换切词）。
     */
    private static void checkAnalyzer(Path dir, Options o, Options.Analyzer analyzer, ProbeResult p) {
        if (analyzer == Options.Analyzer.STORED || o.analyzer() == null || p == null || p.analyzer() == null) return;
        ProbeResult.AnalyzerConfig r = p.analyzer();
        boolean mismatch = analyzer != r.analyzer()
                || (o.ngramMin() != null && o.ngramMin() != r.ngramMin())
                || (o.ngramMax() != null && o.ngramMax() != r.ngramMax())
                || (o.stemming() != null && o.stemming() != r.stemming())
                || (o.stopWords() != null && o.stopWords() != r.stopWords());
        if (mismatch) throw BitcaskException.fault(BitcaskError.ANALYZER_MISMATCH.value(), "bitcask_open", "bitcask/analyzer-mismatch");
    }

    /**
     * 向量配置不一致的 mode-mismatch：libbitcask 的 detail 只说「不一致」不给值 ⇒ 补上库里的值（probe）与选项的值，
     * 消息末尾也写出来。错误码不变（调用方照旧按 bitcask/mode-mismatch 分支）。别的错原样。
     */
    private static BitcaskException withVectorValues(BitcaskException e, Path dir, Options o) {
        if (e.error() != BitcaskError.MODE_MISMATCH || e.detail() == null || !e.detail().contains("vector config")) return e;
        ProbeResult p = tryProbe(dir);
        if (p == null || p.vector() == null) return e;
        VectorConfig have = p.vector(), want = o.requestedVector();
        return e.withData(" — " + Messages.text("bitcask/vector-config-mismatch", have, want), Map.of("metaVector", have, "requestedVector", want));
    }

    // ═══ 写 ═══

    /** 写入（覆盖）一篇文档（{@code bitcask_put_doc_ex}）。向量长度须等于库的维度（空 = 无向量）。 */
    public void putDocument(byte[] key, Document doc) {
        int n = doc.vector().length;
        if (n != 0 && n != vectorDimension()) throw BitcaskException.of("bitcask/dim-mismatch", n, vectorDimension());
        Kv.putDoc(handle, key, doc);
    }

    public void putDocument(String key, Document doc) {
        putDocument(utf8(key), doc);
    }

    /** 只有正文的文档。 */
    public void putDocument(String key, String text) {
        putDocument(utf8(key), Document.of(text));
    }

    /** 删除文档（同 {@link #delete}）。 */
    public void remove(String key) {
        delete(key);
    }

    /** 刷新异步索引队列：之前的写入此后对检索可见。 */
    public void flush() {
        MemorySegment cask = handle.acquire();
        try {
            BitcaskNative.flushIndex(cask);
        } finally {
            handle.release();
        }
    }

    // ═══ 骨架 ═══

    @FunctionalInterface
    private interface Call {
        int call(MemorySegment cask, NativeArena a, MemorySegment out, MemorySegment fault);
    }

    private <T> T run(String op, Call call, Function<MemorySegment, T> read, java.util.function.Consumer<MemorySegment> free) {
        MemorySegment cask = handle.acquire();
        try (var a = new NativeArena()) {
            MemorySegment out = a.pointer();
            MemorySegment f = a.fault();
            int err = call.call(cask, a, out, f);
            MemorySegment r = out.get(ValueLayout.ADDRESS, 0);
            try {
                Faults.check(err, f, op);
                return read.apply(r);
            } finally {
                if (!r.equals(MemorySegment.NULL)) free.accept(r);
            }
        } finally {
            handle.release();
        }
    }

    private List<SearchHit> searching(String op, Call call) {
        return run(op, call, Results::hits, BitcaskNative::searchResultFree);
    }

    private static String q(String s, String name) {
        return Objects.requireNonNull(s, name);
    }

    private static SearchOptions opts(SearchOptions o) {
        return o == null ? SearchOptions.defaults() : o;
    }

    // ═══ BM25 ═══

    public List<SearchHit> searchText(String query) {
        return searchText(query, null);
    }

    /** BM25 词袋检索。认 k / offset / filter。 */
    public List<SearchHit> searchText(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        return searching("bitcask_search_text_ex",
                (c, a, out, f) -> BitcaskNative.searchTextEx(c, a.utf8z(qq), o.k, FilterEncoder.toNative(o.filter, a), o.offset, out, f));
    }

    public List<SearchHit> searchPhrase(String query) {
        return searchPhrase(query, null);
    }

    /** 短语检索。认 k / offset / filter。 */
    public List<SearchHit> searchPhrase(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_phrase_ex", (c, a, out, f) -> BitcaskNative.searchPhraseEx(c, a.utf8z(qq), o.k, o.offset, out, f));
        return searching("bitcask_search_phrase_filtered",
                (c, a, out, f) -> BitcaskNative.searchPhraseFiltered(c, a.utf8z(qq), o.k, FilterEncoder.toNative(o.filter, a), o.offset, out, f));
    }

    public List<SearchHit> searchBool(String query) {
        return searchBool(query, null);
    }

    /** 布尔检索（AND / OR / NOT）。认 k / offset / filter。 */
    public List<SearchHit> searchBool(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_bool_search_ex", (c, a, out, f) -> BitcaskNative.boolSearchEx(c, a.utf8z(qq), o.k, o.offset, out, f));
        return searching("bitcask_bool_search_filtered",
                (c, a, out, f) -> BitcaskNative.boolSearchFiltered(c, a.utf8z(qq), o.k, FilterEncoder.toNative(o.filter, a), o.offset, out, f));
    }

    public List<FieldsHit> searchFields(String query) {
        return searchFields(query, null);
    }

    /** 多字段检索（{@code field:term^boost}）；命中附命中的字段名。认 k / filter。 */
    public List<FieldsHit> searchFields(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return run("bitcask_search_fields_ex", (c, a, out, f) -> BitcaskNative.searchFieldsEx(c, a.utf8z(qq), o.k, out, f),
                    Results::fields, BitcaskNative::searchFieldsResultFree);
        return run("bitcask_search_fields_ex_filtered",
                (c, a, out, f) -> BitcaskNative.searchFieldsExFiltered(c, a.utf8z(qq), o.k, FilterEncoder.toNative(o.filter, a), out, f),
                Results::fields, BitcaskNative::searchFieldsResultFree);
    }

    public List<SearchHit> searchNear(String query) {
        return searchNear(query, null);
    }

    /** 近邻检索：词项间最多隔 slop 个位置（缺省 0 = 短语）。认 slop / k / filter。 */
    public List<SearchHit> searchNear(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_near", (c, a, out, f) -> BitcaskNative.searchNear(c, a.utf8z(qq), o.slop, o.k, out, f));
        return searching("bitcask_search_near_filtered",
                (c, a, out, f) -> BitcaskNative.searchNearFiltered(c, a.utf8z(qq), o.slop, o.k, FilterEncoder.toNative(o.filter, a), out, f));
    }

    public List<SearchHit> searchFuzzy(String query) {
        return searchFuzzy(query, null);
    }

    /** 模糊检索（编辑距离 ≤ maxEdits，缺省 1）。认 maxEdits / k / filter。 */
    public List<SearchHit> searchFuzzy(String query, SearchOptions options) {
        String qq = q(query, "query");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_fuzzy", (c, a, out, f) -> BitcaskNative.searchFuzzy(c, a.utf8z(qq), o.k, o.maxEdits, out, f));
        return searching("bitcask_search_fuzzy_filtered",
                (c, a, out, f) -> BitcaskNative.searchFuzzyFiltered(c, a.utf8z(qq), o.k, o.maxEdits, FilterEncoder.toNative(o.filter, a), out, f));
    }

    public List<SearchHit> searchWildcard(String pattern) {
        return searchWildcard(pattern, null);
    }

    /** 通配检索（{@code *} 与 {@code ?}）。认 k / filter。 */
    public List<SearchHit> searchWildcard(String pattern, SearchOptions options) {
        String p = q(pattern, "pattern");
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_wildcard", (c, a, out, f) -> BitcaskNative.searchWildcard(c, a.utf8z(p), o.k, out, f));
        return searching("bitcask_search_wildcard_filtered",
                (c, a, out, f) -> BitcaskNative.searchWildcardFiltered(c, a.utf8z(p), o.k, FilterEncoder.toNative(o.filter, a), out, f));
    }

    private static final long HO_PRE = NativeTypes.off(NativeTypes.HIGHLIGHT_OPTIONS, "pre_tag");
    private static final long HO_POST = NativeTypes.off(NativeTypes.HIGHLIGHT_OPTIONS, "post_tag");
    private static final long HO_FRAG = NativeTypes.off(NativeTypes.HIGHLIGHT_OPTIONS, "fragment_size");
    private static final long HO_MAX = NativeTypes.off(NativeTypes.HIGHLIGHT_OPTIONS, "max_fragments");

    public List<HighlightHit> searchHighlighted(String query) {
        return searchHighlighted(query, null);
    }

    /**
     * BM25 词袋检索 + 高亮片段（{@code bitcask_search_text_highlight_ex}）。
     * ⚠ 片段默认只取 libbitcask 的进程内原文 LRU（缺省 1024 篇），LRU <b>只在写入 / 建索引时</b>填充、读文档不填 ⇒「冷」文档的命中
     * 照样返回、片段为空。冷 = 本进程没写过它：新开进程 / 只读快照里封进段的文档全是冷的；长驻服务重启后、或文档数超过缓存容量时也会冷。
     * {@link HighlightOptions.ColdMode#LOAD}（libbitcask 9.1.0）⇒ 片段为空的命中从存储读回正文、按本库分析器切片段（与 LRU 命中逐字相同）；
     * 正文为空取第一个命名字段；读回的版本 ≠ 命中的版本（已被覆盖 / 删除）该条不补；读回的原文不回填 LRU。代价：每条冷命中一次点查 + 一次分析。
     */
    public List<HighlightHit> searchHighlighted(String query, HighlightOptions options) {
        String qq = q(query, "query");
        HighlightOptions o = options == null ? HighlightOptions.defaults() : options;
        int flags = o.cold == HighlightOptions.ColdMode.LOAD ? BitcaskNative.HIGHLIGHT_LOAD_COLD : 0;
        return run("bitcask_search_text_highlight_ex", (c, a, out, f) -> {
            MemorySegment ho = a.alloc(NativeTypes.HIGHLIGHT_OPTIONS);
            ho.set(ValueLayout.ADDRESS, HO_PRE, a.utf8zOrNull(o.preTag));
            ho.set(ValueLayout.ADDRESS, HO_POST, a.utf8zOrNull(o.postTag));
            ho.set(ValueLayout.JAVA_LONG, HO_FRAG, o.fragmentSize);
            ho.set(ValueLayout.JAVA_LONG, HO_MAX, o.maxFragments);
            return BitcaskNative.searchTextHighlightEx(c, a.utf8z(qq), o.k, ho, flags, out, f);
        }, Results::highlighted, BitcaskNative::searchResultExFree);
    }

    // ═══ 向量 / 混合 ═══

    private float[] checkQueryVector(float[] v) {
        Objects.requireNonNull(v, "query");
        if (v.length != vectorDimension()) throw BitcaskException.of("bitcask/query-dim-mismatch", v.length, vectorDimension());
        return v;
    }

    private float[] checkHybrid(String text, float[] v) {
        float[] vv = v == null ? new float[0] : v;
        if (text == null && vv.length == 0) throw BitcaskException.of("bitcask/need-text-or-vector");
        if (vv.length != 0 && vv.length != vectorDimension()) throw BitcaskException.of("bitcask/query-dim-mismatch", vv.length, vectorDimension());
        return vv;
    }

    public List<SearchHit> searchVector(float[] query) {
        return searchVector(query, null);
    }

    /** 向量 ANN 检索。认 k / ef / filter。 */
    public List<SearchHit> searchVector(float[] query, SearchOptions options) {
        float[] v = checkQueryVector(query);
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_vector", (c, a, out, f) -> BitcaskNative.searchVector(c, a.copy(v), v.length, o.k, o.ef, out, f));
        return searching("bitcask_search_vector_filtered",
                (c, a, out, f) -> BitcaskNative.searchVectorFiltered(c, a.copy(v), v.length, o.k, o.ef, FilterEncoder.toNative(o.filter, a), out, f));
    }

    public List<SearchHit> rankByVector(float[] query, List<?> keys) {
        return rankByVector(query, keys, DEFAULT_K);
    }

    /**
     * 在给定的一组 key 里按向量相似度<b>精确</b>排序取前 k（libbitcask 12.1.0 {@code bitcask_vector_rank}）。候选已经由别处给出
     * （图上的邻居、全文命中、二级索引筛出的一批 key）时用它，不必把候选翻译成 meta 过滤、也不必把向量读回来自己算。
     * 不经向量索引、不需要先 flush；分数口径同 {@link #searchVector}，降序。不存在 / 已删 / 没有向量的 key 跳过，重复的只算一次。
     * 代价与候选数成正比（每个候选一次点读）。key 是 String 或 byte[]。
     */
    public List<SearchHit> rankByVector(float[] query, List<?> keys, int k) {
        float[] v = checkQueryVector(query);
        if (k < 0) throw new IllegalArgumentException("k");
        List<byte[]> ks = new ArrayList<>(keys.size());
        for (Object key : keys) {
            switch (key) {
                case null -> throw new NullPointerException("keys");
                case byte[] b -> ks.add(b);
                case String s -> ks.add(utf8(s));
                default -> throw new IllegalArgumentException("key 要是 byte[] 或字符串：" + key);
            }
        }
        int n = ks.size();
        if (n == 0 || k == 0) return List.of();
        return searching("bitcask_vector_rank", (c, a, out, f) -> {
            MemorySegment kp = a.alloc(ValueLayout.ADDRESS, n);
            MemorySegment kl = a.alloc(ValueLayout.JAVA_LONG, n);
            for (int i = 0; i < n; i++) {
                byte[] b = ks.get(i);
                kp.setAtIndex(ValueLayout.ADDRESS, i, a.copy(b));
                kl.setAtIndex(ValueLayout.JAVA_LONG, i, b.length);
            }
            return BitcaskNative.vectorRank(c, a.copy(v), v.length, kp, kl, n, k, out, f);
        });
    }

    public List<SearchHit> searchHybrid(String text, float[] vector) {
        return searchHybrid(text, vector, null);
    }

    /** RRF 混合检索（BM25 + 向量）。{@code text} null = 纯向量；{@code vector} null / 空 = 纯文本。认 k / filter。 */
    public List<SearchHit> searchHybrid(String text, float[] vector, SearchOptions options) {
        float[] v = checkHybrid(text, vector);
        SearchOptions o = opts(options);
        if (o.filter == null)
            return searching("bitcask_search_hybrid", (c, a, out, f) -> BitcaskNative.searchHybrid(c, a.utf8zOrNull(text), a.copy(v), v.length, o.k, out, f));
        return searching("bitcask_search_hybrid_filtered",
                (c, a, out, f) -> BitcaskNative.searchHybridFiltered(c, a.utf8zOrNull(text), a.copy(v), v.length, o.k,
                        FilterEncoder.toNative(o.filter, a), out, f));
    }

    // ═══ 批量检索（一次 flush 覆盖全批；结果按位次对齐；单条失败 ⇒ 该位置 null）═══

    private List<List<SearchHit>> batching(String op, int n, Call call) {
        return run(op, call, rs -> Results.batch(rs, n), rs -> BitcaskNative.searchResultBatchFree(rs, n));
    }

    public List<List<SearchHit>> searchTextBatch(List<String> queries) {
        return searchTextBatch(queries, DEFAULT_K);
    }

    /** BM25 批量检索。空批 ⇒ 空表。 */
    public List<List<SearchHit>> searchTextBatch(List<String> queries, int k) {
        if (k < 0) throw new IllegalArgumentException("k");
        int n = queries.size();
        if (n == 0) return List.of();
        for (String s : queries) q(s, "queries");
        return batching("bitcask_search_text_batch", n, (c, a, out, f) -> {
            MemorySegment arr = a.alloc(ValueLayout.ADDRESS, n);
            for (int i = 0; i < n; i++) arr.setAtIndex(ValueLayout.ADDRESS, i, a.utf8z(queries.get(i)));
            return BitcaskNative.searchTextBatch(c, arr, n, k, out, f);
        });
    }

    public List<List<SearchHit>> searchVectorBatch(List<float[]> queries) {
        return searchVectorBatch(queries, DEFAULT_K, 0);
    }

    /** 向量批量检索（每条长度须等于库的维度）。 */
    public List<List<SearchHit>> searchVectorBatch(List<float[]> queries, int k, int ef) {
        if (k < 0) throw new IllegalArgumentException("k");
        if (ef < 0) throw new IllegalArgumentException("ef");
        for (float[] v : queries) checkQueryVector(Objects.requireNonNull(v, "queries"));
        int n = queries.size();
        if (n == 0) return List.of();
        return batching("bitcask_search_vector_batch", n, (c, a, out, f) -> {
            MemorySegment arr = a.alloc(ValueLayout.ADDRESS, n);
            for (int i = 0; i < n; i++) arr.setAtIndex(ValueLayout.ADDRESS, i, a.copy(queries.get(i)));
            return BitcaskNative.searchVectorBatch(c, arr, n, vectorDimension(), k, ef, out, f);
        });
    }

    private static final long HQ_TEXT = NativeTypes.off(NativeTypes.HYBRID_QUERY, "text");
    private static final long HQ_VECTOR = NativeTypes.off(NativeTypes.HYBRID_QUERY, "vector");
    private static final long HQ_LEN = NativeTypes.off(NativeTypes.HYBRID_QUERY, "vector_len");

    public List<List<SearchHit>> searchHybridBatch(List<HybridQuery> queries) {
        return searchHybridBatch(queries, DEFAULT_K);
    }

    /** RRF 混合批量检索（每条至少一路；两路都空 ⇒ {@code bitcask/need-text-or-vector}，整批不发）。 */
    public List<List<SearchHit>> searchHybridBatch(List<HybridQuery> queries, int k) {
        if (k < 0) throw new IllegalArgumentException("k");
        int n = queries.size();
        String[] texts = new String[n];
        float[][] vs = new float[n][];
        for (int i = 0; i < n; i++) {
            HybridQuery hq = queries.get(i);
            texts[i] = hq.text();
            vs[i] = checkHybrid(hq.text(), hq.vector());
        }
        if (n == 0) return List.of();
        return batching("bitcask_search_hybrid_batch", n, (c, a, out, f) -> {
            long size = NativeTypes.HYBRID_QUERY.byteSize();
            MemorySegment arr = a.alloc(NativeTypes.HYBRID_QUERY, n);
            for (int i = 0; i < n; i++) {
                long base = i * size;
                arr.set(ValueLayout.ADDRESS, base + HQ_TEXT, a.utf8zOrNull(texts[i]));
                arr.set(ValueLayout.ADDRESS, base + HQ_VECTOR, a.copy(vs[i]));
                arr.set(ValueLayout.JAVA_LONG, base + HQ_LEN, vs[i].length);
            }
            return BitcaskNative.searchHybridBatch(c, arr, n, k, out, f);
        });
    }
}
