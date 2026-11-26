package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.models.ApiKind;
import io.ailink.agentforge.agent.models.Catalog;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.models.ProviderInfo;
import io.ailink.agentforge.agent.models.ProvidersConfig;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证（照 LiteGate 的「上游凭证」）与模型目录（models.dev 同步 / 导入）。
 *
 * <p>模型的两个来源：
 * <ul>
 *   <li><b>models.dev</b>：目录里的厂商，选中（记在 state）+ 有 key ⇒ 它目录里的模型全进池子；</li>
 *   <li><b>配置</b>：{@code providers.json} 里自己写的 provider（自托管 / 网关），模型逐条列出。网页上「手动填写」写的就是这个文件。</li>
 * </ul>
 * key 一律优先环境变量，其次加密凭据库；接口只回末 4 位。
 */
@RestController
@RequestMapping("/api")
public class CredentialsController {
    private final SessionHub hub;
    private final AgentRuntime rt;
    private final CatalogSync sync;

    public CredentialsController(SessionHub hub, CatalogSync sync) {
        this.hub = hub;
        this.rt = hub.runtime();
        this.sync = sync;
    }

    public record SyncBody(String url) { }

    public record ModelBody(String id, String label, Long context, Long maxOutput, List<String> thinking) { }

    /** source = catalog：{@code provider} + 可选 {@code baseUrl}（目录没给端点的那几家必填）；source = config：其余字段。 */
    public record CredentialBody(String source, String provider, String id, String label, String api, String baseUrl,
                                 String apiKeyEnv, String key, List<ModelBody> models) { }

    // ── 凭证 ───────────────────────────────────────────────────────────

