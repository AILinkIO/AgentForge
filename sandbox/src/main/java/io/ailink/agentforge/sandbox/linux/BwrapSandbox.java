package io.ailink.agentforge.sandbox.linux;

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
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.policy.AccessMode;
import io.ailink.agentforge.sandbox.policy.LexicalPaths;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyEntry;
import io.ailink.agentforge.sandbox.policy.PolicyPath;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.SpecialKind;
import io.ailink.agentforge.sandbox.proxy.ProxyBridge;
import io.ailink.agentforge.sandbox.proxy.ProxyOptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Linux 后端：系统 bubblewrap（参照 Codex linux_run_main.rs / sandboxing/src/bwrap.rs / manager.rs）。
 * <p>{@link #probe()} 找 bwrap 并试跑一次确认非特权 user namespace 真能用（AppArmor、容器、WSL1 常见起不来，报带码的原因）。
 * <p>{@link #wrap} 把请求改写成 {@code bwrap … -- 原 argv} 交给内层后端：/ 只读、可写根回绑、根下受保护名 / 只读子路径再只读、deny 路径遮掉、
 * {@code --unshare-user/pid/ipc}、断网时 {@code --unshare-net}、{@code --new-session --die-with-parent --cap-drop ALL}。
 * <p>{@code PROXIED} 网络：起本地代理 + netns 代理桥（{@link ProxyBridge}），原生件经 LD_PRELOAD 在沙盒里接桥（需要宿主提供原生件）。
 * ⛔ 随带 bwrap、⛔ 以 argv0 重入当 helper：bwrap 直接经内层后端起。
 */
public final class BwrapSandbox implements Sandbox {
    public static final String NAME = "bwrap";
    private static final ExecCapabilities CAPS = new ExecCapabilities(NAME, true, false);

    /**
     * 选项。
     *
     * @param bwrapPath                   显式的 bwrap（{@code null} ⇒ 在 searchPaths / PATH 里找）
     * @param searchPaths                 找 bwrap 的目录（{@code null} ⇒ PATH）
     * @param mountProc                   挂 /proc（探测发现挂不了会自动关）
     * @param protectMissingMetadataNames 不存在的受保护名挂成只读空目录
     * @param probeTimeoutMs              探测超时
     * @param networkProxy                {@code PROXIED} 网络用的代理选项（{@code null} ⇒ 缺省：全拒）
     */
    public record Options(String bwrapPath, List<String> searchPaths, boolean mountProc, boolean protectMissingMetadataNames,
                          long probeTimeoutMs, ProxyOptions networkProxy) {
        public static final Options DEFAULT = new Options(null, null, true, true, 2000, null);

        public Options withBwrapPath(String v) {
            return new Options(v, searchPaths, mountProc, protectMissingMetadataNames, probeTimeoutMs, networkProxy);
        }

        public Options withSearchPaths(List<String> v) {
            return new Options(bwrapPath, v, mountProc, protectMissingMetadataNames, probeTimeoutMs, networkProxy);
        }

        public Options withNetworkProxy(ProxyOptions v) {
            return new Options(bwrapPath, searchPaths, mountProc, protectMissingMetadataNames, probeTimeoutMs, v);
        }
    }

    private record Probe(SandboxCapabilities caps, String path, boolean mountProc) {
    }

    // ── 合成挂载点（进程内登记，按路径计数；最后一个用它的沙盒进程退出后删空目录） ──
    private static final Object SYNTHETIC_GATE = new Object();
    private static final Map<String, Integer> SYNTHETIC_COUNTS = new HashMap<>();

    /** 这个路径是本进程的合成挂载点（还有沙盒在用）。 */
    public static boolean isSynthetic(String path) {
        synchronized (SYNTHETIC_GATE) {
            return SYNTHETIC_COUNTS.containsKey(path);
        }
    }

    private static boolean emptyDir(String dir) throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(Path.of(dir))) {
            return !s.iterator().hasNext();
        }
    }

    private static void releaseSynthetic(List<String> paths) {
        synchronized (SYNTHETIC_GATE) {
            for (String path : paths) {
                Integer n = SYNTHETIC_COUNTS.get(path);
                if (n == null) continue;
                if (n > 1) {
                    SYNTHETIC_COUNTS.put(path, n - 1);
                    continue;
                }
                SYNTHETIC_COUNTS.remove(path);
                try {
                    LinuxFs.Stat st = LinuxFs.stat(path, false);
                    if (st != null && st.isDirectory() && emptyDir(path)) Files.delete(Path.of(path));
                } catch (IOException | SecurityException ignored) {
                    // 尽力
                }
            }
        }
    }

    private final Options options;
    private final CachedProbe<Probe> probe;
    private final Object maskGate = new Object();
    private String maskFile;

    public BwrapSandbox() {
        this(Options.DEFAULT);
    }

    public BwrapSandbox(Options options) {
        this.options = options == null ? Options.DEFAULT : options;
        this.probe = new CachedProbe<>(this::runProbe);
    }

    /** 探测到的 bwrap（不可用 ⇒ {@code null}）。 */
    public String bwrapPath() {
        return probe.get().path();
    }

    /** 挂 /proc 的预检参数（Codex preflight_proc_mount_support：只读挂平台缺省目录，⛔ 绑整个文件系统）。 */
    public static List<String> preflightArguments(boolean mountProc) {
        SandboxPolicy pol = SandboxPolicy.restricted(List.of(PolicyEntry.of(PolicyPath.special(SpecialKind.MINIMAL), AccessMode.READ)));
        String trueCmd = Files.exists(Path.of("/usr/bin/true")) ? "/usr/bin/true" : Files.exists(Path.of("/bin/true")) ? "/bin/true" : "true";
        return BwrapArgs.build(List.of(trueCmd), pol, PathContext.of("/"), "/",
                BwrapArgs.Options.DEFAULT.withMountProc(mountProc).withProtectMissingMetadataNames(false)).args();
    }

    private List<String> searchPaths() {
        return options.searchPaths() != null ? options.searchPaths() : BwrapDiscovery.defaultSearchPaths();
    }

    private static String currentDirectory() {
        return Path.of("").toAbsolutePath().toString();
    }

    private Probe runProbe() {
        if (!Platform.isLinux()) return new Probe(SandboxCapabilities.unavailable(NAME, Message.of("sandbox/bwrap-not-linux")), null, false);
        if (BwrapDiscovery.isWsl1()) return new Probe(SandboxCapabilities.unavailable(NAME, Message.of("sandbox/bwrap-wsl1")), null, false);
        String explicit = options.bwrapPath();
        String path;
        if (explicit != null) {
            LinuxFs.Stat st = LinuxFs.stat(explicit, true);
            path = st != null && st.isRegularFile() && LinuxFs.accessEffective(explicit, LinuxFs.X_OK) == 0 ? explicit : null;
        } else {
            path = BwrapDiscovery.findExecutable(BwrapDiscovery.PROGRAM, searchPaths(), currentDirectory(), null, null);
        }
        if (path == null)
            return new Probe(SandboxCapabilities.unavailable(NAME, Message.of("sandbox/bwrap-missing", explicit != null ? explicit : BwrapDiscovery.PROGRAM)),
                    null, false);
        BwrapDiscovery.UserNamespaceAccess userns = BwrapDiscovery.userNamespaceAccess(path, options.probeTimeoutMs());
        if (!userns.available())
            return new Probe(SandboxCapabilities.unavailable(NAME, Message.of("sandbox/bwrap-userns-blocked", userns.failure())), path, false);
        boolean proc = false;
        if (options.mountProc()) {
            BwrapDiscovery.StderrRun r = BwrapDiscovery.runCapturingStderr(path, preflightArguments(true), options.probeTimeoutMs());
            proc = !r.exited() || !BwrapDiscovery.isProcMountFailure(r.stderr());
        }
        return new Probe(SandboxCapabilities.available(NAME), path, proc);
    }

    @Override
    public SandboxCapabilities probe() {
        return probe.get().caps();
    }

    /** 遮文件用的空文件：本进程私有的临时目录里，权限 000（沙盒里读它 ⇒ Permission denied）。进程内只建一个。 */
    private String maskFile() {
        synchronized (maskGate) {
            if (maskFile == null) {
                try {
                    Path dir = Files.createTempDirectory("ks-bwrap-");
                    Path f = dir.resolve("masked");
                    Files.write(f, new byte[0]);
                    Files.setPosixFilePermissions(f, EnumSet.noneOf(PosixFilePermission.class));
                    // JVM 退出时清掉（000 文件 + 目录）
                    dir.toFile().deleteOnExit();
                    f.toFile().deleteOnExit();
                    maskFile = f.toString();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return maskFile;
        }
    }

    private static boolean needsMask(SandboxPolicy pol) {
        return pol.isRestricted() && pol.entries().stream().anyMatch(e -> e.access() == AccessMode.DENY);
    }

    private Exec.Rewrite rewrite(String bwrap, SandboxPolicy pol, PathContext ctx, boolean mountProc, ExecRequest request) {
        List<String> argv = request.arguments();
        if (argv.isEmpty()) return Exec.Rewrite.of(request);
        String commandCwd = request.workingDirectory() != null ? LexicalPaths.resolve(request.workingDirectory(), ctx.cwd()) : ctx.cwd();
        boolean proxied = pol.network() == NetworkMode.PROXIED;
        String nat = null;
        ProxyBridge.Runtime rt = null;
        if (proxied) {
            nat = LandlockSandbox.nativePath();
            if (nat == null) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/landlock-native-missing");
            try {
                // bwrap 的目标进程是 secure-exec；组 / 他人可写的 LD_PRELOAD 会被 loader 忽略
                Files.setPosixFilePermissions(Path.of(nat), PosixFilePermissions.fromString("rwxr-xr-x"));
                rt = ProxyBridge.runtime(options.networkProxy());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            request = request.mergeEnvironment(Map.of(ProxyBridge.CONTROL_PATH_VARIABLE, rt.bridge().controlPath(),
                    ProxyBridge.SKIP_EXECUTABLE_VARIABLE, bwrap));
        }
        final ProxyBridge.Runtime runtime = rt;
        Runnable closeRt = () -> {
            if (runtime != null) runtime.close();
        };
        BwrapArgs.Built built;
        try {
            synchronized (SYNTHETIC_GATE) {
                BwrapArgs.Options o = new BwrapArgs.Options(mountProc, false, pol.network(), needsMask(pol) ? maskFile() : null,
                        options.protectMissingMetadataNames(), BwrapSandbox::isSynthetic,
                        runtime != null ? runtime.bridge().controlPath() : null, nat);
                built = BwrapArgs.build(argv, pol, ctx, commandCwd, o);
                for (String t : built.syntheticTargets()) SYNTHETIC_COUNTS.merge(t, 1, Integer::sum);
            }
        } catch (Throwable t) {
            closeRt.run();
            throw t;
        }
        List<String> targets = built.syntheticTargets();
        Runnable release = () -> {
            try {
                closeRt.run();
            } finally {
                releaseSynthetic(targets);
            }
        };
        if (built.args().equals(argv) && targets.isEmpty()) return new Exec.Rewrite(request, runtime != null ? closeRt : null, false);
        List<String> full = new ArrayList<>();
        full.add(bwrap);
        full.addAll(built.args());
        ExecRequest out = request.withArguments(full);
        // 沙盒里只看得到固定挂载点；宿主侧原生件路径通常不进最小文件系统
        if (runtime != null) out = out.mergeEnvironment(Map.of("LD_PRELOAD", BwrapArgs.PROXY_BRIDGE_LIBRARY_TARGET));
        return new Exec.Rewrite(out, release, true);
    }

    @Override
    public ExecBackend wrap(ExecBackend inner, SandboxPolicy policy, PathContext context) {
        Objects.requireNonNull(inner, "inner");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(context, "context");
        Probe p = probe.get();
        if (!p.caps().available() || p.path() == null)
            throw new SandboxException(ErrorKind.INVALID_OPERATION,
                    p.caps().unavailableReason() != null ? p.caps().unavailableReason() : Message.of("sandbox/bwrap-missing", BwrapDiscovery.PROGRAM));
        // 带策略再找一遍：沙盒里改得了的 bwrap 不用（Codex find_system_bwrap_in_path）
        String bwrap = options.bwrapPath() != null ? options.bwrapPath()
                : BwrapDiscovery.findExecutable(BwrapDiscovery.PROGRAM, searchPaths(), currentDirectory(), policy, context);
        if (bwrap == null) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/bwrap-untrusted", p.path());
        boolean mountProc = p.mountProc();
        return Exec.rewriting(inner, CAPS, req -> rewrite(bwrap, policy, context, mountProc, req));
    }
}
