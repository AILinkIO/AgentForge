package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.List;

/** 发给模型的一次请求（过滤器可以换掉其中任何一项再往下传）。 */
public record ChatRequest(String system, List<Message> messages, List<AgentTool> tools,
                          RequestOptions options, FilterCtx ctx) {
    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    public ChatRequest withMessages(List<Message> ms) { return new ChatRequest(system, ms, tools, options, ctx); }

    public ChatRequest withSystem(String s) { return new ChatRequest(s, messages, tools, options, ctx); }

    public ChatRequest withTools(List<AgentTool> ts) { return new ChatRequest(system, messages, ts, options, ctx); }

    public ChatRequest withOptions(RequestOptions o) { return new ChatRequest(system, messages, tools, o, ctx); }
}
