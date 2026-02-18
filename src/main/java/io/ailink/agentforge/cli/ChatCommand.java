package io.ailink.agentforge.cli;

import io.ailink.agentforge.llm.dto.ChatMessage;
import io.ailink.agentforge.llm.dto.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.service.ChatHistoryService;
import io.ailink.agentforge.ui.*;
import org.jline.reader.LineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * 交互式对话命令
 *
 * 提供交互式聊天功能：
 * - 底部固定输入框，消息在上方滚动显示
 * - 支持命令历史（上下键浏览）
 * - 持久化命令历史（保存在 ~/.agentforge_history）
 * - ANSI 彩色输出
 * - 内置命令：:help, :history, :clear, :summary, :quit
 *
 * 使用方式：
 * <pre>
 * agentforge chat                           # 启动交互式对话
 * agentforge chat --system "自定义提示词"   # 指定系统提示词
 * agentforge chat --list                   # 列出最近消息（非交互模式）
 * agentforge chat --date 2026-02-18       # 按日期查看消息
 * agentforge chat --summary                # 查看今日总结
 * </pre>
 */
@Command(name = "chat", mixinStandardHelpOptions = true, description = "交互式对话模式")
public class ChatCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

    private final LlmProvider llmProvider;
    private final ChatHistoryService chatHistoryService;

    /**
     * 默认系统提示词
     */
    @Option(names = {"--system"}, description = "自定义系统提示词")
    private String systemPrompt = "你是一个知识问答助手，请根据用户的问题提供准确、有用的回答。";

    /**
     * 列出最近的消息
     */
    @Option(names = {"--list"}, description = "列出最近的消息")
    private boolean listMessages;

    /**
     * 按日期查看消息
     */
    @Option(names = {"--date"}, description = "按日期查看消息 (格式: YYYY-MM-DD)")
    private String dateStr;

    /**
     * 查看今日总结
     */
    @Option(names = {"--summary"}, description = "查看今日总结")
    private boolean showSummary;

    /**
     * 构造函数，Spring 自动注入
     *
     * @param llmProvider         LLM服务
     * @param chatHistoryService 聊天历史服务
     */
    public ChatCommand(LlmProvider llmProvider, ChatHistoryService chatHistoryService) {
        this.llmProvider = llmProvider;
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 命令入口，根据参数执行相应操作
     */
    @Override
    public void run() {
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

        startInteractiveChat();
    }

    /**
     * 列出最近的消息
     */
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

    /**
     * 按日期查询消息
     */
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

    /**
     * 显示今日总结
     */
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

    /**
     * 启动交互式对话
     */
    private void startInteractiveChat() {
        try (
                // 创建终端管理器
                TerminalManager terminalManager = new JLineTerminalManager("AgentForge");
        ) {
            // 创建屏幕绘制器
            ScreenDrawer screenDrawer = new ANSIScreenDrawer(terminalManager);

            // 获取行读取器
            LineReader reader = terminalManager.getReader();

            // 加载历史消息用于显示
            List<DisplayMessage> displayMessages = new java.util.LinkedList<>();
            var recentMessages = chatHistoryService.getRecentChatMessages(20);
            for (var msg : recentMessages) {
                displayMessages.add(new DisplayMessage(
                        msg.getRole(),
                        msg.getContent(),
                        msg.getCreatedAt().toLocalTime().toString()
                ));
            }

            // 绘制初始界面
            screenDrawer.drawChatScreen(displayMessages, "");

            // 构建对话历史（用于 LLM 上下文）
            List<ChatMessage> conversationHistory = new ArrayList<>();
            for (var msg : recentMessages) {
                conversationHistory.add(new ChatMessage(msg.getRole(), msg.getContent()));
            }

            // 主循环
            mainLoop(terminalManager, screenDrawer, reader, displayMessages, conversationHistory);

        } catch (Exception e) {
            log.error("终端错误", e);
            System.err.println("终端错误: " + e.getMessage());
        }
    }

    /**
     * 主交互循环
     */
    private void mainLoop(TerminalManager terminalManager,
                          ScreenDrawer screenDrawer,
                          LineReader reader,
                          List<DisplayMessage> displayMessages,
                          List<ChatMessage> conversationHistory) {
        var writer = terminalManager.getWriter();

        while (true) {
            // 显示输入提示符
            writer.print("\u001B[32m\u001B[1m>\u001B[0m ");
            writer.flush();

            // 读取输入
            String input = reader.readLine();

            // 去掉首尾空格
            input = input.trim();

            if (input.isEmpty()) {
                continue;
            }

            // 处理退出命令
            if (isQuitCommand(input)) {
                writer.println("\u001B[33m再见!\u001B[0m");
                writer.flush();
                break;
            }

            // 处理内置命令
            if (handleBuiltInCommand(input, terminalManager, screenDrawer, reader)) {
                continue;
            }

            // 处理聊天消息
            processChatMessage(input, screenDrawer, displayMessages, conversationHistory);
        }
    }

    /**
     * 判断是否为退出命令
     */
    private boolean isQuitCommand(String input) {
        return ":quit".equals(input) || ":exit".equals(input) || ":q".equals(input);
    }

    /**
     * 处理内置命令
     */
    private boolean handleBuiltInCommand(String input,
                                         TerminalManager terminalManager,
                                         ScreenDrawer screenDrawer,
                                         LineReader reader) {
        var writer = terminalManager.getWriter();

        if (":help".equals(input) || ":h".equals(input)) {
            printHelp(terminalManager);
            // 等待用户按回车后继续
            writer.print("\n\u001B[90m按回车继续...\u001B[0m");
            writer.flush();
            reader.readLine();
            return true;
        }

        if (":history".equals(input)) {
            printHistory(terminalManager);
            writer.print("\n\u001B[90m按回车继续...\u001B[0m");
            writer.flush();
            reader.readLine();
            return true;
        }

        if (":clear".equals(input) || ":c".equals(input)) {
            screenDrawer.clearScreen();
            screenDrawer.drawChatScreen(new java.util.ArrayList<>(), "");
            return true;
        }

        if (":summary".equals(input)) {
            showSummaryCommand(terminalManager);
            writer.print("\n\u001B[90m按回车继续...\u001B[0m");
            writer.flush();
            reader.readLine();
            return true;
        }

        return false;
    }

    /**
     * 处理聊天消息
     */
    private void processChatMessage(String input,
                                    ScreenDrawer screenDrawer,
                                    List<DisplayMessage> displayMessages,
                                    List<ChatMessage> conversationHistory) {
        // 添加用户消息到显示列表
        String currentTime = java.time.LocalTime.now().toString();
        displayMessages.add(new DisplayMessage("user", input, currentTime));

        // 保存到数据库
        chatHistoryService.saveUserMessage(input);

        // 添加到对话历史
        conversationHistory.add(ChatMessage.user(input));

        // 构建请求
        ChatRequest request = ChatRequest.builder()
                .system(systemPrompt)
                .messages(new ArrayList<>(conversationHistory))
                .build();

        // 显示思考状态
        screenDrawer.drawChatScreen(displayMessages, "\u001B[90m思考中...\u001B[0m");

        // 获取回复
        StringBuilder responseBuilder = new StringBuilder();
        llmProvider.chatStream(request)
                .doOnNext(chunk -> responseBuilder.append(chunk))
                .blockLast();

        String responseText = responseBuilder.toString();

        // 添加助手消息到显示列表
        displayMessages.add(new DisplayMessage("assistant", responseText, currentTime));

        // 保存到数据库
        chatHistoryService.saveAssistantMessage(responseText);

        // 添加到对话历史
        conversationHistory.add(ChatMessage.assistant(responseText));

        // 重绘界面
        screenDrawer.drawChatScreen(displayMessages, "");
    }

    /**
     * 打印帮助信息
     */
    private void printHelp(TerminalManager terminalManager) {
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
     * 打印历史记录
     */
    private void printHistory(TerminalManager terminalManager) {
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
     * 显示总结命令
     */
    private void showSummaryCommand(TerminalManager terminalManager) {
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
}
