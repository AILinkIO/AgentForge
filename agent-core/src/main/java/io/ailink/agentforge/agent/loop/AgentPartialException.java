package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;

import java.util.List;

/**
 * 循环半路出错 / 被按停：带着"到出错为止的完整历史"往上抛，会话层据此把已经跑过的工具
 * 封存下来（"它已经改过哪些文件"是要不要重来的唯一依据）。
 */
public class AgentPartialException extends RuntimeException {
    private final List<Message> messages;
    private final Usage usage;
    private final boolean cancelled;

    public AgentPartialException(List<Message> messages, Usage usage, Throwable cause, boolean cancelled) {
        super(cause == null ? (cancelled ? "已按停" : "agent 循环出错") : cause.getMessage(), cause);
        this.messages = List.copyOf(messages);
        this.usage = usage;
        this.cancelled = cancelled;
    }

    public List<Message> messages() { return messages; }

    public Usage usage() { return usage; }

    public boolean cancelled() { return cancelled; }
}
