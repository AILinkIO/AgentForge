package io.ailink.agentforge;

import io.ailink.agentforge.cli.ChatCommand;
import io.ailink.agentforge.cli.HistoryCommand;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.template.PromptRenderer;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 应用启动入口
 *
 * 负责：
 * - 注册子命令（chat, history）
 * - 命令行参数解析和分发
 * - 应用生命周期管理
 *
 * 命令结构：
 * <pre>
 * agentforge [--help] [--version] <command> [options]
 *
 * 子命令：
 * - chat:     交互式对话模式
 * - history:  历史消息管理
 * </pre>
 */
@Component
@Command(name = "agentforge", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "AgentForge AI工具集")
public class AppRunner implements CommandLineRunner, Runnable {

    private final LlmProvider llmProvider;
    private final PromptRenderer promptRenderer;
    private final ChatHistoryService chatHistoryService;

    /**
     * 构造函数，Spring 自动注入依赖
     *
     * @param llmProvider        LLM服务接口
     * @param promptRenderer     提示词渲染器
     * @param chatHistoryService 聊天历史服务
     */
    public AppRunner(LlmProvider llmProvider, PromptRenderer promptRenderer, ChatHistoryService chatHistoryService) {
        this.llmProvider = llmProvider;
        this.promptRenderer = promptRenderer;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 应用启动入口
     *
     * @param args 命令行参数
     */
    @Override
    public void run(String... args) {
        // 创建命令行解析器并注册子命令
        CommandLine cmd = new CommandLine(this)
                .addSubcommand(new ChatCommand(llmProvider, chatHistoryService))
                .addSubcommand(new HistoryCommand(chatHistoryService));
        cmd.execute(args);
    }

    /**
     * 显示帮助信息
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
