package io.ailink.agentforge.agent.tools.shell;

import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.Limit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话级 shell 状态：cwd 跟踪 + 后台任务表（coxswain {@code shell-state.ss}）。
 *
 * <p>每次 run-command 是一条新的 {@code sh -c}，进程间 cwd 不保留；模型 {@code cd foo} 之后期望
 * 下一条 {@code ls} 看见 foo。解法借自 Claude Code：命令尾巴上 {@code pwd -P >| <cwdfile>}，
 * 跑完读回来、下次 spawn 当 cwd 用。**没有持久 shell 进程**，状态用文件交接。
 *
 * <p>取法：会话把自己那份放进 {@code ToolEnv.service(ShellSession.class)}；没有就用
 * {@code Workspace} 上的那份（同一工作区的会话共享 cwd）。
 */
public final class ShellSession {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final AtomicInteger SESSION_SEQ = new AtomicInteger();

    private volatile Path cwd;
    private final Path cwdFile;
    private final int seq;
    private int taskCounter;
    private final List<ShellTask> tasks = new ArrayList<>();

    public ShellSession(Path initialCwd) {
        this.cwd = initialCwd.toAbsolutePath().normalize();
        this.seq = SESSION_SEQ.incrementAndGet();
        this.cwdFile = tmpDir().resolve("agentforge-cwd-" + ProcessHandle.current().pid() + "-" + seq + "-" + System.nanoTime() + ".txt");
    }

    /** 会话给的那份，否则工作区上的那份。 */
    public static ShellSession of(ToolEnv env) {
        ShellSession s = env.service(ShellSession.class);
        if (s != null) return s;
        return env.workspace().component(ShellSession.class, () -> new ShellSession(env.workspace().root()));
    }

    public Path cwd() { return cwd; }

    public void cwd(Path p) { this.cwd = p.toAbsolutePath().normalize(); }

    public Path cwdFile() { return cwdFile; }

    static Path tmpDir() { return Path.of(System.getProperty("java.io.tmpdir")); }

    /**
     * 包成 {@code <cmd>; _code=$?; pwd -P >| <cwdfile>; exit $_code}（POSIX）。
     * 不用 {@code &&}：命令失败也要记 cwd；退出码显式恢复，否则 {@code ; pwd} 会把失败盖成 0。
     * {@code >|} 绕过用户 profile 里的 {@code set -C}。Windows 走一个批处理尾巴（命令行上
     * {@code %ERRORLEVEL%} 是解析期展开的，拿到的是上一条的码）。
     */
    public String wrapWithCwdCapture(String command) {
        if (WINDOWS) return command + " & \"" + ensureEpilogue() + "\" \"" + cwdFile + "\"";
        return command + "; _code=$?; pwd -P >| " + shellQuote(cwdFile.toString()) + "; exit $_code";
    }

    static String shellQuote(String s) {
        if (s.matches("[A-Za-z0-9_./:@%+=,-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static final String EPILOGUE_BODY = "@echo off\r\nset _coxn_code=%ERRORLEVEL%\r\ncd > %1\r\nexit /b %_coxn_code%\r\n";

    static Path epiloguePath() {
        return tmpDir().resolve("agentforge-cwd-epilogue-" + ProcessHandle.current().pid() + ".cmd");
    }

    /** 惰性写出批处理尾巴；写不出不抛（cwd 跟踪是锦上添花）。 */
    static String ensureEpilogue() {
        Path p = epiloguePath();
        if (!Files.exists(p)) {
            try { Files.writeString(p, EPILOGUE_BODY, StandardCharsets.UTF_8); } catch (IOException ignored) { }
        }
        return p.toString().replace('/', '\\');
    }

    /** 读 cwdfile 的结果：路径 / 越界 / 没写（命令没跑到 pwd）。 */
    sealed interface CwdRead permits CwdRead.Ok, CwdRead.OutOfBounds, CwdRead.None {
        record Ok(Path path) implements CwdRead { }

        record OutOfBounds() implements CwdRead { }

        record None() implements CwdRead { }
    }

    static CwdRead readCwdFromFile(Path cwdFile, Path root) {
        String line;
        try {
            List<String> ls = Files.readAllLines(cwdFile, StandardCharsets.UTF_8);
            if (ls.isEmpty()) return new CwdRead.None();
            line = ls.getFirst().strip();
        } catch (IOException | RuntimeException e) {
            return new CwdRead.None();
        }
        if (line.isEmpty()) return new CwdRead.None();
        try {
            return new CwdRead.Ok(Limit.resolveUnder(root, line));
        } catch (ToolException e) {
            return new CwdRead.OutOfBounds();
        }
    }

    /**
     * 跑完命令后调：读回 cwd、校验、更新。回 null（无告警）或一句给模型看的告警。
     * 越界 → **回退到 root** 并告诉模型（而不是保持旧 cwd：让它知道是越界，不是 cd 没生效）；
     * 没写（超时 / {@code exit N}）→ 保持旧 cwd。
     */
    public String update(Path root) {
        CwdRead r = readCwdFromFile(cwdFile, root);
        try { Files.deleteIfExists(cwdFile); } catch (IOException ignored) { }
        return switch (r) {
            case CwdRead.Ok ok -> { cwd(ok.path()); yield null; }
            case CwdRead.OutOfBounds o -> { cwd(root); yield "cd 越出了根目录，cwd 已重置到 " + root; }
            case CwdRead.None n -> null;
        };
    }

    /** 登记下一个后台任务：分配 id 与日志路径（临时目录，放工作根里会污染 git status）。 */
    public synchronized ShellTask registerTask(String command) {
        taskCounter++;
        String id = "bg-" + taskCounter;
        Path log = tmpDir().resolve("agentforge-shell-" + ProcessHandle.current().pid() + "-" + seq + "-" + id + ".log");
        ShellTask t = new ShellTask(id, command, log, System.currentTimeMillis());
        tasks.add(t);
        return t;
    }

    /** 按启动顺序（bg-1 在前）。 */
    public synchronized List<ShellTask> tasks() { return List.copyOf(tasks); }

    public synchronized ShellTask task(String id) {
        for (ShellTask t : tasks) if (t.id().equals(id)) return t;
        return null;
    }
}
