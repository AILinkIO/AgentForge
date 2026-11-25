package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.NEEDS_MERGE;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_HIT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_HIT_EX;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_HIT_FIELDS;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_FIELDS_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_RESULT;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SEARCH_RESULT_EX;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SIZE_T;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.SNIPPET;
import static io.ailink.agentforge.bitcask.ffm.NativeTypes.off;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.MemorySegment;
import java.util.List;

import io.ailink.agentforge.bitcask.FieldsHit;
import io.ailink.agentforge.bitcask.HighlightHit;
import io.ailink.agentforge.bitcask.SearchHit;

/** C 侧结果结构体的读取（逐条拷出；不释放 —— 调用方 finally 里调对应的 free）。 */
public final class Results {
    private static final long HITS = off(SEARCH_RESULT, "hits"), COUNT = off(SEARCH_RESULT, "count");
    private static final long HITS_EX = off(SEARCH_RESULT_EX, "hits"), COUNT_EX = off(SEARCH_RESULT_EX, "count");
    private static final long HX_KEY = off(SEARCH_HIT_EX, "key"), HX_ORD = off(SEARCH_HIT_EX, "ord"), HX_SCORE = off(SEARCH_HIT_EX, "score");
    private static final long HX_HL = off(SEARCH_HIT_EX, "highlights"), HX_HL_COUNT = off(SEARCH_HIT_EX, "highlights_count");
    private static final long HITS_F = off(SEARCH_FIELDS_RESULT, "hits"), COUNT_F = off(SEARCH_FIELDS_RESULT, "count");
    private static final long HF_KEY = off(SEARCH_HIT_FIELDS, "key"), HF_ORD = off(SEARCH_HIT_FIELDS, "ord"), HF_SCORE = off(SEARCH_HIT_FIELDS, "score");
    private static final long HF_FIELDS = off(SEARCH_HIT_FIELDS, "fields"), HF_FIELDS_COUNT = off(SEARCH_HIT_FIELDS, "fields_count");

    private Results() {
    }

    /** {@code bitcask_search_result_t*}（NULL ⇒ 空）。 */
    public static List<SearchHit> hits(MemorySegment r) {
        if (r.equals(MemorySegment.NULL)) return List.of();
        r = r.reinterpret(SEARCH_RESULT.byteSize());
        int n = Math.toIntExact(r.get(SIZE_T, COUNT));
        if (n == 0) return List.of();
        long size = SEARCH_HIT.byteSize();
        MemorySegment hs = r.get(ADDRESS, HITS).reinterpret(n * size);
        SearchHit[] out = new SearchHit[n];
        for (int i = 0; i < n; i++) {
            long b = i * size;
            out[i] = new SearchHit(NativeTypes.cString(hs.get(ADDRESS, b)), hs.get(JAVA_LONG, b + 8), hs.get(JAVA_DOUBLE, b + 16));
        }
        return List.of(out);
    }

    /** {@code bitcask_search_result_ex_t*}（NULL ⇒ 空）。 */
    public static List<HighlightHit> highlighted(MemorySegment r) {
        if (r.equals(MemorySegment.NULL)) return List.of();
        r = r.reinterpret(SEARCH_RESULT_EX.byteSize());
        int n = Math.toIntExact(r.get(SIZE_T, COUNT_EX));
        long size = SEARCH_HIT_EX.byteSize();
        HighlightHit[] out = new HighlightHit[n];
        if (n == 0) return List.of();
        MemorySegment hs = r.get(ADDRESS, HITS_EX).reinterpret(n * size);
        for (int i = 0; i < n; i++) {
            long b = i * size;
            int sn = Math.toIntExact(hs.get(SIZE_T, b + HX_HL_COUNT));
            String[] snippets = new String[sn];
            if (sn > 0) {
                MemorySegment hl = hs.get(ADDRESS, b + HX_HL).reinterpret(sn * SNIPPET.byteSize());
                for (int j = 0; j < sn; j++) snippets[j] = NativeTypes.cString(hl.get(ADDRESS, j * SNIPPET.byteSize()));
            }
            out[i] = new HighlightHit(NativeTypes.cString(hs.get(ADDRESS, b + HX_KEY)), hs.get(JAVA_LONG, b + HX_ORD),
                    hs.get(JAVA_DOUBLE, b + HX_SCORE), List.of(snippets));
        }
        return List.of(out);
    }

    /** {@code bitcask_search_fields_result_t*}（NULL ⇒ 空）。 */
    public static List<FieldsHit> fields(MemorySegment r) {
        if (r.equals(MemorySegment.NULL)) return List.of();
        r = r.reinterpret(SEARCH_FIELDS_RESULT.byteSize());
        int n = Math.toIntExact(r.get(SIZE_T, COUNT_F));
        FieldsHit[] out = new FieldsHit[n];
        if (n == 0) return List.of();
        long size = SEARCH_HIT_FIELDS.byteSize();
        MemorySegment hs = r.get(ADDRESS, HITS_F).reinterpret(n * size);
        for (int i = 0; i < n; i++) {
            long b = i * size;
            int fn = Math.toIntExact(hs.get(SIZE_T, b + HF_FIELDS_COUNT));
            String[] fields = new String[fn];
            if (fn > 0) {
                MemorySegment fs = hs.get(ADDRESS, b + HF_FIELDS).reinterpret(fn * ADDRESS.byteSize());
                for (int j = 0; j < fn; j++) fields[j] = NativeTypes.cString(fs.getAtIndex(ADDRESS, j));
            }
            out[i] = new FieldsHit(NativeTypes.cString(hs.get(ADDRESS, b + HF_KEY)), hs.get(JAVA_LONG, b + HF_ORD),
                    hs.get(JAVA_DOUBLE, b + HF_SCORE), List.of(fields));
        }
        return List.of(out);
    }

    /** {@code bitcask_search_result_t**}（n 条；整体 NULL 或单条 NULL ⇒ 对应位置 null）。 */
    public static List<List<SearchHit>> batch(MemorySegment rs, int n) {
        java.util.ArrayList<List<SearchHit>> out = new java.util.ArrayList<>(n);
        if (rs.equals(MemorySegment.NULL)) {
            for (int i = 0; i < n; i++) out.add(null);
            return out;
        }
        rs = rs.reinterpret(n * ADDRESS.byteSize());
        for (int i = 0; i < n; i++) {
            MemorySegment r = rs.getAtIndex(ADDRESS, i);
            out.add(r.equals(MemorySegment.NULL) ? null : hits(r));
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /** {@code bitcask_needs_merge_t} 的候选文件路径。 */
    public static String[] needsMergeFiles(MemorySegment nm) {
        long n = nm.get(SIZE_T, off(NEEDS_MERGE, "files_count"));
        MemorySegment arr = nm.get(ADDRESS, off(NEEDS_MERGE, "files"));
        if (n <= 0 || arr.equals(MemorySegment.NULL)) return new String[0];
        arr = arr.reinterpret(n * ADDRESS.byteSize());
        String[] out = new String[Math.toIntExact(n)];
        for (int i = 0; i < out.length; i++) out[i] = NativeTypes.cString(arr.getAtIndex(ADDRESS, i));
        return out;
    }
}
