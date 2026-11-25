package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Seg;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 记录区里一条（{@code Entry}）排好的样子：一段文字 + 相对偏移上的 face 段、折叠、按钮。
 * {@link TranscriptView} 把它贴进 buffer 时再换成覆盖层。纯数据，不碰编辑器状态（可以在测试里直接看）。
 */
final class Chunk {
    record Span(int a, int b, String face, boolean extend) {
        Span(int a, int b, String face) { this(a, b, face, false); }
    }

    /** 续行前缀：[a, b) 这一行软折行时，续行先画这几段。 */
    record Prefix(int a, int b, List<Seg> segs) { }

    record FoldSpec(int a, int b, String summary, String key, boolean folded, List<Seg> segs, boolean plain) { }

    record Act(int a, int b, Consumer<Editor> action) { }

    final StringBuilder sb = new StringBuilder();
    final List<Span> spans = new ArrayList<>();
    final List<FoldSpec> folds = new ArrayList<>();
    final List<Act> acts = new ArrayList<>();
    final List<Prefix> prefixes = new ArrayList<>();
    /** M-w 复制的原文（null = 用显示的文字）。 */
    String raw;
    /** 块 id（n / p 跳块、M-w 认块）；null = 不算块（状态行之类）。 */
    Object blockId;

    int pos() { return sb.length(); }

    /** 追加一段文字（face 为 null = 默认）。 */
    Chunk add(String s, String face) {
        int a = sb.length();
        sb.append(s);
        if (face != null && !s.isEmpty()) spans.add(new Span(a, sb.length(), face));
        return this;
    }

    Chunk add(String s) { return add(s, null); }

    Chunk face(int a, int b, String face) {
        if (face != null && b > a) spans.add(new Span(a, b, face));
        return this;
    }

    /** face 铺到行尾（[a, b) 要含行尾的换行）。 */
    Chunk ext(int a, int b, String face) {
        if (face != null && b > a) spans.add(new Span(a, b, face, true));
        return this;
    }

    /** [a, b) 那一行的续行前缀（空 ⇒ 不加）。 */
    Chunk prefix(int a, int b, List<Seg> segs) {
        if (b > a && segs != null && !segs.isEmpty() && Seg.width(segs) > 0) prefixes.add(new Prefix(a, b, List.copyOf(segs)));
        return this;
    }

    /** 一行一行加的方便写法：line 末尾不带换行；cont = 续行前缀（单段）。 */
    Chunk line(String text, String face, String cont, String contFace) {
        int a = pos();
        add(text, face);
        if (cont != null) prefix(a, pos(), List.of(Seg.of(cont, contFace)));
        return add("\n");
    }

    Chunk fold(int a, int b, String summary, String key, boolean folded) {
        if (b > a) folds.add(new FoldSpec(a, b, summary, key, folded, null, false));
        return this;
    }

    /** 折叠：摘要是几段各带 face 的字。 */
    Chunk fold(int a, int b, List<Seg> summary, String key, boolean folded) { return fold(a, b, summary, key, folded, false); }

    /** plain = 摘要不压 fold-summary（斜体暗色）那层，只用各段自己的 face。 */
    Chunk fold(int a, int b, List<Seg> summary, String key, boolean folded, boolean plain) {
        StringBuilder sb = new StringBuilder();
        for (Seg sg : summary) sb.append(sg.text());
        // 摘要压在折起来那段开头的 face 上：没写 face 的段要明说「正文色」，不然会沾上开头那几个字的颜色（树线、暗字）
        if (plain) summary = summary.stream().map(sg -> sg.face() == null ? Seg.of(sg.text(), "agent-text") : sg).toList();
        if (b > a) folds.add(new FoldSpec(a, b, sb.toString(), key, folded, List.copyOf(summary), plain));
        return this;
    }

    Chunk act(int a, int b, Consumer<Editor> action) {
        if (b > a) acts.add(new Act(a, b, action));
        return this;
    }

    String text() { return sb.toString(); }
}
