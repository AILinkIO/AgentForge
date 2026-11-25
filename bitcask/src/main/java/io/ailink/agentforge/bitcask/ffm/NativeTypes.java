package io.ailink.agentforge.bitcask.ffm;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.MemoryLayout.structLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * libbitcask C ABI（{@code c_api/bitcask_kv.h} 等）的结构体布局。
 * 64 位 ABI（LP64 / LLP64）：{@code size_t} / {@code uint64_t} ⇒ {@code JAVA_LONG}，enum / int ⇒ {@code JAVA_INT}，指针 ⇒ {@code ADDRESS}；
 * 对齐空洞显式写成 padding（FFM 不自动补）。布局由测试 {@code LayoutTest} 钉住（期望值取自 C 侧 sizeof / offsetof 实测）。
 */
public final class NativeTypes {
    private NativeTypes() {
    }

    /** {@code size_t}（只支持 64 位）。 */
    public static final ValueLayout.OfLong SIZE_T = JAVA_LONG;

    public static long off(MemoryLayout l, String... path) {
        var elems = new MemoryLayout.PathElement[path.length];
        for (int i = 0; i < path.length; i++) elems[i] = groupElement(path[i]);
        return l.byteOffset(elems);
    }

    // ── bitcask_slice_t ─────────────────────────────────
    public static final StructLayout SLICE = structLayout(ADDRESS.withName("data"), SIZE_T.withName("size")).withName("bitcask_slice_t");

    // ── bitcask_fault_t ─────────────────────────────────
    public static final int DETAIL_MAX = 512;
    public static final StructLayout FAULT = structLayout(
            JAVA_INT.withName("code"),
            JAVA_INT.withName("errnum"),
            sequenceLayout(DETAIL_MAX, JAVA_BYTE).withName("detail")).withName("bitcask_fault_t");
    public static final long FAULT_CODE = 0, FAULT_ERRNUM = 4, FAULT_DETAIL = 8;

    // ── bitcask_options_t ───────────────────────────────
    public static final StructLayout OPTIONS = structLayout(
            JAVA_INT.withName("read_write"),
            paddingLayout(4),
            JAVA_LONG.withName("max_file_size"),
            SIZE_T.withName("max_read_handles"),
            JAVA_INT.withName("o_sync"),
            JAVA_INT.withName("sync_every_n"),
            JAVA_INT.withName("expiry_secs"),
            JAVA_INT.withName("merge_only"),
            JAVA_BYTE.withName("tombstone_version"),
            paddingLayout(3),
            JAVA_INT.withName("enable_search"),
            JAVA_INT.withName("analyzer_type"),
            JAVA_INT.withName("analyzer_min_n"),
            JAVA_INT.withName("analyzer_max_n"),
            paddingLayout(4),
            ADDRESS.withName("jieba_dict_path"),
            JAVA_INT.withName("enable_stop_words"),
            paddingLayout(4),
            ADDRESS.withName("stop_words"),
            JAVA_INT.withName("min_token_length"),
            JAVA_INT.withName("enable_stemming"),
            ADDRESS.withName("synonym_file_path"),
            JAVA_SHORT.withName("vector_dim"),
            paddingLayout(2),
            JAVA_INT.withName("vector_metric"),
            JAVA_INT.withName("vector_quantized"),
            JAVA_INT.withName("vector_inmem_int8"),
            JAVA_INT.withName("vector_engine"),
            JAVA_INT.withName("hnsw_m"),
            JAVA_INT.withName("hnsw_ef_construction"),
            JAVA_INT.withName("hnsw_build_nav_int8"),
            JAVA_INT.withName("vector_rebase_min_docs"),
            JAVA_INT.withName("vector_ivf_nlist"),
            JAVA_INT.withName("vector_ivf_nprobe"),
            JAVA_INT.withName("vector_diskann_r"),
            JAVA_INT.withName("vector_diskann_l_build"),
            paddingLayout(4),
            ADDRESS.withName("log_fn"),
            ADDRESS.withName("log_ctx"),
            SIZE_T.withName("keydir_cache_entries")).withName("bitcask_options_t");
    public static final long OPT_READ_WRITE = off(OPTIONS, "read_write");
    public static final long OPT_MAX_FILE_SIZE = off(OPTIONS, "max_file_size");
    public static final long OPT_MAX_READ_HANDLES = off(OPTIONS, "max_read_handles");
    public static final long OPT_O_SYNC = off(OPTIONS, "o_sync");
    public static final long OPT_SYNC_EVERY_N = off(OPTIONS, "sync_every_n");
    public static final long OPT_EXPIRY_SECS = off(OPTIONS, "expiry_secs");
    public static final long OPT_ENABLE_SEARCH = off(OPTIONS, "enable_search");
    public static final long OPT_ANALYZER_TYPE = off(OPTIONS, "analyzer_type");
    public static final long OPT_ANALYZER_MIN_N = off(OPTIONS, "analyzer_min_n");
    public static final long OPT_ANALYZER_MAX_N = off(OPTIONS, "analyzer_max_n");
    public static final long OPT_JIEBA_DICT_PATH = off(OPTIONS, "jieba_dict_path");
    public static final long OPT_ENABLE_STOP_WORDS = off(OPTIONS, "enable_stop_words");
    public static final long OPT_ENABLE_STEMMING = off(OPTIONS, "enable_stemming");
    public static final long OPT_SYNONYM_FILE_PATH = off(OPTIONS, "synonym_file_path");
    public static final long OPT_VECTOR_DIM = off(OPTIONS, "vector_dim");
    public static final long OPT_VECTOR_METRIC = off(OPTIONS, "vector_metric");
    public static final long OPT_VECTOR_QUANTIZED = off(OPTIONS, "vector_quantized");
    public static final long OPT_VECTOR_ENGINE = off(OPTIONS, "vector_engine");
    public static final long OPT_LOG_FN = off(OPTIONS, "log_fn");
    public static final long OPT_LOG_CTX = off(OPTIONS, "log_ctx");
    public static final long OPT_KEYDIR_CACHE_ENTRIES = off(OPTIONS, "keydir_cache_entries");

