package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.auth.AuthStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 哪些模型能用（对应 coxswain {@code models.ss} + keel 的模型注册表机制）。全是"应该怎样"的决定。
 *
 * <h2>一条模型要过的门</h2>
 * <ol>
 *   <li>**那一家启用了** —— 界面里选中过（{@link SelectedProviders}），或配置里定义过（{@link #defineProvider}）；</li>
 *   <li>**那一家有 key** —— 环境变量或加密凭据文件；</li>
 *   <li>**能对话** —— 目录说有窗口、会调工具（图像 / 嵌入模型列进选择器只会撞错）；</li>
 *   <li>**不在黑名单** —— 筛在注册这一步，所以处处生效（选择器、路由候选池、默认模型）。</li>
 * </ol>
 *
 * <h2>没有内置模型，也没有默认模型</h2>
 * 清单来自 {@link Catalog}；**没配就报错**，而且要说清楚缺哪一步（{@link #problem()}）—— 一个没配过的人应该
 * 看见"你还没选模型"，而不是一个悄悄替他选好的、可能既没 key 也不合适的模型（另一个价钱、另一种能力）。
 *
 * <h2>默认模型的优先级（高到低）</h2>
 * {@code $AGENTFORGE_MODEL}（这一次用别的）→ 配置里写的（{@link #setConfigDefault}）→ 上次在选择器里点的
 * （{@link Prefs}）→ 没有。
 *
 * <p>线程安全：公开方法都同步在实例上。会话层多半用 {@link #instance()}。
 */
public final class Models {

    // ─── 我们认识哪几家：这**不是模型清单**，是"知道它们说哪种格式、端点在哪" ──────────

    /** 已知的一家：格式、显示名、惯用环境变量、默认端点（null = SDK 自带的默认）。 */
    record Known(String id, ApiKind api, String label, List<String> env, String defaultBase) { }

    // ⚠️ 已知默认端点排在目录前面是有意的：目录说 minimax 是国际站，我们的默认是国内站 —— 让目录赢等于把在用的人
    //    静默换到另一个区，那不是一次目录更新该有的权力。
    static final Map<String, Known> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("anthropic", new Known("anthropic", ApiKind.ANTHROPIC, "Anthropic",
                List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"), null));
        KNOWN.put("openai", new Known("openai", ApiKind.OPENAI, "OpenAI", List.of("OPENAI_API_KEY"), null));
        KNOWN.put("deepseek", new Known("deepseek", ApiKind.OPENAI, "DeepSeek（OpenAI 格式）",
                List.of("DEEPSEEK_API_KEY"), "https://api.deepseek.com"));
        KNOWN.put("minimax", new Known("minimax", ApiKind.ANTHROPIC, "MiniMax（Anthropic 格式）",
                List.of("MINIMAX_API_KEY"), "https://api.minimaxi.com/anthropic"));
    }

    private static volatile Models instance;

    /** 进程里那一份（默认位置的目录 / state / 凭据）。 */
    public static Models instance() {
        Models m = instance;
        if (m == null) {
            synchronized (Models.class) {
                if (instance == null) instance = new Models(new Catalog(), new SelectedProviders(), new Prefs(), new AuthStore());
                m = instance;
            }
        }
        return m;
    }

    /** 测试 / 宿主装配用。 */
    public static void setInstance(Models m) { instance = m; }

    private final Catalog catalog;
    private final SelectedProviders selected;
    private final Prefs prefs;
    private final AuthStore auth;

    private final Map<String, ProviderInfo> providers = new LinkedHashMap<>();
    private final Map<String, ModelMeta> models = new LinkedHashMap<>();
    private final List<String> blocked = new ArrayList<>();
    private final List<Orphan> orphans = new ArrayList<>();
    private String configDefault;

    public Models(Catalog catalog, SelectedProviders selected, Prefs prefs, AuthStore auth) {
        this.catalog = Objects.requireNonNull(catalog);
        this.selected = Objects.requireNonNull(selected);
        this.prefs = Objects.requireNonNull(prefs);
        this.auth = Objects.requireNonNull(auth);
    }

    public Catalog catalog() { return catalog; }

    public SelectedProviders selected() { return selected; }

    public Prefs prefs() { return prefs; }

    public AuthStore auth() { return auth; }

    public static List<String> knownProviders() { return List.copyOf(KNOWN.keySet()); }

    // ─── provider：定义（配置）与选中（界面）─────────────────────────────

    /**
     * 定义一家（配置里写的）。**自有的**那种：自托管、内网网关、models.dev 不认识的厂商。定义本身就是启用。
     *
     * <p>⚠️ 认不出格式时**当场报错**，而不是猜一个 openai：猜错的表现是一串 400/404，错误消息指向模型名。
     * ⚠️ 目录也不认识、又没给端点的**必须给 baseUrl**：不给的话请求会发到默认端点上去 —— 一个拿着别人家 key
     * 打自己账号的请求，401 之后没人会想到是这里的问题。
     *
     * @return id（这样"模型归哪一家"能写成一个可见的形状）
     */
    public synchronized String defineProvider(String id, ProviderDef def) {
        if (id == null || id.isEmpty() || id.contains("/")) throw new IllegalArgumentException("provider id 要是不含 `/` 的非空字符串：" + id);
        ProviderDef d = def != null ? def : ProviderDef.of();
        Known k = KNOWN.get(id);
        ApiKind api = d.api != null ? d.api : k != null ? k.api() : ApiKind.fromNpm(catalog.providerNpm(id));
        if (api == null) throw new IllegalArgumentException(
                "不知道 \"" + id + "\" 说哪种格式，请写明：ProviderDef.of().api(ApiKind.OPENAI).baseUrl(\"https://…\")（我们直接认识的：" + KNOWN.keySet() + "）");
        String catalogApi = catalog.providerApi(id);
        if (k == null && d.baseUrl == null && catalogApi == null) throw new IllegalArgumentException(
                "\"" + id + "\" 不在我们直接认识的那几家里（" + KNOWN.keySet() + "），models.dev 也没给它端点，必须写 baseUrl");
        String label = d.label != null ? d.label : k != null ? k.label() : catalog.providerName(id) != null ? catalog.providerName(id) : id;
        String base = d.baseUrl != null ? d.baseUrl : k != null ? null : catalogApi;
        providers.put(id, new ProviderInfo(id, label, api, base, d.apiKeyEnv, d.maxTokens,
                envNamesFor(id, d.apiKeyEnv), catalog.providerNpm(id), ProviderInfo.Origin.DEFINED));
        return id;
    }

    public synchronized void undefineProvider(String id) { providers.remove(id); }

    /**
     * 选中一家（界面里点的）。记进 state，并**当场放进 provider 表** —— 不然点完还要重启才看得见它的模型。
     *
     * @param baseUrl 我们不认识、目录也没端点的那些必须给；跟着这次选择一起落盘
     * @param api     这条端点说哪种格式（null = 按已知表 / npm 推）；选中时说过的**赢** —— 目录说的可能是它另一条端点
     * @throws IllegalArgumentException 目录里没有这一家（说明界面与目录不同步；自己的 provider 用 {@link #defineProvider}）
     */
    public synchronized void selectProvider(String id, String baseUrl, ApiKind api) {
        if (!catalog.hasProvider(id)) throw new IllegalArgumentException(
                "模型目录里没有 \"" + id + "\" 这一家（自己的 provider 请在配置里 defineProvider）");
        selected.add(id, baseUrl, api);
        adoptSelected(id);
    }

    public void selectProvider(String id) { selectProvider(id, null, null); }

    /**
     * 撤选。⚠️ **不删 key**：撤选是"我暂时不用这家"，删 key 是"我不要这个凭据了"，两件事。
     */
    public synchronized void unselectProvider(String id) {
        selected.remove(id);
        providers.remove(id);
    }

    public synchronized boolean providerSelected(String id) { return selected.contains(id); }

    /**
     * 改一家（选中来的）的端点。{@code url == null} = 用回目录里那个；{@code api} 可以跟着一起换
     * （同一家常常两条端点都开着：OpenAI 兼容的与 Anthropic 兼容的）。
     * ⚠️ 一个填得进去的值必须改得出来。配置里定义的那些归配置管，这里拒。
     */
    public synchronized void setProviderEndpoint(String id, String url, ApiKind api) {
        if (!selected.contains(id)) throw new IllegalArgumentException(
                "\"" + id + "\" 不是在界面里选中的那种（配置里定义的端点改在配置里）");
        providers.remove(id);
        if (url != null) selected.add(id, url, api);
        else selected.add(id, null, null);
        adoptSelected(id);
    }

    /** 记下的端点与目录里那个不一样吗（给界面标出来；经代理访问是正当用法，所以只标不纠正）。 */
    public synchronized boolean endpointDiffers(String id) {
        SelectedProviders.Entry e = selected.get(id);
        String theirs = catalog.providerApi(id);
        return e != null && e.baseUrl() != null && theirs != null && !e.baseUrl().equals(theirs);
    }

    /** 选中过、或者自己定义过。 */
    public synchronized boolean providerEnabled(String id) { return providers.containsKey(id); }

    public synchronized ProviderInfo provider(String id) { return providers.get(id); }

    /** 已启用的那几家，按进表顺序。 */
    public synchronized List<String> providerIds() { return new ArrayList<>(providers.keySet()); }

    /** 这一家要不要**用户**给端点：三条来路（已知默认 / 目录 / 用户填的）一条都没有。界面靠它决定先问端点还是直接问 key。 */
    public synchronized boolean needsEndpoint(String id) {
        if (KNOWN.containsKey(id) || catalog.providerApi(id) != null) return false;
        SelectedProviders.Entry e = selected.get(id);
        if (e != null && e.baseUrl() != null) return false;
        ProviderInfo p = providers.get(id);
        return p == null || p.baseUrl() == null;
    }

    /**
     * 这一家实际会用的端点（原样，SDK 用之前还要 {@link #normalizeBase}）。null = 走 SDK 自带的默认。
     *
     * <p>来路，顺序即优先级：{@code $<ID>_BASE_URL}（这个 shell 里临时换一个，与 key 的环境变量同一条理由）→
     * 用户写的（定义的 / 选中时填的）→ 我们认识的那几家的默认 → 目录的 {@code api} 字段。
     */
    public synchronized String endpoint(String id) {
        String env = EnvVars.get(EnvVars.idToEnv(id) + "_BASE_URL");
        if (env != null) return env;
        ProviderInfo p = providers.get(id);
        if (p != null && p.baseUrl() != null) return p.baseUrl();
        SelectedProviders.Entry e = selected.get(id);
        if (e != null && e.baseUrl() != null) return e.baseUrl();
        Known k = KNOWN.get(id);
        if (k != null) return k.defaultBase();
        return catalog.providerApi(id);
    }

    /**
     * 基地址 → SDK 认的那种：去掉尾斜杠与操作路径。OpenAI SDK 自己接 {@code /chat/completions}；Anthropic SDK
     * 自己接 {@code /v1/messages}，所以目录里 {@code …/anthropic/v1} 那种要再去掉 {@code /v1}。
     * ⚠️ 用户手里的可能是完整地址也可能是基地址，我们分不出来 —— 两种都认，比问"你填的是哪种"可靠。
     */
    public static String normalizeBase(String url, ApiKind api) {
        if (url == null) return null;
        String u = url.strip();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (api == ApiKind.OPENAI) {
            if (u.endsWith("/chat/completions")) u = u.substring(0, u.length() - "/chat/completions".length());
        } else {
            if (u.endsWith("/messages")) u = u.substring(0, u.length() - "/messages".length());
            if (u.endsWith("/v1")) u = u.substring(0, u.length() - "/v1".length());
        }
        return u;
    }

    // 选中的（目录里的）落进 provider 表。⚠️ 已经在表里就不动：配置里给同一个 id 定义过自己的端点要赢。
    // 推不出格式、或者三条来路都没端点的：**跳过而不是猜** —— 它会留在候选里，界面标出缺什么。
    private void adoptSelected(String id) {
        if (providers.containsKey(id)) return;
        SelectedProviders.Entry e = selected.get(id);
        if (e == null) return;
        Known k = KNOWN.get(id);
        ApiKind api = e.api() != null ? e.api() : k != null ? k.api() : ApiKind.fromNpm(catalog.providerNpm(id));
        String base = e.baseUrl() != null ? e.baseUrl() : k != null ? null : catalog.providerApi(id);
        if (api == null || (k == null && base == null)) return;
        String label = catalog.providerName(id) != null ? catalog.providerName(id) : k != null ? k.label() : id;
        providers.put(id, new ProviderInfo(id, label, api, base, null, null, envNamesFor(id, null),
                catalog.providerNpm(id), ProviderInfo.Origin.SELECTED));
    }

    private List<String> envNamesFor(String id, String explicit) {
        if (explicit != null) return List.of(explicit);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Known k = KNOWN.get(id);
        if (k != null) names.addAll(k.env());
        names.addAll(catalog.providerEnv(id));
        // 兜底：<ID>_API_KEY。报错文案一直这么教用户设（ModelClients.NotConfigured），取 key 时却从没查过它 ——
        // 自己定义的网关（目录里没有它的 env 表）于是永远"没 key"。
        names.add(EnvVars.idToEnv(id) + "_API_KEY");
        return List.copyOf(names);
    }

    /** 界面要列的一家（**不报 key**，一个字符都不报）。 */
    public record ProviderCandidate(String id, String label, boolean enabled, boolean hasKey,
                                    int catalogModels, boolean formatKnown, boolean needsEndpoint) { }

    /** 目录里的每一家 + 自己定义的那些。 */
    public synchronized List<ProviderCandidate> providerCandidates() {
        LinkedHashSet<String> all = new LinkedHashSet<>(catalog.providerIds());
        all.addAll(providers.keySet());
        List<ProviderCandidate> out = new ArrayList<>();
        for (String id : all) {
            ProviderInfo p = providers.get(id);
            String label = p != null ? p.label() : catalog.providerName(id) != null ? catalog.providerName(id) : id;
            boolean fmt = KNOWN.containsKey(id) || ApiKind.fromNpm(catalog.providerNpm(id)) != null || p != null;
            out.add(new ProviderCandidate(id, label, p != null, hasKey(id), catalog.modelIds(id).size(), fmt, needsEndpoint(id)));
        }
        return out;
    }

    // ─── key ─────────────────────────────────────────────────────────────

    /**
     * 取到的 key 与来路。{@code source} 是环境变量名或 {@code "file"}。⚠️ {@link #toString()} 不带 key 本身 ——
     * 一条为了排障打出来的 key 会留在终端回滚、tmux 日志与录屏里。
     */
    public record ResolvedKey(String value, String source) {
        public boolean fromEnv() { return !"file".equals(source); }

        @Override
        public String toString() { return "ResolvedKey[source=" + source + ", value=<redacted>]"; }
    }

    /**
     * key 的来路，顺序即优先级：环境变量（这个 shell 里要用另一个 —— 临时覆盖要赢过常驻配置，否则
     * "我 export 了怎么没用"要读源码才答得上）→ 加密凭据文件。没有 → null。
     */
    public synchronized ResolvedKey resolveKey(String id) {
        for (String n : envNames(id)) {
            String v = EnvVars.get(n);
            if (v != null) return new ResolvedKey(v, n);
        }
        String f = auth.ref(id);
        return f != null ? new ResolvedKey(f, "file") : null;
    }

    /** 这一家会去哪几个环境变量里找 key（按优先级；只报名字不报值）。 */
    public synchronized List<String> keyEnvNames(String id) { return envNames(id); }

    private List<String> envNames(String id) {
        ProviderInfo p = providers.get(id);
        return p != null ? p.envNames() : envNamesFor(id, null);
    }

    public synchronized boolean hasKey(String id) { return resolveKey(id) != null; }

    /**
     * key 从哪儿来：{@code "env"} / {@code "file"} 里的零到两个。删 key 那条路要靠它分清：凭据文件里那份删得掉，
     * 环境变量里那份**删不掉** —— 分不清的话用户删完发现模型还在。只报来路，不报内容。
     */
    public synchronized List<String> keySources(String id) {
        List<String> out = new ArrayList<>();
        for (String n : envNames(id)) {
            if (EnvVars.get(n) != null) { out.add("env"); break; }
        }
        if (auth.ref(id) != null) out.add("file");
        return out;
    }

    /** 存一条 key。**没启用的当场拒**，并说清下一步。 */
    public synchronized AuthStore.FileMode setKey(String id, String key) {
        if (!providers.containsKey(id)) throw new IllegalStateException(
                "\"" + id + "\" 还没启用，不给存 key —— 先选中它，或者在配置里 defineProvider（已启用的：" + providers.keySet() + "）");
        return auth.set(id, key);
    }

    public synchronized void removeKey(String id) { auth.remove(id); }

    // ─── 模型表 ──────────────────────────────────────────────────────────

    /**
     * 自定义模型唯一该走的入口：**ref 是拼出来的，不是打出来的**（打错一个字会注册一条没有归属的模型）。
     * ⚠️ 这里不校验 provider 定义过没有：配置比目录加载早跑，"给 deepseek 补一条目录还没收录的新模型"是正常写法。
     * 真正的把关在 {@link #registerModels()}（没有归属的剔除并报进 {@link #problem()}）。
     */
    public synchronized ModelRef providerModel(String providerId, String modelId, String label,
                                               Long contextWindow, Long maxOutput, List<String> thinkingLevels) {
        ModelRef ref = ModelRef.of(providerId, modelId);
        ModelFacts f = catalog.modelFacts(providerId, modelId);
        models.put(ref.toString(), new ModelMeta(ref, label, contextWindow, maxOutput, thinkingLevels, false, f));
        return ref;
    }

    /** 直接登记一条（用户写的，重算不动）。 */
    public synchronized void registerModel(ModelMeta m) {
        models.put(m.ref().toString(), new ModelMeta(m.ref(), m.label(), m.contextWindow(), m.maxOutput(),
                m.thinkingLevels(), false, m.facts()));
    }

    public synchronized void unregisterModel(String ref) { models.remove(ref); }

    /**
     * 重算一遍模型表。**可以随时再调**：选中 / 定义了一家、存了 key、刷新了目录、改了黑名单之后都该调。
     * 只撤自动注册的那些，用户自己写的不动（"用户永远赢"）。
     */
    public synchronized void registerModels() {
        if (!catalog.loaded()) catalog.loadOrFallback();
        // ⚠️ 选中过的要先落进 provider 表：这是启动路径上唯一会读 state 的地方
        for (String id : selected.ids()) adoptSelected(id);
        models.values().removeIf(ModelMeta::builtin);
        for (ProviderInfo p : providers.values()) {
            // ⚠️ 没 key 的一家整家跳过："不在列表里"这种状态谁都不会理解错
            if (!hasKey(p.id())) continue;
            boolean budgetShaped = p.api() == ApiKind.ANTHROPIC;
            for (String mid : catalog.modelIds(p.id())) {
                if (mid.isEmpty()) continue;
                ModelRef ref = new ModelRef(p.id(), mid);
                String r = ref.toString();
                if (models.containsKey(r)) continue;          // 用户手写的同名那条赢
                ModelFacts f = catalog.modelFacts(p.id(), mid);
                if (f == null || !f.usable() || isBlocked(r)) continue;
                models.put(r, new ModelMeta(ref, f.name() != null ? f.name() : "",
                        f.context() != null && f.context() > 0 ? f.context() : null,
                        f.output() != null && f.output() > 0 ? f.output() : null,
                        ThinkingLevels.levels(f, budgetShaped), true, f));
            }
        }
        // ⚠️ 用户自己写的那些在这一步之前就在表里了，黑名单要再扫一遍，否则"屏蔽我自己注册的那条"静默无效
        models.keySet().removeIf(this::isBlocked);
        collectOrphans();
    }

    /** 被剔除的一条无归属模型：ref 与它声称的那一家。 */
    public record Orphan(String ref, String providerId) { }

    // 无归属（provider 没定义也没启用）的模型**剔除而不是标灰**，但记下来由 problem() 说出来 —— 删掉不等于不说。
    // 判在这里而不是注册时：配置比 provider 表早跑，到这一行两种来源的 provider 才都进了表。
    private void collectOrphans() {
        orphans.clear();
        var it = models.entrySet().iterator();
        while (it.hasNext()) {
            ModelMeta m = it.next().getValue();
            if (!providers.containsKey(m.ref().providerId())) {
                orphans.add(new Orphan(m.ref().toString(), m.ref().providerId()));
                it.remove();
            }
        }
    }

    public synchronized List<Orphan> orphans() { return List.copyOf(orphans); }

    /** 可用的那几条，按注册顺序。 */
    public synchronized List<String> modelRefs() { return new ArrayList<>(models.keySet()); }

    public synchronized List<ModelMeta> modelMetas() { return new ArrayList<>(models.values()); }

    /** 模型表里的一条，或 null（不在可用池里）。 */
    public synchronized ModelMeta meta(String ref) { return ref == null ? null : models.get(ref); }

    public ModelMeta meta(ModelRef ref) { return ref == null ? null : meta(ref.toString()); }

    /** 目录事实（不管能不能用），或 null。 */
    public synchronized ModelFacts catalogFacts(String ref) {
        ModelRef r = ModelRef.parse(ref);
        return r == null ? null : catalog.modelFacts(r.providerId(), r.modelId());
    }

    /** 某条模型走哪种格式（它那一家的），或 null。 */
    public synchronized ApiKind apiOf(String ref) {
        ModelRef r = ModelRef.parse(ref);
        ProviderInfo p = r == null ? null : providers.get(r.providerId());
        return p == null ? null : p.api();
    }

    // ─── 黑名单 ──────────────────────────────────────────────────────────

    /**
     * 屏蔽：{@code "openai/gpt-5"} 就这一条；{@code "openai/*"} 或 {@code "openai"} 整家（少打两个字符是最容易
     * 出现的写法，不认它的话会得到一条静默无效的配置）。重算 {@link #registerModels()} 后生效。
     * ⚠️ 屏蔽掉当前默认模型是允许的，后果由 {@link #problem()} 如实报出来。
     */
    public synchronized void block(String pattern) {
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException(
                "要写成非空字符串（\"provider/模型名\"、\"provider/*\" 或 \"provider\"）");
        if (!blocked.contains(pattern)) blocked.add(pattern);
    }

    public synchronized void unblock(String pattern) { blocked.remove(pattern); }

    public synchronized void unblockAll() { blocked.clear(); }

    public synchronized List<String> blocked() { return List.copyOf(blocked); }

    public synchronized boolean isBlocked(String ref) {
        if (ref == null) return false;
        ModelRef r = ModelRef.parse(ref);
        for (String p : blocked) {
            if (p.equals(ref)) return true;
            if (r != null && (p.equals(r.providerId() + "/*") || p.equals(r.providerId()))) return true;
        }
        return false;
    }

    // ─── 默认模型 ────────────────────────────────────────────────────────

    /** 配置里写的默认（null = 清掉）。 */
    public synchronized void setConfigDefault(String ref) { this.configDefault = ref == null || ref.isEmpty() ? null : ref; }

    public synchronized String configDefault() { return configDefault; }

    /** 当前默认模型 ref：{@code $AGENTFORGE_MODEL} → 配置 → 上次选的 → null（**不替用户挑**）。 */
    public synchronized String defaultModel() {
        String env = EnvVars.get("AGENTFORGE_MODEL");
        if (env != null) return env;
        if (configDefault != null) return configDefault;
        return prefs.model();
    }

    /** 选择器里点了一条：记进 prefs（下次启动还在）。 */
    public synchronized void selectModel(String ref) { prefs.setModel(ref); }

    // ─── 缺哪一步 ────────────────────────────────────────────────────────

    /**
     * 缺的是哪一步，或 null。顺序是**用户要做的动作的顺序**：没目录排在后面 —— 一个把模型手写在配置里的人
     * 根本不需要 models.dev；无归属排最后 —— 一个还没选模型的人不需要先听拼写问题（但它绝不能不说）。
     */
    public synchronized Problem problem() {
        if (providers.isEmpty())
            return new Problem(Problem.Code.NO_PROVIDERS, "还没启用任何 provider：先选一家（agent 设置里的「选择 provider」），当场输入 key");
        if (providers.keySet().stream().noneMatch(this::hasKey))
            return new Problem(Problem.Code.NO_KEYS, "启用了 " + providers.keySet() + "，但一个 key 都没设：选中它时会问你要，或者设环境变量（"
                    + String.join("、", envNames(providers.keySet().iterator().next())) + "）");
        if (models.isEmpty()) {
            return catalog.loaded()
                    ? new Problem(Problem.Code.NO_MODELS, "启用的这几家在目录里没有能对话的模型（目录可能太旧了，刷新一次）")
                    : new Problem(Problem.Code.NO_CATALOG, "还没有模型目录：联网后会自动下载 models.dev 的那份，或者手动刷新一次");
        }
        String def = defaultModel();
        if (def == null)
            return new Problem(Problem.Code.NO_DEFAULT, "还没选模型。可选的有 " + models.size() + " 条，挑一条（例如 "
                    + models.keySet().iterator().next() + "）");
        if (!models.containsKey(def))
            return new Problem(Problem.Code.UNKNOWN_DEFAULT, "默认模型 \"" + def + "\" 不在可用的那几条里（可选：" + models.size() + " 条）");
        if (!orphans.isEmpty()) {
            LinkedHashSet<String> provs = new LinkedHashSet<>();
            List<String> refs = new ArrayList<>();
            for (Orphan o : orphans) { refs.add(o.ref()); provs.add(o.providerId()); }
            // ⚠️ 顿号分隔：模型 id 里本来就有英文逗号
            return new Problem(Problem.Code.ORPHAN_MODELS, "这几条模型没有归属，已跳过：" + String.join("、", refs)
                    + " —— provider " + String.join("、", provs) + " 既没有定义也没有启用。自定义模型请用 providerModel(\"某家\", \"模型 id\", …)，并先 defineProvider(\"某家\", …)");
        }
        return null;
    }

    /** 当前模型解析结果：要么有 ref + meta，要么有 problem（不会两者都空）。 */
    public record Resolution(ModelRef ref, ModelMeta meta, Problem problem) {
        public boolean ok() { return problem == null; }
    }

    /**
     * 会话起手问的那一句：现在用哪条模型。⚠️ **不回退到"随便挑一条"**。
     * 无归属那条问题只是提醒，不挡住一条可用的默认模型。
     */
    public synchronized Resolution resolveCurrent() {
        Problem p = problem();
        if (p != null && p.code() != Problem.Code.ORPHAN_MODELS) return new Resolution(null, null, p);
        String def = defaultModel();
        ModelMeta m = models.get(def);
        if (!hasKey(m.ref().providerId()))
            return new Resolution(m.ref(), m, new Problem(Problem.Code.UNUSABLE_DEFAULT, "默认模型 \"" + def + "\" 建不出来（没有 key）"));
        return new Resolution(m.ref(), m, null);
    }

    /** 每一家的状态（设置页 / 诊断用）。**不报 key 本身**。 */
    public record ProviderReport(String id, boolean enabled, boolean hasKey, int catalogModels, int usableModels) { }

    public synchronized List<ProviderReport> providerReport() {
        List<ProviderReport> out = new ArrayList<>();
        for (String id : providers.keySet()) {
            int usable = (int) models.values().stream().filter(m -> m.ref().providerId().equals(id)).count();
            out.add(new ProviderReport(id, true, hasKey(id), catalog.modelIds(id).size(), usable));
        }
        return out;
    }

    /** 测试用：清空 provider 表 / 模型表 / 黑名单 / 默认（不碰磁盘上的 state）。 */
    public synchronized void resetForTest() {
        providers.clear();
        models.clear();
        blocked.clear();
        orphans.clear();
        configDefault = null;
    }
}
