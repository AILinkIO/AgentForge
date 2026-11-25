package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * merge 策略 / 显式文件 merge / needs-merge / close-write-file / frozen。期望值移植自 libbitcask tests/c_api_test.c
 * （test_status_and_merge、test_merge_policy_files_checkpoint）与 keel tests/bitcask.ss（§C0–§C5）。
 */
class MergeTest {
    @TempDir
    Path d;

    static final byte[] VALUE_256 = filled();

    private static byte[] filled() {
        byte[] b = new byte[256];
        Arrays.fill(b, (byte) 'x');
        return b;
    }

    static void writeRounds(Bitcask kv, int rounds, String prefix) {
        for (int r = 0; r < rounds; r++) for (int i = 0; i < 200; i++) kv.put(u(prefix + i), VALUE_256);
    }

    static List<Path> dataFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".bitcask.data"))
                    .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().split("\\.")[0]))).toList();
        }
    }

    static String canonical(String f) throws IOException {
        return Path.of(f).toRealPath().toString();
    }

    @Test
    void defaultPolicyEqualsUpstreamDefaults() {
        MergePolicy p = MergePolicy.DEFAULT;
        assertEquals(60, p.fragmentationTrigger());
        assertEquals(512L * 1024 * 1024, p.deadBytesTrigger());
        assertEquals(0, p.deletionRateTrigger());
        assertEquals(40, p.fragmentationThreshold());
        assertEquals(128L * 1024 * 1024, p.deadBytesThreshold());
        assertEquals(10L * 1024 * 1024, p.smallFileThreshold());
        assertEquals(0, p.expirySeconds());
        assertEquals(0, p.expiryGraceSeconds());
        assertEquals(0, p.maxMergeSize());
        assumeNative();
        assertEquals(MergePolicy.upstreamDefaults(), MergePolicy.DEFAULT);
    }

    @Test
    void percentOutOfRangeIsRejectedBeforeTouchingDisk() {
        assumeNative();
        MergePolicy[] bad = {MergePolicy.DEFAULT.withFragmentationTrigger(101), MergePolicy.DEFAULT.withFragmentationThreshold(-1),
                MergePolicy.DEFAULT.withDeletionRateTrigger(101)};
        for (int i = 0; i < bad.length; i++) {
            Path dir = d.resolve("p" + i);
            MergePolicy p = bad[i];
            assertEquals("bitcask/merge-policy-percent", code(() -> Bitcask.open(dir, Options.defaults().mergePolicy(p))));
            assertFalse(Files.exists(dir));
        }
    }

    @Test
    void tightPolicyWithRollingFilesMergesExplicitCandidates() throws IOException {
        assumeNative();
        Options opts = Options.defaults().maxFileSize(8L * 1024)
                .mergePolicy(MergePolicy.DEFAULT.withFragmentationTrigger(30).withFragmentationThreshold(20));
        try (var kv = Bitcask.open(d, opts)) {
            writeRounds(kv, 2, "mp_");
            assertTrue(dataFiles(d).size() > 1);
            // 名字不是 data 文件 ⇒ INVALID_OPTION，报文含 "not a data file"
            var e = thrown(() -> kv.merge(List.of(d.resolve("not-a-data-file.txt").toString())));
            assertEquals(BitcaskError.INVALID_OPTION, e.error());
            assertTrue(e.detail().contains("not a data file"), e.detail());
            // needs-merge：needs 且有候选；还没 merge 过的库上 id 最大 = active，不在候选里
            MergeAdvice nm = kv.needsMerge();
            String active = canonical(dataFiles(d).getLast().toString());
            assertTrue(nm.needed());
            assertFalse(nm.files().isEmpty());
            for (String f : nm.files()) assertFalse(canonical(f).equals(active));
            var ea = thrown(() -> kv.merge(List.of(active)));
            assertEquals(BitcaskError.INVALID_OPTION, ea.error());
            assertTrue(ea.detail().contains("active write file"), ea.detail());
            String first = nm.files().getFirst();
            kv.merge(List.of(first));
            for (int i = 0; i < 200; i++) assertArrayEquals(VALUE_256, kv.get(u("mp_" + i)));
            // 退休 ≠ 删除：merge 后输入文件还在，checkpoint 之后没了
            assertTrue(Files.exists(Path.of(first)));
            kv.checkpoint();
            assertFalse(Files.exists(Path.of(first)));
            assertEquals("bitcask/merge-files-empty", code(() -> kv.merge(List.of())));
            kv.merge();
        }
        // 只读句柄：checkpoint ⇒ READ_ONLY
        try (var ro = Bitcask.open(d, Options.defaults().readOnly(true))) {
            assertEquals(BitcaskError.READ_ONLY, thrown(ro::checkpoint).error());
        }
    }

    @Test
    void defaultFileSizeKeepsOneFileAndNeedsMergeSaysNo() throws IOException {
        assumeNative();
        MergePolicy[] policies = {null, MergePolicy.DEFAULT};
        for (int i = 0; i < policies.length; i++) {
            Path dir = d.resolve("p" + i);
            try (var kv = Bitcask.open(dir, Options.defaults().mergePolicy(policies[i]))) {
                writeRounds(kv, 2, "c/");
                assertEquals(1, dataFiles(dir).size());
                assertEquals(new MergeAdvice(false, List.of()), kv.needsMerge());
                kv.closeWriteFile();
                kv.put("after", "close");
                assertEquals("close", kv.getString("after"));
                assertEquals(2, dataFiles(dir).size());
                assertArrayEquals(VALUE_256, kv.get(u("c/7")));
            }
        }
    }

    @Test
    void partialPolicyFillsInDefaults() {
        assumeNative();
        try (var kv = Bitcask.open(d, Options.defaults().maxFileSize(8192L).mergePolicy(MergePolicy.DEFAULT.withFragmentationTrigger(40)))) {
            writeRounds(kv, 2, "c/");
            MergeAdvice nm = kv.needsMerge();
            assertTrue(nm.needed());
            assertFalse(nm.files().isEmpty());
        }
    }

    @Test
    void openIteratorAllowsConcurrentWrite() {
        assumeNative();
        try (var kv = Bitcask.open(d)) {
            kv.put("p/a", "1");
            kv.put("p/b", "2");
            assertFalse(kv.isFrozen());
            try (Cursor it = kv.scanCursor(ScanOptions.prefix("p/"))) {
                kv.put("fz", "9");
                assertFalse(kv.isFrozen());
                assertEquals("9", kv.getString("fz"));
                assertTrue(it.moveNext());
            }
            assertEquals("9", kv.getString("fz"));
        }
    }
}
