package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.agent.loop.GateDecision;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.session.SessionConfig;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Deletion;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.agent.tools.Vfs;
import io.ailink.agentforge.agent.tools.file.PatchTool;
import io.ailink.agentforge.agent.tools.shell.ShellSettings;
import io.ailink.agentforge.agent.tools.web.WebSearch;
import io.ailink.agentforge.agent.transcript.Transcript;
import io.ailink.agentforge.sandbox.approval.ApprovalMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.approval.ApprovalRequirement;
import io.ailink.agentforge.sandbox.approval.PatchSafety;
import io.ailink.agentforge.sandbox.approval.SafetyCheck;
import io.ailink.agentforge.sandbox.execpolicy.Decision;
import io.ailink.agentforge.sandbox.execpolicy.ExecApprovals;
import io.ailink.agentforge.sandbox.orchestrator.Permissions;
import io.ailink.agentforge.sandbox.policy.PathContext;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每次工具调用进循环前的审批判定（会话的 gate 过滤器调它）。按工具的能力分流，各走 Codex 对应的那套规则：
 * <ul>
 *   <li><b>密钥文件</b>（任何工具，参数里出现 {@code .env}）：先问（never ⇒ 拒）—— AgentForge 自己的保护，Codex 没有；</li>
 *   <li><b>只读工具</b>：直接放；</li>
 *   <li><b>run-command</b>：执行器自带审批（{@link io.ailink.agentforge.agent.tools.shell.CommandRunner#approvesCommands()}，
 *       execpolicy → 沙盒里跑 → 拦下了再问）就放过去；执行器不审批（裸的本地执行器）时在这里按 execpolicy 判一次；</li>
 *   <li><b>写文件</b>（write-file / edit-file / replace-form / apply-patch / delete-path）：{@link PatchSafety}——可写范围内直接放，
 *       否则问 / 拒；开着批量审阅（staging）时要问的写先攒着、整轮结束一次过目；delete-path 的软链 / 越界目标按真实目标判并放确认格；</li>
 *   <li><b>出网</b>（web-fetch / web-search）：{@link NetworkAccess}；</li>
 *   <li><b>其他会改东西的工具</b>（MCP、worktree、execute）：照 Codex 的 MCP 审批——never 且全盘可写 ⇒ 放，never 其他 ⇒ 拒，否则问
 *       （「以后都放行」记进配置）。</li>
 * </ul>
 */
public final class ToolApprovals {
    private ToolApprovals() { }

    /** 会话那一侧：摆审批、报自动判定、报写进去的修订。 */
    public interface Host {
        /** 摆出请求并阻塞等答案（停下 / 按停 ⇒ ABORT）。 */
        ReviewDecision ask(ApprovalRequest request);

        /** 没问人就定了（规则 / 本会话批过 / 策略）：给界面一行提示。 */
        void auto(String toolName, String summary, boolean allowed, String why);

        /** 写进了一条修订（规则文件 / 配置）。 */
        void amended(String description);

        String sessionId();
    }

    static final Set<String> WRITE_TOOLS = Set.of("write-file", "edit-file", "replace-form", "apply-patch", "delete-path");
    static final Set<String> NETWORK_TOOLS = Set.of("web-fetch", "web-search");

    public static GateDecision decide(Host host, SessionPermissions perms, Workspace ws, SessionConfig cfg, Block.ToolUse call, AgentTool tool) {
        String name = call.name();
        ObjectNode input = call.input() == null ? AgentTool.JSON.createObjectNode() : call.input();
        String summary = Transcript.argsOneLine(input, 80);
        String full = Transcript.argsOneLine(input, 8000);

        GateDecision secret = secretGate(host, perms, call, name, input, summary, full);
        if (secret != null) return secret;
        if (!tool.sensitive()) return GateDecision.ALLOW;
        if (name.equals("run-command")) return execGate(host, perms, ws, call, input, summary);
        if (name.equals("worktree-enter")) return GateDecision.ALLOW;   // 只换会话的根，不改东西
        if (WRITE_TOOLS.contains(name)) return patchGate(host, perms, ws, cfg, call, tool, input, summary);
        if (NETWORK_TOOLS.contains(name)) return networkGate(host, perms, call, input, summary);
        return toolGate(host, perms, call, input, summary);
    }

    private static String str(ObjectNode in, String k) {
        JsonNode n = in.get(k);
        return n != null && n.isString() ? n.asString() : null;
    }

    /** 拒绝（带用户的话）/ 停下 的统一出口。 */
    private static GateDecision refused(ReviewDecision d) {
        if (d.kind() == ReviewDecision.Kind.ABORT) return GateDecision.STOP;
        return GateDecision.deny(d.feedback() == null ? null : "用户拒绝了这次调用，并说：" + d.feedback());
    }

    private static boolean promptsRejected(ApprovalPolicy ap) {
        return ap.mode() == ApprovalMode.NEVER || (ap.mode() == ApprovalMode.GRANULAR && !ap.allowsSandboxApproval());
    }

    // ── 密钥文件 ─────────────────────────────────────────────────────

    private static GateDecision secretGate(Host host, SessionPermissions perms, Block.ToolUse call, String name, ObjectNode input, String summary,
                                           String full) {
        if (!SecretFiles.mentions(full)) return null;
        String key = "secret:" + name + "\u0000" + full;
        if (perms.approvedForSession(key)) {
            host.auto(name, summary, true, "密钥文件：本会话批过");
            return null;
        }
        if (promptsRejected(perms.settings().approval()))
            return GateDecision.deny("这次调用会读到密钥文件（.env 一类，内容进了上下文就收不回来），审批策略 " + perms.settings().approval().wire()
                    + " 不允许询问，已拒绝。别读它；需要其中某个值就请用户告诉你。");
        ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.SECRET, host.sessionId(), call.id(), name, name + " " + summary, null, null,
                null, "参数里有密钥文件（.env 一类）：内容进了上下文就收不回来", null, null, null, input, null);
        ReviewDecision d = host.ask(req);
        if (!d.isApproved()) return refused(d);
        if (d.kind() == ReviewDecision.Kind.APPROVED_FOR_SESSION) perms.approveForSession(key);
        return null;   // 接着走这件工具自己的审批
    }

    // ── 命令 ─────────────────────────────────────────────────────────

    /** 执行器自带审批就放过去；否则按 execpolicy 在这里判一次（没有沙盒兜底，所以只认规则与安全命令）。 */
    private static GateDecision execGate(Host host, SessionPermissions perms, Workspace ws, Block.ToolUse call, ObjectNode input, String summary) {
        if (ShellSettings.of(ws).runner().approvesCommands()) return GateDecision.ALLOW;
        String command = str(input, "command");
        if (command == null) return GateDecision.ALLOW;
        PermissionSettings s = perms.settings();
        boolean escalated = "require_escalated".equals(str(input, "sandbox_permissions"));
        ExecApprovals.Result r = perms.execPolicy().evaluate(new ExecApprovals.Request(List.of("bash", "-lc", command), s.approval(),
                s.policy(ws.root()), escalated ? Permissions.REQUIRE_ESCALATED : Permissions.USE_DEFAULT, prefixRule(input), false));
        return switch (r.requirement()) {
            case ApprovalRequirement.Forbidden f -> GateDecision.deny(f.reason().text());
            case ApprovalRequirement.Skip sk -> GateDecision.ALLOW;
            case ApprovalRequirement.NeedsApproval n -> {
                String key = "exec:" + command;
                if (perms.approvedForSession(key)) {
                    host.auto("run-command", summary, true, "本会话批过这条命令");
                    yield GateDecision.ALLOW;
                }
                String reason = n.reason() != null ? n.reason().text() : str(input, "justification");
                Amendment am = r.proposedAmendment() == null ? null : new Amendment.PrefixRule(r.proposedAmendment());
                ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.EXEC, host.sessionId(), call.id(), "run-command", command,
                        List.of("bash", "-lc", command), ws.root().toString(), null, reason, null, null, am, input, null);
                ReviewDecision d = host.ask(req);
                if (!d.isApproved()) yield refused(d);
                if (d.kind() == ReviewDecision.Kind.APPROVED_FOR_SESSION) perms.approveForSession(key);
                if (d.kind() == ReviewDecision.Kind.APPROVED_ALWAYS && am instanceof Amendment.PrefixRule pr) amendPrefix(host, perms, pr.prefix());
                yield GateDecision.ALLOW;
            }
        };
    }

    /** 模型提的 {@code prefix_rule}（字符串数组）；没有 ⇒ null。 */
    public static List<String> prefixRule(ObjectNode input) {
        JsonNode n = input.get("prefix_rule");
        if (n == null || !n.isArray() || n.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode x : n) {
            if (!x.isString()) return null;
            out.add(x.asString());
        }
        return out;
    }

    /** 「以后以它开头的命令不再问」：写规则文件；写失败不拦这一次。 */
    public static void amendPrefix(Host host, SessionPermissions perms, List<String> prefix) {
        try {
            perms.execPolicy().appendAllowPrefixRule(prefix);
            Path f = perms.execPolicy().amendmentFile();
            host.amended("以后 " + new Amendment.PrefixRule(prefix).describe() + "免审批" + (f == null ? "（只在本进程）" : "（写进了 " + f + "）"));
        } catch (RuntimeException e) {
            host.amended("规则没写进去：" + e.getMessage());
        }
    }

    // ── 写文件 ───────────────────────────────────────────────────────

    /** 一次写操作的目标：要判的路径 + 删除确认（delete-path 才有）。 */
    record WriteTargets(List<String> paths, String deletePath, String deleteTarget, String detail) { }

    static WriteTargets targets(Workspace ws, String name, ObjectNode input) {
        if (name.equals("apply-patch")) {
            String text = str(input, "patchText");
            if (text == null) return null;
            Set<String> out = new LinkedHashSet<>();
            for (PatchTool.Hunk h : PatchTool.parse(text)) {
                out.add(Limit.resolveInRoot(ws, h.path()));
                if (h.move() != null) out.add(Limit.resolveInRoot(ws, h.move()));
            }
            return new WriteTargets(List.copyOf(out), null, null, null);
        }
        String path = str(input, "path");
        if (path == null) return null;
        if (name.equals("delete-path")) {
            Deletion.Risk risk = Deletion.risk(ws, path);
            if (risk == null) return new WriteTargets(List.of(Limit.resolveInRoot(ws, path)), null, null, null);
            String target = switch (risk.kind()) {
                case SYMLINK, UNRESOLVABLE -> risk.path();   // 只删链接本身
                case ESCAPE -> risk.real() != null ? risk.real() : risk.path();
            };
            String detail = risk.real() != null && !risk.real().equals(risk.path()) ? risk.path() + " → " + risk.real() : risk.path();
            String full = target.startsWith("/") || Path.of(target).isAbsolute() ? target : Deletion.fullPath(ws.root().toString(), target);
            return new WriteTargets(List.of(full), path, target, detail);
        }
        if (Vfs.isBufferAddress(path)) return new WriteTargets(List.of(), null, null, null);
        return new WriteTargets(List.of(Limit.resolveInRoot(ws, path)), null, null, null);
    }

    private static GateDecision patchGate(Host host, SessionPermissions perms, Workspace ws, SessionConfig cfg, Block.ToolUse call, AgentTool tool,
                                          ObjectNode input, String summary) {
        String name = call.name();
        WriteTargets t;
        try {
            t = targets(ws, name, input);
        } catch (ToolException | IllegalArgumentException e) {
            return GateDecision.ALLOW;   // 参数 / 路径本身不对：让工具自己报给模型
        }
        if (t == null || t.paths().isEmpty()) return GateDecision.ALLOW;
        PermissionSettings s = perms.settings();
        Path root = ws.root();
        SandboxPolicy pol = s.policy(root);
        boolean sessionOk = t.paths().stream().allMatch(p -> perms.approvedForSession(fileKey(p)));
        SafetyCheck check = sessionOk ? SafetyCheck.AUTO_APPROVE : PatchSafety.assess(t.paths(), s.approval(), pol, PathContext.forCwd(root.toString()));
        switch (check) {
            case SafetyCheck.AutoApprove a -> {
                if (sessionOk) host.auto(name, summary, true, "本会话批过这些文件");
                confirmDeletion(ws, t);
                return GateDecision.ALLOW;
            }
            case SafetyCheck.Reject r -> {
                return GateDecision.deny(r.reason().text() + "：" + String.join("、", t.paths()));
            }
            case SafetyCheck.AskUser u -> {
                if (cfg.staging() && tool.stageable()) return GateDecision.ALLOW;   // 攒着，整轮结束一次过目
                Preview.Result pv = null;
                if (cfg.gatePreview()) {
                    try { pv = Preview.toolChangePreview(ws, name, input); } catch (RuntimeException ignored) { }
                }
                String reason = s.approval().mode() == ApprovalMode.UNLESS_TRUSTED ? "审批策略 untrusted：改文件都要先过目"
                        : "不在可写范围内（" + s.sandbox().wire() + "；.git 等受保护目录只读）：" + String.join("、", t.paths());
                String title = t.detail() != null ? "删除 " + t.detail() : name + " " + summary;
                ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.PATCH, host.sessionId(), call.id(), name, title, null,
                        root.toString(), pv, reason, null, null, null, input, null);
                ReviewDecision d = host.ask(req);
                if (!d.isApproved()) {
                    Deletion.of(ws).clear();
                    return refused(d);
                }
                if (d.kind() == ReviewDecision.Kind.APPROVED_FOR_SESSION) for (String p : t.paths()) perms.approveForSession(fileKey(p));
                confirmDeletion(ws, t);
                return GateDecision.ALLOW;
            }
        }
    }

    private static void confirmDeletion(Workspace ws, WriteTargets t) {
        if (t.deletePath() != null) Deletion.confirm(ws, t.deletePath(), t.deleteTarget());
    }

    public static String fileKey(String path) {
        return "file:" + path;
    }

    // ── 出网 ─────────────────────────────────────────────────────────

    private static GateDecision networkGate(Host host, SessionPermissions perms, Block.ToolUse call, ObjectNode input, String summary) {
        String url = call.name().equals("web-search") ? WebSearch.currentEndpoint() : str(input, "url");
        String h = NetworkAccess.hostOf(url);
        return switch (NetworkAccess.decide(perms, h)) {
            case NetworkAccess.Verdict.Allow a -> GateDecision.ALLOW;
            case NetworkAccess.Verdict.Forbid f -> GateDecision.deny(f.reason());
            case NetworkAccess.Verdict.Ask q -> {
                ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.NETWORK, host.sessionId(), call.id(), call.name(),
                        call.name() + " " + summary, null, null, null, "沙盒网络关着；要访问 " + h, null, h, new Amendment.NetworkHost(h), input, null);
                ReviewDecision d = host.ask(req);
                switch (d.kind()) {
                    case APPROVED_FOR_SESSION -> perms.approveForSession(NetworkAccess.sessionKey(h));
                    case APPROVED_ALWAYS -> amendHost(host, perms, h, Decision.ALLOW);
                    case DENIED_ALWAYS -> amendHost(host, perms, h, Decision.FORBIDDEN);
                    default -> { }
                }
                yield d.isApproved() ? GateDecision.ALLOW : refused(d);
            }
        };
    }

    private static void amendHost(Host host, SessionPermissions perms, String h, Decision decision) {
        try {
            perms.execPolicy().appendNetworkRule(h, "https", decision, null);
            perms.execPolicy().appendNetworkRule(h, "http", decision, null);
            host.amended((decision == Decision.ALLOW ? "以后放行 " : "以后拦下 ") + h);
        } catch (RuntimeException e) {
            host.amended("网络规则没写进去：" + e.getMessage());
        }
    }

    // ── 其他工具 ─────────────────────────────────────────────────────

    private static GateDecision toolGate(Host host, SessionPermissions perms, Block.ToolUse call, ObjectNode input, String summary) {
        String name = call.name();
        String key = "tool:" + name;
        if (perms.alwaysAllowed(name) || perms.approvedForSession(key)) {
            host.auto(name, summary, true, perms.alwaysAllowed(name) ? "这件工具设成了以后都放行" : "本会话批过这件工具");
            return GateDecision.ALLOW;
        }
        PermissionSettings s = perms.settings();
        if (s.approval().mode() == ApprovalMode.NEVER) {
            if (s.policy(Path.of("/")).fullDiskWriteAccess()) return GateDecision.ALLOW;
            return GateDecision.deny("调用 " + name + " 需要审批，但审批策略是 never（从不问），已拒绝。");
        }
        ApprovalRequest req = new ApprovalRequest(null, ApprovalRequest.Kind.TOOL, host.sessionId(), call.id(), name, name + " " + summary, null, null,
                null, null, null, null, new Amendment.Tool(name), input, null);
        ReviewDecision d = host.ask(req);
        if (!d.isApproved()) return refused(d);
        if (d.kind() == ReviewDecision.Kind.APPROVED_FOR_SESSION) perms.approveForSession(key);
        if (d.kind() == ReviewDecision.Kind.APPROVED_ALWAYS) {
            perms.rememberTool(name);
            host.amended("以后 " + name + " 免审批");
        }
        return GateDecision.ALLOW;
    }
}
