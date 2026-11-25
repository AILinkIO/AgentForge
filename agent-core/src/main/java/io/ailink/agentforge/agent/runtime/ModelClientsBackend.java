package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.loop.AnthropicHttpCaller;
import io.ailink.agentforge.agent.loop.ModelCaller;
import io.ailink.agentforge.agent.loop.SpringAiModelCaller;
import io.ailink.agentforge.agent.models.ApiKind;
import io.ailink.agentforge.agent.models.ModelClients;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.ModelRef;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.PromptCache;
import io.ailink.agentforge.agent.session.ModelBackend;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 真实的 {@link ModelBackend}：{@link Models}（哪些模型能用、元数据）+ {@link ModelClients}（Spring AI 客户端与选项），
 * 按 {@link CallerPolicy} 在 Spring AI 与原生 Anthropic 之间挑。
 *
 * <p>Spring AI 那路的选项每次请求现算：{@code ChatRequest.options()} 里的 max_tokens / 思考档位 / prompt cache（经
 * {@code extra.promptCache}）+ 工具声明 → {@link ModelClients#options}。
 * <p>原生那路的思考档位要翻一下：{@code on} → {@code adaptive}（"只有开关"的模型）；纯数字是预算原样；厂商档位名
 * （low/medium/high…）由 caller 映射成预算 —— 与 Spring AI 那路的 adaptive + effort 不完全等价，是已知近似。
 */
public final class ModelClientsBackend implements ModelBackend {
    private final Models models;
    private final ModelClients clients;
    private final CallerPolicy policy;
    private final Duration connectTimeout;

    public ModelClientsBackend(Models models, ModelClients clients, CallerPolicy policy, Duration connectTimeout) {
        this.models = Objects.requireNonNull(models);
        this.clients = Objects.requireNonNull(clients);
        this.policy = policy == null ? CallerPolicy.DEFAULT : policy;
        this.connectTimeout = connectTimeout == null ? Duration.ofSeconds(10) : connectTimeout;
    }

    @Override
    public ModelMeta meta(String modelRef) { return models.meta(modelRef); }

    @Override
    public boolean anthropic(String modelRef) { return models.apiOf(modelRef) == ApiKind.ANTHROPIC; }

    @Override
    public ModelCaller caller(String modelRef, String thinking) {
        if (modelRef == null) {
            Models.Resolution r = models.resolveCurrent();
            if (!r.ok()) throw new ModelClients.NotConfigured(r.problem().message());
            modelRef = r.ref().toString();
        }
        ModelRef ref = ModelRef.parse(modelRef);
        if (ref == null) throw new ModelClients.NotConfigured("模型要写成 \"provider/模型 id\"：" + modelRef);
        if (models.meta(ref) == null) throw new ModelClients.NotConfigured("模型 \"" + modelRef + "\" 不在可用的那几条里（拼错了？没 key？被屏蔽了？）");
        ApiKind api = models.apiOf(modelRef);
        if (policy.nativeAnthropic(api, thinking)) {
            ModelClients.Endpoint ep = clients.endpoint(ref.providerId());
            boolean bearer = ep.key().source().endsWith("AUTH_TOKEN");
            AnthropicHttpCaller http = new AnthropicHttpCaller(ep.baseUrl(), ep.key().value(), bearer, Map.of(), connectTimeout);
            return (req, onDelta, cancel) -> {
                String th = req.options().thinking();
                if ("on".equals(th)) req = req.withOptions(req.options().withThinking("adaptive"));
                return http.call(req, onDelta, cancel);
            };
        }
        ChatModel cm = clients.chatModel(ref);
        final ModelRef fref = ref;
        return new SpringAiModelCaller(cm, (req, cbs) -> {
            var o = req.options();
            PromptCache pc = o.extra().get("promptCache") instanceof PromptCache p ? p
                    : o.promptCache() ? PromptCache.FIVE_MINUTES : PromptCache.OFF;
            return clients.options(fref, new ModelClients.RequestOptions(o.maxTokens(), o.thinking(), pc, List.copyOf(cbs)));
        }, true);
    }
}
