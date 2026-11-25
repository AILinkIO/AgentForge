package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 跨子系统的事实通知（coxswain {@code coxn.bus}）：只广播已经发生的事实（事件名过去式 + 不可变 payload），
 * 同步派发、按注册顺序；listener 抛错只记进结果，不影响后面的与调用方。和钩子的区别：钩子是控制流的挂点，
 * 总线只是"告诉一声"。线程安全（可以从任何线程 emit，listener 在 emit 的线程上跑）。
 */
public final class EventBus {
    private final Map<String, List<BiConsumer<String, Object>>> listeners = new ConcurrentHashMap<>();

    /** 订阅；⇒ 取消订阅的 Runnable。 */
    public Runnable subscribe(String event, BiConsumer<String, Object> handler) {
        listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> listeners.getOrDefault(event, List.of()).remove(handler);
    }

    /** 同步广播；⇒ listener 抛的错误（没有就是空表）。 */
    public List<Throwable> emit(String event, Object payload) {
        List<Throwable> errors = new ArrayList<>();
        for (BiConsumer<String, Object> l : listeners.getOrDefault(event, List.of())) {
            try { l.accept(event, payload); } catch (Throwable t) { errors.add(t); }
        }
        return errors;
    }

    public int listenerCount(String event) { return listeners.getOrDefault(event, List.of()).size(); }
}
