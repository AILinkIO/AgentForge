package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.ailink.agentforge.sandbox.SandboxException;

import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 沙盒原生件 {@code libagentforge_sandbox_native.so} 的定位、装载与下行调用（FFM）。原生件是 C 写的（源码在本模块 native/，`:sandbox:buildNative` 编出），由宿主放到可找到的位置；
 * 子进程经 {@code LD_PRELOAD} 装载它，它的构造函数在 exec 之后、main 之前读 {@code KS_SANDBOX_POLICY} 套 Landlock + seccomp，
 * 并为 {@code :proxied} 网络在沙盒 netns 里接代理桥。JVM 自己不设 {@code KS_SANDBOX_POLICY} ⇒ 装进 JVM 时什么都不做。
 * <p>定位次序：{@link #setExplicitDirectory} → 系统属性 {@value #DIRECTORY_PROPERTY} → 环境变量 {@code AGENTFORGE_SANDBOX_NATIVE_DIR}
 * → {@code java.library.path} 各目录。找不到 ⇒ {@link #path()} 回 {@code null}，探测报不可用。
 */
public final class SandboxNative {
    private SandboxNative() {
    }

    /** 原生件目录的系统属性。 */
    public static final String DIRECTORY_PROPERTY = "agentforge.sandbox.native.dir";

    /** 库名（⇒ {@code libagentforge_sandbox_native.so}；与原生件的约定，勿改）。 */
    public static final String LIBRARY = "agentforge_sandbox_native";

    private static final Object GATE = new Object();
    private static String explicitDirectory;
    private static String path;
    private static boolean resolved;
    private static Handles handles;

    /** 宿主显式给的原生件目录（{@code null} ⇒ 按缺省次序找）。定位过之后再换 ⇒ {@code sandbox/landlock-directory-locked}。 */
    public static void setExplicitDirectory(String directory) {
        synchronized (GATE) {
            if (resolved && !Objects.equals(explicitDirectory, directory))
                throw SandboxException.ofNative("sandbox/landlock-directory-locked");
            explicitDirectory = directory;
        }
    }

    /** 原生件的全路径（找不到 ⇒ {@code null}）。LD_PRELOAD 要的就是它。 */
    public static String path() {
        synchronized (GATE) {
            if (!resolved) {
                path = locate();
                resolved = true;
            }
            return path;
        }
    }

    private static String locate() {
        String file = System.mapLibraryName(LIBRARY);
        List<String> dirs = new ArrayList<>();
        if (explicitDirectory != null) {
            dirs.add(explicitDirectory);
        } else {
            String prop = System.getProperty(DIRECTORY_PROPERTY);
            if (prop != null && !prop.isBlank()) dirs.add(prop);
            String env = System.getenv("AGENTFORGE_SANDBOX_NATIVE_DIR");
            if (env != null && !env.isBlank()) dirs.add(env);
            String lib = System.getProperty("java.library.path", "");
            for (String d : lib.split(File.pathSeparator)) if (!d.isBlank()) dirs.add(d);
        }
        for (String d : dirs) {
            Path p = Path.of(d).toAbsolutePath().normalize().resolve(file);
            if (Files.isRegularFile(p)) return p.toString();
        }
        return null;
    }

    private record Handles(MethodHandle probe, MethodHandle apply, MethodHandle writeSeccomp, MethodHandle bridgeReceiveListener) {
    }

    /** 装入原生件（缓存句柄）；找不到 ⇒ {@code sandbox/landlock-native-missing}；dlopen 失败照 JDK 抛 {@link IllegalArgumentException}。 */
    private static Handles handles() {
        synchronized (GATE) {
            if (handles != null) return handles;
            String p = path();
            if (p == null) throw SandboxException.ofNative("sandbox/landlock-native-missing");
            SymbolLookup lib = SymbolLookup.libraryLookup(Path.of(p), Arena.global());
            var l = Libc.LINKER;
            handles = new Handles(
                    l.downcallHandle(lib.findOrThrow("ks_probe"), FunctionDescriptor.of(JAVA_INT)),
                    l.downcallHandle(lib.findOrThrow("ks_apply"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG)),
                    l.downcallHandle(lib.findOrThrow("ks_write_seccomp"), FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT)),
                    l.downcallHandle(lib.findOrThrow("ks_bridge_receive_listener"), FunctionDescriptor.of(JAVA_INT, JAVA_INT)));
            return handles;
        }
    }

    /** Landlock 的 ABI 版本（≥ 1 可用；0 = 内核不支持）。 */
    public static int probe() {
        try {
            return (int) handles().probe().invokeExact();
        } catch (Throwable t) {
            throw Libc.rethrow(t);
        }
    }

    /** 对当前进程套用策略（Landlock + seccomp）。0 成功，负数是 -errno。⚠ 套在 JVM 自己身上，不可撤销。 */
    public static int apply(byte[] blob) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment b = arena.allocate(Math.max(1, blob.length));
            MemorySegment.copy(blob, 0, b, JAVA_BYTE, 0, blob.length);
            return (int) handles().apply().invokeExact(b, (long) blob.length);
        } catch (Throwable t) {
            throw Libc.rethrow(t);
        }
    }

    /** 把 seccomp 过滤器写到 fd（给 bwrap {@code --seccomp} 用）。 */
    public static int writeSeccomp(int fd, int mode) {
        try {
            return (int) handles().writeSeccomp().invokeExact(fd, mode);
        } catch (Throwable t) {
            throw Libc.rethrow(t);
        }
    }

    /** 从控制连接接收沙盒 netns 里的回环监听 fd（SCM_RIGHTS）。非负是 fd，负数是 -errno。 */
    public static int bridgeReceiveListener(int fd) {
        try {
            return (int) handles().bridgeReceiveListener().invokeExact(fd);
        } catch (Throwable t) {
            throw Libc.rethrow(t);
        }
    }
}