    // ── bitcask_get_result_t ────────────────────────────
    public static final StructLayout GET_RESULT = structLayout(
            SLICE.withName("value"),
            SLICE.withName("meta"),
            ADDRESS.withName("vector"),
            SIZE_T.withName("vector_len"),
            JAVA_LONG.withName("tstamp"),
            JAVA_LONG.withName("ord")).withName("bitcask_get_result_t");
    public static final long GR_VALUE = off(GET_RESULT, "value");
    public static final long GR_META = off(GET_RESULT, "meta");
    public static final long GR_VECTOR = off(GET_RESULT, "vector");
    public static final long GR_VECTOR_LEN = off(GET_RESULT, "vector_len");
    public static final long GR_TSTAMP = off(GET_RESULT, "tstamp");
    public static final long GR_ORD = off(GET_RESULT, "ord");

    // ── bitcask_get_doc_result_t ────────────────────────
    public static final StructLayout DOC_FIELD_RESULT = structLayout(
            ADDRESS.withName("name"), SLICE.withName("value")).withName("bitcask_doc_field_result_t");
    public static final long DFR_NAME = off(DOC_FIELD_RESULT, "name");
    public static final long DFR_VALUE = off(DOC_FIELD_RESULT, "value");
    public static final StructLayout GET_DOC_RESULT = structLayout(
            SLICE.withName("value"),
            SLICE.withName("meta"),
            ADDRESS.withName("vector"),
            SIZE_T.withName("vector_len"),
            ADDRESS.withName("fields"),
            SIZE_T.withName("fields_count"),
            JAVA_LONG.withName("tstamp"),
            JAVA_LONG.withName("ord")).withName("bitcask_get_doc_result_t");
    public static final long GDR_VALUE = off(GET_DOC_RESULT, "value");
    public static final long GDR_META = off(GET_DOC_RESULT, "meta");
    public static final long GDR_VECTOR = off(GET_DOC_RESULT, "vector");
    public static final long GDR_VECTOR_LEN = off(GET_DOC_RESULT, "vector_len");
    public static final long GDR_FIELDS = off(GET_DOC_RESULT, "fields");
    public static final long GDR_FIELDS_COUNT = off(GET_DOC_RESULT, "fields_count");
    public static final long GDR_TSTAMP = off(GET_DOC_RESULT, "tstamp");
    public static final long GDR_ORD = off(GET_DOC_RESULT, "ord");

