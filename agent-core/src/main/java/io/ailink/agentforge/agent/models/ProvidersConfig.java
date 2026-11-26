package io.ailink.agentforge.agent.models;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用户配置：自己的 provider（自托管 / 内网网关 / models.dev 不认识的厂商）与它们的模型、默认模型。
 *
 * <p>coxswain 里这件事写在 init.ss（{@code agent-provider-define!}）。Java 这边 {@link Models#defineProvider} 等方法早就
 * 在，缺的是"谁来调"—— 这个类就是那一层：启动时读 {@code <configDir>/providers.json}。
 *
 * <pre>{@code
 * {
 *   "providers": {
 *     "litegate": {
 *       "api": "anthropic",                       // anthropic | openai（OpenAI 兼容）
 *       "baseUrl": "http://127.0.0.1:4000",
 *       "label": "LiteGate",                      // 可选
 *       "apiKeyEnv": "LITEGATE_API_KEY",          // 可选，默认 <ID>_API_KEY；也可以用 /key 存进加密凭据
 *       "maxTokens": 32000,                       // 可选
 *       "models": {
 *         "MiniMax-M3.1-Flash": { "context": 200000, "maxOutput": 32000, "thinking": ["off", "on"] }
 *       }
 *     }
 *   },
 *   "model": "litegate/MiniMax-M3.1-Flash"         // 可选：默认模型（赢过界面里上次点的；$AGENTFORGE_MODEL 赢过它；
 *                                                  //       网页上「设为默认」会改写这一项）
 * }
 * }</pre>
 *
 * <p>⚠️ 一条写错不拖垮整份：出错的那家跳过，原因收进返回的提示里，由宿主说出来（启动时一行，而不是起不来）。
 */
public final class ProvidersConfig {
    public static final String FILE = "providers.json";

    private ProvidersConfig() { }

    public static Path file(Path configDir) { return configDir.resolve(FILE); }

    /** 读并应用；文件不存在 ⇒ 空表。返回给人看的问题（每条一句）。 */
    public static List<String> apply(Models models, Path configDir) {
        Path f = file(configDir);
        List<String> problems = new ArrayList<>();
        if (!Files.isRegularFile(f)) return problems;
        JsonNode root;
        try {
            root = JsonMapper.builder().build().readTree(Files.readString(f));
        } catch (Exception e) {
            problems.add(f + " 读不了：" + e.getMessage());
            return problems;
        }
        JsonNode ps = root.path("providers");
        for (var e : ps.properties()) {
            String id = e.getKey();
            JsonNode p = e.getValue();
            try {
                ProviderDef d = ProviderDef.of();
                String api = p.path("api").asString(null);
                if (api != null) d.api(parseApi(api));
                if (p.hasNonNull("baseUrl")) d.baseUrl(p.path("baseUrl").asString());
                if (p.hasNonNull("label")) d.label(p.path("label").asString());
                if (p.hasNonNull("apiKeyEnv")) d.apiKeyEnv(p.path("apiKeyEnv").asString());
                if (p.hasNonNull("maxTokens")) d.maxTokens(p.path("maxTokens").asInt());
                models.defineProvider(id, d);
                JsonNode ms = p.path("models");
                if (ms.isArray()) {
                    for (JsonNode m : ms) models.providerModel(id, m.asString(), m.asString(), null, null, List.of());
                } else {
                    for (var me : ms.properties()) {
                        JsonNode m = me.getValue();
                        List<String> levels = new ArrayList<>();
                        for (JsonNode l : m.path("thinking")) levels.add(l.asString());
                        models.providerModel(id, me.getKey(), m.path("label").asString(me.getKey()),
                                m.hasNonNull("context") ? m.path("context").asLong() : null,
                                m.hasNonNull("maxOutput") ? m.path("maxOutput").asLong() : null, levels);
                    }
                }
            } catch (RuntimeException ex) {
                problems.add(FILE + " 里的 provider \"" + id + "\" 没用上：" + ex.getMessage());
            }
        }
        String dflt = root.path("model").asString(null);
        if (dflt != null && !dflt.isBlank()) models.setConfigDefault(dflt);
        return problems;
    }

    // ─── 写回（网页上的「手动添加凭证」）────────────────────────────────────

    /** 配置里的一个模型。 */
    public record ModelEntry(String id, String label, Long context, Long maxOutput, List<String> thinking) {
        public ModelEntry {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("模型 id 不能空");
            id = id.strip();
            thinking = thinking == null ? List.of() : List.copyOf(thinking);
        }
    }

    /** 配置里的一家 provider（字段同文件里的写法）。 */
    public record Entry(String id, String api, String baseUrl, String label, String apiKeyEnv, Integer maxTokens, List<ModelEntry> models) {
        public Entry {
            if (id == null || id.isBlank() || id.contains("/")) throw new IllegalArgumentException("provider id 要是不含 `/` 的非空字符串：" + id);
            id = id.strip();
            if (api != null) parseApi(api);
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    /** 读出文件里的各家（写坏了的那家跳过）。文件不存在 ⇒ 空表。 */
    public static List<Entry> read(Path configDir) {
        List<Entry> out = new ArrayList<>();
        JsonNode root = readRoot(file(configDir));
        for (var e : root.path("providers").properties()) {
            JsonNode p = e.getValue();
            try {
                List<ModelEntry> ms = new ArrayList<>();
                JsonNode mn = p.path("models");
                if (mn.isArray()) for (JsonNode m : mn) ms.add(new ModelEntry(m.asString(), null, null, null, null));
                else for (var me : mn.properties()) {
                    JsonNode m = me.getValue();
                    List<String> levels = new ArrayList<>();
                    for (JsonNode l : m.path("thinking")) levels.add(l.asString());
                    ms.add(new ModelEntry(me.getKey(), m.hasNonNull("label") ? m.path("label").asString() : null,
                            m.hasNonNull("context") ? m.path("context").asLong() : null,
                            m.hasNonNull("maxOutput") ? m.path("maxOutput").asLong() : null, levels));
                }
                out.add(new Entry(e.getKey(), text(p, "api"), text(p, "baseUrl"), text(p, "label"), text(p, "apiKeyEnv"),
                        p.hasNonNull("maxTokens") ? p.path("maxTokens").asInt() : null, ms));
            } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /** 加 / 换一家（同 id 整条替换；别的 provider 与 {@code model} 不动）。写入走临时文件 + 原子改名。 */
    public static void upsert(Path configDir, Entry e) {
        if (e.baseUrl() == null || e.baseUrl().isBlank()) throw new IllegalArgumentException("要填 baseUrl");
        if (e.api() == null) throw new IllegalArgumentException("要选格式：anthropic / openai");
        Path f = file(configDir);
        ObjectNode root = (ObjectNode) readRoot(f);
        ObjectNode ps = root.has("providers") && root.get("providers").isObject() ? (ObjectNode) root.get("providers") : root.putObject("providers");
        ObjectNode p = JSON.createObjectNode();
        p.put("api", e.api().strip().toLowerCase(Locale.ROOT));
        p.put("baseUrl", e.baseUrl().strip());
        if (e.label() != null && !e.label().isBlank()) p.put("label", e.label().strip());
        if (e.apiKeyEnv() != null && !e.apiKeyEnv().isBlank()) p.put("apiKeyEnv", e.apiKeyEnv().strip());
        if (e.maxTokens() != null) p.put("maxTokens", e.maxTokens());
        ObjectNode ms = p.putObject("models");
        for (ModelEntry m : e.models()) {
            ObjectNode mn = ms.putObject(m.id());
            if (m.label() != null && !m.label().isBlank()) mn.put("label", m.label());
            if (m.context() != null) mn.put("context", m.context());
            if (m.maxOutput() != null) mn.put("maxOutput", m.maxOutput());
            if (!m.thinking().isEmpty()) { ArrayNode a = mn.putArray("thinking"); m.thinking().forEach(a::add); }
        }
        ps.set(e.id(), p);
        write(f, root);
    }

    /** 改写顶层的默认模型 {@code model}（null = 拿掉）；别的不动。 */
    public static void setDefaultModel(Path configDir, String ref) {
        Path f = file(configDir);
        ObjectNode root = (ObjectNode) readRoot(f);
        if (ref == null) root.remove("model"); else root.put("model", ref);
        write(f, root);
    }

    /** 拿掉一家；没有这家 ⇒ false。 */
    public static boolean remove(Path configDir, String id) {
        Path f = file(configDir);
        JsonNode root = readRoot(f);
        if (!(root.get("providers") instanceof ObjectNode ps) || !ps.has(id)) return false;
        ps.remove(id);
        write(f, root);
        return true;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode readRoot(Path f) {
        if (!Files.isRegularFile(f)) return JSON.createObjectNode();
        try {
            JsonNode n = JSON.readTree(Files.readString(f));
            if (n == null || !n.isObject()) throw new IllegalStateException(f + " 不是 JSON 对象");
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(f + " 读不了", e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) throw e;
            throw new IllegalStateException(f + " 不是合法 JSON，先手动修好：" + e.getMessage(), e);
        }
    }

    private static void write(Path f, JsonNode root) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(f + " 写不了", e);
        }
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asString();
    }

    static ApiKind parseApi(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "anthropic", "claude", "messages" -> ApiKind.ANTHROPIC;
            case "openai", "openai-compatible", "openai_compatible", "chat" -> ApiKind.OPENAI;
            default -> throw new IllegalArgumentException("api 只认 anthropic / openai，收到 \"" + s + "\"");
        };
    }
}
