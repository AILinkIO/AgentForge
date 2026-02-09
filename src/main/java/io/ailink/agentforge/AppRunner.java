package io.ailink.agentforge;

import io.ailink.agentforge.cli.TranslateCommand;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.template.PromptRenderer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Component
@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AgentForge AI工具集")
public class AppRunner implements CommandLineRunner, Runnable {

    private final LlmProvider claudeProvider;
    private final PromptRenderer promptRenderer;

    public AppRunner(@Qualifier("claude") LlmProvider claudeProvider, PromptRenderer promptRenderer) {
        this.claudeProvider = claudeProvider;
        this.promptRenderer = promptRenderer;
    }

    @Override
    public void run(String... args) {
        CommandLine cmd = new CommandLine(this)
                .addSubcommand(new TranslateCommand(claudeProvider, promptRenderer));
        cmd.execute(args);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
