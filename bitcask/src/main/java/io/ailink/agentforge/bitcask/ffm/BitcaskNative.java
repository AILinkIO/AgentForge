package io.ailink.agentforge.bitcask.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static io.ailink.agentforge.bitcask.ffm.BitcaskLibrary.required;
import static io.ailink.agentforge.bitcask.ffm.BitcaskLibrary.rethrow;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SLICE;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * libbitcask C ABI 的 downcall：只收 Java 层用到的入口，按功能分组。
 * 第一次用到本类才装载原生库 ⇒ 调用方先看 {@link BitcaskLibrary#unavailableReason()}。
 * 约定：{@code bitcask_error_t} / {@code int} ⇒ {@code int}；{@code size_t} / {@code uint64_t} ⇒ {@code long}；
 * 指针（含出参、句柄）⇒ {@link MemorySegment}；{@code bitcask_slice_t} 按值传 ⇒ 16 字节的段（{@link NativeTypes#SLICE}）。
 */
public final class BitcaskNative {
    private BitcaskNative() {
    }

    private static final FunctionDescriptor I_ = FunctionDescriptor.of(JAVA_INT);
    private static final FunctionDescriptor V_P = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor E_PP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor E_PPP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor I_P = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    private static final FunctionDescriptor I_PPP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor I_PPLP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);
    // search(cask, query, k, out, fault)
    private static final FunctionDescriptor E_PPLPP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    // search(cask, query, k, offset, out, fault)
    private static final FunctionDescriptor E_PPLLPP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS);
    // search(cask, query, k, filter, offset, out, fault)
    private static final FunctionDescriptor E_PPLPLPP =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS);
    // search(cask, query, k, filter, out, fault)
    private static final FunctionDescriptor E_PPLPPP = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS);
    // (cask, query, k, opts, uint32 flags, out, fault)：libbitcask 9.1.0 bitcask_search_text_highlight_ex
    private static final FunctionDescriptor E_PPLPIPP =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);

    public static final SymbolLookup LIB = BitcaskLibrary.lookup();

    // ── 版本 ──────────────────────────────────────────────
    private static final MethodHandle VERSION_MAJOR = required(LIB, "bitcask_version_major", I_);
    private static final MethodHandle VERSION_MINOR = required(LIB, "bitcask_version_minor", I_);
    private static final MethodHandle VERSION_PATCH = required(LIB, "bitcask_version_patch", I_);
    private static final MethodHandle VERSION_STRING = required(LIB, "bitcask_version_string", FunctionDescriptor.of(ADDRESS));

    // ── 生命周期 ──────────────────────────────────────────
    private static final MethodHandle OPTIONS_INIT = required(LIB, "bitcask_options_init", V_P);
    private static final MethodHandle SET_THREAD_LIMITS = required(LIB, "bitcask_set_thread_limits",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle CLOSE = required(LIB, "bitcask_close", V_P);

    // ── KV ────────────────────────────────────────────────
    private static final MethodHandle GET = required(LIB, "bitcask_get", FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, ADDRESS, ADDRESS));
    private static final MethodHandle GET_RESULT_FREE = required(LIB, "bitcask_get_result_free", V_P);
    private static final MethodHandle GET_DOC = required(LIB, "bitcask_get_doc", FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DOC_RESULT_FREE = required(LIB, "bitcask_get_doc_result_free", V_P);
    private static final MethodHandle PUT = required(LIB, "bitcask_put", FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, SLICE, JAVA_LONG, ADDRESS));
    private static final MethodHandle PUT_EX = required(LIB, "bitcask_put_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, SLICE, JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle DELETE = required(LIB, "bitcask_delete", FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, JAVA_LONG, ADDRESS));
    private static final MethodHandle SYNC = required(LIB, "bitcask_sync", E_PP);
    private static final MethodHandle PUT_DOC_EX = required(LIB, "bitcask_put_doc_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, SLICE, ADDRESS, JAVA_LONG, ADDRESS));

    // ── 批 / 原子批 / 事务 ────────────────────────────────
    private static final MethodHandle PUT_BATCH = required(LIB, "bitcask_put_batch",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS));
    private static final MethodHandle PUT_BATCH_ATOMIC = required(LIB, "bitcask_put_batch_atomic",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle TXN_COMMIT = required(LIB, "bitcask_txn_commit",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    private static final MethodHandle PUT_BATCH_ATOMIC_EX = required(LIB, "bitcask_put_batch_atomic_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle TXN_COMMIT_EX = required(LIB, "bitcask_txn_commit_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    // D-10（libbitcask 8.0.0 S46）：带前置条件的原子批 / 事务
    private static final MethodHandle PUT_BATCH_ATOMIC_COND = required(LIB, "bitcask_put_batch_atomic_cond",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle TXN_COMMIT_COND = required(LIB, "bitcask_txn_commit_cond",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // ── 迭代（keydir，无序 / 有序 range）─────────────────
    private static final MethodHandle ITER_START_EX = required(LIB, "bitcask_iter_start_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, SLICE, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle ITER_NEXT_EX = required(LIB, "bitcask_iter_next_ex", I_PPP);
    private static final MethodHandle ITER_NEXT_BATCH_EX = required(LIB, "bitcask_iter_next_batch_ex", I_PPLP);
    // libbitcask 13.0.0（S75）：扫描免分配批视图——条目借迭代器存储，整批零堆分配、不逐条 free。
    private static final MethodHandle ITER_NEXT_BATCH_VIEW = required(LIB, "bitcask_iter_next_batch_view", I_PPLP);
    private static final MethodHandle ITER_ENTRY_EX_FREE = required(LIB, "bitcask_iter_entry_ex_free", V_P);
    private static final MethodHandle ITER_RELEASE = required(LIB, "bitcask_iter_release", V_P);
    private static final MethodHandle RANGE_OPTIONS_INIT = required(LIB, "bitcask_range_options_init", V_P);
    private static final MethodHandle RANGE_ITER_START_EX = required(LIB, "bitcask_range_iter_start_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle RANGE_ITER_NEXT_EX = required(LIB, "bitcask_range_iter_next_ex", I_PPP);
    private static final MethodHandle RANGE_ITER_NEXT_BATCH_EX = required(LIB, "bitcask_range_iter_next_batch_ex", I_PPLP);
    private static final MethodHandle RANGE_ENTRY_EX_FREE = required(LIB, "bitcask_range_entry_ex_free", V_P);
    private static final MethodHandle RANGE_ITER_RELEASE = required(LIB, "bitcask_range_iter_release", V_P);

    // ── 管理 ──────────────────────────────────────────────
    private static final MethodHandle STATUS_EX = required(LIB, "bitcask_status_ex", E_PPP);
    private static final MethodHandle MERGE = required(LIB, "bitcask_merge", E_PP);
    private static final MethodHandle CHECKPOINT = required(LIB, "bitcask_checkpoint", E_PP);
    private static final MethodHandle IS_EMPTY = required(LIB, "bitcask_is_empty", I_P);
    private static final MethodHandle FLUSH_INDEX = required(LIB, "bitcask_flush_index", V_P);

    // ── 文本检索（BM25）──────────────────────────────────
    private static final MethodHandle SEARCH_TEXT_EX = required(LIB, "bitcask_search_text_ex", E_PPLPLPP);
    private static final MethodHandle SEARCH_PHRASE_EX = required(LIB, "bitcask_search_phrase_ex", E_PPLLPP);
    private static final MethodHandle BOOL_SEARCH_EX = required(LIB, "bitcask_bool_search_ex", E_PPLLPP);
    private static final MethodHandle SEARCH_FIELDS = required(LIB, "bitcask_search_fields", E_PPLPP);
    private static final MethodHandle SEARCH_FIELDS_EX = required(LIB, "bitcask_search_fields_ex", E_PPLPP);
    private static final MethodHandle SEARCH_NEAR = required(LIB, "bitcask_search_near",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_FUZZY = required(LIB, "bitcask_search_fuzzy",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_WILDCARD = required(LIB, "bitcask_search_wildcard", E_PPLPP);
    private static final MethodHandle SEARCH_TEXT_HIGHLIGHT = required(LIB, "bitcask_search_text_highlight", E_PPLPPP);
    private static final MethodHandle SEARCH_TEXT_HIGHLIGHT_EX = required(LIB, "bitcask_search_text_highlight_ex", E_PPLPIPP);
    /** libbitcask 9.1.0 {@code BITCASK_HIGHLIGHT_LOAD_COLD}：原文 LRU 未命中的命中读回正文补片段。 */
    public static final int HIGHLIGHT_LOAD_COLD = 0x1;
    private static final MethodHandle SEARCH_RESULT_FREE = required(LIB, "bitcask_search_result_free", V_P);
    private static final MethodHandle SEARCH_RESULT_EX_FREE = required(LIB, "bitcask_search_result_ex_free", V_P);

    // ── 向量 / 混合 ───────────────────────────────────────
    private static final MethodHandle SEARCH_VECTOR = required(LIB, "bitcask_search_vector",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_HYBRID = required(LIB, "bitcask_search_hybrid",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    // D-23（libbitcask 12.1.0，S67）：在给定的一组 key 里按向量相似度精确排序取前 k。
    private static final MethodHandle VECTOR_RANK = required(LIB, "bitcask_vector_rank",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));

    // ── D-03：meta 编解码 ─────────────────────────────────
    private static final MethodHandle META_ENCODE = required(LIB, "bitcask_meta_encode",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle META_BLOB_FREE = required(LIB, "bitcask_meta_blob_free", V_P);
    private static final MethodHandle META_LOOKUP = required(LIB, "bitcask_meta_lookup", FunctionDescriptor.of(JAVA_INT, SLICE, ADDRESS, ADDRESS));
    private static final MethodHandle META_ITER_BEGIN = required(LIB, "bitcask_meta_iter_begin", FunctionDescriptor.of(JAVA_INT, SLICE, ADDRESS));
    private static final MethodHandle META_ITER_NEXT = required(LIB, "bitcask_meta_iter_next",
            FunctionDescriptor.of(JAVA_INT, SLICE, ADDRESS, ADDRESS, ADDRESS));
    // Q-0（libbitcask 12.0.0 S62）：字符串数组的编码入口与逐元素读取（图层标签 `_labels`）。
    private static final MethodHandle META_ENCODE_EX = required(LIB, "bitcask_meta_encode_ex",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle META_ARRAY_NEXT = required(LIB, "bitcask_meta_array_next",
            FunctionDescriptor.of(JAVA_INT, SLICE, ADDRESS, ADDRESS));

    // ── D-03：过滤版检索 ──────────────────────────────────
    private static final MethodHandle SEARCH_PHRASE_FILTERED = required(LIB, "bitcask_search_phrase_filtered", E_PPLPLPP);
    private static final MethodHandle BOOL_SEARCH_FILTERED = required(LIB, "bitcask_bool_search_filtered", E_PPLPLPP);
    private static final MethodHandle SEARCH_FIELDS_FILTERED = required(LIB, "bitcask_search_fields_filtered", E_PPLPPP);
    private static final MethodHandle SEARCH_FIELDS_EX_FILTERED = required(LIB, "bitcask_search_fields_ex_filtered", E_PPLPPP);
    private static final MethodHandle SEARCH_FIELDS_RESULT_FREE = required(LIB, "bitcask_search_fields_result_free", V_P);
    private static final MethodHandle SEARCH_NEAR_FILTERED = required(LIB, "bitcask_search_near_filtered",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_FUZZY_FILTERED = required(LIB, "bitcask_search_fuzzy_filtered",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_WILDCARD_FILTERED = required(LIB, "bitcask_search_wildcard_filtered", E_PPLPPP);
    private static final MethodHandle SEARCH_VECTOR_FILTERED = required(LIB, "bitcask_search_vector_filtered",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_HYBRID_FILTERED = required(LIB, "bitcask_search_hybrid_filtered",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));

    // ── D-03：批量检索 ────────────────────────────────────
    private static final MethodHandle SEARCH_TEXT_BATCH = required(LIB, "bitcask_search_text_batch",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_VECTOR_BATCH = required(LIB, "bitcask_search_vector_batch",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_HYBRID_BATCH = required(LIB, "bitcask_search_hybrid_batch",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    private static final MethodHandle SEARCH_RESULT_BATCH_FREE = required(LIB, "bitcask_search_result_batch_free",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));

    // ── D-04：merge 策略 / 开库调优 / 管理 ────────────────
    private static final MethodHandle MERGE_POLICY_INIT = required(LIB, "bitcask_merge_policy_init", V_P);
    private static final MethodHandle OPEN_TUNING_INIT = required(LIB, "bitcask_open_tuning_init", V_P);
    private static final MethodHandle OPEN_EX2 = required(LIB, "bitcask_open_ex2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle META_READ = required(LIB, "bitcask_meta_read", E_PPP);
    private static final MethodHandle META_INFO_FREE = required(LIB, "bitcask_meta_info_free", V_P);
    private static final MethodHandle NEEDS_MERGE = required(LIB, "bitcask_needs_merge", E_PPP);
    private static final MethodHandle NEEDS_MERGE_FREE = required(LIB, "bitcask_needs_merge_free", V_P);
    private static final MethodHandle MERGE_FILES = required(LIB, "bitcask_merge_files",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle CLOSE_WRITE_FILE = required(LIB, "bitcask_close_write_file", E_PP);
    private static final MethodHandle IS_FROZEN = required(LIB, "bitcask_is_frozen", I_P);

    // ── D-03：并行扫描 ────────────────────────────────────
    private static final MethodHandle PARALLEL_SCAN_PREFIX = required(LIB, "bitcask_parallel_scan_prefix",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, SLICE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    /** {@code void (*log_fn)(int level, const char* msg, void* ctx)}。 */
    public static final FunctionDescriptor LOG_FN = FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, ADDRESS);
    /** {@code void (*bitcask_scan_fn)(void* ctx, bitcask_slice_t key, bitcask_slice_t value)}。 */
    public static final FunctionDescriptor SCAN_FN = FunctionDescriptor.ofVoid(ADDRESS, SLICE, SLICE);

    // ═══ 类型化包装（invokeExact；Throwable ⇒ unchecked）═══

    public static int versionMajor() {
        try {
            return (int) VERSION_MAJOR.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int versionMinor() {
        try {
            return (int) VERSION_MINOR.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int versionPatch() {
        try {
            return (int) VERSION_PATCH.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static MemorySegment versionString() {
        try {
            return (MemorySegment) VERSION_STRING.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void optionsInit(MemorySegment opts) {
        try {
            OPTIONS_INIT.invokeExact(opts);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int setThreadLimits(long indexWorkers, long searchSlots, MemorySegment fault) {
        try {
            return (int) SET_THREAD_LIMITS.invokeExact(indexWorkers, searchSlots, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void close(MemorySegment cask) {
        try {
            CLOSE.invokeExact(cask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int get(MemorySegment cask, MemorySegment key, MemorySegment out, MemorySegment fault) {
        try {
            return (int) GET.invokeExact(cask, key, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void getResultFree(MemorySegment result) {
        try {
            GET_RESULT_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int getDoc(MemorySegment cask, MemorySegment key, MemorySegment out, MemorySegment fault) {
        try {
            return (int) GET_DOC.invokeExact(cask, key, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void getDocResultFree(MemorySegment result) {
        try {
            GET_DOC_RESULT_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int put(MemorySegment cask, MemorySegment key, MemorySegment value, long tstamp, MemorySegment fault) {
        try {
            return (int) PUT.invokeExact(cask, key, value, tstamp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putEx(MemorySegment cask, MemorySegment key, MemorySegment value, long tstamp, long expiryAt, MemorySegment fault) {
        try {
            return (int) PUT_EX.invokeExact(cask, key, value, tstamp, expiryAt, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int delete(MemorySegment cask, MemorySegment key, long tstamp, MemorySegment fault) {
        try {
            return (int) DELETE.invokeExact(cask, key, tstamp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int sync(MemorySegment cask, MemorySegment fault) {
        try {
            return (int) SYNC.invokeExact(cask, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putDocEx(MemorySegment cask, MemorySegment key, MemorySegment doc, long tstamp, MemorySegment fault) {
        try {
            return (int) PUT_DOC_EX.invokeExact(cask, key, doc, tstamp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putBatch(MemorySegment cask, MemorySegment items, long n, long tstamp, MemorySegment fault) {
        try {
            return (int) PUT_BATCH.invokeExact(cask, items, n, tstamp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putBatchAtomic(MemorySegment cask, MemorySegment ops, long n, MemorySegment fault) {
        try {
            return (int) PUT_BATCH_ATOMIC.invokeExact(cask, ops, n, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int txnCommit(MemorySegment cask, MemorySegment ops, long n, int syncOnCommit, MemorySegment fault) {
        try {
            return (int) TXN_COMMIT.invokeExact(cask, ops, n, syncOnCommit, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putBatchAtomicEx(MemorySegment cask, MemorySegment ops, long n, MemorySegment fault) {
        try {
            return (int) PUT_BATCH_ATOMIC_EX.invokeExact(cask, ops, n, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int putBatchAtomicCond(MemorySegment cask, MemorySegment ops, long n, MemorySegment outOrds,
                                         MemorySegment outFailedOp, MemorySegment fault) {
        try {
            return (int) PUT_BATCH_ATOMIC_COND.invokeExact(cask, ops, n, outOrds, outFailedOp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int txnCommitCond(MemorySegment cask, MemorySegment ops, long n, int syncOnCommit, MemorySegment outOrds,
                                    MemorySegment outFailedOp, MemorySegment fault) {
        try {
            return (int) TXN_COMMIT_COND.invokeExact(cask, ops, n, syncOnCommit, outOrds, outFailedOp, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int txnCommitEx(MemorySegment cask, MemorySegment ops, long n, int syncOnCommit, MemorySegment fault) {
        try {
            return (int) TXN_COMMIT_EX.invokeExact(cask, ops, n, syncOnCommit, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int iterStartEx(MemorySegment cask, int maxage, int maxputs, int seeTombstones, MemorySegment prefix, int wantMeta,
                           MemorySegment filter, MemorySegment iterOut, MemorySegment fault) {
        try {
            return (int) ITER_START_EX.invokeExact(cask, maxage, maxputs, seeTombstones, prefix, wantMeta, filter, iterOut, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int iterNextEx(MemorySegment iter, MemorySegment entry, MemorySegment fault) {
        try {
            return (int) ITER_NEXT_EX.invokeExact(iter, entry, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int iterNextBatchEx(MemorySegment iter, MemorySegment entries, long maxN, MemorySegment fault) {
        try {
            return (int) ITER_NEXT_BATCH_EX.invokeExact(iter, entries, maxN, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int iterNextBatchView(MemorySegment iter, MemorySegment entries, long maxN, MemorySegment fault) {
        try {
            return (int) ITER_NEXT_BATCH_VIEW.invokeExact(iter, entries, maxN, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void iterEntryExFree(MemorySegment entry) {
        try {
            ITER_ENTRY_EX_FREE.invokeExact(entry);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void iterRelease(MemorySegment iter) {
        try {
            ITER_RELEASE.invokeExact(iter);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void rangeOptionsInit(MemorySegment opts) {
        try {
            RANGE_OPTIONS_INIT.invokeExact(opts);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int rangeIterStartEx(MemorySegment cask, MemorySegment opts, int wantMeta, MemorySegment filter, MemorySegment iterOut,
                                MemorySegment fault) {
        try {
            return (int) RANGE_ITER_START_EX.invokeExact(cask, opts, wantMeta, filter, iterOut, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int rangeIterNextEx(MemorySegment iter, MemorySegment entry, MemorySegment fault) {
        try {
            return (int) RANGE_ITER_NEXT_EX.invokeExact(iter, entry, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int rangeIterNextBatchEx(MemorySegment iter, MemorySegment entries, long maxN, MemorySegment fault) {
        try {
            return (int) RANGE_ITER_NEXT_BATCH_EX.invokeExact(iter, entries, maxN, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void rangeEntryExFree(MemorySegment entry) {
        try {
            RANGE_ENTRY_EX_FREE.invokeExact(entry);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void rangeIterRelease(MemorySegment iter) {
        try {
            RANGE_ITER_RELEASE.invokeExact(iter);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int statusEx(MemorySegment cask, MemorySegment status, MemorySegment fault) {
        try {
            return (int) STATUS_EX.invokeExact(cask, status, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int merge(MemorySegment cask, MemorySegment fault) {
        try {
            return (int) MERGE.invokeExact(cask, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int checkpoint(MemorySegment cask, MemorySegment fault) {
        try {
            return (int) CHECKPOINT.invokeExact(cask, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int isEmpty(MemorySegment cask) {
        try {
            return (int) IS_EMPTY.invokeExact(cask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void flushIndex(MemorySegment cask) {
        try {
            FLUSH_INDEX.invokeExact(cask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchTextEx(MemorySegment cask, MemorySegment query, long k, MemorySegment filter, long offset, MemorySegment out,
                            MemorySegment fault) {
        try {
            return (int) SEARCH_TEXT_EX.invokeExact(cask, query, k, filter, offset, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchPhraseEx(MemorySegment cask, MemorySegment query, long k, long offset, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_PHRASE_EX.invokeExact(cask, query, k, offset, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int boolSearchEx(MemorySegment cask, MemorySegment query, long k, long offset, MemorySegment out, MemorySegment fault) {
        try {
            return (int) BOOL_SEARCH_EX.invokeExact(cask, query, k, offset, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFields(MemorySegment cask, MemorySegment query, long k, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_FIELDS.invokeExact(cask, query, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFieldsEx(MemorySegment cask, MemorySegment query, long k, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_FIELDS_EX.invokeExact(cask, query, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchNear(MemorySegment cask, MemorySegment query, int slop, long k, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_NEAR.invokeExact(cask, query, slop, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFuzzy(MemorySegment cask, MemorySegment query, long k, int maxEdits, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_FUZZY.invokeExact(cask, query, k, maxEdits, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchWildcard(MemorySegment cask, MemorySegment pattern, long k, MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_WILDCARD.invokeExact(cask, pattern, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchTextHighlight(MemorySegment cask, MemorySegment query, long k, MemorySegment opts, MemorySegment out,
                                   MemorySegment fault) {
        try {
            return (int) SEARCH_TEXT_HIGHLIGHT.invokeExact(cask, query, k, opts, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code bitcask_search_text_highlight_ex}（9.1.0）：flags = 0 同上；{@link #HIGHLIGHT_LOAD_COLD} 冷文档读回正文补片段。 */
    public static int searchTextHighlightEx(MemorySegment cask, MemorySegment query, long k, MemorySegment opts, int flags,
                                     MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_TEXT_HIGHLIGHT_EX.invokeExact(cask, query, k, opts, flags, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void searchResultFree(MemorySegment result) {
        try {
            SEARCH_RESULT_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void searchResultExFree(MemorySegment result) {
        try {
            SEARCH_RESULT_EX_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int vectorRank(MemorySegment cask, MemorySegment query, long queryLen, MemorySegment keys, MemorySegment keyLens,
                                 long nKeys, long k, MemorySegment out, MemorySegment fault) {
        try {
            return (int) VECTOR_RANK.invokeExact(cask, query, queryLen, keys, keyLens, nKeys, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchVector(MemorySegment cask, MemorySegment query, long queryLen, long k, long ef, MemorySegment out,
                            MemorySegment fault) {
        try {
            return (int) SEARCH_VECTOR.invokeExact(cask, query, queryLen, k, ef, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchHybrid(MemorySegment cask, MemorySegment text, MemorySegment vec, long vecLen, long k, MemorySegment out,
                            MemorySegment fault) {
        try {
            return (int) SEARCH_HYBRID.invokeExact(cask, text, vec, vecLen, k, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int metaEncode(MemorySegment entries, long n, MemorySegment outBlob, MemorySegment fault) {
        try {
            return (int) META_ENCODE.invokeExact(entries, n, outBlob, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void metaBlobFree(MemorySegment blob) {
        try {
            META_BLOB_FREE.invokeExact(blob);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int metaLookup(MemorySegment blob, MemorySegment key, MemorySegment outValue) {
        try {
            return (int) META_LOOKUP.invokeExact(blob, key, outValue);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int metaIterBegin(MemorySegment blob, MemorySegment it) {
        try {
            return (int) META_ITER_BEGIN.invokeExact(blob, it);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int metaIterNext(MemorySegment blob, MemorySegment it, MemorySegment outKey, MemorySegment outValue) {
        try {
            return (int) META_ITER_NEXT.invokeExact(blob, it, outKey, outValue);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int metaEncodeEx(MemorySegment entries, long n, MemorySegment outBlob, MemorySegment fault) {
        try {
            return (int) META_ENCODE_EX.invokeExact(entries, n, outBlob, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code region} = 数组视图的 str 切片（按值传）；{@code cursor} 指向 size_t（从 0 起）。取到 ⇒ 1。 */
    public static int metaArrayNext(MemorySegment region, MemorySegment cursor, MemorySegment outItem) {
        try {
            return (int) META_ARRAY_NEXT.invokeExact(region, cursor, outItem);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchPhraseFiltered(MemorySegment cask, MemorySegment query, long k, MemorySegment filter, long offset,
                                    MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_PHRASE_FILTERED.invokeExact(cask, query, k, filter, offset, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int boolSearchFiltered(MemorySegment cask, MemorySegment query, long k, MemorySegment filter, long offset,
                                  MemorySegment out, MemorySegment fault) {
        try {
            return (int) BOOL_SEARCH_FILTERED.invokeExact(cask, query, k, filter, offset, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFieldsFiltered(MemorySegment cask, MemorySegment query, long k, MemorySegment filter, MemorySegment out,
                                    MemorySegment fault) {
        try {
            return (int) SEARCH_FIELDS_FILTERED.invokeExact(cask, query, k, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFieldsExFiltered(MemorySegment cask, MemorySegment query, long k, MemorySegment filter, MemorySegment out,
                                    MemorySegment fault) {
        try {
            return (int) SEARCH_FIELDS_EX_FILTERED.invokeExact(cask, query, k, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void searchFieldsResultFree(MemorySegment result) {
        try {
            SEARCH_FIELDS_RESULT_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchNearFiltered(MemorySegment cask, MemorySegment query, int slop, long k, MemorySegment filter, MemorySegment out,
                                  MemorySegment fault) {
        try {
            return (int) SEARCH_NEAR_FILTERED.invokeExact(cask, query, slop, k, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchFuzzyFiltered(MemorySegment cask, MemorySegment query, long k, int maxEdits, MemorySegment filter,
                                   MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_FUZZY_FILTERED.invokeExact(cask, query, k, maxEdits, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchWildcardFiltered(MemorySegment cask, MemorySegment pattern, long k, MemorySegment filter, MemorySegment out,
                                      MemorySegment fault) {
        try {
            return (int) SEARCH_WILDCARD_FILTERED.invokeExact(cask, pattern, k, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchVectorFiltered(MemorySegment cask, MemorySegment query, long queryLen, long k, long ef, MemorySegment filter,
                                    MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_VECTOR_FILTERED.invokeExact(cask, query, queryLen, k, ef, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchHybridFiltered(MemorySegment cask, MemorySegment text, MemorySegment vec, long vecLen, long k, MemorySegment filter,
                                    MemorySegment out, MemorySegment fault) {
        try {
            return (int) SEARCH_HYBRID_FILTERED.invokeExact(cask, text, vec, vecLen, k, filter, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchTextBatch(MemorySegment cask, MemorySegment queries, long n, long k, MemorySegment results, MemorySegment fault) {
        try {
            return (int) SEARCH_TEXT_BATCH.invokeExact(cask, queries, n, k, results, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchVectorBatch(MemorySegment cask, MemorySegment queries, long n, long queryLen, long k, long ef,
                                 MemorySegment results, MemorySegment fault) {
        try {
            return (int) SEARCH_VECTOR_BATCH.invokeExact(cask, queries, n, queryLen, k, ef, results, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int searchHybridBatch(MemorySegment cask, MemorySegment queries, long n, long k, MemorySegment results, MemorySegment fault) {
        try {
            return (int) SEARCH_HYBRID_BATCH.invokeExact(cask, queries, n, k, results, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void searchResultBatchFree(MemorySegment results, long n) {
        try {
            SEARCH_RESULT_BATCH_FREE.invokeExact(results, n);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void mergePolicyInit(MemorySegment policy) {
        try {
            MERGE_POLICY_INIT.invokeExact(policy);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void openTuningInit(MemorySegment tuning) {
        try {
            OPEN_TUNING_INIT.invokeExact(tuning);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int openEx2(MemorySegment dirname, MemorySegment opts, MemorySegment policy, MemorySegment tuning, MemorySegment out,
                       MemorySegment fault) {
        try {
            return (int) OPEN_EX2.invokeExact(dirname, opts, policy, tuning, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code bitcask_meta_read}（libbitcask 10.0.0）：不开库读 bitcask.meta；成功后由 {@link #metaInfoFree} 释放。 */
    public static int metaRead(MemorySegment dirname, MemorySegment out, MemorySegment fault) {
        try {
            return (int) META_READ.invokeExact(dirname, out, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void metaInfoFree(MemorySegment info) {
        try {
            META_INFO_FREE.invokeExact(info);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int needsMerge(MemorySegment cask, MemorySegment result, MemorySegment fault) {
        try {
            return (int) NEEDS_MERGE.invokeExact(cask, result, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void needsMergeFree(MemorySegment result) {
        try {
            NEEDS_MERGE_FREE.invokeExact(result);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int mergeFiles(MemorySegment cask, MemorySegment files, long n, MemorySegment fault) {
        try {
            return (int) MERGE_FILES.invokeExact(cask, files, n, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int closeWriteFile(MemorySegment cask, MemorySegment fault) {
        try {
            return (int) CLOSE_WRITE_FILE.invokeExact(cask, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int isFrozen(MemorySegment cask) {
        try {
            return (int) IS_FROZEN.invokeExact(cask);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int parallelScanPrefix(MemorySegment cask, long nThreads, MemorySegment prefix, MemorySegment fn, MemorySegment ctx,
                                  MemorySegment outCount, MemorySegment fault) {
        try {
            return (int) PARALLEL_SCAN_PREFIX.invokeExact(cask, nThreads, prefix, fn, ctx, outCount, fault);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
