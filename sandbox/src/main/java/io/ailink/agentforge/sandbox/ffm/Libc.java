package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * bwrap 后端、进程加固与代理桥用到的 libc 调用（FFM；C# 的 {@code [LibraryImport("libc")]}）。只在 Unix 上调；
 * 符号缺失（Windows、或 macOS 上的 Linux 专有调用）⇒ 句柄为 {@code null}，调用时按失败处理。带 errno 的调用经 {@code captureCallState("errno")}。
 * 不渲染文案：失败回 errno（负数或 {@code {rc, errno}}），由上层（{@code io.ailink.agentforge.sandbox.linux.LinuxFs}）解释。
 */
public final class Libc {
    private Libc() {
    }

    private static final int AT_FDCWD = -100;
    private static final int AT_SYMLINK_NOFOLLOW = 0x100;
    private static final int AT_EACCESS = 0x200;
    private static final int STATX_TYPE = 0x1;
    private static final int STATX_MODE = 0x2;
    private static final int STATX_UID = 0x8;
    public static final int W_OK = 2;
    public static final int X_OK = 1;
    public static final int EACCES = 13;
    public static final int EROFS = 30;
    public static final int EINTR = 4;
    public static final int ENOSYS = 38;
    public static final int SHUT_WR = 1;
    public static final int SHUT_RDWR = 2;
    public static final int SIGINT = 2;
    private static final int AF_UNIX = 1;
    private static final int SOCK_STREAM = 1;

    public static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option ERRNO_OPT = Linker.Option.captureCallState("errno");
    private static final SymbolLookup LIBC = lookup();

