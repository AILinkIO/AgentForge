package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 具名钩子（照 Emacs 的 add-hook，coxswain {@code coxn.hooks}）：每个钩子是一串 {函数, 深度}；深度小的先跑（-100..100，
 * 缺省 0），同深度按加入顺序；同一个函数只留一份。钩子抛错只写进 echo area，不中断后面的。在 UI 线程上跑。
 *
 * <p>核心跑的钩子（常量见下）：post-command（每个输入事件之后）、window-configuration-change（布局 / 选中窗口变了）、
 * buffer-list-update、kill-buffer（参数是被关的 buffer）、theme-change、minibuffer-setup / minibuffer-exit。
 */
public final class Hooks {
    public static final String POST_COMMAND = "post-command-hook";
    public static final String WINDOW_CHANGE = "window-configuration-change-hook";
    public static final String KILL_BUFFER = "kill-buffer-hook";
    public static final String THEME_CHANGE = "theme-change-hook";
    public static final String MINIBUFFER_SETUP = "minibuffer-setup-hook";
    public static final String MINIBUFFER_EXIT = "minibuffer-exit-hook";
    public static final String RESIZE = "window-size-change-hook";

    private record Entry(Consumer<Object> f, int depth) {}

    private final Map<String, List<Entry>> hooks = new HashMap<>();
    private final Editor ed;

    Hooks(Editor ed) { this.ed = ed; }

    /** 挂一个钩子函数；参数是钩子的参数（没有就是 null）。 */
    @SuppressWarnings("unchecked")
    public <T> void add(String hook, Consumer<T> f, int depth) {
        List<Entry> es = hooks.computeIfAbsent(hook, k -> new ArrayList<>());
        es.removeIf(e -> e.f == f);
        es.add(new Entry((Consumer<Object>) f, depth));
        es.sort(Comparator.comparingInt(Entry::depth));
    }

    public <T> void add(String hook, Consumer<T> f) { add(hook, f, 0); }

    public void remove(String hook, Consumer<?> f) {
        List<Entry> es = hooks.get(hook);
        if (es != null) es.removeIf(e -> e.f == f);
    }

    /** 跑钩子 hook（参数 arg）。 */
    public void run(String hook, Object arg) {
        List<Entry> es = hooks.get(hook);
        if (es == null || es.isEmpty()) return;
        for (Entry e : List.copyOf(es)) {
            try {
                e.f.accept(arg);
            } catch (RuntimeException ex) {
                ed.message("%s hook failed: %s", hook, ex.getMessage());
                ed.logError(ex);
            }
        }
    }

    public void run(String hook) { run(hook, null); }
}
