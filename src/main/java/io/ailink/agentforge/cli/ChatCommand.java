package io.ailink.agentforge.cli;

import io.ailink.agentforge.llm.ChatMessage;
import io.ailink.agentforge.llm.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.service.ChatHistoryService;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

@Command(name = "chat", mixinStandardHelpOptions = true, description = "交互式对话模式")
public class ChatCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ChatCommand.class);

    private final LlmProvider llmProvider;
    private final ChatHistoryService chatHistoryService;

    @Option(names = {"--system"}, description = "自定义系统提示词")
    private String systemPrompt = "你是一个知识问答助手，请根据用户的问题提供准确、有用的回答。";

    @Option(names = {"--list"}, description = "列出最近的消息")
    private boolean listMessages;

    @Option(names = {"--date"}, description = "按日期查看消息 (格式: YYYY-MM-DD)")
    private String dateStr;

    @Option(names = {"--summary"}, description = "查看今日总结")
    private boolean showSummary;

    public ChatCommand(LlmProvider llmProvider, ChatHistoryService chatHistoryService) {
        this.llmProvider = llmProvider;
        this.chatHistoryService = chatHistoryService;
    }

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

    // Store conversation messages for display
    private static class DisplayMessage {
        String role;
        String content;
        String time;

        DisplayMessage(String role, String content, String time) {
            this.role = role;
            this.content = content;
            this.time = time;
        }
    }

    private void startInteractiveChat() {
        try {
            Terminal terminal = TerminalBuilder.builder()
                    .name("AgentForge")
                    .build();

            // Set up history file
            Path historyPath = Path.of(System.getProperty("user.home"), ".agentforge_history");
            if (Files.notExists(historyPath.getParent())) {
                Files.createDirectories(historyPath.getParent());
            }

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, historyPath.toFile())
                    .build();

            // Load conversation history for display
            Deque<DisplayMessage> displayMessages = new LinkedList<>();
            var recentMessages = chatHistoryService.getRecentChatMessages(20);
            for (var msg : recentMessages) {
                displayMessages.add(new DisplayMessage(
                        msg.getRole(),
                        msg.getContent(),
                        msg.getCreatedAt().toLocalTime().toString()
                ));
            }

            // Print welcome and draw initial screen
            terminal.writer().println();
            drawScreen(terminal, displayMessages, "");

            List<ChatMessage> conversationHistory = new ArrayList<>();
            for (var msg : recentMessages) {
                conversationHistory.add(new ChatMessage(msg.getRole(), msg.getContent()));
            }

            while (true) {
                // Move cursor to input area
                Size size = terminal.getSize();
                int inputRow = size.getRows() - 2;

                // Draw separator line
                terminal.writer().println();
                terminal.writer().print("\u001B[90m" + "─".repeat(size.getColumns()) + "\u001B[0m");
                terminal.writer().print("\r");

                // Read input
                String input = reader.readLine("\u001B[32m\u001B[1m>\u001B[0m ");
                input = input.trim();

                // Clear input line
                terminal.writer().print("\r\u001B[K");

                if (input.isEmpty()) {
                    continue;
                }

                if (input.equals(":quit") || input.equals(":exit") || input.equals(":q")) {
                    terminal.writer().println("\u001B[33m再见!\u001B[0m");
                    break;
                }

                if (input.equals(":help") || input.equals(":h")) {
                    printHelp(terminal);
                    drawScreen(terminal, displayMessages, "");
                    continue;
                }

                if (input.equals(":history")) {
                    printHistoryCommand(terminal, displayMessages);
                    drawScreen(terminal, displayMessages, "");
                    continue;
                }

                if (input.equals(":clear") || input.equals(":c")) {
                    displayMessages.clear();
                    conversationHistory.clear();
                    clearScreen(terminal);
                    drawScreen(terminal, displayMessages, "");
                    continue;
                }

                if (input.equals(":summary")) {
                    showSummaryCommand(terminal, displayMessages);
                    drawScreen(terminal, displayMessages, "");
                    continue;
                }

                // Add user message to display
                String currentTime = java.time.LocalTime.now().toString();
                displayMessages.add(new DisplayMessage("user", input, currentTime));

                // Save user message
                chatHistoryService.saveUserMessage(input);

                // Build request
                conversationHistory.add(ChatMessage.user(input));

                ChatRequest request = ChatRequest.builder()
                        .system(systemPrompt)
                        .messages(new ArrayList<>(conversationHistory))
                        .build();

                // Show thinking indicator
                drawScreen(terminal, displayMessages, "\u001B[90m思考中...\u001B[0m");

                // Get response with streaming
                StringBuilder responseBuilder = new StringBuilder();
                llmProvider.chatStream(request)
                        .doOnNext(chunk -> {
                            responseBuilder.append(chunk);
                        })
                        .blockLast();

                String responseText = responseBuilder.toString();

                // Add assistant message to display
                displayMessages.add(new DisplayMessage("assistant", responseText, currentTime));

                // Save assistant message
                chatHistoryService.saveAssistantMessage(responseText);

                // Add to conversation history
                conversationHistory.add(ChatMessage.assistant(responseText));

                // Redraw screen with new messages
                drawScreen(terminal, displayMessages, "");
            }

            terminal.close();
        } catch (Exception e) {
            log.error("Terminal error", e);
            System.err.println("终端错误: " + e.getMessage());
        }
    }

    private void drawScreen(Terminal terminal, Deque<DisplayMessage> messages, String status) {
        Size size = terminal.getSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        // Move cursor to top
        terminal.writer().print("\u001B[" + (rows - 1) + "H");
        terminal.writer().print("\u001B[2J"); // Clear screen

        // Draw header
        terminal.writer().print("\u001B[36m");
        terminal.writer().print("╔" + "═".repeat(cols - 2) + "╗\n");
        terminal.writer().print("║" + centerText(" AgentForge 对话 ", cols - 2) + "║\n");
        terminal.writer().print("╚" + "═".repeat(cols - 2) + "╝\u001B[0m");
        terminal.writer().println();

        // Calculate message area
        int messageAreaRows = rows - 6; // Leave space for header, input area, status
        int startRow = 3;

        // Draw messages (newest at bottom)
        StringBuilder messageArea = new StringBuilder();
        int currentRow = startRow;
        int maxWidth = cols - 2;

        List<DisplayMessage> msgList = new ArrayList<>(messages);
        // Show most recent messages that fit in the screen
        int startIdx = Math.max(0, msgList.size() - messageAreaRows);

        for (int i = startIdx; i < msgList.size(); i++) {
            DisplayMessage msg = msgList.get(i);
            String prefix = "user".equals(msg.role) ? "\u001B[32m你\u001B[0m" : "\u001B[35m助手\u001B[0m";
            String timePrefix = "\u001B[90m[" + msg.time + "]\u001B[0m";

            messageArea.append(timePrefix).append(" ").append(prefix).append(": ");
            messageArea.append(wrapText(msg.content, maxWidth - 5, "     "));
            messageArea.append("\n");
        }

        terminal.writer().print(messageArea.toString());

        // Draw status if any
        if (status != null && !status.isEmpty()) {
            terminal.writer().print("\u001B[90m" + status + "\u001B[0m");
            terminal.writer().println();
        }

        // Draw separator
        terminal.writer().print("\u001B[90m" + "─".repeat(cols) + "\u001B[0m");
        terminal.writer().println();

        // Draw input hint
        terminal.writer().print("\u001B[90m输入消息，或 :help/:quit\u001B[0m");
        terminal.writer().println();

        terminal.writer().flush();
    }

    private void clearScreen(Terminal terminal) {
        terminal.writer().print("\u001B[2J");
        terminal.writer().flush();
    }

    private String centerText(String text, int width) {
        int padding = (width - text.length()) / 2;
        return " ".repeat(Math.max(0, padding)) + text + " ".repeat(Math.max(0, width - text.length() - padding));
    }

    private String wrapText(String text, int maxWidth, String indent) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            if (line.length() <= maxWidth) {
                result.append(line);
            } else {
                int start = 0;
                while (start < line.length()) {
                    int end = Math.min(start + maxWidth, line.length());
                    result.append(line, start, end);
                    start = end;
                    if (start < line.length()) {
                        result.append("\n").append(indent);
                    }
                }
            }
            if (lineIdx < lines.length - 1) {
                result.append("\n").append(indent);
            }
        }
        return result.toString();
    }

    private void printHelp(Terminal terminal) {
        terminal.writer().println("\u001B[36m=== 可用命令 ===\u001B[0m");
        terminal.writer().println("  :help, :h   - 显示帮助");
        terminal.writer().println("  :history     - 显示最近消息");
        terminal.writer().println("  :clear, :c  - 清除对话上下文");
        terminal.writer().println("  :summary     - 显示今日总结");
        terminal.writer().println("  :quit, :q   - 退出对话");
        terminal.writer().flush();
    }

    private void printHistoryCommand(Terminal terminal, Deque<DisplayMessage> messages) {
        if (messages.isEmpty()) {
            terminal.writer().println("\u001B[90m暂无消息记录。\u001B[0m");
            terminal.writer().flush();
            return;
        }
        terminal.writer().println("\u001B[36m=== 最近消息 ===\u001B[0m");
        for (var msg : messages) {
            String roleName = "user".equals(msg.role) ? "\u001B[32m用户\u001B[0m" : "\u001B[35m助手\u001B[0m";
            terminal.writer().println("[" + msg.time + "] " + roleName + ": " + msg.content);
        }
        terminal.writer().flush();
    }

    private void showSummaryCommand(Terminal terminal, Deque<DisplayMessage> messages) {
        var today = java.time.LocalDate.now();
        var summary = chatHistoryService.getDailySummary(today);
        if (summary == null) {
            terminal.writer().println("\u001B[90m今日暂无总结，请运行 'history --summary' 生成。\u001B[0m");
            terminal.writer().flush();
            return;
        }
        terminal.writer().println("\u001B[36m=== 今日总结 (" + today + ") ===\u001B[0m");
        terminal.writer().println("消息数: " + summary.getMessageCount());
        terminal.writer().println("总结: " + summary.getSummary());
        terminal.writer().flush();
    }
}
