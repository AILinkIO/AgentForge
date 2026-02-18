package io.ailink.agentforge.ui;

import org.jline.terminal.Size;

import java.io.PrintWriter;
import java.util.Collection;

/**
 * ANSI 屏幕绘制器实现
 *
 * 使用 ANSI 转义序列绘制终端界面。
 * 支持颜色、文本换行、光标控制等。
 *
 * 颜色代码：
 * - 黑色: 30
 * - 红色: 31
 * - 绿色: 32
 * - 黄色: 33
 * - 蓝色: 34
 * - 紫色: 35
 * - 青色: 36
 * - 白色: 37
 * - 灰色: 90
 *
 * 样式代码：
 * - 重置: 0
 * - 高亮: 1
 * - 下划线: 4
 */
public class ANSIScreenDrawer implements ScreenDrawer {

    /**
     * 终端管理器
     */
    private final TerminalManager terminalManager;

    /**
     * 写入器
     */
    private final PrintWriter writer;

    /**
     * 颜色常量
     */
    private static final String COLOR_CYAN = "\u001B[36m";    // 青色 - 标题
    private static final String COLOR_GREEN = "\u001B[32m";   // 绿色 - 用户
    private static final String COLOR_PURPLE = "\u001B[35m";  // 紫色 - 助手
    private static final String COLOR_YELLOW = "\u001B[33m";  // 黄色 - 退出
    private static final String COLOR_GRAY = "\u001B[90m";    // 灰色 - 提示
    private static final String COLOR_BOLD = "\u001B[1m";     // 高亮
    private static final String COLOR_RESET = "\u001B[0m";    // 重置

    /**
     * 构造函数
     *
     * @param terminalManager 终端管理器
     */
    public ANSIScreenDrawer(TerminalManager terminalManager) {
        this.terminalManager = terminalManager;
        this.writer = terminalManager.getWriter();
    }

    /**
     * 清空整个屏幕
     */
    @Override
    public void clearScreen() {
        writer.print("\u001B[2J");
        writer.flush();
    }

    /**
     * 绘制完整的聊天界面
     *
     * @param messages 消息列表
     * @param status  状态提示
     */
    @Override
    public void drawChatScreen(Collection<DisplayMessage> messages, String status) {
        Size size = terminalManager.getSize();
        int rows = size.getRows();
        int cols = size.getColumns();

        // 移动光标到终端顶部并清屏
        writer.print("\u001B[" + (rows - 1) + "H");
        clearScreen();

        // 绘制标题栏
        drawHeader(" AgentForge 对话 ", cols);

        // 计算消息显示区域
        int messageAreaRows = rows - 6;

        // 绘制消息
        drawMessages(messages, messageAreaRows, cols);

        // 绘制状态（如果有）
        if (status != null && !status.isEmpty()) {
            writer.print(COLOR_GRAY);
            writer.print(status);
            writer.print(COLOR_RESET);
            writer.println();
        }

        // 绘制分隔线
        drawSeparator(cols);

        // 绘制输入提示
        drawInputHint("输入消息，或 :help/:quit");

        terminalManager.flush();
    }

    /**
     * 绘制消息列表
     *
     * @param messages      消息列表
     * @param maxRows      最大行数
     * @param maxCols      最大列数
     */
    private void drawMessages(Iterable<DisplayMessage> messages, int maxRows, int maxCols) {
        int maxWidth = maxCols - 2;
        int currentRow = 0;

        for (DisplayMessage msg : messages) {
            if (currentRow >= maxRows) {
                break;
            }

            // 时间前缀
            writer.print(COLOR_GRAY);
            writer.print("[");
            writer.print(msg.getTime());
            writer.print("]");
            writer.print(COLOR_RESET);
            writer.print(" ");

            // 角色前缀和消息内容
            if (msg.isUser()) {
                writer.print(COLOR_GREEN);
                writer.print("你");
            } else {
                writer.print(COLOR_PURPLE);
                writer.print("助手");
            }
            writer.print(COLOR_RESET);
            writer.print(": ");

            // 绘制内容（处理换行）
            drawWrappedText(msg.getContent(), maxWidth - 5, "     ");
            writer.println();

            currentRow++;
        }
    }

    /**
     * 绘制自动换行的文本
     *
     * @param text   文本内容
     * @param width  最大宽度
     * @param indent 缩进文本
     */
    private void drawWrappedText(String text, int width, String indent) {
        if (text == null || text.isEmpty()) {
            return;
        }

        String[] lines = text.split("\n");
        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            if (line.length() <= width) {
                writer.print(line);
            } else {
                // 长行自动换行
                int start = 0;
                while (start < line.length()) {
                    int end = Math.min(start + width, line.length());
                    writer.print(line.substring(start, end));
                    start = end;
                    if (start < line.length()) {
                        writer.print("\n");
                        writer.print(indent);
                    }
                }
            }

            // 不是最后一行则换行
            if (lineIdx < lines.length - 1) {
                writer.print("\n");
                writer.print(indent);
            }
        }
    }

    /**
     * 绘制分隔线
     *
     * @param width 宽度
     */
    @Override
    public void drawSeparator(int width) {
        writer.print(COLOR_GRAY);
        writer.print("─".repeat(width));
        writer.print(COLOR_RESET);
        writer.println();
    }

    /**
     * 绘制标题栏
     *
     * @param title 标题
     * @param width 宽度
     */
    @Override
    public void drawHeader(String title, int width) {
        writer.print(COLOR_CYAN);
        writer.print("╔");
        writer.print("═".repeat(width - 2));
        writer.print("╗");
        writer.println();

        writer.print("║");
        writer.print(centerText(title, width - 2));
        writer.print("║");
        writer.println();

        writer.print("╚");
        writer.print("═".repeat(width - 2));
        writer.print("╝");
        writer.print(COLOR_RESET);
        writer.println();
    }

    /**
     * 绘制输入提示
     *
     * @param hint 提示文本
     */
    @Override
    public void drawInputHint(String hint) {
        writer.print(COLOR_GRAY);
        writer.print(hint);
        writer.print(COLOR_RESET);
        writer.println();
    }

    /**
     * 居中文本
     *
     * @param text  文本
     * @param width 宽度
     * @return 居中的文本
     */
    private String centerText(String text, int width) {
        int padding = Math.max(0, (width - text.length()) / 2);
        String leftPad = " ".repeat(padding);
        String rightPad = " ".repeat(Math.max(0, width - text.length() - padding));
        return leftPad + text + rightPad;
    }
}
