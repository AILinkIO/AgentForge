package io.ailink.agentforge.agent.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置 / 状态 / 数据目录（对应 coxswain 的 {@code <coxn 目录>}、{@code state/}）。
 *
 * <ul>
 *   <li>{@link #configDir()} —— 用户写的配置：{@code $AGENTFORGE_HOME} 或
 *       {@code $XDG_CONFIG_HOME/agentforge}（默认 {@code ~/.config/agentforge}）；
 *       Windows 上 {@code %APPDATA%\agentforge}。</li>
 *   <li>{@link #stateDir()} —— 程序写的状态（上次选的模型、provider、MCP 开关…）：{@code configDir/state}。</li>
 *   <li>{@link #dataDir()} —— 数据（会话库、models.dev 缓存、截断落盘）：{@code configDir/data}。</li>
 * </ul>
 *
 * <p>测试用 {@link #override(Path)} 指到临时目录。
 */
public final class AgentPaths {
    private static volatile Path override;

    private AgentPaths() { }

    public static void override(Path dir) { override = dir; }

    public static Path configDir() {
        if (override != null) return override;
        String home = System.getenv("AGENTFORGE_HOME");
        if (home != null && !home.isBlank()) return Path.of(home);
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata != null) return Path.of(appdata, "agentforge");
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("agentforge");
    }

    public static Path stateDir() { return ensure(configDir().resolve("state")); }

    public static Path dataDir() { return ensure(configDir().resolve("data")); }

    public static Path ensure(Path p) {
        try { Files.createDirectories(p); } catch (IOException ignored) { }
        return p;
    }
}
