package io.ailink.agentforge.agent.session;

/**
 * 会话事件的接收方（UI / 存盘 / 日志）。
 *
 * <p>⚠️ 回调跑在**会话的工作线程**上（agent 那条虚拟线程，或子 agent 的工具线程），不是 UI 线程：要碰界面就自己
 * 投递回 UI 线程。回调要快，抛出来的异常会被吞掉（一条界面通知不该掀掉正在跑的那一轮）。
 */
@FunctionalInterface
public interface SessionListener {
    void onEvent(AgentSession session, SessionEvent event);
}
