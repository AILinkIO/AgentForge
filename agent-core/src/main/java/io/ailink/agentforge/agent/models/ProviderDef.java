package io.ailink.agentforge.agent.models;

/**
 * 配置里定义一家 provider 的选项（对应 coxswain {@code (agent-provider-define! id 'api … 'base-url …)}）。
 *
 * <pre>{@code
 * models.defineProvider("内网", ProviderDef.of().api(ApiKind.OPENAI).baseUrl("http://10.0.0.5:8000/v1"));
 * }</pre>
 *
 * <p>⚠️ {@code baseUrl} 是 **SDK 认的基地址**（OpenAI 形到 {@code /chat/completions} 之前，Anthropic 形到
 * {@code /v1/messages} 之前）；写成完整地址也认，尾巴上的操作路径会被剥掉（见 {@link Models#normalizeBase}）。
 */
public final class ProviderDef {
    ApiKind api;
    String label;
    String baseUrl;
    String apiKeyEnv;
    Integer maxTokens;

    private ProviderDef() { }

    public static ProviderDef of() { return new ProviderDef(); }

    public ProviderDef api(ApiKind v) { this.api = v; return this; }

    public ProviderDef label(String v) { this.label = v; return this; }

    public ProviderDef baseUrl(String v) { this.baseUrl = v; return this; }

    /** 只读这一个环境变量取 key（覆盖已知表与目录的 env）。 */
    public ProviderDef apiKeyEnv(String v) { this.apiKeyEnv = v; return this; }

    /** 这一家请求默认的 max_tokens。 */
    public ProviderDef maxTokens(Integer v) { this.maxTokens = v; return this; }
}
