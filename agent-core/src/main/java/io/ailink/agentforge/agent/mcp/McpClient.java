package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.model.CancelToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条 MCP 连接（协议那一层）：initialize 握手、tools/list（翻页）、tools/call。
 *
 * <p>⚠️ <b>clientInfo 必须自己给</b> —— coxswain 拿真服务器撞出来的：不给的话 initialize 协议非法，
 * 服务器当场拒，而症状只是"握手到超时"，看起来完全像 npx 在下载。离线判据测不出（假服务器不校验它）。
 */
final class McpClient implements AutoCloseable {
    /** 我们先报的版本；对面回哪个就用哪个（宽松：不认识的版本也接着走，见 coxswain "2024-11-05 不在表里"那一条）。 */
    static final String LATEST_PROTOCOL = "2025-06-18";

    private final RpcTransport transport;
    private volatile String protocolVersion;
    private volatile String instructions;
    private volatile String serverName;

    McpClient(RpcTransport transport) { this.transport = transport; }

    RpcTransport transport() { return transport; }

    String instructions() { return instructions; }

    String serverName() { return serverName; }

    String protocolVersion() { return protocolVersion; }

    /** 握手：initialize → notifications/initialized。 */
    void initialize(String clientName, String clientVersion, long timeoutMs) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("protocolVersion", LATEST_PROTOCOL);
        p.putObject("capabilities");
        ObjectNode ci = p.putObject("clientInfo");
        ci.put("name", clientName);
        ci.put("version", clientVersion);
        JsonNode r = transport.request("initialize", p, timeoutMs, null);
        JsonNode v = r.get("protocolVersion");
        protocolVersion = v != null && v.isString() ? v.asString() : LATEST_PROTOCOL;
        JsonNode ins = r.get("instructions");
        instructions = ins != null && ins.isString() && !ins.asString().isBlank() ? ins.asString() : null;
        JsonNode si = r.get("serverInfo");
        if (si != null && si.get("name") != null) serverName = si.get("name").asString();
        transport.protocolVersion(protocolVersion);
        transport.notify("notifications/initialized", null);
    }

    /** 全部工具描述（按 nextCursor 翻页）。 */
    List<JsonNode> listTools(long timeoutMs) {
        List<JsonNode> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 100; page++) {
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            if (cursor != null) p.put("cursor", cursor);
            JsonNode r = transport.request("tools/list", p, timeoutMs, null);
            JsonNode tools = r.get("tools");
            if (tools != null && tools.isArray()) for (JsonNode t : tools) out.add(t);
            JsonNode next = r.get("nextCursor");
            if (next == null || !next.isString() || next.asString().isEmpty()) break;
            cursor = next.asString();
        }
        return out;
    }

    JsonNode callTool(String remoteName, JsonNode args, long timeoutMs, CancelToken cancel) {
        ObjectNode p = JsonNodeFactory.instance.objectNode();
        p.put("name", remoteName);
        p.set("arguments", args == null ? JsonNodeFactory.instance.objectNode() : args);
        return transport.request("tools/call", p, timeoutMs, cancel);
    }

    /**
     * 远程：先试 Streamable HTTP；initialize 回 400/404/405 就回落 legacy SSE（opencode 同，
     * keel 那层自己做的回落）。返回已经握过手的连接。
     */
    static McpClient connectHttp(HttpClient http, URI uri, Map<String, String> headers, String clientName,
                                 String clientVersion, long timeoutMs) {
        HttpTransport.Streamable st = new HttpTransport.Streamable(http, uri, headers);
        McpClient c = new McpClient(st);
        try {
            c.initialize(clientName, clientVersion, timeoutMs);
            return c;
        } catch (McpException.HttpStatus e) {
            st.close();
            if (e.isAuth() || !(e.status() == 400 || e.status() == 404 || e.status() == 405)) throw e;
        } catch (RuntimeException e) {
            st.close();
            throw e;
        }
        HttpTransport.LegacySse sse = HttpTransport.LegacySse.open(http, uri, headers, timeoutMs);
        McpClient c2 = new McpClient(sse);
        try {
            c2.initialize(clientName, clientVersion, timeoutMs);
            return c2;
        } catch (RuntimeException e) {
            sse.close();
            throw e;
        }
    }

    @Override
    public void close() { transport.close(); }
}
