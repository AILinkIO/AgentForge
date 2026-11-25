package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.BatchOp.check;
import static io.ailink.agentforge.bitcask.BatchOp.delete;
import static io.ailink.agentforge.bitcask.BatchOp.put;
import static io.ailink.agentforge.bitcask.Condition.absent;
import static io.ailink.agentforge.bitcask.Condition.ord;
import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.s;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** libbitcask 8.0.0（S46）原子批前置条件。期望值对照 libbitcask tests/c_api_test.c test_conditional_batch。 */
class CondBatchTest {
    @TempDir
    Path d;
    Bitcask db;

    @BeforeEach
    void open() {
        assumeNative();
        db = Bitcask.open(d);
    }

    @AfterEach
    void close() {
        if (db != null) db.close();
    }

    @Test
    void getWithOrd() {
        assertNull(db.getWithOrd("k"));
        db.put("k", "v1");
        var v1 = db.getWithOrd("k");
        assertEquals("v1", v1.valueString());
        db.put("k", "v2");
        var v2 = db.getWithOrd("k");
        assertEquals("v2", v2.valueString());
        assertTrue(Long.compareUnsigned(v2.ord(), v1.ord()) > 0);
        db.delete("k");
        assertNull(db.getWithOrd("k"));
        db.put(new byte[]{0, 1, 2}, new byte[0]);
        var e = db.getWithOrd(new byte[]{0, 1, 2});
        assertEquals(0, e.value().length);
        assertTrue(e.ord() > 0);
        long o1 = db.tryWriteAtomic(List.of(put("z", "zz"))).ords()[0];
        var z = db.getWithOrd("z");
        assertEquals("zz", z.valueString());
        assertEquals(o1, z.ord());
    }

    @Test
    void ordConditionAndZeroSideEffects() {
        db.put("a", "1");
        long oa = db.getWithOrd("a").ord();
        db.writeAtomic(List.of(put("a", "2", ord(oa)), put("b", "x")));
        assertEquals("2", db.getString("a"));
        var e = thrown(() -> db.writeAtomic(List.of(put("c", "no"), put("a", "3", ord(oa)))));
        assertEquals("bitcask/condition-failed", e.code());
        assertEquals(1, e.failedOp());
        assertEquals(BitcaskError.CONDITION_FAILED, e.error());
        assertEquals("2", db.getString("a"));
        assertNull(db.get("c"));
        long oa2 = db.getWithOrd("a").ord();
        assertEquals(0, db.tryWriteAtomic(List.of(delete("a", ord(oa)))).conflict());
        assertTrue(db.tryWriteAtomic(List.of(delete("a", ord(oa2)))).isSuccess());
        assertNull(db.get("a"));
    }

    @Test
    void absentCheckAndPreBatchState() {
        db.writeAtomic(List.of(put("k", "first", absent())));
        assertEquals(0, db.tryWriteAtomic(List.of(put("k", "second", absent()))).conflict());
        assertEquals("first", db.getString("k"));
        long ok = db.getWithOrd("k").ord();
        BatchResult r = db.tryWriteAtomic(List.of(check("k", ord(ok)), check("none", absent()), put("other", "o")));
        assertEquals(3, r.ords().length);
        assertEquals(0, r.ords()[0], "check 没有落盘记录");
        assertNull(db.get("none"));
        assertEquals(0, db.tryWriteAtomic(List.of(check("k", absent()), put("other", "p"))).conflict());
        assertEquals("o", db.getString("other"));
        // 批内同 key 两次：第二个条件仍对照批前（不存在）
        db.writeAtomic(List.of(put("n", "1", absent()), put("n", "2", absent())));
        assertEquals("2", db.getString("n"));
    }

    @Test
    void returnedOrdsChain() {
        db.put("c", "0");
        long o0 = db.getWithOrd("c").ord();
        long o1 = db.tryWriteAtomic(List.of(put("c", "1", ord(o0)))).ords()[0];
        assertEquals(o1, db.getWithOrd("c").ord());
        assertTrue(db.tryWriteAtomic(List.of(put("c", "2", ord(o1)))).isSuccess());
        assertEquals(0, db.tryWriteAtomic(List.of(put("c", "3", ord(o1)))).conflict());
        BatchResult empty = db.tryWriteAtomic(List.of());
        assertTrue(empty.isSuccess());
        assertEquals(0, empty.ords().length);
    }

