package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.config.AgentPaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 界面里选中过哪几家 provider（对应 coxswain {@code selected.ss}），记在 {@code state/providers.json}
 * （{@code $AGENTFORGE_PROVIDERS} 可改）。
 *
 * <p>"我要用哪几家"是**日常操作**，该在界面里点、点完就记住 —— 不该逼人去编辑配置文件。
 * key **不在这里**（见 {@code auth.json.enc}）。按选中顺序保存。
 */
public final class SelectedProviders {

    /** 选中时记下的东西：端点（选中时填的）与格式（"这条端点是 anthropic 兼容的"）。都可以没有。 */
    public record Entry(String id, String baseUrl, ApiKind api) { }

    private final Path pathOverride;
    private Map<String, Entry> entries;

    public SelectedProviders() { this(null); }

    public SelectedProviders(Path path) { this.pathOverride = path; }

    public Path path() {
        if (pathOverride != null) return pathOverride;
        String env = EnvVars.get("AGENTFORGE_PROVIDERS");
        return env != null ? Path.of(env) : AgentPaths.stateDir().resolve("providers.json");
    }

    public synchronized void reload() { entries = null; ensure(); }

    public synchronized void clear() { entries = null; }

    private void ensure() {
        if (entries != null) return;
        entries = new LinkedHashMap<>();
        JsonNode root = StateFile.read(path());
        if (root == null || !root.isArray()) return;
        for (JsonNode e : root) {
            JsonNode id = e.get("id");
            if (id == null || !id.isString() || id.stringValue().isEmpty()) continue;
            JsonNode base = e.get("baseUrl");
            JsonNode api = e.get("api");
            entries.put(id.stringValue(), new Entry(id.stringValue(),
                    base != null && base.isString() ? base.stringValue() : null,
                    api != null && api.isString() ? ApiKind.parse(api.stringValue()) : null));
        }
    }

    public synchronized List<String> ids() { ensure(); return new ArrayList<>(entries.keySet()); }

    public synchronized boolean contains(String id) { ensure(); return id != null && entries.containsKey(id); }

    public synchronized Entry get(String id) { ensure(); return id == null ? null : entries.get(id); }

    /** 记一条（同 id 覆盖、位置不变）并落盘。 */
    public synchronized void add(String id, String baseUrl, ApiKind api) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("provider id 应当是非空字符串");
        ensure();
        entries.put(id, new Entry(id, baseUrl, api));
        save();
    }

    public synchronized void remove(String id) {
        ensure();
        if (entries.remove(id) != null) save();
    }

    private void save() {
        ArrayNode arr = StateFile.JSON.createArrayNode();
        for (Entry e : entries.values()) {
            ObjectNode o = arr.addObject();
            o.put("id", e.id());
            if (e.baseUrl() != null) o.put("baseUrl", e.baseUrl());
            if (e.api() != null) o.put("api", e.api().name().toLowerCase());
        }
        StateFile.write(path(), arr);
    }
}
