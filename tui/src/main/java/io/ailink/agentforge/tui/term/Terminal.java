package io.ailink.agentforge.tui.term;

import java.io.IOException;

/**
 * 终端后端：真终端（{@link JLineTerminal}）或无头的 {@link VirtualTerminal}（测试）。
 * 只管 raw mode、尺寸、读字符、写字节；按键解码与绘制在上层（照 coxswain：JLine 只管这些）。
 */
public interface Terminal {
    /** 进入：raw mode、主屏、bracketed paste、焦点事件。onResize 在任意线程回调。 */
    void enter(Runnable onResize) throws IOException;

    /** 恢复终端（幂等）。 */
    void leave();

    /** {cols, rows}。 */
    int[] size();

    /** 读一个字符码：timeout 毫秒（0 = 一直等）⇒ 码 / -2 超时 / -1 EOF。 */
    int read(long timeoutMs) throws IOException;

    void write(String s);

    /** 开 / 关 xterm 鼠标（?1000 ?1002 ?1006）。 */
    default void setMouse(boolean on) { write(on ? "\u001b[?1000h\u001b[?1002h\u001b[?1006h" : "\u001b[?1006l\u001b[?1002l\u001b[?1000l"); }

    /** 开 / 关扩展按键（kitty flag 1 + modifyOtherKeys 1）：C-; C-? C-S-a 送得出来；终端不认就忽略。 */
    default void setExtendedKeys(boolean on) { write(on ? "\u001b[>1u\u001b[>4;1m" : "\u001b[<u\u001b[>4;0m"); }

    /** OSC 52：把文本放进系统剪贴板（终端支持才有效）。 */
    default void setClipboard(String text) {
        write("\u001b]52;c;" + java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\u0007");
    }

    /** 色深：24 或 8。 */
    default int colorDepth() { return Colors.detectDepth(); }
}
