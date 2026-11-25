package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Isearch;

/** isearch（C-s / C-r / C-M-s / C-M-r）。 */
public final class SearchCommands {
    private SearchCommands() {}

    public static void install(Editor ed) {
        ed.defineCommand("isearch-forward", "C-s：增量搜索。", e -> Isearch.start(e, 1, false));
        ed.defineCommand("isearch-backward", "C-r：反向增量搜索。", e -> Isearch.start(e, -1, false));
        ed.defineCommand("isearch-forward-regexp", "C-M-s：正则增量搜索。", e -> Isearch.start(e, 1, true));
        ed.defineCommand("isearch-backward-regexp", "C-M-r：反向正则增量搜索。", e -> Isearch.start(e, -1, true));
    }
}
