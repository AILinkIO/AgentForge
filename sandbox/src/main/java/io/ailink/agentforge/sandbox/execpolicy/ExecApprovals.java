package io.ailink.agentforge.sandbox.execpolicy;

import io.ailink.agentforge.sandbox.Message;
import io.ailink.agentforge.sandbox.approval.ApprovalMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.command.BashCommands;
import io.ailink.agentforge.sandbox.command.CommandSafety;
import io.ailink.agentforge.sandbox.orchestrator.Permissions;
import io.ailink.agentforge.sandbox.policy.FileSystemMode;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 一条命令要不要审批（移植自 Codex core/src/exec_policy.rs 的 create_exec_approval_requirement_for_command，Apache-2.0）：
 * <ol>
 * <li>{@code bash -lc "…"} 能拆成纯命令就逐条求值，否则整条当一条；</li>
 * <li>每条先看规则；没有规则命中按启发式判（{@link #renderDecisionForUnmatched}）；多条取最严；</li>
 * <li>{@code forbidden} ⇒ 禁止（带理由）；{@code prompt} ⇒ 要审批（审批策略不许问 ⇒ 禁止）；{@code allow} ⇒ 免审批，
 *     <b>每一条都被规则明确放行</b>时连沙盒都不上（bypass）；</li>
 * <li>顺带给出「以后这类不再问」的规则修订建议（模型提的 {@code prefix_rule} 合格就用它，否则取第一条启发式命中的命令）。</li>
 * </ol>
 */
public final class ExecApprovals {
    private ExecApprovals() {
    }

    /**
     * 求值输入。
     *
     * @param command            要跑的 argv（shell 命令写成 {@code ["bash", "-lc", 脚本]}）
     * @param approvalPolicy     审批策略
     * @param sandboxPolicy      沙盒策略
     * @param sandboxPermissions 模型要没要出沙盒
     * @param prefixRule         模型提议的可复用前缀（可空）
     * @param sandboxAvailable   本机有没有可用的平台沙盒（受限策略却没有沙盒 ⇒ 未知命令一律要问）
     */
    public record Request(List<String> command, ApprovalPolicy approvalPolicy, SandboxPolicy sandboxPolicy, Permissions sandboxPermissions,
                          List<String> prefixRule, boolean sandboxAvailable) {
        public Request {
            command = List.copyOf(command);
            Objects.requireNonNull(approvalPolicy, "approvalPolicy");
            Objects.requireNonNull(sandboxPolicy, "sandboxPolicy");
            if (sandboxPermissions == null) sandboxPermissions = Permissions.USE_DEFAULT;
            prefixRule = prefixRule == null ? null : List.copyOf(prefixRule);
        }
    }

    /**
     * 求值结果。
     *
     * @param requirement       审批要求（交给编排器）
     * @param proposedAmendment 「以后以它开头的命令不再问」的建议前缀（可空）
     * @param commands          参与求值的命令（拆过的）
     */
    public record Result(ApprovalRequirement requirement, List<String> proposedAmendment, List<List<String>> commands) {
    }

    /** 不许作为「以后都放行」建议的前缀：解释器 / shell / 万能入口（Codex BANNED_PREFIX_SUGGESTIONS）。 */
    public static final List<List<String>> BANNED_PREFIX_SUGGESTIONS = List.of(
            List.of("/bin/bash"), List.of("/bin/bash", "-c"), List.of("/bin/bash", "-lc"), List.of("/bin/sh"), List.of("/bin/sh", "-c"),
            List.of("/bin/sh", "-lc"), List.of("/bin/zsh"), List.of("/bin/zsh", "-c"), List.of("/bin/zsh", "-lc"), List.of("Rscript"),
            List.of("bash"), List.of("bash", "-c"), List.of("bash", "-lc"), List.of("bun"), List.of("bun", "-e"), List.of("bun", "run"),
            List.of("cmd"), List.of("cmd", "/c"), List.of("cmd", "/k"), List.of("cmd.exe"), List.of("cmd.exe", "/c"), List.of("cmd.exe", "/k"),
            List.of("dash"), List.of("dash", "-c"), List.of("deno"), List.of("deno", "eval"), List.of("env"), List.of("fish"), List.of("fish", "-c"),
            List.of("git"), List.of("julia"), List.of("julia", "-e"), List.of("ksh"), List.of("ksh", "-c"), List.of("lua"), List.of("lua", "-e"),
            List.of("node"), List.of("node", "-e"), List.of("nodejs"), List.of("nodejs", "-e"), List.of("npm", "run"), List.of("osascript"),
            List.of("perl"), List.of("perl", "-e"), List.of("php"), List.of("php", "-r"), List.of("pnpm", "run"), List.of("powershell"),
            List.of("powershell", "-Command"), List.of("powershell", "-EncodedCommand"), List.of("powershell", "-File"), List.of("powershell", "-c"),
            List.of("powershell.exe"), List.of("powershell.exe", "-Command"), List.of("powershell.exe", "-EncodedCommand"),
            List.of("powershell.exe", "-File"), List.of("powershell.exe", "-c"), List.of("pwsh"), List.of("pwsh", "-Command"),
            List.of("pwsh", "-EncodedCommand"), List.of("pwsh", "-File"), List.of("pwsh", "-c"), List.of("pwsh", "-e"), List.of("pwsh", "-ec"),
            List.of("pwsh", "-f"), List.of("py"), List.of("py", "-3"), List.of("pypy"), List.of("pypy3"), List.of("python"), List.of("python", "-"),
            List.of("python", "-c"), List.of("python3"), List.of("python3", "-"), List.of("python3", "-c"), List.of("pythonw"), List.of("pyw"),
            List.of("rm"), List.of("ruby"), List.of("ruby", "-e"), List.of("sh"), List.of("sh", "-c"), List.of("sh", "-lc"), List.of("sudo"),
            List.of("yarn", "run"), List.of("zsh"), List.of("zsh", "-c"), List.of("zsh", "-lc"));

    /** {@code bash -lc "…"} 拆得开 ⇒ 拆开的纯命令；否则原样一条（Codex commands_for_exec_policy）。 */
    public static List<List<String>> commandsForExecPolicy(List<String> command) {
        List<List<String>> cmds = BashCommands.parseShellLcPlainCommands(command);
        return cmds != null && !cmds.isEmpty() ? cmds : List.of(List.copyOf(command));
    }

    /** 求值（见类注释）。 */
    public static Result evaluate(Policy policy, Request req) {
        List<List<String>> commands = commandsForExecPolicy(req.command());
        Function<List<String>, Decision> fallback = cmd -> renderDecisionForUnmatched(cmd, req);
        Evaluation ev = policy.checkMultiple(commands, fallback, true);
        List<String> requested = requestedAmendmentFromPrefixRule(req.prefixRule(), ev.matchedRules(), policy, commands, fallback);
        return switch (ev.decision()) {
            case FORBIDDEN -> new Result(ApprovalRequirement.forbidden(forbiddenReason(req.command(), ev)), null, commands);
            case PROMPT -> {
                boolean promptIsRule = ev.matchedRules().stream().anyMatch(m -> m.isPolicyMatch() && m.decision() == Decision.PROMPT);
                Message rejected = req.approvalPolicy().promptRejection(promptIsRule);
                if (rejected != null) {
                    Message reason = promptIsRule ? rejected : rejectedPromptReason(rejected, dangerousForHeuristics(ev, Decision.PROMPT));
                    yield new Result(ApprovalRequirement.forbidden(reason), null, commands);
                }
                List<String> amendment = requested != null ? requested : amendmentForPromptRules(ev.matchedRules());
                yield new Result(ApprovalRequirement.needsApproval(promptReason(req.command(), ev)), amendment, commands);
            }
            case ALLOW -> {
                boolean bypass = true;
                for (List<String> c : commands) {
                    boolean explicitlyAllowed = policy.matchesForCommand(c, null, true).stream()
                            .anyMatch(m -> m.isPolicyMatch() && m.decision() == Decision.ALLOW);
                    if (!explicitlyAllowed) {
                        bypass = false;
                        break;
                    }
                }
                yield new Result(ApprovalRequirement.skip(bypass), amendmentForAllowRules(ev.matchedRules()), commands);
            }
        };
    }

    /**
     * 没有规则命中时的判决（Codex render_decision_for_unmatched_command；外加上游已退役的「已知安全」白名单与 on-failure）：
     * <ul>
     * <li>已知安全且（untrusted 或没有沙盒兜底）⇒ allow；</li>
     * <li>危险命令，或受限策略却没有沙盒后端 ⇒ prompt（never ⇒ forbidden）；</li>
     * <li>never / on-failure ⇒ allow（靠沙盒兜底）；untrusted ⇒ prompt；</li>
     * <li>on-request / granular / on-failure：文件系统不受限 ⇒ allow；受限时模型显式要出沙盒才 prompt。</li>
     * </ul>
     */
    public static Decision renderDecisionForUnmatched(List<String> command, Request req) {
        ApprovalMode mode = req.approvalPolicy().mode();
        SandboxPolicy pol = req.sandboxPolicy();
        boolean noBackend = pol.fileSystem() == FileSystemMode.RESTRICTED && !pol.fullDiskWriteAccess() && !req.sandboxAvailable();
        if (CommandSafety.isKnownSafe(command) && (mode == ApprovalMode.UNLESS_TRUSTED || noBackend)) return Decision.ALLOW;
        if (CommandSafety.isDangerous(command) || noBackend) return mode == ApprovalMode.NEVER ? Decision.FORBIDDEN : Decision.PROMPT;
        boolean escalated = req.sandboxPermissions() == Permissions.REQUIRE_ESCALATED;
        return switch (mode) {
            case NEVER -> Decision.ALLOW;
            case UNLESS_TRUSTED -> Decision.PROMPT;
            // 先跑后问；但模型显式要出沙盒不能悄悄放（旧版 Codex 在 shell 处理器里直接拒，这里改成问）
            case ON_FAILURE, ON_REQUEST, GRANULAR -> pol.fileSystem() != FileSystemMode.RESTRICTED ? Decision.ALLOW
                    : escalated ? Decision.PROMPT : Decision.ALLOW;
        };
    }

    // ── 修订建议 ─────────────────────────────────────────────────────

    /** 有 prompt 规则命中 ⇒ 不建议（加了 allow 也盖不住它）；否则第一条启发式 prompt 的命令。 */
    static List<String> amendmentForPromptRules(List<RuleMatch> matches) {
        if (matches.stream().anyMatch(m -> m.isPolicyMatch() && m.decision() == Decision.PROMPT)) return null;
        for (RuleMatch m : matches)
            if (m instanceof RuleMatch.HeuristicsRuleMatch h && h.decision() == Decision.PROMPT) return h.command();
        return null;
    }


    /** 只用于「沙盒里失败、问要不要出沙盒重跑」时：任何规则命中 ⇒ 不建议；否则第一条启发式 allow 的命令。 */
    static List<String> amendmentForAllowRules(List<RuleMatch> matches) {
        if (matches.stream().anyMatch(RuleMatch::isPolicyMatch)) return null;
        for (RuleMatch m : matches)
            if (m instanceof RuleMatch.HeuristicsRuleMatch h && h.decision() == Decision.ALLOW) return h.command();
        return null;
    }

    /** 模型提的前缀：非空、不在黑名单、没有规则已命中、并且加上它之后每条命令都会被放行，才采用。 */
    static List<String> requestedAmendmentFromPrefixRule(List<String> prefixRule, List<RuleMatch> matches, Policy policy,
                                                         List<List<String>> commands, Function<List<String>, Decision> fallback) {
        if (prefixRule == null || prefixRule.isEmpty()) return null;
        if (BANNED_PREFIX_SUGGESTIONS.contains(prefixRule)) return null;
        if (matches.stream().anyMatch(RuleMatch::isPolicyMatch)) return null;
        Policy with;
        try {
            with = policy.withPrefixRule(prefixRule, Decision.ALLOW);
        } catch (ExecPolicyException e) {
            return null;
        }
        for (List<String> c : commands) if (with.check(c, fallback, true).decision() != Decision.ALLOW) return null;
        return List.copyOf(prefixRule);
    }

    // ── 理由 ─────────────────────────────────────────────────────────

    static String render(List<String> args) {
        return Shlex.join(args);
    }

    /** 只有规则驱动的 prompt 才给理由（取最具体的那条）。 */
    static Message promptReason(List<String> command, Evaluation ev) {
        RuleMatch.PrefixRuleMatch best = null;
        for (RuleMatch m : ev.matchedRules())
            if (m instanceof RuleMatch.PrefixRuleMatch p && p.decision() == Decision.PROMPT
                    && (best == null || p.matchedPrefix().size() > best.matchedPrefix().size())) best = p;
        if (best == null) return null;
        return best.justification() != null ? Message.of("sandbox/execpolicy-prompt-justified", render(command), best.justification())
                : Message.of("sandbox/execpolicy-prompt-policy", render(command));
    }

    static Message forbiddenReason(List<String> command, Evaluation ev) {
        RuleMatch.PrefixRuleMatch best = null;
        for (RuleMatch m : ev.matchedRules())
            if (m instanceof RuleMatch.PrefixRuleMatch p && p.decision() == Decision.FORBIDDEN
                    && (best == null || p.matchedPrefix().size() > best.matchedPrefix().size())) best = p;
        String cmd = render(command);
        if (best != null) {
            return best.justification() != null ? Message.of("sandbox/execpolicy-forbidden-justified", cmd, best.justification())
                    : Message.of("sandbox/execpolicy-forbidden-prefix", cmd, render(best.matchedPrefix()));
        }
        CommandSafety.DangerousMatch d = dangerousForHeuristics(ev, Decision.FORBIDDEN);
        if (d == CommandSafety.DangerousMatch.FORCED_RM)
            return Message.of("sandbox/execpolicy-forbidden-justified", cmd, Message.of("sandbox/execpolicy-forced-rm").text());
        return Message.of("sandbox/execpolicy-forbidden-blocked", cmd);
    }

    static Message rejectedPromptReason(Message fallback, CommandSafety.DangerousMatch d) {
        return d == CommandSafety.DangerousMatch.FORCED_RM ? Message.of("sandbox/execpolicy-forced-rm") : fallback;
    }

    private static CommandSafety.DangerousMatch dangerousForHeuristics(Evaluation ev, Decision decision) {
        for (RuleMatch m : ev.matchedRules()) {
            if (m instanceof RuleMatch.HeuristicsRuleMatch h && h.decision() == decision) {
                CommandSafety.DangerousMatch d = CommandSafety.dangerousMatch(h.command());
                if (d != null) return d;
            }
        }
        return null;
    }
}
