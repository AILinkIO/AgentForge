package io.ailink.agentforge.agent.tool;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次工具调用的上下文：工作区、按停令牌、这次调用的 id，以及会话挂进来的回调。
 *
 * <p>{@code services} 装会话侧的能力（"附一张图给对话"、"问用户一个选择题"、"派子 agent"
 * 等），工具按接口类型取。拿不到的话说明宿主没接 —— 工具要给出能读懂的错误。
 */
public final class ToolEnv {
    private final Workspace workspace;
    private final CancelToken cancel;
    private final String toolUseId;
    private final String sessionId;
    private final Map<Class<?>, Object> services;

    public ToolEnv(Workspace workspace, CancelToken cancel, String toolUseId, String sessionId,
                   Map<Class<?>, Object> services) {
        this.workspace = workspace;
        this.cancel = cancel == null ? new CancelToken() : cancel;
        this.toolUseId = toolUseId;
        this.sessionId = sessionId;
        this.services = services == null ? new ConcurrentHashMap<>() : services;
    }

    public static ToolEnv of(Workspace ws) { return new ToolEnv(ws, new CancelToken(), "t0", "s0", null); }

    public Workspace workspace() { return workspace; }

    public CancelToken cancel() { return cancel; }

    public String toolUseId() { return toolUseId; }

    public String sessionId() { return sessionId; }

    @SuppressWarnings("unchecked")
    public <T> T service(Class<T> type) { return (T) services.get(type); }

    public <T> ToolEnv withService(Class<T> type, T impl) {
        var m = new ConcurrentHashMap<>(services);
        m.put(type, impl);
        return new ToolEnv(workspace, cancel, toolUseId, sessionId, m);
    }

    public ToolEnv withCall(String id, CancelToken c) {
        return new ToolEnv(workspace, c, id, sessionId, services);
    }

    /** 会话侧：把一块媒体（图片 / 文档）附进对话（read-file 读图时用）。 */
    public interface Attacher { void attach(String name, Block block); }
}
