package io.ailink.agentforge.bitcask.ffm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import io.ailink.agentforge.bitcask.BitcaskException;

/**
 * libbitcask 的装载（进程内只装一次，留在全局 arena、不卸载）。查找顺序：
 * <ol>
 * <li>系统属性 {@value #LIB_PROPERTY}（库文件完整路径）；</li>
 * <li>环境变量 {@value #LIB_ENV}（同上）；</li>
 * <li>系统属性 {@value #NATIVE_DIR_PROPERTY}（目录，其下 {@code System.mapLibraryName("bitcask")}）；</li>
 * <li>classpath 资源 {@code /native/<rid>/<库文件名>}（rid 如 {@code linux-x64} / {@code osx-arm64} / {@code win-x64}）：
 *     解到按内容指纹复用的缓存目录（系统属性 {@value #CACHE_PROPERTY}，缺省 {@code <java.io.tmpdir>/agentforge-native-<用户>}），
 *     同目录资源 {@code /native/<rid>/jieba/*} 一并解到库旁边的 {@code jieba/}；</li>
 * <li>操作系统的库搜索路径（{@code SymbolLookup.libraryLookup}；Linux 上再试带 soname 主版本的 {@code libbitcask.so.13}）。</li>
 * </ol>
 * 前三条给了就只在那里找（不退回别处的同名库，免得悄悄用错版本）。装不上 ⇒ {@code bitcask/no-library}（列出找过的位置与原因）。
 */
public final class BitcaskLibrary {
    public static final String LIBRARY = "bitcask";
    /** libbitcask 的 soname 主版本（OS 搜索路径里只有带版本号的文件时用）。 */
    public static final int SONAME_MAJOR = 13;
    public static final String LIB_PROPERTY = "agentforge.bitcask.lib";
    public static final String LIB_ENV = "AGENTFORGE_BITCASK_LIB";
    public static final String NATIVE_DIR_PROPERTY = "agentforge.native.dir";
    public static final String CACHE_PROPERTY = "agentforge.native.cache";
    /** classpath 里与库同目录、随库一起解出来的 jieba 词典文件。 */
    public static final List<String> JIEBA_FILES = List.of("jieba.dict.utf8", "hmm_model.utf8", "user.dict.utf8", "idf.utf8", "stop_words.utf8");
    public static final Linker LINKER = Linker.nativeLinker();

    private record Loaded(SymbolLookup lookup, Path location, RuntimeException failure, List<String> searched) {
    }

    private static volatile Loaded loaded;

    private BitcaskLibrary() {
    }

