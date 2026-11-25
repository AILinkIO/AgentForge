package io.ailink.agentforge.tui.term;

import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真终端（JLine 3）。照 coxswain {@code coxn.term.terminal}：JLine 只管 raw mode、窗口大小、SIGWINCH、读字符；
 * 进出主屏、bracketed paste 在这里配对；shutdown hook 兜底恢复。
 *
 * <p>⚠️ 编码固定 UTF-8：跟 locale 走的话 LANG 没设时就成了 ASCII，中文全是 ?（coxswain 实测踩到）。
 * ⚠️ 退出时不调 {@code Terminal.close()}：输入线程阻塞在 read 上持着锁，close 会卡死；恢复属性后由调用方
 * {@code Runtime.halt}（见 demo）。
 */
public final class JLineTerminal implements Terminal {
    private static final String ENTER = "\u001b[?1049h\u001b[?2004h\u001b[?1004h\u001b[22;0t";
    private static final String LEAVE = "\u001b[<u\u001b[>4;0m\u001b[?1006l\u001b[?1002l\u001b[?1000l\u001b[?1004l"
            + "\u001b[?2004l\u001b[0m\u001b[?25h\u001b[?1049l\u001b[23;0t";

    private org.jline.terminal.Terminal t;
    private Attributes saved;
    private NonBlockingReader reader;
    private PrintWriter out;
    private final AtomicBoolean restored = new AtomicBoolean(false);

    @Override
    public void enter(Runnable onResize) throws IOException {
        t = TerminalBuilder.builder().system(true).name("agentforge")
                .encoding(StandardCharsets.UTF_8).build();
        if (t instanceof DumbTerminal) {
            t.close();
            throw new IOException("需要一个真终端（拿到的是 dumb 终端；TERM=" + System.getenv("TERM") + "）");
        }
        saved = t.enterRawMode();
        Attributes raw = new Attributes(t.getAttributes());
        // Emacs 要 C-c C-z C-\ C-s C-q 当普通键：关掉信号产生与流控
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        raw.setInputFlag(Attributes.InputFlag.IXON, false);
        t.setAttributes(raw);
        t.handle(org.jline.terminal.Terminal.Signal.WINCH, s -> onResize.run());
        reader = t.reader();
        out = t.writer();
        Runtime.getRuntime().addShutdownHook(new Thread(this::leave, "agentforge-tui-restore"));
        write(ENTER);
    }

    @Override
    public void leave() {
        if (t == null || !restored.compareAndSet(false, true)) return;
        out.print(LEAVE);
        out.flush();
        t.setAttributes(saved);
    }

    @Override
    public int[] size() {
        Size s = t.getSize();
        return new int[]{Math.max(10, s.getColumns()), Math.max(3, s.getRows())};
    }

    @Override
    public int read(long timeoutMs) throws IOException {
        return timeoutMs == 0 ? reader.read() : reader.read(timeoutMs);
    }

    @Override
    public void write(String s) {
        out.print(s);
        out.flush();
    }

    /** 诊断：JLine 选中的终端实现类。 */
    public String info() { return t == null ? "-" : t.getClass().getName(); }
}
