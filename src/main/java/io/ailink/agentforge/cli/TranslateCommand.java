package io.ailink.agentforge.cli;

import io.ailink.agentforge.llm.ChatMessage;
import io.ailink.agentforge.llm.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.template.PromptRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.Map;

@Command(name = "translate", mixinStandardHelpOptions = true, description = "翻译文本")
public class TranslateCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TranslateCommand.class);

    private final LlmProvider claudeProvider;
    private final PromptRenderer promptRenderer;

    @Option(names = {"-t", "--target"}, defaultValue = "en",
            description = "目标语言: en(英文), zh(中文)。默认: ${DEFAULT-VALUE}")
    private String targetLang;

    @Parameters(index = "0", description = "要翻译的文本")
    private String text;

    public TranslateCommand(LlmProvider claudeProvider, PromptRenderer promptRenderer) {
        this.claudeProvider = claudeProvider;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public void run() {
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
