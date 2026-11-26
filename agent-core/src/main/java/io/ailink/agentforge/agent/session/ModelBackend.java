package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.loop.ModelCaller;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.ModelRef;

/**
 * 会话"问模型"要的那一点：这条模型的元数据 + 这次请求用哪个 {@link ModelCaller}。
 *
 * <p>真实实现在 runtime（{@code ModelClientsBackend}：Anthropic 线格式走原生 caller，OpenAI 线格式走 Spring AI）；测试给一个假
 * caller 就行（{@link #of}）。⚠️ caller 按**每次请求**取：换模型 / 换思考档位都可能换 caller。
 */
public interface ModelBackend {

    /** 这条模型的元数据（窗口、输出上限、思考档位、价钱）；null = 不知道 —— 会话对"不知道"的反应是不作为（不压缩、不估价）。 */
    ModelMeta meta(String modelRef);

    /** 这次请求用哪个 caller。{@code thinking} 是档位名或 null（不发）。没配好时抛（消息给用户看）。 */
    ModelCaller caller(String modelRef, String thinking);

    /** 发给服务端的模型名：{@code provider/模型 id} 只发后半段（整串发过去服务端回 400，而消息里那个名字看起来还挺对）。 */
    default String wireModel(String modelRef) {
        if (modelRef == null) return null;
        ModelRef r = ModelRef.parse(modelRef);
        return r == null ? modelRef : r.modelId();
    }

    /** 这条走 Anthropic 线格式吗（prompt cache 观察、PDF 块支持看它）。 */
    default boolean anthropic(String modelRef) { return false; }

    /** 固定一个 caller、什么元数据都不知道（测试 / 脚本用）。 */
    static ModelBackend of(ModelCaller caller) {
        return new ModelBackend() {
            @Override public ModelMeta meta(String modelRef) { return null; }

            @Override public ModelCaller caller(String modelRef, String thinking) { return caller; }
        };
    }
}