    private static final MethodHandle STATX = opt("statx", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), ERRNO_OPT);
    private static final MethodHandle REALPATH = opt("realpath", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle FREE = opt("free", FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle GETEUID = opt("geteuid", FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle FACCESSAT = opt("faccessat", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), ERRNO_OPT);
    // int prctl(int option, ...)：第 1 个之后是变参
    private static final MethodHandle PRCTL = opt("prctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG),
            Linker.Option.firstVariadicArg(1), ERRNO_OPT);
    private static final MethodHandle SETRLIMIT = opt("setrlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), ERRNO_OPT);
    private static final MethodHandle GETRLIMIT = opt("getrlimit", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS), ERRNO_OPT);
    // macOS：int ptrace(int request, pid_t pid, caddr_t addr, int data)
    private static final MethodHandle PTRACE = opt("ptrace", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPT);
    // 代理桥（AF_UNIX 控制面、收到的监听 fd、转发）
    private static final MethodHandle SOCKET = opt("socket", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPT);
    private static final MethodHandle BIND = opt("bind", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), ERRNO_OPT);
    private static final MethodHandle LISTEN = opt("listen", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPT);
    private static final MethodHandle ACCEPT = opt("accept", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), ERRNO_OPT);
    private static final MethodHandle READ = opt("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPT);
    private static final MethodHandle WRITE = opt("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), ERRNO_OPT);
    private static final MethodHandle SHUTDOWN = opt("shutdown", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle CLOSE = opt("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle KILL = opt("kill", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), ERRNO_OPT);

    private static SymbolLookup lookup() {
        // Windows 上没有 libc：给空查找 ⇒ 全部句柄为 null
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")
                ? name -> java.util.Optional.empty() : LINKER.defaultLookup();
    }

    private static MethodHandle opt(String name, FunctionDescriptor fd, Linker.Option... options) {
        return LIBC.find(name).map(s -> LINKER.downcallHandle(s, fd, options)).orElse(null);
    }

    public static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    private static int errno(MemorySegment cs) {
        return (int) ERRNO.get(cs, 0L);
    }

    /** {@code statx}；{@code followSymlinks} false ⇒ lstat 语义。回 {mode, uid}；不存在 / 失败 ⇒ {@code null}。 */
    public static int[] stat(String path, boolean followSymlinks) {
        if (STATX == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256, 8);
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) STATX.invokeExact(cs, AT_FDCWD, arena.allocateFrom(path), followSymlinks ? 0 : AT_SYMLINK_NOFOLLOW,
                    STATX_TYPE | STATX_MODE | STATX_UID, buf);
            if (rc != 0) return null;
            // struct statx：stx_uid@20、stx_mode@28（u16）
            return new int[] {Short.toUnsignedInt(buf.get(JAVA_SHORT, 28)), buf.get(JAVA_INT, 20)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code realpath}：全解符号链接；不存在 ⇒ {@code null}。 */
    public static String realPath(String path) {
        if (REALPATH == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) REALPATH.invokeExact(arena.allocateFrom(path), MemorySegment.NULL);
            if (p.equals(MemorySegment.NULL)) return null;
            try {
                return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
            } finally {
                FREE.invokeExact(p);
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static int effectiveUid() {
        if (GETEUID == null) return -1;
        try {
            return (int) GETEUID.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code faccessat(AT_FDCWD, path, mode, AT_EACCESS)}：0 ⇒ 可；否则回 errno。 */
    public static int accessEffective(String path, int mode) {
        if (FACCESSAT == null) return EACCES;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) FACCESSAT.invokeExact(cs, AT_FDCWD, arena.allocateFrom(path), mode, AT_EACCESS);
            return rc == 0 ? 0 : errno(cs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 回 {rc, errno}。 */
    public static long[] prctl(int option, long a2, long a3, long a4, long a5) {
        if (PRCTL == null) return new long[] {-1, ENOSYS};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) PRCTL.invokeExact(cs, option, a2, a3, a4, a5);
            return new long[] {rc, errno(cs)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code struct rlimit { rlim_t cur, max; }}（64 位）；回 {rc, errno}。 */
    public static long[] setrlimit(int resource, long current, long maximum) {
        if (SETRLIMIT == null) return new long[] {-1, ENOSYS};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lim = arena.allocate(16, 8);
            lim.set(JAVA_LONG, 0, current);
            lim.set(JAVA_LONG, 8, maximum);
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) SETRLIMIT.invokeExact(cs, resource, lim);
            return new long[] {rc, errno(cs)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 失败 ⇒ {@code null}；否则 {cur, max}（无符号解释）。 */
    public static long[] getrlimit(int resource) {
        if (GETRLIMIT == null) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment lim = arena.allocate(16, 8);
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) GETRLIMIT.invokeExact(cs, resource, lim);
            return rc == 0 ? new long[] {lim.get(JAVA_LONG, 0), lim.get(JAVA_LONG, 8)} : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 回 {rc, errno}。 */
    public static long[] ptrace(int request, int pid, int data) {
        if (PTRACE == null) return new long[] {-1, ENOSYS};
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) PTRACE.invokeExact(cs, request, pid, MemorySegment.NULL, data);
            return new long[] {rc, errno(cs)};
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ── 套接字（代理桥） ────────────────────────────────────────────────────────────

    /** 建 AF_UNIX 流套接字、绑到 {@code path}；失败回 -errno。 */
    public static int unixListenSocket(String path) {
        if (SOCKET == null || BIND == null) return -ENOSYS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int fd = (int) SOCKET.invokeExact(cs, AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) return -errno(cs);
            byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
            // struct sockaddr_un { sa_family_t sun_family; char sun_path[108]; }
            MemorySegment addr = arena.allocate(110, 2);
            if (bytes.length >= 108) {
                close(fd);
                return -36; // ENAMETOOLONG
            }
            addr.set(JAVA_SHORT, 0, (short) AF_UNIX);
            MemorySegment.copy(bytes, 0, addr, JAVA_BYTE, 2, bytes.length);
            int rc = (int) BIND.invokeExact(cs, fd, addr, 110);
            if (rc != 0) {
                int e = errno(cs);
                close(fd);
                return -e;
            }
            return fd;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 0 成功；失败回 -errno。 */
    public static int listen(int fd, int backlog) {
        if (LISTEN == null) return -ENOSYS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) LISTEN.invokeExact(cs, fd, backlog);
            return rc == 0 ? 0 : -errno(cs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 阻塞 accept（EINTR 重试）；失败回 -errno。 */
    public static int accept(int fd) {
        if (ACCEPT == null) return -ENOSYS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            while (true) {
                int rc = (int) ACCEPT.invokeExact(cs, fd, MemorySegment.NULL, MemorySegment.NULL);
                if (rc >= 0) return rc;
                int e = errno(cs);
                if (e != EINTR) return -e;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 读进 {@code buf[0, len)}；0 = EOF；失败回 -errno。 */
    public static int read(int fd, byte[] buf, int len) {
        if (READ == null) return -ENOSYS;
        int n = Math.min(len, buf.length);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Math.max(1, n));
            MemorySegment cs = arena.allocate(CAPTURE);
            while (true) {
                long r = (long) READ.invokeExact(cs, fd, seg, (long) n);
                if (r >= 0) {
                    MemorySegment.copy(seg, JAVA_BYTE, 0, buf, 0, (int) r);
                    return (int) r;
                }
                int e = errno(cs);
                if (e != EINTR) return -e;
            }
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 把 {@code buf[0, len)} 全部写完；失败回 false。 */
    public static boolean writeAll(int fd, byte[] buf, int len) {
        if (WRITE == null) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(Math.max(1, len));
            MemorySegment.copy(buf, 0, seg, JAVA_BYTE, 0, len);
            MemorySegment cs = arena.allocate(CAPTURE);
            long off = 0;
            while (off < len) {
                long n = (long) WRITE.invokeExact(cs, fd, seg.asSlice(off), len - off);
                if (n < 0) {
                    if (errno(cs) == EINTR) continue;
                    return false;
                }
                off += n;
            }
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void shutdown(int fd, int how) {
        if (SHUTDOWN == null || fd < 0) return;
        try {
            int ignored = (int) SHUTDOWN.invokeExact(fd, how);
            assert ignored <= 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public static void close(int fd) {
        if (CLOSE == null || fd < 0) return;
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
            assert ignored <= 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 有 {@code kill}（POSIX）。 */
    public static boolean hasKill() {
        return KILL != null;
    }

    /** {@code kill(pid, sig)}：0 成功；失败回 errno。 */
    public static int kill(int pid, int signal) {
        if (KILL == null) return ENOSYS;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = arena.allocate(CAPTURE);
            int rc = (int) KILL.invokeExact(cs, pid, signal);
            return rc == 0 ? 0 : errno(cs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
