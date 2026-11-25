package io.ailink.agentforge.tui;

import java.util.List;

/**
 * 一段带 face 的文字：mode line / header line / 浮框 / which-key 的内容都是 {@code List<Seg>}。
 * face 是 face 名字（主题里查，换主题自动跟着变）。
 */
public record Seg(String text, String face) {
    public static Seg of(String text) { return new Seg(text, null); }
    public static Seg of(String text, String face) { return new Seg(text, face); }

    public static int width(List<Seg> segs) {
        int w = 0;
        for (Seg s : segs) w += io.ailink.agentforge.tui.text.Width.stringWidth(s.text);
        return w;
    }
}
