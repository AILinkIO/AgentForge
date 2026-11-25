package io.ailink.agentforge.bitcask;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.function.BiConsumer;

import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * 打开选项（KV 与检索库共用一份；KV 库忽略检索段）。没给的格一律取 {@code bitcask_options_init} 的缺省。可变、链式；
 * 开库时只读取、不改它（同一份可以反复用）。
 * <p>KV：{@link #readOnly} {@link #maxFileSize} {@link #maxReadHandles} {@link #syncOnWrite} {@link #syncEveryN}
 * {@link #expirySeconds} {@link #keydirCacheEntries} {@link #mergePolicy} {@link #segmentVerifyCrc}（null = 上游缺省 = 校验）
 * {@link #log}（可能从任意原生线程调用，抛的异常被丢掉）。
 * <p>检索库：{@link #analyzer}（null = 已有库按库里记的配置开（{@code STORED}），新建库 {@code NGRAM}）{@link #ngramMin}
 * {@link #ngramMax} {@link #jiebaDictPath} {@link #synonymFilePath} {@link #stemming} {@link #stopWords}
 * {@link #vectorDimension}（0..65535）{@link #vectorMetric}（缺省 COSINE）{@link #vectorEngine}（缺省 HNSW）
 * {@link #vectorQuantized} {@link #vectorInmemInt8}（HNSW int8-only 内存模式）。向量配置建库时定下、之后开库要一致——
 * 不知道就先 {@link Bitcask#probe}。
 */
public final class Options {
    /** 分词器（数值 = C 侧 {@code analyzer_type}）。{@code STORED} = 按库里记的配置（libbitcask 10.0.0）。 */
    public enum Analyzer {
        STORED(4), NGRAM(1), WHITESPACE(2), JIEBA(3);

        final int value;

        Analyzer(int value) {
            this.value = value;
        }

        static Analyzer of(int v) {
            for (Analyzer a : values()) if (a.value == v) return a;
            return null;
        }
    }

    /** 向量度量（序数 = C 侧值）。磁盘档引擎（IVF_RQ / DISK_ANN）只收 COSINE / DOT。 */
    public enum VectorMetric { NONE, COSINE, L2, DOT }

    /** 向量引擎（序数 = C 侧值）。 */
    public enum VectorEngine { HNSW, IVF_RQ, DISK_ANN }

    /** 原生日志级别（C 侧 0 = warn，1 = error）。 */
    public enum LogLevel { WARNING, ERROR }

    private boolean readOnly;
    private Long maxFileSize;
    private Long maxReadHandles;
    private boolean syncOnWrite;
    private int syncEveryN;
    private int expirySeconds;
    private long keydirCacheEntries;
    private MergePolicy mergePolicy;
    private Boolean segmentVerifyCrc;
    private BiConsumer<LogLevel, String> log;

    private Analyzer analyzer;
    private Integer ngramMin;
    private Integer ngramMax;
    private String jiebaDictPath;
    private String synonymFilePath;
    private Boolean stemming;
    private Boolean stopWords;
    private int vectorDimension;
    private VectorMetric vectorMetric;
    private VectorEngine vectorEngine;
    private boolean vectorQuantized;
    private boolean vectorInmemInt8;

    public static Options defaults() {
        return new Options();
    }

    public Options copy() {
        Options o = new Options();
        o.readOnly = readOnly;
        o.maxFileSize = maxFileSize;
        o.maxReadHandles = maxReadHandles;
        o.syncOnWrite = syncOnWrite;
        o.syncEveryN = syncEveryN;
        o.expirySeconds = expirySeconds;
        o.keydirCacheEntries = keydirCacheEntries;
        o.mergePolicy = mergePolicy;
        o.segmentVerifyCrc = segmentVerifyCrc;
        o.log = log;
        o.analyzer = analyzer;
        o.ngramMin = ngramMin;
        o.ngramMax = ngramMax;
        o.jiebaDictPath = jiebaDictPath;
        o.synonymFilePath = synonymFilePath;
        o.stemming = stemming;
        o.stopWords = stopWords;
        o.vectorDimension = vectorDimension;
        o.vectorMetric = vectorMetric;
        o.vectorEngine = vectorEngine;
        o.vectorQuantized = vectorQuantized;
        o.vectorInmemInt8 = vectorInmemInt8;
        return o;
    }

    private static long nat(String name, long v) {
        if (v < 0) throw BitcaskException.of("bitcask/options-invalid", name + " = " + v + "（要非负）");
        return v;
    }

    private static int pos(String name, int v) {
        if (v <= 0) throw BitcaskException.of("bitcask/options-invalid", name + " = " + v + "（要正数）");
        return v;
    }

    // ── KV ──

    public Options readOnly(boolean v) {
        readOnly = v;
        return this;
    }

    /** 单个 data 文件上限（字节；null = 上游缺省 2 GiB）。 */
    public Options maxFileSize(Long v) {
        maxFileSize = v == null ? null : nat("maxFileSize", v);
        return this;
    }

    public Options maxReadHandles(Long v) {
        maxReadHandles = v == null ? null : nat("maxReadHandles", v);
        return this;
    }

    /** 每次写都 fsync（O_SYNC）。 */
    public Options syncOnWrite(boolean v) {
        syncOnWrite = v;
        return this;
    }

    /** 每 n 次写 fsync 一次（0 = 不按次数）。 */
    public Options syncEveryN(int v) {
        syncEveryN = (int) nat("syncEveryN", v);
        return this;
    }

    /** 全库相对过期秒（0 = 不过期）。 */
    public Options expirySeconds(int v) {
        expirySeconds = (int) nat("expirySeconds", v);
        return this;
    }

    public Options keydirCacheEntries(long v) {
        keydirCacheEntries = nat("keydirCacheEntries", v);
        return this;
    }

    /** null = 上游缺省（不传结构体）。 */
    public Options mergePolicy(MergePolicy v) {
        mergePolicy = v;
        return this;
    }

    /** 开库时校验检索段 CRC（null = 上游缺省 = 校验）。 */
    public Options segmentVerifyCrc(Boolean v) {
        segmentVerifyCrc = v;
        return this;
    }

    /** 原生日志回调（可能从任意原生线程调用；抛的异常被丢掉）。 */
    public Options log(BiConsumer<LogLevel, String> v) {
        log = v;
        return this;
    }

    // ── 检索 ──

    public Options analyzer(Analyzer v) {
        analyzer = v;
        return this;
    }

    public Options ngramMin(Integer v) {
        ngramMin = v == null ? null : pos("ngramMin", v);
        return this;
    }

    public Options ngramMax(Integer v) {
        ngramMax = v == null ? null : pos("ngramMax", v);
        return this;
    }

    public Options jiebaDictPath(String v) {
        jiebaDictPath = v;
        return this;
    }

    public Options synonymFilePath(String v) {
        synonymFilePath = v;
        return this;
    }

    public Options stemming(Boolean v) {
        stemming = v;
        return this;
    }

    public Options stopWords(Boolean v) {
        stopWords = v;
        return this;
    }

    public Options vectorDimension(int v) {
        vectorDimension = v;
        return this;
    }

    public Options vectorMetric(VectorMetric v) {
        vectorMetric = v;
        return this;
    }

    public Options vectorEngine(VectorEngine v) {
        vectorEngine = v;
        return this;
    }

    public Options vectorQuantized(boolean v) {
        vectorQuantized = v;
        return this;
    }

    public Options vectorInmemInt8(boolean v) {
        vectorInmemInt8 = v;
        return this;
    }

    /** 一次设齐向量配置（如 {@link Bitcask#probe} 读回的那份）。 */
    public Options vector(VectorConfig v) {
        vectorDimension = v.dimension();
        vectorMetric = v.metric();
        vectorEngine = v.engine();
        vectorQuantized = v.quantized();
        vectorInmemInt8 = v.inmemInt8();
        return this;
    }

    // ── 读 ──

    public boolean readOnly() {
        return readOnly;
    }

    public Long maxFileSize() {
        return maxFileSize;
    }

    public Long maxReadHandles() {
        return maxReadHandles;
    }

    public boolean syncOnWrite() {
        return syncOnWrite;
    }

    public int syncEveryN() {
        return syncEveryN;
    }

    public int expirySeconds() {
        return expirySeconds;
    }

    public long keydirCacheEntries() {
        return keydirCacheEntries;
    }

    public MergePolicy mergePolicy() {
        return mergePolicy;
    }

    public Boolean segmentVerifyCrc() {
        return segmentVerifyCrc;
    }

    public BiConsumer<LogLevel, String> log() {
        return log;
    }

    public Analyzer analyzer() {
        return analyzer;
    }

    public Integer ngramMin() {
        return ngramMin;
    }

    public Integer ngramMax() {
        return ngramMax;
    }

    public String jiebaDictPath() {
        return jiebaDictPath;
    }

    public String synonymFilePath() {
        return synonymFilePath;
    }

    public Boolean stemming() {
        return stemming;
    }

    public Boolean stopWords() {
        return stopWords;
    }

    public int vectorDimension() {
        return vectorDimension;
    }

    public VectorMetric vectorMetric() {
        return vectorMetric;
    }

    public VectorEngine vectorEngine() {
        return vectorEngine;
    }

    public boolean vectorQuantized() {
        return vectorQuantized;
    }

    public boolean vectorInmemInt8() {
        return vectorInmemInt8;
    }

    /** 实际交给 libbitcask 比对的那份向量配置（缺省补齐）。 */
    public VectorConfig requestedVector() {
        boolean on = vectorDimension > 0;
        return new VectorConfig(vectorDimension, on ? (vectorMetric == null ? VectorMetric.COSINE : vectorMetric) : VectorMetric.NONE,
                on && vectorQuantized, on && vectorInmemInt8, on && vectorEngine != null ? vectorEngine : VectorEngine.HNSW);
    }

    // ── 写进 C 结构体 ──

    private static long o(String field) {
        return NativeTypes.off(NativeTypes.OPTIONS, field);
    }

    /** KV 段写进 {@code bitcask_options_t}（已 options_init）。 */
    void kvToNative(MemorySegment opts) {
        opts.set(ValueLayout.JAVA_INT, o("read_write"), readOnly ? 0 : 1);
        if (maxFileSize != null) opts.set(ValueLayout.JAVA_LONG, o("max_file_size"), maxFileSize);
        if (maxReadHandles != null) opts.set(ValueLayout.JAVA_LONG, o("max_read_handles"), maxReadHandles);
        opts.set(ValueLayout.JAVA_INT, o("o_sync"), syncOnWrite ? 1 : 0);
        opts.set(ValueLayout.JAVA_INT, o("sync_every_n"), syncEveryN);
        opts.set(ValueLayout.JAVA_INT, o("expiry_secs"), expirySeconds);
        opts.set(ValueLayout.JAVA_LONG, o("keydir_cache_entries"), keydirCacheEntries);
    }

    /** 检索段写进 {@code bitcask_options_t}（路径串进 arena，只在 open 期间被读）。{@code analyzer} = 实际用的那个。 */
    void indexToNative(MemorySegment opts, Analyzer analyzer, String jiebaDir, NativeArena a) {
        opts.set(ValueLayout.JAVA_INT, o("enable_search"), 1);
        opts.set(ValueLayout.JAVA_INT, o("analyzer_type"), analyzer.value);
        if (ngramMin != null) opts.set(ValueLayout.JAVA_INT, o("analyzer_min_n"), ngramMin);
        if (ngramMax != null) opts.set(ValueLayout.JAVA_INT, o("analyzer_max_n"), ngramMax);
        opts.set(ValueLayout.JAVA_INT, o("enable_stemming"), Boolean.TRUE.equals(stemming) ? 1 : 0);
        opts.set(ValueLayout.JAVA_INT, o("enable_stop_words"), Boolean.TRUE.equals(stopWords) ? 1 : 0);
        opts.set(ValueLayout.JAVA_SHORT, o("vector_dim"), (short) vectorDimension);
        if (vectorDimension > 0) {
            VectorConfig v = requestedVector();
            opts.set(ValueLayout.JAVA_INT, o("vector_metric"), v.metric().ordinal());
            opts.set(ValueLayout.JAVA_INT, o("vector_engine"), v.engine().ordinal());
            opts.set(ValueLayout.JAVA_INT, o("vector_quantized"), v.quantized() ? 1 : 0);
            opts.set(ValueLayout.JAVA_INT, o("vector_inmem_int8"), v.inmemInt8() ? 1 : 0);
        }
        opts.set(ValueLayout.ADDRESS, o("jieba_dict_path"), a.utf8zOrNull(jiebaDir));
        opts.set(ValueLayout.ADDRESS, o("synonym_file_path"), a.utf8zOrNull(synonymFilePath));
    }

    /** {@code segmentVerifyCrc} ⇒ {@code bitcask_open_tuning_t}（null ⇒ NULL）。 */
    MemorySegment tuningToNative(NativeArena a) {
        if (segmentVerifyCrc == null) return MemorySegment.NULL;
        MemorySegment t = a.alloc(NativeTypes.OPEN_TUNING);
        io.ailink.agentforge.bitcask.ffm.BitcaskNative.openTuningInit(t);
        t.set(ValueLayout.JAVA_INT, NativeTypes.off(NativeTypes.OPEN_TUNING, "segment_verify_crc"), segmentVerifyCrc ? 1 : 0);
        return t;
    }
}
