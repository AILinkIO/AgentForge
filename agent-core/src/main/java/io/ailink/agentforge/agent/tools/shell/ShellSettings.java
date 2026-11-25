package io.ailink.agentforge.agent.tools.shell;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.function.Consumer;

/**
 * run-command 的宿主可调项（coxswain 的 {@code shell-timeout-default} / {@code shell-timeout-cap} /
 * {@code shell-after-git-proc} 三个 parameter + 可换的执行器）。挂在 {@link Workspace} 上：
 * {@code ShellSettings.of(ws).timeoutCap(…)}。
 *
 * <p>⚠️ 超时消息里写的"上限"取的是这里的当前值 —— 文案与政策不得各自漂。
 */
public final class ShellSettings {
    private volatile long timeoutDefaultMs = 60_000;
    private volatile long timeoutCapMs = 600_000;
    private volatile Consumer<String> afterGit;
    private volatile CommandRunner runner = new LocalCommandRunner();

    public static ShellSettings of(Workspace ws) { return ws.component(ShellSettings.class, ShellSettings::new); }

    public long timeoutDefaultMs() { return timeoutDefaultMs; }

    public ShellSettings timeoutDefaultMs(long v) { this.timeoutDefaultMs = v; return this; }

    public long timeoutCapMs() { return timeoutCapMs; }

    public ShellSettings timeoutCapMs(long v) { this.timeoutCapMs = v; return this; }

    /** 改仓库的 git 命令（commit / push / …）**成功**之后叫一声，宿主接成"刷新 git 面板"。 */
    public Consumer<String> afterGit() { return afterGit; }

    public ShellSettings afterGit(Consumer<String> f) { this.afterGit = f; return this; }

    public CommandRunner runner() { return runner; }

    public ShellSettings runner(CommandRunner r) { this.runner = r == null ? new LocalCommandRunner() : r; return this; }
}
