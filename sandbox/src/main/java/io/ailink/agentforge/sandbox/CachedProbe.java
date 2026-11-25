package io.ailink.agentforge.sandbox;

import java.util.function.Supplier;

/** 只跑一次的探测（线程安全；探测可能要起进程，结果对本进程生命期有效）。 */
public final class CachedProbe<T> implements Supplier<T> {
    private final Supplier<T> body;
    private volatile T value;

    public CachedProbe(Supplier<T> body) {
        this.body = body;
    }

    @Override
    public T get() {
        T v = value;
        if (v == null) {
            synchronized (this) {
                v = value;
                if (v == null) value = v = body.get();
            }
        }
        return v;
    }
}
