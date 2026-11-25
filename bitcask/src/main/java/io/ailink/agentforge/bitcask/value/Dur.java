package io.ailink.agentforge.bitcask.value;

/**
 * openCypher 的 duration：月、日、秒、纳秒四个分量（同 Neo4j）。规范化：纳秒落在 [0, 10^9)，多出的并进秒
 * （PT-0.1S 是 秒 -1、纳秒 900000000）。构造用 {@link Temporals#dur}；{@link #toString()} = ISO-8601（同 {@link Temporals#durToIso}）。
 */
public record Dur(long months, long days, long seconds, long nanos) {
    @Override
    public String toString() {
        return Temporals.durToIso(this);
    }
}
