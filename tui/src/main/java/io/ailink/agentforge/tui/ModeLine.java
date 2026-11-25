package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * mode line = 左右两组段（segment），每段是 {@code Ctx ⇒ List<Seg>}（返回 null / 空表 = 这段不显示）。
 * 编辑器有一份全局的（{@link Editor#modeLine()}，带默认段：窗口号、只读 / 改过标记、buffer 名、位置、主模式与次要模式）；
 * buffer 可以换成自己的（{@link Buffer#setModeLine}），{@link #NONE} = 这个 buffer 的窗口不要 mode line（输入框）。
 * 段的 face 为 null 时用 mode-line / mode-line-inactive。
 */
public final class ModeLine {
    public record Ctx(Editor ed, Window window, boolean selected) {
        public Buffer buffer() { return window.buffer(); }
    }

    public record Segment(String id, int order, boolean right, Function<Ctx, List<Seg>> fn) {}

    /** 不画 mode line。 */
    public static final ModeLine NONE = new ModeLine();

    private final List<Segment> segments = new ArrayList<>();

    /** 加一段（同 id 替换）。order 小的在前；right = 右对齐那组。 */
    public ModeLine add(String id, int order, boolean right, Function<Ctx, List<Seg>> fn) {
        remove(id);
        segments.add(new Segment(id, order, right, fn));
        segments.sort(Comparator.comparingInt(Segment::order));
        return this;
    }

    public ModeLine remove(String id) { segments.removeIf(s -> s.id.equals(id)); return this; }

    public List<Segment> segments() { return List.copyOf(segments); }

    List<Seg> render(Ctx ctx, boolean right) {
        List<Seg> out = new ArrayList<>();
        for (Segment s : segments) {
            if (s.right != right) continue;
            try {
                List<Seg> r = s.fn.apply(ctx);
                if (r != null) out.addAll(r);
            } catch (RuntimeException e) {
                out.add(Seg.of("[" + s.id + "!]", "error"));
            }
        }
        return out;
    }

    /** 默认的 mode line（照 coxswain 的 mode-line-parts）。 */
    static ModeLine standard() {
        ModeLine m = new ModeLine();
        m.add("window-number", 10, false, c -> {
            List<Window> ws = c.ed.windows();
            return ws.size() > 1 ? List.of(Seg.of(" " + (ws.indexOf(c.window) + 1) + " ")) : null;
        });
        m.add("flags", 20, false, c -> {
            Buffer b = c.buffer();
            // 只读的文档 buffer（transcript）不显示"改过"：程序一直在往里写，没有"保存"这回事
            String f1 = b.readOnly() ? "%" : b.modified() ? "*" : "-";
            String f2 = b.readOnly() ? "%" : b.modified() ? "*" : "-";
            return List.of(Seg.of(" -:" + f1 + f2 + "-  "));
        });
        m.add("buffer-name", 30, false, c -> List.of(Seg.of(c.buffer().name(), c.selected ? "mode-line-buffer-id" : null)));
        m.add("position", 40, false, c -> List.of(Seg.of("   " + c.ed.positionLabel(c.window))));
        m.add("line-col", 50, false, c -> {
            Buffer b = c.buffer();
            int p = c.window.point();
            int row = b.text.lineOf(p);
            int ls = b.text.lineStart(row);
            return List.of(Seg.of("  (" + (row + 1) + "," + io.ailink.agentforge.tui.text.Width.displayCol(b.substring(ls, p), p - ls, 8) + ")"));
        });
        m.add("modes", 60, false, c -> {
            Buffer b = c.buffer();
            StringBuilder sb = new StringBuilder("   (").append(b.mode().displayName());
            for (MinorMode mm : b.minorModes()) if (mm.lighter() != null && !mm.lighter().isEmpty()) sb.append(' ').append(mm.lighter());
            return List.of(Seg.of(sb.append(')').toString()));
        });
        m.add("kmacro", 70, false, c -> c.selected && c.ed.recordingMacro() ? List.of(Seg.of("  Def")) : null);
        return m;
    }
}
