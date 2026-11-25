package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.tool.AgentTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Anthropic Messages API 的原生 caller（SSE 流式）。
 *
 * <p>为什么在 Spring AI 之外还要这一个：Spring AI 2.0 不把 thinking 块（含签名）回传，而
 * "开思考 + 工具循环"时 Anthropic 要求上一条 assistant 的 thinking 原样带回；另外 prompt cache
 * 的断点要按 coxswain 的规矩打在 system / 工具表末尾 / 最后一条消息上，工具结果里的图片与 PDF
 * 也要作为 tool_result 的内容块发出去。这三件 Spring AI 的抽象都接不住。
 *
 * <p>思考档位：{@code low}/{@code medium}/{@code high}/{@code max} → budget 4k/10k/24k/32k；
 * {@code adaptive} → {@code {"type":"adaptive"}}；null/off 不发。
 */
public final class AnthropicHttpCaller implements ModelCaller {
    private final String baseUrl;
    private final String apiKey;
    private final boolean bearer;
    private final Map<String, String> extraHeaders;
    private final HttpClient http;
    private final Duration connectTimeout;

    public AnthropicHttpCaller(String baseUrl, String apiKey, boolean bearer, Map<String, String> extraHeaders,
                               Duration connectTimeout) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl).replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.bearer = bearer;
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(this.connectTimeout).build();
    }

    @Override
    public ModelTurn call(ChatRequest req, DeltaListener onDelta, CancelToken cancel) {
        cancel.throwIfCancelled();
        ObjectNode body = encode(req);
        String url = baseUrl.endsWith("/v1") ? baseUrl + "/messages" : baseUrl + "/v1/messages";
        HttpRequest.Builder hb = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (bearer) hb.header("authorization", "Bearer " + apiKey);
        else hb.header("x-api-key", apiKey);
        extraHeaders.forEach(hb::header);
        HttpResponse<InputStream> resp;
        try {
            var fut = http.sendAsync(hb.build(), HttpResponse.BodyHandlers.ofInputStream());
            Runnable undo = cancel.onCancel(() -> fut.cancel(true));
            try {
                resp = fut.get();
            } finally {
                undo.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelToken.CancelledException();
        } catch (java.util.concurrent.CancellationException e) {
            throw new CancelToken.CancelledException();
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            String kind = c instanceof java.net.http.HttpConnectTimeoutException ? "timeout" : "io";
            throw new ModelCallException(kind, 0, -1, "连不上 " + baseUrl + "：" + c, c);
        }
        if (resp.statusCode() / 100 != 2) {
            String err;
            try (InputStream in = resp.body()) { err = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException e) { err = String.valueOf(e); }
            long ra = resp.headers().firstValue("retry-after").map(AnthropicHttpCaller::parseRetryAfter).orElse(-1L);
            String kind = err.toLowerCase().contains("prompt is too long") ? "context-overflow" : "http";
            throw new ModelCallException(kind, resp.statusCode(), ra, "HTTP " + resp.statusCode() + "：" + err, null);
        }
        try (InputStream in = resp.body()) {
            Runnable undo = cancel.onCancel(() -> { try { in.close(); } catch (IOException ignored) { } });
            try {
                return readStream(in, onDelta, cancel);
            } finally {
                undo.run();
            }
        } catch (IOException e) {
            if (cancel.isCancelled()) throw new CancelToken.CancelledException();
            throw new ModelCallException("io", 0, -1, "读流式回答时断开：" + e.getMessage(), e);
        }
    }

    private static long parseRetryAfter(String v) {
        try { return (long) (Double.parseDouble(v.trim()) * 1000); } catch (NumberFormatException e) { return -1; }
    }

    // ── 编码 ─────────────────────────────────────────────────────────────

    static ObjectNode encode(ChatRequest req) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        RequestOptions o = req.options();
        ObjectNode body = f.objectNode();
        body.put("model", o.model());
        int max = o.maxTokens() == null ? 8192 : o.maxTokens();
        body.put("stream", true);
        boolean cache = o.promptCache();
        if (req.system() != null && !req.system().isBlank()) {
            ArrayNode sys = body.putArray("system");
            ObjectNode t = sys.addObject().put("type", "text").put("text", req.system());
            if (cache) t.putObject("cache_control").put("type", "ephemeral");
        }
        if (!req.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (AgentTool t : req.tools()) {
                tools.addObject().put("name", t.name()).put("description", t.description()).set("input_schema", t.schema());
            }
            if (cache) ((ObjectNode) tools.get(tools.size() - 1)).putObject("cache_control").put("type", "ephemeral");
        }
        String th = o.thinking();
        if (th != null && !th.isBlank() && !"off".equals(th) && !"none".equals(th)) {
            ObjectNode tc = body.putObject("thinking");
            if ("adaptive".equals(th)) tc.put("type", "adaptive");
            else {
                int budget = switch (th) { case "low", "minimal" -> 4000; case "medium" -> 10000; case "high" -> 24000;
                    case "max", "xhigh" -> 32000; default -> parseIntOr(th, 10000); };
                budget = Math.max(1024, Math.min(budget, max - 1024));
                if (budget >= 1024 && max > 2048) tc.put("type", "enabled").put("budget_tokens", budget);
                else body.remove("thinking");
            }
        }
        body.put("max_tokens", max);
        ArrayNode msgs = body.putArray("messages");
        List<Message> ms = req.messages();
        for (Message m : ms) {
            ArrayNode content = f.arrayNode();
            for (Block b : m.blocks()) {
                ObjectNode n = encodeBlock(b, m.role());
                if (n != null) content.add(n);
            }
            if (content.isEmpty()) content.addObject().put("type", "text").put("text", " ");
            // 相邻同角色合并（Anthropic 要求交替）
            if (!msgs.isEmpty() && msgs.get(msgs.size() - 1).get("role").asString().equals(m.role().wire())) {
                ArrayNode prev = (ArrayNode) msgs.get(msgs.size() - 1).get("content");
                // tool_result 必须排在 user 内容最前面
                if (m.role() == Role.USER && content.get(0).path("type").asString().equals("tool_result")) {
                    ArrayNode merged = f.arrayNode();
                    merged.addAll(content);
                    merged.addAll(prev);
                    ((ObjectNode) msgs.get(msgs.size() - 1)).set("content", merged);
                } else prev.addAll(content);
            } else {
                msgs.addObject().put("role", m.role().wire()).set("content", content);
            }
        }
        if (cache && !msgs.isEmpty()) {
            ArrayNode last = (ArrayNode) msgs.get(msgs.size() - 1).get("content");
            for (int i = last.size() - 1; i >= 0; i--) {
                ObjectNode b = (ObjectNode) last.get(i);
                String type = b.path("type").asString();
                if (!type.equals("thinking") && !type.equals("redacted_thinking")) {
                    b.putObject("cache_control").put("type", "ephemeral");
                    break;
                }
            }
        }
        return body;
    }

    private static int parseIntOr(String s, int d) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return d; }
    }

    private static ObjectNode encodeBlock(Block b, Role role) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return switch (b) {
            case Block.Text t -> t.text().isEmpty() ? null : f.objectNode().put("type", "text").put("text", t.text());
            case Block.Image i -> {
                ObjectNode n = f.objectNode().put("type", "image");
                n.putObject("source").put("type", "base64").put("media_type", i.mediaType()).put("data", i.data());
                yield n;
            }
            case Block.Document d -> {
                ObjectNode n = f.objectNode().put("type", "document");
                n.putObject("source").put("type", "base64").put("media_type", d.mediaType()).put("data", d.data());
                yield n;
            }
            case Block.Thinking th -> {
                if (role != Role.ASSISTANT) yield null;
                if (th.redacted()) yield f.objectNode().put("type", "redacted_thinking").put("data", th.text());
                if (th.signature() == null) yield null;   // 没签名的（别家模型产的）不能回传
                yield f.objectNode().put("type", "thinking").put("thinking", th.text()).put("signature", th.signature());
            }
            case Block.ToolUse u -> {
                ObjectNode n = f.objectNode().put("type", "tool_use").put("id", u.id()).put("name", u.name());
                n.set("input", u.input() == null ? f.objectNode() : u.input());
                yield n;
            }
            case Block.ToolResult r -> {
                ObjectNode n = f.objectNode().put("type", "tool_result").put("tool_use_id", r.toolUseId());
                ArrayNode c = n.putArray("content");
                for (Block x : r.content()) {
                    ObjectNode e = encodeBlock(x, Role.USER);
                    if (e != null) c.add(e);
                }
                if (c.isEmpty()) c.addObject().put("type", "text").put("text", "（无输出）");
                if (r.error()) n.put("is_error", true);
                yield n;
            }
        };
    }

    // ── 解码 SSE ─────────────────────────────────────────────────────────

    private static ModelTurn readStream(InputStream in, DeltaListener onDelta, CancelToken cancel) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        TreeMap<Integer, ObjectNode> blocks = new TreeMap<>();
        TreeMap<Integer, StringBuilder> partial = new TreeMap<>();
        long input = 0, output = 0, cacheRead = 0, cacheWrite = 0;
        String stop = null, model = null;
        String line;
        StringBuilder data = new StringBuilder();
        while ((line = r.readLine()) != null) {
            if (cancel.isCancelled()) throw new CancelToken.CancelledException();
            if (line.startsWith("data:")) { data.append(line.substring(5).trim()); continue; }
            if (!line.isEmpty() || data.isEmpty()) continue;
            JsonNode ev = AgentTool.JSON.readTree(data.toString());
            data.setLength(0);
            String type = ev.path("type").asString();
            switch (type) {
                case "message_start" -> {
                    JsonNode msg = ev.path("message");
                    model = msg.path("model").asString(null);
                    JsonNode u = msg.path("usage");
                    input = u.path("input_tokens").asLong(0);
                    cacheRead = u.path("cache_read_input_tokens").asLong(0);
                    cacheWrite = u.path("cache_creation_input_tokens").asLong(0);
                    output = u.path("output_tokens").asLong(0);
                }
                case "content_block_start" -> {
                    int idx = ev.path("index").asInt();
                    blocks.put(idx, ((ObjectNode) ev.path("content_block")).deepCopy());
                    partial.put(idx, new StringBuilder());
                }
                case "content_block_delta" -> {
                    int idx = ev.path("index").asInt();
                    JsonNode d = ev.path("delta");
                    ObjectNode b = blocks.get(idx);
                    if (b == null) break;
                    switch (d.path("type").asString()) {
                        case "text_delta" -> {
                            String t = d.path("text").asString("");
                            b.put("text", b.path("text").asString("") + t);
                            onDelta.onDelta("text", t);
                        }
                        case "thinking_delta" -> {
                            String t = d.path("thinking").asString("");
                            b.put("thinking", b.path("thinking").asString("") + t);
                            onDelta.onDelta("thinking", t);
                        }
                        case "signature_delta" -> b.put("signature", b.path("signature").asString("") + d.path("signature").asString(""));
                        case "input_json_delta" -> partial.get(idx).append(d.path("partial_json").asString(""));
                        default -> { }
                    }
                }
                case "message_delta" -> {
                    if (ev.path("delta").has("stop_reason")) stop = ev.path("delta").path("stop_reason").asString(null);
                    JsonNode u = ev.path("usage");
                    if (u.has("output_tokens")) output = u.path("output_tokens").asLong(output);
                    if (u.has("input_tokens")) input = u.path("input_tokens").asLong(input);
                    if (u.has("cache_read_input_tokens")) cacheRead = u.path("cache_read_input_tokens").asLong(cacheRead);
                    if (u.has("cache_creation_input_tokens")) cacheWrite = u.path("cache_creation_input_tokens").asLong(cacheWrite);
                }
                case "error" -> {
                    JsonNode e = ev.path("error");
                    String et = e.path("type").asString("");
                    int st = switch (et) { case "overloaded_error" -> 529; case "rate_limit_error" -> 429;
                        case "api_error" -> 500; default -> 400; };
                    String msg = e.path("message").asString(ev.toString());
                    throw new ModelCallException(msg.toLowerCase().contains("prompt is too long") ? "context-overflow" : "http",
                            st, -1, et + "：" + msg, null);
                }
                default -> { }
            }
        }
        List<Block> out = new ArrayList<>();
        for (var e : blocks.entrySet()) {
            ObjectNode b = e.getValue();
            switch (b.path("type").asString()) {
                case "text" -> out.add(new Block.Text(b.path("text").asString("")));
                case "thinking" -> out.add(new Block.Thinking(b.path("thinking").asString(""), b.path("signature").asString(null), false));
                case "redacted_thinking" -> out.add(new Block.Thinking(b.path("data").asString(""), null, true));
                case "tool_use" -> out.add(new Block.ToolUse(b.path("id").asString(), b.path("name").asString(),
                        SpringAiModelCaller.parseArgs(partial.get(e.getKey()).toString())));
                default -> { }
            }
        }
        if (stop == null) throw new ModelCallException("io", 0, -1, "流式回答没有收尾就断了", null);
        return new ModelTurn(new Message(Role.ASSISTANT, out), new Usage(input, output, cacheRead, cacheWrite, 0), stop, model);
    }
}
