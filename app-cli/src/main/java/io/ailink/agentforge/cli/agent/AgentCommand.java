package io.ailink.agentforge.cli.agent;

import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.models.EnvVars;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code agentforge agent}：基于 agent-core {@link AgentRuntime} 的交互式编码 agent。
 *
 * <p>⚠️ 不碰 Spring：入口在 {@code AgentForgeApplication.main} 里先认出 {@code agent} 直接跑这里 —— Spring AI 的自动配置要 key 才起得来，
 * 而 agent 用的是 agent-core 自己的模型管理（环境变量 key + 加密凭据库 + models.dev 目录），没 key 也得能进来看历史、选模型。
 */
@Command(name = "agent", mixinStandardHelpOptions = true, description = "交互式编码 agent（工具、审批、沙箱、会话存盘）")
public class AgentCommand implements Callable<Integer> {

    @Option(names = "--root", description = "工作根目录（默认当前目录）")
    Path root;

    @Option(names = "--model", description = "模型 ref，形如 anthropic/claude-sonnet-4-5（会记成默认）")
    String model;

    @Option(names = "--agent", description = "用哪个 agent（main / deep / …，默认 main）")
    String agent;

    @Option(names = {"-s", "--sandbox"}, description = "沙盒策略：read-only / workspace-write（默认）/ danger-full-access")
    String sandbox;

    @Option(names = {"-a", "--ask-for-approval"}, description = "审批策略：untrusted / on-failure / on-request（默认）/ never / granular[:sandbox,rules]")
    String approval;

    @Option(names = "--full-auto", description = "省事档：on-request + workspace-write（沙盒里自动干活，要越界才问）")
    boolean fullAuto;

    @Option(names = {"--dangerously-bypass-approvals-and-sandbox", "--yolo"},
            description = "不审批、不进沙盒（never + danger-full-access）。只在本身就隔离好的环境里用")
    boolean yolo;

    @Option(names = "--preset", description = "审批 × 沙盒预设：read-only / auto / full-access")
    String preset;

    @Option(names = "--add-dir", description = "workspace-write 下另加一个可写目录（可重复）")
    List<Path> addDirs;

    @Option(names = "--network", negatable = true, description = "沙盒里能不能出网（workspace-write / read-only 下；默认断网）")
    Boolean network;

    @Option(names = "--no-mcp", description = "不连 MCP 服务器")
    boolean noMcp;

    @Override
    public Integer call() throws Exception {
        Path ws = (root != null ? root : Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize();
        if (!Files.isDirectory(ws)) {
            System.err.println("工作根不是目录：" + ws);
            return 2;
        }
        AgentRuntime.Builder b = AgentRuntime.builder().workspaceRoot(ws);
        try {
            permissionFlags().applyTo(b);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return 2;
        }
        McpManager mcp = null;
        if (!noMcp) {
            mcp = new McpManager();
            mcp.setClientInfo("agentforge", "0.2.0");
        }
        b.mcp(mcp);
        try (AgentRuntime rt = b.build()) {
            List<String> notes = bootstrapModels(rt);
            notes.addAll(rt.configProblems());
            if (model != null) {
                try {
                    String note = AgentRepl.selectModel(rt, model);
                    if (note.contains("（")) notes.add(note);       // 只有"按自定义模型登记"值得单说，模型本身横幅里有
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                    return 2;
                }
            }
            return launch(rt, notes);
        }
    }

    /** 运行时搭好之后跑哪种界面：这里是行模式 REPL；{@link TuiCommand} 换成全屏工作台。 */
    int launch(AgentRuntime rt, List<String> notes) throws Exception {
        return new AgentRepl(rt, agent, notes).run();
    }

    /**
     * 命令行给的权限：{@code full} 非空 ⇒ 整套设置（--yolo / --full-auto / --preset 定基调，再叠单项）；否则只有单项（没给的看 prefs）。
     */
    record PermissionFlags(PermissionSettings full, ApprovalPolicy approval, SandboxMode sandbox, Boolean network, List<String> writableRoots) {
        void applyTo(AgentRuntime.Builder b) {
            if (full != null) b.permissions(full);
            else b.approvalPolicy(approval).sandbox(sandbox).networkAccess(network).writableRoots(writableRoots);
        }
    }

    /**
     * 权限相关的命令行开关（照 Codex：{@code --yolo} / {@code --full-auto} / {@code --preset} 定基调，
     * {@code -a} / {@code -s} / {@code --network} / {@code --add-dir} 再逐项盖上；什么都不给就看 prefs）。互相矛盾 ⇒ {@link IllegalArgumentException}。
     */
    PermissionFlags permissionFlags() {
        int bases = (yolo ? 1 : 0) + (fullAuto ? 1 : 0) + (preset != null ? 1 : 0);
        if (bases > 1) throw new IllegalArgumentException("--yolo、--full-auto、--preset 只能选一个");
        if (yolo && (approval != null || sandbox != null))
            throw new IllegalArgumentException("--dangerously-bypass-approvals-and-sandbox 不能再和 -a / -s 一起用");
        PermissionSettings base = yolo ? ApprovalPreset.FULL_ACCESS.settings()
                : fullAuto ? PermissionSettings.of(ApprovalPolicy.ON_REQUEST, SandboxMode.WORKSPACE_WRITE)
                : preset != null ? ApprovalPreset.parse(preset).settings() : null;
        ApprovalPolicy ap = approval == null ? null : ApprovalPolicy.parse(approval);
        SandboxMode sm = sandbox == null ? null : SandboxMode.parse(sandbox);
        List<String> roots = addDirs == null ? null : addDirs.stream().map(p -> p.toAbsolutePath().normalize().toString()).toList();
        if (base != null) {
            if (ap != null) base = base.withApproval(ap);
            if (sm != null) base = base.withSandbox(sm);
            if (network != null) base = base.withNetworkAccess(network);
            if (roots != null) base = base.withWritableRoots(roots);
        }
        return new PermissionFlags(base, ap, sm, network, roots);
    }

    /**
     * 模型池：注册目录里的模型；一家 provider 都没启用、而环境里有某家的 key 时替用户启用它（首次运行的便利，会记进 state）。
     * 回要在启动时说的话。
     */
    static List<String> bootstrapModels(AgentRuntime rt) {
        List<String> notes = new java.util.ArrayList<>();
        rt.refreshModels();
        Models m = rt.models();
        if (m.providerIds().isEmpty()) {
            List<String> adopted = new java.util.ArrayList<>();
            for (String id : Models.knownProviders()) {
                if (m.hasKey(id)) {
                    try {
                        m.selectProvider(id);
                        adopted.add(id);
                    } catch (RuntimeException e) {
                        notes.add("启用 " + id + " 失败：" + e.getMessage());
                    }
                }
            }
            if (!adopted.isEmpty()) {
                rt.refreshModels();
                notes.add("按环境变量里的 key 启用了 provider：" + String.join("、", adopted));
            }
        }
        if (EnvVars.get("AGENTFORGE_MODEL") != null) notes.add("模型取自 $AGENTFORGE_MODEL");
        return notes;
    }
}
