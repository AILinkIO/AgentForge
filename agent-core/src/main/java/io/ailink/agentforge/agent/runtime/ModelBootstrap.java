package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.models.EnvVars;
import io.ailink.agentforge.agent.models.Models;

import java.util.ArrayList;
import java.util.List;

/**
 * 各入口（行模式、TUI、Web）共用的模型池启动：注册目录里的模型；一家 provider 都没启用、而环境里有某家的 key 时
 * 替用户启用它（首次运行的便利，会记进 state）。
 */
public final class ModelBootstrap {
    private ModelBootstrap() { }

    /** 跑一遍，回要在启动时说的话。 */
    public static List<String> run(AgentRuntime rt) {
        List<String> notes = new ArrayList<>();
        rt.refreshModels();
        Models m = rt.models();
        if (m.providerIds().isEmpty()) {
            List<String> adopted = new ArrayList<>();
            for (String id : Models.knownProviders()) {
                if (m.hasKey(id)) {
                    try {
                        m.selectProvider(id);
                        adopted.add(id);
                    } catch (RuntimeException e) {
                        notes.add("启用 " + id + " 失败：" + e.getMessage());
                    }
                }
            }
            if (!adopted.isEmpty()) {
                rt.refreshModels();
                notes.add("按环境变量里的 key 启用了 provider：" + String.join("、", adopted));
            }
        }
        if (EnvVars.get("AGENTFORGE_MODEL") != null) notes.add("模型取自 $AGENTFORGE_MODEL");
        return notes;
    }
}
