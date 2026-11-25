package io.ailink.agentforge.tui;

import java.util.PriorityQueue;
import java.util.function.LongSupplier;

/**
 * 定时器：都在 UI 线程上跑（Emacs 的 run-at-time / run-with-timer）。主循环按最近的到期时间决定最多睡多久。
 * 时钟可替换（测试里用假时钟 + {@link Editor#runTimers()}）。
 */
public final class Timers {
    /** 一个定时器；{@link #cancel()} 取消。 */
    public static final class Timer implements Comparable<Timer> {
        long due;
        final long repeatMs;
        final Runnable action;
        volatile boolean cancelled;

        Timer(long due, long repeatMs, Runnable action) {
            this.due = due;
            this.repeatMs = repeatMs;
            this.action = action;
        }

        public void cancel() { cancelled = true; }
        public boolean cancelled() { return cancelled; }
        @Override public int compareTo(Timer o) { return Long.compare(due, o.due); }
    }

    private final PriorityQueue<Timer> queue = new PriorityQueue<>();
    LongSupplier clock = System::currentTimeMillis;

    public long now() { return clock.getAsLong(); }

    Timer add(long delayMs, long repeatMs, Runnable r) {
        Timer t = new Timer(now() + delayMs, repeatMs, r);
        queue.add(t);
        return t;
    }

    /** 离最近一个到期还有多少毫秒；没有定时器 ⇒ -1。 */
    long waitMs() {
        while (!queue.isEmpty() && queue.peek().cancelled) queue.poll();
        if (queue.isEmpty()) return -1;
        return Math.max(0, queue.peek().due - now());
    }

    /** 跑到期的；⇒ 跑了几个。 */
    int runDue(Editor ed) {
        int n = 0;
        long now = now();
        while (!queue.isEmpty() && queue.peek().due <= now) {
            Timer t = queue.poll();
            if (t.cancelled) continue;
            n++;
            try {
                t.action.run();
            } catch (RuntimeException e) {
                ed.reportError(e);
            }
            if (t.repeatMs > 0 && !t.cancelled) {
                t.due = now + t.repeatMs;
                queue.add(t);
            }
        }
        return n;
    }
}
