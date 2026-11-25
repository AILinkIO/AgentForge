package io.ailink.agentforge.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 用户登记的一台 MCP 服务器（纯数据，不认识连接）。对应 coxswain {@code (coxn agent mcp spec)}。
 *
 * <p>给 {@code command} 还是给 {@code url} 本身就是种类 —— 没有 {@code type} 字段（多一个只能填错的
 * 字段是纯负担）。两个都给、一个都不给都当场抛。
 *
 * <p><b>不可变</b>：改一台服务器的配置就重新登记一条（同名后来居上），运行时那边捏着的 spec
 * 不会与表里那条悄悄分岔。
 *
 * <p>前缀强制非空、只许 {@code [A-Za-z0-9_-]}：撞上内置工具名会让审批规则误伤；{@code /} 不是合法的
 * 工具名字符（各家 API 的约束），用户照着写一条 {@code pw/*} 规则会永远不命中而且不报错。
 *
 * <p>{@code url} / {@code headers} / {@code env} 里的 {@code ${VAR}} 在<b>连接那一刻</b>展开
 * （见 {@link EnvRefs}），这里存原文 —— token 轮换之后重连就该拿到新的。
 *
 * @param timeoutMs     握手 + tools/list 的期限（默认 30 秒；{@code npx -y} 第一次要下载，未必够）
 * @param callTimeoutMs 单次 tools/call 的期限（默认 10 分钟）。⚠️ coxswain 只有一个超时同时管两者；
 *                      这里分开，免得浏览器类工具一次调用超过 30 秒就被掐
 * @param authScheme    {@code null} = 头里放裸 token（{@code X-API-Key: sk-…} 那种）
 */
public record McpSpec(String name, Kind kind, List<String> command, String cwd, Map<String, String> env,
                      String url, Map<String, String> headers, boolean enabled, long timeoutMs,
                      long callTimeoutMs, String prefix, String authHeader, String authScheme) {

    public enum Kind { LOCAL, REMOTE }

    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    public static final long DEFAULT_CALL_TIMEOUT_MS = 600_000;

    /** 认得的键。拼错一个当场抛：静静走默认值的结局是"起来了但不是你配的那台"。 */
    public static final Set<String> KEYS = Set.of("command", "cwd", "env", "url", "headers", "enabled",
            "timeout-ms", "call-timeout-ms", "prefix", "auth-header", "auth-scheme");

    public McpSpec {
        command = command == null ? null : List.copyOf(command);
        env = env == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(env));
        headers = headers == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /**
     * 按键值造一条（与 init 配置、界面添加、state 读回走同一条校验 —— 两份校验的下场是
     * "配置里报错、state 里静静吞掉"）。
     *
     * @throws IllegalArgumentException 话是给用户看的
     */
    public static McpSpec of(String name, Map<String, ?> opts) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("服务器名要是一个非空字符串");
        Map<String, ?> o = opts == null ? Map.of() : opts;
        for (String k : o.keySet()) {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("MCP 服务器 " + name + " 的配置里有不认识的键 '" + k
                        + "'（认得的：" + String.join(" ", new java.util.TreeSet<>(KEYS)) + "）");
            }
        }
        Object command = o.get("command");
        Object url = o.get("url");
        Kind kind;
        if (command != null && url != null) {
            throw new IllegalArgumentException("\"" + name + "\" 同时给了 'command 与 'url —— 一台服务器只能是本地（子进程）或远程（HTTP）中的一种");
        } else if (command != null) {
            kind = Kind.LOCAL;
        } else if (url != null) {
            kind = Kind.REMOTE;
        } else {
            throw new IllegalArgumentException("\"" + name + "\" 既没给 'command（本地，子进程 + stdio）也没给 'url（远程，HTTP）");
        }
        List<String> cmd = command == null ? null : strings("command", command);
        String cwd = stringOrNull("cwd", o.get("cwd"));
        Map<String, String> env = alist("env", o.get("env"));
        String u = url == null ? null : stringOrNull("url", url);
        Map<String, String> headers = alist("headers", o.get("headers"));
        Object en = o.get("enabled");
        boolean enabled = en == null || !(en instanceof Boolean b) || b;
        long timeout = positive("timeout-ms", o.containsKey("timeout-ms") ? o.get("timeout-ms") : DEFAULT_TIMEOUT_MS);
        long callTimeout = positive("call-timeout-ms",
                o.containsKey("call-timeout-ms") ? o.get("call-timeout-ms") : DEFAULT_CALL_TIMEOUT_MS);
        String prefix = prefix(name, o.containsKey("prefix") ? o.get("prefix") : null, o.containsKey("prefix"));
        String authHeader = o.containsKey("auth-header") ? stringOrNull("auth-header", o.get("auth-header")) : "Authorization";
        // ⚠️ 'auth-scheme 显式给 null 与不给是两回事：不给 = Bearer，给 null = 裸 token。
        String authScheme = o.containsKey("auth-scheme") ? stringOrNull("auth-scheme", o.get("auth-scheme")) : "Bearer";
        return new McpSpec(name, kind, cmd, cwd, env, u, headers, enabled, timeout, callTimeout, prefix,
                authHeader, authScheme);
    }

    /** 从 state 文件里那一条（JSON 对象）读回来。 */
    public static McpSpec fromJson(String name, ObjectNode o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : o.properties()) {
            m.put(e.getKey(), fromNode(e.getValue()));
        }
        return of(name, m);
    }

    private static Object fromNode(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        if (n.isString()) return n.asString();
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonNode e : n) l.add(fromNode(e));
            return l;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) m.put(e.getKey(), fromNode(e.getValue()));
        return m;
    }

    /** 写回 state 的那串键值（含 enabled）。 */
    public ObjectNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("enabled", enabled);
        if (command != null) {
            ArrayNode a = o.putArray("command");
            command.forEach(a::add);
        }
        if (url != null) o.put("url", url);
        if (cwd != null) o.put("cwd", cwd);
        if (!env.isEmpty()) {
            ObjectNode e = o.putObject("env");
            env.forEach(e::put);
        }
        if (!headers.isEmpty()) {
            ObjectNode h = o.putObject("headers");
            headers.forEach(h::put);
        }
        o.put("timeout-ms", timeoutMs);
        if (callTimeoutMs != DEFAULT_CALL_TIMEOUT_MS) o.put("call-timeout-ms", callTimeoutMs);
        o.put("prefix", prefix);
        if (!"Authorization".equals(authHeader)) o.put("auth-header", authHeader);
        if (!"Bearer".equals(authScheme)) {
            if (authScheme == null) o.putNull("auth-scheme"); else o.put("auth-scheme", authScheme);
        }
        return o;
    }

    /** 只改 enabled。⚠️ 别拆成 opts 再拼回来 —— 拆漏一格不报错，只表现为那一格悄悄回了默认值。 */
    public McpSpec withEnabled(boolean on) {
        return new McpSpec(name, kind, command, cwd, env, url, headers, on, timeoutMs, callTimeoutMs, prefix,
                authHeader, authScheme);
    }

    /** 连接形状是否相同（不比 enabled）：形状一变，旧连接就要整条换掉。 */
    public boolean sameShape(McpSpec o) {
        return o != null && kind == o.kind && Objects.equals(command, o.command) && Objects.equals(cwd, o.cwd)
                && env.equals(o.env) && Objects.equals(url, o.url) && headers.equals(o.headers)
                && Objects.equals(prefix, o.prefix) && timeoutMs == o.timeoutMs && callTimeoutMs == o.callTimeoutMs
                && Objects.equals(authHeader, o.authHeader) && Objects.equals(authScheme, o.authScheme);
    }

    // ── 校验 ────────────────────────────────────────────────────────────

    private static List<String> strings(String who, Object v) {
        if (v instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(x -> x instanceof String)) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add((String) x);
            return out;
        }
        throw new IllegalArgumentException("'" + who + " 要是一串非空的字符串，例如 [\"npx\", \"-y\", \"@playwright/mcp@latest\"]");
    }

    private static String stringOrNull(String who, Object v) {
        if (v == null || v instanceof String) return (String) v;
        throw new IllegalArgumentException("'" + who + " 要是一个字符串");
    }

    private static Map<String, String> alist(String who, Object v) {
        if (v == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof String s)) {
                    throw new IllegalArgumentException("'" + who + " 要是一张 {\"键\": \"值\"} 的表（值都是字符串）");
                }
                out.put(k, s);
            }
            return out;
        }
        throw new IllegalArgumentException("'" + who + " 要是一张 {\"键\": \"值\"} 的表");
    }

    private static long positive(String who, Object v) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && n.longValue() > 0) return n.longValue();
        throw new IllegalArgumentException("'" + who + " 要是一个正整数（毫秒）");
    }

    private static String prefix(String name, Object v, boolean given) {
        Object p = given ? v : name + "_";
        if (!(p instanceof String s) || s.isEmpty()) {
            throw new IllegalArgumentException("'prefix 要是一个非空字符串（前缀不许空：撞上内置工具名会让审批规则误伤）");
        }
        if (!s.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("'prefix 只能用字母/数字/下划线/连字符 —— `/` 不合法（各家 API 的工具名约束）。要 \""
                    + s + "\" 这种分组感的话，界面上按服务器分节，名字用 `_`");
        }
        return s;
    }
}
