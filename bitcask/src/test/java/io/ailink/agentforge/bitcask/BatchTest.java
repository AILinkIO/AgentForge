package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.BatchOp.delete;
import static io.ailink.agentforge.bitcask.BatchOp.put;
import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 批 / 原子批 / 事务。期望值移植自 libbitcask tests/c_api_test.c（test_put_batch / test_txn）。 */
class BatchTest {
    @TempDir
    Path d;

    @BeforeEach
    void needNative() {
        assumeNative();
    }

    @Test
    void putBatchWritesAllItems() {
        try (var kv = Bitcask.open(d)) {
            kv.writeBatch(List.of(put("bk0", "v0"), put("bk1", "v1"), put("bk2", "v2")));
            for (int i = 0; i < 3; i++) assertEquals("v" + i, kv.getString("bk" + i));
            kv.writeBatch(List.of());
        }
    }

    @Test
    void putBatchRejectsRemoves() {
        try (var kv = Bitcask.open(d)) {
            assertEquals("bitcask/put-batch-put-only", code(() -> kv.writeBatch(List.of(put("a", "1"), delete("b")))));
            assertNull(kv.getString("a"));
        }
    }

    @Test
    void commitAppliesPutsAndRemoves() {
        try (var kv = Bitcask.open(d)) {
            kv.put("txn_victim", "old");
            kv.commit(List.of(put("txn_a", "va"), put("txn_b", "vb"), delete("txn_victim")), true);
            assertEquals("va", kv.getString("txn_a"));
            assertEquals("vb", kv.getString("txn_b"));
            assertNull(kv.getString("txn_victim"));
        }
    }

    @Test
    void commitValidationIsSideEffectFree() {
        try (var kv = Bitcask.open(d)) {
            // 空批 / 重复 key / "_txn:" 前缀 → INVALID_OPTION
            var empty = thrown(() -> kv.commit(List.of()));
            assertEquals(BitcaskError.INVALID_OPTION, empty.error());
            assertEquals("bitcask/invalid-option", empty.code());
            var dup = thrown(() -> kv.commit(List.of(put("same", "1"), delete("same"))));
            assertEquals(BitcaskError.INVALID_OPTION, dup.error());
            assertEquals("bitcask_txn_commit", dup.operation());
            assertEquals(BitcaskError.INVALID_OPTION, thrown(() -> kv.commit(List.of(put("ok", "1"), put("_txn:x", "v")))).error());
            assertNull(kv.getString("ok"));
            assertTrue(kv.isEmpty());
        }
    }

    @Test
    void atomicBatchIsLastWriterWinsWithinBatch() {
        try (var kv = Bitcask.open(d)) {
            kv.writeAtomic(List.of(put("raw", "first"), put("raw", "second")));
            assertEquals("second", kv.getString("raw"));
            kv.writeAtomic(List.of());
        }
    }

    @Test
    void atomicBatchSurvivesReopen() {
        try (var kv = Bitcask.open(d)) {
            kv.put("pre", "x");
            List<BatchOp> ops = new ArrayList<>();
            for (int i = 0; i < 50; i++) ops.add(put(String.format("a%02d", i), "v" + i));
            ops.add(delete("pre"));
            kv.writeAtomic(ops);
            kv.commit(List.of(put("c1", "1"), put("c2", "2")), false);
        }
        try (var kv = Bitcask.open(d)) {
            for (int i = 0; i < 50; i++) assertEquals("v" + i, kv.getString(String.format("a%02d", i)));
            assertNull(kv.getString("pre"));
            assertEquals("1", kv.getString("c1"));
            assertEquals("2", kv.getString("c2"));
            assertEquals(52, kv.status().keyCount());
        }
    }

    @Test
    void batchesArePlainData() {
        try (var kv = Bitcask.open(d)) {
            List<BatchOp> batch = List.of(put("a", "1"));
            kv.writeAtomic(batch);
            kv.writeAtomic(List.of(put("b", "2"), delete("a")));
            assertNull(kv.getString("a"));
            assertEquals("2", kv.getString("b"));
        }
    }
}
