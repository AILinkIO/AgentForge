package io.ailink.agentforge.sandbox.exec;

/**
 * 一次执行的结果（拒判看它）。
 *
 * @param exitCode 退出码；超时 ⇒ {@link Exec#TIMEOUT_EXIT_CODE}
 * @param stdout   标准输出（UTF-8 解码，坏字节替换）
 * @param stderr   标准错误
 * @param output   stdout 与 stderr 按到达次序交错的全文
 * @param timedOut 到了时限被杀
 */
public record ExecResult(int exitCode, String stdout, String stderr, String output, boolean timedOut) {
    public ExecResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        output = output == null ? "" : output;
    }

    public static ExecResult of(int exitCode) {
        return new ExecResult(exitCode, "", "", "", false);
    }

    public static ExecResult of(int exitCode, String stdout, String stderr, String output) {
        return new ExecResult(exitCode, stdout, stderr, output, false);
    }
}
