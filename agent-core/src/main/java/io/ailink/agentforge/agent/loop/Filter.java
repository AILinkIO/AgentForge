package io.ailink.agentforge.agent.loop;

/**
 * 过滤器：包住"问模型"与"跑工具"两个点（coxswain {@code make-filter} 的 'chat / 'tool）。
 * 默认实现直接往下传；排在前面的包在外层。
 */
public interface Filter {
    default String name() { return getClass().getSimpleName(); }

    default ModelTurn chat(ChatRequest req, ChatChain chain) { return chain.next(req); }

    default ToolResult tool(ToolRequest req, ToolChain chain) { return chain.next(req); }

    @FunctionalInterface
    interface ChatChain { ModelTurn next(ChatRequest req); }

    @FunctionalInterface
    interface ToolChain { ToolResult next(ToolRequest req); }
}
