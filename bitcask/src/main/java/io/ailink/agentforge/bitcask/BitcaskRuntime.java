package io.ailink.agentforge.bitcask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import io.ailink.agentforge.bitcask.ffm.BitcaskLibrary;
import io.ailink.agentforge.bitcask.ffm.BitcaskNative;
import io.ailink.agentforge.bitcask.ffm.Faults;
import io.ailink.agentforge.bitcask.ffm.NativeArena;
import io.ailink.agentforge.bitcask.ffm.NativeTypes;

/**
 * 进程级：原生库可用性 / 位置 / 版本、线程上限、jieba 词典定位。原生库的查找顺序见 {@link BitcaskLibrary}。
 */
public final class BitcaskRuntime {
    /** 显式 jieba 词典目录的系统属性（宿主自己铺词典时设一次，不必每次开库给 {@link Options#jiebaDictPath}）。 */
    public static final String JIEBA_DIR_PROPERTY = "agentforge.bitcask.jieba.dir";
    /** 随原生库分发的 jieba 词典相对库目录（或 {@code agentforge.native.dir}）的位置。 */
    public static final String JIEBA_RELATIVE_PATH = "jieba";
    /** jieba 分析器打开时必须在场的词典文件（缺一个 cppjieba 就 abort 整个进程）。 */
    public static final List<String> JIEBA_DICT_FILES = BitcaskLibrary.JIEBA_FILES;

    /** libbitcask 版本。 */
    public record Version(int major, int minor, int patch) {
        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    /** 一条 jieba 候选目录齐不齐（齐 = 五个文件都在）。 */
    public record JiebaRoot(String path, boolean complete) {
    }

    private BitcaskRuntime() {
    }

    /** 原生库装得上 ⇒ null；否则是理由（渲染好的文案；不抛）。 */
    public static String unavailableReason() {
        try {
            RuntimeException e = BitcaskLibrary.unavailableReason();
            return e == null ? null : String.valueOf(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** 原生库能否装上（不抛）。 */
    public static boolean isAvailable() {
        return unavailableReason() == null;
    }

    /** 装载用的那份库文件（定位不到 ⇒ null）；装不上 ⇒ 抛。 */
    public static Path libraryLocation() {
        return BitcaskLibrary.location();
    }

    public static Version version() {
        return new Version(BitcaskNative.versionMajor(), BitcaskNative.versionMinor(), BitcaskNative.versionPatch());
    }

    /** 版本串 "major.minor.patch"。 */
    public static String versionString() {
        return NativeTypes.cString(BitcaskNative.versionString());
    }

    /**
     * 进程级线程上限（{@code bitcask_set_thread_limits}；0 = 缺省 hardware_concurrency）。首个检索库开库即冻结：
     * 之后值不同 ⇒ {@code bitcask/invalid-option} fault，值相同 ⇒ 幂等。
     */
    public static void setThreadLimits(long indexWorkers, long searchSlots) {
        if (indexWorkers < 0) throw new IllegalArgumentException("indexWorkers");
        if (searchSlots < 0) throw new IllegalArgumentException("searchSlots");
        try (var a = new NativeArena()) {
            var f = a.fault();
            Faults.check(BitcaskNative.setThreadLimits(indexWorkers, searchSlots, f), f, "bitcask_set_thread_limits");
        }
    }

    /** 设显式 jieba 词典目录（= 系统属性，排在 {@link #jiebaRoots()} 第一位）；null = 清掉。只影响之后打开的检索库。 */
    public static void setJiebaDirectory(String dir) {
        if (dir == null) System.clearProperty(JIEBA_DIR_PROPERTY);
        else System.setProperty(JIEBA_DIR_PROPERTY, dir);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String abs(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    /** 实际装载的库所在目录；装不上 / 定位不到 ⇒ null（不抛）。 */
    private static Path libraryDirectory() {
        try {
            Path p = BitcaskLibrary.location();
            return p == null ? null : p.getParent();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 会去找缺省 jieba 词典的目录，按顺序：显式目录（系统属性 {@value #JIEBA_DIR_PROPERTY}，原样）、
     * {@code <agentforge.native.dir>/jieba}、{@code <实际装载的库所在目录>/jieba}（classpath 内嵌的库连词典一起解在这里）；
     * 绝对路径、去重。
     */
    public static List<String> jiebaRoots() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String explicit = blankToNull(System.getProperty(JIEBA_DIR_PROPERTY));
        if (explicit != null) out.add(abs(Path.of(explicit)));
        String nativeDir = blankToNull(System.getProperty(BitcaskLibrary.NATIVE_DIR_PROPERTY));
        if (nativeDir != null) out.add(abs(Path.of(nativeDir).resolve(JIEBA_RELATIVE_PATH)));
        Path lib = libraryDirectory();
        if (lib != null) out.add(abs(lib.resolve(JIEBA_RELATIVE_PATH)));
        return List.copyOf(out);
    }

    /** 目录里缺的词典文件（按 {@link #JIEBA_DICT_FILES} 的顺序）。 */
    public static List<String> missingJiebaDictFiles(String dir) {
        List<String> out = new ArrayList<>();
        for (String f : JIEBA_DICT_FILES) if (!Files.isRegularFile(Path.of(dir, f))) out.add(f);
        return out;
    }

    /** {@link #jiebaRoots()} 每一条齐不齐（同序同长）。 */
    public static List<JiebaRoot> jiebaStatus() {
        return jiebaRoots().stream().map(r -> new JiebaRoot(r, missingJiebaDictFiles(r).isEmpty())).toList();
    }

    /** {@link #jiebaRoots()} 里第一个齐全的；都不齐 ⇒ null。 */
    public static String defaultJiebaDictPath() {
        for (String r : jiebaRoots()) if (missingJiebaDictFiles(r).isEmpty()) return r;
        return null;
    }

    public static boolean isJiebaAvailable() {
        return defaultJiebaDictPath() != null;
    }
}
