package io.ailink.agentforge.agent.loop;

/** 流式增量：{@code kind} 是 {@code text} 或 {@code thinking}。 */
@FunctionalInterface
public interface DeltaListener {
    DeltaListener NONE = (k, t) -> { };

    void onDelta(String kind, String text);
}
