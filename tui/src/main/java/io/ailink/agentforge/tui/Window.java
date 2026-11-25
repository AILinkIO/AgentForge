package io.ailink.agentforge.tui;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 窗口：显示一个 buffer 的一块屏幕。point 跟窗口走（Emacs 的 window-point：同一个 buffer 两个窗口各有各的 point），
 * point / start 都是 {@link Marker}，buffer 改了自动平移。
 *
 * <p><b>跟着末尾走（follow-tail）</b>：{@link #setFollowTail(boolean)} 开着时 point 的 marker 是"插入时前进"的 ——
 * point 在 buffer 末尾时后台追加的文字会把它推到新末尾，窗口跟着滚；用户往上翻过（point 不在末尾）就不再自动滚，
 * 回到末尾（M-&gt;）又接着跟（agent transcript 要的就是这个）。
 *
 * <p>窗口参数（{@link #param}）：{@link #NO_OTHER_WINDOW}（C-x o 跳过）、{@link #DEDICATED}（不在这里换 buffer）。
 */
public final class Window extends LayoutNode {
    public static final String NO_OTHER_WINDOW = "no-other-window";
    public static final String DEDICATED = "dedicated";

    final int id;
    Buffer buffer;
    Marker point, start;
    int hscroll;
    Integer goalCol;
    boolean followTail;
    final Map<String, Object> params = new HashMap<>();
    /** 每个窗口记自己显示过的 buffer 的位置（换回来时恢复）。 */
    final Map<Buffer, int[]> prevPositions = new IdentityHashMap<>();
    // 显示层的折行缓存（View 管）
    final Map<Integer, View.DLine> layoutCache = new HashMap<>();
    long layoutKey = Long.MIN_VALUE;

    Window(int id, Buffer b) {
        this.id = id;
        attach(b, 0, 0);
    }

    void attach(Buffer b, int p, int s) {
        if (point != null) { point.detach(); start.detach(); }
        buffer = b;
        point = b.marker(p, followTail);
        start = b.marker(s, false);
        hscroll = 0;
        goalCol = null;
        layoutCache.clear();
        layoutKey = Long.MIN_VALUE;
    }

    public int id() { return id; }
    public Buffer buffer() { return buffer; }
    public int point() { return Math.min(point.pos, buffer.length()); }
    public void setPoint(int p) { point.set(p); }
    public int start() { return Math.min(start.pos, buffer.length()); }
    public void setStart(int s) { start.set(s); }

    public boolean followTail() { return followTail; }
    public Window setFollowTail(boolean f) {
        followTail = f;
        point.setInsertionType(f);
        return this;
    }

    public Object param(String k) { return params.get(k); }
    public Window setParam(String k, Object v) { params.put(k, v); return this; }
    public boolean flag(String k) { return Boolean.TRUE.equals(params.get(k)); }

    /** 所在原子组的名字；不在组里 ⇒ null。 */
    public String group() {
        Split s = atomicRoot();
        return s == null ? null : s.atomic;
    }

    @Override public String toString() { return "Window[" + id + " " + buffer.name + "]"; }
}
