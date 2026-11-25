package io.ailink.agentforge.sandbox.orchestrator;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.approval.ApprovalDecision;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequest;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.approval.Approver;
import io.ailink.agentforge.sandbox.exec.Exec;
import io.ailink.agentforge.sandbox.exec.ExecBackend;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.exec.ExecResult;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * 沙盒编排（移植自 Codex ToolOrchestrator::run，Apache-2.0）：审批要求 → 选沙盒 → 第一次尝试 → 拒判 → 工具许升级且审批模式许 ⇒
 * 带重跑理由再审批（本次已批过 / 会话缓存里有 ⇒ 免问）→ 不带沙盒重跑。
 * 有 deny-read 规则 ⇒ 永不裸跑（只有沙盒能兑现它；没有可用沙盒就拒）。阻塞调用；取消 = 线程中断。线程安全（不可变，状态在缓存里）。
 */
public final class SandboxOrchestrator {
    /** 用尝试的后端执行，回输出。 */
    @FunctionalInterface
    public interface AttemptRunner<T> {
        T run(Attempt attempt) throws IOException, InterruptedException;
    }

    private final ExecBackend inner;
    private final Sandbox sandbox;
    private final Approver approver;
    private final OrchestratorOptions options;

    /**
     * @param inner    不带沙盒的后端（沙盒包装的也是它）
     * @param sandbox  平台沙盒；{@code null} ⇒ 本机没有
     * @param approver 审批者
     * @param options  选项（可空 ⇒ 缺省）
     */
    public SandboxOrchestrator(ExecBackend inner, Sandbox sandbox, Approver approver, OrchestratorOptions options) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.approver = Objects.requireNonNull(approver, "approver");
        this.sandbox = sandbox;
        this.options = options == null ? OrchestratorOptions.DEFAULT : options;
    }

    public SandboxOrchestrator(ExecBackend inner, Sandbox sandbox, Approver approver) {
        this(inner, sandbox, approver, null);
    }

    public Sandbox sandbox() {
        return sandbox;
    }

    public OrchestratorOptions options() {
        return options;
    }

    /**
     * 第一次尝试的沙盒取舍（Codex sandbox_override_for_first_attempt）：有 deny-read ⇒ 只有「显式升级且已批准」才宽写，仍留在沙盒；
     * 规则明确放行（skip + bypass）或显式升级 ⇒ 第一次就不带沙盒。
     */
    public static FirstAttemptOverride firstAttemptOverride(Permissions permissions, ApprovalRequirement requirement, SandboxPolicy policy,
                                                            boolean alreadyApproved) {
        if (!policy.unsandboxedExecutionAllowed())
            return permissions == Permissions.REQUIRE_ESCALATED && alreadyApproved
                    ? FirstAttemptOverride.ESCALATED_SANDBOX_WITH_RESTRICTIONS : FirstAttemptOverride.NO_OVERRIDE;
        if (requirement instanceof ApprovalRequirement.Skip s && s.bypassSandbox()) return FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT;
        if (permissions == Permissions.REQUIRE_ESCALATED) return FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT;
        return FirstAttemptOverride.NO_OVERRIDE;
    }

    /** 要不要沙盒（Codex should_sandbox）。 */
    public static boolean shouldSandbox(SandboxPolicy policy, SandboxPreference preference, boolean managedNetwork) {
        return switch (preference) {
            case FORBID -> false;
            case REQUIRE -> true;
            case AUTO -> policy.requiresPlatformSandbox(managedNetwork);
        };
    }

    private ApprovalDecision ask(ApprovalRequest request) {
        ApprovalCache cache = options.approvalCache();
        String k = request.key();
        if (k != null && cache != null && cache.isApproved(k)) return ApprovalDecision.APPROVE;
        ApprovalDecision d = approver.decide(request);
        if (d == null) d = ApprovalDecision.DENY;
        if (d == ApprovalDecision.APPROVE_FOR_SESSION && k != null && cache != null) cache.approve(k);
        return d;
    }

    private Attempt makeAttempt(SandboxCapabilities caps, SandboxPolicy pol, PathContext ctx, FirstAttemptOverride override, boolean retry) {
        if (caps == null || sandbox == null) return new Attempt(inner, null, pol, override, retry);
        ExecBackend backend = sandbox.wrap(inner, pol, ctx);
        if (options.environmentMarkers() != null) {
            var markers = options.environmentMarkers();
            backend = Exec.rewriting(backend, backend.capabilities(), req -> Exec.Rewrite.of(markers.apply(req, caps, pol.network())));
        }
        return new Attempt(backend, caps, pol, override, retry);
    }

    private static String describe(SandboxCapabilities probe) {
        if (probe == null) return Message.of("sandbox/no-backend").text();
        if (probe.unavailableReason() != null) return probe.unavailableReason().text();
        return probe.name();
    }

    /**
     * 编排一次调用。{@code runAttempt} 用尝试的后端执行；{@code observe} 从输出取拒判要看的执行结果。
     */
    public <T> Outcome<T> run(OrchestrationRequest request, AttemptRunner<T> runAttempt, Function<T, ExecResult> observe)
            throws IOException, InterruptedException {
        SandboxPolicy pol = request.policy();
        PathContext ctx = request.context();
        ApprovalPolicy ap = request.approvalPolicy();
        String key = request.approvalKey();
        ApprovalRequirement requirement = request.requirement() != null ? request.requirement() : ap.defaultRequirement(pol);
        if (requirement instanceof ApprovalRequirement.Forbidden f) return new Outcome<>(OutcomeStatus.REJECTED, null, 0, f.reason(), null);
        Message approvalReason = requirement instanceof ApprovalRequirement.NeedsApproval n ? n.reason() : null;
        ApprovalDecision firstDecision = requirement instanceof ApprovalRequirement.NeedsApproval
                ? ask(new ApprovalRequest(key, approvalReason, null, null)) : null;
        if (firstDecision == ApprovalDecision.ABORT) return new Outcome<>(OutcomeStatus.ABORTED, null, 0, null, null);
        if (firstDecision == ApprovalDecision.DENY)
            return new Outcome<>(OutcomeStatus.REJECTED, null, 0, Message.of("sandbox/approval-denied"), null);

        boolean alreadyApproved = firstDecision != null;
        boolean unsandboxedOk = pol.unsandboxedExecutionAllowed();
        FirstAttemptOverride override = firstAttemptOverride(request.permissions(), requirement, pol, alreadyApproved);
        SandboxPolicy attemptPol = override == FirstAttemptOverride.ESCALATED_SANDBOX_WITH_RESTRICTIONS ? pol.forApprovedCommand() : pol;
        boolean requested = override != FirstAttemptOverride.BYPASS_SANDBOX_FIRST_ATTEMPT
                && shouldSandbox(attemptPol, request.preference(), false);
        SandboxCapabilities probe = (requested || !unsandboxedOk) && sandbox != null ? sandbox.probe() : null;
        SandboxCapabilities caps = probe != null && probe.available() ? probe : null;

        if ((requested || !unsandboxedOk) && caps == null && !unsandboxedOk)
            return new Outcome<>(OutcomeStatus.REJECTED, null, 0, Message.of("sandbox/unavailable-deny-read", describe(probe)), probe);
        if (requested && caps == null && !options.runUnsandboxedWhenUnavailable())
            return new Outcome<>(OutcomeStatus.REJECTED, null, 0, Message.of("sandbox/unavailable", describe(probe)), probe);

        T first = runAttempt.run(makeAttempt(caps, attemptPol, ctx, override, false));
        ExecResult observed = observe.apply(first);
        if (caps == null || !options.classifier().isDenied(caps, observed))
            return new Outcome<>(OutcomeStatus.COMPLETED, first, 1, null, caps);
        if (!request.escalateOnFailure() || !ap.wantsNoSandboxApproval() || !unsandboxedOk)
            return new Outcome<>(OutcomeStatus.DENIED, first, 1, Message.of("sandbox/denied", caps.name()), caps);
        ApprovalDecision retry = ap.shouldBypassApproval(alreadyApproved) ? null
                : ask(new ApprovalRequest(key, approvalReason, Message.of("sandbox/retry-without-sandbox"), observed));
        if (retry == ApprovalDecision.ABORT) return new Outcome<>(OutcomeStatus.ABORTED, first, 1, null, caps);
        if (retry == ApprovalDecision.DENY)
            return new Outcome<>(OutcomeStatus.REJECTED, first, 1, Message.of("sandbox/approval-denied"), caps);
        T second = runAttempt.run(makeAttempt(null, attemptPol, ctx, override, true));
        return new Outcome<>(OutcomeStatus.ESCALATED, second, 2, null, null);
    }

    /** {@link #run} 的输出就是 {@link ExecResult} 时。 */
    public Outcome<ExecResult> run(OrchestrationRequest request, AttemptRunner<ExecResult> runAttempt) throws IOException, InterruptedException {
        return run(request, runAttempt, Function.identity());
    }

    /**
     * 便捷：起一个进程（{@link Exec#run}）并编排。请求顶层的 {@code timeoutMs} 是缺省值；执行请求已经给了时限时保持更具体的那个。
     */
    public Outcome<ExecResult> runExec(OrchestrationRequest request, ExecRequest execRequest) throws IOException, InterruptedException {
        Objects.requireNonNull(execRequest, "execRequest");
        ExecRequest er = request.timeoutMs() != null && execRequest.timeoutMs() == null ? execRequest.withTimeoutMs(request.timeoutMs()) : execRequest;
        return run(request, attempt -> Exec.run(attempt.backend(), er));
    }
}
