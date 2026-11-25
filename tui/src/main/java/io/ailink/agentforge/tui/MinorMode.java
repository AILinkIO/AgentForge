package io.ailink.agentforge.tui;

/**
 * 次要模式（Emacs 的 minor mode）：名字、mode line 标记（lighter）、键表（开着时比主模式表优先）。
 * buffer 局部开关：{@link Buffer#enableMinorMode} / {@link Buffer#disableMinorMode}。
 */
public record MinorMode(String name, String lighter, Keymap keymap) {
    public MinorMode(String name, String lighter) { this(name, lighter, new Keymap(name)); }
}
