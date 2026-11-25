package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.auth.Keystore;
import io.ailink.agentforge.agent.auth.SecretKeys;
import io.ailink.agentforge.agent.models.Catalog;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.Prefs;
import io.ailink.agentforge.agent.models.SelectedProviders;
import io.ailink.agentforge.agent.session.FakeCaller;
import io.ailink.agentforge.agent.session.ModelBackend;
import io.ailink.agentforge.agent.session.SessionConfig;

import java.nio.file.Path;

/** 测试夹具：配置目录、模型表都在临时目录里，模型是假的（不联网、不要 key）。 */
public final class RuntimeFixture {
    private RuntimeFixture() { }

    public static Models models(Path cfg) {
        AuthStore auth = new AuthStore(cfg.resolve("auth.json.enc"), new SecretKeys(cfg.resolve(".auth-key"), Keystore.NONE));
        return new Models(new Catalog(cfg.resolve("models.json")), new SelectedProviders(cfg.resolve("providers.json")),
                new Prefs(cfg.resolve("agent.json")), auth);
    }

    public static AgentRuntime.Builder builder(Path cfg, Path root, FakeCaller fc) {
        return AgentRuntime.builder()
                .configDir(cfg)
                .workspaceRoot(root)
                .models(models(cfg))
                .backend(ModelBackend.of(fc))
                .config(SessionConfig.defaults().retryInitialDelayMs(1));
    }
}
