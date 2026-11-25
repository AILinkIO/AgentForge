package io.ailink.agentforge.sandbox.exec;

/**
 * 执行后端的能力声明。
 *
 * @param name        后端名（{@code local} / {@code bwrap} / …）
 * @param sandboxed   经它起的进程受沙盒管束
 * @param supportsTty 能给伪终端
 */
public record ExecCapabilities(String name, boolean sandboxed, boolean supportsTty) {
}
