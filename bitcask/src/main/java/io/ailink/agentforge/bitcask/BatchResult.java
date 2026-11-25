package io.ailink.agentforge.bitcask;

/**
 * {@link Bitcask#tryWriteAtomic} 的结果：成功 ⇒ {@code ords} = 各 op 落盘记录的 LSN（check 为 0；可直接当下一轮
 * {@link Condition#ord}），{@code conflict} = -1；条件不成立 ⇒ {@code conflict} = 首个不成立的 op 下标（从 0 起），
 * {@code ords} = null，整批零副作用。
 */
public record BatchResult(long[] ords, long conflict) {
    public static BatchResult success(long[] ords) {
        return new BatchResult(ords, -1);
    }

    public static BatchResult conflictAt(long index) {
        return new BatchResult(null, index);
    }

    public boolean isConflict() {
        return conflict >= 0;
    }

    public boolean isSuccess() {
        return conflict < 0;
    }
}