    // ── 检索结果 ─────────────────────────────────────────
    public static final StructLayout SEARCH_HIT = structLayout(
            ADDRESS.withName("key"), JAVA_LONG.withName("ord"), JAVA_DOUBLE.withName("score")).withName("bitcask_search_hit_t");
    public static final StructLayout SEARCH_RESULT = structLayout(
            ADDRESS.withName("hits"), SIZE_T.withName("count")).withName("bitcask_search_result_t");
    public static final StructLayout SNIPPET = structLayout(
            ADDRESS.withName("text"), JAVA_DOUBLE.withName("score")).withName("bitcask_snippet_t");
    public static final StructLayout SEARCH_HIT_EX = structLayout(
            ADDRESS.withName("key"),
            JAVA_LONG.withName("ord"),
            JAVA_DOUBLE.withName("score"),
            ADDRESS.withName("highlights"),
            SIZE_T.withName("highlights_count")).withName("bitcask_search_hit_ex_t");
    public static final StructLayout SEARCH_RESULT_EX = structLayout(
            ADDRESS.withName("hits"), SIZE_T.withName("count")).withName("bitcask_search_result_ex_t");
    public static final StructLayout SEARCH_HIT_FIELDS = structLayout(
            ADDRESS.withName("key"),
            JAVA_LONG.withName("ord"),
            JAVA_DOUBLE.withName("score"),
            ADDRESS.withName("fields"),
            SIZE_T.withName("fields_count")).withName("bitcask_search_hit_fields_t");
    public static final StructLayout SEARCH_FIELDS_RESULT = structLayout(
            ADDRESS.withName("hits"), SIZE_T.withName("count")).withName("bitcask_search_fields_result_t");
    public static final StructLayout HIGHLIGHT_OPTIONS = structLayout(
            ADDRESS.withName("pre_tag"),
            ADDRESS.withName("post_tag"),
            SIZE_T.withName("fragment_size"),
            SIZE_T.withName("max_fragments")).withName("bitcask_highlight_options_t");

    // ── 迭代条目 / range 选项 ────────────────────────────
    public static final StructLayout ITER_ENTRY = structLayout(
            SLICE.withName("key"),
            SLICE.withName("value"),
            JAVA_LONG.withName("tstamp"),
            JAVA_INT.withName("file_id"),
            paddingLayout(4),
            JAVA_LONG.withName("offset"),
            JAVA_INT.withName("total_sz"),
            JAVA_INT.withName("is_tombstone"),
            JAVA_LONG.withName("ord")).withName("bitcask_iter_entry_t");
    public static final StructLayout RANGE_OPTIONS = structLayout(
            SLICE.withName("lo"),
            SLICE.withName("hi"),
            SIZE_T.withName("prefetch"),
            SIZE_T.withName("prefetch_threads")).withName("bitcask_range_options_t");
    public static final long RO_LO = off(RANGE_OPTIONS, "lo");
    public static final long RO_HI = off(RANGE_OPTIONS, "hi");
    public static final long RO_PREFETCH = off(RANGE_OPTIONS, "prefetch");
    public static final StructLayout RANGE_ENTRY = structLayout(
            SLICE.withName("key"),
            SLICE.withName("value"),
            JAVA_LONG.withName("tstamp"),
            JAVA_LONG.withName("ord")).withName("bitcask_range_entry_t");
    public static final StructLayout ITER_ENTRY_EX = structLayout(
            ITER_ENTRY.withName("base"), SLICE.withName("meta")).withName("bitcask_iter_entry_ex_t");
    public static final StructLayout RANGE_ENTRY_EX = structLayout(
            RANGE_ENTRY.withName("base"), SLICE.withName("meta")).withName("bitcask_range_entry_ex_t");

    /** 两种 *_ex 条目里 key / value / tstamp / ord / meta 的偏移（游标共用一套读法）。 */
    public record EntryShape(StructLayout layout, long key, long value, long tstamp, long ord, long meta) {
        static EntryShape of(StructLayout l) {
            return new EntryShape(l, off(l, "base", "key"), off(l, "base", "value"), off(l, "base", "tstamp"),
                    off(l, "base", "ord"), off(l, "meta"));
        }
    }

    public static final EntryShape ITER_SHAPE = EntryShape.of(ITER_ENTRY_EX);
    public static final EntryShape RANGE_SHAPE = EntryShape.of(RANGE_ENTRY_EX);

    /** {@code bitcask_iter_entry_view_t}（libbitcask 13.0.0，S75）：免分配批视图条目，切片借迭代器内部存储、没有 free。 */
    public static final StructLayout ITER_ENTRY_VIEW = structLayout(
            SLICE.withName("key"),
            SLICE.withName("value"),
            SLICE.withName("meta"),
            JAVA_LONG.withName("tstamp"),
            JAVA_INT.withName("file_id"),
            paddingLayout(4),
            JAVA_LONG.withName("offset"),
            JAVA_INT.withName("total_sz"),
            JAVA_INT.withName("is_tombstone"),
            JAVA_LONG.withName("ord")).withName("bitcask_iter_entry_view_t");
    public static final EntryShape VIEW_SHAPE = new EntryShape(ITER_ENTRY_VIEW, off(ITER_ENTRY_VIEW, "key"),
            off(ITER_ENTRY_VIEW, "value"), off(ITER_ENTRY_VIEW, "tstamp"), off(ITER_ENTRY_VIEW, "ord"), off(ITER_ENTRY_VIEW, "meta"));

