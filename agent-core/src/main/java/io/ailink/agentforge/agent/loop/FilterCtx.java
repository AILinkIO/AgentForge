package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一趟循环里过滤器共享的上下文：这一趟允许的工具、第几轮、以及"下一轮开头要并进去的消息"
 * （排队的用户输入就是从这里并进去的 —— coxswain 的 {@code filter-ctx-push-message!}）。
 */
public final class FilterCtx {
    private final List<AgentTool> allowedTools;
    private final List<Message> pushed = new ArrayList<>();
    private final Map<String, Object> attrs = new ConcurrentHashMap<>();
    private volatile int round;

    public FilterCtx(List<AgentTool> allowedTools) { this.allowedTools = List.copyOf(allowedTools); }

    public List<AgentTool> allowedTools() { return allowedTools; }

    public AgentTool findTool(String name) {
        for (AgentTool t : allowedTools) if (t.name().equals(name)) return t;
        return null;
    }

    public int round() { return round; }

    void setRound(int r) { this.round = r; }

    /** 下一轮发请求之前追加到历史末尾。 */
    public synchronized void pushMessage(Message m) { pushed.add(m); }

    synchronized List<Message> drainPushedPeek() { return List.copyOf(pushed); }

    synchronized List<Message> drainPushed() {
        List<Message> out = List.copyOf(pushed);
        pushed.clear();
        return out;
    }

    public Map<String, Object> attrs() { return attrs; }
}
