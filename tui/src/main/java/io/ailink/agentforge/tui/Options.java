package io.ailink.agentforge.tui;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局选项（Emacs 的自定义变量；buffer 局部的放在 {@link Buffer#put}）。默认值照 coxswain 的 default-options 里
 * agent 工作台还用得上的那些。
 */
public final class Options {
    private final Map<String, Object> values = new HashMap<>();

    Options() {
        values.put("tab-width", 8);
        values.put("which-key-delay-ms", 500);
        values.put("line-move-visual", true);         // C-n / C-p 按屏幕行走（Emacs 默认）
        values.put("mouse", true);                    // xterm-mouse-mode（Shift+拖动仍是终端自己的选择）
        values.put("extended-keys", true);            // kitty 键盘协议 / modifyOtherKeys
        values.put("minibuffer-display", "bottom");   // "bottom" = helm 样式贴底（spacemacs，用户定的默认）；"popup" = 屏幕中间的浮框
        values.put("color-depth", "auto");            // "auto" / 24 / 8
        values.put("clipboard-osc52", true);          // kill 时用 OSC 52 同步系统剪贴板
        values.put("kill-ring-max", 120);
        values.put("esc-prefix", true);               // 单按的 ESC 当 Meta 前缀（Emacs）；false = 当普通键 "ESC" 派发（agent 工作台：Esc 清空 / 停）
        values.put("completion-auto", true);          // 输入框里打了触发字符（/ @ …）或前缀够长就自动弹补全
        values.put("completion-auto-delay-ms", 150);
        values.put("completion-auto-prefix", 3);
        values.put("echo-area", "line");              // "line" = 最后一行留给 echo area（Emacs）；"overlay" = 有话要说时才叠在最后一行上；"host" = 同 overlay，但普通消息交给宿主自己摆
        values.put("completion-display", "box");      // "box" = 光标旁的浮框（corfu）；"above-window" = 贴在当前窗口正上方的整宽列表（Codex 的 / 菜单）
        values.put("theme", null);                    // null = 默认主题
    }

    public Object get(String k) { return values.get(k); }
    public void set(String k, Object v) { values.put(k, v); }
    public boolean getBool(String k) { return Boolean.TRUE.equals(values.get(k)); }
    public int getInt(String k) { Object v = values.get(k); return v instanceof Number n ? n.intValue() : 0; }
    public String getString(String k) { Object v = values.get(k); return v == null ? null : v.toString(); }
}
