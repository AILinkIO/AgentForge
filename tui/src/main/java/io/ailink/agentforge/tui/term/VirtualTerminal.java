package io.ailink.agentforge.tui.term;

import io.ailink.agentforge.tui.text.Width;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 无头终端（测试 / 录制用）：输入由 {@link #type(String)} 喂进去，输出不进真终端，而是由一个极简 ANSI 解释器
 * 落到字符矩阵上 —— 测试可以检查"按 diff 输出重放之后屏幕上到底是什么"，不只是比对 Grid。
 * 认：CUP（ESC[r;cH）、ESC[2J、SGR（记录不解释）、?25l/h、DEC 私有模式（忽略）、OSC（忽略），UTF-8 文本与宽字。
 */
public final class VirtualTerminal implements Terminal {
    private final LinkedBlockingQueue<Integer> input = new LinkedBlockingQueue<>();
    private int cols, rows;
    private String[] cells;
    private int cr, cc;
    private boolean cursorVisible = true;
    private final StringBuilder raw = new StringBuilder();
    private Runnable onResize = () -> {};
    private String clipboard;

    public VirtualTerminal(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        cells = new String[cols * rows];
        Arrays.fill(cells, " ");
    }

    @Override public void enter(Runnable onResize) { this.onResize = onResize; }
    @Override public void leave() {}
    @Override public int[] size() { return new int[]{cols, rows}; }
    @Override public int colorDepth() { return 24; }

    /** 改尺寸并通知（模拟 SIGWINCH）。 */
    public synchronized void resize(int c, int r) {
        cols = c;
        rows = r;
        cells = new String[c * r];
        Arrays.fill(cells, " ");
        onResize.run();
    }

    /** 往输入里塞原始字符（转义序列照写）。 */
    public void type(String s) { s.chars().forEach(input::add); }

    /** 输入结束（读到 -1）。 */
    public void eof() { input.add(-1); }

    @Override
    public int read(long timeoutMs) {
        try {
            Integer c = timeoutMs == 0 ? input.take() : input.poll(timeoutMs, TimeUnit.MILLISECONDS);
            return c == null ? -2 : c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    @Override
    public synchronized void write(String s) {
        raw.append(s);
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == 27 && i + 1 < s.length() && s.charAt(i + 1) == '[') {
                int j = i + 2;
                while (j < s.length() && (s.charAt(j) < 0x40 || s.charAt(j) > 0x7E)) j++;
                if (j >= s.length()) break;
                csi(s.substring(i + 2, j), s.charAt(j));
                i = j + 1;
            } else if (ch == 27 && i + 1 < s.length() && s.charAt(i + 1) == ']') {
                int j = s.indexOf('\u0007', i);
                if (j < 0) break;
                String osc = s.substring(i + 2, j);
                if (osc.startsWith("52;c;"))
                    clipboard = new String(java.util.Base64.getDecoder().decode(osc.substring(5)), java.nio.charset.StandardCharsets.UTF_8);
                i = j + 1;
            } else {
                int cp = s.codePointAt(i);
                i += Character.charCount(cp);
                put(cp);
            }
        }
    }

    private void csi(String params, char fin) {
        if (fin == 'H') {
            String[] p = params.split(";");
            cr = p.length > 0 && !p[0].isEmpty() ? Integer.parseInt(p[0]) - 1 : 0;
            cc = p.length > 1 && !p[1].isEmpty() ? Integer.parseInt(p[1]) - 1 : 0;
        } else if (fin == 'J' && params.equals("2")) {
            Arrays.fill(cells, " ");
        } else if (params.equals("?25")) {
            cursorVisible = fin == 'h';
        }
    }

    private void put(int cp) {
        int w = Width.cpWidth(cp);
        if (cr < 0 || cr >= rows) return;
        if (w == 0) {
            if (cc > 0) cells[cr * cols + cc - 1] += new String(Character.toChars(cp));
            return;
        }
        if (cc < cols) {
            int i = cr * cols + cc;
            // 盖住半个宽字：另一半变空格（真终端就是这样）
            if (cells[i].isEmpty() && cc > 0) cells[i - 1] = " ";
            if (!cells[i].isEmpty() && cc + 1 < cols && cells[i + 1].isEmpty()) cells[i + 1] = " ";
            cells[i] = new String(Character.toChars(cp));
            if (w == 2 && cc + 1 < cols) {
                if (cc + 2 < cols && !cells[i + 1].isEmpty() && cells[i + 2].isEmpty()) cells[i + 2] = " ";
                cells[i + 1] = "";
            }
        }
        cc += w;
    }

    /** 屏幕上第 row 行的文字。 */
    public synchronized String line(int row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cols; c++) sb.append(cells[row * cols + c]);
        return sb.toString();
    }

    /** 整屏文字，行尾空白去掉。 */
    public synchronized String screen() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) sb.append(line(r).stripTrailing()).append('\n');
        return sb.toString();
    }

    public synchronized int[] cursor() { return new int[]{cr, cc}; }
    public synchronized boolean cursorVisible() { return cursorVisible; }
    /** 至今写出的全部原始字节。 */
    public synchronized String rawOutput() { return raw.toString(); }
    public synchronized void clearRawOutput() { raw.setLength(0); }
    public synchronized String clipboard() { return clipboard; }
}
