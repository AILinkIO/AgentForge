package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 主模式（Emacs 的 major mode）：只剩 agent 工作台要的部分 —— 一个名字、mode line 上的显示名、一张键表（父模式的表当父表）、
 * 进入时跑的钩子（{@code <模式>-hook}）。例：transcript-mode（只读文档：n/p 跳块、TAB 折叠、RET 点按钮）、
 * prompt-mode（输入框：RET 发送）。用 {@link Buffer#setMode(Mode)} 换上。
 */
public final class Mode {
    private final String name;
    private final String displayName;
    private final Keymap keymap;
    private final Mode parent;
    private final List<Consumer<Buffer>> hooks = new ArrayList<>();

    public Mode(String name, String displayName, Mode parent) {
        this.name = name;
        this.displayName = displayName;
        this.parent = parent;
        this.keymap = new Keymap(name);
        if (parent != null) keymap.setParent(parent.keymap);
    }

    public static final Mode FUNDAMENTAL = new Mode("fundamental-mode", "Fundamental", null);
    /** 只读的特殊 buffer（*Help* 等）：q 关窗口。 */
    public static final Mode SPECIAL = new Mode("special-mode", "Special", null);
    static { SPECIAL.keymap.define("q", "quit-window"); }

    public String name() { return name; }
    public String displayName() { return displayName; }
    public Keymap keymap() { return keymap; }
    public Mode parent() { return parent; }

    /** 进入这个模式时调（buffer 作参数）；父模式的钩子先跑。 */
    public Mode addHook(Consumer<Buffer> f) { hooks.add(f); return this; }

    void runHooks(Buffer b) {
        if (parent != null) parent.runHooks(b);
        for (Consumer<Buffer> f : hooks) f.accept(b);
    }

    public boolean derivedFrom(Mode m) { return this == m || parent != null && parent.derivedFrom(m); }
}
