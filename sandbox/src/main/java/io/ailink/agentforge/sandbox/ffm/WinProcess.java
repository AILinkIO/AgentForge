package io.ailink.agentforge.sandbox.ffm;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.ailink.agentforge.sandbox.SandboxException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;



/**
 * Windows 沙盒进程（参照 Codex {@code windows-sandbox-rs/src/process.rs}：CreateProcessAsUserW + HANDLE_LIST + JOB_LIST 原子入 Job + 私有桌面）。
 * ⛔ breakaway（保「杀整棵树」承诺）。这里只给原生原语（读 / 写管道、等退出、杀、关）；读管道的线程与收尾在 Java 上层（io.ailink.agentforge.sandbox.windows）。⚠ 未验证。
 * 失败 ⇒ {@code sandbox/win-token-failed} / {@code sandbox/win-process-failed} / {@code sandbox/win-appcontainer-process-failed}（错误码）。
 */
public final class WinProcess implements AutoCloseable {
    /** {@link #read} 的流号。 */
    public static final int STDOUT = 1, STDERR = 2;

    private final MemorySegment process;
    private final MemorySegment thread;
    private final MemorySegment job;
    private final MemorySegment attrList;
    private final Arena arena;
    private final MemorySegment stdin;
    private final MemorySegment stdout;
    private final MemorySegment stderr;
    private final int pid;
    private final AtomicBoolean stdinClosed = new AtomicBoolean();
    private final AtomicBoolean disposed = new AtomicBoolean();

    private WinProcess(MemorySegment process, MemorySegment thread, MemorySegment job, MemorySegment attrList, Arena arena,
                       MemorySegment stdin, MemorySegment stdout, MemorySegment stderr, int pid) {
        this.process = process;
        this.thread = thread;
        this.job = job;
        this.attrList = attrList;
        this.arena = arena;
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
        this.pid = pid;
    }

    private static SandboxException tokenFailed() {
        return SandboxException.ofNative("sandbox/win-token-failed", WinApi.lastError());
    }

    private static SandboxException processFailed() {
        return SandboxException.ofNative("sandbox/win-process-failed", WinApi.lastError());
    }

