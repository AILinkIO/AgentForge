package io.ailink.agentforge.ui;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JLine3 终端管理器实现
 *
 * 封装 JLine3 的复杂配置，提供简洁的终端管理接口。
 * 支持：
 * - 自动终端检测和配置
 * - 命令历史持久化
 * - ANSI 颜色支持
 *
 * 使用示例：
 * <pre>
 * JLineTerminalManager manager = new JLineTerminalManager("AgentForge");
 * try {
 *     PrintWriter writer = manager.getWriter();
 *     LineReader reader = manager.getReader();
 *     String input = reader.readLine("> ");
 * } finally {
 *     manager.close();
 * }
 * </pre>
 */
public class JLineTerminalManager implements TerminalManager {

    private static final Logger log = LoggerFactory.getLogger(JLineTerminalManager.class);

    /**
     * 默认历史文件名称
     */
    private static final String DEFAULT_HISTORY_FILE = ".agentforge_history";

    /**
     * JLine 终端实例
     */
    private final Terminal terminal;

    /**
     * 行读取器
     */
    private final LineReader reader;

    /**
     * 终端写入器
     */
    private final PrintWriter writer;

    /**
     * 构造函数
     *
     * @param terminalName 终端名称，用于显示
     */
    public JLineTerminalManager(String terminalName) {
        try {
            // 构建终端
            this.terminal = TerminalBuilder.builder()
                    .name(terminalName)
                    .build();

            // 获取写入器
            this.writer = terminal.writer();

            // 设置历史文件路径
            Path historyPath = resolveHistoryPath();

            // 构建行读取器
            this.reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, historyPath.toFile())
                    .build();

            log.debug("终端初始化成功: {}", terminalName);
        } catch (IOException e) {
            throw new RuntimeException("终端初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析历史文件路径
     *
     * @return 历史文件路径
     */
    private Path resolveHistoryPath() {
        Path historyPath = Path.of(System.getProperty("user.home"), DEFAULT_HISTORY_FILE);
        try {
            if (Files.notExists(historyPath.getParent())) {
                Files.createDirectories(historyPath.getParent());
            }
        } catch (IOException e) {
            log.warn("无法创建历史文件目录: {}", e.getMessage());
        }
        return historyPath;
    }

    /**
     * 获取终端写入器
     *
     * @return 终端写入器
     */
    @Override
    public PrintWriter getWriter() {
        return writer;
    }

    /**
     * 获取行读取器
     *
     * @return 行读取器
     */
    @Override
    public LineReader getReader() {
        return reader;
    }

    /**
     * 获取终端尺寸
     *
     * @return 终端尺寸
     */
    @Override
    public Size getSize() {
        return terminal.getSize();
    }

    /**
     * 刷新输出缓冲区
     */
    @Override
    public void flush() {
        writer.flush();
    }

    /**
     * 关闭终端
     */
    @Override
    public void close() {
        try {
            terminal.close();
            log.debug("终端已关闭");
        } catch (IOException e) {
            log.warn("关闭终端时出错: {}", e.getMessage());
        }
    }
}
