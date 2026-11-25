package io.ailink.agentforge.agent.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/** 记下会话发的事件；可以等某个事件出现。 */
public final class Recorder implements SessionListener {
    public final List<SessionEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onEvent(AgentSession session, SessionEvent event) {
        synchronized (events) {
            events.add(event);
            events.notifyAll();
        }
    }

    public <T extends SessionEvent> List<T> of(Class<T> type) {
        List<T> out = new ArrayList<>();
        synchronized (events) { for (SessionEvent e : events) if (type.isInstance(e)) out.add(type.cast(e)); }
        return out;
    }

    public <T extends SessionEvent> T await(Class<T> type, Duration max) throws InterruptedException {
        return await(type, x -> true, max);
    }

    public <T extends SessionEvent> T await(Class<T> type, Predicate<T> p, Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (events) {
            while (true) {
                for (SessionEvent e : events) if (type.isInstance(e) && p.test(type.cast(e))) return type.cast(e);
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new AssertionError("等不到事件 " + type.getSimpleName() + "；已有：" + events);
                events.wait(Math.max(1, left / 1_000_000));
            }
        }
    }

    /** 事件类名序列（去掉 StatusChanged / Delta / UsageUpdated 这些噪声）。 */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        synchronized (events) {
            for (SessionEvent e : events) {
                if (e instanceof SessionEvent.StatusChanged || e instanceof SessionEvent.Delta
                        || e instanceof SessionEvent.UsageUpdated || e instanceof SessionEvent.Assistant) continue;
                out.add(e.getClass().getSimpleName());
            }
        }
        return out;
    }
}
