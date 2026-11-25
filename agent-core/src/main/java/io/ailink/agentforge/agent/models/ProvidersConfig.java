package io.ailink.agentforge.agent.models;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用户配置：自己的 provider（自托管 / 内网网关 / models.dev 不认识的厂商）与它们的模型、默认模型。
 *
 * <p>coxswain 里这件事写在 init.ss（{@code agent-provider-define!}）。Java 这边 {@link Models#defineProvider} 等方法早就
 * 在，缺的是"谁来调"—— 这个类就是那一层：启动时读 {@code <configDir>/providers.json}。
 *
 * <pre>{@code
 * {
 *   "providers": {
 *     "litegate": {
 *       "api": "anthropic",                       // anthropic | openai（OpenAI 兼容）
 *       "baseUrl": "http://127.0.0.1:4000",
 *       "label": "LiteGate",                      // 可选
 *       "apiKeyEnv": "LITEGATE_API_KEY",          // 可选，默认 <ID>_API_KEY；也可以用 /key 存进加密凭据
 *       "maxTokens": 32000,                       // 可选
 *       "models": {
 *         "MiniMax-M3.1-Flash": { "context": 200000, "maxOutput": 32000, "thinking": ["off", "on"] }
 *       }
 *     }
 *   },
 *   "model": "litegate/MiniMax-M3.1-Flash"         // 可选：默认模型（命令行 --model / 界面里选的照样赢）
 * }
 * }</pre>
 *
 * <p>⚠️ 一条写错不拖垮整份：出错的那家跳过，原因收进返回的提示里，由宿主说出来（启动时一行，而不是起不来）。
 */
public final class ProvidersConfig {
    public static final String FILE = "providers.json";

    private ProvidersConfig() { }

    public static Path file(Path configDir) { return configDir.resolve(FILE); }

    /** 读并应用；文件不存在 ⇒ 空表。返回给人看的问题（每条一句）。 */
    public static List<String> apply(Models models, Path configDir) {
        Path f = file(configDir);
        List<String> problems = new ArrayList<>();
        if (!Files.isRegularFile(f)) return problems;
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(Files.readString(f));
        } catch (Exception e) {
            problems.add(f + " 读不了：" + e.getMessage());
            return problems;
        }
        JsonNode ps = root.path("providers");
        for (var e : ps.properties()) {
            String id = e.getKey();
            JsonNode p = e.getValue();
            try {
                ProviderDef d = ProviderDef.of();
                String api = p.path("api").asString(null);
                if (api != null) d.api(parseApi(api));
                if (p.hasNonNull("baseUrl")) d.baseUrl(p.path("baseUrl").asString());
                if (p.hasNonNull("label")) d.label(p.path("label").asString());
                if (p.hasNonNull("apiKeyEnv")) d.apiKeyEnv(p.path("apiKeyEnv").asString());
                if (p.hasNonNull("maxTokens")) d.maxTokens(p.path("maxTokens").asInt());
                models.defineProvider(id, d);
                JsonNode ms = p.path("models");
                if (ms.isArray()) {
                    for (JsonNode m : ms) models.providerModel(id, m.asString(), m.asString(), null, null, List.of());
                } else {
                    for (var me : ms.properties()) {
                        JsonNode m = me.getValue();
                        List<String> levels = new ArrayList<>();
                        for (JsonNode l : m.path("thinking")) levels.add(l.asString());
                        models.providerModel(id, me.getKey(), m.path("label").asString(me.getKey()),
                                m.hasNonNull("context") ? m.path("context").asLong() : null,
                                m.hasNonNull("maxOutput") ? m.path("maxOutput").asLong() : null, levels);
                    }
                }
            } catch (RuntimeException ex) {
                problems.add(FILE + " 里的 provider \"" + id + "\" 没用上：" + ex.getMessage());
            }
        }
        String dflt = root.path("model").asString(null);
        if (dflt != null && !dflt.isBlank()) models.setConfigDefault(dflt);
        return problems;
    }

    static ApiKind parseApi(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "anthropic", "claude", "messages" -> ApiKind.ANTHROPIC;
            case "openai", "openai-compatible", "openai_compatible", "chat" -> ApiKind.OPENAI;
            default -> throw new IllegalArgumentException("api 只认 anthropic / openai，收到 \"" + s + "\"");
        };
    }
}
