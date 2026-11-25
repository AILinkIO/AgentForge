package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.model.CancelToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * JSON-RPC 2.0 的公共那一半：按 id 配对应答、处理对面发来的请求 / 通知、超时与按停。
 *
 * <p>子类只管"怎么把一条消息送出去"（{@link #send}）以及"收到一条就交给 {@link #dispatch}"。
 * stdio（NDJSON 一行一条）、Streamable HTTP（应答在 POST 的回包里，JSON 或 SSE）、legacy HTTP+SSE
 * （应答从另一条 GET 流回来）都是这一个形状。
 */
abstract class RpcTransport implements AutoCloseable {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile String closedReason;
    private volatile Consumer<String> notificationHook = m -> { };

    /** 送出一条消息。失败可以直接抛，也可以稍后对相应 id 调 {@link #failPending}。 */
    protected abstract void send(ObjectNode msg) throws Exception;

    /** 协议版本谈好之后告诉传输层（HTTP 要把它放进 MCP-Protocol-Version 头）。 */
    void protocolVersion(String v) { }

    /** 对面发来的通知（method 名）。 */
    void onNotification(Consumer<String> hook) { this.notificationHook = hook == null ? m -> { } : hook; }

    boolean isClosed() { return closedReason != null; }

    String closedReason() { return closedReason; }

    /** 发一个请求并等应答的 result。 */
    JsonNode request(String method, JsonNode params, long timeoutMs, CancelToken cancel) {
        if (closedReason != null) throw new McpException.Closed(closedReason);
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        ObjectNode msg = JsonNodeFactory.instance.objectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        Runnable unhook = () -> { };
        if (cancel != null) {
            unhook = cancel.onCancel(() -> {
                if (f.completeExceptionally(new CancelToken.CancelledException())) sendCancelled(id, "按停");
            });
        }
        try {
            send(msg);
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            sendCancelled(id, "超时");
            throw new McpException.Timeout(method + " 等了 " + timeoutMs + " ms 没有应答");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) throw r;
            throw new McpException(String.valueOf(c.getMessage()), c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelToken.CancelledException();
        } catch (McpException | CancelToken.CancelledException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException(e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        } finally {
            pending.remove(id);
            unhook.run();
        }
    }

    void notify(String method, JsonNode params) {
        if (closedReason != null) return;
        ObjectNode msg = JsonNodeFactory.instance.objectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        if (params != null) msg.set("params", params);
        try { send(msg); } catch (Exception ignored) { }
    }

    private void sendCancelled(long id, String why) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("requestId", id);
        p.put("reason", why);
        notify("notifications/cancelled", p);
    }

    /** 收到一条（或一批）消息。 */
    protected void dispatch(JsonNode msg) {
        if (msg == null) return;
        if (msg.isArray()) {
            for (JsonNode m : msg) dispatch(m);
            return;
        }
        JsonNode id = msg.get("id");
        JsonNode method = msg.get("method");
        if (method != null && method.isString()) {
            if (id == null || id.isNull()) {
                try { notificationHook.accept(method.asString()); } catch (RuntimeException ignored) { }
            } else {
                answerServerRequest(id, method.asString());
            }
            return;
        }
        if (id == null || !id.canConvertToLong()) return;
        CompletableFuture<JsonNode> f = pending.get(id.asLong());
        if (f == null) return;
        JsonNode err = msg.get("error");
        if (err != null && !err.isNull()) {
            JsonNode m = err.get("message");
            JsonNode c = err.get("code");
            String text = m != null && m.isString() ? m.asString() : err.toString();
            JsonNode data = err.get("data");
            if (data != null && !data.isNull()) text += "（" + (data.isString() ? data.asString() : data.toString()) + "）";
            f.completeExceptionally(new McpException.RpcError(c != null && c.canConvertToInt() ? c.asInt() : 0, text));
        } else {
            JsonNode r = msg.get("result");
            f.complete(r == null ? JsonNodeFactory.instance.objectNode() : r);
        }
    }

    /** 对面发来的请求：只答 ping；sampling / roots / elicitation 都没做，回 method not found。 */
    private void answerServerRequest(JsonNode id, String method) {
        ObjectNode msg = JsonNodeFactory.instance.objectNode();
        msg.put("jsonrpc", "2.0");
        msg.set("id", id);
        if ("ping".equals(method)) {
            msg.putObject("result");
        } else {
            ObjectNode e = msg.putObject("error");
            e.put("code", -32601);
            e.put("message", "客户端不支持 " + method);
        }
        try { send(msg); } catch (Exception ignored) { }
    }

    protected void failPending(long id, RuntimeException e) {
        CompletableFuture<JsonNode> f = pending.get(id);
        if (f != null) f.completeExceptionally(e);
    }

    /** 连接没了：标记、让所有在等的请求立刻失败（不然它们要等到超时）。 */
    protected void markClosed(String reason) {
        if (closedReason == null) closedReason = reason;
        McpException.Closed e = new McpException.Closed(closedReason);
        for (CompletableFuture<JsonNode> f : pending.values()) f.completeExceptionally(e);
    }

    @Override
    public abstract void close();
}
