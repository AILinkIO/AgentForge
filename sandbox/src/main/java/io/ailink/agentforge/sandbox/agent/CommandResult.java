package io.ailink.agentforge.sandbox.agent;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.orchestrator.OutcomeStatus;

/**
 * 执行结果（给工具回模型）。
 *
 * @param exitCode   退出码；没跑（被拒）⇒ 126；超时 ⇒ 124
 * @param timedOut   超时被杀
 * @param durationMs 用时
 * @param output     交错的全文（UTF-8 字节；超过上限时头尾保留、中间换成截断标记）
 * @param omitted    {@code output} 被截断过
 * @param stdout     标准输出全文
 * @param stderr     标准错误全文
 * @param rejected   没跑：禁止 / 审批未过 / 沙盒不可用
 * @param reason     拒绝理由或沙盒拒判理由（带码；正常完成 ⇒ {@code null}）
 * @param status     编排状态（COMPLETED / DENIED / ESCALATED / REJECTED / ABORTED）
 * @param sandbox    这次实际用的沙盒后端名（不带沙盒 ⇒ {@code null}）
 */
public record CommandResult(int exitCode, boolean timedOut, long durationMs, byte[] output, boolean omitted, String stdout, String stderr,
                            boolean rejected, Message reason, OutcomeStatus status, String sandbox) {
    /** {@code output} 按 UTF-8 解码（截断处可能有替换字符）。 */
    public String outputText() {
        return new String(output, java.nio.charset.StandardCharsets.UTF_8);
    }
}
