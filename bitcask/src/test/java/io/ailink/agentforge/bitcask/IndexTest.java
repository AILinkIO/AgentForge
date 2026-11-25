package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.f;
import static io.ailink.agentforge.bitcask.TestSupport.keys;
import static io.ailink.agentforge.bitcask.TestSupport.s;
import static io.ailink.agentforge.bitcask.TestSupport.sortedKeys;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 检索层。期望值移植自 libbitcask tests/c_api_test.c（test_doc_fields_and_meta / test_paging_and_highlight 的形状）。 */
class IndexTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    static <T> T single(List<T> hits) {
        assertEquals(1, hits.size(), String.valueOf(hits));
        return hits.getFirst();
    }

    @Test
    void bm25BagOfWords() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("d0", "hello world");
            ix.putDocument("d1", "hello there");
            ix.putDocument("d2", "foo bar");
            ix.flush();
            assertEquals(List.of("d0", "d1"), sortedKeys(ix.searchText("hello")));
            assertEquals("d2", single(ix.searchText("foo")).key());
            assertTrue(ix.searchText("zzz").isEmpty());
            assertTrue(ix.searchText("hello").stream().allMatch(h -> h.score() > 0));
        }
    }

    @Test
    void phraseBoolFuzzyWildcard() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("d0", "hello world");
            ix.putDocument("d1", "world hello");
            ix.putDocument("d2", "foo bar");
            ix.flush();
            assertEquals("d0", single(ix.searchPhrase("hello world")).key());
            assertEquals("d0", single(ix.searchNear("hello world", SearchOptions.defaults().slop(2))).key());
            assertEquals(List.of("d0", "d1"), sortedKeys(ix.searchFuzzy("helo", SearchOptions.defaults().maxEdits(1))));
            assertEquals(List.of("d0", "d1"), sortedKeys(ix.searchWildcard("wor*")));
            assertEquals("d2", single(ix.searchBool("foo AND bar")).key());
        }
    }

    @Test
    void pagingWithOffsetIsPrefixConsistent() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            for (int i = 0; i < 20; i++) ix.putDocument(String.format("p%02d", i), "apple ".repeat(i + 1) + "pie");
            ix.flush();
            List<String> all = keys(ix.searchText("apple", SearchOptions.k(20)));
            assertEquals(20, all.size());
            assertEquals(all.subList(5, 10), keys(ix.searchText("apple", SearchOptions.k(5).offset(5))));
        }
    }

    @Test
    void namedFieldsAreSearchableByField() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("a", Document.builder().field("title", "rust guide").field("body", "systems programming").build());
            ix.putDocument("b", Document.builder().field("title", "cooking").field("body", "rust removal from pans").build());
            ix.flush();
            assertEquals("a", single(ix.searchFields("title:rust")).key());
            assertEquals("b", single(ix.searchFields("body:rust")).key());
            assertEquals(List.of("a", "b"), sortedKeys(ix.searchText("rust")));
            // 字段命中归属：无字段限定的词经 catch-all 落默认字段，记空串
            assertEquals(List.of("title"), single(ix.searchFields("title:rust")).fields());
            assertEquals(List.of("body"), single(ix.searchFields("body:rust")).fields());
            for (FieldsHit h : ix.searchFields("rust")) assertTrue(h.fields().contains(""), "默认字段应记空串：" + h);
        }
    }

    @Test
    void getDocReadsBackFields() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("d", Document.builder().text("正文").field("title", "标题").field("body", "内容").meta(Map.of("price", 42)).build());
            ix.flush();
            StoredDocument doc = ix.getDocument("d");
            assertEquals("正文", doc.text());
            assertEquals(Map.of("title", "标题", "body", "内容"), doc.fields());
            assertEquals(List.of("title", "body"), new ArrayList<>(doc.fields().keySet()), "写入序");
            assertEquals(Map.of("price", 42L), Meta.decodeMap(doc.meta()));
            assertNull(doc.vector());
            assertTrue(doc.tstamp() > 0);
            assertTrue(doc.ord() >= 0);
            assertNull(ix.getDocument("missing"));
        }
    }

    @Test
    void highlightWrapsTerms() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("h", "the quick brown fox jumps");
            ix.flush();
            List<HighlightHit> hits = ix.searchHighlighted("fox", HighlightOptions.defaults().k(10).tags("[", "]"));
            assertEquals(1, hits.size());
            assertEquals("h", hits.getFirst().key());
            assertTrue(hits.getFirst().snippets().stream().anyMatch(x -> x.contains("[fox]")), String.valueOf(hits));
        }
    }

    // 冷文档高亮：关库前 checkpoint ⇒ 重开不重放、LRU 为空，目标文档确定是冷的。
    @Test
    void highlightedColdLoad() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("h", "the quick brown fox jumps");
            ix.put("k2", "lazy dog watches the fox"); // 纯 KV 写入：正文即 value
            for (int i = 0; i < 1500; i++) ix.putDocument("zz" + i, "filler text number " + i);
            ix.flush();
            ix.checkpoint();
        }
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            var k10 = HighlightOptions.defaults().k(10);
            List<HighlightHit> skip = ix.searchHighlighted("fox", k10);
            List<HighlightHit> load = ix.searchHighlighted("fox", HighlightOptions.defaults().k(10).cold(HighlightOptions.ColdMode.LOAD));
            List<HighlightHit> tagged = ix.searchHighlighted("fox",
                    HighlightOptions.defaults().k(10).cold(HighlightOptions.ColdMode.LOAD).tags("[", "]"));
            assertEquals(List.of("h", "k2"), sortedKeys(skip));
            assertEquals(keys(skip), keys(load), "命中与排序不变，只补片段");
            assertTrue(skip.stream().allMatch(h -> h.snippets().isEmpty()), "重开即冷：缺省没有片段 " + skip);
            assertTrue(load.stream().allMatch(h -> h.snippets().stream().anyMatch(x -> x.contains("<em>fox</em>"))), String.valueOf(load));
            assertTrue(tagged.stream().allMatch(h -> h.snippets().stream().anyMatch(x -> x.contains("[fox]"))), "标签选项对冷路径同样生效");
            assertTrue(ix.searchHighlighted("fox", k10).stream().allMatch(h -> h.snippets().isEmpty()), "读回的原文不回填 LRU");
        }
    }

    @Test
    void vectorAndHybrid() {
        try (var ix = BitcaskIndex.open(d, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.L2))) {
            float[][] vecs = {f(1, 0, 0, 0), f(0, 1, 0, 0), f(0, 0, 1, 0)};
            String[] texts = {"alpha", "beta", "gamma"};
            for (int i = 0; i < 3; i++) ix.putDocument("v" + i, Document.builder().text(texts[i]).vector(vecs[i]).build());
            ix.flush();
            assertEquals("v0", ix.searchVector(f(1, 0, 0, 0), SearchOptions.k(3)).getFirst().key());
            assertEquals("v2", ix.searchVector(f(0, 0, 1, 0), SearchOptions.k(3)).getFirst().key());
            List<SearchHit> hybrid = ix.searchHybrid("alpha", f(1, 0, 0, 0), SearchOptions.k(5));
            assertEquals("v0", hybrid.getFirst().key());
            assertEquals("v2", ix.searchHybrid("gamma", null, SearchOptions.k(5)).getFirst().key());
            assertEquals("v1", ix.searchHybrid(null, f(0, 1, 0, 0), SearchOptions.k(5)).getFirst().key());
            try (Record r = ix.readRecord(u("v1"))) {
                assertNotNull(r);
                assertEquals("beta", s(r.value()));
                assertArrayEquals(f(0, 1, 0, 0), r.vector());
                assertEquals(16, r.vectorSegment().byteSize());
            }
            assertEquals("bitcask/query-dim-mismatch", code(() -> ix.searchVector(f(1, 0, 0))));
            assertEquals("bitcask/dim-mismatch", code(() -> ix.putDocument("bad", Document.builder().text("x").vector(f(1, 2, 3)).build())));
            assertEquals("bitcask/need-text-or-vector", code(() -> ix.searchHybrid(null, null)));
        }
    }

    @Test
    void rankByVectorOverGivenKeys() {
        try (var ix = BitcaskIndex.open(d, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.L2))) {
            Object[][] docs = {{"a", f(1, 0, 0, 0)}, {"b", f(0.9, 0.1, 0, 0)}, {"c", f(0, 1, 0, 0)}, {"d", f(0, 0, 1, 0)}, {"e", f(0.5, 0.5, 0, 0)}};
            for (Object[] doc : docs) ix.putDocument((String) doc[0], Document.builder().text((String) doc[0]).vector((float[]) doc[1]).build());
            ix.putDocument("novec", "just text");
            List<SearchHit> hits = ix.rankByVector(f(1, 0, 0, 0), List.of("c", "b", "e", "a", "d"));
            assertEquals(List.of("a", "b", "e", "c", "d"), keys(hits));
            for (int i = 1; i < hits.size(); i++) assertTrue(hits.get(i - 1).score() >= hits.get(i).score());
            assertEquals(List.of("b", "e"), keys(ix.rankByVector(f(1, 0, 0, 0), List.of("c", "b", "e"), 2)));
            assertEquals(List.of("c", "e"), keys(ix.rankByVector(f(0, 1, 0, 0), List.of("e", "missing", "novec", "e", u("c")))));
            assertEquals(List.of(), ix.rankByVector(f(1, 0, 0, 0), List.of()));
            assertEquals(List.of(), ix.rankByVector(f(1, 0, 0, 0), List.of("missing", "novec")));
            ix.flush();
            assertEquals(ix.searchVector(f(1, 0, 0, 0), SearchOptions.k(1)).getFirst().score(),
                    ix.rankByVector(f(1, 0, 0, 0), List.of("a")).getFirst().score(), 1e-12); // L2 距离 0 取负 ⇒ ±0.0
            assertEquals("bitcask/query-dim-mismatch", code(() -> ix.rankByVector(f(1, 0, 0), List.of("a"))));
        }
    }

    @Test
    void indexSurvivesReopenAndRemove() {
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("d0", "hello world");
            ix.putDocument("d1", "hello there");
            ix.flush();
        }
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            assertEquals(2, ix.searchText("hello").size());
            ix.remove("d1");
            ix.flush();
            assertEquals("d0", single(ix.searchText("hello")).key());
        }
    }
}
