package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.auth.AuthStore;
import io.ailink.agentforge.agent.auth.Keystore;
import io.ailink.agentforge.agent.auth.SecretKeys;
import io.ailink.agentforge.agent.config.AgentPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 移植自 coxswain tests/agent-models-test.ss（目录 / 三道门 / 思考档位 / 端点 / 缺哪一步 / 黑名单）。 */
class ModelsTest {

    static final String FAKE_CATALOG = """
            {
              "deepseek": {
                "id": "deepseek", "name": "DeepSeek", "npm": "@ai-sdk/openai-compatible",
                "env": ["DEEPSEEK_API_KEY"],
                "models": {
                  "deepseek-v4-pro": {
                    "name": "DeepSeek V4 Pro", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"toggle"}, {"type":"effort","values":["high",null,"max"]}],
                    "limit": {"context": 1000000, "output": 384000},
                    "cost": {"input": 0.435, "output": 0.87},
                    "release_date": "2026-04-24", "last_updated": "2026-04-24"
                  },
                  "deepseek-budget": {
                    "name": "DeepSeek Budget", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"budget_tokens","min":1024}],
                    "limit": {"context": 200000, "output": 64000}
                  },
                  "deepseek-embed": {
                    "name": "DeepSeek Embed", "reasoning": false, "tool_call": false,
                    "limit": {"context": 8192, "output": 1536}
                  }
                }
              },
              "minimax": {
                "id": "minimax", "name": "MiniMax", "npm": "@ai-sdk/anthropic",
                "env": ["MINIMAX_API_KEY"],
                "models": {
                  "MiniMax-M3": {
                    "name": "MiniMax-M3", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"toggle"}],
                    "limit": {"context": 1000000, "output": 128000},
                    "cost": {"input": 0.3, "output": 1.2}
                  },
                  "MiniMax-Text": {
                    "name": "MiniMax Text", "reasoning": false, "tool_call": true,
                    "limit": {"context": 200000, "output": 8192}
                  },
                  "MiniMax-Budget": {
                    "name": "MiniMax Budget", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"budget_tokens","min":1024}],
                    "limit": {"context": 200000, "output": 64000}
                  },
                  "MiniMax-Budget-Capped": {
                    "name": "MiniMax Budget Capped", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"toggle"}, {"type":"budget_tokens","min":128,"max":24576}],
                    "limit": {"context": 200000, "output": 65536}
                  },
                  "MiniMax-Budget-Tiny": {
                    "name": "MiniMax Budget Tiny", "reasoning": true, "tool_call": true,
                    "reasoning_options": [{"type":"budget_tokens","min":1024}],
                    "limit": {"context": 200000, "output": 4096}
                  },
                  "MiniMax-Image": {
                    "name": "MiniMax Image", "reasoning": false, "tool_call": false,
                    "limit": {"context": 0, "output": 0}
                  }
                }
              },
              "noapi": {
                "id": "noapi", "name": "目录没给端点", "npm": "@ai-sdk/openai-compatible",
                "env": ["NOAPI_KEY"],
                "models": { "m1": {"name": "M1", "reasoning": false, "tool_call": true, "limit": {"context": 100000, "output": 8192}} }
              },
              "zhipuai": {
                "id": "zhipuai", "name": "智谱", "npm": "@ai-sdk/openai-compatible",
                "api": "https://open.bigmodel.cn/api/coding/paas/v4",
                "env": ["ZHIPUAI_API_KEY"],
                "models": { "glm-5": {"name": "GLM-5", "reasoning": true, "tool_call": true, "limit": {"context": 200000, "output": 32768}} }
              }
            }
            """;

    @TempDir Path tmp;
    Map<String, String> env;
    Catalog catalog;
    Models models;

    @BeforeEach
    void setUp() {
        AgentPaths.override(tmp);
        env = EnvVars.override(new HashMap<>());
        catalog = new Catalog(tmp.resolve("models-dev.json"));
        AuthStore auth = new AuthStore(tmp.resolve("auth.json.enc"), new SecretKeys(tmp.resolve(".auth-key"), Keystore.NONE));
        models = new Models(catalog, new SelectedProviders(tmp.resolve("providers.json")), new Prefs(tmp.resolve("agent.json")), auth);
    }

    @AfterEach
    void tearDown() {
        EnvVars.reset();
        AgentPaths.override(null);
    }