    /** 装得上 ⇒ null；否则是原因（{@code bitcask/no-library} / {@code bitcask/missing-symbol} 等）。不抛。 */
    public static RuntimeException unavailableReason() {
        try {
            Loaded l = load();
            if (l.failure != null) return l.failure;
            if (l.lookup.find("bitcask_version_major").isEmpty()) return BitcaskException.of("bitcask/missing-symbol", "bitcask_version_major");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    /** 装载后的符号表；装不上 ⇒ 抛（调用方先看 {@link #unavailableReason()}）。 */
    public static SymbolLookup lookup() {
        Loaded l = load();
        if (l.failure != null) throw l.failure;
        return l.lookup;
    }

    /** 实际装载的库文件（OS 搜索路径装上而定位不到 ⇒ null）。装不上 ⇒ 抛。 */
    public static Path location() {
        Loaded l = load();
        if (l.failure != null) throw l.failure;
        return l.location;
    }

    /** 找过的位置（诊断用）。 */
    public static List<String> searched() {
        return load().searched;
    }

    /** 当前平台的 rid（{@code linux-x64} …）。 */
    public static String rid() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String o = os.startsWith("windows") ? "win" : os.startsWith("mac") || os.contains("darwin") ? "osx"
                : os.contains("freebsd") ? "freebsd" : os.contains("linux") ? "linux" : os.replace(' ', '_');
        String a = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
        return o + "-" + a;
    }

    private static Loaded load() {
        Loaded l = loaded;
        if (l != null) return l;
        synchronized (BitcaskLibrary.class) {
            if (loaded == null) loaded = doLoad();
            return loaded;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Loaded doLoad() {
        List<String> searched = new ArrayList<>();
        String file = System.mapLibraryName(LIBRARY);
        String explicit = blankToNull(System.getProperty(LIB_PROPERTY));
        String source = "-D" + LIB_PROPERTY;
        if (explicit == null) {
            explicit = blankToNull(System.getenv(LIB_ENV));
            source = LIB_ENV;
        }
        if (explicit == null) {
            String dir = blankToNull(System.getProperty(NATIVE_DIR_PROPERTY));
            if (dir != null) {
                explicit = Path.of(dir).resolve(file).toString();
                source = "-D" + NATIVE_DIR_PROPERTY;
            }
        }
        if (explicit != null) return loadPath(Path.of(explicit).toAbsolutePath(), source, searched);

        String resource = "/native/" + rid() + "/" + file;
        searched.add("classpath:" + resource);
        try (InputStream in = BitcaskLibrary.class.getResourceAsStream(resource)) {
            if (in != null) return loadPath(extract(in.readAllBytes(), file), "classpath", searched);
        } catch (IOException | RuntimeException e) {
            return new Loaded(null, null, noLibrary(searched, e), List.copyOf(searched));
        }

        List<String> names = new ArrayList<>(List.of(file));
        if (file.endsWith(".so")) names.add(file + "." + SONAME_MAJOR);
        RuntimeException last = null;
        for (String name : names) {
            searched.add("os:" + name);
            try {
                SymbolLookup lookup = SymbolLookup.libraryLookup(name, Arena.global());
                return new Loaded(lookup, locateLoaded(), null, List.copyOf(searched));
            } catch (RuntimeException e) {
                last = e;
            }
        }
        return new Loaded(null, null, noLibrary(searched, last), List.copyOf(searched));
    }

    private static Loaded loadPath(Path p, String source, List<String> searched) {
        searched.add(source + ":" + p);
        try {
            return new Loaded(SymbolLookup.libraryLookup(p, Arena.global()), p, null, List.copyOf(searched));
        } catch (RuntimeException e) {
            return new Loaded(null, null, noLibrary(searched, e), List.copyOf(searched));
        }
    }

    private static BitcaskException noLibrary(List<String> searched, Throwable cause) {
        String why = String.join(", ", searched) + (cause == null || cause.getMessage() == null ? "" : " — " + cause.getMessage());
        return BitcaskException.of("bitcask/no-library", rid(), why);
    }

    /** classpath 里的库 ⇒ {@code <缓存>/bitcask-<rid>-<指纹>/<文件名>}（已有 ⇒ 复用）；同目录的 jieba 词典一并解出。 */
    private static Path extract(byte[] bytes, String file) throws IOException {
        String cache = blankToNull(System.getProperty(CACHE_PROPERTY));
        Path root = cache != null ? Path.of(cache)
                : Path.of(System.getProperty("java.io.tmpdir"), "agentforge-native-" + System.getProperty("user.name", "user"));
        Path dir = root.resolve(LIBRARY + "-" + rid() + "-" + fingerprint(bytes));
        Files.createDirectories(dir);
        Path target = dir.resolve(file);
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) writeAtomically(target, bytes);
        for (String f : JIEBA_FILES) {
            try (InputStream in = BitcaskLibrary.class.getResourceAsStream("/native/" + rid() + "/jieba/" + f)) {
                if (in == null) continue;
                Path jt = dir.resolve("jieba").resolve(f);
                Files.createDirectories(jt.getParent());
                if (!Files.isRegularFile(jt)) writeAtomically(jt, in.readAllBytes());
            }
        }
        return target;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String fingerprint(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    /** OS 搜索路径装上的库在哪（Linux：读 /proc/self/maps；别的平台 ⇒ null）。 */
    private static Path locateLoaded() {
        Path maps = Path.of("/proc/self/maps");
        if (!Files.isReadable(maps)) return null;
        try (var lines = Files.lines(maps)) {
            return lines.map(s -> {
                int i = s.indexOf('/');
                return i < 0 ? null : s.substring(i);
            }).filter(s -> s != null && Path.of(s).getFileName().toString().startsWith("lib" + LIBRARY + "."))
                    .findFirst().map(Path::of).orElse(null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static MethodHandle required(SymbolLookup lib, String name, FunctionDescriptor fd) {
        return LINKER.downcallHandle(lib.findOrThrow(name), fd);
    }

    /** invokeExact 的 Throwable ⇒ unchecked。 */
    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }
}
