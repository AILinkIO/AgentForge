package io.ailink.agentforge.sandbox;

import io.ailink.agentforge.sandbox.linux.BwrapSandbox;
import io.ailink.agentforge.sandbox.linux.LandlockSandbox;
import io.ailink.agentforge.sandbox.windows.AppContainerSandbox;
import io.ailink.agentforge.sandbox.windows.RestrictedTokenSandbox;

import java.util.List;

/**
 * 平台选择：本机该用哪个沙盒后端。
 * <ul>
 * <li>Linux：bwrap（文件系统 + netns，PROXIED 的代理桥只在它上面）→ 不可用时 Landlock + seccomp（要宿主提供原生件）；</li>
 * <li>Windows：AppContainer（真读隔离 + 真断网）→ 受限令牌（尽力断网）；都要宿主给 {@code stateDirectory}；</li>
 * <li>macOS：没有 Seatbelt 后端 ⇒ 无（只有进程加固）。</li>
 * </ul>
 * 全部不可用 ⇒ {@code null}；编排器拿到 {@code null} 会按策略拒（{@code sandbox/unavailable}）或经宿主同意裸跑。
 */
public final class SandboxPlatform {
    private SandboxPlatform() {
    }

    /**
     * 选项。
     *
     * @param bwrap                    bwrap 选项（可空）
     * @param landlockNativeDirectory  Landlock 原生件目录（可空）
     * @param stateDirectory           Windows 账本目录（Windows 必需；空 ⇒ 没有 Windows 后端）
     * @param toolchainReadPaths       AppContainer 额外放读的目录
     * @param acceptBestEffortNetwork  受限令牌后端接受「尽力断网」
     */
    public record Options(BwrapSandbox.Options bwrap, String landlockNativeDirectory, String stateDirectory, List<String> toolchainReadPaths,
                          boolean acceptBestEffortNetwork) {
        public static final Options DEFAULT = new Options(null, null, null, List.of(), false);
    }

    /** 本机按优先序的后端（未探测）。 */
    public static List<Sandbox> candidates(Options opts) {
        Options o = opts == null ? Options.DEFAULT : opts;
        if (Platform.isLinux()) return List.of(new BwrapSandbox(o.bwrap()), new LandlockSandbox(o.landlockNativeDirectory()));
        if (Platform.isWindows() && o.stateDirectory() != null)
            return List.of(new AppContainerSandbox(o.stateDirectory(), o.toolchainReadPaths(), false),
                    new RestrictedTokenSandbox(o.stateDirectory(), o.acceptBestEffortNetwork()));
        return List.of();
    }

    /** 第一个探测可用的后端；没有 ⇒ {@code null}。 */
    public static Sandbox select(Options opts) {
        for (Sandbox s : candidates(opts)) if (s.probe().available()) return s;
        return null;
    }

    public static Sandbox select() {
        return select(null);
    }

    /** 各候选的探测结果（诊断用）。 */
    public static List<SandboxCapabilities> diagnose(Options opts) {
        return candidates(opts).stream().map(Sandbox::probe).toList();
    }
}