    void writeCatalog(String text) throws Exception {
        Files.writeString(catalog.path(), text, StandardCharsets.UTF_8);
        catalog.clear();
        assertTrue(catalog.load());
    }

    @Test
    void 目录读缓存不联网() throws Exception {
        assertFalse(catalog.load(), "还没有缓存时装不进来");
        assertTrue(catalog.stale(), "那就叫该刷新了");
        assertNull(catalog.ageSeconds(), "没有年龄可报（null，不是 0）");
        writeCatalog(FAKE_CATALOG);
        assertTrue(catalog.loaded());
        assertEquals(Catalog.Source.CACHE, catalog.source());
        assertEquals(List.of("deepseek", "minimax", "noapi", "zhipuai"), catalog.providerIds().stream().sorted().toList());
        assertEquals(List.of("deepseek-budget", "deepseek-embed", "deepseek-v4-pro"), catalog.modelIds("deepseek").stream().sorted().toList());
        assertEquals("@ai-sdk/anthropic", catalog.providerNpm("minimax"));
        assertEquals(List.of("DEEPSEEK_API_KEY"), catalog.providerEnv("deepseek"));
        assertFalse(catalog.stale(), "刚写完不算过期");
        ModelFacts f = catalog.modelFacts("deepseek", "deepseek-v4-pro");
        assertEquals(1000000L, f.context());
        assertEquals(384000L, f.output());
        assertEquals(0.435, f.costInput());
        assertTrue(f.reasons());
        assertEquals(List.of("high", "max"), f.reasoningEffort(), "null 筛掉");
        assertNull(f.costCacheWrite(), "没写的键不在");
        assertNull(catalog.modelFacts("deepseek", "没这条"));
        assertNull(catalog.modelFacts("没这家", "m"));
        // 坏缓存装不进来
        Files.writeString(catalog.path(), "<html>502 Bad Gateway</html>");
        catalog.clear();
        assertFalse(catalog.load());
        assertFalse(catalog.loaded());
    }

    @Test
    void 刷新先验再落盘() throws Exception {
        writeCatalog(FAKE_CATALOG);
        catalog.fetcher(url -> "<html>502</html>".getBytes());
        assertFalse(catalog.refresh(), "坏响应不落盘");
        catalog.clear();
        assertTrue(catalog.load(), "旧缓存还在");
        catalog.fetcher(url -> null);
        assertFalse(catalog.refresh());
        catalog.fetcher(url -> "{\"x\":{\"name\":\"X\",\"models\":{}}}".getBytes());
        assertTrue(catalog.refresh());
        assertEquals(List.of("x"), catalog.providerIds());
        assertFalse(Files.exists(catalog.path().resolveSibling("models-dev.json.tmp")));
    }

