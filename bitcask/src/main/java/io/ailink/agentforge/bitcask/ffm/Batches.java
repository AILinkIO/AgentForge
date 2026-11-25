package io.ailink.agentforge.bitcask.ffm;

import io.ailink.agentforge.bitcask.Document;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.COND_OP;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.KV_PAIR;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.TXN_OP;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.TXN_OP_EX;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.off;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;

/**
 * 批的编组（热循环）：调用方给平行数组，这里一次拷进原生内存并提交。
 * <ul>
 * <li>{@link #putBatch} —— {@code bitcask_put_batch}（只收 put）；</li>
 * <li>{@link #submit} —— mode 0 = {@code bitcask_put_batch_atomic}，1 = {@code bitcask_txn_commit}，2 = 同上并 fsync；
 *     有文档 ⇒ 对应的 {@code *_ex} 入口。kinds：0 = put（value 是 byte[]），1 = remove（value 忽略），2 = put_doc（value 是 {@link Document}）。</li>
 * <li>{@link #submitCond} —— 带前置条件（libbitcask 8.0.0）：{@code bitcask_put_batch_atomic_cond} / {@code bitcask_txn_commit_cond}；
 *     kinds 多一种 3 = check（只校验不写）；expects：0 无 / 1 不存在 / 2 ord 相等（对照 expectOrds）。</li>
 * </ul>
 */
public final class Batches {
    public static final byte PUT = 0, REMOVE = 1, PUT_DOC = 2, CHECK = 3;
    public static final byte EXPECT_NONE = 0, EXPECT_ABSENT = 1, EXPECT_ORD = 2;
    public static final int ATOMIC = 0, COMMIT = 1, COMMIT_SYNC = 2;

    private static final long TXN_KEY = off(TXN_OP, "key"), TXN_VALUE = off(TXN_OP, "value");
    private static final long TXN_EX_KEY = off(TXN_OP_EX, "key"), TXN_EX_VALUE = off(TXN_OP_EX, "value"), TXN_EX_DOC = off(TXN_OP_EX, "doc");
    private static final long COND_EXPECT = off(COND_OP, "expect"), COND_ORD = off(COND_OP, "expect_ord"),
            COND_KEY = off(COND_OP, "key"), COND_VALUE = off(COND_OP, "value"), COND_DOC = off(COND_OP, "doc");

    private Batches() {
    }

    /** 全部字节连续拷进一块（至少 1 字节，保证非 NULL）；回起点表（下标 2i = key，2i+1 = value）。 */
    private static MemorySegment pack(NativeArena a, byte[][] keys, Object[] values, int n, long[] at) {
        long total = 0;
        for (int i = 0; i < n; i++) {
            total += keys[i].length;
            if (values[i] instanceof byte[] v) total += v.length;
        }
        MemorySegment p = a.alloc(JAVA_BYTE, Math.max(1, total));
        long o = 0;
        for (int i = 0; i < n; i++) {
            byte[] k = keys[i];
            MemorySegment.copy(k, 0, p, JAVA_BYTE, o, k.length);
            at[2 * i] = o;
            o += k.length;
            if (values[i] instanceof byte[] v) {
                MemorySegment.copy(v, 0, p, JAVA_BYTE, o, v.length);
                at[2 * i + 1] = o;
                o += v.length;
            }
        }
        return p;
    }

    private static void slice(MemorySegment target, long offset, MemorySegment data, long at, int len) {
        NativeTypes.setSlice(target, offset, data.asSlice(at, len), len);
    }

