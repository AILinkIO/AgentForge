package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import io.ailink.agentforge.sandbox.SandboxException;

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
 * Windows 沙盒用到的 Win32 API（FFM；参照 Codex {@code windows-sandbox-rs}）。⚠ 未验证：只在 Linux 上编译过。
 * 句柄在首次调用时才绑定（{@link H}），非 Windows 平台装载本类不碰任何 DLL。结构体按 x64 / ARM64 的布局手写偏移。
 * 带 {@code SetLastError} 的调用经 {@code captureCallState("GetLastError")} 取错误码（{@link #lastError()}，每线程一份）。
 */
public final class WinApi {
    private WinApi() {
    }

    public static final int FILE_GENERIC_READ = 0x120089, FILE_GENERIC_WRITE = 0x120116, FILE_GENERIC_EXECUTE = 0x1200A0, DELETE_RIGHT = 0x10000;
    public static final int GRANT_MASK = FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE_RIGHT;
    public static final int DENY_MASK = FILE_GENERIC_WRITE | DELETE_RIGHT;
    /** deny-read：读 / 写 / 执行 / 删除全禁。 */
    public static final int DENY_READ_MASK = FILE_GENERIC_READ | FILE_GENERIC_WRITE | FILE_GENERIC_EXECUTE | DELETE_RIGHT;
    public static final int READ_EXECUTE_MASK = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
    static final int GENERIC_ALL = 0x10000000, READ_CONTROL = 0x20000;
    public static final int CONTAINER_INHERIT_ACE = 0x2, OBJECT_INHERIT_ACE = 0x1;
    public static final int INHERIT_BOTH = CONTAINER_INHERIT_ACE | OBJECT_INHERIT_ACE;
    public static final int SET_ACCESS = 2, DENY_ACCESS = 3, REVOKE_ACCESS = 4;
    static final int TRUSTEE_IS_SID = 0;
    static final int SE_FILE_OBJECT = 1, DACL_SECURITY_INFORMATION = 0x4;
    static final int TOKEN_ASSIGN_PRIMARY = 0x1, TOKEN_DUPLICATE = 0x2, TOKEN_QUERY = 0x8;
    static final int TOKEN_ADJUST_PRIVILEGES = 0x20, TOKEN_ADJUST_DEFAULT = 0x80;
    static final int DISABLE_MAX_PRIVILEGE = 0x1, LUA_TOKEN = 0x4, WRITE_RESTRICTED = 0x8;
    static final int TOKEN_USER = 1, TOKEN_GROUPS = 2, TOKEN_DEFAULT_DACL = 6, TOKEN_RESTRICTED_SIDS = 11;
    static final int SE_GROUP_ENABLED = 0x00000004;
    static final int SE_GROUP_LOGON_ID = 0xC0000000;
    static final int CREATE_UNICODE_ENVIRONMENT = 0x400, CREATE_NO_WINDOW = 0x08000000, EXTENDED_STARTUPINFO_PRESENT = 0x80000;
    static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    static final int HANDLE_FLAG_INHERIT = 1, FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
    static final int FILE_READ_ATTRIBUTES = 0x80, FILE_SHARE_ALL = 7, OPEN_EXISTING = 3;
    static final int ACL_REVISION = 2;
    /** SE_DACL_AUTO_INHERIT_REQ：把父目录上的可继承 ACE 传播给已存在的子对象。 */
    static final int SE_DACL_AUTO_INHERIT_REQ = 0x0100;
    static final long PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x20009;
    static final long PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x20002;
    static final long PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x2000D;
    static final int AF_INET = 2, TCP_TABLE_OWNER_PID_CONNECTIONS = 4;
    static final int ERROR_INSUFFICIENT_BUFFER = 122;
    static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    static final int WAIT_TIMEOUT = 0x102, STARTF_USESTDHANDLES = 0x100;

    // ── 结构体大小（x64） ──────────────────────────────────────────────────────
    static final long SID_AND_ATTRIBUTES_SIZE = 16;
    static final long EXPLICIT_ACCESS_SIZE = 48;
    static final long STARTUPINFOEX_SIZE = 112;
    static final long PROCESS_INFORMATION_SIZE = 24;
    static final long JOBOBJECT_EXTENDED_LIMIT_INFORMATION_SIZE = 144;
    static final long SECURITY_CAPABILITIES_SIZE = 24;
    static final long SECURITY_ATTRIBUTES_SIZE = 24;
    static final long TOKEN_PRIVILEGES_SIZE = 16;

    private static final ThreadLocal<int[]> LAST_ERROR = ThreadLocal.withInitial(() -> new int[1]);

    /** 最近一次带 SetLastError 的调用留下的错误码（本线程）。 */
    public static int lastError() {
        return LAST_ERROR.get()[0];
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** 句柄（首次用到时绑定）。 */
    static final class H {
        private H() {
        }

        static final Linker LINKER = Linker.nativeLinker();
        static final StructLayout CAPTURE = Linker.Option.captureStateLayout();
        static final VarHandle GLE = CAPTURE.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));
        private static final Linker.Option GLE_OPT = Linker.Option.captureCallState("GetLastError");
        private static final Arena ARENA = Arena.global();
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", ARENA);
        private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32", ARENA);
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", ARENA);
        private static final SymbolLookup USERENV = SymbolLookup.libraryLookup("userenv", ARENA);
        private static final SymbolLookup IPHLPAPI = SymbolLookup.libraryLookup("iphlpapi", ARENA);

        private static MethodHandle k(String name, FunctionDescriptor fd) {
            return LINKER.downcallHandle(KERNEL32.findOrThrow(name), fd, GLE_OPT);
        }

        private static MethodHandle a(String name, FunctionDescriptor fd) {
            return LINKER.downcallHandle(ADVAPI32.findOrThrow(name), fd, GLE_OPT);
        }

        private static final FunctionDescriptor B_P = FunctionDescriptor.of(JAVA_INT, ADDRESS);

        static final MethodHandle GET_CURRENT_PROCESS = LINKER.downcallHandle(KERNEL32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(ADDRESS));
        static final MethodHandle CLOSE_HANDLE = k("CloseHandle", B_P);
        static final MethodHandle LOCAL_FREE = k("LocalFree", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle OPEN_PROCESS_TOKEN = a("OpenProcessToken", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle GET_TOKEN_INFORMATION = a("GetTokenInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle SET_TOKEN_INFORMATION = a("SetTokenInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle CREATE_RESTRICTED_TOKEN = a("CreateRestrictedToken",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle CONVERT_STRING_SID_TO_SID = a("ConvertStringSidToSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle CONVERT_SID_TO_STRING_SID = a("ConvertSidToStringSidW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle SET_ENTRIES_IN_ACL = a("SetEntriesInAclW", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle GET_NAMED_SECURITY_INFO = a("GetNamedSecurityInfoW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle SET_NAMED_SECURITY_INFO = a("SetNamedSecurityInfoW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle INITIALIZE_ACL = a("InitializeAcl", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle ADD_ACCESS_ALLOWED_ACE = a("AddAccessAllowedAce", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle LOOKUP_PRIVILEGE_VALUE = a("LookupPrivilegeValueW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle ADJUST_TOKEN_PRIVILEGES = a("AdjustTokenPrivileges",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle GET_ACL_INFORMATION = a("GetAclInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle GET_ACE = a("GetAce", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle CREATE_PROCESS_AS_USER = k("CreateProcessAsUserW",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CREATE_PROCESS = k("CreateProcessW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT,
                JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle CREATE_JOB_OBJECT = k("CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        static final MethodHandle SET_INFORMATION_JOB_OBJECT = k("SetInformationJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle TERMINATE_JOB_OBJECT = k("TerminateJobObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle CREATE_PIPE = k("CreatePipe", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        static final MethodHandle SET_HANDLE_INFORMATION = k("SetHandleInformation", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST = k("InitializeProcThreadAttributeList",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle UPDATE_PROC_THREAD_ATTRIBUTE = k("UpdateProcThreadAttribute",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        static final MethodHandle DELETE_PROC_THREAD_ATTRIBUTE_LIST = k("DeleteProcThreadAttributeList", FunctionDescriptor.ofVoid(ADDRESS));
        static final MethodHandle GET_EXTENDED_TCP_TABLE = LINKER.downcallHandle(IPHLPAPI.findOrThrow("GetExtendedTcpTable"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), GLE_OPT);
        static final MethodHandle OPEN_PROCESS = k("OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        static final MethodHandle CREATE_DESKTOP = LINKER.downcallHandle(USER32.findOrThrow("CreateDesktopW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), GLE_OPT);
        static final MethodHandle CLOSE_DESKTOP = LINKER.downcallHandle(USER32.findOrThrow("CloseDesktop"), B_P, GLE_OPT);
        static final MethodHandle CREATE_APP_CONTAINER_PROFILE = LINKER.downcallHandle(USERENV.findOrThrow("CreateAppContainerProfile"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle DERIVE_APP_CONTAINER_SID = LINKER.downcallHandle(USERENV.findOrThrow("DeriveAppContainerSidFromAppContainerName"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle DELETE_APP_CONTAINER_PROFILE = LINKER.downcallHandle(USERENV.findOrThrow("DeleteAppContainerProfile"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        static final MethodHandle WAIT_FOR_SINGLE_OBJECT = k("WaitForSingleObject", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        static final MethodHandle GET_EXIT_CODE_PROCESS = k("GetExitCodeProcess", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle CREATE_FILE = k("CreateFileW", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        static final MethodHandle GET_FINAL_PATH_NAME_BY_HANDLE = k("GetFinalPathNameByHandleW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        static final MethodHandle READ_FILE = k("ReadFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        static final MethodHandle WRITE_FILE = k("WriteFile", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    }

    static MemorySegment capture(Arena arena) {
        return arena.allocate(H.CAPTURE);
    }

    static void saveError(MemorySegment cs) {
        LAST_ERROR.get()[0] = (int) H.GLE.get(cs, 0L);
    }

    /** UTF-16 NUL 结尾串。 */
    static MemorySegment wide(Arena arena, String s) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }

    /** 读原生 UTF-16 串（NUL 结尾）。 */
    static String readWide(MemorySegment p) {
        return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    static boolean isNull(MemorySegment p) {
        return p == null || p.address() == 0;
    }

    static boolean invalidHandle(MemorySegment p) {
        return isNull(p) || p.address() == -1L;
    }

    static MemorySegment getCurrentProcess() {
        try {
            return (MemorySegment) H.GET_CURRENT_PROCESS.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static boolean closeHandle(MemorySegment handle) {
        if (isNull(handle)) return false;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = capture(arena);
            int rc = (int) H.CLOSE_HANDLE.invokeExact(cs, handle);
            saveError(cs);
            return rc != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static void localFree(MemorySegment p) {
        if (isNull(p)) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = capture(arena);
            MemorySegment ignored = (MemorySegment) H.LOCAL_FREE.invokeExact(cs, p);
            assert ignored != null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 成功 ⇒ 令牌句柄；失败 ⇒ {@code null}（错误码见 {@link #lastError()}）。 */
    static MemorySegment openProcessToken(MemorySegment process, int access) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment cs = capture(arena);
            int rc = (int) H.OPEN_PROCESS_TOKEN.invokeExact(cs, process, access, out);
            saveError(cs);
            return rc != 0 ? out.get(ADDRESS, 0) : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 调用一个回 BOOL 的句柄（参数照原样），存错误码。 */
    static boolean callBool(MethodHandle mh, Object... args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = capture(arena);
            Object[] all = new Object[args.length + 1];
            all[0] = cs;
            System.arraycopy(args, 0, all, 1, args.length);
            int rc = (int) mh.invokeWithArguments(all);
            saveError(cs);
            return rc != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 调用一个回 DWORD / HRESULT 的句柄（{@code captured}：句柄带 GetLastError 捕获参数）。 */
    static int callInt(MethodHandle mh, boolean captured, Object... args) {
        try (Arena arena = Arena.ofConfined()) {
            Object[] all;
            MemorySegment cs = null;
            if (captured) {
                cs = capture(arena);
                all = new Object[args.length + 1];
                all[0] = cs;
                System.arraycopy(args, 0, all, 1, args.length);
            } else {
                all = args;
            }
            int rc = (int) mh.invokeWithArguments(all);
            if (cs != null) saveError(cs);
            return rc;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** 调用一个回指针 / 句柄的句柄，存错误码。 */
    static MemorySegment callPtr(MethodHandle mh, Object... args) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cs = capture(arena);
            Object[] all = new Object[args.length + 1];
            all[0] = cs;
            System.arraycopy(args, 0, all, 1, args.length);
            MemorySegment rc = (MemorySegment) mh.invokeWithArguments(all);
            saveError(cs);
            return rc;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // ── 令牌 / 桌面 / 真实路径（Java 上层（io.ailink.agentforge.sandbox.windows）直接用的） ─────────────────────────────

    /** 本进程令牌能否拿到建受限令牌要的权限（受限令牌后端的探测）；失败 ⇒ Win32 错误码，成功 ⇒ 0。 */
    public static int probeRestrictedToken() {
        MemorySegment token = openProcessToken(getCurrentProcess(), TOKEN_DUPLICATE | TOKEN_QUERY | TOKEN_ASSIGN_PRIMARY | TOKEN_ADJUST_DEFAULT
                | TOKEN_ADJUST_PRIVILEGES);
        if (token == null) return Math.max(1, lastError());
        closeHandle(token);
        return 0;
    }

    /** 当前用户的 SID 串（读进程令牌的 TokenUser）；失败 ⇒ {@code sandbox/win-token-failed}。 */
    public static String currentUserSid() {
        MemorySegment token = openProcessToken(getCurrentProcess(), TOKEN_QUERY);
        if (token == null) throw SandboxException.ofNative("sandbox/win-token-failed", lastError());
        try (Arena a = Arena.ofConfined()) {
            MemorySegment needed = a.allocate(JAVA_INT);
            callBool(H.GET_TOKEN_INFORMATION, token, TOKEN_USER, MemorySegment.NULL, 0, needed);
            int n = needed.get(JAVA_INT, 0);
            MemorySegment buf = a.allocate(Math.max(n, 16), 8);
            if (!callBool(H.GET_TOKEN_INFORMATION, token, TOKEN_USER, buf, n, needed))
                throw SandboxException.ofNative("sandbox/win-token-failed", lastError());
            return WinAcl.nativeSidToString(buf.get(ADDRESS, 0));
        } finally {
            closeHandle(token);
        }
    }

    /** 建私有桌面（{@code GENERIC_ALL}）；失败 ⇒ {@code sandbox/win-desktop-failed}。 */
    public static MemorySegment createDesktop(String name) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment desktop = callPtr(H.CREATE_DESKTOP, wide(a, name), MemorySegment.NULL, MemorySegment.NULL, 0, GENERIC_ALL, MemorySegment.NULL);
            if (isNull(desktop)) throw SandboxException.ofNative("sandbox/win-desktop-failed", lastError());
            return desktop;
        }
    }

    public static void closeDesktop(MemorySegment desktop) {
        if (!isNull(desktop)) callBool(H.CLOSE_DESKTOP, desktop);
    }

    /** {@code GetFinalPathNameByHandleW} 的原文（含 {@code \\?\} 前缀）；打不开 / 取不到 ⇒ {@code null}。 */
    public static String finalPathName(String path) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handle = callPtr(H.CREATE_FILE, wide(arena, path), FILE_READ_ATTRIBUTES, FILE_SHARE_ALL, MemorySegment.NULL, OPEN_EXISTING,
                    FILE_FLAG_BACKUP_SEMANTICS, MemorySegment.NULL);
            if (invalidHandle(handle)) return null;
            try {
                final int maxLength = 32768;
                MemorySegment buffer = arena.allocate((long) maxLength * 2);
                int written = callInt(H.GET_FINAL_PATH_NAME_BY_HANDLE, true, handle, buffer, maxLength, 0);
                if (written == 0 || written > maxLength) return null;
                return readWide(buffer);
            } finally {
                closeHandle(handle);
            }
        }
    }
}
