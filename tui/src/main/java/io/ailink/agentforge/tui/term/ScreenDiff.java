package io.ailink.agentforge.tui.term;

import java.util.HashMap;
import java.util.Map;

/**
 * 双矩阵 diff：拿期望矩阵和上一帧比较，逐行只发变了的那一段（照 Emacs 的 redisplay，coxswain {@code coxn.term.screen/diff}）。
 * 画的时候隐藏光标，SGR 按 face 缓存。
 */
public final class ScreenDiff {
    private ScreenDiff() {}

    /**
     * prev（可为 null，或尺寸不同 ⇒ 整屏重画）⇒ next 的终端输出。
     * cursor：{row, col} 或 null（隐藏）。depth：24 真彩 / 8 色深 256。
     */
    public static String diff(Grid prev, Grid next, int[] cursor, int depth) {
        StringBuilder sb = new StringBuilder();
        int cols = next.cols;
        boolean full = prev == null || prev.cols != cols || prev.rows != next.rows;
        Map<Face, String> sgrCache = new HashMap<>();
        sb.append("\u001b[?25l");
        if (full) sb.append("\u001b[0m\u001b[2J");
        Face cur = null;
        for (int row = 0; row < next.rows; row++) {
            int base = row * cols;
            int first = -1;
            if (full) first = 0;
            else for (int c = 0; c < cols; c++) if (!same(prev, next, base + c)) { first = c; break; }
            if (first < 0) continue;
            int last = cols - 1;
            if (!full) while (last > first && same(prev, next, base + last)) last--;
            // 落在宽字续格上：往回退到宽字本身
            int start = first;
            while (start > 0 && next.text[base + start].isEmpty()) start--;
            sb.append("\u001b[").append(row + 1).append(';').append(start + 1).append('H');
            for (int c = start; c <= last; c++) {
                String t = next.text[base + c];
                if (t.isEmpty()) continue;
                Face f = next.face[base + c];
                if (cur == null || !cur.equals(f)) {
                    sb.append(sgrCache.computeIfAbsent(f, x -> sgr(x, depth)));
                    cur = f;
                }
                sb.append(t);
            }
        }
        sb.append("\u001b[0m");
        if (cursor != null) {
            sb.append("\u001b[").append(cursor[0] + 1).append(';').append(cursor[1] + 1).append('H');
            sb.append("\u001b[?25h");
        }
        return sb.toString();
    }

    private static boolean same(Grid a, Grid b, int i) {
        Face fa = a.face[i], fb = b.face[i];
        return a.text[i].equals(b.text[i]) && (fa == fb || (fa != null && fa.equals(fb)));
    }

    /** face ⇒ ESC[...m。depth 8 时颜色按 256 色出（38;5;n）。 */
    public static String sgr(Face f, int depth) {
        StringBuilder sb = new StringBuilder("\u001b[0");
        if (f.isBold()) sb.append(";1");
        if (f.isItalic()) sb.append(";3");
        if (f.isUnderline()) sb.append(";4");
        if (f.isStrike()) sb.append(";9");
        if (f.underlineColor() != null) {
            int c = f.underlineColor();
            if (depth == 8) sb.append(";58;5;").append(Colors.rgbTo256(c));
            else sb.append(";58;2;").append(c >> 16 & 0xff).append(';').append(c >> 8 & 0xff).append(';').append(c & 0xff);
        }
        if (f.isReverse()) sb.append(";7");
        if (f.fg() != null) color(sb, f.fg(), false, depth);
        if (f.bg() != null) color(sb, f.bg(), true, depth);
        return sb.append('m').toString();
    }

    private static void color(StringBuilder sb, int c, boolean bg, int depth) {
        if (depth == 8) sb.append(bg ? ";48;5;" : ";38;5;").append(Colors.rgbTo256(c));
        else sb.append(bg ? ";48;2;" : ";38;2;").append(c >> 16 & 0xff).append(';').append(c >> 8 & 0xff).append(';').append(c & 0xff);
    }
}
