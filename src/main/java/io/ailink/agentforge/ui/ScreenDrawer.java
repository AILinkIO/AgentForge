package io.ailink.agentforge.ui;

import java.util.Collection;

/**
 * 屏幕绘制器接口
 *
 * 负责终端界面的渲染和绘制。
 * 支持：
 * - 清屏
 * - 绘制标题栏
 * - 绘制消息列表
 * - 绘制分隔线
 * - 绘制输入提示
 *
 * 实现类应处理：
 * - ANSI 转义序列
 * - 颜色和样式
 * - 文本自动换行
 * - 光标定位
 */
public interface ScreenDrawer {

    /**
     * 清空整个屏幕
     */
    void clearScreen();

    /**
     * 绘制完整的聊天界面
     *
     * @param messages 消息列表
     * @param status  状态提示（如"思考中..."）
     */
    void drawChatScreen(Collection<DisplayMessage> messages, String status);

    /**
     * 绘制分隔线
     *
     * @param width 分隔线宽度
     */
    void drawSeparator(int width);

    /**
     * 绘制标题栏
     *
     * @param title  标题文本
     * @param width  标题栏宽度
     */
    void drawHeader(String title, int width);

    /**
     * 绘制输入提示
     *
     * @param hint 提示文本
     */
    void drawInputHint(String hint);
}
