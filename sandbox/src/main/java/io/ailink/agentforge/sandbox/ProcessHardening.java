package io.ailink.agentforge.sandbox;

import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.ffm.Libc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 进程加固（移植自 Codex process-hardening：PR_SET_DUMPABLE=0、RLIMIT_CORE=0、PT_DENY_ATTACH、清 LD_* / DYLD_*）。
 * 都要宿主显式调：库不在装载时改进程状态；失败抛带码的 {@link SandboxException}（{@code sandbox/hardening-failed} /
 * {@code sandbox/hardening-unsupported}）而不是退出进程。
 */
public final class ProcessHardening {
    private ProcessHardening() {
    }

    /** 动态装载器会认的变量前缀（Linux / BSD 的 LD_，macOS 的 DYLD_）。 */
    public static final List<String> LOADER_VARIABLE_PREFIXES = List.of("LD_", "DYLD_");

    private static final int PR_SET_DUMPABLE = 4;
    private static final int PR_GET_DUMPABLE = 3;
    private static final int PT_DENY_ATTACH = 31;
    private static final int RLIMIT_CORE = 4; // Linux / macOS / BSD 同号

    /** 把当前进程的 core 文件上限设为 0（软硬都设；之后不能再调高）。Windows ⇒ {@code sandbox/hardening-unsupported}。 */
    public static void disableCoreDumps() {
        if (Platform.isWindows())
            throw SandboxException.of(ErrorKind.PLATFORM_NOT_SUPPORTED, "sandbox/hardening-unsupported", "setrlimit(RLIMIT_CORE)");
        long[] r = Libc.setrlimit(RLIMIT_CORE, 0, 0);
        if (r[0] != 0) throw SandboxException.of(ErrorKind.IO, "sandbox/hardening-failed", "setrlimit(RLIMIT_CORE)", r[1]);
    }

    /** Linux：设「可 dump」标志（测试用来复原）。 */
    public static void setDumpable(boolean dumpable) {
        long[] r = Libc.prctl(PR_SET_DUMPABLE, dumpable ? 1 : 0, 0, 0, 0);
        if (r[0] != 0) throw SandboxException.of(ErrorKind.IO, "sandbox/hardening-failed", "prctl(PR_SET_DUMPABLE)", r[1]);
    }

    public static boolean isDumpable() {
        return Libc.prctl(PR_GET_DUMPABLE, 0, 0, 0, 0)[0] == 1;
    }

    /** 禁止调试器附着（Linux：不可 dump；macOS：拒绝 ptrace）。其他平台 ⇒ {@code sandbox/hardening-unsupported}。 */
    public static void disableDebuggerAttach() {
        if (Platform.isLinux()) {
            setDumpable(false);
        } else if (Platform.isMacOS()) {
            long[] r = Libc.ptrace(PT_DENY_ATTACH, 0, 0);
            if (r[0] == -1) throw SandboxException.of(ErrorKind.IO, "sandbox/hardening-failed", "ptrace(PT_DENY_ATTACH)", r[1]);
        } else {
            throw SandboxException.of(ErrorKind.PLATFORM_NOT_SUPPORTED, "sandbox/hardening-unsupported", "PR_SET_DUMPABLE");
        }
    }

    /** 先禁调试器附着再禁 core dump（Codex pre_main_hardening 的次序；Windows 上什么都不做）。 */
    public static void hardenCurrentProcess() {
        if (Platform.isWindows()) return;
        if (Platform.isLinux() || Platform.isMacOS()) disableDebuggerAttach();
        disableCoreDumps();
    }

    /** 当前 core 上限 {cur, max}（无符号）；取不到 ⇒ {-1, -1}。 */
    public static long[] coreLimit() {
        long[] l = Libc.getrlimit(RLIMIT_CORE);
        return l != null ? l : new long[] {-1, -1};
    }

    /** 以任一前缀开头的键（区分大小写，同 Codex env_keys_with_prefix）。 */
    public static List<String> keysWithPrefix(Collection<String> keys, List<String> prefixes) {
        List<String> out = new ArrayList<>();
        for (String k : keys) if (prefixes.stream().anyMatch(k::startsWith)) out.add(k);
        return out;
    }

    private static boolean isLoaderVariable(String k) {
        return LOADER_VARIABLE_PREFIXES.stream().anyMatch(k::startsWith);
    }

    /** 同 {@link #withoutLoaderVariables(ExecRequest, Collection)}，继承的那份取 {@link System#getenv()} 的键。 */
    public static ExecRequest withoutLoaderVariables(ExecRequest request) {
        return withoutLoaderVariables(request, System.getenv().keySet());
    }

    /**
     * 子进程环境里去掉装载器变量：显式覆盖里的删掉，继承来的（{@code inheritEnvironment}）记成 {@code null}（= 删除）。
     * JVM 改不了自己的环境 ⇒ 继承来的键由 {@code currentKeys} 给（缺省 JVM 起来时的快照，同 ProcessBuilder）。
     */
    public static ExecRequest withoutLoaderVariables(ExecRequest request, Collection<String> currentKeys) {
        Map<String, String> env = new LinkedHashMap<>();
        request.environment().forEach((k, v) -> env.put(k, isLoaderVariable(k) ? null : v));
        if (request.inheritEnvironment()) for (String k : keysWithPrefix(currentKeys, LOADER_VARIABLE_PREFIXES)) env.put(k, null);
        return request.withEnvironment(env);
    }
}
