package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.config.AgentPaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户在界面上对 MCP 服务器做过的决定（程序写的，落 {@code <stateDir>/mcp.json}）。
 *
 * <p>为什么要落盘：「停用 X」是跨会话的决定 —— 用户停用它多半是因为它坏了或者太吵，只活在内存里的话
 * 重启一次它又回来了。为什么不写进配置文件：那是人写的、程序只读。
 *
 * <p>每条是 {@code 名字 → {键: 值}}：
 * <ul>
 *   <li>配置里定义的那些，这里只可能记一条 {@code enabled}（形状归配置说了算）；</li>
 *   <li>界面上加出来的，整条 spec 都在这里。</li>
 * </ul>
 * 合并规则在 {@link McpManager#syncSpecs()}。⚠️ <b>秘密不在这里</b>（token 见 {@link McpTokens}）。
 *
 * <p>读坏了当成空、一条坏的跳过不整份作废；写不动不抛（这次的决定本次生效、下次没了，而不是崩掉）。
 */
public final class McpState {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path path;
    /** null = 还没读过；与"读过了，是空的"分得开，免得空文件让每次查询都读一遍盘。 */
    private LinkedHashMap<String, ObjectNode> entries;

    public McpState(Path path) { this.path = path; }

    /** 默认位置：{@code AgentPaths.stateDir()/mcp.json}。 */
    public static McpState defaultState() { return new McpState(AgentPaths.stateDir().resolve("mcp.json")); }

    public Path path() { return path; }

    public synchronized void reload() { entries = null; ensure(); }

    /** 只清内存，不动盘上那份（"模拟重启"与重读配置用）。 */
    public synchronized void clear() { entries = null; }

    /** 名字 → 选项（拷贝）。 */
    public synchronized List<Map.Entry<String, ObjectNode>> entries() {
        ensure();
        List<Map.Entry<String, ObjectNode>> out = new ArrayList<>();
        entries.forEach((k, v) -> out.add(Map.entry(k, v.deepCopy())));
        return out;
    }

    /** 一条的选项；没有这条是 null（"用户没动过它"，与"动过但没留下什么"分得开）。 */
    public synchronized ObjectNode ref(String name) {
        ensure();
        ObjectNode o = entries.get(name);
        return o == null ? null : o.deepCopy();
    }

    /** 记一条：已在里面时替换选项、保住位置。 */
    public synchronized void set(String name, ObjectNode opts) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("服务器名应当是一个非空字符串");
        ensure();
        entries.put(name, opts == null ? JsonNodeFactory.instance.objectNode() : opts.deepCopy());
        save();
    }

    public synchronized void remove(String name) {
        ensure();
        if (entries.remove(name) != null) save();
    }

    private void ensure() { if (entries == null) entries = read(); }

    private LinkedHashMap<String, ObjectNode> read() {
        LinkedHashMap<String, ObjectNode> out = new LinkedHashMap<>();
        if (path == null || !Files.isRegularFile(path)) return out;
        try {
            JsonNode root = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
            JsonNode servers = root == null ? null : root.get("servers");
            if (servers == null || !servers.isArray()) return out;
            for (JsonNode e : servers) {
                JsonNode n = e.get("name");
                if (!(e instanceof ObjectNode o) || n == null || !n.isString() || n.asString().isEmpty()) continue;
                ObjectNode opts = o.deepCopy();
                opts.remove("name");
                out.put(n.asString(), opts);
            }
        } catch (RuntimeException | IOException ignored) {
            // 读坏了当成空：配置目录里任何一个文件坏掉都不该拦住启动
        }
        return out;
    }

    private void save() {
        if (path == null) return;
        try {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("_comment", "AgentForge 生成的：你在界面上对 MCP 服务器做过的决定。手改会被下一次操作覆盖；token 不在这里。");
            ArrayNode arr = root.putArray("servers");
            entries.forEach((k, v) -> {
                ObjectNode o = JsonNodeFactory.instance.objectNode();
                o.put("name", k);
                o.setAll(v);
                arr.add(o);
            });
            Path dir = path.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "mcp", ".tmp");
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (RuntimeException | IOException ignored) {
            // 写不动不抛
        }
    }
}
