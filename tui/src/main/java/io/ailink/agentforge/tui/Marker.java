package io.ailink.agentforge.tui;

/**
 * buffer 里跟着编辑挪的位置（Emacs 的 marker）。insertionType 为 true 时，正好插在它所在位置的文字会把它推到后面
 * （comint 的输出 marker、"跟着末尾走"的窗口 point 就靠它）。不用了要 {@link #detach()}，否则 buffer 一直替它平移。
 */
public final class Marker {
    final Buffer buffer;
    int pos;
    boolean insertionType;

    Marker(Buffer b, int pos, boolean insertionType) {
        this.buffer = b;
        this.pos = pos;
        this.insertionType = insertionType;
    }

    public int pos() { return pos; }
    public Buffer buffer() { return buffer; }
    public void set(int p) { pos = Math.max(0, Math.min(p, buffer.length())); }
    public boolean insertionType() { return insertionType; }
    public void setInsertionType(boolean t) { insertionType = t; }
    public void detach() { buffer.markers.remove(this); }

    /** 编辑 [a, a+del) 换成 ins 个字之后 p 挪到哪（先删后插，照 Emacs）。 */
    static int shift(int p, int a, int del, int ins, boolean advance) {
        if (p < a) return p;
        int q = p <= a + del ? a : p - del;
        if (q > a) return q + ins;
        return advance ? a + ins : a;
    }
}
