package io.ailink.agentforge.bitcask.ffm;

import static io.ailink.agentforge.bitcask.ffm.NativeTypes.off;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 结构体布局与 C ABI 逐字节一致。期望值是 C 侧实测（gcc，x86_64 LP64）；不需要原生库在场。
 * 另加一格：c_api 头与 gcc 都在时，现编一个打印 sizeof / offsetof 的小程序、逐项比对（头不在 / 没有 gcc ⇒ 跳过）。
 * 头的位置：系统属性 / 环境变量 {@code AGENTFORGE_BITCASK_CAPI}，缺省按库路径推（{@code <libbitcask 源码>/build-*}/libbitcask.so ⇒ {@code <源码>/c_api}）。
 */
class LayoutTest {
    @BeforeEach
    void abi64() {
        Assumptions.assumeTrue(ValueLayout.ADDRESS.byteSize() == 8, "期望值按 64 位 ABI 实测");
    }

    static long size(MemoryLayout l) {
        return l.byteSize();
    }

    @Test
    void sizesMatchCAbi() {
        assertEquals(184, size(NativeTypes.OPTIONS));
        assertEquals(520, size(NativeTypes.FAULT));
        assertEquals(16, size(NativeTypes.SLICE));
        assertEquals(64, size(NativeTypes.GET_RESULT));
        assertEquals(24, size(NativeTypes.SEARCH_HIT));
        assertEquals(16, size(NativeTypes.SEARCH_RESULT));
        assertEquals(72, size(NativeTypes.ITER_ENTRY));
        assertEquals(48, size(NativeTypes.RANGE_OPTIONS));
        assertEquals(48, size(NativeTypes.RANGE_ENTRY));
        assertEquals(56, size(NativeTypes.STATUS_EX));
        assertEquals(32, size(NativeTypes.KV_PAIR));
        assertEquals(40, size(NativeTypes.TXN_OP));
        assertEquals(32, size(NativeTypes.DOC_FIELD));
        assertEquals(72, size(NativeTypes.DOC_INPUT_EX));
        assertEquals(32, size(NativeTypes.HIGHLIGHT_OPTIONS));
        assertEquals(40, size(NativeTypes.SEARCH_HIT_EX));
        assertEquals(16, size(NativeTypes.SNIPPET));
        assertEquals(32, size(NativeTypes.META_VALUE));
        assertEquals(64, size(NativeTypes.META_CONDITION));
        assertEquals(40, size(NativeTypes.META_FILTER));
        assertEquals(40, size(NativeTypes.META_ENTRY));
        assertEquals(40, size(NativeTypes.META_VALUE_VIEW));
        assertEquals(16, size(NativeTypes.META_ITER));
        assertEquals(88, size(NativeTypes.ITER_ENTRY_EX));
        assertEquals(64, size(NativeTypes.RANGE_ENTRY_EX));
        assertEquals(48, size(NativeTypes.TXN_OP_EX));
        assertEquals(56, size(NativeTypes.COND_OP));
        assertEquals(24, size(NativeTypes.HYBRID_QUERY));
        assertEquals(56, size(NativeTypes.MERGE_POLICY));
        assertEquals(16, size(NativeTypes.OPEN_TUNING));
        assertEquals(24, size(NativeTypes.NEEDS_MERGE));
        assertEquals(80, size(NativeTypes.META_INFO));
    }

    @Test
    void offsetsMatchCAbi() {
        assertEquals(8, off(NativeTypes.MERGE_POLICY, "dead_bytes_merge_trigger"));
        assertEquals(16, off(NativeTypes.MERGE_POLICY, "deletion_rate_trigger"));
        assertEquals(48, off(NativeTypes.MERGE_POLICY, "max_merge_size"));
        assertEquals(8, off(NativeTypes.OPEN_TUNING, "segment_verify_crc"));
        assertEquals(16, off(NativeTypes.NEEDS_MERGE, "files_count"));
        assertEquals(24, off(NativeTypes.META_VALUE, "str"));
        assertEquals(48, off(NativeTypes.META_CONDITION, "values"));
        assertEquals(32, off(NativeTypes.META_FILTER, "children_count"));
        assertEquals(72, off(NativeTypes.ITER_ENTRY_EX, "meta"));
        assertEquals(48, off(NativeTypes.RANGE_ENTRY_EX, "meta"));
        assertEquals(40, off(NativeTypes.TXN_OP_EX, "doc"));
        assertEquals(1, off(NativeTypes.COND_OP, "expect"));
        assertEquals(8, off(NativeTypes.COND_OP, "expect_ord"));
        assertEquals(48, off(NativeTypes.COND_OP, "doc"));
        assertEquals(16, off(NativeTypes.HYBRID_QUERY, "vector_len"));
        assertEquals(40, off(NativeTypes.OPTIONS, "tombstone_version"));
        assertEquals(44, off(NativeTypes.OPTIONS, "enable_search"));
        assertEquals(64, off(NativeTypes.OPTIONS, "jieba_dict_path"));
        assertEquals(96, off(NativeTypes.OPTIONS, "synonym_file_path"));
        assertEquals(104, off(NativeTypes.OPTIONS, "vector_dim"));
        assertEquals(120, off(NativeTypes.OPTIONS, "vector_engine"));
        assertEquals(160, off(NativeTypes.OPTIONS, "log_fn"));
        assertEquals(176, off(NativeTypes.OPTIONS, "keydir_cache_entries"));
        assertEquals(48, off(NativeTypes.GET_RESULT, "tstamp"));
        assertEquals(64, off(NativeTypes.ITER_ENTRY, "ord"));
        assertEquals(56, off(NativeTypes.DOC_INPUT_EX, "fields"));
    }

