package io.ailink.agentforge.agent.tools.shell;

import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tools.Limit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * run-command：跑一条 shell 命令（coxswain {@code tools/shell.ss}）。
 *
 * <p>⚠️ 标 {@code sensitive}，不是"保险起见"：拒掉 write-file 之后模型会立刻改用
 * {@code echo … >> a.txt}。审批要按能力划。它也不 read-only、不可暂存（跑出去就跑出去了）、
 * 不并发（判据会漏，漏的方向是两条互相踩的命令被并排跑）。
 *
 * <ul>
 *   <li>stdout / stderr 分开，各自截到 {@link Limit#SHELL_OUTPUT_LIMIT}，超了完整那份落盘。</li>
 *   <li>cwd 跨调用保留（{@link ShellSession}）；越界回退到根并告警。</li>
 *   <li>超时默认 / 上限由 {@link ShellSettings} 给；超时消息写明"实际生效的 timeout、量出来的时长、
 *       上限"，并指两条出路（加大 timeout / 后台跑）。</li>
 *   <li>每流 1MB 捕获封顶，触到不杀进程。</li>
 *   <li>后台：{@code ( cmd ) > log 2>&1}，立即回任务号与日志路径；不过 cwd 捕获（看不见何时跑完的
 *       命令不许改会话状态）。</li>
 * </ul>
 */
public final class ShellTools {
    private ShellTools() { }

    static final long MAX_CAPTURE_BYTES = 1024 * 1024;
    static final String STDOUT_TRUNC_NOTE = "[输出超过 1MB 被截断——命令照常跑完了；要完整输出就用 run-in-background 落日志]";
    static final String STDERR_TRUNC_NOTE = "[stderr 超过 1MB 被截断——命令照常跑完了；要完整输出就用 run-in-background 落日志]";

    public static List<AgentTool> tools() { return List.of(runCommand()); }

    public static AgentTool runCommand() {
        return AgentTool.builder("run-command")
                .description("在工作根目录下执行一条 shell 命令，分别返回 stdout 和 stderr；cd 会记住（会话级 cwd）。"
                        + "可传 run-in-background 后台跑：立即返回任务号与日志路径，不阻塞会话。"
                        + "⚠️ 它是给 git / 构建 / 测试 / 包管理这类终端操作用的 —— 读文件、写文件、搜内容、"
                        + "找文件名**别用它**，用 read-file / edit-file / search-text / find-files，"
                        + "那几件更稳、多数不用等审批。"
                        + "git 纪律：只在用户明确要求时 commit / push / 建 PR；提交前先看 git status 与 git diff，"
                        + "只 add 这次要提交的文件；不要 --amend、--force、--no-verify、-i 这类交互式参数，"
                        + "提交被钩子拒了就修好再提一次新的。"
                        + "输出每流 30000 字符封顶；超了完整输出会落到一个文件，注记里有路径，"
                        + "用 grep / sed -n 看需要的那段，别重跑。")
                .required("command", "string", "要执行的命令")
                .optional("timeout", "integer", "超时毫秒，默认取宿主配置（60000），上限也由宿主配置（600000）", 0L)
                .optional("run-in-background", "boolean", "后台跑：立即返回任务号与日志路径，不阻塞会话", false)
                .param(io.ailink.agentforge.agent.tool.Param.optional("sandbox_permissions", "string",
                        "这条命令的沙盒要求：use_default（缺省，在沙盒里跑）/ require_escalated（要在沙盒外跑，需用户批准；"
                        + "只在确实需要写工作根之外、联网等沙盒不许的事时用，并写 justification）", "use_default")
                        .withEnum(List.of("use_default", "require_escalated")))
                .optional("justification", "string", "require_escalated 时给用户看的一句理由（问用户的话）；其余情况省略", "")
                .param(io.ailink.agentforge.agent.tool.Param.optional("prefix_rule", "array",
                        "require_escalated 时可提议一个可复用的前缀，用户可以选「以后以它开头的命令不再问」，例如 [\"npm\", \"install\"]", null))
                .sensitive()
                .handler(ShellTools::run)
                .build();
    }

    static String run(ToolEnv env, Args a) throws Exception {
        String command = a.requireStr("command");
        long timeout = a.integer("timeout", 0);
        boolean bg = a.bool("run-in-background", false);
        String sp = a.str("sandbox_permissions", "use_default");
        if (!sp.isEmpty() && !sp.equals("use_default") && !sp.equals("require_escalated"))
            throw new ToolException("sandbox_permissions 只能是 use_default 或 require_escalated：" + sp);
        boolean escalated = sp.equals("require_escalated");
        String justification = a.str("justification", "");
        List<String> prefixRule = io.ailink.agentforge.agent.approval.ToolApprovals.prefixRule(a.raw());
        Path root = env.workspace().root();
        ShellSettings cfg = ShellSettings.of(env.workspace());
        ShellSession sess = ShellSession.of(env);
        Path cwd = sess.cwd();
        if (!Files.isDirectory(cwd)) { sess.cwd(root); cwd = root; }
        if (bg) return runInBackground(cfg.runner(), sess, cwd, command, escalated, justification, prefixRule, env.toolUseId(), env.cancel());

        String wrapped = sess.wrapWithCwdCapture(command);
        long requested = timeout == 0 ? cfg.timeoutDefaultMs() : timeout;
        long applied = Math.min(Math.max(requested, 1), cfg.timeoutCapMs());
        long t0 = System.nanoTime();
        CancelToken cancel = env.cancel();
        CommandRunner.Result r = cfg.runner().run(new CommandRunner.Request(
                command, shellArgv(wrapped), cwd, applied, MAX_CAPTURE_BYTES, cancel, escalated,
                justification.isEmpty() ? null : justification, prefixRule, env.toolUseId()));
        long elapsed = (System.nanoTime() - t0) / 1_000_000;
        cancel.throwIfCancelled();
        String warning = sess.update(root);
        String text = format(r.exitCode(), decode(r.stdout()), decode(r.stderr()), r.timedOut(), warning,
                applied, elapsed, cfg.timeoutCapMs(), r.stdoutTruncated(), r.stderrTruncated());
        if (!r.timedOut()) afterGit(cfg.afterGit(), command, r.exitCode());
        return text;
    }

    /** 认 {@code $SHELL}（文件在才用），否则 {@code /bin/sh}；Windows 用 {@code cmd /c}。不加 {@code 2>&1}。 */
    static List<String> shellArgv(String command) {
        if (ShellSession.WINDOWS) return List.of("cmd", "/c", command);
        String sh = System.getenv("SHELL");
        if (sh == null || sh.isEmpty() || !Files.isRegularFile(Path.of(sh))) sh = "/bin/sh";
        return List.of(sh, "-c", command);
    }

    static String decode(byte[] b) {
        try {
            return new String(b, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return "（输出解不出来，没有显示）";
        }
    }

    static String format(int code, String stdout, String stderr, boolean timedOut, String warning,
                         long appliedMs, long elapsedMs, long cap, boolean outTrunc, boolean errTrunc) {
        StringBuilder b = new StringBuilder();
        if (timedOut) {
            b.append("超时（timeout ").append(appliedMs).append(" ms，已杀；实际跑了 ").append(elapsedMs)
                    .append(" ms）。这命令若本来就要更久：加大 timeout 重试（上限 ").append(cap)
                    .append("，宿主可调），或加 run-in-background: true 后台跑、轮询日志。\n");
        } else {
            b.append("退出码 ").append(code).append('\n');
        }
        if (warning != null && !warning.isEmpty()) b.append(warning).append('\n');
        b.append("── stdout ──\n");
        if (stdout.isEmpty()) b.append("（没有输出）\n");
        else b.append(Limit.truncateNoteSpill(stdout, Limit.SHELL_OUTPUT_LIMIT, "stdout"))
                .append(outTrunc ? STDOUT_TRUNC_NOTE : "").append('\n');
        b.append("── stderr ──\n");
        if (stderr.isEmpty()) b.append("（没有输出）");
        else b.append(Limit.truncateNoteSpill(stderr, Limit.SHELL_OUTPUT_LIMIT, "stderr")).append(errTrunc ? STDERR_TRUNC_NOTE : "");
        return b.toString();
    }

    static String runInBackground(CommandRunner runner, ShellSession sess, Path cwd, String command) throws Exception {
        return runInBackground(runner, sess, cwd, command, false, null, null, null, null);
    }

    static String runInBackground(CommandRunner runner, ShellSession sess, Path cwd, String command, boolean escalated, String justification,
                                  List<String> prefixRule, String toolUseId, CancelToken cancel) throws Exception {
        ShellTask task = sess.registerTask(command);
        String log = task.log().toString();
        String wrapped = ShellSession.WINDOWS
                ? command + " > \"" + log + "\" 2>&1"
                : "( " + command + " ) > " + ShellSession.shellQuote(log) + " 2>&1";
        CommandRunner.Background h;
        try {
            h = runner.spawn(new CommandRunner.Request(command, shellArgv(wrapped), cwd, 0, 0, cancel, escalated,
                    justification == null || justification.isEmpty() ? null : justification, prefixRule, toolUseId));
        } catch (Exception e) {
            task.finish(-1);
            throw new ToolException("后台任务起不来：" + e.getMessage());
        }
        task.pid(h.pid());
        h.onExit().whenComplete((code, ex) -> {
            int c = code == null ? -1 : code;
            try {
                Files.writeString(task.log(), "\n[agentforge 后台任务 " + task.id() + " 结束：退出码 " + c + "]\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
            task.finish(c);
        });
        return "后台任务已启动：" + task.id() + "\n命令：" + command + "\n日志：" + log
                + "\n查看进度：run-command \"tail -n 50 " + log + "\"\n进程结束时会看到一行 \"[agentforge 后台任务 "
                + task.id() + " 结束：退出码 N]\"——那就是完成信号。";
    }

    // ── git tracking：模型 commit / push 之后让宿主刷新 git 面板 ─────────

    static final Set<String> GIT_MUTATING_VERBS = Set.of(
            "commit", "push", "pull", "fetch", "merge", "rebase", "reset", "checkout", "switch", "restore",
            "tag", "stash", "cherry-pick", "revert", "am", "apply", "branch", "worktree", "clean", "rm", "mv", "add");

    /** 认的是命令串**开头**（跳过 -C / -c 及其值、别的选项）；{@code cd x && git commit} 认不出，代价只是晚一步刷新。 */
    static boolean gitMutation(String command) {
        List<String> ws = new ArrayList<>(List.of(command.strip().split("\\s+")));
        if (ws.isEmpty() || ws.getFirst().isEmpty()) return false;
        if (ws.getFirst().equals("gh")) return ws.size() > 1 && ws.get(1).equals("pr");
        if (!ws.getFirst().equals("git")) return false;
        for (int i = 1; i < ws.size(); i++) {
            String w = ws.get(i);
            if (w.equals("-C") || w.equals("-c")) { i++; continue; }
            if (w.startsWith("-")) continue;
            return GIT_MUTATING_VERBS.contains(w);
        }
        return false;
    }

    private static void afterGit(Consumer<String> hook, String command, int code) {
        if (hook != null && code == 0 && gitMutation(command)) {
            try { hook.accept(command); } catch (RuntimeException ignored) { }
        }
    }
}
