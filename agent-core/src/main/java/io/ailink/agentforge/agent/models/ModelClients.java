package io.ailink.agentforge.agent.models;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.AnthropicCacheTtl;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModelRef → Spring AI {@link ChatModel}（按 provider 缓存）+ 每次请求的 {@link ChatOptions}。
 *
 * <ul>
 *   <li>Anthropic 形（{@code @ai-sdk/anthropic}、minimax、用户配成 Anthropic 兼容的端点）→ {@link AnthropicChatModel}；</li>
 *   <li>OpenAI 兼容（openai、deepseek、{@code @ai-sdk/openai-compatible} 那一大类）→ {@link OpenAiChatModel}。</li>
 * </ul>
 *
 * <p>⚠️ **工具不由 Spring AI 执行**：Spring AI 2.0 的 {@code ChatModel.call} 本来就只把工具声明发出去、把
 * tool_use 交回来（1.x 的 {@code internalToolExecutionEnabled} 已经没有了，工具循环挪到了 ChatClient 的
 * advisor），所以这里把 工具（AgentTool 即 ToolCallback）放进 options 只是为了声明 —— 会话自己跑 gate / 并发 / 按停。
 *
 * <p>⚠️ 递给服务端的模型名是 {@link ModelRef#modelId()}，不是整串 ref。
 * ⚠️ 同一个 ChatModel 只给同一家用：两家都说 OpenAI 格式时，按格式共用会把 A 家的 key 发给 B 家。
 */
public final class ModelClients {

    /** 一次请求要的选项。null 字段 = 不说（交给默认）。 */
    public record RequestOptions(Integer maxTokens, String thinking, PromptCache promptCache, List<ToolCallback> tools) {
        public RequestOptions {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public static RequestOptions defaults() { return new RequestOptions(null, null, PromptCache.FIVE_MINUTES, List.of()); }

        public RequestOptions withTools(List<? extends ToolCallback> t) { return new RequestOptions(maxTokens, thinking, promptCache, List.copyOf(t)); }

        public RequestOptions withThinking(String level) { return new RequestOptions(maxTokens, level, promptCache, tools); }

        public RequestOptions withMaxTokens(Integer n) { return new RequestOptions(n, thinking, promptCache, tools); }

        public RequestOptions withPromptCache(PromptCache c) { return new RequestOptions(maxTokens, thinking, c, tools); }
    }

    /** 一次请求要的全部：客户端 + 选项 + 它是哪条、什么格式（会话层编解码用量时要分口径）。 */
    public record ChatCall(ChatModel chatModel, ChatOptions options, ModelRef ref, ApiKind api, ModelMeta meta) { }

    /** 建一家客户端要的东西。{@link #toString()} 不带 key。 */
    public record Endpoint(String providerId, ApiKind api, String baseUrl, Models.ResolvedKey key) {
        @Override
        public String toString() { return "Endpoint[" + providerId + ", " + api + ", " + baseUrl + ", key from " + key.source() + "]"; }
    }

    /** 没有 key / 没启用 / 不在可用池里时抛，消息说清下一步。 */
    public static final class NotConfigured extends IllegalStateException {
        public NotConfigured(String msg) { super(msg); }
    }

    /** 默认 max_tokens 的上限：非流式请求太大会撞 SDK 的 HTTP 超时。 */
    public static final int DEFAULT_MAX_TOKENS_CAP = 32000;

    private final Models models;
    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    public ModelClients(Models models) { this.models = Objects.requireNonNull(models); }

    /** 用 {@link Models#instance()}。 */
    public ModelClients() { this(Models.instance()); }

    /** key / 端点变了之后清掉缓存的客户端。 */
    public void invalidate() { cache.clear(); }

    /** 解析一家的格式 / 端点 / key。 */
    public Endpoint endpoint(String providerId) {
        ProviderInfo p = models.provider(providerId);
        if (p == null) throw new NotConfigured("provider \"" + providerId + "\" 还没启用：先选中它，或者在配置里 defineProvider");
        Models.ResolvedKey key = models.resolveKey(providerId);
        if (key == null) {
            String envs = p.envNames().isEmpty() ? EnvVars.idToEnv(providerId) + "_API_KEY" : String.join(" / ", p.envNames());
            throw new NotConfigured("provider \"" + providerId + "\" 没有 API key：设环境变量 " + envs
                    + "，或者在 agent 设置里为它输入 key（加密存进 " + models.auth().path() + "）");
        }
        return new Endpoint(providerId, p.api(), Models.normalizeBase(models.endpoint(providerId), p.api()), key);
    }

    /** 这条模型的 ChatModel（按 provider + 端点 + key 缓存）。 */
    public ChatModel chatModel(ModelRef ref) {
        Endpoint ep = endpoint(ref.providerId());
        String ck = ep.providerId() + "|" + ep.api() + "|" + ep.baseUrl() + "|" + sha256(ep.key().value());
        return cache.computeIfAbsent(ck, k -> build(ep));
    }

    /** 一次请求：ChatModel + 选项。模型必须在可用池里（被屏蔽 / 那家没 key 的不给建）。 */
    public ChatCall forRequest(ModelRef ref, RequestOptions req) {
        ModelMeta meta = models.meta(ref);
        if (meta == null) throw new NotConfigured("模型 \"" + ref + "\" 不在可用的那几条里（拼错了？没 key？被屏蔽了？）");
        ChatModel cm = chatModel(ref);
        ProviderInfo p = models.provider(ref.providerId());
        ChatOptions opts = options(ref, p.api(), meta, p, req == null ? RequestOptions.defaults() : req);
        return new ChatCall(cm, opts, ref, p.api(), meta);
    }

    /** 当前默认模型的一次请求；没配好时抛 {@link NotConfigured}（消息就是 {@link Problem} 那句话）。 */
    public ChatCall forCurrent(RequestOptions req) {
        Models.Resolution r = models.resolveCurrent();
        if (!r.ok()) throw new NotConfigured(r.problem().message());
        return forRequest(r.ref(), req);
    }

    // ─── 建客户端 ────────────────────────────────────────────────────────

    static ChatModel build(Endpoint ep) {
        if (ep.api() == ApiKind.ANTHROPIC) {
            AnthropicChatOptions.Builder b = AnthropicChatOptions.builder().apiKey(ep.key().value()).maxRetries(2);
            if (ep.baseUrl() != null) b.baseUrl(ep.baseUrl());
            // ANTHROPIC_AUTH_TOKEN 那种是 Bearer token（代理 / 网关常见），x-api-key 之外再带一个 Authorization
            if (ep.key().source().endsWith("AUTH_TOKEN")) b.customHeaders(Map.of("Authorization", "Bearer " + ep.key().value()));
            return AnthropicChatModel.builder().options(b.build()).build();
        }
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().apiKey(ep.key().value()).maxRetries(2);
        if (ep.baseUrl() != null) b.baseUrl(ep.baseUrl());
        return OpenAiChatModel.builder().options(b.build()).build();
    }

    // ─── 每次请求的选项 ──────────────────────────────────────────────────

    /** 不建客户端，只算选项（测试 / 预览用）。 */
    public ChatOptions options(ModelRef ref, RequestOptions req) {
        ModelMeta meta = models.meta(ref);
        ProviderInfo p = models.provider(ref.providerId());
        ApiKind api = p != null ? p.api() : ApiKind.OPENAI;
        return options(ref, api, meta, p, req == null ? RequestOptions.defaults() : req);
    }

    static ChatOptions options(ModelRef ref, ApiKind api, ModelMeta meta, ProviderInfo p, RequestOptions req) {
        Long maxOut = meta == null ? null : meta.maxOutput();
        int maxTokens = req.maxTokens() != null ? req.maxTokens()
                : p != null && p.maxTokens() != null ? p.maxTokens()
                : (int) Math.min(maxOut != null ? maxOut : 8192, DEFAULT_MAX_TOKENS_CAP);
        String level = req.thinking();
        return api == ApiKind.ANTHROPIC
                ? anthropicOptions(ref, maxTokens, maxOut, level, req)
                : openAiOptions(ref, maxTokens, level, meta, req);
    }

    /**
     * 档位 → Anthropic 的 thinking：{@code off} = disabled；{@code on} = adaptive（"只有开关"正是我们没有预算那个数，
     * 而 enabled 必须同时给 budget_tokens）；纯数字 = enabled(budget)；其余厂商档位名 = adaptive + {@code effort}
     * 原样（display summarized —— 不写的话 adaptive-only 那批模型的 thinking 块回来是空的）。null = 什么都不发。
     */
    static AnthropicChatOptions anthropicOptions(ModelRef ref, int maxTokens, Long maxOut, String level, RequestOptions req) {
        AnthropicChatOptions.Builder b = AnthropicChatOptions.builder().model(ref.modelId());
        int mt = maxTokens;
        if (level != null) {
            if (level.equals("off")) b.thinkingDisabled();
            else if (level.equals("on")) b.thinkingAdaptive();
            else if (ThinkingLevels.isBudget(level)) {
                long budget = Long.parseLong(level);
                // ⚠️ 预算是从 max_tokens 里切出来的：不够就抬，但不超过输出上限
                long want = budget + ThinkingLevels.ANSWER_HEADROOM;
                if (mt < want) mt = (int) (maxOut != null ? Math.min(want, maxOut) : want);
                b.thinkingEnabled(budget);
            } else {
                b.thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED);
                b.effort(OutputConfig.Effort.of(level));
            }
        }
        b.maxTokens(mt);
        PromptCache pc = req.promptCache() == null ? PromptCache.FIVE_MINUTES : req.promptCache();
        b.cacheOptions(switch (pc) {
            case OFF -> AnthropicCacheOptions.disabled();
            case FIVE_MINUTES -> AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY).build();
            case ONE_HOUR -> AnthropicCacheOptions.builder().strategy(AnthropicCacheStrategy.CONVERSATION_HISTORY)
                    .messageTypeTtl(MessageType.SYSTEM, AnthropicCacheTtl.ONE_HOUR).build();
        });
        if (!req.tools().isEmpty()) b.toolCallbacks(req.tools());
        return b.build();
    }

    /**
     * 档位 → OpenAI 兼容：厂商档位名原样进 {@code reasoning_effort}；{@code off} 若厂商档位里有 {@code none} 就发它，
     * 否则发 {@code thinking: {type: disabled}}（DeepSeek / 智谱那一类的开关写法），{@code on} 同理发 enabled。
     * 第一方 OpenAI 用 {@code max_completion_tokens}（推理模型不收 max_tokens），兼容端点多半只认 {@code max_tokens}。
     */
    static OpenAiChatOptions openAiOptions(ModelRef ref, int maxTokens, String level, ModelMeta meta, RequestOptions req) {
        OpenAiChatOptions.Builder b = OpenAiChatOptions.builder().model(ref.modelId());
        if ("openai".equals(ref.providerId())) b.maxCompletionTokens(maxTokens);
        else b.maxTokens(maxTokens);
        if (level != null) {
            List<String> effort = meta == null || meta.facts() == null ? null : meta.facts().reasoningEffort();
            if (level.equals("off")) {
                if (effort != null && effort.contains("none")) b.reasoningEffort("none");
                else b.extraBody(Map.of("thinking", Map.of("type", "disabled")));
            } else if (level.equals("on")) {
                b.extraBody(Map.of("thinking", Map.of("type", "enabled")));
            } else {
                b.reasoningEffort(level);
            }
        }
        if (!req.tools().isEmpty()) b.toolCallbacks(req.tools());
        return b.build();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
