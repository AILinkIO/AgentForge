package io.ailink.agentforge.cli;

import io.ailink.agentforge.service.ChatHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.LocalDate;

/**
 * 历史消息管理命令
 *
 * 提供历史消息的查询和管理功能：
 * - 列出最近的消息
 * - 按日期查询消息
 * - 查看消息统计
 * - 生成/查看每日总结
 *
 * 使用方式：
 * <pre>
 * agentforge history --list                    # 列出最近消息
 * agentforge history --date 2026-02-18         # 按日期查询
 * agentforge history --count                   # 消息统计
 * agentforge history --summary                 # 生成今日总结
 * agentforge history --all-summaries           # 查看所有总结
 * </pre>
 */
@Command(name = "history", mixinStandardHelpOptions = true, description = "历史消息管理")
public class HistoryCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(HistoryCommand.class);

    private final ChatHistoryService chatHistoryService;

    /**
     * 列出最近的消息
     */
    @Option(names = {"--list"}, description = "列出最近的消息")
    private boolean listMessages;

    /**
     * 按日期查看消息
     * 格式：YYYY-MM-DD
     */
    @Option(names = {"--date"}, description = "按日期查看消息 (格式: YYYY-MM-DD)")
    private String dateStr;

    /**
     * 显示消息统计
     */
    @Option(names = {"--count"}, description = "显示消息统计")
    private boolean showCount;

    /**
     * 生成今日总结
     */
    @Option(names = {"--summary"}, description = "生成今日总结")
    private boolean generateSummary;

    /**
     * 查看所有每日总结
     */
    @Option(names = {"--all-summaries"}, description = "查看所有每日总结")
    private boolean showAllSummaries;

    /**
     * 构造函数，Spring 自动注入
     *
     * @param chatHistoryService 聊天历史服务
     */
    public HistoryCommand(ChatHistoryService chatHistoryService) {
        this.chatHistoryService = chatHistoryService;
    }

    /**
     * 命令入口，根据参数执行相应操作
     */
    @Override
    public void run() {
        if (generateSummary) {
            generateTodaySummary();
            return;
        }

        if (showAllSummaries) {
            showAllSummaries();
            return;
        }

        if (showCount) {
            showMessageCount();
            return;
        }

        if (dateStr != null) {
            listMessagesByDate();
            return;
        }

        if (listMessages) {
            listRecentMessages();
            return;
        }

        // 默认：显示最近消息
        listRecentMessages();
    }

    /**
     * 列出最近50条消息
     */
    private void listRecentMessages() {
        var messages = chatHistoryService.getRecentChatMessages(50);
        if (messages.isEmpty()) {
            System.out.println("暂无消息记录。");
            return;
        }
        System.out.println("=== 最近消息 (" + messages.size() + "条) ===");
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
            LocalDate date = LocalDate.parse(dateStr);
            var messages = chatHistoryService.getMessagesByDate(date);
            if (messages.isEmpty()) {
                System.out.println("日期 " + dateStr + " 没有消息记录。");
                return;
            }
            System.out.println("=== " + dateStr + " 消息 (" + messages.size() + "条) ===");
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
     * 显示消息统计信息
     */
    private void showMessageCount() {
        long total = chatHistoryService.getTotalMessageCount();
        var today = LocalDate.now();
        long todayCount = chatHistoryService.getMessageCountByDate(today);

        System.out.println("=== 消息统计 ===");
        System.out.println("总消息数: " + total);
        System.out.println("今日消息: " + todayCount);
    }

    /**
     * 生成今日总结
     */
    private void generateTodaySummary() {
        var today = LocalDate.now();
        try {
            System.out.println("正在生成 " + today + " 的总结...");
            var summary = chatHistoryService.generateDailySummary(today);
            System.out.println("=== 总结已生成 ===");
            System.out.println("日期: " + summary.getSummaryDate());
            System.out.println("消息数: " + summary.getMessageCount());
            System.out.println("总结: " + summary.getSummary());
        } catch (IllegalArgumentException e) {
            System.out.println("错误: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("生成总结时出错: " + e.getMessage());
            log.error("Failed to generate summary", e);
        }
    }

    /**
     * 显示所有每日总结
     */
    private void showAllSummaries() {
        var summaries = chatHistoryService.getAllDailySummaries();
        if (summaries.isEmpty()) {
            System.out.println("暂无每日总结。");
            return;
        }
        System.out.println("=== 所有每日总结 (" + summaries.size() + "条) ===");
        for (var summary : summaries) {
            System.out.println("日期: " + summary.getSummaryDate());
            System.out.println("消息数: " + summary.getMessageCount());
            System.out.println("总结: " + summary.getSummary());
            System.out.println("---");
        }
    }
}
