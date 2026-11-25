package io.ailink.agentforge.bitcask;

import static io.ailink.agentforge.bitcask.TestSupport.assumeNative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.ailink.agentforge.bitcask.ffm.BitcaskLibrary;

/** 原生库可用性、jieba 词典诊断（keel tests/bitcask.ss §J）与 setThreadLimits（libbitcask c_api_test.c test_thread_limits_and_tuning；子 JVM）。 */
class RuntimeDiagnosticsTest {
    @TempDir
    Path d;

    @Test
    void unavailableReasonAgreesWithAvailable() {
        String reason = BitcaskRuntime.unavailableReason();
        assertEquals(BitcaskRuntime.isAvailable(), reason == null);
        if (reason != null) assertTrue(reason.contains(BitcaskLibrary.rid()), reason);
    }

    @Test
    void availableNativeHasLocation() {
        assumeNative();
        assertNull(BitcaskRuntime.unavailableReason());
        assertTrue(Files.isRegularFile(BitcaskRuntime.libraryLocation()));
        assertFalse(BitcaskLibrary.searched().isEmpty());
    }

    @Test
    void jiebaStatusMatchesRoots() {
        List<String> roots = BitcaskRuntime.jiebaRoots();
        var status = BitcaskRuntime.jiebaStatus();
        String def = BitcaskRuntime.defaultJiebaDictPath();
        assertTrue(roots.stream().allMatch(r -> Path.of(r).isAbsolute()));
        assertEquals(roots.size(), roots.stream().distinct().count());
        assertEquals(roots, status.stream().map(BitcaskRuntime.JiebaRoot::path).toList());
        assertEquals(BitcaskRuntime.isJiebaAvailable(), status.stream().anyMatch(BitcaskRuntime.JiebaRoot::complete));
        if (def == null) assertTrue(status.stream().noneMatch(BitcaskRuntime.JiebaRoot::complete));
        else assertEquals(def, status.stream().filter(BitcaskRuntime.JiebaRoot::complete).findFirst().orElseThrow().path());
    }

    @Test
    void explicitJiebaDirectoryComesFirst() throws IOException {
        Path dict = d.resolve("dict");
        Files.createDirectories(dict);
        for (String f : BitcaskRuntime.JIEBA_DICT_FILES) Files.writeString(dict.resolve(f), "");
        String old = System.getProperty(BitcaskRuntime.JIEBA_DIR_PROPERTY);
        try {
            BitcaskRuntime.setJiebaDirectory(dict.toString());
            assertEquals(dict.toRealPath(), Path.of(BitcaskRuntime.jiebaRoots().getFirst()).toRealPath());
            assertEquals(dict.toRealPath(), Path.of(BitcaskRuntime.defaultJiebaDictPath()).toRealPath());
        } finally {
            BitcaskRuntime.setJiebaDirectory(old);
        }
        String def = BitcaskRuntime.defaultJiebaDictPath();
        assertNotEquals(dict.toRealPath().toString(), def == null ? null : Path.of(def).toRealPath().toString());
    }

    @Test
    void jiebaRootsIncludeLoadedLibraryDirectory() {
        assumeNative();
        Path lib = BitcaskRuntime.libraryLocation().getParent();
        assertTrue(BitcaskRuntime.jiebaRoots().contains(lib.resolve("jieba").toAbsolutePath().normalize().toString()));
    }

    @Test
    void jiebaCompleteMeansAllFiveFiles() throws IOException {
        assertEquals(BitcaskRuntime.JIEBA_DICT_FILES, BitcaskRuntime.missingJiebaDictFiles(d.toString()));
        for (String f : BitcaskRuntime.JIEBA_DICT_FILES.subList(1, 5)) Files.writeString(d.resolve(f), "");
        assertEquals(List.of(BitcaskRuntime.JIEBA_DICT_FILES.getFirst()), BitcaskRuntime.missingJiebaDictFiles(d.toString()));
        Files.writeString(d.resolve(BitcaskRuntime.JIEBA_DICT_FILES.getFirst()), "");
        assertTrue(BitcaskRuntime.missingJiebaDictFiles(d.toString()).isEmpty());
    }

    @Test
    void threadLimitsAndTuningInChildProcess() throws IOException, InterruptedException {
        assumeNative();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8"));
        for (String p : new String[]{BitcaskLibrary.LIB_PROPERTY, BitcaskLibrary.NATIVE_DIR_PROPERTY, BitcaskRuntime.JIEBA_DIR_PROPERTY}) {
            String v = System.getProperty(p);
            if (v != null) cmd.add("-D" + p + "=" + v);
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), ThreadLimitsChild.class.getName(), d.resolve("db").toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "子进程输出：\n" + out);
        assertTrue(out.contains("OK"), out);
    }
}
