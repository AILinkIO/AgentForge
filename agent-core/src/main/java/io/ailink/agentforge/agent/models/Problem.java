package io.ailink.agentforge.agent.models;

/**
 * 没有可用模型时**缺的是哪一步**（对应 coxswain {@code agent-models-problem}）。
 *
 * <p>存在的全部理由是"报错要说清楚"：一句"没有可用的模型"对着几种完全不同的成因说同一句话，
 * 而它们的下一步动作各不相同（去联网、去改配置、去存 key、去选一个）。
 */
public record Problem(Code code, String message) {

    public enum Code {
        /** 一家都没启用。 */
        NO_PROVIDERS,
        /** 启用了但一个 key 都没有。 */
        NO_KEYS,
        /** 目录里没有能对话的模型（目录可能太旧）。 */
        NO_MODELS,
        /** 一份目录都没有。 */
        NO_CATALOG,
        /** 有模型但没选（**不替用户挑**）。 */
        NO_DEFAULT,
        /** 默认模型不在可用的那几条里（被屏蔽、那家没 key…）。 */
        UNKNOWN_DEFAULT,
        /** 有模型没有归属，已剔除（配置写错）。 */
        ORPHAN_MODELS,
        /** 默认模型建不出客户端（key 不对？端点不通？）。 */
        UNUSABLE_DEFAULT
    }

    @Override
    public String toString() { return code + ": " + message; }
}
