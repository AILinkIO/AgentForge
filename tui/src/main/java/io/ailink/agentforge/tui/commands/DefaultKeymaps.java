package io.ailink.agentforge.tui.commands;

import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Keymap;

/**
 * 默认键表：照 Emacs global-map（coxswain {@code coxn.keys}，只留 agent 工作台用得上的）。前缀只有 Emacs 的
 * {@code C-x}（只剩退出 {@code C-x C-c}、换窗口 {@code C-x o}）、{@code C-c}（留给模式 / 用户）、{@code C-h}（帮助）；
 * 按了前缀停半秒弹 which-key。分窗、键盘宏、只读、主题这些命令照样定义着，走 {@code M-x}。
 * 上层加键：{@code ed.globalMap().define("C-c C-c", "agent-interrupt").prefixName("C-c", "agent")}。
 */
public final class DefaultKeymaps {
    private DefaultKeymaps() {}

    static final String[][] GLOBAL = {
        // 移动
        {"C-f", "forward-char"}, {"<right>", "forward-char"}, {"C-b", "backward-char"}, {"<left>", "backward-char"},
        {"C-n", "next-line"}, {"<down>", "next-line"}, {"C-p", "previous-line"}, {"<up>", "previous-line"},
        {"C-a", "move-beginning-of-line"}, {"<home>", "move-beginning-of-line"},
        {"C-e", "move-end-of-line"}, {"<end>", "move-end-of-line"},
        {"M-f", "forward-word"}, {"C-<right>", "forward-word"}, {"M-<right>", "forward-word"},
        {"M-b", "backward-word"}, {"C-<left>", "backward-word"}, {"M-<left>", "backward-word"},
        {"M-<", "beginning-of-buffer"}, {"C-<home>", "beginning-of-buffer"},
        {"M->", "end-of-buffer"}, {"C-<end>", "end-of-buffer"},
        {"C-v", "scroll-up-command"}, {"<next>", "scroll-up-command"},
        {"M-v", "scroll-down-command"}, {"<prior>", "scroll-down-command"},
        {"C-l", "recenter-top-bottom"},
        // 编辑
        {"RET", "newline"}, {"C-j", "newline"}, {"C-o", "open-line"},
        {"C-d", "delete-char"}, {"<delete>", "delete-forward-char"},
        {"DEL", "delete-backward-char"},
        {"M-\\", "delete-horizontal-space"}, {"M-SPC", "just-one-space"},
        {"C-t", "transpose-chars"}, {"M-u", "upcase-word"}, {"M-l", "downcase-word"}, {"M-c", "capitalize-word"},
        {"C-q", "quoted-insert"}, {"M-TAB", "completion-at-point"}, {"C-M-i", "completion-at-point"},
        // mark / kill / yank
        {"C-SPC", "set-mark-command"}, {"C-@", "set-mark-command"},
        {"C-w", "kill-region"}, {"M-w", "kill-ring-save"}, {"C-k", "kill-line"}, {"C-S-DEL", "kill-whole-line"},
        {"M-d", "kill-word"}, {"M-DEL", "backward-kill-word"}, {"C-DEL", "backward-kill-word"}, {"C-<delete>", "kill-word"},
        {"C-y", "yank"}, {"M-y", "yank-pop"},
        // undo
        {"C-/", "undo"}, {"C-_", "undo"}, {"C-?", "undo-redo"}, {"C-M-_", "undo-redo"},
        // 前缀参数 / 杂项
        {"C-u", "universal-argument"}, {"M--", "negative-argument"},
        {"C-g", "keyboard-quit"}, {"C-M-g", "keyboard-quit"}, {"ESC ESC ESC", "keyboard-escape-quit"}, {"M-ESC ESC", "keyboard-escape-quit"},
        {"M-x", "execute-extended-command"},
        // C-x 只留退出与换窗口（编辑器那些 C-x 键 —— 分窗、宏、只读、C-x C-x … —— 命令还在，走 M-x）
        {"C-x C-c", "save-buffers-kill-terminal"}, {"C-x o", "other-window"},
        // 搜索
        {"C-s", "isearch-forward"}, {"C-r", "isearch-backward"},
        {"C-M-s", "isearch-forward-regexp"}, {"C-M-r", "isearch-backward-regexp"},
        // 帮助：C-h 前缀（Emacs 原样）；<f1> 同一套（有的终端退格发 ^H，那时用 <f1>）
        {"C-h k", "describe-key"}, {"C-h f", "describe-function"}, {"C-h b", "describe-bindings"},
        {"C-h c", "describe-key-briefly"}, {"C-h w", "where-is"},
        {"<f1> k", "describe-key"}, {"<f1> f", "describe-function"}, {"<f1> b", "describe-bindings"},
        {"<f1> c", "describe-key-briefly"}, {"<f1> w", "where-is"},
    };

    static final String[][] PREFIX_NAMES = {
        {"C-x", "C-x"}, {"<f1>", "help"}, {"C-h", "help"}, {"C-c", "C-c"},
    };


    static final String[][] MINIBUFFER = {
        {"RET", "minibuffer-complete-and-exit"}, {"TAB", "minibuffer-complete"},
        {"M-j", "exit-minibuffer"}, {"C-j", "exit-minibuffer"},
        {"C-n", "next-completion"}, {"<down>", "next-completion"}, {"C-p", "previous-completion"}, {"<up>", "previous-completion"},
        {"M-n", "next-history-element"}, {"M-p", "previous-history-element"},
        {"C-g", "abort-minibuffers"}, {"ESC ESC ESC", "abort-minibuffers"}, {"M-ESC ESC", "abort-minibuffers"},
    };

    public static void install(Editor ed) {
        Keymap g = ed.globalMap();
        for (String[] b : GLOBAL) g.define(b[0], b[1]);
        for (int d = 0; d < 10; d++) for (String m : new String[]{"M-", "C-", "C-M-"}) g.define(m + d, "digit-argument");
        for (String[] p : PREFIX_NAMES) g.prefixName(p[0], p[1]);
        Keymap mm = ed.minibufferMap();
        for (String[] b : MINIBUFFER) mm.define(b[0], b[1]);
    }
}
