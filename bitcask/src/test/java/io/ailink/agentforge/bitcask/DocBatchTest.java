package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.BatchOp.delete;
import static io.ailink.agentforge.bitcask.BatchOp.put;
import static io.ailink.agentforge.bitcask.BatchOp.putDoc;
import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.f;
import static io.ailink.agentforge.bitcask.TestSupport.keys;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 带文档的原子批 / 事务（bitcask_put_batch_atomic_ex / bitcask_txn_commit_ex）。期望值移植自 libbitcask tests/atomic_batch_doc_test.cpp。 */
class DocBatchTest {
    @TempDir
    Path d;

    static final float[] VEC4 = f(3, 4, 0, 0);

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    static BitcaskIndex open(Path d) {
        return BitcaskIndex.open(d, TestSupport.whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.COSINE));
    }

    static byte[] color(String c) {
        return Meta.encode(Map.of("c", c));
    }

    static Filter colorFilter(String c) {
        return Filter.eq("c", c);
    }

    @Test
    void docInBatchMatchesPutDocAndIsIndexed() throws IOException {
        Document doc = Document.builder().text("apple pie").meta(color("red")).vector(VEC4).field("title", "crumble").build();
        try (var ix = open(d)) {
            ix.putDocument("ref", doc);
            ix.writeAtomic(List.of(putDoc("doc1", doc), put("ix/c/red/doc1", "")));
            try (Record a = ix.readRecord("doc1"); Record b = ix.readRecord("ref")) {
                assertArrayEquals(b.value(), a.value());
                assertArrayEquals(b.meta(), a.meta());
                assertArrayEquals(b.vector(), a.vector());
                assertEquals(4, a.vector().length);
                assertTrue(Math.abs(0.6 - a.vector()[0]) < 1e-6);
            }
        }
        for (int round = 0; round < 2; round++) {
            if (round == 1) CrashTest.dropDerived(d, true); // 第二轮：data fold 全量重建
            try (var ix = open(d)) {
                ix.flush();
                assertEquals(2, ix.searchText("apple", SearchOptions.defaults().filter(colorFilter("red"))).size());
                assertEquals(2, ix.searchFields("title:crumble").size());
                assertEquals(2, ix.searchVector(VEC4, SearchOptions.defaults().filter(colorFilter("red"))).size());
                assertTrue(ix.containsKey(u("ix/c/red/doc1")));
            }
        }
    }

    @Test
    void metaChangeWithSecondaryIndexInOneBatch() {
        try (var ix = open(d)) {
            ix.writeAtomic(List.of(putDoc("pk", Document.builder().text("apple").meta(color("red")).build()), put("ix/c/red/pk", "")));
            ix.writeAtomic(List.of(putDoc("pk", Document.builder().text("apple").meta(color("blue")).build()), put("ix/c/blue/pk", ""),
                    delete("ix/c/red/pk")));
            assertFalse(ix.containsKey("ix/c/red/pk"));
            assertTrue(ix.containsKey("ix/c/blue/pk"));
            try (Record g = ix.readRecord("pk")) {
                assertArrayEquals(color("blue"), g.meta());
            }
            ix.flush();
            assertTrue(ix.searchText("apple", SearchOptions.defaults().filter(colorFilter("red"))).isEmpty());
            assertEquals(List.of("pk"), keys(ix.searchText("apple", SearchOptions.defaults().filter(colorFilter("blue")))));
        }
    }

    private static void copyTree(Path from, Path to, boolean skipLocks) throws IOException {
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (Path f : s.toList()) {
                String n = f.getFileName().toString();
                if (Files.isDirectory(f)) copyTree(f, to.resolve(n), false);
                else if (!(skipLocks && n.contains("lock"))) Files.copy(f, to.resolve(n));
            }
        }
    }

    @Test
    void tornBatchDropsDocAndIndexEntryTogether() throws IOException {
        Path live = d.resolve("db"), crash = d.resolve("crash");
        try (var ix = open(live)) {
            ix.put("pre", "kept");
            // 索引项排前、文档排末：掐尾 1 字节断的是文档成员
            ix.writeAtomic(List.of(put("ix/c/red/pk", ""), putDoc("pk", Document.builder().text("apple").meta(color("red")).vector(VEC4).build())));
            ix.sync();
            // 崩溃时刻的盘面 = 库仍打开时的目录内容（不经 close）；锁文件不拷
            copyTree(live, crash, true);
        }
        CrashTest.dropDerived(crash, true);
        Path data = CrashTest.maxDataFile(crash);
        CrashTest.truncate(data, Files.size(data) - 1);
        try (var c = open(crash)) {
            assertEquals("kept", c.getString("pre"));
            assertNull(c.getString("pk"));
            assertNull(c.getString("ix/c/red/pk"));
            c.flush();
            assertTrue(c.searchText("apple").isEmpty());
            assertTrue(c.searchVector(VEC4).isEmpty());
        }
    }

    @Test
    void validationFailuresHaveNoSideEffects() throws IOException {
        try (var ix = open(d)) {
            ix.put("seed", "x");
            Document named = Document.builder().text("apple").field("never_interned", "zzz").build();
            Document wrong = Document.builder().text("x").vector(f(1, 2, 3)).build(); // 维度错，排在带新字段名的文档之后
            var e = thrown(() -> ix.writeAtomic(List.of(putDoc("a", named), putDoc("b", wrong))));
            assertEquals(BitcaskError.INVALID_OPTION, e.error());
            assertEquals("bitcask_put_batch_atomic_ex", e.operation());
            assertFalse(ix.containsKey("a"));
            assertFalse(ix.containsKey("b"));
            assertEquals("bitcask/put-batch-put-only", code(() -> ix.writeBatch(List.of(putDoc("a", named)))));
        }
        Path schema = d.resolve("field.schema");
        if (Files.exists(schema)) assertFalse(Files.readString(schema, java.nio.charset.StandardCharsets.ISO_8859_1).contains("never_interned"));
    }

    @Test
    void expiryAndTxnCommit() {
        try (var ix = open(d)) {
            ix.commit(List.of(putDoc("old", Document.builder().text("gone").expiresAt(Instant.ofEpochSecond(1)).build()),
                    putDoc("pk", Document.builder().text("apple").meta(color("red")).build()), put("ix/c/red/pk", "")));
            assertNull(ix.getString("old"));
            try (Record g = ix.readRecord("pk")) {
                assertArrayEquals(color("red"), g.meta());
            }
            assertTrue(ix.containsKey("ix/c/red/pk"));
            var e = thrown(() -> ix.commit(List.of(putDoc("z", Document.of("a")), putDoc("z", Document.of("b")))));
            assertEquals(BitcaskError.INVALID_OPTION, e.error());
            assertEquals("bitcask_txn_commit_ex", e.operation());
            assertNull(ix.getString("z"));
        }
    }
}
