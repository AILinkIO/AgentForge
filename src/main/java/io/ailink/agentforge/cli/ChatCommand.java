package io.ailink.agentforge.cli;

import io.ailink.agentforge.llm.ChatMessage;
import io.ailink.agentforge.llm.ChatRequest;
import io.ailink.agentforge.llm.LlmProvider;
import io.ailink.agentforge.service.ChatHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

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

    private void startInteractiveChat() {
        System.out.println("=== AgentForge 交互式对话 ===");
        System.out.println("输入 :help 查看命令，输入 :quit 退出");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        List<ChatMessage> conversationHistory = new ArrayList<>();

        // Load recent history for context
        var recentMessages = chatHistoryService.getRecentChatMessages(10);
        for (var msg : recentMessages) {
            conversationHistory.add(new ChatMessage(msg.getRole(), msg.getContent()));
        }

        while (true) {
            System.out.print("你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            if (input.equals(":quit") || input.equals(":exit")) {
                System.out.println("再见!");
                break;
            }

            if (input.equals(":help")) {
                printHelp();
                continue;
            }

            if (input.equals(":history")) {
                printHistory();
                continue;
            }

            if (input.equals(":clear")) {
                conversationHistory.clear();
                System.out.println("对话上下文已清除。");
                continue;
            }

            if (input.equals(":summary")) {
                showTodaySummary();
                continue;
            }

            // Save user message
            chatHistoryService.saveUserMessage(input);

            // Build request
            conversationHistory.add(ChatMessage.user(input));

            ChatRequest request = ChatRequest.builder()
                    .system(systemPrompt)
                    .messages(new ArrayList<>(conversationHistory))
                    .build();

            // Get response
            System.out.print("助手: ");
            StringBuilder responseBuilder = new StringBuilder();
            llmProvider.chatStream(request)
                    .doOnNext(chunk -> {
                        System.out.print(chunk);
                        responseBuilder.append(chunk);
                    })
                    .blockLast();
            System.out.println();

            // Save assistant message
            String responseText = responseBuilder.toString();
            chatHistoryService.saveAssistantMessage(responseText);

            // Add to conversation history
            conversationHistory.add(ChatMessage.assistant(responseText));
        }
    }

    private void printHelp() {
        System.out.println("=== 可用命令 ===");
        System.out.println(":help   - 显示帮助");
        System.out.println(":history - 显示最近消息");
        System.out.println(":clear  - 清除对话上下文");
        System.out.println(":summary - 显示今日总结");
        System.out.println(":quit   - 退出对话");
    }

    private void printHistory() {
        var messages = chatHistoryService.getRecentChatMessages(20);
        if (messages.isEmpty()) {
            System.out.println("暂无消息记录。");
            return;
        }
        System.out.println("=== 最近消息 ===");
        for (var msg : messages) {
            String roleName = "user".equals(msg.getRole()) ? "用户" : "助手";
            System.out.println("[" + msg.getCreatedAt().toLocalTime() + "] " + roleName + ": " + msg.getContent());
        }
    }
}
