package io.ailink.agentforge.sandbox.agent;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.SandboxPlatform;
import io.ailink.agentforge.sandbox.approval.Approver;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.exec.LocalExecBackend;
import io.ailink.agentforge.sandbox.orchestrator.OrchestrationRequest;
import io.ailink.agentforge.sandbox.orchestrator.OrchestratorOptions;
import io.ailink.agentforge.sandbox.orchestrator.Outcome;
import io.ailink.agentforge.sandbox.orchestrator.OutcomeStatus;
import io.ailink.agentforge.sandbox.orchestrator.Permissions;
import io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 智能体「执行命令」工具的门面（框架无关；≙ keelson clj-sandbox-agent 的 runner）：宿主配好编排器与一次会话的策略 / 上下文 / 审批，
 * 工具每次调用只给 argv。这里做形状翻译：编排结果 ⇒ {@link CommandResult}。输出超过 {@code outputBytes} 时头尾各留一半、中间换成
 * 截断标记（模型上下文有限，⛔ 只留头：错误常在尾部）；审批未过 / 平台沙盒不可用等被编排器拒绝 ⇒ {@code rejected = true}、退出码 126。
 * <p>线程安全（不可变；会话审批状态在 {@link OrchestratorOptions#approvalCache()} 里）。
 */
public final class SandboxCommandRunner {
    /** 截断标记。 */
    public static final byte[] OUTPUT_MARKER = "...[output truncated]...".getBytes(StandardCharsets.UTF_8);
    /** 缺省输出上限 1 MiB。 */
    public static final int DEFAULT_OUTPUT_BYTES = 1024 * 1024;
    private static final String TOOL = "shell";

    private final SandboxOrchestrator orchestrator;
    private final OrchestrationRequest request;
    private final int outputBytes;

    /**
     * @param orchestrator 编排器
     * @param request      会话级的策略 / 上下文 / 审批配置（每次调用在它上面改 argv 相关的部分）
     * @param outputBytes  输出上限（字节）
     */
    public SandboxCommandRunner(SandboxOrchestrator orchestrator, OrchestrationRequest request, int outputBytes) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.request = Objects.requireNonNull(request, "request");
        if (outputBytes <= 0) throw new IllegalArgumentException("outputBytes must be > 0");
        this.outputBytes = outputBytes;
    }

    public SandboxCommandRunner(SandboxOrchestrator orchestrator, OrchestrationRequest request) {
        this(orchestrator, request, DEFAULT_OUTPUT_BYTES);
    }

    /**
     * 一步到位：本机选平台沙盒（{@link SandboxPlatform#select}，没有 ⇒ 按 {@code options.runUnsandboxedWhenUnavailable} 拒或裸跑），
     * 内层是 {@link LocalExecBackend}，审批者包一层 {@link AgentApprover}。
     */
    public static SandboxCommandRunner create(OrchestrationRequest request, Approver approver, SandboxPlatform.Options platform,
                                              OrchestratorOptions options) {
        Sandbox sandbox = SandboxPlatform.select(platform);
        SandboxOrchestrator o = new SandboxOrchestrator(new LocalExecBackend(), sandbox, new AgentApprover(approver, TOOL, null), options);
        return new SandboxCommandRunner(o, request);
    }

    public SandboxOrchestrator orchestrator() {
        return orchestrator;
    }

    public OrchestrationRequest request() {
        return request;
    }

    /** 头尾各留一半；超过上限 ⇒ 截断。 */
    static byte[][] capOutput(byte[] b, int cap) {
        int n = b.length;
        int markerLen = OUTPUT_MARKER.length;
        if (n <= cap) return new byte[][] {b, null};
        if (cap <= markerLen) return new byte[][] {java.util.Arrays.copyOf(b, cap), new byte[0]};
        int half = Math.max(0, (cap - markerLen) / 2);
        byte[] out = new byte[Math.min(cap, 2 * half + markerLen)];
        System.arraycopy(b, 0, out, 0, half);
        System.arraycopy(OUTPUT_MARKER, 0, out, half, markerLen);
        System.arraycopy(b, n - half, out, half + markerLen, half);
        return new byte[][] {out, new byte[0]};
    }

    /**
     * 跑一条命令。程序起不来（不存在等）照常抛 {@link IOException}；被中断 ⇒ 进程被杀并抛 {@link InterruptedException}。
     * 策略 / 后端层面的错误（如 deny glob 无法安全展开）抛 {@link io.ailink.agentforge.sandbox.SandboxException}。
     */
    public CommandResult run(CommandRequest cmd) throws IOException, InterruptedException {
        Objects.requireNonNull(cmd, "cmd");
        Long timeout = cmd.timeoutMs() != null ? cmd.timeoutMs() : request.timeoutMs();
        ExecRequest exec = new ExecRequest(cmd.argv(), cmd.cwd(), cmd.env(), true, false, false, timeout);
        OrchestrationRequest req = request;
        if (cmd.escalated()) req = req.withPermissions(Permissions.REQUIRE_ESCALATED);
        if (cmd.approvalKey() != null) req = req.withApprovalKey(cmd.approvalKey());
        else if (req.approvalKey() == null) req = req.withApprovalKey(AgentApprover.approvalKey(TOOL, cmd.argv()));
        long started = System.nanoTime();
        Outcome<ExecResult> outcome = orchestrator.runExec(req, exec);
        ExecResult r = outcome.output();
        byte[] bytes = (r == null ? "" : r.output()).getBytes(StandardCharsets.UTF_8);
        byte[][] capped = capOutput(bytes, outputBytes);
        boolean omitted = capped[1] != null;
        String sandbox = outcome.sandbox() != null && outcome.status() != OutcomeStatus.ESCALATED ? outcome.sandbox().name() : null;
        if (outcome.status() == OutcomeStatus.REJECTED || outcome.status() == OutcomeStatus.ABORTED || r == null) {
            Message reason = outcome.reason() != null ? outcome.reason() : new Message("sandbox/rejected", "The command was not run.");
            return new CommandResult(126, false, 0, capped[0], omitted, r == null ? "" : r.stdout(), r == null ? "" : r.stderr(), true, reason,
                    outcome.status(), sandbox);
        }
        return new CommandResult(r.exitCode(), r.timedOut(), (System.nanoTime() - started) / 1_000_000, capped[0], omitted, r.stdout(),
                r.stderr(), false, outcome.reason(), outcome.status(), sandbox);
    }
}
