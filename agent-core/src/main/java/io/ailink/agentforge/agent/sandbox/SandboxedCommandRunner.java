package io.ailink.agentforge.agent.sandbox;

import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.approval.SessionPermissions;
import io.ailink.agentforge.agent.tools.shell.CommandRunner;
import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.agent.CommandRequest;
import io.ailink.agentforge.sandbox.agent.CommandResult;
import io.ailink.agentforge.sandbox.agent.SandboxCommandRunner;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.approval.Approver;
import io.ailink.agentforge.sandbox.exec.LocalExecBackend;
import io.ailink.agentforge.sandbox.execpolicy.ExecApprovals;
import io.ailink.agentforge.sandbox.orchestrator.OrchestrationRequest;
import io.ailink.agentforge.sandbox.orchestrator.OrchestratorOptions;
import io.ailink.agentforge.sandbox.orchestrator.OutcomeStatus;
import io.ailink.agentforge.sandbox.orchestrator.Permissions;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * run-command 的执行器：Codex 的命令审批 + 沙盒编排（{@link CommandRunner} → sandbox 模块的 {@link SandboxOrchestrator}）。
 *
 * <h2>一条命令怎么走</h2>
 * <ol>
 *   <li>{@code ["bash", "-lc", 命令]} 交给 execpolicy（{@link ExecApprovals}）：规则 + 已知安全 / 危险命令的启发式 × 审批策略 × 沙盒策略
 *       ⇒ 禁止 / 要审批 / 免审批（每一段都被规则明确放行时连沙盒都不上）；</li>
 *   <li>要审批 ⇒ 摆 EXEC 审批（会话的审批面板）；</li>
 *   <li>在平台沙盒（bwrap / Landlock）里跑第一次；策略不需要沙盒（danger-full-access）就直接跑；本机没有沙盒后端时经审批后裸跑；</li>
 *   <li>被沙盒拦下 ⇒ 审批策略许（untrusted / on-failure / granular+sandbox）就摆 EXEC_RETRY「不带沙盒重跑吗」，否则把拦截原因回给模型
 *       （on-request 下它该带 {@code sandbox_permissions=require_escalated} + justification 重新申请）。</li>
 * </ol>
 * 本会话「不再问」按命令原文缓存（{@link SessionPermissions#execApprovals()}）。
 *
 * <h2>cwd 跟踪改由宿主写文件</h2>
 * run-command 原本让命令自己 {@code pwd -P >| <cwdfile>}；只读沙盒里那一下写不出去，而且失败信息里的 "Read-only file system"
 * 会被拒判当成"沙盒挡了"。所以这里把尾巴换成往 stdout 末尾打一行标记，跑完从输出里摘掉、由本进程写 cwd 文件。
 */
public final class SandboxedCommandRunner implements CommandRunner {

    static final String MARK = "__AGENTFORGE_CWD__=";
    /** ShellSession#wrapWithCwdCapture 的 POSIX 形状。 */
    private static final Pattern CWD_TAIL = Pattern.compile(
            "^(.*); _code=\\$\\?; pwd -P >\\| ('(?:[^']|'\\\\'')*'|\\S+); exit \\$_code$", Pattern.DOTALL);
    /** 后台任务的时限（前台由 run-command 的 timeout 管）。 */
    static final long BACKGROUND_TIMEOUT_MS = 24L * 3600 * 1000;

    private final Sandbox sandbox;
    private final Supplier<Path> root;
    private final SessionPermissions perms;
    private final ExecApprovalHost host;

    /**
     * @param sandbox 平台沙盒（{@link CommandSandbox} 探测选好的）；{@code null} ⇒ 本机没有，受限策略下命令经审批后裸跑
     * @param root    当前工作根（worktree-enter 之后会变，所以每次现取）
     * @param perms   会话的权限状态
     * @param host    会话那一侧（摆审批）
     */
    public SandboxedCommandRunner(Sandbox sandbox, Supplier<Path> root, SessionPermissions perms, ExecApprovalHost host) {
        this.sandbox = sandbox;
        this.root = Objects.requireNonNull(root);
        this.perms = Objects.requireNonNull(perms);
        this.host = Objects.requireNonNull(host);
    }

    @Override
    public boolean approvesCommands() { return true; }

    /** 用的平台沙盒名；没有 ⇒ null。 */
    public String backend() { return sandbox == null ? null : sandbox.probe().name(); }

    static String approvalKey(String command) { return "run-command\u0000" + command; }

    static List<String> policyArgv(String command) { return List.of("bash", "-lc", command); }

    /** 一次调用的求值上下文。 */
    private record Plan(PermissionSettings settings, Path root, SandboxPolicy policy, ExecApprovals.Result eval, Permissions permissions) { }

    private Plan plan(Request req) {
        PermissionSettings s = perms.settings();
        Path r = root.get();
        SandboxPolicy pol = s.policy(r);
        Permissions p = req.escalated() ? Permissions.REQUIRE_ESCALATED : Permissions.USE_DEFAULT;
        ExecApprovals.Result ev = perms.execPolicy().evaluate(new ExecApprovals.Request(policyArgv(req.command()), s.approval(), pol, p,
                req.prefixRule(), sandbox != null));
        return new Plan(s, r, pol, ev, p);
    }

    /** 审批策略不许发起沙盒审批时，模型申请出沙盒直接回绝（Codex exec_command 的同一句）。 */
    static String escalationRejected(ApprovalPolicy ap) {
        Message m = ap.promptRejection(false);
        return m == null ? null : Message.of("sandbox/execpolicy-escalation-rejected", ap.wire()).text();
    }

    private SandboxCommandRunner runner(Plan pl, Request req, Approver approver, boolean escalate, int outputBytes) {
        OrchestrationRequest oreq = OrchestrationRequest.of(pl.policy(), PathContext.forCwd(pl.root().toString()))
                .withApprovalPolicy(pl.settings().approval())
                .withRequirement(pl.eval().requirement())
                .withPermissions(pl.permissions())
                .withEscalateOnFailure(escalate);
        SandboxOrchestrator o = new SandboxOrchestrator(new LocalExecBackend(), sandbox, approver,
                OrchestratorOptions.DEFAULT.withApprovalCache(perms.execApprovals()).withRunUnsandboxedWhenUnavailable(true));
        return new SandboxCommandRunner(o, oreq, Math.max(1, outputBytes));
    }

    private SessionExecApprover approver(Plan pl, Request req) {
        return new SessionExecApprover(host, perms, req.command(), policyArgv(req.command()), req.cwd() == null ? null : req.cwd().toString(),
                req.justification(), pl.eval().proposedAmendment(), req.toolUseId());
    }

    @Override
    public Result run(Request req) throws Exception {
        Plan pl = plan(req);
        if (req.escalated()) {
            String no = escalationRejected(pl.settings().approval());
            if (no != null) return rejected(no);
        }
        Rewritten rw = rewrite(req.argv());
        int cap = (int) Math.min(Integer.MAX_VALUE, Math.max(1, req.maxBytes()));
        SandboxCommandRunner r = runner(pl, req, approver(pl, req), true, cap);
        CommandRequest cr = new CommandRequest(rw.argv(), req.cwd().toString(), null, Math.max(1, req.timeoutMs()), req.escalated(),
                approvalKey(req.command()));
        Thread me = Thread.currentThread();
        Runnable unhook = req.cancel() == null ? () -> { } : req.cancel().onCancel(me::interrupt);
        CommandResult res;
        try {
            res = r.run(cr);
        } finally {
            unhook.run();
        }
        String stdout = res.stdout() == null ? "" : res.stdout();
        String stderr = res.stderr() == null ? "" : res.stderr();
        if (rw.cwdFile() != null) stdout = takeCwd(stdout, rw.cwdFile());
        String note = note(res);
        if (note != null) stderr = stderr.isEmpty() ? note : stderr + (stderr.endsWith("\n") ? "" : "\n") + note;
        byte[] out = stdout.getBytes(StandardCharsets.UTF_8), err = stderr.getBytes(StandardCharsets.UTF_8);
        boolean ot = out.length > req.maxBytes(), et = err.length > req.maxBytes();
        if (ot) out = java.util.Arrays.copyOf(out, cap);
        if (et) err = java.util.Arrays.copyOf(err, cap);
        return new Result(res.exitCode(), out, err, res.timedOut(), ot, et);
    }

    private static Result rejected(String why) {
        return new Result(126, new byte[0], ("[沙盒] 命令没有执行：" + why).getBytes(StandardCharsets.UTF_8), false, false, false);
    }

    /** 给模型的一句话：被拒 / 被沙盒挡 / 出沙盒重跑过（让它分清"命令失败"和"被管住了"）。 */
    static String note(CommandResult r) {
        String why = r.reason() == null ? null : r.reason().text();
        if (r.status() == OutcomeStatus.ABORTED) return "[沙盒] 命令没有执行：用户停下了这一轮。";
        if (r.rejected() || r.status() == OutcomeStatus.REJECTED) {
            return "[沙盒] 命令没有执行" + (why == null ? "" : "：" + why);
        }
        if (r.status() == OutcomeStatus.DENIED) {
            return "[沙盒] 命令被沙盒拦下（" + (why == null ? "越权访问" : why) + "）。工作根之外不可写、网络不通；确实需要的话，"
                    + "带上 sandbox_permissions=\"require_escalated\" 和一句 justification 重新调用，由用户决定是否放行。";
        }
        if (r.status() == OutcomeStatus.ESCALATED) return "[沙盒] 用户批准后已在沙盒外重跑。";
        return null;
    }

    @Override
    public Background spawn(String command, List<String> argv, Path cwd) throws Exception {
        return spawn(new Request(command, argv, cwd, 0, 0, null));
    }

    /**
     * 后台任务：审批在起之前当场问完（后台没人守着审批面板）；沙盒拦下就是拦下，不问重跑。
     */
    @Override
    public Background spawn(Request req) throws Exception {
        Plan pl = plan(req);
        if (req.escalated()) {
            String no = escalationRejected(pl.settings().approval());
            if (no != null) throw new IOException(no);
        }
        ApprovalRequirement requirement = pl.eval().requirement();
        boolean approved = false;
        switch (requirement) {
            case ApprovalRequirement.Forbidden f -> throw new IOException(f.reason().text());
            case ApprovalRequirement.NeedsApproval n -> {
                String key = approvalKey(req.command());
                if (!perms.execApprovals().isApproved(key)) {
                    SessionExecApprover a = approver(pl, req);
                    ReviewDecision d = a.askUpfront(n.reason() == null ? null : n.reason().text());
                    ApprovalDecision ad = a.apply(d);
                    if (ad == ApprovalDecision.DENY || ad == ApprovalDecision.ABORT) throw new IOException("用户没有批准这条命令");
                    if (ad == ApprovalDecision.APPROVE_FOR_SESSION) perms.execApprovals().approve(key);
                }
                approved = true;
            }
            case ApprovalRequirement.Skip s -> { }
        }
        boolean bypass = requirement instanceof ApprovalRequirement.Skip s && s.bypassSandbox() || (req.escalated() && approved);
        boolean sandboxed = !bypass && sandbox != null && pl.policy().requiresPlatformSandbox();
        if (sandboxed && !pl.policy().fullDiskWriteAccess() && pl.policy().entries().stream()
                .noneMatch(e -> e.access() == io.ailink.agentforge.sandbox.policy.AccessMode.WRITE)) {
            throw new IOException("只读沙盒里不能起后台任务（日志写不进临时目录）；请前台跑这条命令");
        }
        Plan bg = new Plan(pl.settings(), pl.root(), pl.policy(),
                new ExecApprovals.Result(ApprovalRequirement.skip(bypass), null, pl.eval().commands()), pl.permissions());
        SandboxCommandRunner r = runner(bg, req, x -> ApprovalDecision.DENY, false, 64 * 1024);
        CommandRequest cr = new CommandRequest(req.argv(), req.cwd().toString(), null, BACKGROUND_TIMEOUT_MS, false, approvalKey(req.command()));
        CompletableFuture<Integer> done = new CompletableFuture<>();
        Thread.ofVirtual().name("sandbox-bg").start(() -> {
            try {
                done.complete(r.run(cr).exitCode());
            } catch (Exception e) {
                done.complete(-1);
            }
        });
        return new Background() {
            @Override public long pid() { return -1; }       // 进程在编排器里，拿不到 pid

            @Override public CompletableFuture<Integer> onExit() { return done; }
        };
    }

    // ── cwd 跟踪 ─────────────────────────────────────────────────────────

    record Rewritten(List<String> argv, Path cwdFile) { }

    /** 认出 {@code [sh, -c, "<cmd>; _code=$?; pwd -P >| <file>; exit $_code"]} 就把尾巴换成打标记；认不出原样返回。 */
    static Rewritten rewrite(List<String> argv) {
        if (argv.size() != 3 || !"-c".equals(argv.get(1))) return new Rewritten(argv, null);
        Matcher m = CWD_TAIL.matcher(argv.get(2));
        if (!m.matches()) return new Rewritten(argv, null);
        String file = m.group(2);
        if (file.startsWith("'")) file = file.substring(1, file.length() - 1).replace("'\\''", "'");
        String script = m.group(1) + "; _code=$?; printf '\\n%s%s\\n' '" + MARK + "' \"$(pwd -P)\"; exit $_code";
        List<String> out = new ArrayList<>(argv.subList(0, 2));
        out.add(script);
        return new Rewritten(out, Path.of(file));
    }

    /** 从 stdout 末尾摘掉标记行、把 cwd 写进文件；回去掉标记的 stdout。 */
    static String takeCwd(String stdout, Path cwdFile) {
        int at = stdout.lastIndexOf("\n" + MARK);
        if (at < 0) return stdout;
        int from = at + 1 + MARK.length();
        int nl = stdout.indexOf('\n', from);
        String dir = nl < 0 ? stdout.substring(from) : stdout.substring(from, nl);
        String rest = nl < 0 ? "" : stdout.substring(nl + 1);
        try {
            Files.writeString(cwdFile, dir + "\n", StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // cwd 跟踪是锦上添花：写不出就当这条命令没 cd
        }
        return stdout.substring(0, at) + rest;
    }
}
