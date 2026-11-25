package io.ailink.agentforge.sandbox;

/**
 * 一个平台沙盒后端的探测结果。
 *
 * @param name              后端名（{@code bwrap} / {@code landlock} / {@code win-appcontainer} / {@code win-restricted}）
 * @param available         本机能不能用
 * @param unavailableReason 不能用的原因（带码）；能用 ⇒ {@code null}
 * @param usesSeccomp       装了 seccomp ⇒ 子进程被 SIGSYS 杀掉（128+31）也算沙盒拒判
 * @param networkIsolated   真能断网（Windows 受限令牌只能尽力 ⇒ false）
 */
public record SandboxCapabilities(String name, boolean available, Message unavailableReason, boolean usesSeccomp,
                                  boolean networkIsolated) {

    /** 可用的能力（无 seccomp、能断网）。 */
    public static SandboxCapabilities available(String name) {
        return new SandboxCapabilities(name, true, null, false, true);
    }

    /** 不可用，带原因。 */
    public static SandboxCapabilities unavailable(String name, Message reason) {
        return new SandboxCapabilities(name, false, reason, false, true);
    }

    public SandboxCapabilities withSeccomp(boolean value) {
        return new SandboxCapabilities(name, available, unavailableReason, value, networkIsolated);
    }

    public SandboxCapabilities withNetworkIsolated(boolean value) {
        return new SandboxCapabilities(name, available, unavailableReason, usesSeccomp, value);
    }
}