    // ── 状态 / 批 / 文档 ─────────────────────────────────
    public static final StructLayout STATUS_EX = structLayout(
            JAVA_LONG.withName("key_count"),
            JAVA_LONG.withName("key_bytes"),
            JAVA_LONG.withName("epoch"),
            JAVA_LONG.withName("index_errors"),
            JAVA_LONG.withName("hnsw_nodes"),
            JAVA_LONG.withName("search_cache_entries"),
            JAVA_LONG.withName("read_handles")).withName("bitcask_status_ex_t");
    public static final StructLayout KV_PAIR = structLayout(
            SLICE.withName("key"), SLICE.withName("value")).withName("bitcask_kv_pair_t");
    public static final StructLayout TXN_OP = structLayout(
            JAVA_BYTE.withName("op"),
            paddingLayout(7),
            SLICE.withName("key"),
            SLICE.withName("value")).withName("bitcask_txn_op_t");
    public static final StructLayout DOC_FIELD = structLayout(
            SLICE.withName("name"), SLICE.withName("value")).withName("bitcask_doc_field_t");
    public static final StructLayout DOC_INPUT_EX = structLayout(
            SLICE.withName("text"),
            SLICE.withName("meta"),
            ADDRESS.withName("vector"),
            SIZE_T.withName("vector_len"),
            JAVA_LONG.withName("expiry_at"),
            ADDRESS.withName("fields"),
            SIZE_T.withName("fields_count")).withName("bitcask_doc_input_ex_t");

    // ── D-03：meta 编解码 / 过滤树 / 带文档的原子批 / 混合批量查询 ──
    public static final StructLayout META_VALUE = structLayout(
            JAVA_INT.withName("type"),
            paddingLayout(4),
            JAVA_LONG.withName("i64"),
            JAVA_DOUBLE.withName("f64"),
            ADDRESS.withName("str")).withName("bitcask_meta_value_t");
    public static final StructLayout META_CONDITION = structLayout(
            ADDRESS.withName("key"),
            JAVA_INT.withName("op"),
            paddingLayout(4),
            META_VALUE.withName("value"),
            ADDRESS.withName("values"),
            SIZE_T.withName("values_count")).withName("bitcask_meta_condition_t");
    public static final StructLayout META_FILTER = structLayout(
            JAVA_INT.withName("logic_or"),
            paddingLayout(4),
            ADDRESS.withName("conditions"),
            SIZE_T.withName("conditions_count"),
            ADDRESS.withName("children"),
            SIZE_T.withName("children_count")).withName("bitcask_meta_filter_t");
    public static final StructLayout META_ENTRY = structLayout(
            ADDRESS.withName("key"), META_VALUE.withName("value")).withName("bitcask_meta_entry_t");
    /** {@code bitcask_meta_entry_ex_t}（S62）：value.type == STRING_ARRAY(5) 时读 items / items_count。 */
    public static final StructLayout META_ENTRY_EX = structLayout(
            ADDRESS.withName("key"), META_VALUE.withName("value"), ADDRESS.withName("items"), SIZE_T.withName("items_count"))
            .withName("bitcask_meta_entry_ex_t");
    public static final StructLayout META_VALUE_VIEW = structLayout(
            JAVA_INT.withName("type"),
            paddingLayout(4),
            JAVA_LONG.withName("i64"),
            JAVA_DOUBLE.withName("f64"),
            SLICE.withName("str")).withName("bitcask_meta_value_view_t");
    public static final StructLayout META_ITER = structLayout(
            SIZE_T.withName("offset"), JAVA_LONG.withName("remaining")).withName("bitcask_meta_iter_t");
    public static final StructLayout TXN_OP_EX = structLayout(
            JAVA_BYTE.withName("op"),
            paddingLayout(7),
            SLICE.withName("key"),
            SLICE.withName("value"),
            ADDRESS.withName("doc")).withName("bitcask_txn_op_ex_t");
    /** D-10（libbitcask 8.0.0 S46）：带前置条件的批 op。op：0 put / 1 remove / 2 put_doc / 3 check；expect：0 无 / 1 不存在 / 2 ord 相等。 */
    public static final StructLayout COND_OP = structLayout(
            JAVA_BYTE.withName("op"),
            JAVA_BYTE.withName("expect"),
            paddingLayout(6),
            JAVA_LONG.withName("expect_ord"),
            SLICE.withName("key"),
            SLICE.withName("value"),
            ADDRESS.withName("doc")).withName("bitcask_cond_op_t");
    public static final StructLayout HYBRID_QUERY = structLayout(
            ADDRESS.withName("text"),
            ADDRESS.withName("vector"),
            SIZE_T.withName("vector_len")).withName("bitcask_hybrid_query_t");

