package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static io.ailink.agentforge.bitcask.TestSupport.code;
import static io.ailink.agentforge.bitcask.TestSupport.thrown;
import static io.ailink.agentforge.bitcask.TestSupport.u;
import static io.ailink.agentforge.bitcask.TestSupport.whitespace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** fault ⇒ BitcaskException 的映射（错误码、errno、详情、入口名）。 */
class ErrorTest {
    @TempDir
    Path d;

    @Test
    void writeOnReadOnlyHandleMapsToReadOnly() {
        assumeNative();
        try (var kv = Bitcask.open(d)) {
            kv.put("a", "1");
        }
        try (var ro = Bitcask.open(d, Options.defaults().readOnly(true))) {
            var e = thrown(() -> ro.put("b", "2"));
            assertEquals(BitcaskError.READ_ONLY, e.error());
            assertEquals("bitcask_put", e.operation());
            assertEquals("bitcask/read-only", e.code());
            assertTrue(e.isNative());
        }
    }

    @Test
    void openOnARegularFileFailsWithFault() throws IOException {
        assumeNative();
        Path f = d.resolve("not-a-dir");
        Files.writeString(f, "x");
        var e = thrown(() -> Bitcask.open(f));
        assertEquals("bitcask_open", e.operation());
        assertNotEquals(BitcaskError.OK, e.error());
    }

    @Test
    void readOnlyRangeUsesIndexBuiltByWriter() {
        assumeNative();
        try (var kv = Bitcask.open(d)) {
            kv.put("a", "1");
        }
        try (var ro = Bitcask.open(d, Options.defaults().readOnly(true)); Cursor c = ro.rangeCursor()) {
            assertTrue(c.moveNext());
            assertEquals("a", c.keyString());
            assertFalse(c.moveNext());
        }
    }

    @Test
    void useAfterCloseThrowsDisposed() {
        assumeNative();
        var kv = Bitcask.open(d);
        kv.close();
        assertEquals("bitcask/disposed", code(() -> kv.get(u("a"))));
        kv.close();
        assertTrue(kv.isClosed());
    }

    @Test
    void vectorEngineSwitchIsModeMismatchAndBadComboIsInvalidOption() {
        assumeNative();
        Path d1 = d.resolve("a"), d2 = d.resolve("b");
        BitcaskIndex.open(d1, whitespace().vectorDimension(4).vectorMetric(Options.VectorMetric.COSINE)).close();
        // 引擎建库时持久化，重开不一致 ⇒ MODE_MISMATCH
        assertEquals(BitcaskError.MODE_MISMATCH, thrown(() -> BitcaskIndex.open(d1, whitespace().vectorDimension(4)
                .vectorMetric(Options.VectorMetric.COSINE).vectorEngine(Options.VectorEngine.IVF_RQ))).error());
        // 磁盘档引擎只收 COSINE / DOT：IVF_RQ + L2 ⇒ INVALID_OPTION
        assertEquals(BitcaskError.INVALID_OPTION, thrown(() -> BitcaskIndex.open(d2, whitespace().vectorDimension(4)
                .vectorMetric(Options.VectorMetric.L2).vectorEngine(Options.VectorEngine.IVF_RQ))).error());
    }

    @Test
    void optionsAreCheckedBeforeTouchingDisk() {
        Path dir = d.resolve("never");
        assertEquals("bitcask/options-invalid", code(() -> Options.defaults().syncEveryN(-1)));
        assertEquals("bitcask/options-invalid", code(() -> Options.defaults().ngramMin(0)));
        assumeNative();
        assertEquals("bitcask/vector-dimension-range", code(() -> BitcaskIndex.open(dir, Options.defaults().vectorDimension(70000))));
        assertFalse(Files.exists(dir));
    }
}
