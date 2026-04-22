package io.ailink.agentforge;

import io.ailink.agentforge.cli.ChatCommand;
import io.ailink.agentforge.cli.HistoryCommand;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.template.PromptRenderer;
import io.ailink.agentforge.tool.builtin.CalculatorTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Component
@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AgentForge AI工具集")
public class AppRunner implements CommandLineRunner, Runnable {

    private final ChatClient.Builder chatClientBuilder;
    private final CalculatorTool calculatorTool;
    private final PromptRenderer promptRenderer;
    private final ChatHistoryService chatHistoryService;

    public AppRunner(ChatClient.Builder chatClientBuilder, CalculatorTool calculatorTool,
                     PromptRenderer promptRenderer, ChatHistoryService chatHistoryService) {
        this.chatClientBuilder = chatClientBuilder;
        this.calculatorTool = calculatorTool;
        this.promptRenderer = promptRenderer;
        this.chatHistoryService = chatHistoryService;
    }

    @Override
    public void run(String... args) {
        CommandLine cmd = new CommandLine(this)
                .addSubcommand(new ChatCommand(chatClientBuilder, calculatorTool, chatHistoryService))
                .addSubcommand(new HistoryCommand(chatHistoryService));
        cmd.execute(args);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
