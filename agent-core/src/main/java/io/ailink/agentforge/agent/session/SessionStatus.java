package io.ailink.agentforge.agent.session;

/**
 * 会话状态机（coxswain {@code agent-session-status}）。
 *
 * <pre>
 *   IDLE ──send──▶ THINKING ⇄ STREAMING ⇄ AWAITING_APPROVAL / AWAITING_QUESTION ──▶ DONE
 *                      └──────── 出错 ──▶ ERROR          按停 ──▶ IDLE
 *   COMPACTING：自动 / 手动压缩那一问在跑（也算忙：send 排队）
 * </pre>
 */
public enum SessionStatus {
    IDLE, THINKING, STREAMING, AWAITING_APPROVAL, AWAITING_QUESTION, COMPACTING, ERROR, DONE;

    /** 忙：这时 send 进来的话排队。 */
    public boolean busy() { return this != IDLE && this != ERROR && this != DONE; }
}