    @Test
    void 没有缓存时退到随包那份() {
        assertEquals(Catalog.Source.BUNDLED, catalog.loadOrFallback());
        assertTrue(catalog.loaded());
        assertTrue(catalog.stale(), "随包那份不算缓存，照样该刷");
        assertTrue(catalog.modelIds("anthropic").contains("claude-opus-5-5"));
        models.defineProvider("anthropic", null);
        env.put("ANTHROPIC_API_KEY", "sk-fake");
        models.registerModels();
        assertTrue(models.modelRefs().contains("anthropic/claude-opus-5-5"));
        ModelMeta m = models.meta("anthropic/claude-opus-5-5");
        assertEquals(1000000L, m.contextWindow());
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), m.thinkingLevels());
    }

    @Test
    void 三道门与思考档位() throws Exception {
        writeCatalog(FAKE_CATALOG);
        models.registerModels();
        assertTrue(models.modelRefs().isEmpty(), "一家都没准许");
        models.defineProvider("deepseek", null);
        models.registerModels();
        assertTrue(models.modelRefs().isEmpty(), "准许了但没 key");
        env.put("DEEPSEEK_API_KEY", "sk-fake-deepseek");
        models.registerModels();
        assertEquals(List.of("deepseek/deepseek-budget", "deepseek/deepseek-v4-pro"), models.modelRefs().stream().sorted().toList());
        ModelMeta pro = models.meta("deepseek/deepseek-v4-pro");
        assertEquals(1000000L, pro.contextWindow());
        assertEquals(384000L, pro.maxOutput());
        assertEquals("DeepSeek V4 Pro", pro.label());
        assertEquals(List.of("off", "high", "max"), pro.thinkingLevels(), "厂商只给两档就是两档");

        models.defineProvider("minimax", null);
        env.put("MINIMAX_API_KEY", "sk-fake-minimax");
        models.registerModels();
        assertEquals(7, models.modelRefs().size());
        assertEquals(List.of("off", "on"), models.meta("minimax/MiniMax-M3").thinkingLevels());
        assertEquals(List.of(), models.meta("minimax/MiniMax-Text").thinkingLevels());
        assertNotNull(models.meta("minimax/MiniMax-Text").thinkingLevels(), "空表也写进去（不是没配）");
        assertEquals(List.of("1024", "4096", "16384", "59904"), models.meta("minimax/MiniMax-Budget").thinkingLevels());
        assertEquals(List.of("off", "1024", "4096", "16384", "24576"), models.meta("minimax/MiniMax-Budget-Capped").thinkingLevels());
        assertEquals(List.of(), models.meta("minimax/MiniMax-Budget-Tiny").thinkingLevels());
        assertEquals(List.of(), models.meta("deepseek/deepseek-budget").thinkingLevels(), "OpenAI 形不列预算档");

        models.defineProvider("zhipuai", null);
        env.put("ZHIPUAI_API_KEY", "sk-fake-zhipu");
        models.registerModels();
        assertEquals(List.of(), models.meta("zhipuai/glm-5").thinkingLevels(), "会推理但没说怎么控制");
        models.undefineProvider("zhipuai");
        models.undefineProvider("minimax");
        models.registerModels();
        assertEquals(List.of("deepseek/deepseek-budget", "deepseek/deepseek-v4-pro"), models.modelRefs().stream().sorted().toList());

        models.providerModel("deepseek", "我自己加的", "手写的", 64000L, null, null);
        models.registerModels();
        assertTrue(models.modelRefs().contains("deepseek/我自己加的"), "用户手写的活过了一次重算");
        assertFalse(models.meta("deepseek/我自己加的").builtin());
        assertTrue(models.meta("deepseek/deepseek-v4-pro").builtin());
        assertThrows(IllegalArgumentException.class, () -> models.providerModel("a/b", "c", "", null, null, null));
    }

    @Test
    void 端点从哪儿来() throws Exception {
        writeCatalog(FAKE_CATALOG);
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", catalog.providerApi("zhipuai"));
        assertNull(catalog.providerApi("minimax"));
        assertFalse(models.needsEndpoint("zhipuai"));
        assertTrue(models.needsEndpoint("noapi"));
        models.selectProvider("zhipuai");
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", models.endpoint("zhipuai"));
        assertEquals(ApiKind.OPENAI, models.provider("zhipuai").api());
        models.selectProvider("minimax");
        assertEquals("https://api.minimaxi.com/anthropic", models.endpoint("minimax"), "认识的那家用我们自己的默认，不用目录");
        models.unselectProvider("zhipuai");
        models.selectProvider("zhipuai", "https://my-proxy.example/v4", null);
        assertEquals("https://my-proxy.example/v4", models.endpoint("zhipuai"), "用户填的赢");
        assertTrue(models.endpointDiffers("zhipuai"));
        models.setProviderEndpoint("zhipuai", "https://open.bigmodel.cn/api/anthropic", ApiKind.ANTHROPIC);
        assertEquals(ApiKind.ANTHROPIC, models.provider("zhipuai").api(), "格式能跟着一起换");
        models.setProviderEndpoint("zhipuai", null, null);
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", models.endpoint("zhipuai"), "null = 用回目录");
        env.put("ZHIPUAI_BASE_URL", "http://localhost:9999/v4");
        assertEquals("http://localhost:9999/v4", models.endpoint("zhipuai"), "环境变量临时覆盖");
        assertThrows(IllegalArgumentException.class, () -> models.selectProvider("目录里没有"));
        assertThrows(IllegalArgumentException.class, () -> models.setProviderEndpoint("deepseek", "http://x", null));
        // 选中状态落盘：新实例读得回来
        Models again = new Models(catalog, new SelectedProviders(tmp.resolve("providers.json")), new Prefs(tmp.resolve("agent.json")), models.auth());
        again.registerModels();
        assertTrue(again.providerEnabled("zhipuai"));
        assertTrue(again.providerEnabled("minimax"));
    }

    @Test
    void 基地址归一() {
        assertEquals("https://api.minimax.io/anthropic", Models.normalizeBase("https://api.minimax.io/anthropic/v1/", ApiKind.ANTHROPIC));
        assertEquals("https://x/anthropic", Models.normalizeBase("https://x/anthropic/v1/messages", ApiKind.ANTHROPIC));
        assertEquals("https://x/paas/v4", Models.normalizeBase("https://x/paas/v4/chat/completions", ApiKind.OPENAI));
        assertEquals("https://x/v1", Models.normalizeBase("https://x/v1", ApiKind.OPENAI));
        assertNull(Models.normalizeBase(null, ApiKind.OPENAI));
    }

    @Test
    void 定义一家认识的与不认识的() throws Exception {
        writeCatalog(FAKE_CATALOG);
        models.defineProvider("deepseek", null);
        assertTrue(Models.knownProviders().contains("deepseek"));
        assertDoesNotThrow(() -> models.defineProvider("zhipuai", null), "目录给了端点就不必写");
        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", models.endpoint("zhipuai"));
        models.undefineProvider("zhipuai");
        models.defineProvider("zhipuai", ProviderDef.of().baseUrl("https://open.bigmodel.cn/api/paas/v4"));
        assertEquals(ApiKind.OPENAI, models.provider("zhipuai").api(), "格式从 npm 推");
        assertEquals("https://open.bigmodel.cn/api/paas/v4", models.endpoint("zhipuai"));
        assertThrows(IllegalArgumentException.class, () -> models.defineProvider("内网", ProviderDef.of().baseUrl("http://10.0.0.5")),
                "目录里没有、也没写格式 → 拒");
        assertThrows(IllegalArgumentException.class, () -> models.defineProvider("内网", ProviderDef.of().api(ApiKind.OPENAI)),
                "没端点 → 拒");
        assertDoesNotThrow(() -> models.defineProvider("内网", ProviderDef.of().api(ApiKind.OPENAI).baseUrl("http://10.0.0.5")));
        assertThrows(IllegalArgumentException.class, () -> models.defineProvider("noapi", null), "目录没端点的必须写 baseUrl");
    }

    @Test
    void key的来路与存取() throws Exception {
        writeCatalog(FAKE_CATALOG);
        assertThrows(IllegalStateException.class, () -> models.setKey("deepseek", "sk-x"), "没启用不给存");
        models.defineProvider("deepseek", null);
        assertFalse(models.hasKey("deepseek"));
        models.setKey("deepseek", "sk-from-file");
        assertEquals("file", models.resolveKey("deepseek").source());
        assertEquals(List.of("file"), models.keySources("deepseek"));
        env.put("DEEPSEEK_API_KEY", "sk-from-env");
        assertEquals("sk-from-env", models.resolveKey("deepseek").value(), "环境变量赢过文件");
        assertEquals(List.of("env", "file"), models.keySources("deepseek"));
        assertFalse(models.resolveKey("deepseek").toString().contains("sk-"), "toString 不带 key");
        env.remove("DEEPSEEK_API_KEY");
        models.removeKey("deepseek");
        assertFalse(models.hasKey("deepseek"));
        // 显式指定的环境变量只认它
        models.defineProvider("内网", ProviderDef.of().api(ApiKind.OPENAI).baseUrl("http://10.0.0.5/v1").apiKeyEnv("MY_GW_KEY"));
        env.put("MY_GW_KEY", "gw");
        assertEquals("MY_GW_KEY", models.resolveKey("内网").source());
    }

    @Test
    void 没配时报错说清楚缺哪一步() throws Exception {
        writeCatalog(FAKE_CATALOG);
        assertEquals(Problem.Code.NO_PROVIDERS, models.problem().code());
        models.defineProvider("deepseek", null);
        assertEquals(Problem.Code.NO_KEYS, models.problem().code());
        assertTrue(models.problem().message().contains("DEEPSEEK_API_KEY"));
        env.put("DEEPSEEK_API_KEY", "sk-fake");
        models.registerModels();
        assertEquals(Problem.Code.NO_DEFAULT, models.problem().code(), "不替用户挑");
        assertFalse(models.resolveCurrent().ok());
        models.selectModel("deepseek/deepseek-v4-pro");
        assertNull(models.problem());
        Models.Resolution r = models.resolveCurrent();
        assertTrue(r.ok());
        assertEquals("deepseek-v4-pro", r.ref().modelId());
        models.setConfigDefault("deepseek/deepseek-budget");
        assertEquals("deepseek/deepseek-budget", models.defaultModel(), "配置赢过上次点的");
        env.put("AGENTFORGE_MODEL", "deepseek/deepseek-v4-pro");
        assertEquals("deepseek/deepseek-v4-pro", models.defaultModel(), "环境变量赢过配置");
        env.put("AGENTFORGE_MODEL", "deepseek/没这条");
        assertEquals(Problem.Code.UNKNOWN_DEFAULT, models.problem().code());
        env.remove("AGENTFORGE_MODEL");
        // 无归属
        models.registerModel(new ModelMeta(new ModelRef("内纲", "qwen3-max"), "打错了", 131072L, null, null, false, null));
        models.registerModels();
        assertEquals(1, models.orphans().size());
        assertEquals(Problem.Code.ORPHAN_MODELS, models.problem().code());
        assertTrue(models.problem().message().contains("内纲/qwen3-max"));
        assertTrue(models.resolveCurrent().ok(), "无归属只是提醒，不挡住可用的默认模型");
        // 没目录
        Models bare = new Models(new Catalog(tmp.resolve("nope.json")), new SelectedProviders(tmp.resolve("p2.json")),
                new Prefs(tmp.resolve("a2.json")), models.auth());
        bare.defineProvider("deepseek", null);
        assertEquals(Problem.Code.NO_CATALOG, bare.problem().code());
    }

    @Test
    void 黑名单() throws Exception {
        writeCatalog(FAKE_CATALOG);
        models.defineProvider("deepseek", null);
        models.defineProvider("minimax", null);
        env.put("DEEPSEEK_API_KEY", "a");
        env.put("MINIMAX_API_KEY", "b");
        models.block("deepseek/deepseek-v4-pro");
        models.block("minimax");
        models.registerModels();
        assertEquals(List.of("deepseek/deepseek-budget"), models.modelRefs());
        assertTrue(models.isBlocked("minimax/MiniMax-M3"));
        models.unblock("minimax");
        models.block("minimax/*");
        assertTrue(models.isBlocked("minimax/MiniMax-M3"), "整家两种写法");
        models.providerModel("deepseek", "手写", "", null, null, null);
        models.block("deepseek/手写");
        models.registerModels();
        assertFalse(models.modelRefs().contains("deepseek/手写"), "用户自己写的也照筛");
        models.unblockAll();
        models.registerModels();
        // ⚠️ 被屏蔽时用户手写的那条是从表里删掉的（与 coxswain 一致），解除屏蔽不会凭空回来
        assertEquals(7, models.modelRefs().size());
        assertThrows(IllegalArgumentException.class, () -> models.block(""));
    }

    @Test
    void prefs落盘() {
        Prefs p = new Prefs(tmp.resolve("prefs.json"));
        assertNull(p.model());
        assertNull(p.approval());
        assertNull(p.network());
        p.setModel("a/b");
        p.setThinking("high");
        p.setApproval("untrusted");
        p.setSandbox("read-only");
        p.setNetwork(true);
        p.setWritableRoots(List.of("/srv/data"));
        p.addApprovedTool("mcp.fs.write");
        p.addApprovedTool("mcp.fs.write");
        Prefs q = new Prefs(tmp.resolve("prefs.json"));
        assertEquals("a/b", q.model());
        assertEquals("high", q.thinking());
        assertEquals("untrusted", q.approval());
        assertEquals("read-only", q.sandbox());
        assertEquals(Boolean.TRUE, q.network());
        assertEquals(List.of("/srv/data"), q.writableRoots());
        assertEquals(List.of("mcp.fs.write"), q.approvedTools());
        q.setThinking(null);
        assertNull(new Prefs(tmp.resolve("prefs.json")).thinking());
        assertThrows(IllegalArgumentException.class, () -> q.setModel(""));
    }

    @Test
    void 候选与报告() throws Exception {
        writeCatalog(FAKE_CATALOG);
        models.defineProvider("deepseek", null);
        env.put("DEEPSEEK_API_KEY", "a");
        models.registerModels();
        Models.ProviderCandidate ds = models.providerCandidates().stream().filter(c -> c.id().equals("deepseek")).findFirst().orElseThrow();
        assertTrue(ds.enabled() && ds.hasKey() && ds.formatKnown());
        assertEquals(3, ds.catalogModels());
        Models.ProviderCandidate na = models.providerCandidates().stream().filter(c -> c.id().equals("noapi")).findFirst().orElseThrow();
        assertTrue(na.needsEndpoint());
        Models.ProviderReport rep = models.providerReport().get(0);
        assertEquals(2, rep.usableModels());
    }
}
