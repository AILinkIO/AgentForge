package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.exec.ExecResult;

/**
 * 交给审批者的请求。
 *
 * @param key         审批键（会话缓存按它记；可空 ⇒ 不缓存）
 * @param reason      为什么要问（可空）
 * @param retryReason 非空 ⇒ 这是「沙盒拒了，不带沙盒重跑吗」
 * @param denial      重跑审批时，第一次（沙盒里）的执行结果
 */
public record ApprovalRequest(String key, Message reason, Message retryReason, ExecResult denial) {
    public boolean isRetry() {
        return retryReason != null;
    }

    public ApprovalRequest withKey(String value) {
        return new ApprovalRequest(value, reason, retryReason, denial);
    }

    public ApprovalRequest withRetryReason(Message value) {
        return new ApprovalRequest(key, reason, value, denial);
    }
}
