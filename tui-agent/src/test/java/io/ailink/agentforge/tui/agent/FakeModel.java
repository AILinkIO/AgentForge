package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.loop.DeltaListener;
import io.ailink.agentforge.agent.loop.ModelCaller;
import io.ailink.agentforge.agent.loop.ModelTurn;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.tool.AgentTool;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/** 测试用的假模型（照 agent-core 测试里的 FakeCaller 抄的）：按脚本一步步回答（每一步收请求、回一次回答），记下每次收到的请求。不联网。 */
public final class FakeModel implements ModelCaller {

    @FunctionalInterface
    public interface Reply { ModelTurn reply(ChatRequest req, DeltaListener onDelta, CancelToken cancel); }

    public final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Reply> script = new ArrayDeque<>();
    private Function<ChatRequest, Reply> router;

    public FakeModel then(Reply r) { synchronized (script) { script.add(r); } return this; }

    public FakeModel thenText(String s) { return then((q, d, c) -> text(s)); }

    public FakeModel thenTool(String id, String name, Object... kv) { return then((q, d, c) -> tool(id, name, kv)); }

    /** 不按脚本、按请求内容回答（子 agent / 压缩那种要分辨"谁在问"的场合）。 */
    public FakeModel route(Function<ChatRequest, Reply> f) { this.router = f; return this; }

    @Override
    public ModelTurn call(ChatRequest req, DeltaListener onDelta, CancelToken cancel) {
        cancel.throwIfCancelled();
        requests.add(req);
        if (router != null) {
            Reply r = router.apply(req);
            if (r != null) return r.reply(req, onDelta, cancel);
        }
        Reply r;
        synchronized (script) { r = script.poll(); }
        if (r == null) return text("（脚本用完了）");
        return r.reply(req, onDelta, cancel);
    }

    public ChatRequest last() { return requests.getLast(); }

    public static final Usage USAGE = new Usage(100, 20, 0, 0, 0);

    public static ModelTurn text(String s) { return text(s, USAGE); }

    public static ModelTurn text(String s, Usage u) {
        return new ModelTurn(new Message(Role.ASSISTANT, List.of(new Block.Text(s))), u, "end_turn", "fake");
    }

    public static ModelTurn tool(String id, String name, Object... kv) { return toolU(USAGE, id, name, kv); }

    public static ModelTurn toolU(Usage u, String id, String name, Object... kv) {
        ObjectNode in = AgentTool.JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) in.set((String) kv[i], AgentTool.JSON.valueToTree(kv[i + 1]));
        return new ModelTurn(new Message(Role.ASSISTANT, List.of(new Block.ToolUse(id, name, in))), u, "tool_use", "fake");
    }

    /** 等到按停才回（模拟一次在飞的请求）。 */
    public static Reply blockUntilCancelled() {
        return (q, d, c) -> {
            java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
            c.onCancel(l::countDown);
            try { l.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new CancelToken.CancelledException();
        };
    }

    /** 请求里最后一条 user 消息的文本（含 tool_result 的话是空串）。 */
    public static String lastUserText(ChatRequest req) {
        List<Message> ms = req.messages();
        for (int i = ms.size() - 1; i >= 0; i--) if (ms.get(i).isUser()) return ms.get(i).text();
        return "";
    }
}
