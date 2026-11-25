package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.f;
import static io.ailink.agentforge.bitcask.TestSupport.keys;
import static io.ailink.agentforge.bitcask.TestSupport.sortedKeys;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 批量检索。期望值移植自 libbitcask c_api_test.c test_search_text_batch / test_search_vector_hybrid_batch 与 keel tests/bitcask.ss。 */
class BatchSearchTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    @Test
    void upstreamTextBatch() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("d0", "hello world");
            ix.putDocument("d1", "hello there");
            ix.putDocument("d2", "foo bar");
            ix.flush();
            var rs = ix.searchTextBatch(List.of("hello", "foo", "zzz"));
            assertEquals(3, rs.size());
            assertEquals(2, rs.get(0).size());
            assertEquals(1, rs.get(1).size());
            assertEquals(List.of(), rs.get(2), "无命中：非 null、0 条");
            assertEquals(List.of(), ix.searchTextBatch(List.of()));
        }
    }

    @Test
    void upstreamVectorAndHybridBatch() {
        try (var ix = BitcaskIndex.open(d, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.L2))) {
            float[][] vecs = {f(1, 0, 0, 0), f(0, 1, 0, 0), f(0, 0, 1, 0)};
            String[] texts = {"alpha", "beta", "gamma"};
            for (int i = 0; i < 3; i++) ix.putDocument("v" + i, Document.builder().text(texts[i]).vector(vecs[i]).build());
            ix.flush();
            var vr = ix.searchVectorBatch(List.of(vecs[0], vecs[2]), 3, 0);
            assertEquals("v0", vr.get(0).getFirst().key());
            assertEquals("v2", vr.get(1).getFirst().key());
            var hr = ix.searchHybridBatch(List.of(new HybridQuery("alpha", vecs[0]), new HybridQuery("gamma", vecs[2])), 5);
            assertFalse(hr.get(0).isEmpty());
            assertFalse(hr.get(1).isEmpty());
            assertEquals(List.of(), ix.searchVectorBatch(List.of()));
            assertEquals("bitcask/query-dim-mismatch", code(() -> ix.searchVectorBatch(List.of(f(1, 0)))));
        }
    }

    @Test
    void keelBatchesAlignByPosition() {
        try (var ix = FilterTest.openKeelCorpus(d)) {
            var rs = ix.searchTextBatch(List.of("牛肉", "咖啡", "番茄"), 5);
            assertEquals(3, rs.size());
            assertEquals(List.of("d1", "d5", "d6"), sortedKeys(rs.get(0)));
            assertEquals(List.of("d3"), keys(rs.get(1)));
            assertEquals(List.of("d2"), keys(rs.get(2)));
            var vr = ix.searchVectorBatch(List.of(f(0, 1, 0), f(1, 0, 0)), 4, 0);
            assertEquals("d2", vr.get(0).getFirst().key());
            assertTrue(Set.of("d1", "d4").contains(vr.get(1).getFirst().key()));
            var hr = ix.searchHybridBatch(List.of(new HybridQuery("咖啡", null), new HybridQuery(null, f(0, 1, 0)), new HybridQuery("牛肉", f(1, 0, 0))), 4);
            assertEquals("d3", hr.get(0).getFirst().key());
            assertEquals("d2", hr.get(1).getFirst().key());
            // d1 / d4 向量完全相同（距离并列），HNSW 对并列的先后不作保证 ⇒ 只认这两个
            assertTrue(Set.of("d1", "d6").contains(hr.get(2).getFirst().key()), String.valueOf(keys(hr.get(2))));
            // 两路都空的查询让整批被拒
            assertEquals("bitcask/need-text-or-vector", code(() -> ix.searchHybridBatch(List.of(new HybridQuery("咖啡", null), new HybridQuery(null, null)), 4)));
        }
    }
}
