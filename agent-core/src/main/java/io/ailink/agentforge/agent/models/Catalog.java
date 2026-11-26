package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.config.AgentPaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型目录：models.dev 的 {@code api.json} 的本地副本 + 一层薄薄的读取（对应 coxswain {@code catalog.ss}）。
 *
 * <p>**目录不静态内置，下载下来缓存着**：厂商发新模型不该等我们发新版本。缓存在
 * {@code AgentPaths.dataDir()/models-dev.json}（{@code $AGENTFORGE_MODELS_CACHE} 可改），删了会自己回来。
 *
 * <ul>
 *   <li>⚠️ **永不阻塞启动**：刷新由宿主在后台线程发起；有缓存先用着，过期了顺手刷。</li>
 *   <li>⚠️ **离线用旧缓存，不清空**：拉取失败与"这些模型不存在"是两件事。</li>
 *   <li>⚠️ 一条缓存都没有时退到**随包带的一小份**（{@code models-fallback.json}，几条 Anthropic /
 *       OpenAI / DeepSeek），让离线的第一次启动也有模型可选；{@link #source()} 说清楚用的是哪份。</li>
 * </ul>
 *
 * <p>这一层**不筛**：准许了哪几家、有没有 key、能不能选，全是 {@link Models} 的事。
 */
public final class Catalog {

    public static final String URL = "https://models.dev/api.json";
    /** 7 天：厂商发新模型的节奏是周级的，每次启动都去拉一遍是拿别人家的服务器当自己的缓存。 */
    public static final long MAX_AGE_SECONDS = 7L * 24 * 60 * 60;
    static final String FALLBACK_RESOURCE = "/io/ailink/agentforge/agent/models/models-fallback.json";

    public enum Source { NONE, CACHE, BUNDLED }

    /** 下载：返回 200 的响应体，别的状态返回 null。测试注入假的。 */
    @FunctionalInterface
    public interface Fetcher {
        byte[] get(String url) throws IOException, InterruptedException;
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path pathOverride;
    private volatile JsonNode db;
    private volatile Long mtime;
    private volatile Source source = Source.NONE;
    private static final Fetcher DEFAULT_FETCHER = (url) -> Download.get(url, null);
    private volatile Fetcher fetcher = DEFAULT_FETCHER;

    public Catalog() { this(null); }

    /** @param path 缓存文件；null = 默认位置（每次现算，跟着 {@link AgentPaths#override} 走） */
    public Catalog(Path path) { this.pathOverride = path; }

    public Path path() {
        if (pathOverride != null) return pathOverride;
        String env = EnvVars.get("AGENTFORGE_MODELS_CACHE");
        return env != null ? Path.of(env) : AgentPaths.dataDir().resolve("models-dev.json");
    }

    public void fetcher(Fetcher f) { this.fetcher = f; }

    public boolean loaded() { return db != null; }

    public Source source() { return source; }

    public void clear() {
        db = null;
        mtime = null;
        source = Source.NONE;
    }

    /**
     * 读缓存进内存。读不到 / 不是 JSON / 不是非空对象都返回 false，**不抛**：一份坏掉的缓存与
     * "还没下载过"对调用方是同一件事（都得去刷新）。
     */
    public boolean load() {
        Path p = path();
        JsonNode obj = readCatalog(p);
        if (obj == null) return false;
        db = obj;
        mtime = fileSeconds(p);
        source = Source.CACHE;
        return true;
    }

    /** 先缓存，没有再退到随包那份。返回最后用的是哪份。 */
    public Source loadOrFallback() {
        if (load()) return source;
        try (InputStream in = Catalog.class.getResourceAsStream(FALLBACK_RESOURCE)) {
            if (in != null) {
                JsonNode obj = JSON.readTree(in.readAllBytes());
                if (obj != null && obj.isObject() && !obj.isEmpty()) {
                    db = obj;
                    mtime = null;
                    source = Source.BUNDLED;
                }
            }
        } catch (Exception ignored) { }
        return source;
    }

    private static JsonNode readCatalog(Path p) {
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            JsonNode obj = JSON.readTree(Files.readAllBytes(p));
            return obj != null && obj.isObject() && !obj.isEmpty() ? obj : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long fileSeconds(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis() / 1000;
        } catch (IOException e) {
            return null;
        }
    }

    public Long mtime() { return mtime; }

    /**
     * 缓存有多老（秒）。没有缓存时 **null** —— 不是 0 也不是很大的数：那两个都会让"没下载过"看起来
     * 像"下载过但很新/很旧"。随包那份不算缓存。
     */
    public Long ageSeconds() {
        Long m = source == Source.CACHE ? mtime : fileSeconds(path());
        if (m == null) return null;
        return Math.max(0, System.currentTimeMillis() / 1000 - m);
    }

    /** 该刷了吗。**没有缓存也算该刷**。 */
    public boolean stale() {
        Long age = ageSeconds();
        return age == null || age > MAX_AGE_SECONDS;
    }

    /**
     * 下载 → 验 → 写缓存 → 重新装载。成功 true，任何失败 false（旧缓存原样还在）。
     *
     * <p>⚠️ **先验再落盘**：一次 502 的 HTML 错误页不能把好好的缓存覆盖掉。
     * ⚠️ **先写临时文件再改名**：写到一半被杀，原来那份还在。
     */
    public boolean refresh() { return sync(null, null).ok(); }

    /** 一次同步的结果：成了没有、没成为什么、收了多少字节、试了几次。 */
    public record SyncResult(boolean ok, String error, long bytes, int attempts) { }

    /**
     * 同步（从 {@code url}，null = models.dev）：下载失败重试至多 {@link #ATTEMPTS} 次（4xx 不重试），说得出原因。
     * 下到了但不是 api.json 的样子 ⇒ 不落盘，旧目录原样还在。
     */
    public SyncResult sync(String url, java.util.function.LongConsumer progress) {
        String u = url == null || url.isBlank() ? URL : url.strip();
        String why = null;
        long bytes = 0;
        int attempt = 0;
        while (attempt < ATTEMPTS) {
            attempt++;
            try {
                byte[] body = fetcher == DEFAULT_FETCHER ? Download.get(u, progress) : fetcher.get(u);
                if (body == null) { why = "下载失败"; continue; }
                bytes = body.length;
                boolean ok;
                try {
                    ok = install(body);
                } catch (IOException | RuntimeException e) {
                    ok = false;                                       // 解析不了（比如代理回了一页 HTML）
                }
                if (ok) return new SyncResult(true, null, bytes, attempt);
                return new SyncResult(false, "下到的不是 models.dev 的 api.json（不是非空 JSON 对象，可能是代理 / 网关回的错误页），旧目录没动", bytes, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new SyncResult(false, "被打断了", bytes, attempt);
            } catch (Exception e) {
                why = e.getMessage() == null ? e.toString() : e.getMessage();
                if (why.contains("HTTP 4")) break;                 // 4xx：再试也一样
            }
            try { Thread.sleep(1000L * attempt * attempt); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return new SyncResult(false, why, bytes, attempt);
    }

    static final int ATTEMPTS = 3;

    /**
     * 装一份现成的 api.json（网页上的「导入」：服务器连不上 models.dev 时，在别处下好传上来）。
     * 同样先验再落盘；不是非空 JSON 对象 ⇒ false，旧缓存不动。
     */
    public boolean importJson(byte[] body) {
        try {
            return install(body);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean install(byte[] body) throws IOException {
        Path p = path();
        JsonNode obj = JSON.readTree(body);
        if (obj == null || !obj.isObject() || obj.isEmpty()) return false;
        Path dir = p.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, body);
        try {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        }
        db = obj;
        mtime = fileSeconds(p);
        source = Source.CACHE;
        return true;
    }


    // ─── 读：只回答"models.dev 说了什么" ─────────────────────────────────

    public List<String> providerIds() {
        JsonNode d = db;
        if (d == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : d.properties()) if (e.getValue().isObject()) out.add(e.getKey());
        return out;
    }

    /** 那一家的原始条目，或 null。 */
    public JsonNode provider(String id) {
        JsonNode d = db;
        if (d == null || id == null) return null;
        JsonNode p = d.get(id);
        return p != null && p.isObject() ? p : null;
    }

    public boolean hasProvider(String id) { return provider(id) != null; }

    public String providerName(String id) { return str(provider(id), "name"); }

    /**
     * 那一家的**基地址**（models.dev 的 {@code api} 字段）。第一方 SDK 那几家（anthropic / openai）
     * 没有 —— 端点写死在 SDK 里；要它的正是 openai-compatible 那一类。值本身多半已带版本段
     * （{@code …/paas/v4}、{@code …/anthropic/v1}），操作路径由 SDK 自己接。
     */
    public String providerApi(String id) { return str(provider(id), "api"); }

    /** 那一家用的 AI SDK 包名 —— 只当"说哪种格式"的线索，见 {@link ApiKind#fromNpm}。 */
    public String providerNpm(String id) { return str(provider(id), "npm"); }

    /** 那一家惯用的环境变量名。 */
    public List<String> providerEnv(String id) {
        JsonNode p = provider(id);
        JsonNode v = p == null ? null : p.get("env");
        if (v == null || !v.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonNode x : v) if (x.isString()) out.add(x.stringValue());
        return out;
    }

    public List<String> modelIds(String providerId) {
        JsonNode p = provider(providerId);
        JsonNode ms = p == null ? null : p.get("models");
        if (ms == null || !ms.isObject()) return List.of();
        return new ArrayList<>(ms.propertyNames());
    }

    /** 一条模型 → 事实；目录里没有时 null。 */
    public ModelFacts modelFacts(String providerId, String modelId) {
        JsonNode p = provider(providerId);
        JsonNode ms = p == null ? null : p.get("models");
        JsonNode m = ms == null || modelId == null ? null : ms.get(modelId);
        return m != null && m.isObject() ? factsOf(m) : null;
    }

    static ModelFacts factsOf(JsonNode m) {
        JsonNode limit = m.path("limit");
        JsonNode cost = m.path("cost");
        JsonNode ropts = m.get("reasoning_options");
        JsonNode effort = ropt(ropts, "effort");
        JsonNode budget = ropt(ropts, "budget_tokens");
        List<String> effortValues = null;
        if (effort != null && effort.get("values") != null && effort.get("values").isArray()) {
            effortValues = new ArrayList<>();
            // ⚠️ 只收字符串：源数据里真有 null 混在档位表里
            for (JsonNode v : effort.get("values")) if (v.isString()) effortValues.add(v.stringValue());
        }
        return new ModelFacts(
                str(m, "name"),
                lng(limit, "context"), lng(limit, "output"),
                dbl(cost, "input"), dbl(cost, "output"), dbl(cost, "cache_read"), dbl(cost, "cache_write"),
                bool(m, "reasoning"),
                ropt(ropts, "toggle") != null,
                effortValues,
                budget != null,
                budget == null ? null : lng(budget, "min"),
                budget == null ? null : lng(budget, "max"),
                bool(m, "tool_call"), bool(m, "attachment"),
                str(m, "release_date"), str(m, "last_updated"));
    }

    private static JsonNode ropt(JsonNode ropts, String type) {
        if (ropts == null || !ropts.isArray()) return null;
        for (JsonNode o : ropts) {
            JsonNode t = o.get("type");
            if (t != null && t.isString() && type.equals(t.stringValue())) return o;
        }
        return null;
    }

    private static String str(JsonNode o, String k) {
        JsonNode v = o == null ? null : o.get(k);
        return v != null && v.isString() ? v.stringValue() : null;
    }

    private static Long lng(JsonNode o, String k) {
        JsonNode v = o == null ? null : o.get(k);
        return v != null && v.isNumber() ? (long) Math.floor(v.doubleValue()) : null;
    }

    private static Double dbl(JsonNode o, String k) {
        JsonNode v = o == null ? null : o.get(k);
        return v != null && v.isNumber() ? v.doubleValue() : null;
    }

    private static Boolean bool(JsonNode o, String k) {
        JsonNode v = o == null ? null : o.get(k);
        return v != null && v.isBoolean() ? v.booleanValue() : null;
    }
}
