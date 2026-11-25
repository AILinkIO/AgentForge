package io.ailink.agentforge.agent.tools.shell;

import io.ailink.agentforge.agent.model.CancelToken;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * run-command 真正起子进程的那一层（coxswain {@code (keel sys process-reap)}）。可换：
 * 默认 {@link LocalCommandRunner} 直接 {@link ProcessBuilder}；{@code sandbox} 模块可以给一份
 * 把命令关进沙箱的实现，经 {@link ShellSettings#runner(CommandRunner)} 装上。
 *
 * <p>argv 已经是包好的 shell 调用（{@code [$SHELL, -c, "<cmd>; _code=$?; pwd -P >| <cwdfile>; exit $_code"]}）；
 * 原始命令另给一份（{@link Request#command}）供沙盒做策略判断。
 *
 * <p>自带审批的执行器（{@link #approvesCommands()} 为 true，如 {@code agent.sandbox.SandboxedCommandRunner}）自己走
 * execpolicy → 沙盒 → 拦下了再问；否则会话的 gate 在调用前按 execpolicy 判一次。
 */
public interface CommandRunner {

    /**
     * @param command       模型写的原命令（未经包裹）
     * @param argv          要执行的 argv
     * @param cwd           工作目录（通过 spawn 传，绝不 chdir 本进程）
     * @param timeoutMs     到点杀（连同子孙进程）
     * @param maxBytes      每流捕获上限；触到时**不杀进程**，只停止收集并标记截断
     * @param cancel        按停时也杀
     * @param escalated     模型要求出沙盒（{@code sandbox_permissions: "require_escalated"}；仍要审批）
     * @param justification 模型给用户的理由（审批时显示；可空）
     * @param prefixRule    模型提议的「以后免审批」前缀（可空）
     * @param toolUseId     哪次工具调用（审批请求带上它；可空）
     */
    record Request(String command, List<String> argv, Path cwd, long timeoutMs, long maxBytes, CancelToken cancel, boolean escalated,
                   String justification, List<String> prefixRule, String toolUseId) {
        public Request(String command, List<String> argv, Path cwd, long timeoutMs, long maxBytes, CancelToken cancel) {
            this(command, argv, cwd, timeoutMs, maxBytes, cancel, false, null, null, null);
        }
    }

    record Result(int exitCode, byte[] stdout, byte[] stderr, boolean timedOut,
                  boolean stdoutTruncated, boolean stderrTruncated) { }

    /** 前台跑，等它结束（或超时 / 按停被杀）。 */
    Result run(Request req) throws Exception;

    /** 后台进程的句柄：pid + 退出时完成的 future（值为退出码）。 */
    interface Background {
        long pid();

        CompletableFuture<Integer> onExit();
    }

    /** 后台起：stdout/stderr 已由命令自己重定向到日志，这里不接管道、不等。 */
    Background spawn(String command, List<String> argv, Path cwd) throws Exception;

    /** 后台起（带审批相关的参数）；缺省忽略它们。 */
    default Background spawn(Request req) throws Exception { return spawn(req.command(), req.argv(), req.cwd()); }

    /** 这个执行器自己做命令审批（execpolicy + 沙盒 + 拦下再问）。缺省 false：会话在调用前替它判。 */
    default boolean approvesCommands() { return false; }
}
