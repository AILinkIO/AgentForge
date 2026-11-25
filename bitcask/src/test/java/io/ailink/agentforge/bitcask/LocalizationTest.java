package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.ffm.NativeHandle;

class LocalizationTest {
    @TempDir
    Path d;

    static Set<String> placeholders(String s) {
        Matcher m = Pattern.compile("\\{(\\d+)[^}]*}").matcher(s);
        return m.results().map(r -> r.group(1)).collect(Collectors.toSet());
    }

    @Test
    void resourcesAreConsistent() {
        // 英文覆盖全部键、占位符一致
        Map<String, String> zh = Messages.table(""), en = Messages.table("_en");
        assertTrue(zh.size() > 50);
        assertEquals(zh.keySet(), en.keySet());
        for (var e : zh.entrySet()) {
            assertTrue(e.getKey().startsWith("bitcask/"), e.getKey());
            assertEquals(placeholders(e.getValue()), placeholders(en.get(e.getKey())), e.getKey());
        }
    }

    @Test
    void nativeFaultInEnglishKeepsCode() {
        var zh = Messages.withLocale(Locale.CHINESE, () -> BitcaskException.fault(7, 0, null, "bitcask_put"));
        assertEquals("bitcask/read-only", zh.code());
        assertEquals("bitcask_put：存储是只读的", zh.getMessage());
        var en = Messages.withLocale(Locale.ENGLISH, () -> BitcaskException.fault(1, 5, "disk", "bitcask_sync"));
        assertEquals("bitcask/io", en.code());
        assertEquals("bitcask_sync: I/O error (errno 5): disk", en.getMessage());
        assertEquals(BitcaskError.IO, en.error());
        assertEquals(5, en.errno());
        assertEquals("disk", en.detail());
        assertEquals("bitcask_sync", en.operation());
        assertEquals("bitcask/fault", BitcaskException.fault(99, 0, null, "x").code());
    }

    @Test
    void managedChecksInEnglishKeepCode() {
        assumeNative();
        try (var kv = Bitcask.open(d)) {
            var e = Messages.withLocale(Locale.ENGLISH, () -> {
                try {
                    kv.writeBatch(List.of(BatchOp.put("a", "1"), BatchOp.delete("b")));
                    return null;
                } catch (BitcaskException x) {
                    return x;
                }
            });
            assertEquals("bitcask/put-batch-put-only", e.code());
            assertTrue(e.getMessage().startsWith("bitcask_put_batch only takes puts"), e.getMessage());
        }
    }

    @Test
    void disposedIsLocalized() {
        var e = Messages.withLocale(Locale.ENGLISH, () -> NativeHandle.disposed("BitcaskKv"));
        assertEquals("bitcask/disposed", e.code());
        assertTrue(e.getMessage().startsWith("BitcaskKv has been closed"), e.getMessage());
        assertEquals("no/such-key", Messages.text("no/such-key"));
    }
}
