package io.ailink.agentforge.agent.mcp;

/**
 * MCP 事件。两种，<b>分开</b>（消费者不同；合成一条的话状态栏每闪一下都要重算工具集）：
 *
 * <ul>
 *   <li>{@link Kind#STATUS} —— 状态栏 / 列表要它</li>
 *   <li>{@link Kind#CATALOG} —— 目录真的变了（到手 / 停用 / 删掉）⇒ 宿主把新工具表推给会话（下一轮生效）</li>
 * </ul>
 *
 * @param server 服务器名；{@code ""} = 整张表（syncSpecs）
 * @param status STATUS 事件里的新状态；CATALOG 事件里为 null
 */
public record McpEvent(Kind kind, String server, McpStatus status) {
    public enum Kind { STATUS, CATALOG }

    /** 监听器。⚠️ 跑在连接线程上；抛了会被吞掉（一条界面通知不该掀掉正在连接的那条线程）。 */
    @FunctionalInterface
    public interface Listener { void onEvent(McpEvent e); }
}
