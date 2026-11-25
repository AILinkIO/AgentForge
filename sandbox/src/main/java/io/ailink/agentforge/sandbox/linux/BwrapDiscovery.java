package io.ailink.agentforge.sandbox.linux;

import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.WritableRoot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 找系统 bwrap、探 user namespace、认 WSL1 / 挂 proc 失败（移植自 Codex codex-rs/sandboxing/src/bwrap.rs 与 linux_run_main.rs 的
 * is_proc_mount_failure）。⛔ 随带 bwrap：只找系统里的（随带一个 setuid / 可改写的 bwrap 等于给沙盒开后门）。
 */
public final class BwrapDiscovery {
    private BwrapDiscovery() {
    }

    public static final String PROGRAM = "bwrap";

    /** bwrap 起不来 user namespace 时 stderr 里的原话（数据，不是文案）。 */
    public static final List<String> USER_NAMESPACE_FAILURES = List.of("loopback: Failed RTM_NEWADDR", "loopback: Failed RTM_NEWLINK",
            "setting up uid map: Permission denied", "No permissions to create a new namespace");

    private static final int STDERR_LIMIT = 64 * 1024;

    /** 属主（能先 chmod 再写）或内核按有效身份判可写（含 ACL）；判不出 ⇒ 当作改得了（⛔ 把未验证的当可信）。 */
    public static boolean currentUserCanModify(String path) {
        LinuxFs.Stat st = LinuxFs.stat(path, true);
        if (st == null || st.uid() == LinuxFs.effectiveUid()) return true;
        int e = LinuxFs.accessEffective(path, LinuxFs.W_OK);
        return !(e == LinuxFs.EACCES || e == LinuxFs.EROFS);
    }

    private static List<WritableRoot> canonicalRoots(List<WritableRoot> roots) {
        List<WritableRoot> out = new ArrayList<>();
        for (WritableRoot r : roots) {
            String c = LinuxFs.realPath(r.root());
            if (c == null || c.equals(r.root())) {
                out.add(r);
                continue;
            }
            List<String> ro = new ArrayList<>();
            for (String s : r.readOnlySubpaths()) {
                if (LexicalPaths.isUnder(s, r.root()) && !LexicalPaths.same(s, r.root())) ro.add(LexicalPaths.join(c, LexicalPaths.relative(r.root(), s)));
                else if (LexicalPaths.same(s, r.root())) ro.add(c);
                else ro.add(s);
            }
            out.add(new WritableRoot(c, ro, r.protectedMetadataNames()));
        }
        return out;
    }

    /**
     * 在 {@code searchPaths} 里按序找第一个可执行的 {@code program}（规范化成真实路径）；跳过：在非根 cwd 之下的；策略允许写且当前用户
     * 改得了的；任一祖先目录策略允许写、不是只读子路径且用户改得了的。{@code pol} 为 {@code null} ⇒ 只做 cwd 这一条。
     */
    public static String findExecutable(String program, List<String> searchPaths, String cwd, SandboxPolicy pol, PathContext ctx) {
        String canonicalCwd = LinuxFs.realPath(cwd);
        if (canonicalCwd == null) canonicalCwd = cwd;
        boolean cwdRoot = canonicalCwd.equals("/");
        boolean fullWrite = pol != null && pol.fullDiskWriteAccess();
        List<WritableRoot> roots = pol != null && ctx != null ? canonicalRoots(pol.matcher(ctx).writableRoots()) : List.of();
        java.util.function.Predicate<String> canWrite = path -> fullWrite || roots.stream().anyMatch(r -> r.isWritable(path));
        for (String dir : searchPaths) {
            if (dir == null || dir.isEmpty()) continue;
            String candidate = LexicalPaths.join(LexicalPaths.isAbsolute(dir) ? dir : LexicalPaths.join(canonicalCwd, dir), program);
            LinuxFs.Stat st = LinuxFs.stat(candidate, true);
            if (st == null || !st.isRegularFile() || LinuxFs.accessEffective(candidate, LinuxFs.X_OK) != 0) continue;
            String path = LinuxFs.realPath(candidate);
            if (path == null) continue;
            if (!cwdRoot && LexicalPaths.isUnder(path, canonicalCwd)) continue;
            if (pol != null) {
                if (canWrite.test(path) && currentUserCanModify(path)) continue;
                boolean replaceable = false;
                for (String component = path; component != null; component = LexicalPaths.directoryName(component)) {
                    String parent = LexicalPaths.directoryName(component);
                    final String comp = component;
                    if (parent != null && canWrite.test(parent)
                            && roots.stream().noneMatch(r -> LexicalPaths.containsPath(r.readOnlySubpaths(), comp))
                            && currentUserCanModify(parent)) {
                        replaceable = true;
                        break;
                    }
                }
                if (replaceable) continue;
            }
            return path;
        }
        return null;
    }

