package io.ailink.agentforge.tui;

/**
 * 布局树的内部节点：两个孩子，上下（BELOW）或左右（RIGHT）分。大小按 ratio（第一个孩子占的比例），
 * 或者固定某个孩子的格数（{@link #setFixedSize}：agent 列的输入框固定几行，transcript 拿剩下的）。
 *
 * <p><b>原子组</b>（Emacs 的 atomic window）：{@link #setAtomic(String)} 之后这棵子树当成一个窗口 ——
 * 在组里任何一个窗口上 split / delete / delete-other-windows 都作用于整个组（agent 列 = transcript + 输入框两个
 * 上下叠着的窗口）；balance-windows 不动组内比例。
 */
public final class Split extends LayoutNode {
    public enum Dir { BELOW, RIGHT }

    Dir dir;
    double ratio;
    LayoutNode a, b;
    String atomic;
    int fixedSize = -1;
    int fixedChild = 1;

    Split(Dir dir, double ratio, LayoutNode a, LayoutNode b) {
        this.dir = dir;
        this.ratio = ratio;
        this.a = a;
        this.b = b;
        a.parent = this;
        b.parent = this;
    }

    public Dir dir() { return dir; }
    public double ratio() { return ratio; }
    public Split setRatio(double r) { ratio = Math.max(0.05, Math.min(0.95, r)); return this; }
    public LayoutNode first() { return a; }
    public LayoutNode second() { return b; }
    public String atomic() { return atomic; }
    /** 标成原子组（name 非空）或取消（null）。 */
    public Split setAtomic(String name) { atomic = name; return this; }
    public int fixedSize() { return fixedSize; }
    public int fixedChild() { return fixedChild; }

    /** 第 child（0 / 1）个孩子固定 cells 行（BELOW）/ 列（RIGHT）；cells &lt; 0 取消、回到 ratio。 */
    public Split setFixedSize(int child, int cells) {
        fixedChild = child;
        fixedSize = cells;
        return this;
    }

    void replaceChild(LayoutNode old, LayoutNode n) {
        if (a == old) a = n; else if (b == old) b = n;
        n.parent = this;
    }

    /** 第一个孩子的大小（总共 total 格，两边至少 1 格）。 */
    int firstSize(int total) {
        int sa;
        if (fixedSize >= 0) sa = fixedChild == 0 ? fixedSize : total - fixedSize;
        else sa = (int) Math.round(total * ratio);
        return Math.max(1, Math.min(total - 1, sa));
    }
}
