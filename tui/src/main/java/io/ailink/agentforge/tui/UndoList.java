package io.ailink.agentforge.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 线性 undo / redo（Emacs 28 undo-redo 的语义，coxswain 一样）。coxswain 存不可变 rope 快照；这里文本是可变的，
 * 改成记逆操作：一组 = 一条命令里的全部编辑；连续打字每 20 个字合一组（Emacs 的 amalgamation，由命令循环控制边界）。
 * 新的编辑清空 redo。最多留 {@link #LIMIT} 组。
 */
public final class UndoList {
    public static final int LIMIT = 1000;

    record Edit(int pos, String removed, String inserted) {}

    static final class Group {
        final List<Edit> edits = new ArrayList<>();
        int pointBefore;
    }

    private final Deque<Group> undo = new ArrayDeque<>();
    private final Deque<Group> redo = new ArrayDeque<>();
    private Group open;
    private boolean enabled = true;
    boolean applying;
    /** 命令循环在命令开始前设：这一组改动之前的 point（undo 回到这里）。 */
    int pointHint = -1;

    public boolean enabled() { return enabled; }

    /** 关掉 undo（transcript 这种只追加的文档 buffer 不用记）。关掉时清空。 */
    public void setEnabled(boolean e) {
        enabled = e;
        if (!e) clear();
    }

    public void clear() { undo.clear(); redo.clear(); open = null; }

    /** 结束当前组：下一次编辑开新组。 */
    public void boundary() { open = null; }

    /** 当前打开的组里有几次编辑（打字合并用）。 */
    public int openSize() { return open == null ? 0 : open.edits.size(); }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }

    void record(int pos, String removed, String inserted) {
        if (!enabled || applying) return;
        if (open == null) {
            open = new Group();
            open.pointBefore = pointHint >= 0 ? pointHint : pos;
            undo.push(open);
            while (undo.size() > LIMIT) undo.removeLast();
            redo.clear();
        }
        // 连续打字：并进上一条插入
        if (!open.edits.isEmpty() && removed.isEmpty()) {
            Edit last = open.edits.getLast();
            if (last.removed.isEmpty() && last.pos + last.inserted.length() == pos) {
                open.edits.set(open.edits.size() - 1, new Edit(last.pos, "", last.inserted + inserted));
                return;
            }
        }
        open.edits.add(new Edit(pos, removed, inserted));
    }

    /** 撤销一组；⇒ 应该放 point 的位置，没有可撤的 ⇒ -1。currentPoint 记进 redo。 */
    int undo(Buffer b, int currentPoint) { return apply(b, undo, redo, currentPoint); }

    int redo(Buffer b, int currentPoint) { return apply(b, redo, undo, currentPoint); }

    private int apply(Buffer b, Deque<Group> from, Deque<Group> to, int currentPoint) {
        open = null;
        Group g = from.poll();
        if (g == null) return -1;
        Group inv = new Group();
        inv.pointBefore = currentPoint;
        applying = true;
        try {
            for (int i = g.edits.size() - 1; i >= 0; i--) {
                Edit e = g.edits.get(i);
                b.replace(e.pos, e.pos + e.inserted.length(), e.removed);
                inv.edits.add(new Edit(e.pos, e.inserted, e.removed));
            }
        } finally {
            applying = false;
        }
        to.push(inv);
        return g.pointBefore;
    }
}
