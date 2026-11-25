package io.ailink.agentforge.sandbox.windows;

import io.ailink.agentforge.sandbox.CachedProbe;
import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.SandboxException;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecProcess;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.ffm.WinApi;
import io.ailink.agentforge.sandbox.ffm.WinProcess;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyJson;
import io.ailink.agentforge.sandbox.policy.PolicyMatcher;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.WritableRoot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Windows 一期：CreateRestrictedToken + 随机能力 SID ACL + CreateProcessAsUserW + Job + 私有桌面。⚠ 未验证（只在 Linux 上编译 / 跑平台无关部分）。
 * 一期拒绝：非全盘可读（deny / 缺省拒）、全盘可写、PROXIED、未显式接受的「尽力断网」（受限令牌断不了网，只能投毒代理变量）。
 */
public final class RestrictedTokenSandbox implements Sandbox {
    public static final String NAME = "win-restricted";
    private static final ExecCapabilities CAPS = new ExecCapabilities(NAME, true, false);

    private final String stateDirectory;
    private final boolean acceptBestEffortNetwork;
    private final CachedProbe<SandboxCapabilities> probe;
    private final Map<String, Desktop> desktops = new HashMap<>();

    private static final class Desktop {
        final String name;
        final MemorySegment handle;
        int refs;

        Desktop(String name, MemorySegment handle) {
            this.name = name;
            this.handle = handle;
        }
    }

    /**
     * @param stateDirectory          账本与运行状态目录（宿主给，⛔ 读环境变量）
     * @param acceptBestEffortNetwork 接受「尽力断网」（不是真隔离）
     */
    public RestrictedTokenSandbox(String stateDirectory, boolean acceptBestEffortNetwork) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory");
        this.acceptBestEffortNetwork = acceptBestEffortNetwork;
        this.probe = new CachedProbe<>(() -> {
            if (!WindowsSupport.isWindows()) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/win-not-windows"));
            int e = WinApi.probeRestrictedToken();
            if (e != 0) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/win-token-failed", e));
            try {
                Files.createDirectories(Path.of(stateDirectory));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return SandboxCapabilities.available(NAME).withNetworkIsolated(false); // 尽力断网不是隔离
        });
    }

    public static String ledgerPath(String stateDirectory) {
        return Path.of(stateDirectory).resolve("acl-ledger.log").toString();
    }

    public String ledgerPath() {
        return ledgerPath(stateDirectory);
    }

    /** 宿主启动时清上次崩溃留下的 ACL / 合成目录。 */
    public static void recoverLedger(String stateDirectory) throws IOException {
        String l = ledgerPath(stateDirectory);
        WindowsSupport.withLedgerLock(l, () -> {
            WindowsSupport.recoverLedger(l);
            return null;
        });
    }

    /** 私有桌面池：同一份策略复用一个桌面；最后一个租约释放后才关。⇒ {名字, 释放}。 */
    private record Lease(String name, Runnable close) {
    }

    private Lease rentDesktop(String key) {
        synchronized (desktops) {
            Desktop d = desktops.get(key);
            if (d == null) {
                String name = "agentforge-sbx-" + UUID.randomUUID().toString().replace("-", "");
                d = new Desktop(name, WinApi.createDesktop(name));
                desktops.put(key, d);
            }
            d.refs++;
            final Desktop held = d;
            boolean[] released = {false};
            return new Lease(d.name, () -> {
                synchronized (desktops) {
                    if (released[0]) return;
                    released[0] = true;
                    if (--held.refs <= 0) {
                        desktops.remove(key);
                        WinApi.closeDesktop(held.handle);
                    }
                }
            });
        }
    }