    @GetMapping("/credentials")
    public List<Map<String, Object>> list() {
        Models m = rt.models();
        Map<String, Integer> counts = new HashMap<>();
        for (ModelMeta mm : rt.modelMetas()) counts.merge(mm.ref().providerId(), 1, Integer::sum);
        Map<String, ProvidersConfig.Entry> cfg = new HashMap<>();
        for (ProvidersConfig.Entry e : ProvidersConfig.read(rt.configDir())) cfg.put(e.id(), e);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : m.providerIds()) {
            ProviderInfo p = m.provider(id);
            if (p == null) continue;
            Models.ResolvedKey k = m.resolveKey(id);
            ProvidersConfig.Entry ce = cfg.get(id);
            boolean config = p.origin() == ProviderInfo.Origin.DEFINED && ce != null;
            out.add(Views.m().put("id", id).put("label", p.label()).put("api", p.api() == ApiKind.ANTHROPIC ? "anthropic" : "openai")
                    .put("baseUrl", m.endpoint(id))
                    .put("source", config ? "config" : "catalog")
                    .put("keySource", k == null ? null : k.fromEnv() ? "env" : "file")
                    .put("keyEnv", k != null && k.fromEnv() ? k.source() : null)
                    .put("keyHint", k == null ? null : hint(k.value()))
                    .put("envNames", m.keyEnvNames(id))
                    .put("models", counts.getOrDefault(id, 0))
                    .put("catalogModels", m.catalog().modelIds(id).size())
                    .put("configModels", ce == null ? null : ce.models().stream().map(x -> Views.m().put("id", x.id()).put("label", x.label())
                            .put("context", x.context()).put("maxOutput", x.maxOutput()).put("thinking", x.thinking().isEmpty() ? null : x.thinking()).done()).toList())
                    .put("apiKeyEnv", ce == null ? null : ce.apiKeyEnv())
                    .done());
        }
        return out;
    }

    @PostMapping("/credentials")
    public List<Map<String, Object>> create(@RequestBody CredentialBody b) {
        String id;
        if ("catalog".equals(b.source())) {
            id = need(b.provider(), "要选一家");
            if (rt.models().providerEnabled(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "\"" + id + "\" 已经加过了");
            rt.selectProvider(id, blank(b.baseUrl()) ? null : b.baseUrl().strip(), null);
        } else {
            id = need(b.id(), "要填 id");
            if (rt.models().providerEnabled(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "\"" + id + "\" 已经有了（换个 id，或去编辑那一条）");
            writeConfig(id, b);
        }
        if (!blank(b.key())) rt.setKey(id, b.key().strip());
        changed();
        return list();
    }

    /** 改一条配置里的 provider（models.dev 那几家只能换 key / 端点 / 移除）。 */
    @PutMapping("/credentials/{id}")
    public List<Map<String, Object>> update(@PathVariable String id, @RequestBody CredentialBody b) {
        ProviderInfo p = rt.models().provider(id);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这家：" + id);
        if (p.origin() == ProviderInfo.Origin.DEFINED) {
            writeConfig(id, b);
        } else if (b.baseUrl() != null) {
            rt.selectProvider(id, blank(b.baseUrl()) ? null : b.baseUrl().strip(), null);
        }
        if (!blank(b.key())) rt.setKey(id, b.key().strip());
        changed();
        return list();
    }

    /** 移除一家：models.dev 那几家取消选中，配置里的从 providers.json 拿掉；存下的 key 默认一并删掉。 */
    @DeleteMapping("/credentials/{id}")
    public List<Map<String, Object>> remove(@PathVariable String id, @RequestParam(defaultValue = "true") boolean removeKey) {
        ProviderInfo p = rt.models().provider(id);
        if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这家：" + id);
        if (removeKey) rt.models().removeKey(id);
        if (p.origin() == ProviderInfo.Origin.DEFINED && ProvidersConfig.remove(rt.configDir(), id)) {
            List<String> problems = rt.reloadProvidersConfig();
            if (rt.models().selected().contains(id)) rt.unselectProvider(id);
            if (!problems.isEmpty()) throw new IllegalStateException(String.join("；", problems));
        } else {
            rt.unselectProvider(id);
        }
        changed();
        return list();
    }

    private void writeConfig(String id, CredentialBody b) {
        List<ProvidersConfig.ModelEntry> ms = new ArrayList<>();
        if (b.models() != null) for (ModelBody m : b.models()) {
            if (m == null || blank(m.id())) continue;
            ms.add(new ProvidersConfig.ModelEntry(m.id(), m.label(), m.context(), m.maxOutput(), m.thinking()));
        }
        if (ms.isEmpty()) throw new IllegalArgumentException("至少列一个模型（配置里的 provider 不从 models.dev 取模型）");
        ProvidersConfig.upsert(rt.configDir(), new ProvidersConfig.Entry(id, need(b.api(), "要选格式"), need(b.baseUrl(), "要填 baseUrl"),
                b.label(), b.apiKeyEnv(), null, ms));
        List<String> problems = rt.reloadProvidersConfig();
        for (String pr : problems) if (pr.contains("\"" + id + "\"")) throw new IllegalArgumentException(pr);
    }

    // ── models.dev 目录 ─────────────────────────────────────────────────

    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Catalog c = rt.models().catalog();
        int models = 0;
        for (String id : c.providerIds()) models += c.modelIds(id).size();
        return Views.m().put("source", c.source().name()).put("syncedAt", c.mtime() == null ? null : c.mtime() * 1000)
                .put("ageSeconds", c.ageSeconds()).put("stale", c.stale()).put("providers", c.providerIds().size())
                .put("models", models).put("url", Catalog.URL).put("sync", sync.status()).done();
    }

    /** 目录里能加的各家（格式认得出的才能一键加；没给端点的要自己填 baseUrl）。 */
    @GetMapping("/catalog/providers")
    public List<Map<String, Object>> catalogProviders() {
        Catalog c = rt.models().catalog();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Models.ProviderCandidate p : rt.providerCandidates()) {
            out.add(Views.m().put("id", p.id()).put("label", p.label()).put("enabled", p.enabled()).put("hasKey", p.hasKey())
                    .put("models", p.catalogModels()).put("formatKnown", p.formatKnown()).put("needsEndpoint", p.needsEndpoint())
                    .put("api", c.providerApi(p.id())).put("envNames", rt.models().keyEnvNames(p.id())).done());
        }
        return out;
    }

    /** 开始后台同步（可给镜像地址）；进度 / 结果看 {@code GET /api/catalog} 的 sync 或 SSE 的 catalog-sync 事件。 */
    @PostMapping("/catalog/sync")
    public ResponseEntity<Map<String, Object>> sync(@RequestBody(required = false) SyncBody b) {
        String url = b == null ? null : b.url();
        if (url != null && !url.isBlank() && !url.strip().matches("(?i)https?://\\S+"))
            throw new IllegalArgumentException("地址要以 http:// 或 https:// 开头");
        if (!sync.start(url)) throw new ResponseStatusException(HttpStatus.CONFLICT, "已经在同步了");
        return ResponseEntity.accepted().body(catalog());
    }

    @PostMapping("/catalog/import")
    public Map<String, Object> importCatalog(@RequestBody byte[] body) {
        if (!rt.importCatalog(body)) throw new IllegalArgumentException("不是 models.dev 的 api.json（要一个非空 JSON 对象）");
        changed();
        return catalog();
    }

    // ── 工具函数 ───────────────────────────────────────────────────────

    private void changed() {
        hub.publish(Views.m().put("type", "models").done());
    }

    static String hint(String key) {
        if (key == null || key.length() < 8) return "…";
        return "…" + key.substring(key.length() - 4);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String need(String s, String msg) {
        if (blank(s)) throw new IllegalArgumentException(msg);
        return s.strip();
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", String.valueOf(e.getReason())));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> onBad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