    /** 缺省搜索路径：进程的 PATH。 */
    public static List<String> defaultSearchPaths() {
        String path = System.getenv("PATH");
        if (path == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** 跑一次只收 stderr 的结果。 */
    public record StderrRun(boolean exited, int exitCode, String stderr) {
    }

    /**
     * 起进程、只收 stderr（至多 64 KiB）；超时 ⇒ 杀掉、{@code exited = false}；起不来 ⇒ {@code exited = false}。退出后至多再等 200 ms 收 stderr。
     * ⛔ 等占着 stderr 的孙进程。
     */
    public static StderrRun runCapturingStderr(String program, List<String> args, long timeoutMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(program);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p;
        try {
            p = pb.start();
        } catch (IOException | SecurityException e) {
            return new StderrRun(false, -1, "");
        }
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // 已关
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread reader = Thread.startVirtualThread(() -> {
            byte[] chunk = new byte[4096];
            try (InputStream in = p.getErrorStream()) {
                while (true) {
                    synchronized (buffer) {
                        if (buffer.size() >= STDERR_LIMIT) break;
                    }
                    int n = in.read(chunk);
                    if (n <= 0) break;
                    synchronized (buffer) {
                        buffer.write(chunk, 0, Math.min(n, STDERR_LIMIT - buffer.size()));
                    }
                }
            } catch (IOException ignored) {
                // 到此为止
            }
        });
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                return new StderrRun(false, -1, "");
            }
            reader.join(200);
            synchronized (buffer) {
                return new StderrRun(true, p.exitValue(), buffer.toString(StandardCharsets.UTF_8));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new StderrRun(false, -1, "");
        }
    }

    private static String firstLineContaining(String text, String needle) {
        for (String line : text.split("\n", -1)) if (line.contains(needle)) return line.trim();
        return needle;
    }

    /** user namespace 探测结果；{@code failure} 是不可用时 stderr 里的那一行。 */
    public record UserNamespaceAccess(boolean available, String failure) {
    }

    /**
     * 试跑 {@code bwrap --unshare-user --unshare-net --ro-bind / / /bin/true}：成功、超时、起不来、或失败但不是 user namespace 的原因 ⇒ 可用；
     * stderr 是已知的 user namespace 失败 ⇒ 不可用。
     */
    public static UserNamespaceAccess userNamespaceAccess(String bwrap, long timeoutMs) {
        StderrRun r = runCapturingStderr(bwrap, List.of("--unshare-user", "--unshare-net", "--ro-bind", "/", "/", "/bin/true"), timeoutMs);
        if (!r.exited() || r.exitCode() == 0) return new UserNamespaceAccess(true, null);
        String known = USER_NAMESPACE_FAILURES.stream().filter(r.stderr()::contains).findFirst().orElse(null);
        String failure = known == null ? null : firstLineContaining(r.stderr(), known);
        return new UserNamespaceAccess(failure == null, failure);
    }

    /** 挂 /proc 失败的 stderr（Codex is_proc_mount_failure）。 */
    public static boolean isProcMountFailure(String stderr) {
        return stderr.contains("Can't mount proc") && stderr.contains("/newroot/proc")
                && (stderr.contains("Invalid argument") || stderr.contains("Operation not permitted") || stderr.contains("Permission denied"));
    }

    /** /proc/version 说明是 WSL1（Codex proc_version_indicates_wsl1）。 */
    public static boolean procVersionIndicatesWsl1(String procVersion) {
        String v = procVersion.toLowerCase(Locale.ROOT);
        String rest = v;
        while (true) {
            int marker = rest.indexOf("wsl");
            if (marker < 0) return v.contains("microsoft") && !v.contains("microsoft-standard");
            int start = marker + 3;
            int digits = 0;
            while (start + digits < rest.length() && rest.charAt(start + digits) >= '0' && rest.charAt(start + digits) <= '9') digits++;
            Long parsed = null;
            if (digits > 0) {
                try {
                    parsed = Long.parseLong(rest.substring(start, start + digits));
                } catch (NumberFormatException ignored) {
                    parsed = null;
                }
            }
            if (parsed != null && parsed <= 0xFFFFFFFFL) return parsed == 1;
            rest = rest.substring(start);
        }
    }

    public static boolean isWsl1() {
        try {
            Path path = Path.of("/proc/version");
            return Files.exists(path) && procVersionIndicatesWsl1(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
