package io.ailink.agentforge.agent.models;

import com.anthropic.models.messages.ThinkingConfigParam;
import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.auth.Keystore;
import io.ailink.agentforge.agent.auth.SecretKeys;
import io.ailink.agentforge.agent.config.AgentPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ChatModel 工厂：只建客户端、算选项，不发请求（不联网、不碰真 key）。 */
class ModelClientsTest {

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
    }

    @AfterEach
    void tearDown() {
        EnvVars.reset();
        AgentPaths.override(null);
    }

    @Test
    void 没key时说清楚怎么设() {
        models.defineProvider("deepseek", null);
        ModelClients.NotConfigured e = assertThrows(ModelClients.NotConfigured.class, () -> clients.endpoint("deepseek"));
        assertTrue(e.getMessage().contains("DEEPSEEK_API_KEY"));
        assertThrows(ModelClients.NotConfigured.class, () -> clients.endpoint("没启用"));
        assertThrows(ModelClients.NotConfigured.class, () -> clients.forCurrent(ModelClients.RequestOptions.defaults()));
    }

    @Test
    void 按格式建客户端并缓存() {
        models.defineProvider("deepseek", null);
        models.defineProvider("minimax", null);
        env.put("DEEPSEEK_API_KEY", "sk-fake-1");
        env.put("MINIMAX_API_KEY", "sk-fake-2");
        models.registerModels();
        ModelRef ds = ModelRef.parse("deepseek/deepseek-v4-pro");
        ModelRef mm = ModelRef.parse("minimax/MiniMax-M3");
        assertInstanceOf(OpenAiChatModel.class, clients.chatModel(ds));
        assertInstanceOf(AnthropicChatModel.class, clients.chatModel(mm));
        assertSame(clients.chatModel(ds), clients.chatModel(ModelRef.parse("deepseek/deepseek-budget")), "同一家共用");
        var before = clients.chatModel(ds);
        env.put("DEEPSEEK_API_KEY", "sk-fake-changed");
        assertNotSame(before, clients.chatModel(ds), "换了 key 就是另一个客户端");
        ModelClients.Endpoint ep = clients.endpoint("minimax");
        assertEquals("https://api.minimaxi.com/anthropic", ep.baseUrl());
        assertFalse(ep.toString().contains("sk-"));
        models.selectModel("minimax/MiniMax-M3");
        ModelClients.ChatCall call = clients.forCurrent(ModelClients.RequestOptions.defaults());
        assertEquals(ApiKind.ANTHROPIC, call.api());
        assertEquals("MiniMax-M3", call.options().getModel(), "发给服务端的是模型 id，不是整串 ref");
        assertThrows(ModelClients.NotConfigured.class,
                () -> clients.forRequest(ModelRef.parse("minimax/MiniMax-Image"), null), "不在可用池里");
    }

    @Test
    void Anthropic选项() {
        models.defineProvider("minimax", null);
        env.put("MINIMAX_API_KEY", "k");
        models.registerModels();
        ModelRef ref = ModelRef.parse("minimax/MiniMax-Budget");
        AnthropicChatOptions o = (AnthropicChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults());
        assertEquals(32000, o.getMaxTokens(), "默认 = min(输出上限, 32000)");
        assertNull(o.getThinking(), "没给档位：什么都不发");
        assertNotNull(o.getCacheOptions());

        AnthropicChatOptions budget = (AnthropicChatOptions) clients.options(ref,
                ModelClients.RequestOptions.defaults().withThinking("16384").withMaxTokens(4096));
        ThinkingConfigParam t = budget.getThinking();
        assertTrue(t.isEnabled());
        assertEquals(16384, t.asEnabled().budgetTokens());
        assertEquals(16384 + 4096, budget.getMaxTokens(), "max_tokens 抬到够装下预算 + 正文");

        AnthropicChatOptions off = (AnthropicChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults().withThinking("off"));
        assertTrue(off.getThinking().isDisabled());
        AnthropicChatOptions on = (AnthropicChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults().withThinking("on"));
        assertTrue(on.getThinking().isAdaptive());
        AnthropicChatOptions eff = (AnthropicChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults().withThinking("xhigh"));
        assertTrue(eff.getThinking().isAdaptive());
        assertEquals("xhigh", eff.getOutputConfig().effort().orElseThrow().toString());
        AnthropicChatOptions noCache = (AnthropicChatOptions) clients.options(ref,
                ModelClients.RequestOptions.defaults().withPromptCache(PromptCache.OFF));
        assertNotNull(noCache.getCacheOptions());
    }

    @Test
    void OpenAI选项() {
        models.defineProvider("deepseek", null);
        env.put("DEEPSEEK_API_KEY", "k");
        models.registerModels();
        ModelRef ref = ModelRef.parse("deepseek/deepseek-v4-pro");
        OpenAiChatOptions o = (OpenAiChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults().withThinking("max"));
        assertEquals("deepseek-v4-pro", o.getModel());
        assertEquals("max", o.getReasoningEffort());
        assertEquals(32000, o.getMaxTokens());
        OpenAiChatOptions off = (OpenAiChatOptions) clients.options(ref, ModelClients.RequestOptions.defaults().withThinking("off"));
        assertNull(off.getReasoningEffort());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), off.getExtraBody());
        assertTrue(o.getToolCallbacks() == null || o.getToolCallbacks().isEmpty());
        assertEquals(List.of(), ModelClients.RequestOptions.defaults().tools());
    }
}
