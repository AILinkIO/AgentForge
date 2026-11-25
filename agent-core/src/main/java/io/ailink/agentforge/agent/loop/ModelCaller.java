package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.CancelToken;

/**
 * 真正去问模型的那一层（L1 的 provider 抽象）。实现：{@link SpringAiModelCaller}（任意 Spring AI
 * {@code ChatModel}）；出错抛 {@link ModelCallException}，以便会话层判断要不要重试。
 */
public interface ModelCaller {
    ModelTurn call(ChatRequest request, DeltaListener onDelta, CancelToken cancel);
}
