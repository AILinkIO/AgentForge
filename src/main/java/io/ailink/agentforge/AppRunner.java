package io.ailink.agentforge;

import io.ailink.agentforge.cli.ChatCommand;
import io.ailink.agentforge.cli.HistoryCommand;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.template.PromptRenderer;
import io.ailink.agentforge.tool.ToolExecutor;
import io.ailink.agentforge.tool.ToolRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Component
@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AgentForge AI工具集")
public class AppRunner implements CommandLineRunner, Runnable {

    private final LlmProvider llmProvider;
    private final PromptRenderer promptRenderer;
    private final ChatHistoryService chatHistoryService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    public AppRunner(LlmProvider llmProvider, PromptRenderer promptRenderer, 
                     ChatHistoryService chatHistoryService,
                     ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this.llmProvider = llmProvider;
        this.promptRenderer = promptRenderer;
        this.chatHistoryService = chatHistoryService;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public void run(String... args) {
        CommandLine cmd = new CommandLine(this)
                .addSubcommand(new ChatCommand(llmProvider, chatHistoryService, toolRegistry, toolExecutor))
                .addSubcommand(new HistoryCommand(chatHistoryService));
        cmd.execute(args);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