    /** 建受限令牌、Job、三根管道并起进程（{@code desktopName}：私有桌面名，可 {@code null}）。调用方负责之后按账撤销 ACL。 */
    public static WinProcess startRestricted(String commandLine, byte[] environment, String cwd, String capabilitySid, String desktopName) {
        MemorySegment baseToken = WinApi.openProcessToken(WinApi.getCurrentProcess(), WinApi.TOKEN_DUPLICATE | WinApi.TOKEN_QUERY
                | WinApi.TOKEN_ASSIGN_PRIMARY | WinApi.TOKEN_ADJUST_DEFAULT | WinApi.TOKEN_ADJUST_PRIVILEGES);
        if (baseToken == null) throw tokenFailed();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sidPtr = WinAcl.stringSidToNative(capabilitySid);
            try {
                // 限制集 = 能力 SID + 登录 SID + Everyone（照 token.rs:481；缺前两者进程 DLL 初始化都过不去）
                var owned = new ArrayList<MemorySegment>();
                try {
                    MemorySegment[] restrict = buildRestrictSids(baseToken, sidPtr, owned);
                    MemorySegment list = a.allocate(WinApi.SID_AND_ATTRIBUTES_SIZE * restrict.length, 8);
                    for (int i = 0; i < restrict.length; i++) list.set(ADDRESS, i * WinApi.SID_AND_ATTRIBUTES_SIZE, restrict[i]);
                    MemorySegment out = a.allocate(ADDRESS);
                    if (!WinApi.callBool(WinApi.H.CREATE_RESTRICTED_TOKEN, baseToken, WinApi.DISABLE_MAX_PRIVILEGE | WinApi.LUA_TOKEN | WinApi.WRITE_RESTRICTED,
                            0, MemorySegment.NULL, 0, MemorySegment.NULL, restrict.length, list, out))
                        throw tokenFailed();
                    MemorySegment token = out.get(ADDRESS, 0);
                    try {
                        setDefaultDacl(token, restrict[1]);
                        reEnableChangeNotify(token);
                        return startCore(commandLine, environment, cwd, token, desktopName, null);
                    } finally {
                        WinApi.closeHandle(token);
                    }
                } finally {
                    for (MemorySegment s : owned) NativeMemory.free(s);
                }
            } finally {
                WinApi.localFree(sidPtr);
            }
        } finally {
            WinApi.closeHandle(baseToken);
        }
    }

    /** 建 AppContainer 进程：默认桌面 + Job + SECURITY_CAPABILITIES；{@code networkEnabled} 才给 internetClient。 */
    public static WinProcess startAppContainer(String commandLine, byte[] environment, String cwd, String appContainerSid, boolean networkEnabled) {
        MemorySegment appSid = WinAcl.stringSidToNative(appContainerSid);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment internetClient = networkEnabled ? WinAcl.stringSidToNative("S-1-15-3-1") : MemorySegment.NULL;
            try {
                MemorySegment caps = MemorySegment.NULL;
                if (networkEnabled) {
                    caps = a.allocate(WinApi.SID_AND_ATTRIBUTES_SIZE, 8);
                    caps.set(ADDRESS, 0, internetClient);
                    caps.set(JAVA_INT, 8, WinApi.SE_GROUP_ENABLED);
                }
                MemorySegment security = a.allocate(WinApi.SECURITY_CAPABILITIES_SIZE, 8);
                security.set(ADDRESS, 0, appSid);
                security.set(ADDRESS, 8, caps);
                security.set(JAVA_INT, 16, networkEnabled ? 1 : 0);
                return startCore(commandLine, environment, cwd, null, null, security);
            } finally {
                if (networkEnabled) WinApi.localFree(internetClient);
            }
        } finally {
            WinApi.localFree(appSid);
        }
    }

    /** 限制 SID：本次能力 SID → 登录 SID（SE_GROUP_LOGON_ID）→ Everyone（S-1-1-0）。 */
    private static MemorySegment[] buildRestrictSids(MemorySegment baseToken, MemorySegment capabilitySid, List<MemorySegment> owned) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment needed = a.allocate(JAVA_INT);
            WinApi.callBool(WinApi.H.GET_TOKEN_INFORMATION, baseToken, WinApi.TOKEN_GROUPS, MemorySegment.NULL, 0, needed);
            int n = needed.get(JAVA_INT, 0);
            MemorySegment buffer = a.allocate(Math.max(n, 8), 8);
            if (!WinApi.callBool(WinApi.H.GET_TOKEN_INFORMATION, baseToken, WinApi.TOKEN_GROUPS, buffer, n, needed)) throw tokenFailed();
            int count = buffer.get(JAVA_INT, 0);
            MemorySegment logon = MemorySegment.NULL;
            for (int i = 0; i < count; i++) {
                long entry = ADDRESS.byteSize() + i * WinApi.SID_AND_ATTRIBUTES_SIZE; // GroupCount 后按指针对齐
                int attributes = buffer.get(JAVA_INT, entry + ADDRESS.byteSize());
                if ((attributes & WinApi.SE_GROUP_LOGON_ID) == WinApi.SE_GROUP_LOGON_ID) {
                    logon = buffer.get(ADDRESS, entry);
                    break;
                }
            }
            MemorySegment everyonePtr = WinAcl.stringSidToNative("S-1-1-0");
            try {
                // 登录 / Everyone 的 SID 原生指针要活到 CreateRestrictedToken 之后 —— 复制成自持缓冲
                MemorySegment logonCopy = WinApi.isNull(logon) ? MemorySegment.NULL : copySid(logon);
                MemorySegment everyone = copySid(everyonePtr);
                if (!WinApi.isNull(logonCopy)) owned.add(logonCopy);
                owned.add(everyone);
                return new MemorySegment[] {capabilitySid, logonCopy, everyone};
            } finally {
                WinApi.localFree(everyonePtr);
            }
        }
    }

    /** 按 SID 自身的子授权数整块拷贝；用 {@link NativeMemory#free} 释放。 */
    private static MemorySegment copySid(MemorySegment source) {
        MemorySegment s = source.reinterpret(Long.MAX_VALUE);
        int length = 8 + 4 * Byte.toUnsignedInt(s.get(JAVA_BYTE, 1));
        MemorySegment copy = NativeMemory.allocForNative(length);
        MemorySegment.copy(s, 0, copy.reinterpret(length), 0, length);
        return copy;
    }

    /** DISABLE_MAX_PRIVILEGE 连 SeChangeNotify 一起拿掉了；不重新启用则路径遍历检查大量失败（token.rs:504）。 */
    private static void reEnableChangeNotify(MemorySegment token) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment luid = a.allocate(8, 4);
            if (!WinApi.callBool(WinApi.H.LOOKUP_PRIVILEGE_VALUE, MemorySegment.NULL, WinApi.wide(a, "SeChangeNotifyPrivilege"), luid)) return;
            MemorySegment tp = a.allocate(WinApi.TOKEN_PRIVILEGES_SIZE, 4);
            tp.set(JAVA_INT, 0, 1);
            tp.set(JAVA_INT, 4, luid.get(JAVA_INT, 0));
            tp.set(JAVA_INT, 8, luid.get(JAVA_INT, 4));
            tp.set(JAVA_INT, 12, 2); // SE_PRIVILEGE_ENABLED
            WinApi.callBool(WinApi.H.ADJUST_TOKEN_PRIVILEGES, token, 0, tp, 0, MemorySegment.NULL, MemorySegment.NULL);
        }
    }

    /** 受限令牌的默认 DACL：登录 SID 可管理；OWNER RIGHTS 只读。 */
    private static void setDefaultDacl(MemorySegment token, MemorySegment logon) {
        MemorySegment ownerRights = WinAcl.stringSidToNative("S-1-3-4");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment acl = a.allocate(1024, 8);
            if (!WinApi.callBool(WinApi.H.INITIALIZE_ACL, acl, 1024, WinApi.ACL_REVISION)
                    || !WinApi.callBool(WinApi.H.ADD_ACCESS_ALLOWED_ACE, acl, WinApi.ACL_REVISION, WinApi.GENERIC_ALL, logon)
                    || !WinApi.callBool(WinApi.H.ADD_ACCESS_ALLOWED_ACE, acl, WinApi.ACL_REVISION, WinApi.READ_CONTROL, ownerRights))
                throw tokenFailed();
            MemorySegment value = a.allocate(ADDRESS);
            value.set(ADDRESS, 0, acl);
            if (!WinApi.callBool(WinApi.H.SET_TOKEN_INFORMATION, token, WinApi.TOKEN_DEFAULT_DACL, value, (int) ADDRESS.byteSize())) throw tokenFailed();
        } finally {
            WinApi.localFree(ownerRights);
        }
    }

    private static MemorySegment[] createPipe(Arena a) {
        MemorySegment sa = a.allocate(WinApi.SECURITY_ATTRIBUTES_SIZE, 8);
        sa.set(JAVA_INT, 0, (int) WinApi.SECURITY_ATTRIBUTES_SIZE);
        sa.set(JAVA_INT, 16, 1);
        MemorySegment r = a.allocate(ADDRESS);
        MemorySegment w = a.allocate(ADDRESS);
        if (!WinApi.callBool(WinApi.H.CREATE_PIPE, r, w, sa, 0)) throw processFailed();
        return new MemorySegment[] {r.get(ADDRESS, 0), w.get(ADDRESS, 0)};
    }

    private static WinProcess startCore(String commandLine, byte[] environment, String cwd, MemorySegment token, String desktopName,
                                        MemorySegment securityCapabilities) {
        MemorySegment job = WinApi.callPtr(WinApi.H.CREATE_JOB_OBJECT, MemorySegment.NULL, MemorySegment.NULL);
        if (WinApi.isNull(job)) throw processFailed();
        Arena arena = Arena.ofShared();
        var childHandles = new ArrayList<MemorySegment>();
        MemorySegment[] hostHandles = new MemorySegment[3];
        boolean ok = false;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment limits = a.allocate(WinApi.JOBOBJECT_EXTENDED_LIMIT_INFORMATION_SIZE, 8);
            limits.set(JAVA_INT, 16, WinApi.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE); // ⛔ BREAKAWAY_OK
            if (!WinApi.callBool(WinApi.H.SET_INFORMATION_JOB_OBJECT, job, 9, limits, (int) WinApi.JOBOBJECT_EXTENDED_LIMIT_INFORMATION_SIZE))
                throw processFailed();

            // 三根管道：宿主端不继承；子端保持可继承，只进 HANDLE_LIST
            MemorySegment[] in = createPipe(a);
            MemorySegment[] out = createPipe(a);
            MemorySegment[] err = createPipe(a);
            childHandles.addAll(List.of(in[0], out[1], err[1]));
            hostHandles[0] = in[1];
            hostHandles[1] = out[0];
            hostHandles[2] = err[0];
            for (MemorySegment h : hostHandles) WinApi.callBool(WinApi.H.SET_HANDLE_INFORMATION, h, WinApi.HANDLE_FLAG_INHERIT, 0);

            int attributeCount = 2 + (securityCapabilities != null ? 1 : 0);
            MemorySegment size = a.allocate(JAVA_LONG);
            WinApi.callBool(WinApi.H.INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST, MemorySegment.NULL, attributeCount, 0, size);
            MemorySegment attrList = arena.allocate(Math.max(size.get(JAVA_LONG, 0), 8), 8);
            if (!WinApi.callBool(WinApi.H.INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST, attrList, attributeCount, 0, size)) throw processFailed();
            MemorySegment inherit = arena.allocate(ADDRESS.byteSize() * childHandles.size(), 8);
            for (int i = 0; i < childHandles.size(); i++) inherit.set(ADDRESS, i * ADDRESS.byteSize(), childHandles.get(i));
            if (!WinApi.callBool(WinApi.H.UPDATE_PROC_THREAD_ATTRIBUTE, attrList, 0, WinApi.PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit,
                    ADDRESS.byteSize() * childHandles.size(), MemorySegment.NULL, MemorySegment.NULL))
                throw processFailed();
            MemorySegment jobRef = arena.allocate(ADDRESS);
            jobRef.set(ADDRESS, 0, job);
            if (!WinApi.callBool(WinApi.H.UPDATE_PROC_THREAD_ATTRIBUTE, attrList, 0, WinApi.PROC_THREAD_ATTRIBUTE_JOB_LIST, jobRef, ADDRESS.byteSize(),
                    MemorySegment.NULL, MemorySegment.NULL))
                throw processFailed();
            if (securityCapabilities != null) {
                MemorySegment sec = arena.allocate(WinApi.SECURITY_CAPABILITIES_SIZE, 8);
                MemorySegment.copy(securityCapabilities, 0, sec, 0, WinApi.SECURITY_CAPABILITIES_SIZE);
                if (!WinApi.callBool(WinApi.H.UPDATE_PROC_THREAD_ATTRIBUTE, attrList, 0, WinApi.PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, sec,
                        WinApi.SECURITY_CAPABILITIES_SIZE, MemorySegment.NULL, MemorySegment.NULL))
                    throw SandboxException.ofNative("sandbox/win-appcontainer-process-failed", WinApi.lastError());
            }

            // STARTUPINFOEXW（x64）：cb@0 lpDesktop@16 dwFlags@60 hStdInput@80 hStdOutput@88 hStdError@96 lpAttributeList@104
            MemorySegment si = a.allocate(WinApi.STARTUPINFOEX_SIZE, 8);
            si.set(JAVA_INT, 0, (int) WinApi.STARTUPINFOEX_SIZE);
            // 受限令牌用共享私有桌面；AppContainer 用默认桌面，避免私有桌面 DACL 又挡住包 SID
            if (desktopName != null) si.set(ADDRESS, 16, WinApi.wide(a, "Winsta0\\" + desktopName));
            si.set(JAVA_INT, 60, WinApi.STARTF_USESTDHANDLES);
            si.set(JAVA_SHORT, 64, (short) 0);
            si.set(ADDRESS, 80, in[0]);
            si.set(ADDRESS, 88, out[1]);
            si.set(ADDRESS, 96, err[1]);
            si.set(ADDRESS, 104, attrList);

            MemorySegment env = a.allocate(Math.max(environment.length, 2), 2);
            MemorySegment.copy(environment, 0, env, JAVA_BYTE, 0, environment.length);
            // lpCommandLine 是可写缓冲（API 会就地改写）
            MemorySegment cmd = WinApi.wide(a, commandLine);
            MemorySegment pi = a.allocate(WinApi.PROCESS_INFORMATION_SIZE, 8);
            int flags = WinApi.CREATE_UNICODE_ENVIRONMENT | WinApi.CREATE_NO_WINDOW | WinApi.EXTENDED_STARTUPINFO_PRESENT;
            boolean started = token != null
                    ? WinApi.callBool(WinApi.H.CREATE_PROCESS_AS_USER, token, MemorySegment.NULL, cmd, MemorySegment.NULL, MemorySegment.NULL, 1, flags, env,
                            WinApi.wide(a, cwd), si, pi)
                    : WinApi.callBool(WinApi.H.CREATE_PROCESS, MemorySegment.NULL, cmd, MemorySegment.NULL, MemorySegment.NULL, 1, flags, env,
                            WinApi.wide(a, cwd), si, pi);
            if (!started) throw processFailed();
            ok = true;
            return new WinProcess(pi.get(ADDRESS, 0), pi.get(ADDRESS, 8), job, attrList, arena, hostHandles[0], hostHandles[1], hostHandles[2],
                    pi.get(JAVA_INT, 16));
        } finally {
            for (MemorySegment h : childHandles) WinApi.closeHandle(h); // 子进程端起完立刻关
            if (!ok) {
                WinApi.callBool(WinApi.H.TERMINATE_JOB_OBJECT, job, 1);
                WinApi.closeHandle(job);
                for (MemorySegment h : hostHandles) if (h != null) WinApi.closeHandle(h);
                arena.close();
            }
        }
    }

    public int pid() {
        return pid;
    }

    /** 从 {@link #STDOUT} / {@link #STDERR} 读一段进 {@code buf}；EOF / 管道断开 ⇒ -1。阻塞。 */
    public int read(int stream, byte[] buf) {
        MemorySegment handle = stream == STDERR ? stderr : stdout;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(Math.max(1, buf.length));
            MemorySegment read = a.allocate(JAVA_INT);
            if (!WinApi.callBool(WinApi.H.READ_FILE, handle, seg, buf.length, read, MemorySegment.NULL)) return -1;
            int n = read.get(JAVA_INT, 0);
            if (n <= 0) return -1;
            MemorySegment.copy(seg, JAVA_BYTE, 0, buf, 0, n);
            return n;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** 写 stdin（子进程已关 stdin / 已退出：静默丢弃）。 */
    public void write(byte[] data) {
        if (stdinClosed.get()) return;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(Math.max(data.length, 1));
            MemorySegment.copy(data, 0, buf, JAVA_BYTE, 0, data.length);
            MemorySegment written = a.allocate(JAVA_INT);
            WinApi.callBool(WinApi.H.WRITE_FILE, stdin, buf, data.length, written, MemorySegment.NULL);
        } catch (RuntimeException e) {
            // 静默丢弃
        }
    }

    public void closeInput() {
        if (stdinClosed.compareAndSet(false, true)) WinApi.closeHandle(stdin);
    }

    /** 等至多 {@code millis} 毫秒；退出了 ⇒ 退出码，否则 {@code null}。 */
    public Integer waitExit(int millis) {
        if (WinApi.callInt(WinApi.H.WAIT_FOR_SINGLE_OBJECT, true, process, millis) == WinApi.WAIT_TIMEOUT) return null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment c = a.allocate(JAVA_INT);
            WinApi.callBool(WinApi.H.GET_EXIT_CODE_PROCESS, process, c);
            return c.get(JAVA_INT, 0);
        }
    }

    /** 杀整棵树（终止 Job）。 */
    public void kill() {
        WinApi.callBool(WinApi.H.TERMINATE_JOB_OBJECT, job, 1);
    }

    @Override
    public void close() {
        if (!disposed.compareAndSet(false, true)) return;
        if (waitExit(0) == null) {
            kill();
            waitExit(5000);
        }
        closeInput();
        WinApi.closeHandle(stdout);
        WinApi.closeHandle(stderr);
        for (MemorySegment h : new MemorySegment[] {process, thread, job}) WinApi.closeHandle(h);
        try (Arena a = Arena.ofConfined()) {
            WinApi.H.DELETE_PROC_THREAD_ATTRIBUTE_LIST.invokeExact(WinApi.capture(a), attrList);
        } catch (Throwable t) {
            // 收尾
        }
        arena.close();
    }
}
