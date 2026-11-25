package io.ailink.agentforge.tui.text;

import java.util.Arrays;

/**
 * 可变文本：gap buffer + 换行位置索引。实现 {@link CharSequence}，所以 {@code java.util.regex} 直接在上面跑（isearch）。
 *
 * <p>coxswain 用的是持久化 rope（undo 直接存旧 rope）；这里是单线程可变模型，undo 改记逆操作（见 {@code UndoList}），
 * 所以文本本身用最简单的 gap buffer：agent 的 transcript 总在末尾追加、输入框很短，gap 几乎不用挪。
 * 位置单位 = UTF-16 单元（Java 字符串下标）。只在 UI 线程上用，不加锁。
 */
public final class GapText implements CharSequence {
    private char[] buf;
    private int gapStart, gapEnd;
    /** 各个 '\n' 的位置，升序。 */
    private int[] nl = new int[16];
    private int nlCount;
    private long tick;

    public GapText() { this(""); }

    public GapText(String s) {
        buf = new char[Math.max(64, s.length() * 2)];
        gapStart = 0;
        gapEnd = buf.length;
        replace(0, 0, s);
        tick = 0;
    }

    /** 每次修改加一：显示层的折行缓存拿它判断是否失效。 */
    public long tick() { return tick; }

    @Override public int length() { return buf.length - (gapEnd - gapStart); }

    @Override public char charAt(int i) {
        if (i < 0 || i >= length()) throw new IndexOutOfBoundsException(i);
        return i < gapStart ? buf[i] : buf[i + gapEnd - gapStart];
    }

    public String substring(int a, int b) {
        a = Math.max(0, a);
        b = Math.min(length(), b);
        if (a >= b) return "";
        char[] out = new char[b - a];
        if (b <= gapStart) System.arraycopy(buf, a, out, 0, b - a);
        else if (a >= gapStart) System.arraycopy(buf, a + gapEnd - gapStart, out, 0, b - a);
        else {
            int k = gapStart - a;
            System.arraycopy(buf, a, out, 0, k);
            System.arraycopy(buf, gapEnd, out, k, b - gapStart);
        }
        return new String(out);
    }

    @Override public CharSequence subSequence(int a, int b) { return substring(a, b); }

    @Override public String toString() { return substring(0, length()); }

    private void moveGap(int pos) {
        if (pos < gapStart) {
            int n = gapStart - pos;
            System.arraycopy(buf, pos, buf, gapEnd - n, n);
            gapStart -= n;
            gapEnd -= n;
        } else if (pos > gapStart) {
            int n = pos - gapStart;
            System.arraycopy(buf, gapEnd, buf, gapStart, n);
            gapStart += n;
            gapEnd += n;
        }
    }

    private void ensureGap(int need) {
        if (gapEnd - gapStart >= need) return;
        int len = length();
        int cap = Math.max(buf.length * 2, len + need + 64);
        char[] nb = new char[cap];
        int tail = buf.length - gapEnd;
        System.arraycopy(buf, 0, nb, 0, gapStart);
        System.arraycopy(buf, gapEnd, nb, cap - tail, tail);
        buf = nb;
        gapEnd = cap - tail;
    }

    /** 把 [a, b) 换成 s。 */
    public void replace(int a, int b, String s) {
        int len = length();
        if (a < 0 || b > len || a > b) throw new IndexOutOfBoundsException("[" + a + "," + b + ") of " + len);
        int del = b - a, ins = s.length();
        // 换行索引：删掉 [a, b) 里的，后面的平移，再插入 s 里的
        int i0 = lowerBound(a), i1 = lowerBound(b);
        int newNl = 0;
        for (int i = 0; i < ins; i++) if (s.charAt(i) == '\n') newNl++;
        int count = nlCount - (i1 - i0) + newNl;
        if (count > nl.length) nl = Arrays.copyOf(nl, Math.max(count, nl.length * 2));
        System.arraycopy(nl, i1, nl, i0 + newNl, nlCount - i1);
        for (int i = i0 + newNl; i < count; i++) nl[i] += ins - del;
        int k = i0;
        for (int i = 0; i < ins; i++) if (s.charAt(i) == '\n') nl[k++] = a + i;
        nlCount = count;
        // 文本
        moveGap(a);
        gapEnd += del;
        ensureGap(ins);
        s.getChars(0, ins, buf, gapStart);
        gapStart += ins;
        tick++;
    }

    /** nl 里第一个 ≥ pos 的下标。 */
    private int lowerBound(int pos) {
        int lo = 0, hi = nlCount;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (nl[m] < pos) lo = m + 1; else hi = m;
        }
        return lo;
    }

    public int lineCount() { return nlCount + 1; }

    /** pos 所在的行号（从 0 起）。 */
    public int lineOf(int pos) { return lowerBound(Math.min(pos, length())); }

    public int lineStart(int row) {
        if (row <= 0) return 0;
        if (row > nlCount) return length();
        return nl[row - 1] + 1;
    }

    /** 行尾（换行符的位置，最后一行是文本末尾）。 */
    public int lineEnd(int row) { return row < nlCount ? nl[row] : length(); }

    public String line(int row) { return substring(lineStart(row), lineEnd(row)); }
}
