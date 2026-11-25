package io.ailink.agentforge.tui.text;

/**
 * 字符的终端显示宽度（JDK 没有 wcwidth）与按「字」移动（不拆代理对）。
 * 移植自 coxswain {@code coxn.text.width}：East Asian Wide/Fullwidth + emoji 区间宽 2，组合字符 / 零宽宽 0。
 */
public final class Width {
    private Width() {}

    // East Asian Wide / Fullwidth 与常见 emoji 区间（Unicode 15；八卦 2630–2637、268A–268F 按 Unicode 16 算宽，和新版 tmux 一致），成对的 [lo, hi]
    private static final int[] WIDE = {
        0x1100, 0x115F, 0x231A, 0x231B, 0x2329, 0x232A, 0x23E9, 0x23EC, 0x23F0, 0x23F0,
        0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x2630, 0x2637, 0x2648, 0x2653, 0x267F, 0x267F,
        0x268A, 0x268F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
        0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728,
        0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797,
        0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
        0x2E80, 0x303E, 0x3041, 0x33FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF,
        0xA960, 0xA97F, 0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F,
        0xFF00, 0xFF60, 0xFFE0, 0xFFE6, 0x16FE0, 0x16FE4, 0x17000, 0x18CFF, 0x1B000, 0x1B2FF,
        0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A,
        0x1F200, 0x1F202, 0x1F210, 0x1F23B, 0x1F240, 0x1F248, 0x1F250, 0x1F251,
        0x1F260, 0x1F265, 0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C,
        0x1F37E, 0x1F393, 0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0,
        0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440, 0x1F442, 0x1F4FC,
        0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A,
        0x1F595, 0x1F596, 0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5,
        0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D7, 0x1F6DC, 0x1F6DF,
        0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0,
        0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FAFF,
        0x20000, 0x2FFFD, 0x30000, 0x3FFFD,
    };

    private static boolean wide(int cp) {
        int a = 0, b = WIDE.length / 2 - 1;
        while (a <= b) {
            int m = (a + b) >>> 1;
            if (cp < WIDE[2 * m]) b = m - 1;
            else if (cp > WIDE[2 * m + 1]) a = m + 1;
            else return true;
        }
        return false;
    }

    public static boolean zeroWidth(int cp) {
        if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return true;
        if (cp >= 0xFE00 && cp <= 0xFE0F) return true;   // variation selectors
        if (cp >= 0xE0100 && cp <= 0xE01EF) return true;
        int t = Character.getType(cp);
        return t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.FORMAT;
    }

    /** 码点的显示宽度：0、1 或 2。控制字符按 ^X 两格算。 */
    public static int cpWidth(int cp) {
        if (cp < 0x20) return 2;
        if (cp < 0x7F) return 1;
        if (cp == 0x7F) return 2;
        if (cp < 0x300) return 1;
        if (zeroWidth(cp)) return 0;
        return wide(cp) ? 2 : 1;
    }

    /** 字符串的显示宽度（不展 Tab，Tab 按 1 计）。 */
    public static int stringWidth(CharSequence s) {
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            w += cp == 9 ? 1 : cpWidth(cp);
        }
        return w;
    }

    /** pos 之后一个字的位置（跨代理对）。 */
    public static int nextPos(CharSequence cs, int pos) {
        int n = cs.length();
        if (pos >= n) return n;
        if (Character.isHighSurrogate(cs.charAt(pos)) && pos + 1 < n && Character.isLowSurrogate(cs.charAt(pos + 1)))
            return pos + 2;
        return pos + 1;
    }

    public static int prevPos(CharSequence cs, int pos) {
        if (pos <= 0) return 0;
        if (pos >= 2 && Character.isLowSurrogate(cs.charAt(pos - 1)) && Character.isHighSurrogate(cs.charAt(pos - 2)))
            return pos - 2;
        return pos - 1;
    }

    /** 按显示宽度截断到 ≤ w。 */
    public static String truncate(String s, int w) {
        int acc = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cw = cpWidth(cp);
            if (acc + cw > w) return s.substring(0, i);
            acc += cw;
            i += Character.charCount(cp);
        }
        return s;
    }

    /** 超过 w 列截断，末尾放 …。 */
    public static String ellipsize(String s, int w) {
        if (stringWidth(s) <= w) return s;
        return truncate(s, Math.max(0, w - 1)) + "…";
    }

    /** 截断 / 补空格到恰好 w 宽。 */
    public static String padRight(String s, int w) {
        String t = truncate(s, w);
        int tw = stringWidth(t);
        return tw < w ? t + " ".repeat(w - tw) : t;
    }

    /** [0, upto) 的显示列（展 Tab）。 */
    public static int displayCol(CharSequence s, int upto, int tabWidth) {
        int col = 0;
        for (int i = 0; i < upto && i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            col += cp == 9 ? tabWidth - col % tabWidth : cpWidth(cp);
            i += Character.charCount(cp);
        }
        return col;
    }

    /** s 里显示列 ≥ col 的第一个下标（不超过末尾）。 */
    public static int posAtCol(CharSequence s, int col, int tabWidth) {
        int c = 0;
        for (int i = 0; i < s.length(); ) {
            if (c >= col) return i;
            int cp = Character.codePointAt(s, i);
            int cw = cp == 9 ? tabWidth - c % tabWidth : cpWidth(cp);
            if (c + cw > col) return i;
            c += cw;
            i += Character.charCount(cp);
        }
        return s.length();
    }
}
