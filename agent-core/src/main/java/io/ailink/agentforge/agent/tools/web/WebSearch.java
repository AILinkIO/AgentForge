package io.ailink.agentforge.agent.tools.web;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.Limit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Year;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * web-search：往一家 MCP 搜索端点 POST 一条**裸的** {@code tools/call}（不握手，一问一答），
 * 与 coxswain {@code tools/websearch.ss} / opencode websearch 同一路子。
 *
 * <pre>
 *   Parallel  https://search.parallel.ai/mcp   工具 web_search      免 key（有 PARALLEL_API_KEY 就带 Bearer）
 *   Exa       https://mcp.exa.ai/mcp           工具 web_search_exa  要 EXA_API_KEY（免 key 会撞限流）
 * </pre>
 *
 * 选家：{@code AGENTFORGE_WEBSEARCH_PROVIDER}（兼认 {@code COXN_WEBSEARCH_PROVIDER}）= exa|parallel
 * &gt; 有 EXA_API_KEY 用 Exa &gt; 默认 Parallel。{@code AGENTFORGE_WEBSEARCH_URL}（兼认 {@code COXN_…}）
 * 给了就打那个端点。回的是 JSON 或 SSE（{@code data: {…}} 行），都认。
 */
public final class WebSearch {
    private WebSearch() { }

    static final String EXA_URL = "https://mcp.exa.ai/mcp";
    static final String PARALLEL_URL = "https://search.parallel.ai/mcp";
    static final int TIMEOUT_MS = 25000;
    static final int SEARCH_OUTPUT_LIMIT = 40000;

    public enum Provider { EXA, PARALLEL }

    /** 测试换掉环境变量来源。 */
    static volatile Function<String, String> env = System::getenv;

    static String env(String name) {
        String v = env.apply(name);
        return v == null || v.isEmpty() ? null : v;
    }

    static String env2(String a, String b) {
        String v = env(a);
        return v != null ? v : env(b);
    }

    public static AgentTool tool() {
        return AgentTool.builder("web-search")
                .description("搜网页：实时搜索，回来的是最相关那几页的正文摘录（带 URL）。"
                        + "要知识截止之后的事（新版本、新闻、最近的文档）用它；有 URL 直接抓用 web-fetch。"
                        + "现在是 " + Year.now().getValue() + " 年——搜最近的事把年份写进 query（别写去年）。")
                .required("query", "string", "搜索词")
                .optional("numResults", "integer", "要几条（默认 8；Parallel 那家不认这个）", 0L)
                .optional("livecrawl", "string", "fallback（默认：缓存没有才现抓）/ preferred（尽量现抓）；只有 Exa 认", "")
                .optional("type", "string", "auto（默认）/ fast / deep；只有 Exa 认", "")
                .optional("contextMaxCharacters", "integer", "给模型的上下文最多几个字（默认 10000；只有 Exa 认）", 0L)
                .sensitive().concurrencySafe()
                .handler(WebSearch::run)
                .build();
    }

    // ── 纯函数 ─────────────────────────────────────────────────────────

    public static Provider provider(String override, boolean exaKey) {
        if ("exa".equals(override)) return Provider.EXA;
        if ("parallel".equals(override)) return Provider.PARALLEL;
        return exaKey ? Provider.EXA : Provider.PARALLEL;
    }

    public static String endpoint(Provider p, String exaKey, String override) {
        if (override != null && !override.isEmpty()) return override;
        if (p == Provider.EXA) return exaKey != null ? EXA_URL + "?exaApiKey=" + URLEncoder.encode(exaKey, StandardCharsets.UTF_8) : EXA_URL;
        return PARALLEL_URL;
    }

