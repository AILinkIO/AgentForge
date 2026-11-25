package io.ailink.agentforge.sandbox.windows;

import io.ailink.agentforge.sandbox.CachedProbe;
import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecProcess;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.ffm.WinAcl;
import io.ailink.agentforge.sandbox.ffm.WinApi;
import io.ailink.agentforge.sandbox.ffm.WinAppContainer;
import io.ailink.agentforge.sandbox.ffm.WinProcess;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyMatcher;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Windows 二期：CreateAppContainerProfile + SECURITY_CAPABILITIES + CreateProcessW；读隔离 + 不给 internetClient 即真断网。
 * 不要管理员、不打包、不产 exe。⚠ 未验证（只在 Linux 上编译 / 跑平台无关部分）。
 */
public final class AppContainerSandbox implements Sandbox {
    public static final String NAME = "win-appcontainer";
    private static final String INTERNET_CLIENT_SID = "S-1-15-3-1";
    private static final ExecCapabilities CAPS = new ExecCapabilities(NAME, true, false);

    private final String stateDirectory;
    private final List<String> toolchainReadPaths;
    private final boolean deleteProfileOnDispose;
    private final CachedProbe<SandboxCapabilities> probe;

    /**
     * @param stateDirectory         账本目录（宿主给）
     * @param toolchainReadPaths     额外要放读的工具链目录
     * @param deleteProfileOnDispose 进程结束后删 profile
     */
    public AppContainerSandbox(String stateDirectory, List<String> toolchainReadPaths, boolean deleteProfileOnDispose) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
        this.toolchainReadPaths = toolchainReadPaths == null ? List.of() : List.copyOf(toolchainReadPaths);
        this.deleteProfileOnDispose = deleteProfileOnDispose;
        this.probe = new CachedProbe<>(this::runProbe);
    }

    public AppContainerSandbox(String stateDirectory) {
        this(stateDirectory, List.of(), false);
    }

    private SandboxCapabilities runProbe() {
        if (!WindowsSupport.isWindows()) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/win-not-windows"));
        try {
            Files.createDirectories(Path.of(stateDirectory));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // 用一次性 profile 实测非提权路径，失败码直接暴露给宿主诊断
        String name = "agentforge-probe-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            WinAppContainer.ensureProfile(name, List.of());
            return SandboxCapabilities.available(NAME);
        } catch (SandboxException e) {
            return SandboxCapabilities.unavailable(NAME, e.reason());
        } finally {
            try {
                WinAppContainer.deleteProfile(name);
            } catch (RuntimeException ignored) {
                // 尽力
            }
        }
    }

    /** 工作区路径派生稳定 profile 名（{@code agentforge-ws-<32 hex>}）；AppContainer 名只允许安全字符且远短于 64。 */
    public static String profileName(String workspace) {
        if (workspace == null || workspace.isBlank()) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/path-not-absolute", String.valueOf(workspace));
        String full = LexicalPaths.fullPath(workspace);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(full.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_16LE));
            return "agentforge-ws-" + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String localApplicationData() {
        String home = System.getProperty("user.home");
        if (Platform.isWindows()) {
            String lad = System.getenv("LOCALAPPDATA");
            return lad != null ? lad : LexicalPaths.join(home, "AppData\\Local");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return xdg != null && !xdg.isEmpty() ? xdg : LexicalPaths.join(home, ".local/share");
    }

    /** 常见工具链读路径 + 宿主清单，绝对化、不分大小写去重。 */
    public List<String> effectiveToolchainReadPaths() {
        String lad = localApplicationData();
        List<String> paths = new ArrayList<>(List.of(LexicalPaths.join(LexicalPaths.join(lad, "Microsoft"), "dotnet"),
                LexicalPaths.join(System.getProperty("user.home"), ".dotnet"), LexicalPaths.join(LexicalPaths.join(lad, "Programs"), "Python")));
        paths.addAll(toolchainReadPaths);
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            String f = LexicalPaths.fullPath(p);
            if (seen.add(f.toUpperCase(Locale.ROOT))) out.add(f);
        }
        return out;
    }

    public String ledgerPath() {
        return RestrictedTokenSandbox.ledgerPath(stateDirectory);
    }

    private static String systemDirectory() {
        String root = System.getenv("SystemRoot");
        return LexicalPaths.join(root != null ? root : "C:\\Windows", "System32");
    }

    private static boolean systemManaged(String path) {
        List<String> roots = new ArrayList<>();
        roots.add(systemDirectory());
        roots.add(LexicalPaths.directoryName(systemDirectory()));
        roots.add(System.getenv("ProgramFiles"));
        roots.add(System.getenv("ProgramFiles(x86)"));
        roots.add(System.getenv("ProgramData"));
        for (String r : roots) if (r != null && !r.isBlank() && LexicalPaths.isUnder(path, LexicalPaths.fullPath(r))) return true;
        return false;
    }

    /** AppContainer 不能可靠地按宿主 PATH 搜索；宿主先解析，且不搜 cwd（防可写根里的同名程序劫持）。 */
    private static String resolveExecutable(String program, String cwd) {
        if (LexicalPaths.isAbsolute(program)) return program;
        if (program.contains("/") || program.contains("\\")) return LexicalPaths.fullPath(LexicalPaths.combine(cwd, program));
        String name = program.toLowerCase(Locale.ROOT).endsWith(".exe") ? program : program + ".exe";
        List<String> dirs = new ArrayList<>();
        dirs.add(systemDirectory());
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(";")) if (!d.isBlank()) dirs.add(d.trim());
        for (String d : dirs) {
            String c = LexicalPaths.combine(d, name);
            if (Files.isRegularFile(Path.of(c))) return c;
        }
        throw SandboxException.of(ErrorKind.IO, "sandbox/win-process-failed", 2);
    }

    /** 撤销并清账；deny-read 有快照 ⇒ 按快照还原 DACL（REVOKE 会误伤用户原有 ACE），没快照 ⇒ 不是本次加的，不动。 */
    static void revokeAll(String ledger, List<LedgerEntry> entries, String runTemp) {
        try {
            WindowsSupport.withLedgerLock(ledger, () -> {
                List<LedgerEntry> remaining = WindowsSupport.readLedger(ledger);
                for (LedgerEntry e : entries) {
                    WindowsSupport.Release r = WindowsSupport.release(remaining, e);
                    remaining = r.remaining();
                    if (!r.last()) continue;
                    try {
                        if (e.kind().equals("synthetic")) {
                            if (WindowsSupport.isDirectory(e.path()) && WindowsSupport.isEmptyDirectory(e.path())) Files.delete(Path.of(e.path()));
                        } else if (e.kind().equals("deny-read")) {
                            if (r.snapshot() != null) WinAcl.restoreDacl(e.path(), r.snapshot());
                        } else {
                            WindowsSupport.setAce(e.path(), e.sid(), WinApi.REVOKE_ACCESS, 0, 0);
                        }
                    } catch (Exception ex) {
                        remaining = new ArrayList<>(remaining);
                        remaining.add(e.withSddl(r.snapshot()));
                    }
                }
                if (WindowsSupport.exists(ledger)) WindowsSupport.writeLedger(ledger, remaining);
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            WindowsSupport.deleteTree(runTemp);
        }
    }

    private static void grantRead(List<LedgerEntry> acc, String root, boolean write, String sid) {
        int mask = write ? WinApi.GRANT_MASK : WinApi.READ_EXECUTE_MASK;
        if (WindowsSupport.setAce(root, sid, WinApi.SET_ACCESS, mask, WinApi.INHERIT_BOTH) != WindowsSupport.AceChange.BLOCKED)
            acc.add(new LedgerEntry(root, sid, write ? "grant" : "read", mask, WinApi.INHERIT_BOTH));
    }

    /** 读也禁。真机取证：AppContainer 的读检查会落到宿主用户 SID 上 ⇒ deny 宿主用户；撤销按 DACL 快照还原。 */
    private static void denyRead(List<LedgerEntry> acc, String path, String hostSid) {
        String c = WindowsSupport.canonicalizeExisting(path);
        int inheritance = WindowsSupport.isDirectory(c) ? WinApi.INHERIT_BOTH : 0;
        String sddl = WinAcl.daclSnapshot(c);
        WindowsSupport.AceChange change = WindowsSupport.setAce(c, hostSid, WinApi.DENY_ACCESS, WinApi.DENY_READ_MASK, inheritance);
        acc.add(new LedgerEntry(c, hostSid, "deny-read", WinApi.DENY_READ_MASK, inheritance, change == WindowsSupport.AceChange.CHANGED ? sddl : null));
    }

    private static void applyDenyRead(List<LedgerEntry> acc, SandboxPolicy pol, PolicyMatcher m, List<String> granted, String hostSid) {
        if (!pol.hasDeniedReadRestrictions()) return; // 缺省读全拒，不需要额外 ACE
        List<String> denyRoots = new ArrayList<>();
        for (String d : m.unreadableRoots()) {
            if (!WindowsSupport.exists(d)) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/win-appcontainer-deny-path-missing", d);
            denyRead(acc, d, hostSid);
            denyRoots.add(WindowsSupport.canonicalizeExisting(d));
        }
        // glob deny：把已存在的命中项逐个 deny
        for (String root : granted) {
            if (!WindowsSupport.isDirectory(root)) continue;
            for (String c : WindowsSupport.enumerateExistingPaths(root)) {
                if (LexicalPaths.same(c, root) || denyRoots.stream().anyMatch(d -> LexicalPaths.isUnder(c, d)) || !m.readDenied(c)) continue;
                denyRead(acc, c, hostSid);
            }
        }
    }

    private ExecProcess start(SandboxPolicy pol, PathContext ctx, ExecRequest request) throws IOException {
        String program = WindowsSupport.checkRequest(request);
        String cwd = request.workingDirectory() != null ? request.workingDirectory() : ctx.cwd();
        String resolved = resolveExecutable(program, cwd);
        List<String> argv = new ArrayList<>();
        argv.add(resolved);
        argv.addAll(request.arguments().subList(1, request.arguments().size()));
        String commandLine = WindowsSupport.commandLine(argv);
        if (commandLine == null) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/win-command-line-too-long");
        String pname = profileName(ctx.cwd());
        boolean net = pol.network() == NetworkMode.ENABLED;
        String profileSid = WinAppContainer.ensureProfile(pname, net ? List.of(INTERNET_CLIENT_SID) : List.of());
        String hostSid = WinApi.currentUserSid();
        String runTemp = RestrictedTokenSandbox.runTempDirectory("agentforge-ac-");
        String ledger = ledgerPath();
        List<LedgerEntry> acc = new ArrayList<>();
        List<String> granted = new ArrayList<>();
        PolicyMatcher m = pol.matcher(ctx);
        try {
            WindowsSupport.withLedgerLock(ledger, () -> {
                grantRead(acc, runTemp, true, profileSid);
                RestrictedTokenSandbox.protectRoots(acc, pol, ctx, profileSid, (a, root, sid) -> {
                    granted.add(root);
                    grantRead(a, root, true, sid);
                });
                // 文件系统根（「全盘可读」）授不得：那等于把整块盘读开；只授显式路径
                String fsRoot = LexicalPaths.fileSystemRoot(ctx.cwd());
                for (String r : m.readableRoots()) {
                    if (LexicalPaths.same(r, fsRoot)) continue;
                    String path = WindowsSupport.canonicalizeRoot(r);
                    granted.add(path);
                    grantRead(acc, path, false, profileSid);
                }
                for (String t : effectiveToolchainReadPaths()) {
                    if (!WindowsSupport.exists(t)) continue;
                    String path = WindowsSupport.canonicalizeExisting(t);
                    granted.add(path);
                    grantRead(acc, path, false, profileSid);
                }
                // PATH 命中的可执行文件若不在上面的清单里，至少放行这一个文件
                String exe = WindowsSupport.canonicalizeExisting(LexicalPaths.fullPath(resolved));
                boolean covered = acc.stream().anyMatch(e -> (e.kind().equals("grant") || e.kind().equals("read")) && LexicalPaths.isUnder(exe, e.path()));
                if (!covered && !systemManaged(exe)) {
                    granted.add(exe);
                    grantRead(acc, exe, false, profileSid);
                }
                applyDenyRead(acc, pol, m, granted, hostSid);
                for (LedgerEntry e : acc) WindowsSupport.appendLedger(ledger, e);
                return null;
            });
            byte[] env = WindowsSupport.unicodeBlock(WindowsSupport.environment(request, pol.network() == NetworkMode.RESTRICTED, runTemp));
            WinProcess nat = WinProcess.startAppContainer(commandLine, env, cwd, profileSid, net);
            return Exec.withCleanup(WindowsProcess.wrap(nat), () -> {
                revokeAll(ledger, acc, runTemp);
                if (deleteProfileOnDispose) WinAppContainer.deleteProfile(pname);
            }, false);
        } catch (Throwable t) {
            revokeAll(ledger, acc, runTemp);
            throw t;
        }
    }

    @Override
    public SandboxCapabilities probe() {
        return probe.get();
    }

    @Override
    public ExecBackend wrap(ExecBackend inner, SandboxPolicy policy, PathContext context) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        SandboxCapabilities caps = probe();
        if (!caps.available())
            throw new SandboxException(ErrorKind.INVALID_OPERATION,
                    caps.unavailableReason() != null ? caps.unavailableReason() : Message.of("sandbox/win-not-windows"));
        if (policy.fullDiskWriteAccess()) throw unsupported("full-disk write");
        if (policy.fullDiskReadAccess() && policy.matcher(context).writableRoots().isEmpty())
            throw unsupported("full-disk read without writable roots");
        if (policy.network() == NetworkMode.PROXIED) throw unsupported("proxied network");
        return new ExecBackend() {
            @Override
            public ExecCapabilities capabilities() {
                return CAPS;
            }

            @Override
            public ExecProcess start(ExecRequest request) throws IOException {
                return AppContainerSandbox.this.start(policy, context, request);
            }
        };
    }

    private static SandboxException unsupported(String what) {
        return SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/win-appcontainer-policy-unsupported", what);
    }
}