    static final StructLayout[] STRUCTS = {NativeTypes.SLICE, NativeTypes.FAULT, NativeTypes.OPTIONS, NativeTypes.GET_RESULT,
            NativeTypes.GET_DOC_RESULT, NativeTypes.DOC_FIELD_RESULT, NativeTypes.SEARCH_HIT, NativeTypes.SEARCH_RESULT, NativeTypes.SNIPPET,
            NativeTypes.SEARCH_HIT_EX, NativeTypes.SEARCH_RESULT_EX, NativeTypes.SEARCH_HIT_FIELDS, NativeTypes.SEARCH_FIELDS_RESULT,
            NativeTypes.HIGHLIGHT_OPTIONS, NativeTypes.ITER_ENTRY, NativeTypes.RANGE_OPTIONS, NativeTypes.RANGE_ENTRY, NativeTypes.ITER_ENTRY_EX,
            NativeTypes.RANGE_ENTRY_EX, NativeTypes.ITER_ENTRY_VIEW, NativeTypes.STATUS_EX, NativeTypes.KV_PAIR, NativeTypes.TXN_OP,
            NativeTypes.DOC_FIELD, NativeTypes.DOC_INPUT_EX, NativeTypes.META_VALUE, NativeTypes.META_CONDITION, NativeTypes.META_FILTER,
            NativeTypes.META_ENTRY, NativeTypes.META_ENTRY_EX, NativeTypes.META_VALUE_VIEW, NativeTypes.META_ITER, NativeTypes.TXN_OP_EX,
            NativeTypes.COND_OP, NativeTypes.HYBRID_QUERY, NativeTypes.MERGE_POLICY, NativeTypes.OPEN_TUNING, NativeTypes.NEEDS_MERGE,
            NativeTypes.META_INFO};

    static Path capiDir() {
        String explicit = System.getProperty("AGENTFORGE_BITCASK_CAPI", System.getenv("AGENTFORGE_BITCASK_CAPI"));
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        String lib = System.getProperty(BitcaskLibrary.LIB_PROPERTY, System.getenv(BitcaskLibrary.LIB_ENV));
        if (lib == null) return null;
        Path build = Path.of(lib).toAbsolutePath().getParent();
        return build == null || build.getParent() == null ? null : build.getParent().resolve("c_api");
    }

    static boolean gcc() {
        try {
            return new ProcessBuilder("gcc", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test
    void everyFieldMatchesCompiledHeader() throws IOException, InterruptedException {
        Path capi = capiDir();
        Assumptions.assumeTrue(capi != null && Files.isRegularFile(capi.resolve("bitcask_kv.h")) && gcc(), "c_api 头不在或没有 gcc：" + capi);
        Path tmp = Files.createTempDirectory("agentforge-bitcask-layout");
        try {
            List<String> expected = new ArrayList<>();
            StringBuilder src = new StringBuilder("#include <stdio.h>\n#include <stddef.h>\n#include \"bitcask_kv.h\"\n#include \"bitcask_vec.h\"\n"
                    + "#include \"bitcask_text.h\"\nint main(void){\n");
            for (StructLayout l : STRUCTS) {
                String c = l.name().orElseThrow();
                src.append(String.format("printf(\"%%s %%zu\\n\", \"%s\", sizeof(%s));%n", c, c));
                expected.add(c + " " + l.byteSize());
                for (MemoryLayout m : l.memberLayouts()) {
                    if (m.name().isEmpty()) continue;
                    String f = m.name().get();
                    src.append(String.format("printf(\"%%s.%%s %%zu\\n\", \"%s\", \"%s\", offsetof(%s, %s));%n", c, f, c, f));
                    expected.add(c + "." + f + " " + off(l, f));
                }
            }
            src.append("return 0;}\n");
            Files.writeString(tmp.resolve("layout.c"), src);
            run(tmp, "gcc", "-I", capi.toString(), "-o", "layout", "layout.c");
            List<String> actual = run(tmp, tmp.resolve("layout").toString()).strip().lines().toList();
            List<String> diff = new ArrayList<>();
            for (int i = 0; i < Math.min(expected.size(), actual.size()); i++)
                if (!expected.get(i).equals(actual.get(i))) diff.add("Java " + expected.get(i) + " ≠ C " + actual.get(i));
            assertEquals(expected.size(), actual.size());
            assertEquals(List.of(), diff);
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }
}
