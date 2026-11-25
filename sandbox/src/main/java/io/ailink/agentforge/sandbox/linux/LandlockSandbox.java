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
import io.ailink.agentforge.sandbox.ffm.SandboxNative;
import io.ailink.agentforge.sandbox.policy.NetworkMode;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.PolicyMatcher;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import io.ailink.agentforge.sandbox.policy.WritableRoot;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Linux 后端：Landlock（文件系统）+ seccomp（网络）。
 * <p>{@link #probe()}：不是 Linux / 原生件没装 / 内核不支持 Landlock ⇒ 不可用（带码的原因；不装作可用，由编排层决定退到 bwrap 或裸跑）。
 * <p>{@link #wrap}：把请求改写后再交给内层：策略以十六进制写进环境（{@value #POLICY_VARIABLE}），子进程经 {@code LD_PRELOAD} 装载
 * 原生件，它的构造函数在 exec 之后、main 之前读 blob 并套用 Landlock + seccomp（套不上就退出 127 —— fail closed）。
 * 这样 Landlock 能叠在 bwrap 里面，而 JVM 自己不受限。
 */
public final class LandlockSandbox implements Sandbox {
    public static final String NAME = "landlock";
    /** 原生件读策略的环境变量名（与原生件的约定，勿改）。 */
    public static final String POLICY_VARIABLE = "KS_SANDBOX_POLICY";

    // 布局：u32 magic 'KSPB' | u32 version | u32 net_mode | u32 fs_enabled | u32 rule_count，之后每条 u64 access | u32 path_len | path（小端）。
    public static final int MAGIC = 0x4250534B;
    public static final int VERSION = 1;
    public static final int NET_NONE = 0;
    public static final int NET_RESTRICTED = 1;

    // Landlock 的访问位（内核 ABI，勿改）
    public static final long EXECUTE = 1, WRITE_FILE = 2, READ_FILE = 4, READ_DIR = 8, REMOVE_DIR = 16, REMOVE_FILE = 32, MAKE_CHAR = 64,
            MAKE_DIR = 128, MAKE_REG = 256, MAKE_SOCK = 512, MAKE_FIFO = 1024, MAKE_BLOCK = 2048, MAKE_SYM = 4096, REFER = 8192,
            TRUNCATE = 16384, IOCTL_DEV = 32768;

    /** 执行 + 读文件 + 列目录（EXECUTE 是全局位，跑子进程必须给）。 */
    public static final long READ_ONLY_ACCESS = EXECUTE | READ_FILE | READ_DIR;
    /** Codex 的 AccessFs::from_all；内核不支持的位在原生侧按 ABI 掩掉。 */
    public static final long READ_WRITE_ACCESS = 0xFFFF;

    private static final ExecCapabilities CAPS = new ExecCapabilities(NAME, true, false);
    private final CachedProbe<SandboxCapabilities> probe = new CachedProbe<>(LandlockSandbox::runProbe);

    public LandlockSandbox() {
    }

    /** @param nativeDirectory 原生件目录（{@code null} ⇒ 按缺省次序找） */
    public LandlockSandbox(String nativeDirectory) {
        if (nativeDirectory != null && !nativeDirectory.isEmpty()) setNativeDirectory(nativeDirectory);
    }

    /** 宿主显式给原生件目录；定位过之后再换 ⇒ {@code sandbox/landlock-directory-locked}。 */
    public static void setNativeDirectory(String dir) {
        SandboxNative.setExplicitDirectory(dir);
    }

    /** 原生件的全路径（找不到 ⇒ {@code null}）。 */
    public static String nativePath() {
        return SandboxNative.path();
    }

    /** 策略 blob。 */
    public record Blob(byte[] blob, boolean fsEnabled, boolean netRestricted) {
    }

    private static void u32(ByteArrayOutputStream b, long v) {
        for (int i = 0; i < 4; i++) b.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    /**
     * 策略 ⇒ blob：文件系统不限 ⇒ 不装 Landlock；网络不是 ENABLED ⇒ 装断网的 seccomp。
     * 只读子路径（.git 等）不带进 Landlock：path-beneath 规则按祖先合并，挡不住可写父根 —— 由外层 bwrap 的 ro-bind 兑现。
     */
    public static Blob buildBlob(SandboxPolicy pol, PathContext ctx) {
        boolean fs = pol.isRestricted();
        boolean net = pol.network() != NetworkMode.ENABLED;
        List<Object[]> rules = new ArrayList<>();
        if (fs) {
            PolicyMatcher m = pol.matcher(ctx);
            if (pol.fullDiskReadAccess()) {
                rules.add(new Object[] {READ_ONLY_ACCESS, "/"});
            } else {
                for (String r : m.readableRoots()) rules.add(new Object[] {READ_ONLY_ACCESS, r});
                if (pol.includePlatformDefaults())
                    for (String r : BwrapArgs.LINUX_PLATFORM_DEFAULT_READ_ROOTS) rules.add(new Object[] {READ_ONLY_ACCESS, r});
            }
            for (WritableRoot w : m.writableRoots()) rules.add(new Object[] {READ_WRITE_ACCESS, w.root()});
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u32(b, MAGIC);
        u32(b, VERSION);
        u32(b, net ? NET_RESTRICTED : NET_NONE);
        u32(b, fs ? 1 : 0);
        u32(b, rules.size());
        for (Object[] r : rules) {
            long access = (Long) r[0];
            byte[] bs = ((String) r[1]).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 8; i++) b.write((int) ((access >>> (8 * i)) & 0xFF));
            u32(b, bs.length);
            b.writeBytes(bs);
        }
        return new Blob(b.toByteArray(), fs, net);
    }

    private static SandboxCapabilities runProbe() {
        if (!Platform.isLinux()) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/landlock-not-linux"));
        if (nativePath() == null) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/landlock-native-missing"));
        int abi;
        try {
            abi = SandboxNative.probe();
        } catch (SandboxException | IllegalArgumentException | UnsatisfiedLinkError | NoSuchElementException e) {
            abi = -1;
        }
        if (abi < 0) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/landlock-native-missing"));
        if (abi < 1) return SandboxCapabilities.unavailable(NAME, Message.of("sandbox/landlock-unsupported"));
        // 装了 seccomp 过滤器 ⇒ 子进程被 SIGSYS 杀掉（128+31）也算拒判
        return SandboxCapabilities.available(NAME).withSeccomp(true);
    }

    /** 改写请求（加上 LD_PRELOAD 与策略 blob）；策略什么都不限 ⇒ 原样。 */
    public static ExecRequest rewrite(SandboxPolicy pol, PathContext ctx, ExecRequest request) {
        Blob b = buildBlob(pol, ctx);
        if (!b.fsEnabled() && !b.netRestricted()) return request;
        String nat = nativePath();
        if (nat == null) throw SandboxException.of(ErrorKind.INVALID_OPERATION, "sandbox/landlock-native-missing");
        String existing = request.environment().get("LD_PRELOAD");
        // 桥的控制路径原样带着（原生构造函数先激活桥，再把网络位改成 NONE —— 直连仍由外层空 netns 阻断）
        return request.mergeEnvironment(Map.of(POLICY_VARIABLE, "hex:" + HexFormat.of().withUpperCase().formatHex(b.blob()),
                "LD_PRELOAD", existing != null && !existing.isEmpty() ? nat + " " + existing : nat));
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
                    caps.unavailableReason() != null ? caps.unavailableReason() : Message.of("sandbox/landlock-unsupported"));
        return Exec.rewriting(inner, CAPS, req -> Exec.Rewrite.of(rewrite(policy, context, req)));
    }
}
