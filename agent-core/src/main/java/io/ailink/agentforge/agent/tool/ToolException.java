package io.ailink.agentforge.agent.tool;

/**
 * 工具执行失败：消息原样作为 is_error 的 tool_result 交给模型。
 *
 * <p>与 coxswain 里 {@code (error 'agent-tools "…")} 同一个角色 —— 文本是写给模型看的，
 * 要说清"错在哪、下一步怎么做"。
 */
public class ToolException extends RuntimeException {
    public ToolException(String message) { super(message); }

    public ToolException(String message, Throwable cause) { super(message, cause); }
}
