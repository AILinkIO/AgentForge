package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.List;

/** 窗口布局树的节点：叶子是 {@link Window}，内部节点是 {@link Split}。 */
public abstract sealed class LayoutNode permits Window, Split {
    Split parent;

    public Split parent() { return parent; }

    /** 子树里的窗口，布局顺序（左 / 上在前）。 */
    public List<Window> windows() {
        List<Window> out = new ArrayList<>();
        collect(this, out);
        return out;
    }

    private static void collect(LayoutNode n, List<Window> out) {
        if (n instanceof Window w) out.add(w);
        else if (n instanceof Split s) { collect(s.a, out); collect(s.b, out); }
    }

    /** 包着这个节点的最外层原子组（{@link Split#atomic()} 非空的）；没有 ⇒ null。 */
    public Split atomicRoot() {
        Split found = null;
        for (Split p = this instanceof Split s ? s : parent; p != null; p = p.parent) if (p.atomic != null) found = p;
        return found;
    }
}
