package io.ailink.agentforge.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    @Bean
    @Primary
    public LlmProvider primaryLlmProvider(
            @Value("${agentforge.llm.provider:}") String provider,
            @Qualifier("claude") LlmProvider claudeProvider,
            @Qualifier("openai") LlmProvider openaiProvider) {

        String selected = resolveProvider(provider);
        log.info("Using LLM provider: {}", selected);

        return switch (selected) {
            case "openai" -> openaiProvider;
            default -> claudeProvider;
        };
    }

    private String resolveProvider(String provider) {
        if (provider != null && !provider.isBlank()) {
            return provider;
        }
        // 根据环境变量自动检测
        if (hasEnv("ANTHROPIC_AUTH_TOKEN") || hasEnv("ANTHROPIC_API_KEY")) {
            return "claude";
        }
        if (hasEnv("OPENAI_API_KEY")) {
            return "openai";
        }
        return "claude";
    }

    private boolean hasEnv(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
