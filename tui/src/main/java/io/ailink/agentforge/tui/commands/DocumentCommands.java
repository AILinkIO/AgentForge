package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Buffer;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Fold;
import io.ailink.agentforge.tui.Mode;
import io.ailink.agentforge.tui.Overlay;
import io.ailink.agentforge.tui.UserError;

/** 文档 buffer（transcript）的命令：点按钮、块级导航、折叠开合。 */
public final class DocumentCommands {
    private DocumentCommands() {}

    /**
     * 只读文档的主模式（transcript 用它或从它派生）：n / p 跳块、TAB 开合折叠、S-TAB 全部折起 / 展开、RET 点按钮、
     * SPC / DEL 翻页、&lt; &gt; 到头 / 尾。
     */
    public static final Mode DOCUMENT_MODE = new Mode("document-mode", "Doc", null);

    static {
        DOCUMENT_MODE.keymap()
                .define("n", "next-block").define("p", "previous-block")
                .define("TAB", "toggle-fold").define("<backtab>", "fold-all").define("C-c C-a", "unfold-all")
                .define("RET", "push-button")
                .define("SPC", "scroll-up-command").define("DEL", "scroll-down-command").define("S-SPC", "scroll-down-command")
                .define("<", "beginning-of-buffer").define(">", "end-of-buffer");
    }

    public static void install(Editor ed) {
        ed.defineCommand("push-button", "RET（按钮上）：调覆盖层的 action。", e -> {
            Overlay o = e.buttonAt(e.point());
            if (o == null && e.point() > 0) o = e.buttonAt(e.point() - 1);
            if (o == null) throw new UserError("No button at point");
            o.action().accept(e);
        });
        ed.defineCommand("next-block", "跳到下一个块（消息 / 工具调用）的开头。", e -> {
            Buffer b = e.currentBuffer();
            int p = e.point();
            for (int i = 0; i < Math.max(1, e.countArg()); i++) {
                int n = b.nextOverlayStart(p, o -> o.get(Fold.BLOCK) != null);
                if (n < 0) throw new UserError("No next block");
                p = n;
            }
            e.goTo(p);
        });
        ed.defineCommand("previous-block", "跳到上一个块的开头（在块中间时先回到本块开头）。", e -> {
            Buffer b = e.currentBuffer();
            int p = e.point();
            for (int i = 0; i < Math.max(1, e.countArg()); i++) {
                int n = b.previousOverlayStart(p, o -> o.get(Fold.BLOCK) != null);
                if (n < 0) throw new UserError("No previous block");
                p = n;
            }
            e.goTo(p);
        });
        ed.defineCommand("toggle-fold", "TAB：开合 point 处（或这一行下面）的折叠。", e -> {
            Overlay o = Fold.at(e.currentBuffer(), e.point());
            if (o == null) throw new UserError("No foldable block here");
            Fold.toggle(o);
            if (o.invisible() && e.point() > o.start() && e.point() < o.end()) e.goTo(o.start());
        });
        ed.defineCommand("fold-all", "折起全部。", e -> Fold.all(e.currentBuffer()).forEach(o -> Fold.setFolded(o, true)));
        ed.defineCommand("unfold-all", "展开全部。", e -> Fold.all(e.currentBuffer()).forEach(o -> Fold.setFolded(o, false)));
    }
}
