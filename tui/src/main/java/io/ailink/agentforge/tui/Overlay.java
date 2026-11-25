package io.ailink.agentforge.tui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 覆盖层（Emacs 的 overlay）：buffer 里一段 [start, end)，带显示属性，跟着编辑挪。agent transcript 靠它做
 * 分段配色、可折叠的块、块级导航、可点的区域。
 * <ul>
 *   <li>{@link #face}：这段的 face（名字；或 {@link #faceSpec} 直接给样式）。多个覆盖层叠在一起时 priority 大的在上，
 *       只写了部分属性的（比如只给底色）和下面的合成。</li>
 *   <li>{@link #invisible}：这段不显示（折叠）；{@link #display} 非空时在原位画这串字代替（"▸ 12 行已折叠"）。</li>
 *   <li>{@link #action}：可点 —— 鼠标点在上面、或 point 在上面按 RET（命令 push-button）就调它。</li>
 *   <li>{@link #keymap}：point 在这段里时先查这张表（Emacs 的 keymap 属性）。</li>
 *   <li>{@link #wrapPrefix}：软折行时续行前面先画这串字（Emacs 的 wrap-prefix，悬挂缩进用）；看行首那个字落在哪个覆盖层里。</li>
 *   <li>{@link #extend}：face 的底色铺到行尾（Emacs face 的 :extend）—— 行尾那个换行落在这个覆盖层里时，这一行剩下的格子也涂上。</li>
 *   <li>{@link #props}：任意属性（如 "block" ⇒ 块 id，块级导航按它找）。</li>
 * </ul>
 * 边界：frontAdvance=false（默认）时正好插在 start 的字算进覆盖层；rearAdvance=false（默认）时插在 end 的字不算。
 * evaporate：变成空的就自动删掉。只在 UI 线程上改。
 */
public final class Overlay {
    final Buffer buffer;
    final Marker start, end;
    final long seq;
    String face;
    io.ailink.agentforge.tui.term.Face faceSpec;
    int priority;
    boolean invisible;
    String display;
    String displayFace;
    java.util.List<Seg> displaySegs;
    Consumer<Editor> action;
    Keymap keymap;
    boolean evaporate;
    java.util.List<Seg> wrapPrefix;
    boolean extend;
    final Map<String, Object> props = new HashMap<>();

    Overlay(Buffer b, int a, int e, boolean frontAdvance, boolean rearAdvance, long seq) {
        buffer = b;
        start = b.marker(a, frontAdvance);
        end = b.marker(e, rearAdvance);
        this.seq = seq;
    }

    public Buffer buffer() { return buffer; }
    public int start() { return start.pos; }
    public int end() { return end.pos; }
    public boolean deleted() { return !buffer.overlays.contains(this); }

    /** 挪到 [a, b)。 */
    public Overlay move(int a, int b) { start.set(a); end.set(b); buffer.overlaysChanged(); return this; }

    public String face() { return face; }
    public Overlay face(String name) { face = name; buffer.overlaysChanged(); return this; }
    public io.ailink.agentforge.tui.term.Face faceSpec() { return faceSpec; }
    public Overlay faceSpec(io.ailink.agentforge.tui.term.Face f) { faceSpec = f; buffer.overlaysChanged(); return this; }
    public int priority() { return priority; }
    public Overlay priority(int p) { priority = p; buffer.overlaysChanged(); return this; }
    public boolean invisible() { return invisible; }
    public Overlay invisible(boolean v) { invisible = v; buffer.overlaysChanged(); return this; }
    public String display() { return display; }
    /** 隐藏时代替显示的文字（换行会换成空格）与它的 face。 */
    public Overlay display(String text, String faceName) {
        display = text == null ? null : text.replace('\n', ' ');
        displayFace = faceName;
        displaySegs = null;
        buffer.overlaysChanged();
        return this;
    }
    public java.util.List<Seg> wrapPrefix() { return wrapPrefix; }
    /** 续行前缀（null / 空 = 不要）与它的 face。 */
    public Overlay wrapPrefix(String prefix, String faceName) {
        return wrapPrefix(prefix == null || prefix.isEmpty() ? null : java.util.List.of(Seg.of(prefix.replace('\n', ' '), faceName)));
    }
    /** 续行前缀：几段各带 face（外层缩进不着底色、里层带底色的那种）。 */
    public Overlay wrapPrefix(java.util.List<Seg> segs) {
        wrapPrefix = segs == null || segs.isEmpty() ? null : java.util.List.copyOf(segs);
        buffer.overlaysChanged();
        return this;
    }
    public boolean extend() { return extend; }
    /** face 铺到行尾。 */
    public Overlay extend(boolean v) { extend = v; buffer.overlaysChanged(); return this; }
    /** 隐藏时代替显示的几段字（各带 face，压在 baseFace 上）。 */
    public Overlay display(java.util.List<Seg> segs, String baseFace) {
        if (segs == null) return display((String) null, baseFace);
        StringBuilder sb = new StringBuilder();
        java.util.List<Seg> clean = new java.util.ArrayList<>();
        for (Seg sg : segs) { String t = sg.text().replace('\n', ' '); sb.append(t); clean.add(Seg.of(t, sg.face())); }
        display = sb.toString();
        displayFace = baseFace;
        displaySegs = java.util.List.copyOf(clean);
        buffer.overlaysChanged();
        return this;
    }
    public java.util.List<Seg> displaySegs() { return displaySegs; }
    public Consumer<Editor> action() { return action; }
    /** 设成可点（按钮）：鼠标单击或 RET 调 action。没设 face 的话用 "button"。 */
    public Overlay action(Consumer<Editor> a) {
        action = a;
        if (face == null && faceSpec == null) face = "button";
        buffer.overlaysChanged();
        return this;
    }
    public Keymap keymap() { return keymap; }
    public Overlay keymap(Keymap k) { keymap = k; return this; }
    public Overlay evaporate(boolean v) { evaporate = v; return this; }
    public Object get(String k) { return props.get(k); }
    public Overlay put(String k, Object v) { props.put(k, v); return this; }

    public boolean contains(int pos) { return start.pos <= pos && pos < end.pos; }

    /** 删掉这个覆盖层。 */
    public void delete() { buffer.removeOverlay(this); }

    @Override public String toString() { return "Overlay[" + start.pos + "," + end.pos + " face=" + face + (invisible ? " invisible" : "") + "]"; }
}
