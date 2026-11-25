package io.ailink.agentforge.tui.theme;

import io.ailink.agentforge.tui.term.Face;

import java.util.Map;

/**
 * 一个主题。两种（照 coxswain §17，那边又照 spacemacs-theme）：
 * <ul>
 *   <li><b>调色板式</b>：{@code palette} 填满 60 个语义色槽（槽名照抄 spacemacs-theme），face 由共享映射
 *       {@link Themes#paletteToFaces} 推出来。内置 14 套都是这种。槽值 {真彩, 256 色档}。</li>
 *   <li><b>差异式</b>：{@code faces} 只写想改的 face，其余回退到 {@code base}（null = 默认主题，"none" = 不回退）。</li>
 * </ul>
 */
public record Theme(String name, String label, String source, Map<String, int[]> palette,
                    Map<String, Face> faces, String base) {

    public static Theme ofPalette(String name, String label, String source, Map<String, int[]> palette) {
        return new Theme(name, label, source, palette, null, null);
    }

    public static Theme ofFaces(String name, String label, Map<String, Face> faces, String base) {
        return new Theme(name, label, null, null, faces, base);
    }

    public boolean isPalette() { return palette != null; }
}
