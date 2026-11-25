package io.ailink.agentforge.sandbox.orchestrator;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.SandboxCapabilities;

/**
 * 编排结果。
 *
 * @param status   状态
 * @param output   最后一次尝试的输出（没跑 ⇒ {@code null}）
 * @param attempts 跑了几次（0 / 1 / 2）
 * @param reason   拒绝理由（带码）
 * @param sandbox  最后相关的沙盒能力 / 探测结果
 */
public record Outcome<T>(OutcomeStatus status, T output, int attempts, Message reason, SandboxCapabilities sandbox) {
}
