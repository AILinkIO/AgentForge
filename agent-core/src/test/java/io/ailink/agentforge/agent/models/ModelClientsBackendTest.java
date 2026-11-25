package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.auth.Keystore;
import io.ailink.agentforge.agent.auth.SecretKeys;
import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.loop.SpringAiModelCaller;
import io.ailink.agentforge.agent.runtime.CallerPolicy;
import io.ailink.agentforge.agent.runtime.ModelClientsBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 会话的真实后端：按策略挑 caller（只建对象，不发请求）。 */
class ModelClientsBackendTest {

    @TempDir Path tmp;
    Map<String, String> env;
    Models models;
    ModelClients clients;

    @BeforeEach
    void setUp() throws Exception {
        AgentPaths.override(tmp);
        env = EnvVars.override(new HashMap<>());
        Catalog catalog = new Catalog(tmp.resolve("models-dev.json"));
        Files.writeString(catalog.path(), ModelsTest.FAKE_CATALOG);
        AuthStore auth = new AuthStore(tmp.resolve("auth.json.enc"), new SecretKeys(tmp.resolve(".auth-key"), Keystore.NONE));
        models = new Models(catalog, new SelectedProviders(tmp.resolve("providers.json")), new Prefs(tmp.resolve("agent.json")), auth);
        catalog.load();
        clients = new ModelClients(models);
        models.defineProvider("deepseek", null);
        models.defineProvider("minimax", null);
        env.put("DEEPSEEK_API_KEY", "sk-fake-1");
        env.put("MINIMAX_API_KEY", "sk-fake-2");
        models.registerModels();
    }

    @AfterEach
    void tearDown() {
        EnvVars.reset();
        AgentPaths.override(null);
    }

    @Test
    void nativeAnthropicOnlyWhenThinkingIsOn() {
        ModelClientsBackend b = new ModelClientsBackend(models, clients, CallerPolicy.DEFAULT, null);
        assertInstanceOf(SpringAiModelCaller.class, b.caller("deepseek/deepseek-v4-pro", "high"));
        assertInstanceOf(SpringAiModelCaller.class, b.caller("minimax/MiniMax-M3", null));
        assertInstanceOf(SpringAiModelCaller.class, b.caller("minimax/MiniMax-M3", "off"));
        assertFalse(b.caller("minimax/MiniMax-M3", "on") instanceof SpringAiModelCaller, "开思考的 Anthropic 走原生 caller");
        assertTrue(b.anthropic("minimax/MiniMax-M3"));
        assertFalse(b.anthropic("deepseek/deepseek-v4-pro"));
        assertNotNull(b.meta("minimax/MiniMax-M3"));
        assertEquals("MiniMax-M3", b.wireModel("minimax/MiniMax-M3"));
        assertThrows(ModelClients.NotConfigured.class, () -> b.caller("nope/x", null));

        ModelClientsBackend spring = new ModelClientsBackend(models, clients, CallerPolicy.SPRING_AI_ONLY, null);
        assertInstanceOf(SpringAiModelCaller.class, spring.caller("minimax/MiniMax-M3", "on"));
    }
}
