package io.ailink.agentforge;

import io.ailink.agentforge.llm.ChatMessage;
import io.ailink.agentforge.llm.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.template.PromptRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AppRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AppRunner.class);

    private final LlmProvider claudeProvider;
    private final PromptRenderer promptRenderer;

    public AppRunner(@Qualifier("claude") LlmProvider claudeProvider, PromptRenderer promptRenderer) {
        this.claudeProvider = claudeProvider;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public void run(String... args) {
        String targetLang = "en";
        String text = null;

        for (int i = 0; i < args.length; i++) {
            if ("-t".equals(args[i]) && i + 1 < args.length) {
                targetLang = args[++i];
            } else if (text == null) {
                text = args[i];
            }
        }

        if (text == null) {
            System.out.println("用法: agentforge [-t en|zh] <要翻译的文本>");
            return;
        }

        String role;
        String lang;
        if ("zh".equals(targetLang)) {
            role = "中文翻译专家";
            lang = "中文";
        } else {
            role = "英语翻译专家";
            lang = "英文";
        }

        String systemPrompt = promptRenderer.render("translator.md", Map.of("role", role, "lang", lang));
        log.debug("System prompt: {}", systemPrompt);

        ChatRequest request = ChatRequest.builder()
                .system(systemPrompt)
                .messages(List.of(ChatMessage.user(text)))
                .build();

        StringBuilder result = new StringBuilder();
        claudeProvider.chatStream(request)
                .doOnNext(chunk -> {
                    System.out.print(chunk);
                    result.append(chunk);
                })
                .blockLast();
        System.out.println();

        log.debug("Translation complete: {}", result);
    }
}
