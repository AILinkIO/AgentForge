package io.ailink.agentforge.agent.todo;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 每段会话一份待办清单（coxswain {@code todo-items / todo-set! / todo-progress / todo-current}）。
 *
 * <p>键是会话 id（{@code ToolEnv.sessionId()}）：子 agent 有自己的清单，不与主线互相冲掉进度。
 * null 归一成 {@code "#main"}。UI 读 {@link #items} / {@link #progress} / {@link #current}，
 * 或者 {@link #listen} 一个回调在每次整份替换后刷新。
 */
public final class TodoStore {
    public static final String MAIN = "#main";

    private final Map<String, List<Todo.Item>> store = new ConcurrentHashMap<>();
    private final List<BiConsumer<String, List<Todo.Item>>> listeners = new CopyOnWriteArrayList<>();

    public static TodoStore of(Workspace ws) { return ws.component(TodoStore.class, TodoStore::new); }

    static String key(String k) { return k == null ? MAIN : k; }

    public List<Todo.Item> items(String key) { return store.getOrDefault(key(key), List.of()); }

    public void clear(String key) { set0(key(key), List.of()); }

    /** 整份替换，回新的条数。一条都认不出也照样设（= 清空：模型交了一份空清单该让屏幕上那块消失）。 */
    public int set(String key, String markdown) {
        List<Todo.Item> items = List.copyOf(Todo.parse(markdown));
        set0(key(key), items);
        return items.size();
    }

    private void set0(String k, List<Todo.Item> items) {
        store.put(k, items);
        for (BiConsumer<String, List<Todo.Item>> l : listeners) {
            try { l.accept(k, items); } catch (RuntimeException ignored) { }
        }
    }

    public record Progress(int done, int total) { }

    public Progress progress(String key) {
        List<Todo.Item> xs = items(key);
        int done = 0;
        for (Todo.Item it : xs) if (it.status() == Todo.Status.DONE) done++;
        return new Progress(done, xs.size());
    }

    /** 正在做的那一条：只回**第一条** DOING（清单有序，取第一条可解释）；没有回 null。 */
    public Todo.Item current(String key) {
        for (Todo.Item it : items(key)) if (it.status() == Todo.Status.DOING) return it;
        return null;
    }

    /** 每次替换后叫一声 (键, 新清单)；回一个撤销句柄。 */
    public Runnable listen(BiConsumer<String, List<Todo.Item>> l) {
        listeners.add(l);
        return () -> listeners.remove(l);
    }
}
