package io.ailink.agentforge.bitcask;

/** 库状态（{@code bitcask_status_ex}；全部按无符号）。 */
public record Status(long keyCount, long keyBytes, long epoch, long indexErrors, long hnswNodes, long searchCacheEntries,
                     long readHandles) {
}
