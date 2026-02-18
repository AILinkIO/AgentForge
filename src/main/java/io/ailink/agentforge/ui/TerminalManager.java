package io.ailink.agentforge.ui;

import java.io.PrintWriter;

/**
 * 终端管理器接口
 *
 * 负责终端的初始化、配置和生命周期管理。
 * 封装 JLine3 的复杂性，提供简洁的 API。
 *
 * 主要功能：
 * - 终端初始化和配置
 * - 历史记录管理
 * - 行读取器配置
 *
 * 依赖：
 * - JLine3 库
 */
public interface TerminalManager extends AutoCloseable {

    /**
     * 获取终端写入器
     *
     * @return 终端写入器，用于输出内容
     */
    PrintWriter getWriter();

    /**
     * 获取行读取器
     *
     * @return 行读取器，用于读取用户输入
     */
    org.jline.reader.LineReader getReader();

    /**
     * 获取终端大小
     *
     * @return 终端尺寸（行数和列数）
     */
    org.jline.terminal.Size getSize();

    /**
     * 刷新输出
     */
    void flush();

    /**
     * 关闭终端
     */
    @Override
    void close();
}