    public static void putBatch(NativeHandle.CaskHandle h, byte[][] keys, byte[][] values, int n) {
        if (n == 0) return;
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            long[] at = new long[2 * n];
            MemorySegment data = pack(a, keys, values, n, at);
            MemorySegment items = a.alloc(KV_PAIR, n);
            long size = KV_PAIR.byteSize();
            for (int i = 0; i < n; i++) {
                slice(items, i * size, data, at[2 * i], keys[i].length);
                slice(items, i * size + 16, data, at[2 * i + 1], values[i].length);
            }
            MemorySegment f = a.fault();
            Faults.check(BitcaskNative.putBatch(cask, items, n, 0, f), f, "bitcask_put_batch");
        } finally {
            h.release();
        }
    }

    public static void submit(NativeHandle.CaskHandle h, int mode, byte[] kinds, byte[][] keys, Object[] values, int n) {
        boolean docs = false;
        for (int i = 0; i < n; i++) docs |= kinds[i] == PUT_DOC;
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            long[] at = new long[2 * n];
            MemorySegment data = pack(a, keys, values, n, at);
            MemorySegment f = a.fault();
            int err;
            String op;
            if (!docs) {
                MemorySegment nat = a.alloc(TXN_OP, n);
                long size = TXN_OP.byteSize();
                for (int i = 0; i < n; i++) {
                    long base = i * size;
                    nat.set(JAVA_BYTE, base, kinds[i]);
                    slice(nat, base + TXN_KEY, data, at[2 * i], keys[i].length);
                    if (kinds[i] == PUT) slice(nat, base + TXN_VALUE, data, at[2 * i + 1], ((byte[]) values[i]).length);
                }
                MemorySegment ops = n == 0 ? MemorySegment.NULL : nat;
                if (mode == ATOMIC) {
                    err = BitcaskNative.putBatchAtomic(cask, ops, n, f);
                    op = "bitcask_put_batch_atomic";
                } else {
                    err = BitcaskNative.txnCommit(cask, ops, n, mode == COMMIT_SYNC ? 1 : 0, f);
                    op = "bitcask_txn_commit";
                }
            } else {
                MemorySegment nat = a.alloc(TXN_OP_EX, n);
                long size = TXN_OP_EX.byteSize();
                for (int i = 0; i < n; i++) {
                    long base = i * size;
                    nat.set(JAVA_BYTE, base, kinds[i]);
                    slice(nat, base + TXN_EX_KEY, data, at[2 * i], keys[i].length);
                    switch (kinds[i]) {
                        case PUT -> slice(nat, base + TXN_EX_VALUE, data, at[2 * i + 1], ((byte[]) values[i]).length);
                        case PUT_DOC -> nat.set(ADDRESS, base + TXN_EX_DOC, DocMarshal.toNative((Document) values[i], a));
                        default -> {
                        }
                    }
                }
                if (mode == ATOMIC) {
                    err = BitcaskNative.putBatchAtomicEx(cask, nat, n, f);
                    op = "bitcask_put_batch_atomic_ex";
                } else {
                    err = BitcaskNative.txnCommitEx(cask, nat, n, mode == COMMIT_SYNC ? 1 : 0, f);
                    op = "bitcask_txn_commit_ex";
                }
            }
            Faults.check(err, f, op);
        } finally {
            h.release();
        }
    }

    /** 小批走每线程暂存区的门槛：op 条数与 key + value 字节合计（不含 doc 的批）。 */
    static final int SMALL_OPS = 16;
    static final long SMALL_BYTES = 64 * 1024;

    /**
     * 带前置条件的原子批（mode 同 {@link #submit}）。成功 ⇒ 回 -1，{@code outOrds}（可为 null，否则长度 ≥ n）填各 op 落盘记录的 ord；
     * 条件不成立 ⇒ 回首个不成立的 op 下标（从 0 起），整批零副作用；其它错误照常抛 {@link io.ailink.agentforge.bitcask.BitcaskException}。
     */
    public static long submitCond(NativeHandle.CaskHandle h, int mode, byte[] kinds, byte[][] keys, Object[] values,
                                  byte[] expects, long[] expectOrds, int n, long[] outOrds) {
        if (n == 0) return -1;
        if (n <= SMALL_OPS) {
            long total = 0;
            boolean doc = false;
            for (int i = 0; i < n && !doc; i++) {
                if (kinds[i] == PUT_DOC) doc = true;
                total += keys[i].length;
                if (kinds[i] == PUT) total += ((byte[]) values[i]).length;
            }
            if (!doc && total <= SMALL_BYTES) return submitCondSmall(h, mode, kinds, keys, values, expects, expectOrds, n, outOrds, total);
        }
        MemorySegment cask = h.acquire();
        try (var a = new NativeArena()) {
            long[] at = new long[2 * n];
            MemorySegment data = pack(a, keys, values, n, at);
            MemorySegment nat = a.alloc(COND_OP, n);
            long size = COND_OP.byteSize();
            for (int i = 0; i < n; i++) {
                long base = i * size;
                nat.set(JAVA_BYTE, base, kinds[i]);
                nat.set(JAVA_BYTE, base + COND_EXPECT, expects[i]);
                nat.set(JAVA_LONG, base + COND_ORD, expectOrds[i]);
                slice(nat, base + COND_KEY, data, at[2 * i], keys[i].length);
                switch (kinds[i]) {
                    case PUT -> slice(nat, base + COND_VALUE, data, at[2 * i + 1], ((byte[]) values[i]).length);
                    case PUT_DOC -> nat.set(ADDRESS, base + COND_DOC, DocMarshal.toNative((Document) values[i], a));
                    default -> {
                    }
                }
            }
            MemorySegment ords = a.alloc(JAVA_LONG, n);
            MemorySegment failed = a.alloc(JAVA_LONG, 1);
            MemorySegment f = a.fault();
            int err;
            String op;
            if (mode == ATOMIC) {
                err = BitcaskNative.putBatchAtomicCond(cask, nat, n, ords, failed, f);
                op = "bitcask_put_batch_atomic_cond";
            } else {
                err = BitcaskNative.txnCommitCond(cask, nat, n, mode == COMMIT_SYNC ? 1 : 0, ords, failed, f);
                op = "bitcask_txn_commit_cond";
            }
            if (err == Faults.CONDITION_FAILED) {
                long idx = failed.get(JAVA_LONG, 0);
                if (idx >= 0 && idx < n) return idx;
            }
            Faults.check(err, f, op);
            if (outOrds != null) MemorySegment.copy(ords, JAVA_LONG, 0, outOrds, 0, n);
            return -1;
        } finally {
            h.release();
        }
    }
    /**
     * 小批（≤ {@link #SMALL_OPS} 条、无 doc、字节 ≤ {@link #SMALL_BYTES}）：结构体、出参、key / value 全放进每线程暂存区的
     * 一块（{@link Scratch#batch}），不开 arena——图层的单条写边是 2～4 条 op 的小批，开关 arena 与清零 fault 的固定开销与
     * 批本身同量级。结构体区每次显式清零（未用的 value / doc 字段须为 NULL）。
     */
    private static long submitCondSmall(NativeHandle.CaskHandle h, int mode, byte[] kinds, byte[][] keys, Object[] values,
                                        byte[] expects, long[] expectOrds, int n, long[] outOrds, long total) {
        long size = COND_OP.byteSize();
        long structs = size * n;
        long ordsAt = (structs + 7) & ~7L;
        long failedAt = ordsAt + 8L * n;
        long dataAt = failedAt + 8;
        MemorySegment cask = h.acquire();
        try {
            Scratch s = Scratch.get();
            MemorySegment buf = s.batch(dataAt + Math.max(1, total));
            MemorySegment nat = buf.asSlice(0, structs);
            nat.fill((byte) 0);
            MemorySegment ords = buf.asSlice(ordsAt, 8L * n);
            MemorySegment failed = buf.asSlice(failedAt, 8);
            failed.set(JAVA_LONG, 0, -1L);
            long o = dataAt;
            for (int i = 0; i < n; i++) {
                long base = i * size;
                nat.set(JAVA_BYTE, base, kinds[i]);
                nat.set(JAVA_BYTE, base + COND_EXPECT, expects[i]);
                nat.set(JAVA_LONG, base + COND_ORD, expectOrds[i]);
                byte[] k = keys[i];
                MemorySegment.copy(k, 0, buf, JAVA_BYTE, o, k.length);
                slice(nat, base + COND_KEY, buf, o, k.length);
                o += k.length;
                if (kinds[i] == PUT) {
                    byte[] v = (byte[]) values[i];
                    MemorySegment.copy(v, 0, buf, JAVA_BYTE, o, v.length);
                    slice(nat, base + COND_VALUE, buf, o, v.length);
                    o += v.length;
                }
            }
            MemorySegment f = s.fault();
            int err;
            String op;
            if (mode == ATOMIC) {
                err = BitcaskNative.putBatchAtomicCond(cask, nat, n, ords, failed, f);
                op = "bitcask_put_batch_atomic_cond";
            } else {
                err = BitcaskNative.txnCommitCond(cask, nat, n, mode == COMMIT_SYNC ? 1 : 0, ords, failed, f);
                op = "bitcask_txn_commit_cond";
            }
            if (err == Faults.CONDITION_FAILED) {
                long idx = failed.get(JAVA_LONG, 0);
                if (idx >= 0 && idx < n) return idx;
            }
            Faults.check(err, f, op);
            if (outOrds != null) MemorySegment.copy(ords, JAVA_LONG, 0, outOrds, 0, n);
            return -1;
        } finally {
            h.release();
        }
    }
}
