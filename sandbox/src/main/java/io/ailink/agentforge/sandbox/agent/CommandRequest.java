package io.ailink.agentforge.sandbox.agent;

import java.util.List;
import java.util.Map;

/**
 * 智能体「执行命令」工具的一次请求（框架无关）。
 *
 * @param argv        {@code [程序, 参数…]}（⛔ 拼 shell 串；要 shell 就显式 {@code ["/bin/sh", "-c", script]}）
 * @param cwd         工作目录（可空 ⇒ 上下文的 cwd）
 * @param env         环境覆盖（值 null = 删除；可空）
 * @param timeoutMs   时限（可空 ⇒ 用运行器的缺省）
 * @param escalated   模型显式要求出沙盒（Codex {@code sandbox_permissions: "require_escalated"}）；仍要审批
 * @param approvalKey 会话审批键（可空 ⇒ 按工具名 + argv 生成）
 */
public record CommandRequest(List<String> argv, String cwd, Map<String, String> env, Long timeoutMs, boolean escalated, String approvalKey) {
    public CommandRequest {
        argv = argv == null ? List.of() : List.copyOf(argv);
    }

    public static CommandRequest of(List<String> argv) {
        return new CommandRequest(argv, null, null, null, false, null);
    }

    public CommandRequest withTimeoutMs(Long v) {
        return new CommandRequest(argv, cwd, env, v, escalated, approvalKey);
    }

    public CommandRequest withCwd(String v) {
        return new CommandRequest(argv, v, env, timeoutMs, escalated, approvalKey);
    }
}
