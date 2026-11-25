package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.config.AgentPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvidersConfigTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() { AgentPaths.override(null); }

    @Test
    void definesGatewayProviderModelsAndDefault() throws Exception {
        AgentPaths.override(dir);
        Files.writeString(dir.resolve("providers.json"), """
                {"providers": {
                   "litegate": {"api": "anthropic", "baseUrl": "http://127.0.0.1:4000",
                                "models": {"MiniMax-M3.1-Flash": {"context": 200000, "maxOutput": 32000}}},
                   "broken": {"models": ["x"]}
                 },
                 "model": "litegate/MiniMax-M3.1-Flash"}
                """);
        Models m = new Models(new Catalog(), new SelectedProviders(), new Prefs(), new AuthStore());
        List<String> problems = ProvidersConfig.apply(m, dir);
        assertTrue(m.providerEnabled("litegate"));
        assertEquals(ApiKind.ANTHROPIC, m.provider("litegate").api());
        assertEquals("http://127.0.0.1:4000", m.endpoint("litegate"));
        assertEquals(200000L, m.meta("litegate/MiniMax-M3.1-Flash").contextWindow());
        assertTrue(m.keyEnvNames("litegate").contains("LITEGATE_API_KEY"), "没写 apiKeyEnv 时认 <ID>_API_KEY");
        assertEquals(1, problems.size(), "写错的那家只报一句，不拖垮整份：" + problems);
        assertTrue(problems.getFirst().contains("broken"));
    }

    @Test
    void missingFileIsFine() {
        Models m = new Models(new Catalog(), new SelectedProviders(), new Prefs(), new AuthStore());
        assertTrue(ProvidersConfig.apply(m, dir).isEmpty());
    }
}
