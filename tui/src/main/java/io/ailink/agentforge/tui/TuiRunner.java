package io.ailink.agentforge.tui;

import io.ailink.agentforge.tui.term.Grid;
import io.ailink.agentforge.tui.term.InputDecoder;
import io.ailink.agentforge.tui.term.InputEvent;
import io.ailink.agentforge.tui.term.ScreenDiff;
import io.ailink.agentforge.tui.term.Terminal;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 事件循环（coxswain {@code coxn.main/run-ui}）：调用 {@link #run()} 的线程就是 UI 线程，独占编辑器状态；
 * 输入线程（解码终端字节）与 SIGWINCH 只往队列里投，后台线程用 {@link Editor#post} 投 Runnable（会叫醒主循环）。
 * <b>没有帧率</b>：队列里已到的事件全处理完、post 进来的跑完（每帧最多 50ms，剩下的下一帧接着跑，先画、先收键）、
 * 到期的定时器跑完，才画一帧；画 = 和上一帧 diff，只发变了的格子。
 *
 * <pre>{@code
 * Editor ed = new Editor(80, 24);
 * // … 建 buffer、窗口、键 …
 * new TuiRunner(ed, new JLineTerminal()).run();   // C-x C-c（或 ed.quit()）返回，终端已恢复
 * Runtime.getRuntime().halt(0);                     // JLine 的输入线程阻塞在 read 上，别等它
 * }</pre>
 */
public final class TuiRunner {
    private static final Object WAKE = new Object();

    private final Editor ed;
    private final Terminal term;
    private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private Grid prev;
    private boolean mouseOn, keysOn;

    public TuiRunner(Editor ed, Terminal term) {
        this.ed = ed;
        this.term = term;
    }

    /** 跑到退出为止（之后终端已恢复）。 */
    public void run() throws IOException {
        term.enter(() -> queue.add(new InputEvent.Resize(-1, -1)));
        ed.terminal = term;
        try {
            int[] sz = term.size();
            ed.resize(sz[0], sz[1]);
            if (ed.theme() != null) ed.loadTheme(ed.theme());      // 按真终端的色深重算 face
            ed.wakeHook = () -> queue.add(WAKE);
            Thread input = new Thread(this::readLoop, "agentforge-tui-input");
            input.setDaemon(true);
            input.start();
            loop();
        } finally {
            ed.wakeHook = () -> {};
            term.leave();
        }
    }

    private void readLoop() {
        InputDecoder dec = new InputDecoder(term::read);
        try {
            for (InputEvent ev; (ev = dec.read()) != null; ) queue.add(ev);
        } catch (IOException | RuntimeException e) {
            ed.logError(e);
        }
        queue.add("EOF");
    }

    private void loop() {
        while (!ed.quit) {
            if (ed.dirty) redraw();
            long wait = ed.timers.waitMs();
            Object ev;
            try {
                ev = wait < 0 ? queue.take() : queue.poll(Math.max(1, wait), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            int n = 0;
            while (ev != null) {
                handle(ev);
                if (ed.quit || ++n >= 512) break;
                ev = queue.poll();
            }
            ed.drainPosted(50);
            if (ed.hasPosted()) queue.add(WAKE);        // 剩下的下一帧接着跑
            ed.runTimers();
        }
    }

    private void handle(Object ev) {
        if (ev == WAKE) return;
        if ("EOF".equals(ev)) { ed.quit = true; return; }
        if (ev instanceof InputEvent.Resize) {
            int[] sz = term.size();
            ed.handleEvent(new InputEvent.Resize(sz[0], sz[1]));
            return;
        }
        if (ev instanceof InputEvent.Focus || ev instanceof InputEvent.Unknown) return;
        ed.handleEvent((InputEvent) ev);
    }

    private void redraw() {
        Grid g;
        try {
            g = ed.render();
        } catch (RuntimeException e) {
            ed.logError(e);
            ed.messageRaw(I18n.format("redisplay error: %s", e.getMessage()));
            ed.closeCompletion();
            g = ed.render();
        }
        boolean mouse = ed.options.getBool("mouse"), keys = ed.options.getBool("extended-keys");
        StringBuilder out = new StringBuilder();
        String s = ScreenDiff.diff(prev, g, ed.cursor(), ed.colorDepth());
        term.write(s);
        if (mouse != mouseOn) { term.setMouse(mouse); mouseOn = mouse; }
        if (keys != keysOn) { term.setExtendedKeys(keys); keysOn = keys; }
        if (ed.pendingClipboard != null) { term.setClipboard(ed.pendingClipboard); ed.pendingClipboard = null; }
        prev = g;
        ed.dirty = false;
        out.setLength(0);
    }
}
