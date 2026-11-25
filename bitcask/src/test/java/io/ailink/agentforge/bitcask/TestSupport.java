package io.ailink.agentforge.bitcask;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.function.Executable;

/** 测试共用：原生库不在就跳过（不是失败）、字节小工具、取错误码。 */
final class TestSupport {
    static {
        // 开发机上的 jieba 词典：libbitcask 源码树里 cppjieba 自带的那份（库在 <src>/build-*/libbitcask.so*）
        String lib = System.getProperty("agentforge.bitcask.lib");
        if (lib != null && System.getProperty(BitcaskRuntime.JIEBA_DIR_PROPERTY) == null) {
            Path p = Path.of(lib).toAbsolutePath().getParent();
            Path dict = p == null || p.getParent() == null ? null : p.getParent().resolve("third_party/cppjieba/dict");
            if (dict != null && Files.isDirectory(dict)) System.setProperty(BitcaskRuntime.JIEBA_DIR_PROPERTY, dict.toString());
        }
    }

    private TestSupport() {
    }

    static boolean nativeAvailable() {
        return BitcaskRuntime.isAvailable();
    }

    /** 原生库不在 ⇒ 跳过（打印理由）。 */
    static void assumeNative() {
        Assumptions.assumeTrue(nativeAvailable(), () -> "SKIP: libbitcask 不在（-Pbitcask.lib=…）：" + BitcaskRuntime.unavailableReason());
    }

    static byte[] u(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    static String s(MemorySegment m) {
        return new String(m.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
    }

    static BitcaskException thrown(Executable body) {
        return assertThrows(BitcaskException.class, body);
    }

    static String code(Executable body) {
        return thrown(body).code();
    }

    static List<String> keys(List<? extends Object> hits) {
        return hits.stream().map(TestSupport::keyOf).toList();
    }

    static List<String> sortedKeys(List<? extends Object> hits) {
        return hits.stream().map(TestSupport::keyOf).sorted().toList();
    }

    private static String keyOf(Object h) {
        return switch (h) {
            case SearchHit x -> x.key();
            case HighlightHit x -> x.key();
            case FieldsHit x -> x.key();
            default -> throw new IllegalArgumentException(String.valueOf(h));
        };
    }

    static List<String> entryKeys(List<Entry> es) {
        return es.stream().map(Entry::keyString).toList();
    }

    static float[] f(double... v) {
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) v[i];
        return out;
    }

    static Options whitespace() {
        return Options.defaults().analyzer(Options.Analyzer.WHITESPACE);
    }
}
