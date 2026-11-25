package io.ailink.agentforge.agent.models;

import io.ailink.agentforge.agent.config.AgentPaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;

/**
 * 上次在选择器里点的模型 / 思考档位 / 审批与沙盒设置（对应 coxswain {@code prefs.ss}），记在
 * {@code state/agent.json}（{@code $AGENTFORGE_AGENT_PREFS} 可改）。
 *
 * <p>选完就该记住，否则每次启动都要重挑一遍。⚠️ 配置里明确写的默认模型**赢过**这份
 * （见 {@link Models#defaultModel()}）：一个人写进配置的东西不该被他昨天随手点的那一下盖掉。
 */
public final class Prefs {
    private final Path pathOverride;
    private ObjectNode data;

    public Prefs() { this(null); }

    public Prefs(Path path) { this.pathOverride = path; }

    public Path path() {
        if (pathOverride != null) return pathOverride;
        String env = EnvVars.get("AGENTFORGE_AGENT_PREFS");
        return env != null ? Path.of(env) : AgentPaths.stateDir().resolve("agent.json");
    }

    public synchronized void reload() { data = null; ensure(); }

    public synchronized void clear() { data = null; }

    private void ensure() {
        if (data != null) return;
        JsonNode n = StateFile.read(path());
        data = n != null && n.isObject() ? (ObjectNode) n : StateFile.JSON.createObjectNode();
    }

    private String str(String k) {
        ensure();
        JsonNode v = data.get(k);
        return v != null && v.isString() && !v.stringValue().isEmpty() ? v.stringValue() : null;
    }

    private void putStr(String k, String v) {
        ensure();
        if (v == null) data.remove(k); else data.put(k, v);
        StateFile.write(path(), data);
    }

    /** 上次选的模型 ref，或 null。 */
    public synchronized String model() { return str("model"); }

    /** null = 清掉。 */
    public synchronized void setModel(String ref) {
        if (ref != null && ref.isEmpty()) throw new IllegalArgumentException("模型 ref 应当是非空字符串或 null");
        putStr("model", ref);
    }

    /** 上次选的思考档位（厂商原名），或 null（= 默认，什么都不发）。 */
    public synchronized String thinking() { return str("thinking"); }

    public synchronized void setThinking(String level) {
        if (level != null && level.isEmpty()) throw new IllegalArgumentException("思考档位应当是非空字符串或 null");
        putStr("thinking", level);
    }

    /** 审批策略（{@code untrusted / on-failure / on-request / granular… / never}），或 null（= 默认）。 */
    public synchronized String approval() { return str("approval"); }

    public synchronized void setApproval(String policy) {
        if (policy != null && policy.isEmpty()) throw new IllegalArgumentException("审批策略应当是非空字符串或 null");
        putStr("approval", policy);
    }

    /** 沙盒策略（{@code read-only / workspace-write / danger-full-access}），或 null（= 默认）。 */
    public synchronized String sandbox() { return str("sandbox"); }

    public synchronized void setSandbox(String mode) {
        if (mode != null && mode.isEmpty()) throw new IllegalArgumentException("沙盒策略应当是非空字符串或 null");
        putStr("sandbox", mode);
    }

    /** 沙盒里能不能出网；null = 没设（默认断网）。 */
    public synchronized Boolean network() {
        ensure();
        JsonNode v = data.get("network");
        return v != null && v.isBoolean() ? v.booleanValue() : null;
    }

    public synchronized void setNetwork(Boolean on) {
        ensure();
        if (on == null) data.remove("network"); else data.put("network", on);
        StateFile.write(path(), data);
    }

    /** workspace-write 下另加的可写根。 */
    public synchronized java.util.List<String> writableRoots() { return strings("writable_roots"); }

    public synchronized void setWritableRoots(java.util.List<String> roots) { putStrings("writable_roots", roots); }

    /** 以后都免审批的工具（审批时选了「以后都放行」）。 */
    public synchronized java.util.List<String> approvedTools() { return strings("approved_tools"); }

    public synchronized void addApprovedTool(String name) {
        java.util.List<String> cur = new java.util.ArrayList<>(strings("approved_tools"));
        if (!cur.contains(name)) cur.add(name);
        putStrings("approved_tools", cur);
    }

    private java.util.List<String> strings(String k) {
        ensure();
        JsonNode v = data.get(k);
        if (v == null || !v.isArray()) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode x : v) if (x.isString() && !x.stringValue().isEmpty()) out.add(x.stringValue());
        return java.util.List.copyOf(out);
    }

    private void putStrings(String k, java.util.List<String> vs) {
        ensure();
        if (vs == null || vs.isEmpty()) data.remove(k);
        else {
            var a = data.putArray(k);
            vs.forEach(a::add);
        }
        StateFile.write(path(), data);
    }
}
