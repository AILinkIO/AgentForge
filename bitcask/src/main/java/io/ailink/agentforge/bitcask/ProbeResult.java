package io.ailink.agentforge.bitcask;

import java.util.List;

/**
 * 目录探测的结果（{@link Bitcask#probe}）：KV 还是检索库、meta 格式版本、建库时的 ICU / Unicode 主版本；
 * 检索库另带建库时的向量配置 {@link #vector()} 与分词器配置 {@link #analyzer()}（旧库没记录分词器 ⇒ null）。
 */
public record ProbeResult(Mode mode, int metaVersion, int icuMajor, int unicodeMajor, VectorConfig vector, AnalyzerConfig analyzer) {
    public enum Mode { KV, INDEX }

    /**
     * 库里记的分词器配置（libbitcask 10.0.0 起）。
     *
     * @param stopWordList 显式停用词（没有 ⇒ 空表）
     */
    public record AnalyzerConfig(Options.Analyzer analyzer, int ngramMin, int ngramMax, boolean stopWords, List<String> stopWordList,
                                 int minTokenLength, int maxTokenBytes, boolean stemming) {
    }

    public boolean isIndex() {
        return mode == Mode.INDEX;
    }
}