    /** 撤销并清账：还有别的运行在用同一条 ACE ⇒ 只销掉本次这一份账（最后一份持有者才撤销）。然后删运行临时目录。 */
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
                        } else {
                            WindowsSupport.setAce(e.path(), e.sid(), WinApi.REVOKE_ACCESS, 0, 0);
                        }
                    } catch (Exception ex) {
                        remaining = new ArrayList<>(remaining);
                        remaining.add(e);
                    }
                }
                WindowsSupport.writeLedger(ledger, remaining);
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            WindowsSupport.deleteTree(runTemp);
        }
    }

    /** 授权回调：{@code (acc, root, sid)}。 */
    @FunctionalInterface
    interface Grant {
        void grant(List<LedgerEntry> acc, String root, String sid);
    }

    private static void grant(List<LedgerEntry> acc, String root, String sid) {
        // BLOCKED = 该主体上已有 deny ⇒ 不授 allow；ALREADY_PRESENT ⇒ 跳过重写但仍记账
        if (WindowsSupport.setAce(root, sid, WinApi.SET_ACCESS, WinApi.GRANT_MASK, WinApi.INHERIT_BOTH) != WindowsSupport.AceChange.BLOCKED)
            acc.add(new LedgerEntry(root, sid, "grant", WinApi.GRANT_MASK, WinApi.INHERIT_BOTH));
    }

    static void deny(List<LedgerEntry> acc, String path, String sid) {
        String c = WindowsSupport.canonicalizeExisting(path);
        WindowsSupport.setAce(c, sid, WinApi.DENY_ACCESS, WinApi.DENY_MASK, WinApi.INHERIT_BOTH);
        acc.add(new LedgerEntry(c, sid, "deny", WinApi.DENY_MASK, WinApi.INHERIT_BOTH));
    }

    static void denyExisting(List<LedgerEntry> acc, String path, String sid) {
        // 大小写不敏感地找现存项 ⇒ .GiT 这类变体同样被护住
        String dir = LexicalPaths.directoryName(path);
        String e = WindowsSupport.findExistingByName(dir == null ? "" : dir, LexicalPaths.fileName(path));
        if (e != null) deny(acc, e, sid);
    }

    /** 可写根：授权 / 只读子路径 deny / 受保护名 deny（缺失的先建空目录，账上记 synthetic）。 */
    static void protectRoots(List<LedgerEntry> acc, SandboxPolicy pol, PathContext ctx, String sid, Grant grantFn) throws IOException {
        PolicyMatcher m = pol.matcher(ctx);
        for (WritableRoot root : m.writableRoots()) {
            String rootPath = WindowsSupport.canonicalizeRoot(root.root());
            grantFn.grant(acc, rootPath, sid);
            for (String sub : root.readOnlySubpaths()) denyExisting(acc, sub, sid);
            for (String name : root.protectedMetadataNames()) {
                String prot = LexicalPaths.combine(root.root(), name);
                if (m.canWrite(prot)) continue; // 显式 write 条目：跳过合成与 deny
                denyExisting(acc, prot, sid);
                if (!WindowsSupport.exists(prot)) {
                    Files.createDirectories(Path.of(prot));
                    deny(acc, prot, sid);
                    acc.add(new LedgerEntry(prot, sid, "synthetic", WinApi.DENY_MASK, WinApi.INHERIT_BOTH));
                }
            }
        }
    }

    static String runTempDirectory(String prefix) throws IOException {
        Path pp = Path.of(System.getProperty("java.io.tmpdir")).resolve(prefix + UUID.randomUUID().toString().replace("-", ""));
        Files.createDirectories(pp);
        return pp.toString();
    }

    private ExecProcess start(SandboxPolicy pol, PathContext ctx, ExecRequest request) throws IOException {
        WindowsSupport.checkRequest(request);
        String commandLine = WindowsSupport.commandLine(request.arguments());
        if (commandLine == null) throw SandboxException.of(ErrorKind.ARGUMENT, "sandbox/win-command-line-too-long");
        String sid = WindowsSupport.randomCapabilitySid();
        String runTemp = runTempDirectory("agentforge-sbx-");
        String ledger = ledgerPath();
        List<LedgerEntry> acc = new ArrayList<>();
        try {
            // 授权与记账在同一把锁里（跨进程文件锁）
            WindowsSupport.withLedgerLock(ledger, () -> {
                grant(acc, runTemp, sid);
                protectRoots(acc, pol, ctx, sid, RestrictedTokenSandbox::grant);
                for (LedgerEntry e : acc) WindowsSupport.appendLedger(ledger, e);
                return null;
            });
            byte[] env = WindowsSupport.unicodeBlock(WindowsSupport.environment(request, pol.network() == NetworkMode.RESTRICTED, runTemp));
            Lease desktop = rentDesktop(PolicyJson.write(pol));
            try {
                String cwd = request.workingDirectory() != null ? request.workingDirectory() : ctx.cwd();
                WinProcess nat = WinProcess.startRestricted(commandLine, env, cwd, sid, desktop.name());
                return Exec.withCleanup(WindowsProcess.wrap(nat), () -> {
                    try {
                        revokeAll(ledger, acc, runTemp);
                    } finally {
                        desktop.close().run();
                    }
                }, false);
            } catch (Throwable t) {
                desktop.close().run();
                throw t;
            }
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
        if (!policy.fullDiskReadAccess()) throw unsupported("deny-read / deny-by-default");
        if (policy.fullDiskWriteAccess()) throw unsupported("full-disk write");
        if (policy.network() == NetworkMode.PROXIED) throw unsupported("proxied network");
        if (policy.network() == NetworkMode.RESTRICTED && !acceptBestEffortNetwork) throw unsupported("best-effort network not accepted");
        return new ExecBackend() {
            @Override
            public ExecCapabilities capabilities() {
                return CAPS;
            }

            @Override
            public ExecProcess start(ExecRequest request) throws IOException {
                return RestrictedTokenSandbox.this.start(policy, context, request);
            }
        };
    }

    private static SandboxException unsupported(String what) {
        return SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/win-policy-unsupported", what);
    }
}
