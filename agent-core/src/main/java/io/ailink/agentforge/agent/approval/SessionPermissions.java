package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.sandbox.approval.ApprovalCache;
import io.ailink.agentforge.sandbox.execpolicy.ExecPolicyManager;
import io.ailink.agentforge.sandbox.execpolicy.Policy;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 一段会话（连同它派出的子 agent）的权限状态：当前设置、执行规则、本会话的「不再问」记录、以后都放行的工具。线程安全。
 * <ul>
 *   <li>{@link #settings()} 随 {@code /permissions} 等即时改，下一次工具调用生效；</li>
 *   <li>{@link #execPolicy()} 是这个项目根的规则（内建 + 用户 + 项目），worktree-enter 换根时宿主换掉它；</li>
 *   <li>「本会话不再问」只活在内存里（键：命令 / 文件 / 主机 / 工具），命令那份交给沙盒编排器的 {@link #execApprovals()}。</li>
 * </ul>
 */
public final class SessionPermissions {
    private volatile PermissionSettings settings;
    private volatile ExecPolicyManager execPolicy;
    private volatile boolean sandboxAvailable;
    private final Set<String> sessionApprovals = ConcurrentHashMap.newKeySet();
    private final ApprovalCache execApprovals = ApprovalCache.inMemory();
    private final Set<String> alwaysTools = ConcurrentHashMap.newKeySet();
    private volatile Consumer<String> onAlwaysTool;

    public SessionPermissions(PermissionSettings settings, ExecPolicyManager execPolicy, boolean sandboxAvailable) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.execPolicy = execPolicy != null ? execPolicy : ExecPolicyManager.of(Policy.empty());
        this.sandboxAvailable = sandboxAvailable;
    }

    /** 缺省设置、空规则、有沙盒（测试 / 嵌入用）。 */
    public SessionPermissions() {
        this(PermissionSettings.DEFAULT, null, true);
    }

    public PermissionSettings settings() { return settings; }

    public void setSettings(PermissionSettings s) { this.settings = Objects.requireNonNull(s); }

    public ExecPolicyManager execPolicy() { return execPolicy; }

    public void setExecPolicy(ExecPolicyManager m) { this.execPolicy = m != null ? m : ExecPolicyManager.of(Policy.empty()); }

    /** 本机有没有可用的平台沙盒（没有 ⇒ 受限策略下未知命令一律要问）。 */
    public boolean sandboxAvailable() { return sandboxAvailable; }

    public void setSandboxAvailable(boolean on) { this.sandboxAvailable = on; }

    /** 本会话已批过的键（命令之外：{@code file:…}、{@code host:…}、{@code tool:…}、{@code secret:…}）。 */
    public boolean approvedForSession(String key) { return sessionApprovals.contains(key); }

    public void approveForSession(String key) { sessionApprovals.add(key); }

    /** 沙盒编排器用的会话审批缓存（键是命令）。 */
    public ApprovalCache execApprovals() { return execApprovals; }

    /** 以后都免审批的工具（宿主从配置里装，{@link #rememberTool} 时回调落盘）。 */
    public boolean alwaysAllowed(String tool) { return alwaysTools.contains(tool); }

    public void loadAlwaysTools(List<String> tools) { if (tools != null) alwaysTools.addAll(tools); }

    public void rememberTool(String tool) {
        if (!alwaysTools.add(tool)) return;
        Consumer<String> f = onAlwaysTool;
        if (f != null) {
            try { f.accept(tool); } catch (RuntimeException ignored) { }   // 落盘失败不拦审批
        }
    }

    public void onAlwaysTool(Consumer<String> f) { this.onAlwaysTool = f; }
}