    public static ObjectNode requestBody(Provider p, String query, long num, String livecrawl, String type, long maxChars) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode o = f.objectNode();
        o.put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        ObjectNode params = o.putObject("params");
        if (p == Provider.EXA) {
            params.put("name", "web_search_exa");
            ObjectNode args = params.putObject("arguments");
            args.put("query", query);
            args.put("type", List.of("auto", "fast", "deep").contains(type) ? type : "auto");
            args.put("numResults", num > 0 ? num : 8);
            args.put("livecrawl", List.of("fallback", "preferred").contains(livecrawl) ? livecrawl : "fallback");
            if (maxChars > 0) args.put("contextMaxCharacters", maxChars);
        } else {
            params.put("name", "web_search");
            ObjectNode args = params.putObject("arguments");
            args.put("objective", query);
            ArrayNode qs = args.putArray("search_queries");
            qs.add(query);
        }
        return o;
    }

    /** 响应正文（JSON 或 SSE）→ 第一段非空 text；没有 → null。错误对象带回"（对面报错）…"。 */
    public static String parseResponse(String body) {
        String t = body.strip();
        String hit = payloadText(t);
        if (hit != null) return hit;
        for (String line : body.split("\n")) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.startsWith("data: ")) {
                String h = payloadText(l.substring(6));
                if (h != null) return h;
            }
        }
        return null;
    }

    private static String payloadText(String s) {
        if (!s.strip().startsWith("{")) return null;
        JsonNode obj;
        try { obj = AgentTool.JSON.readTree(s); } catch (RuntimeException e) { return null; }
        if (obj == null || !obj.isObject()) return null;
        JsonNode content = obj.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode it : content) {
                JsonNode tx = it.get("text");
                if (tx != null && tx.isString() && !tx.asString().isEmpty()) return tx.asString();
            }
        }
        JsonNode err = obj.get("error");
        if (err != null && err.isObject() && err.has("message") && err.get("message").isString()) {
            return "（对面报错）" + err.get("message").asString();
        }
        return null;
    }

    /** 按当前环境变量会打的那个端点（审批 / 网络策略按它的主机判）。 */
    public static String currentEndpoint() {
        String exaKey = env("EXA_API_KEY");
        Provider p = provider(env2("AGENTFORGE_WEBSEARCH_PROVIDER", "COXN_WEBSEARCH_PROVIDER"), exaKey != null);
        return endpoint(p, exaKey, env2("AGENTFORGE_WEBSEARCH_URL", "COXN_WEBSEARCH_URL"));
    }

    // ── 出网 ───────────────────────────────────────────────────────────

    static String run(ToolEnv envx, Args a) throws Exception {
        String query = a.requireStr("query").strip();
        if (query.isEmpty()) throw new ToolException("query 是空的");
        String exaKey = env("EXA_API_KEY");
        Provider p = provider(env2("AGENTFORGE_WEBSEARCH_PROVIDER", "COXN_WEBSEARCH_PROVIDER"), exaKey != null);
        String url = endpoint(p, exaKey, env2("AGENTFORGE_WEBSEARCH_URL", "COXN_WEBSEARCH_URL"));
        String pkey = env("PARALLEL_API_KEY");
        String body = requestBody(p, query, a.integer("numResults", 0), a.str("livecrawl", ""), a.str("type", ""),
                a.integer("contextMaxCharacters", 0)).toString();
        String name = p == Provider.EXA ? "exa" : "parallel";
        HttpResponse<String> resp;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(TIMEOUT_MS)).build()) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", WebFetch.USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (p == Provider.PARALLEL && pkey != null) rb.header("Authorization", "Bearer " + pkey);
            CompletableFuture<HttpResponse<String>> f = client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Runnable unhook = envx.cancel().onCancel(() -> f.cancel(true));
            try {
                resp = f.get(TIMEOUT_MS + 5000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.CancellationException e) {
                envx.cancel().throwIfCancelled();
                throw new ToolException("搜索没连上（" + name + "）：被取消");
            } catch (Exception e) {
                Throwable c = e instanceof java.util.concurrent.ExecutionException && e.getCause() != null ? e.getCause() : e;
                throw new ToolException("搜索没连上（" + name + "）：" + WebFetch.describe(c));
            } finally {
                unhook.run();
            }
        } catch (IllegalArgumentException e) {
            throw new ToolException("搜索没连上（" + name + "）：" + e.getMessage());
        }
        String text = resp.body() == null ? "" : resp.body();
        int st = resp.statusCode();
        if (st < 200 || st >= 300) {
            String t = text.strip();
            throw new ToolException("搜索端点（" + name + "）回了 HTTP " + st + "：" + (t.length() > 300 ? t.substring(0, 300) : t));
        }
        String hit = parseResponse(text);
        if (hit == null) return "没搜到结果，换个说法再试。";
        return "（" + (p == Provider.EXA ? "Exa" : "Parallel") + "）\n" + Limit.truncateNote(hit, SEARCH_OUTPUT_LIMIT, "搜索结果");
    }
}
