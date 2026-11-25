package io.ailink.agentforge.sandbox;

import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

/**
 * 平台沙盒的契约（≙ C# ISandbox）。{@link #probe()} 报本机能不能用（实现缓存结果）；{@link #wrap} 包装一个内层执行后端
 * （通常 {@link io.ailink.agentforge.sandbox.exec.LocalExecBackend}），经它起的进程受策略管束。
 * 沙盒不自己起进程：改写请求（如前置 {@code bwrap … --}）再交给内层 —— 这样沙盒可以叠加、也便于测试。
 */
public interface Sandbox {
    /** 探测结果（实现应缓存：探测可能要起进程）。 */
    SandboxCapabilities probe();

    /**
     * 包装内层后端：经它起的进程按 {@code policy} 受限；策略里的相对路径与特殊路径按 {@code context} 解析
     * （命令自己的工作目录缺省同 {@code context.cwd()}）。后端不可用 / 策略超出能力 ⇒ {@link SandboxException}。
     */
    ExecBackend wrap(ExecBackend inner, SandboxPolicy policy, PathContext context);
}
