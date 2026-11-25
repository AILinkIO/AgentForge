package io.ailink.agentforge.tui.term;

/** 终端输入事件（{@link InputDecoder} 产出；虚拟终端 / 测试也可以直接造）。 */
public sealed interface InputEvent {
    /** 一个键，Emacs 的 kbd 写法："C-x" "M-f" "C-M-_" "RET" "TAB" "DEL" "SPC" "&lt;up&gt;" "S-&lt;up&gt;" "&lt;f1&gt;" "a" "中"。 */
    record Key(String key) implements InputEvent {}
    /** bracketed paste 的整段文本（不进 undo 合并、不触发补全）。 */
    record Paste(String text) implements InputEvent {}
    /** xterm SGR 鼠标：button 的位 0/1/2 = 左/中/右，+32 拖动，64/65 滚轮上/下，+4/+8/+16 = Shift/Meta/Ctrl。x、y 从 0 起。 */
    record Mouse(int button, int x, int y, boolean release) implements InputEvent {}
    /** 终端焦点进出（?1004）。 */
    record Focus(boolean in) implements InputEvent {}
    /** 窗口大小变了。 */
    record Resize(int cols, int rows) implements InputEvent {}
    /** 认不出的序列（忽略）。 */
    record Unknown(String seq) implements InputEvent {}
}
