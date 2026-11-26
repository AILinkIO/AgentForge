package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.models.ModelRef;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.runtime.ModelBootstrap;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 搭 {@link AgentRuntime}（整个进程一份，与 TUI 一样：一个工作根、一个会话库、一组 MCP 连接）。 */
@Configuration
public class RuntimeConfig {
    private static final Logger log = LoggerFactory.getLogger(RuntimeConfig.class);

    /** 启动时要告诉用户的话（模型自动启用、配置问题…）：页面顶上的提示条照着画。 */
    public record StartupNotes(List<String> notes) { }

    @Bean(destroyMethod = "close")
    public AgentRuntime agentRuntime(WebProperties p) {
        Path ws = Path.of(p.root() == null || p.root().isBlank() ? System.getProperty("user.dir") : p.root()).toAbsolutePath().normalize();
        if (!Files.isDirectory(ws)) throw new IllegalStateException("工作根不是目录：" + ws);
        AgentRuntime.Builder b = AgentRuntime.builder().workspaceRoot(ws).host(Prompts.Host.WEB);
        applyPermissions(b, p);
        if (p.mcpEnabled()) {
            McpManager mcp = new McpManager();
            mcp.setClientInfo("agentforge-web", "0.2.0");
            b.mcp(mcp);
        }
        return b.build();
    }

    @Bean
    public StartupNotes startupNotes(AgentRuntime rt, WebProperties p) {
        List<String> notes = new ArrayList<>(ModelBootstrap.run(rt));
        notes.addAll(rt.configProblems());
        if (p.model() != null && !p.model().isBlank()) {
            try {
                ModelRef mr = ModelRef.parse(p.model());
                if (!rt.modelRefs().contains(mr.toString())) {
                    if (!rt.models().providerEnabled(mr.providerId()))
                        throw new IllegalArgumentException("provider \"" + mr.providerId() + "\" 没启用，选不了 " + mr);
                    rt.models().providerModel(mr.providerId(), mr.modelId(), mr.modelId(), null, null, List.of());
                }
                rt.selectModel(mr.toString());
            } catch (IllegalArgumentException e) {
                notes.add(e.getMessage());
            }
        }
        // 启动时给了工作根：把它登记成一个项目（已经有项目包含这个文件夹就不重复建）
        if (p.root() != null && !p.root().isBlank()) {
            try {
                var root = rt.workspaceRoot();
                if (rt.projects().byFolder(root).isEmpty()) {
                    String name = root.getFileName() == null ? root.toString() : root.getFileName().toString();
                    rt.projects().create(name, List.of(root.toString()), null, null, null);
                    notes.add("已把 " + root + " 登记成项目「" + name + "」");
                }
            } catch (RuntimeException e) {
                notes.add("登记项目失败：" + e.getMessage());
            }
        }
        for (String n : notes) log.info("{}", n);
        log.info("工作根 {}；权限 {}；沙盒 {}", rt.workspaceRoot(), rt.permissionSettings().describe(), rt.sandboxStatus());
        return new StartupNotes(List.copyOf(notes));
    }

    /** 同 {@code AgentCommand.permissionFlags}：预设定基调，单项再盖上；什么都不给就看 prefs。 */
    static void applyPermissions(AgentRuntime.Builder b, WebProperties p) {
        ApprovalPolicy ap = p.approval() == null ? null : ApprovalPolicy.parse(p.approval());
        SandboxMode sm = p.sandbox() == null ? null : SandboxMode.parse(p.sandbox());
        List<String> roots = p.addDirs() == null || p.addDirs().isEmpty() ? null
                : p.addDirs().stream().map(d -> Path.of(d).toAbsolutePath().normalize().toString()).toList();
        if (p.preset() != null) {
            PermissionSettings base = ApprovalPreset.parse(p.preset()).settings();
            if (ap != null) base = base.withApproval(ap);
            if (sm != null) base = base.withSandbox(sm);
            if (p.network() != null) base = base.withNetworkAccess(p.network());
            if (roots != null) base = base.withWritableRoots(roots);
            b.permissions(base);
        } else {
            b.approvalPolicy(ap).sandbox(sm).networkAccess(p.network()).writableRoots(roots);
        }
    }
}
