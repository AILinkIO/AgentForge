package io.ailink.agentforge.bitcask;

/**
 * 原子批 op 的前置条件（libbitcask 8.0.0，乐观并发）：{@link #absent()}（key 不存在：从未写过 / 已删 / 已过期）或
 * {@link #ord(long)}（key 当前 LSN = n，n 来自 {@link Bitcask#getWithOrd} 或上一次 {@link Bitcask#tryWriteAtomic} 回的 ords）。
 * 全部条件在引擎写锁内、落盘前对照<b>批开始前</b>的状态判断。
 */
public sealed interface Condition permits Condition.Absent, Condition.Ord {
    /** key 不存在。 */
    record Absent() implements Condition {
    }

    /** key 当前 LSN = {@code ord}。 */
    record Ord(long ord) implements Condition {
    }

    static Condition absent() {
        return new Absent();
    }

    static Condition ord(long ord) {
        return new Ord(ord);
    }
}
