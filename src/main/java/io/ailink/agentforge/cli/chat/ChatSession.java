package io.ailink.agentforge.cli.chat;

import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.ui.DisplayMessage;
import io.ailink.agentforge.ui.ScreenDrawer;
import io.ailink.agentforge.ui.TerminalManager;
import org.jline.reader.LineReader;

import java.util.List;

/**
 * 聊天会话管理器
 * 
 * 负责交互式对话的主循环、内置命令处理、界面绘制。
 * 将用户交互逻辑与业务逻辑分离。
 */
public class ChatSession {

    private final TerminalManager terminalManager;
    private final ScreenDrawer screenDrawer;
    private final ConversationState conversationState;
    private final MessageProcessor messageProcessor;
    private final ChatHistoryService chatHistoryService;
    private final LineReader reader;

    public ChatSession(TerminalManager terminalManager,
                     ScreenDrawer screenDrawer,
                     ConversationState conversationState,
                     MessageProcessor messageProcessor,
                     ChatHistoryService chatHistoryService,
                     LineReader reader) {
        this.terminalManager = terminalManager;
        this.screenDrawer = screenDrawer;
        this.conversationState = conversationState;
        this.messageProcessor = messageProcessor;
        this.chatHistoryService = chatHistoryService;
        this.reader = reader;
    }

    /**
     * 启动交互式会话
     */
    public void start() {
        var writer = terminalManager.getWriter();

        // 绘制初始界面
        screenDrawer.drawChatScreen(conversationState.getDisplayMessages(), "");

        // 主循环
        while (true) {
            writer.print("\u001B[32m\u001B[1m>\u001B[0m ");
            writer.flush();

            String input = reader.readLine();
            input = input.trim();

            if (input.isEmpty()) {
                continue;
            }

            // 退出命令
            if (isQuitCommand(input)) {
                writer.println("\u001B[33m再见!\u001B[0m");
                writer.flush();
                break;
            }

            // 内置命令
            if (handleBuiltInCommand(input)) {
                continue;
            }

            // 处理聊天消息
            processChatMessage(input);
        }
    }

    /**
     * 处理内置命令
     * 
     * @param input 用户输入
     * @return true 如果是内置命令并已处理
     */
    public boolean handleBuiltInCommand(String input) {
        var writer = terminalManager.getWriter();

        boolean handled = switch (input) {
            case ":help", ":h" -> {
                printHelp();
                yield true;
            }
            case ":history" -> {
                printHistory();
                yield true;
            }
            case ":clear", ":c" -> {
                screenDrawer.clearScreen();
                screenDrawer.drawChatScreen(conversationState.getDisplayMessages(), "");
                yield true;
            }
            case ":summary" -> {
                showSummary();
                yield true;
            }
            default -> false;
        };

        if (handled) {
            writer.print("\n\u001B[90m按回车继续...\u001B[0m");
            writer.flush();
            reader.readLine();
        }

        return handled;
    }

    /**
     * 处理聊天消息
     * 
     * @param input 用户输入
     */
    private void processChatMessage(String input) {
        // 显示思考中
        screenDrawer.drawChatScreen(conversationState.getDisplayMessages(), "\u001B[90m思考中...\u001B[0m");

        // 处理消息并获取响应
        String responseText = messageProcessor.processMessage(input, conversationState);

        // 绘制最终界面
        screenDrawer.drawChatScreen(conversationState.getDisplayMessages(), "");
    }

    /**
     * 打印帮助信息
     */
    public void printHelp() {
        var writer = terminalManager.getWriter();
        writer.println();
        writer.println("\u001B[36m=== 可用命令 ===\u001B[0m");
        writer.println("  \u001B[33m:help\u001B[0m, \u001B[33m:h\u001B[0m   - 显示帮助");
        writer.println("  \u001B[33m:history\u001B[0m     - 显示最近消息");
        writer.println("  \u001B[33m:clear\u001B[0m, \u001B[33m:c\u001B[0m  - 清除对话上下文");
        writer.println("  \u001B[33m:summary\u001B[0m     - 显示今日总结");
        writer.println("  \u001B[33m:quit\u001B[0m, \u001B[33m:q\u001B[0m   - 退出对话");
        writer.println();
        writer.println("\u001B[90m提示: 使用上下方向键查看历史命令\u001B[0m");
        terminalManager.flush();
    }

    /**
     * 打印历史消息
     */
    public void printHistory() {
        var writer = terminalManager.getWriter();
        var messages = chatHistoryService.getRecentChatMessages(20);

        writer.println();
        if (messages.isEmpty()) {
            writer.println("\u001B[90m暂无消息记录。\u001B[0m");
            terminalManager.flush();
            return;
        }
        writer.println("\u001B[36m=== 最近消息 ===\u001B[0m");
        for (var msg : messages) {
            String roleName = "user".equals(msg.getRole()) ? "\u001B[32m用户\u001B[0m" : "\u001B[35m助手\u001B[0m";
            writer.println("[\u001B[90m" + msg.getCreatedAt().toLocalTime() + "\u001B[0m] " + roleName + ": " + msg.getContent());
        }
        terminalManager.flush();
    }

    /**
     * 显示今日总结
     */
    public void showSummary() {
        var writer = terminalManager.getWriter();
        var today = java.time.LocalDate.now();
        var summary = chatHistoryService.getDailySummary(today);

        writer.println();
        if (summary == null) {
            writer.println("\u001B[90m今日暂无总结，请运行 'history --summary' 生成。\u001B[0m");
        } else {
            writer.println("\u001B[36m=== 今日总结 (" + today + ") ===\u001B[0m");
            writer.println("消息数: " + summary.getMessageCount());
            writer.println("总结: " + summary.getSummary());
        }
        terminalManager.flush();
    }

    /**
     * 检查退出命令
     * 
     * @param input 用户输入
     * @return true 如果是退出命令
     */
    private boolean isQuitCommand(String input) {
        return ":quit".equals(input) || ":exit".equals(input) || ":q".equals(input);
    }
}