    // ── D-04：merge 策略 / 开库调优 / needs_merge ─────────
    public static final StructLayout MERGE_POLICY = structLayout(
            JAVA_INT.withName("frag_merge_trigger"),
            paddingLayout(4),
            JAVA_LONG.withName("dead_bytes_merge_trigger"),
            JAVA_INT.withName("deletion_rate_trigger"),
            JAVA_INT.withName("frag_threshold"),
            JAVA_LONG.withName("dead_bytes_threshold"),
            JAVA_LONG.withName("small_file_threshold"),
            JAVA_INT.withName("expiry_secs"),
            JAVA_INT.withName("expiry_grace_time"),
            JAVA_LONG.withName("max_merge_size")).withName("bitcask_merge_policy_t");
    public static final StructLayout OPEN_TUNING = structLayout(
            SIZE_T.withName("struct_size"),
            JAVA_INT.withName("segment_verify_crc"),
            paddingLayout(4)).withName("bitcask_open_tuning_t");
    public static final StructLayout NEEDS_MERGE = structLayout(
            JAVA_INT.withName("needs"),
            paddingLayout(4),
            ADDRESS.withName("files"),
            SIZE_T.withName("files_count")).withName("bitcask_needs_merge_t");

    // ── S55（libbitcask 10.0.0）：bitcask_meta_info_t ─────
    public static final StructLayout META_INFO = structLayout(
            JAVA_BYTE.withName("meta_version"),
            paddingLayout(3),
            JAVA_INT.withName("index_mode"),
            JAVA_INT.withName("vector_metric"),
            JAVA_SHORT.withName("vector_dim"),
            paddingLayout(2),
            JAVA_INT.withName("vector_quantized"),
            JAVA_INT.withName("vector_inmem_int8"),
            JAVA_INT.withName("vector_engine"),
            JAVA_BYTE.withName("icu_major"),
            JAVA_BYTE.withName("unicode_major"),
            paddingLayout(2),
            JAVA_INT.withName("analyzer_fingerprint"),
            JAVA_INT.withName("analyzer_recorded"),
            JAVA_INT.withName("analyzer_type"),
            JAVA_INT.withName("analyzer_min_n"),
            JAVA_INT.withName("analyzer_max_n"),
            JAVA_INT.withName("enable_stop_words"),
            ADDRESS.withName("stop_words"),
            JAVA_INT.withName("min_token_length"),
            JAVA_INT.withName("max_token_bytes"),
            JAVA_INT.withName("enable_stemming"),
            paddingLayout(4)).withName("bitcask_meta_info_t");

    // ── 读写小工具 ───────────────────────────────────────

    /** 写一个 slice（{@code data} 为 null ⇒ NULL / 0）。 */
    public static void setSlice(MemorySegment s, long offset, MemorySegment data, long size) {
        s.set(ADDRESS, offset + 0, data == null ? MemorySegment.NULL : data);
        s.set(SIZE_T, offset + 8, data == null ? 0 : size);
    }

    /** 读 slice 指向的字节（借 C 侧缓冲，全局作用域；调用方负责生命周期）。NULL / 0 ⇒ 空段。 */
    public static MemorySegment sliceView(MemorySegment s, long offset) {
        MemorySegment data = s.get(ADDRESS, offset);
        long size = s.get(SIZE_T, offset + 8);
        if (data.equals(MemorySegment.NULL) || size == 0) return MemorySegment.NULL;
        return data.reinterpret(size);
    }

    /** 拷出 slice 的字节。 */
    public static byte[] sliceBytes(MemorySegment s, long offset) {
        MemorySegment v = sliceView(s, offset);
        return v.byteSize() == 0 ? new byte[0] : v.toArray(JAVA_BYTE);
    }

    /** NUL 结尾 UTF-8 串（NULL ⇒ 空串）。 */
    public static String cString(MemorySegment p) {
        if (p.equals(MemorySegment.NULL)) return "";
        return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    }

    /** 拷出 float 数组（NULL ⇒ 空）。 */
    public static float[] floats(MemorySegment p, long count) {
        if (p.equals(MemorySegment.NULL) || count == 0) return new float[0];
        return p.reinterpret(Math.multiplyExact(count, 4L)).toArray(JAVA_FLOAT);
    }

    public static int checkedInt(long n) {
        return Math.toIntExact(n);
    }
}
