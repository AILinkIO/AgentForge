package io.ailink.agentforge.agent.approval;

import java.util.Objects;

/**
 * 审批请求上可选的一项（界面照着画按钮 / 选项列表）。
 *
 * @param decision 选它就回这个决定
 * @param key      建议的快捷键（与 Codex 一致：y 放行 · a 本会话 · p 以后都 · d 不做继续 · b 以后都拒 · n 停下来）
 * @param label    文案
 */
public record ApprovalChoice(ReviewDecision.Kind decision, String key, String label) {
    public ApprovalChoice {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
    }
}
