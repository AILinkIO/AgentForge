package io.ailink.agentforge.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentforge.llm")
public abstract class AbstractLlmProperties<T extends AbstractLlmProperties<T>> {

    private String apiKey;
    private String baseUrl;
    private String defaultModel;
    private int defaultMaxTokens = 1024;

    public abstract String getApiPath();

    public String getApiKey() {
        return apiKey;
    }

    @SuppressWarnings("unchecked")
    public T setApiKey(String apiKey) {
        this.apiKey = apiKey;
        return (T) this;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    @SuppressWarnings("unchecked")
    public T setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return (T) this;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    @SuppressWarnings("unchecked")
    public T setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
        return (T) this;
    }

    public int getDefaultMaxTokens() {
        return defaultMaxTokens;
    }

    @SuppressWarnings("unchecked")
    public T setDefaultMaxTokens(int defaultMaxTokens) {
        this.defaultMaxTokens = defaultMaxTokens;
        return (T) this;
    }
}