    @Test
    void commitPassesConditions() {
        db.put("a", "1");
        long oa = db.getWithOrd("a").ord();
        db.put("a", "2");
        assertEquals("bitcask/condition-failed", code(() -> db.commit(List.of(put("b", "x"), put("a", "3", ord(oa))), false)));
        assertNull(db.get("b"));
        long oa2 = db.getWithOrd("a").ord();
        db.commit(List.of(put("a", "3", ord(oa2)), check("c", absent())), false);
        assertEquals("3", db.getString("a"));
    }

    @Test
    void badShapes() {
        assertEquals("bitcask/batch-cond-invalid", code(() -> check("k", null)));
        assertEquals("bitcask/put-batch-put-only", code(() -> db.writeBatch(List.of(put("k", "v", absent())))));
        assertNull(db.get("k"));
    }

    @Test
    void concurrentCasCounterIsExact() throws Exception {
        db.put("ctr", new byte[8]);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        while (true) {
                            var cur = db.getWithOrd("ctr");
                            long n = ByteBuffer.wrap(cur.value()).getLong();
                            byte[] nv = ByteBuffer.allocate(8).putLong(n + 1).array();
                            if (db.tryWriteAtomic(List.of(put("ctr".getBytes(), nv, ord(cur.ord())))).isSuccess()) break;
                        }
                    }
                }));
            }
            for (var f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        assertEquals(800, ByteBuffer.wrap(db.get("ctr")).getLong());
    }

    // ═══ 小批走每线程暂存区：门槛两侧语义一致 ═══

    private static List<BatchOp> ops(int n, String tag) {
        List<BatchOp> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(put(tag + "-" + i, "v" + i));
        return out;
    }

    @Test
    void smallAndLargeBatchesAgree() {
        // 16 条（暂存区）与 17 条（arena）都整批生效、回各自的 ord
        for (Object[] c : new Object[][]{{16, "s"}, {17, "l"}}) {
            int n = (int) c[0];
            String tag = (String) c[1];
            long[] ords = db.tryWriteAtomic(ops(n, tag)).ords();
            assertEquals(n, ords.length);
            for (long o : ords) assertTrue(o > 0);
            assertEquals("v3", db.getString(tag + "-3"));
        }
        // 冲突下标与零副作用（两条路径各一次）
        for (Object[] c : new Object[][]{{4, "s2"}, {20, "l2"}}) {
            int n = (int) c[0];
            String tag = (String) c[1];
            List<BatchOp> batch = new ArrayList<>(ops(n - 1, tag));
            batch.add(put("s-0", "x", absent()));
            assertEquals(n - 1, db.tryWriteAtomic(batch).conflict());
            assertNull(db.get(tag + "-0"));
        }
        // 只含 check / remove / 空 value
        BatchResult r = db.tryWriteAtomic(List.of(check("s-1", ord(db.getWithOrd("s-1").ord())), check("nope", absent())));
        assertArrayEquals(new long[]{0, 0}, r.ords());
        assertEquals(0, db.tryWriteAtomic(List.of(check("s-1", absent()))).conflict());
        db.tryWriteAtomic(List.of(delete("s-1"), put("e", new byte[0])));
        assertNull(db.get("s-1"));
        assertEquals(0, db.get("e").length);
        // 字节门槛 64 KiB：恰好 / 多 1 字节
        for (int extra : new int[]{0, 1}) {
            String k = "big";
            byte[] v = new byte[64 * 1024 - k.length() + extra];
            db.tryWriteAtomic(List.of(put(k, v), put("z", "1")));
            assertEquals(v.length, db.get(k).length);
        }
        db.tryWriteAtomic(List.of(put("after", "ok")));
        assertEquals("ok", db.getString("after"));
    }

    @Test
    void smallBatchesConcurrentCas() throws Exception {
        db.put("n", "0");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                fs.add(pool.submit(() -> {
                    for (int i = 0; i < 400; i++) {
                        while (true) {
                            var cur = db.getWithOrd("n");
                            long n = Long.parseLong(s(cur.value()));
                            if (db.tryWriteAtomic(List.of(put("n", String.valueOf(n + 1), ord(cur.ord())), check("zz", absent()))).isSuccess()) break;
                        }
                    }
                }));
            }
            for (var f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        assertEquals("3200", db.getString("n"));
    }
}
