package io.ailink.agentforge.bitcask;

/**
 * 检索库建库时定下的向量配置（之后开库要<b>完全一致</b>，否则 {@code bitcask/mode-mismatch}）。{@link Bitcask#probe} 读回、
 * {@link Options#vector(VectorConfig)} 原样当选项。
 */
public record VectorConfig(int dimension, Options.VectorMetric metric, boolean quantized, boolean inmemInt8, Options.VectorEngine engine) {
    /** 没有向量（维度 0、metric NONE）。 */
    public static final VectorConfig NONE = new VectorConfig(0, Options.VectorMetric.NONE, false, false, Options.VectorEngine.HNSW);
}
