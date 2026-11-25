package io.ailink.agentforge.agent.mcp;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 服务器的认证 token 存哪儿（键 {@code mcp:<名字>}）。
 *
 * <p>⚠️ token <b>不在 spec 里、也不在 state 里</b>：配置目录会被整个塞进 dotfiles 仓库。宿主应当接上
 * 与 provider key 同一个加密仓；没接时用 {@link #inMemory()}（只活在本进程）。
 *
 * <p>界面面没有"读出来看看"的入口（{@link McpManager#hasToken} 只说有没有）——那种入口迟早会把
 * token 贴进 issue 里。{@link #get} 只给连接那一刻用。
 */
public interface McpTokens {
    Optional<String> get(String key);

    void set(String key, String token);

    void remove(String key);

    static McpTokens inMemory() {
        ConcurrentHashMap<String, String> m = new ConcurrentHashMap<>();
        return new McpTokens() {
            @Override public Optional<String> get(String key) { return Optional.ofNullable(m.get(key)); }
            @Override public void set(String key, String token) { m.put(key, token); }
            @Override public void remove(String key) { m.remove(key); }
        };
    }
}
