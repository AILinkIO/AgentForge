package io.ailink.agentforge.tui.term;

import java.util.Locale;

/** 色深探测与真彩 ⇒ xterm 256 色（照 coxswain {@code coxn.theme/rgb->256}、{@code detect-color-depth}）。 */
public final class Colors {
    private Colors() {}

    private static final int[] STEPS = {0, 95, 135, 175, 215, 255};

    private static int idx(int v) {
        int best = 0;
        for (int i = 1; i < 6; i++) if (Math.abs(v - STEPS[i]) < Math.abs(v - STEPS[best])) best = i;
        return best;
    }

    /** 0xRRGGBB ⇒ xterm 256 色下标（6×6×6 色块和 24 级灰里取最近的）。 */
    public static int rgbTo256(int rgb) {
        int r = rgb >> 16 & 0xff, g = rgb >> 8 & 0xff, b = rgb & 0xff;
        int ri = idx(r), gi = idx(g), bi = idx(b);
        int grayI = (int) Math.max(0, Math.min(23, Math.round(((r + g + b) / 3.0 - 8) / 10.0)));
        int gray = 8 + 10 * grayI;
        double dGray = sq(r - gray) + sq(g - gray) + sq(b - gray);
        double dCube = sq(r - STEPS[ri]) + sq(g - STEPS[gi]) + sq(b - STEPS[bi]);
        return dGray < dCube ? 232 + grayI : 16 + 36 * ri + 6 * gi + bi;
    }

    private static double sq(double x) { return x * x; }

    /**
     * 终端色深：24（真彩）或 8（256 色）。COLORTERM=truecolor/24bit ⇒ 24；Windows Terminal ⇒ 24；
     * TERM 带 direct / 256color 或是常见的现代终端 ⇒ 24；别的（xterm、vt100、linux …）⇒ 8。
     */
    public static int detectDepth() {
        String ct = env("COLORTERM"), term = env("TERM");
        if (ct.equals("truecolor") || ct.equals("24bit")) return 24;
        if (System.getenv("WT_SESSION") != null) return 24;
        if (term.matches(".*(direct|256color|kitty|alacritty|wezterm|foot|ghostty|tmux|screen|iterm).*")) return 24;
        if (term.isBlank()) return 24;
        return 8;
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v.toLowerCase(Locale.ROOT);
    }

    /** xterm 256 色下标 ⇒ 0xRRGGBB（16 色按 xterm 默认）。 */
    public static int from256(int n) {
        int[] p16 = {0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xc0c0c0,
                     0x808080, 0xff0000, 0x00ff00, 0xffff00, 0x0000ff, 0xff00ff, 0x00ffff, 0xffffff};
        if (n < 16) return p16[n];
        if (n >= 232) { int g = 8 + 10 * (n - 232); return g << 16 | g << 8 | g; }
        n -= 16;
        return STEPS[n / 36] << 16 | STEPS[n / 6 % 6] << 8 | STEPS[n % 6];
    }

    /** 两色按 t（0..1）混合。 */
    public static int blend(int a, int b, double t) {
        int r = (int) Math.round((a >> 16 & 0xff) * (1 - t) + (b >> 16 & 0xff) * t);
        int g = (int) Math.round((a >> 8 & 0xff) * (1 - t) + (b >> 8 & 0xff) * t);
        int bl = (int) Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return r << 16 | g << 8 | bl;
    }

    /**
     * 底色 bg 往 toward 那边染 t 的一档「衬底」。256 色下保证落到和 bg 不同的那一格（不然染了等于没染），
     * 并直接给那一格的颜色（输出时换算不再走样）。
     */
    public static int tint(int bg, int toward, double t, boolean trueColor) {
        if (trueColor) return blend(bg, toward, t);
        int base = rgbTo256(bg);
        for (double x = t; x <= 0.6; x += 0.02) {
            int i = rgbTo256(blend(bg, toward, x));
            if (i != base) return from256(i);
        }
        return from256(rgbTo256(blend(bg, toward, t)));
    }
}
