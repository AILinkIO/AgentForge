package io.ailink.agentforge.cli;

import io.ailink.agentforge.cli.chat.ChatSession;
import io.ailink.agentforge.cli.chat.ConversationState;
import io.ailink.agentforge.cli.chat.MessageProcessor;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.tool.ToolExecutor;
import io.ailink.agentforge.tool.ToolRegistry;
import io.ailink.agentforge.ui.ANSIScreenDrawer;
import io.ailink.agentforge.ui.DisplayMessage;
import io.ailink.agentforge.ui.JLineTerminalManager;
import io.ailink.agentforge.ui.ScreenDrawer;
import io.ailink.agentforge.ui.TerminalManager;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天命令入口类
 * 
 * 负责参数解析和子命令分发，将具体业务委托给 ChatSession 处理。
 */
@Command(name = "chat", mixinStandardHelpOptions = true, description = "交互式对话模式")
public class ChatCommand implements Runnable {

    private final LlmProvider llmProvider;
    private final ChatHistoryService chatHistoryService;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    @Option(names = {"--system"}, description = "自定义系统提示词")
    private String systemPrompt = "你是一个知识问答助手，请根据用户的问题提供准确、有用的回答。";

    @Option(names = {"--list"}, description = "列出最近的消息")
    private boolean listMessages;

    @Option(names = {"--date"}, description = "按日期查看消息 (格式: YYYY-MM-DD)")
    private String dateStr;

    @Option(names = {"--summary"}, description = "查看今日总结")
    private boolean showSummary;

    public ChatCommand(LlmProvider llmProvider, ChatHistoryService chatHistoryService,
                      ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this.llmProvider = llmProvider;
        this.chatHistoryService = chatHistoryService;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public void run() {
        // 子命令分发
        if (listMessages) {
            listRecentMessages();
            return;
        }

        if (dateStr != null) {
            listMessagesByDate();
            return;
        }

        if (showSummary) {
            showTodaySummary();
            return;
        }

        // 启动交互式会话
        startInteractiveChat();
    }

    /**
     * 启动交互式聊天会话
     */
    private void startInteractiveChat() {
        try (TerminalManager terminalManager = new JLineTerminalManager("AgentForge")) {
            ScreenDrawer screenDrawer = new ANSIScreenDrawer(terminalManager);
            var reader = terminalManager.getReader();

            // 初始化对话状态，加载历史消息
            ConversationState conversationState = initConversationState();

            // 创建消息处理器
            MessageProcessor messageProcessor = new MessageProcessor(
                    llmProvider, toolRegistry, toolExecutor,
                    chatHistoryService, systemPrompt);

            // 创建并启动会话
            ChatSession chatSession = new ChatSession(
                    terminalManager, screenDrawer, conversationState,
                    messageProcessor, chatHistoryService, reader);

            chatSession.start();

        } catch (Exception e) {
            System.err.println("终端错误: " + e.getMessage());
        }
    }

    /**
     * 初始化对话状态，加载历史消息
     */
    private ConversationState initConversationState() {
        var state = new ConversationState();
        var recentMessages = chatHistoryService.getRecentChatMessages(20);

        for (var msg : recentMessages) {
            state.addDisplayMessage(
                    msg.getRole(),
                    msg.getContent(),
                    msg.getCreatedAt().toLocalTime().toString());

            if ("user".equals(msg.getRole())) {
                state.getConversationHistory().add(ChatMessage.user(msg.getContent()));
            } else {
                state.getConversationHistory().add(ChatMessage.assistant(msg.getContent()));
            }
        }

        return state;
    }

    private void listRecentMessages() {
        var messages = chatHistoryService.getRecentChatMessages(20);
        if (messages.isEmpty()) {
            System.out.println("暂无消息记录。");
            return;
        }
        System.out.println("=== 最近消息 ===");
        for (var msg : messages) {
            String roleName = "user".equals(msg.getRole()) ? "用户" : "助手";
            System.out.println("[" + msg.getCreatedAt() + "] " + roleName + ": " + msg.getContent());
            System.out.println();
        }
    }

    private void listMessagesByDate() {
        try {
            var date = java.time.LocalDate.parse(dateStr);
            var messages = chatHistoryService.getMessagesByDate(date);
            if (messages.isEmpty()) {
                System.out.println("日期 " + dateStr + " 没有消息记录。");
                return;
            }
            System.out.println("=== " + dateStr + " 消息 ===");
            for (var msg : messages) {
                String roleName = "user".equals(msg.getRole()) ? "用户" : "助手";
                System.out.println("[" + msg.getCreatedAt() + "] " + roleName + ": " + msg.getContent());
                System.out.println();
            }
        } catch (Exception e) {
            System.out.println("日期格式错误，请使用 YYYY-MM-DD 格式。");
        }
    }

    private void showTodaySummary() {
        var today = java.time.LocalDate.now();
        var summary = chatHistoryService.getDailySummary(today);
        if (summary == null) {
            System.out.println("今日暂无总结，请运行 'history --summary' 生成。");
            return;
        }
        System.out.println("=== 今日总结 (" + today + ") ===");
        System.out.println("消息数: " + summary.getMessageCount());
        System.out.println("总结: " + summary.getSummary());
    }
}
