package io.ailink.agentforge.tui.term;

/**
 * 一个 face 的样式：前景 / 背景（0xRRGGBB，null = 不指定）与几个属性（null = 不指定）。
 * 照 Emacs：没写的属性在合成时回退到底下那层（最底层是主题的 {@code default} face）。
 * record 自带 equals —— 屏幕 diff 按值比较格子的 face。
 */
public record Face(Integer fg, Integer bg, Boolean bold, Boolean italic, Boolean underline,
                   Boolean reverse, Integer underlineColor, Boolean strike) {

    public static final Face EMPTY = new Face(null, null, null, null, null, null, null, null);

    /** 不带删除线的旧写法。 */
    public Face(Integer fg, Integer bg, Boolean bold, Boolean italic, Boolean underline, Boolean reverse, Integer underlineColor) {
        this(fg, bg, bold, italic, underline, reverse, underlineColor, null);
    }

    public static Face fg(int rgb) { return EMPTY.withFg(rgb); }
    public static Face bg(int rgb) { return EMPTY.withBg(rgb); }
    public static Face of(Integer fg, Integer bg) { return new Face(fg, bg, null, null, null, null, null); }

    public Face withFg(Integer c) { return new Face(c, bg, bold, italic, underline, reverse, underlineColor, strike); }
    public Face withBg(Integer c) { return new Face(fg, c, bold, italic, underline, reverse, underlineColor, strike); }
    public Face withBold(boolean b) { return new Face(fg, bg, b, italic, underline, reverse, underlineColor, strike); }
    public Face withItalic(boolean b) { return new Face(fg, bg, bold, b, underline, reverse, underlineColor, strike); }
    public Face withUnderline(boolean b) { return new Face(fg, bg, bold, italic, b, reverse, underlineColor, strike); }
    public Face withReverse(boolean b) { return new Face(fg, bg, bold, italic, underline, b, underlineColor, strike); }
    public Face withUnderlineColor(Integer c) { return new Face(fg, bg, bold, italic, underline, reverse, c, strike); }
    public Face withStrike(boolean b) { return new Face(fg, bg, bold, italic, underline, reverse, underlineColor, b); }

    /** over 压在 this 上面：over 指定了的属性优先。 */
    public Face merge(Face over) {
        if (over == null || over == EMPTY) return this;
        return new Face(over.fg != null ? over.fg : fg, over.bg != null ? over.bg : bg,
                over.bold != null ? over.bold : bold, over.italic != null ? over.italic : italic,
                over.underline != null ? over.underline : underline, over.reverse != null ? over.reverse : reverse,
                over.underlineColor != null ? over.underlineColor : underlineColor,
                over.strike != null ? over.strike : strike);
    }

    public boolean isBold() { return Boolean.TRUE.equals(bold); }
    public boolean isItalic() { return Boolean.TRUE.equals(italic); }
    public boolean isUnderline() { return Boolean.TRUE.equals(underline); }
    public boolean isStrike() { return Boolean.TRUE.equals(strike); }
    public boolean isReverse() { return Boolean.TRUE.equals(reverse); }

    /** "#rrggbb" ⇒ 0xRRGGBB；形状不对抛异常（指名道姓）。 */
    public static int hex(String s) {
        if (s == null || !s.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("color must be \"#rrggbb\", got " + s);
        return Integer.parseInt(s.substring(1), 16);
    }
}
