package io.ailink.agentforge.tui.term;

import io.ailink.agentforge.tui.text.Width;

import java.util.Arrays;

/**
 * 屏幕格子矩阵：格 = 文字（一个字，宽字的续格是 ""，组合字符贴在前一格）+ face（已和 default 合成好的 {@link Face}）。
 * 移植自 coxswain {@code coxn.term.screen}。
 */
public final class Grid {
    public final int cols, rows;
    final String[] text;
    final Face[] face;

    public Grid(int cols, int rows, Face fill) {
        this.cols = cols;
        this.rows = rows;
        text = new String[cols * rows];
        face = new Face[cols * rows];
        Arrays.fill(text, " ");
        Arrays.fill(face, fill);
    }

    private static final String[] ASCII = new String[128];
    static { for (int i = 0; i < 128; i++) ASCII[i] = String.valueOf((char) i); }

    static String cpString(int cp) { return cp >= 0 && cp < 128 ? ASCII[cp] : new String(Character.toChars(cp)); }

    public String text(int row, int col) { return text[row * cols + col]; }
    public Face face(int row, int col) { return face[row * cols + col]; }

    public void set(int row, int col, String s, Face f) {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return;
        int i = row * cols + col;
        String old = text[i];
        // 盖住半个宽字：另一半变成空格（和真终端的行为一致，diff 才对得上）
        if (old.isEmpty() && !s.isEmpty() && col > 0) text[i - 1] = " ";
        if (!old.isEmpty() && col + 1 < cols && text[i + 1].isEmpty()) text[i + 1] = " ";
        text[i] = s;
        face[i] = f;
    }

    /** [c0, c1) 填空格。 */
    public void fill(int row, int c0, int c1, Face f) {
        if (row < 0 || row >= rows) return;
        for (int c = Math.max(0, c0); c < Math.min(c1, cols); c++) set(row, c, " ", f);
    }

    /** 在 (row, col) 放一个字，返回下一列；放不下的宽字用空格补；控制字符画成 ^X。 */
    public int putChar(int row, int col, int cp, Face f, int limit) {
        limit = Math.min(limit, cols);
        if (cp < 0x20) return putChar(row, putChar(row, col, '^', f, limit), cp + 64, f, limit);
        if (cp == 0x7F) return putChar(row, putChar(row, col, '^', f, limit), '?', f, limit);
        int cw = Width.cpWidth(cp);
        if (cw == 0) {
            if (col > 0 && col - 1 < limit && row >= 0 && row < rows) {
                int i = row * cols + col - 1;
                text[i] = text[i] + cpString(cp);
            }
            return col;
        }
        if (col + cw > limit) {
            if (col < limit) fill(row, col, limit, f);
            return limit;
        }
        set(row, col, cpString(cp), f);
        if (cw == 2) set(row, col + 1, "", f);
        return col + cw;
    }

    /** 从 (row, col) 写字符串，截到 limit 列，返回下一列。 */
    public int putString(int row, int col, String s, Face f, int limit) {
        int c = col;
        for (int i = 0; i < s.length() && c < limit; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            c = putChar(row, c, cp, f, limit);
        }
        return c;
    }

    public int putString(int row, int col, String s, Face f) { return putString(row, col, s, f, cols); }

    /** 一行的文本（测试用）。 */
    public String rowString(int row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++) sb.append(text(row, c));
        return sb.toString();
    }

    /** 整屏文本（测试用），行之间换行，行尾空白去掉。 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) sb.append(rowString(r).stripTrailing()).append('\n');
        return sb.toString();
    }
}
