package io.ailink.agentforge.tui;

/** 屏幕上的矩形；divider = 最后一列画竖分隔线（左右分时左边的窗口）。 */
public record Rect(int x, int y, int w, int h, boolean divider) {
    public boolean contains(int px, int py) { return px >= x && px < x + w && py >= y && py < y + h; }
}
