package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.text.GapText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * buffer = 一段文本 + 覆盖层 + 局部状态。agent 工作台里只有两类：
 * <ul>
 *   <li><b>文档 buffer</b>（transcript、*Help*）：只读，程序往里追加带 face 的段落、建可折叠 / 可点的覆盖层；</li>
 *   <li><b>输入 buffer</b>（prompt 框、minibuffer）：用户编辑。</li>
 * </ul>
 * <p>这里的编辑方法（insert / delete / replace / append）是<b>程序接口</b>：不查只读（文档 buffer 照样能写），
 * 每次改动平移 marker（窗口 point / start、覆盖层边界）、记 undo、通知监听者。用户命令走 {@link Editor#insert} 等，
 * 那里才查只读。⚠️ 只能在 UI 线程上调；后台线程用 {@link Editor#post(Runnable)} 投过来。
 */
public final class Buffer {
    /** 改动通知：[a, oldEnd) 换成了 [a, newEnd)。 */
    @FunctionalInterface
    public interface ChangeListener { void changed(Buffer b, int a, int oldEnd, int newEnd); }

    String name;
    final GapText text;
    boolean readOnly;
    Integer mark;
    boolean markActive;
    Mode mode = Mode.FUNDAMENTAL;
    final Set<MinorMode> minorModes = new LinkedHashSet<>();
    Keymap localKeymap;
    final UndoList undo = new UndoList();
    final List<Overlay> overlays = new ArrayList<>();
    final List<Marker> markers = new ArrayList<>();
    final Map<String, Object> locals = new HashMap<>();
    private final List<ChangeListener> listeners = new ArrayList<>();
    private long overlayTick;
    private long savedTick;
    private long overlaySeq;
    private List<int[]> invisibleCache;
    private long invisibleCacheKey = -1;
    /** 主模式外的显示名（mode line 用）。 */
    java.util.function.Function<Window, List<Seg>> headerLine;
    ModeLine modeLine;
    /** 正文下面、mode line 上面的几行（每行一串段；null = 没有）。 */
    java.util.function.Function<Window, List<List<Seg>>> footer;
    /** 窗口最上面、header line 之前的几行（状态行、排队之类；null = 没有）。 */
    java.util.function.Function<Window, List<List<Seg>>> topRows;

    public Buffer(String name) { this(name, ""); }

    public Buffer(String name, String text) {
        this.name = name;
        this.text = new GapText(text);
    }

    public String name() { return name; }
    public GapText text() { return text; }
    public int length() { return text.length(); }
    public char charAt(int i) { return text.charAt(i); }
    public String substring(int a, int b) { return text.substring(Math.min(a, b), Math.max(a, b)); }
    public String contents() { return text.toString(); }
    public int lineCount() { return text.lineCount(); }

    public boolean readOnly() { return readOnly; }
    public Buffer setReadOnly(boolean r) { readOnly = r; return this; }

    public Mode mode() { return mode; }
    /** 换主模式（跑它的钩子）。 */
    public Buffer setMode(Mode m) { mode = m; m.runHooks(this); return this; }

    public Set<MinorMode> minorModes() { return minorModes; }
    public Buffer enableMinorMode(MinorMode m) { minorModes.add(m); return this; }
    public Buffer disableMinorMode(MinorMode m) { minorModes.remove(m); return this; }
    public boolean minorModeEnabled(MinorMode m) { return minorModes.contains(m); }

    /** buffer 自己的键表（比主模式表优先）。 */
    public Keymap localKeymap() { return localKeymap; }
    public Buffer setLocalKeymap(Keymap k) { localKeymap = k; return this; }

    public UndoList undo() { return undo; }

    // ── 局部变量（Emacs 的 buffer-local）────────────────────────────────

    /** 局部变量：truncate-lines、word-wrap（visual-line）、tab-width、display-line-numbers … */
    public Object get(String k) { return locals.get(k); }
    public Buffer put(String k, Object v) { locals.put(k, v); overlaysChanged(); return this; }
    public boolean flag(String k) { return Boolean.TRUE.equals(locals.get(k)); }

    /** header line（窗口顶上一行）；null = 没有。 */
    public Buffer setHeaderLine(java.util.function.Function<Window, List<Seg>> f) { headerLine = f; return this; }
    public java.util.function.Function<Window, List<Seg>> headerLine() { return headerLine; }
    /** 这个 buffer 专用的 mode line；null = 用编辑器的全局 mode line。 */
    public Buffer setModeLine(ModeLine m) { modeLine = m; return this; }
    /** 正文下面的固定行（输入框的徽章行之类）；行数可以随窗口变。 */
    public Buffer setFooter(java.util.function.Function<Window, List<List<Seg>>> f) { footer = f; return this; }
    public java.util.function.Function<Window, List<List<Seg>>> footer() { return footer; }
    /** 窗口顶上的几行（在 header line 之前；底色用局部变量 top-rows-face，缺省 default）；行数可以随时变。 */
    public Buffer setTopRows(java.util.function.Function<Window, List<List<Seg>>> f) { topRows = f; return this; }
    public java.util.function.Function<Window, List<List<Seg>>> topRows() { return topRows; }
    public ModeLine modeLine() { return modeLine; }

    // ── mark ──────────────────────────────────────────────────────────

    public Integer mark() { return mark; }
    public boolean markActive() { return markActive && mark != null; }
    public void setMark(Integer m, boolean active) { mark = m; markActive = active; }
    public void deactivateMark() { markActive = false; }

    // ── 改动 ──────────────────────────────────────────────────────────

    /** 编辑以来改过吗（undo 回到原样不算）。 */
    public boolean modified() { return text.tick() != savedTick; }
    public void setUnmodified() { savedTick = text.tick(); }

    public void addChangeListener(ChangeListener l) { listeners.add(l); }
    public void removeChangeListener(ChangeListener l) { listeners.remove(l); }

    /** 把 [a, b) 换成 s（程序接口：不查只读）。 */
    public void replace(int a, int b, String s) {
        if (a > b) { int t = a; a = b; b = t; }
        a = Math.max(0, Math.min(a, length()));
        b = Math.max(a, Math.min(b, length()));
        if (a == b && s.isEmpty()) return;
        String removed = text.substring(a, b);
        undo.record(a, removed, s);
        text.replace(a, b, s);
        int del = b - a, ins = s.length();
        for (Marker m : markers) m.pos = Marker.shift(m.pos, a, del, ins, m.insertionType);
        if (mark != null) mark = Marker.shift(mark, a, del, ins, false);
        boolean evaporated = false;
        for (Overlay o : List.copyOf(overlays)) {
            if (o.evaporate && o.start.pos >= o.end.pos) { removeOverlay(o); evaporated = true; }
            else if (o.end.pos < o.start.pos) o.end.pos = o.start.pos;
        }
        if (evaporated) overlaysChanged();
        for (ChangeListener l : List.copyOf(listeners)) l.changed(this, a, b, a + ins);
    }

    public void insert(int pos, String s) { replace(pos, pos, s); }
    public void delete(int a, int b) { replace(a, b, ""); }

    /** 末尾追加；⇒ 追加段的起点。 */
    public int append(String s) {
        int a = length();
        insert(a, s);
        return a;
    }

    /** 末尾追加一段带 face 的文字；⇒ 盖住它的覆盖层（可以接着设 action / props）。 */
    public Overlay append(String s, String face) {
        int a = append(s);
        return addOverlay(a, a + s.length()).face(face);
    }

    /** 整个换掉（undo 记一次）。 */
    public void setContents(String s) { replace(0, length(), s); }

    /** 清空文字和覆盖层。 */
    public void clear() {
        for (Overlay o : List.copyOf(overlays)) removeOverlay(o);
        replace(0, length(), "");
    }

    // ── marker / 覆盖层 ───────────────────────────────────────────────

    /** 新 marker（用完 detach）。 */
    public Marker marker(int pos, boolean insertionType) {
        Marker m = new Marker(this, Math.max(0, Math.min(pos, length())), insertionType);
        markers.add(m);
        return m;
    }

    /** 新覆盖层 [a, b)，边界默认照 Emacs（front-advance nil, rear-advance nil）。 */
    public Overlay addOverlay(int a, int b) { return addOverlay(a, b, false, false); }

    public Overlay addOverlay(int a, int b, boolean frontAdvance, boolean rearAdvance) {
        Overlay o = new Overlay(this, Math.min(a, b), Math.max(a, b), frontAdvance, rearAdvance, overlaySeq++);
        overlays.add(o);
        overlaysChanged();
        return o;
    }

    void removeOverlay(Overlay o) {
        if (overlays.remove(o)) {
            o.start.detach();
            o.end.detach();
            overlaysChanged();
        }
    }

    public List<Overlay> overlays() { return List.copyOf(overlays); }

    /** 盖住 pos 的覆盖层（start ≤ pos &lt; end），priority 大的在前。 */
    public List<Overlay> overlaysAt(int pos) {
        List<Overlay> out = new ArrayList<>();
        for (Overlay o : overlays) if (o.contains(pos)) out.add(o);
        out.sort(Buffer::byPriorityDesc);
        return out;
    }

    /** 和 [a, b) 相交的覆盖层（空覆盖层落在区间里也算），priority 大的在前。 */
    public List<Overlay> overlaysIn(int a, int b) {
        List<Overlay> out = new ArrayList<>();
        for (Overlay o : overlays) {
            int s = o.start.pos, e = o.end.pos;
            if (s < b && e > a || s == e && s >= a && s < b) out.add(o);
        }
        out.sort(Buffer::byPriorityDesc);
        return out;
    }

    static int byPriorityDesc(Overlay x, Overlay y) {
        if (x.priority != y.priority) return Integer.compare(y.priority, x.priority);
        return Long.compare(y.seq, x.seq);      // 同优先级：后建的在上
    }

    /** pos 之后（不含）第一个满足 pred 的覆盖层起点；没有 ⇒ -1。块级导航用。 */
    public int nextOverlayStart(int pos, Predicate<Overlay> pred) {
        int best = -1;
        for (Overlay o : overlays) if (o.start.pos > pos && pred.test(o) && (best < 0 || o.start.pos < best)) best = o.start.pos;
        return best;
    }

    /** pos 之前（不含）最后一个满足 pred 的覆盖层起点；没有 ⇒ -1。 */
    public int previousOverlayStart(int pos, Predicate<Overlay> pred) {
        int best = -1;
        for (Overlay o : overlays) if (o.start.pos < pos && pred.test(o) && o.start.pos > best) best = o.start.pos;
        return best;
    }

    /** 盖住 pos、满足 pred 的覆盖层里最内层（最短）的；没有 ⇒ null。 */
    public Overlay overlayAt(int pos, Predicate<Overlay> pred) {
        Overlay best = null;
        for (Overlay o : overlays)
            if (o.contains(pos) && pred.test(o) && (best == null || o.end.pos - o.start.pos < best.end.pos - best.start.pos)) best = o;
        return best;
    }

    void overlaysChanged() { overlayTick++; }

    /** 文字或显示属性变了就变：显示层的折行缓存用它判断失效。 */
    public long displayTick() { return text.tick() * 1_000_003L + overlayTick; }

    /** 不可见区间（合并好的、升序的 [a, b)）；按 displayTick 缓存。 */
    List<int[]> invisibleIntervals() {
        long k = displayTick();
        if (k == invisibleCacheKey) return invisibleCache;
        List<int[]> iv = new ArrayList<>();
        for (Overlay o : overlays) if (o.invisible && o.start.pos < o.end.pos) iv.add(new int[]{o.start.pos, o.end.pos});
        iv.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] x : iv) {
            if (!merged.isEmpty() && merged.getLast()[1] >= x[0]) merged.getLast()[1] = Math.max(merged.getLast()[1], x[1]);
            else merged.add(x.clone());
        }
        invisibleCache = merged;
        invisibleCacheKey = k;
        return merged;
    }

    /** 盖住 pos 的不可见区间（a ≤ pos &lt; b），没有 ⇒ null。 */
    int[] invisibleAt(int pos) {
        List<int[]> iv = invisibleIntervals();
        int lo = 0, hi = iv.size() - 1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            int[] x = iv.get(m);
            if (pos < x[0]) hi = m - 1;
            else if (pos >= x[1]) lo = m + 1;
            else return x;
        }
        return null;
    }

    private List<Overlay> prefixCache;
    private long prefixCacheKey = -1;

    /** 盖住 pos 的带续行前缀的覆盖层（多个取 priority 大的；没有 ⇒ null）。按 displayTick 缓存一份按起点排好的表。 */
    Overlay wrapPrefixAt(int pos) {
        long k = displayTick();
        if (k != prefixCacheKey) {
            List<Overlay> l = new ArrayList<>();
            for (Overlay o : overlays) if (o.wrapPrefix != null && o.start.pos < o.end.pos) l.add(o);
            l.sort((x, y) -> Integer.compare(x.start.pos, y.start.pos));
            prefixCache = l;
            prefixCacheKey = k;
        }
        List<Overlay> l = prefixCache;
        // 起点 ≤ pos 的最后一个下标，往前扫（前缀覆盖层一般不嵌套，扫几步就停）
        int lo = 0, hi = l.size() - 1, at = -1;
        while (lo <= hi) {
            int m = (lo + hi) >>> 1;
            if (l.get(m).start.pos <= pos) { at = m; lo = m + 1; } else hi = m - 1;
        }
        Overlay best = null;
        for (int i = at, seen = 0; i >= 0 && seen < 64; i--, seen++) {
            Overlay o = l.get(i);
            if (o.contains(pos) && (best == null || byPriorityDesc(o, best) < 0)) best = o;
        }
        return best;
    }

    /** 隐藏区间 [a, b) 开头那个覆盖层的替代文字（多个取 priority 大的）。 */
    Overlay displayOverlayAt(int a) {
        Overlay best = null;
        for (Overlay o : overlays)
            if (o.invisible && o.start.pos == a && o.display != null && (best == null || byPriorityDesc(o, best) < 0)) best = o;
        return best;
    }

    @Override public String toString() { return "Buffer[" + name + "]"; }
}
