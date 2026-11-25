package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.f;
import static io.ailink.agentforge.bitcask.TestSupport.keys;
import static io.ailink.agentforge.bitcask.TestSupport.sortedKeys;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * meta 过滤：检索、带 meta / 过滤的迭代。期望值移植自 libbitcask c_api_test.c test_search_filtered / test_doc_fields_and_meta /
 * test_meta_filter_query，与 keel tests/bitcask.ss §K3、「meta 过滤」那一组。
 */
class FilterTest {
    @TempDir
    Path d;

    static SearchOptions fo(Filter f) {
        return SearchOptions.defaults().filter(f);
    }

    static Filter deep() {
        Filter f = Filter.and(Filter.exists("tag"));
        for (int i = 0; i < 40; i++) f = Filter.and(f);
        return f;
    }

    @Test
    void upstreamFiltersWithoutMetaPassNothing() {
        assumeNative();
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("f0", "hello world");
            ix.putDocument("f1", "hello there");
            ix.putDocument("f2", "foo bar");
            ix.flush();
            assertTrue(ix.searchText("hello", fo(Filter.exists("tag"))).isEmpty());
            assertTrue(ix.searchText("hello", fo(Filter.notEq("tag", 1))).isEmpty());
            assertTrue(ix.searchText("hello", fo(Filter.and(Filter.or(Filter.exists("tag"), Filter.notEq("tag", 1))))).isEmpty());
            assertEquals(2, ix.searchText("hello", fo(null)).size());
            // 嵌套 > 32 层 ⇒ INVALID_OPTION
            assertEquals(BitcaskError.INVALID_OPTION, thrown(() -> ix.searchText("hello", fo(deep()))).error());
        }
    }

    @Test
    void upstreamFieldsAndMetaFilter() {
        assumeNative();
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            byte[] meta = Meta.encode(List.of(new SimpleEntry<>("price", 42), new SimpleEntry<>("author", "david"), new SimpleEntry<>("draft", false)));
            ix.putDocument("d0", Document.builder().text("hello world").meta(meta).field("title", "alpha").field("body", "gamma").build());
            ix.putDocument("d1", Document.builder().field("title", "beta").build());
            ix.flush();
            assertEquals(List.of("d0"), keys(ix.searchText("hello", fo(Filter.eq("author", "david")))));
            assertTrue(ix.searchText("hello", fo(Filter.gt("price", 100))).isEmpty());
            assertEquals(List.of("d0"), keys(ix.searchFields("title:alpha", fo(Filter.lte("price", 42)))));
            assertTrue(ix.searchFields("title:beta", fo(Filter.exists("price"))).isEmpty());
        }
    }

    static BitcaskIndex openMetaQuery(Path d) {
        var ix = BitcaskIndex.open(d, whitespace());
        byte[] keep = Meta.encode(Map.of("tag", "keep")), drop = Meta.encode(Map.of("tag", "drop"));
        for (int i = 0; i < 200; i++)
            ix.putDocument(String.format("d%03d", i), Document.builder().text("apple pie").meta(i % 10 == 0 ? keep : drop).build());
        ix.flush();
        return ix;
    }

    @Test
    void upstreamMetaFilterQueryAllTextSearches() {
        assumeNative();
        try (var ix = openMetaQuery(d)) {
            Filter f = Filter.eq("tag", "keep");
            assertEquals(10, ix.searchText("apple", fo(f).withK(10)).size());
            assertEquals(20, ix.searchText("apple", fo(f).withK(50)).size());
            assertEquals(10, ix.searchPhrase("apple pie", fo(f).withK(10)).size());
            assertEquals(5, ix.searchPhrase("apple pie", fo(f).withK(10).offset(15)).size());
            assertEquals(10, ix.searchBool("apple AND pie", fo(f).withK(10).offset(10)).size());
            assertEquals(10, ix.searchFields("apple", fo(f).withK(10)).size());
            assertEquals(10, ix.searchNear("apple pie", fo(f).slop(0).withK(10)).size());
            assertEquals(10, ix.searchFuzzy("aple", fo(f).maxEdits(1).withK(10)).size());
            assertEquals(10, ix.searchWildcard("app*", fo(f).withK(10)).size());
            assertEquals(10, ix.searchWildcard("app*", fo(null).withK(10)).size());
            for (SearchHit h : ix.searchText("apple", fo(f).withK(50))) assertEquals(0, Integer.parseInt(h.key().substring(1)) % 10);
            // 小 k 恒为大 k 的前缀
            assertEquals(keys(ix.searchText("apple", fo(f).withK(5))), keys(ix.searchText("apple", fo(f).withK(20))).subList(0, 5));
        }
    }

    @Test
    void upstreamMetaFilterQueryIterators() {
        assumeNative();
        try (var ix = openMetaQuery(d)) {
            byte[] keep = Meta.encode(Map.of("tag", "keep"));
            Filter f = Filter.eq("tag", "keep");
            List<Entry> es = ix.scanEntries(ScanOptions.all().filter(f));
            assertEquals(20, es.size());
            for (Entry e : es) {
                assertEquals(0, Integer.parseInt(e.keyString().substring(1)) % 10);
                assertArrayEquals(keep, e.meta());
                assertEquals("apple pie", e.valueString());
            }
            List<Entry> all = ix.scanEntries();
            assertEquals(200, all.size());
            assertTrue(all.stream().allMatch(e -> e.meta().length == 0));
            for (int prefetch : new int[]{0, 64}) {
                try (Cursor c = ix.rangeCursor(RangeOptions.between("d050", "d150").filter(f).meta(true).prefetch(prefetch))) {
                    List<String> ks = new ArrayList<>();
                    while (c.moveNext()) {
                        assertArrayEquals(keep, c.meta());
                        ks.add(c.keyString());
                    }
                    assertEquals(IntStream.range(0, 10).mapToObj(i -> String.format("d%03d", 50 + 10 * i)).toList(), ks);
                }
            }
            assertEquals(BitcaskError.INVALID_OPTION, thrown(() -> ix.scanCursor(ScanOptions.all().filter(deep()))).error());
            assertEquals(BitcaskError.INVALID_OPTION, thrown(() -> ix.searchNear("apple pie", fo(deep()).slop(0).withK(10))).error());
        }
    }

    @Test
    void rangeWithMetaEntriesCopyOut() {
        assumeNative();
        try (var ix = openMetaQuery(d); Cursor c = ix.rangeCursor(RangeOptions.between("d000", "d001").meta(true))) {
            assertTrue(c.moveNext());
            Entry e = c.entry();
            assertEquals("d000", e.keyString());
            assertArrayEquals(Meta.encode(Map.of("tag", "keep")), e.meta());
            assertFalse(c.moveNext());
        }
    }

    /** keel tests/bitcask.ss 的那份语料（ngram + 3 维向量；d2 的 meta 是裸 JSON，d5 / d6 的是手写的编码字节）。 */
    static BitcaskIndex openKeelCorpus(Path d) {
        var ix = BitcaskIndex.open(d, Options.defaults().analyzer(Options.Analyzer.NGRAM).vectorDimension(3));
        int[] raw = {0x01, 0x82, 0x84, 0x6b, 0x69, 0x6e, 0x64, 0x04, 0x84, 0x66, 0x6f, 0x6f, 0x64, 0x81, 0x6e, 0x02, 0x05, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] food = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) food[i] = (byte) raw[i];
        ix.put("s", "值");
        ix.putDocument("d1", Document.builder().text("红烧牛肉面 beef noodle soup").vector(f(1, 0, 0)).build());
        ix.putDocument("d2", Document.builder().text("番茄鸡蛋面 tomato egg noodle").vector(f(0, 1, 0)).meta(u("{\"kind\":\"food\"}")).build());
        ix.putDocument("d3", Document.builder().text("咖啡 coffee").vector(f(0, 0, 1)).build());
        ix.putDocument("d4", Document.builder().text("经 embedder 写进去的").vector(f(1, 0, 0)).build());
        for (String k : new String[]{"p/a", "p/b", "q/c"}) ix.put(u(k), new byte[]{9});
        ix.putDocument("d5", Document.builder().text("牛肉拉面 beef noodle house").meta(food).build());
        ix.putDocument("d6", Document.builder().text("牛肉汤 beef soup house").vector(f(1, 1, 0)).meta(food).build());
        ix.flush();
        return ix;
    }

    @Test
    void keelMetaFilters() {
        assumeNative();
        try (var ix = openKeelCorpus(d)) {
            SearchOptions k5 = SearchOptions.k(5);
            assertEquals(List.of("d1", "d5", "d6"), sortedKeys(ix.searchText("牛肉", k5)));
            assertEquals(List.of("d5", "d6"), sortedKeys(ix.searchText("牛肉", SearchOptions.k(5).filter(Filter.eq("kind", "food")))));
            assertTrue(ix.searchText("牛肉", SearchOptions.k(5).filter(Filter.notEq("kind", "food"))).isEmpty());
            assertEquals(List.of("d5", "d6"), sortedKeys(ix.searchText("牛肉", SearchOptions.k(5).filter(Filter.in("kind", "food", "drink")))));
            assertEquals(List.of("d5", "d6"), sortedKeys(ix.searchText("牛肉", SearchOptions.k(5).filter(Filter.gte("n", 3)))));
            assertEquals(List.of("d5", "d6"), sortedKeys(ix.searchText("牛肉",
                    SearchOptions.k(5).filter(Filter.or(Filter.eq("kind", "food"), Filter.and(Filter.gte("n", 3)))))));
            // d2 的 meta 是裸 JSON：存得进、读得回，但过滤不认
            assertTrue(ix.searchText("番茄", SearchOptions.k(5).filter(Filter.exists("kind"))).isEmpty());
            assertEquals(List.of("d6"), keys(ix.searchVector(f(1, 1, 0), SearchOptions.k(5).filter(Filter.eq("kind", "food")))));
            assertEquals(List.of("d5", "d6"), sortedKeys(ix.searchHybrid("牛肉", f(1, 1, 0), SearchOptions.k(5).filter(Filter.eq("kind", "food")))));
            Filter reused = Filter.eq("kind", "food");
            assertEquals(sortedKeys(ix.searchText("牛肉", SearchOptions.k(5).filter(reused))), sortedKeys(ix.searchText("牛肉", SearchOptions.k(5).filter(reused))));
        }
    }

    @Test
    void stringArrayContains() {
        assumeNative();
        try (var ix = BitcaskIndex.open(d, whitespace())) {
            ix.putDocument("a", Document.builder().text("apple").meta(Map.of("tags", List.of("red", "sweet"))).build());
            ix.putDocument("b", Document.builder().text("apple").meta(Map.of("tags", "red")).build());
            ix.putDocument("c", Document.builder().text("apple").meta(Map.of("tags", List.of("green"))).build());
            ix.flush();
            assertEquals(List.of("a", "b"), sortedKeys(ix.searchText("apple", fo(Filter.contains("tags", "red")))));
            assertEquals(List.of("a", "c"), sortedKeys(ix.searchText("apple", fo(Filter.containsAny("tags", "sweet", "green")))));
            assertEquals(List.of("b"), sortedKeys(ix.searchText("apple", fo(Filter.eq("tags", "red")))), "既有运算碰到数组按类型不匹配");
        }
    }

    @Test
    void inNeedsCandidates() {
        assertEquals("bitcask/filter-in-empty", code(() -> Filter.validate(Filter.in("k", List.of()))));
        assertEquals("bitcask/filter-in-empty", code(() -> Filter.validate(Filter.containsAny("k", List.of()))));
        assertEquals("bitcask/filter-invalid", code(() -> Filter.validate(Filter.eq("k", Map.of("a", 1)))));
        assertEquals("bitcask/filter-invalid", code(() -> Filter.validate(new Filter.Condition(Filter.Op.CONTAINS, "k", 1, null))));
    }
}
