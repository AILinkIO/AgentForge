package io.ailink.agentforge.tui;

import java.util.List;

/**
 * 文档 buffer 里的「块」与「折叠」（agent transcript：每条消息 / 工具调用一个块，长的输出可以折起来）。
 * 都是普通的 {@link Overlay}，只是约定了属性：
 * <ul>
 *   <li>块：属性 {@code "block"} = 块 id（任意对象）。块级导航（transcript-mode 的 n / p，命令 next-block / previous-block）按它跳。</li>
 *   <li>折叠：属性 {@code "fold"} = true；折起来时 invisible，原位显示摘要（{@link Overlay#display}，face fold-summary）。
 *       TAB（toggle-fold）开合 point 所在的折叠，或者 point 所在行下面紧跟着的那个（标题行上按 TAB）。</li>
 * </ul>
 */
public final class Fold {
    private Fold() {}

    public static final String BLOCK = "block";
    public static final String FOLD = "fold";

    /** 把 [a, b) 标成一个块。 */
    public static Overlay markBlock(Buffer b, int a, int z, Object id) {
        return b.addOverlay(a, z).put(BLOCK, id);
    }

    /**
     * 建一个折叠：[a, b) 是可以藏起来的正文，summary 是折起来时显示的那行字。
     * 正文最后的换行不藏（摘要单占一行，后面的内容照常从下一行开始）。
     */
    public static Overlay create(Buffer b, int a, int z, String summary, boolean folded) {
        if (z > a + 1 && b.charAt(z - 1) == '\n') z--;
        Overlay o = b.addOverlay(a, z).put(FOLD, Boolean.TRUE);
        o.display(summary, "fold-summary");
        o.invisible(folded);
        return o;
    }

    /** 同上，摘要是几段各带 face 的字（压在 fold-summary 上）。 */
    public static Overlay create(Buffer b, int a, int z, List<Seg> summary, boolean folded) {
        return create(b, a, z, summary, folded, "fold-summary");
    }

    /** 同上，摘要的底 face 自己定（null = 只用各段自己的 face：摘要和正文的头一行长得一样）。 */
    public static Overlay create(Buffer b, int a, int z, List<Seg> summary, boolean folded, String baseFace) {
        if (z > a + 1 && b.charAt(z - 1) == '\n') z--;
        Overlay o = b.addOverlay(a, z).put(FOLD, Boolean.TRUE);
        o.display(summary, baseFace);
        o.invisible(folded);
        return o;
    }

    public static boolean isFold(Overlay o) { return Boolean.TRUE.equals(o.get(FOLD)); }

    /** 开合。 */
    public static void toggle(Overlay o) { o.invisible(!o.invisible()); }

    public static void setFolded(Overlay o, boolean folded) { o.invisible(folded); }

    /** pos 处的折叠：盖住 pos 的（最内层），否则 pos 那一行结尾之后紧跟着开始的那个；没有 ⇒ null。 */
    public static Overlay at(Buffer b, int pos) {
        Overlay o = b.overlayAt(pos, Fold::isFold);
        if (o != null) return o;
        int row = b.text().lineOf(pos);
        int eol = b.text().lineEnd(row);
        Overlay best = null;
        for (Overlay x : b.overlays())
            if (isFold(x) && (x.start() == eol + 1 || x.start() == eol) && (best == null || x.end() - x.start() > best.end() - best.start())) best = x;
        if (best == null && pos > 0) best = b.overlayAt(pos - 1, x -> isFold(x) && x.invisible() && x.end() == pos);
        return best;
    }

    /** buffer 里全部的折叠。 */
    public static List<Overlay> all(Buffer b) { return b.overlays().stream().filter(Fold::isFold).toList(); }

    /** pos 所在的块（最内层）；没有 ⇒ null。 */
    public static Overlay blockAt(Buffer b, int pos) { return b.overlayAt(pos, o -> o.get(BLOCK) != null); }
}
