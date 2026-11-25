package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.MinibufferRequest;
import io.ailink.agentforge.tui.theme.Theme;
import io.ailink.agentforge.tui.theme.Themes;

import java.util.List;

/** 主题：M-x load-theme（helm 里选，注释是说明，● 标当前）、cycle-theme（都走 M-x；agent 工作台另有 /theme）。 */
public final class ThemeCommands {
    private ThemeCommands() {}

    public static void install(Editor ed) {
        ed.defineCommand("load-theme", "换主题（内置 14 套 + terminal + 注册的）。", e ->
                e.readFromMinibuffer(new MinibufferRequest("Load custom theme: ")
                        .candidates(Themes.names()).requireMatch(true).history("theme").source("Themes")
                        .annotate((x, item) -> {
                            Theme t = Themes.find(item);
                            return (item.equals(x.theme()) ? "● " : "") + (t == null ? "" : t.label());
                        })
                        .onDone(name -> choose(e, name))));
        ed.defineCommand("cycle-theme", "换到下一套主题。", e -> {
            List<String> ns = Themes.names();
            choose(e, ns.get((ns.indexOf(e.theme()) + 1) % ns.size()));
        });
    }

    static void choose(Editor e, String name) {
        e.loadTheme(name);
        e.message("Theme: %s — %s", name, Themes.find(name).label());
    }
}
