package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 浮框（终端里的 child frame / posframe，coxswain {@code coxn.popup}）：圆角边框的小框，压在正文上面画。
 * 内容是若干行 {@code List<Seg>}；可以有选中行（菜单）、标题、滚动条。放置：
 * <ul>
 *   <li>{@link Anchor#CURSOR}：光标下方（放不下换上方），横向挤回屏幕；</li>
 *   <li>{@link Anchor#CENTER}：屏幕中间偏上；</li>
 *   <li>{@link Anchor#SCREEN}：屏幕坐标 (x, y)。</li>
 * </ul>
 * 显示 / 收起：{@link Editor#showPopup(Popup)} / {@link Editor#closePopup(Popup)}；菜单：{@link Editor#popupMenu}。
 */
public final class Popup {
    public enum Anchor { CURSOR, CENTER, SCREEN }

    List<List<Seg>> rows = new ArrayList<>();
    String title;
    Anchor anchor = Anchor.CURSOR;
    int x, y;
    boolean preferAbove;
    int selected = -1;
    int top;
    int maxRows = 12;
    int minWidth;
    Consumer<Integer> onSelect;
    Runnable onClose;

    public Popup() {}

    public Popup(List<List<Seg>> rows) { this.rows = new ArrayList<>(rows); }

    /** 纯文字行。 */
    public static Popup ofLines(List<String> lines) {
        Popup p = new Popup();
        for (String l : lines) p.rows.add(List.of(Seg.of(l)));
        return p;
    }

    public List<List<Seg>> rows() { return rows; }
    public Popup rows(List<List<Seg>> r) { rows = new ArrayList<>(r); return this; }
    public Popup title(String t) { title = t; return this; }
    public Popup anchor(Anchor a) { anchor = a; return this; }
    public Popup at(int sx, int sy) { anchor = Anchor.SCREEN; x = sx; y = sy; return this; }
    public Popup preferAbove(boolean b) { preferAbove = b; return this; }
    public Popup maxRows(int n) { maxRows = Math.max(1, n); return this; }
    public Popup minWidth(int w) { minWidth = w; return this; }
    public int selected() { return selected; }
    /** 选中第 i 行（-1 = 不是菜单），自动滚到看得见。 */
    public Popup select(int i) {
        selected = rows.isEmpty() ? -1 : Math.floorMod(i, rows.size());
        if (selected >= 0) {
            if (selected < top) top = selected;
            if (selected >= top + maxRows) top = selected - maxRows + 1;
        }
        return this;
    }
    public Popup onSelect(Consumer<Integer> f) { onSelect = f; return this; }
    public Popup onClose(Runnable r) { onClose = r; return this; }
}
