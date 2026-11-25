package io.ailink.agentforge.agent.model;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 按停：翻一次就不再翻回来。正在飞的 HTTP / 子进程登记一个回调好被一起掐掉。 */
public final class CancelToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();
    private final CancelToken parent;

    public CancelToken() { this(null); }

    private CancelToken(CancelToken parent) { this.parent = parent; }

    /** 子令牌：父令牌翻了它也算翻；它自己翻不影响父。 */
    public CancelToken child() {
        CancelToken c = new CancelToken(this);
        onCancel(c::cancel);
        return c;
    }

    public boolean isCancelled() { return cancelled.get() || (parent != null && parent.isCancelled()); }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable r : hooks) {
                try { r.run(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /** 登记回调；已经翻过则立即执行。返回一个撤销登记的句柄。 */
    public Runnable onCancel(Runnable r) {
        hooks.add(r);
        if (isCancelled()) r.run();
        return () -> hooks.remove(r);
    }

    public void throwIfCancelled() {
        if (isCancelled()) throw new CancelledException();
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() { super("已按停"); }
    }
}
