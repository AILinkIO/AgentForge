package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.policy.SandboxPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一段会话的权限设置：审批策略 × 沙盒策略（Codex 的 approval_policy + sandbox_mode + sandbox_workspace_write）。不可变。
 *
 * @param approval      审批策略
 * @param sandbox       沙盒三档
 * @param networkAccess workspace-write / read-only 下沙盒里能不能出网（danger-full-access 总是能）
 * @param writableRoots workspace-write 下工作根之外另加的可写根（绝对路径）
 */
public record PermissionSettings(ApprovalPolicy approval, SandboxMode sandbox, boolean networkAccess, List<String> writableRoots) {
    /** 宿主自己的元数据目录：项目根下的它（规则、配置）在沙盒里保持只读，即使还不存在（≙ Codex 保护 {@code .codex}）。 */
    public static final String HOST_METADATA_DIR = ".agentforge";

    /** 缺省 = {@link ApprovalPreset#AUTO}：on-request + workspace-write，断网。 */
    public static final PermissionSettings DEFAULT = ApprovalPreset.AUTO.settings();

    public PermissionSettings {
        Objects.requireNonNull(approval, "approval");
        Objects.requireNonNull(sandbox, "sandbox");
        writableRoots = writableRoots == null ? List.of() : List.copyOf(writableRoots);
    }

    public static PermissionSettings of(ApprovalPolicy approval, SandboxMode sandbox) {
        return new PermissionSettings(approval, sandbox, false, List.of());
    }

    public PermissionSettings withApproval(ApprovalPolicy a) {
        return new PermissionSettings(a, sandbox, networkAccess, writableRoots);
    }

    public PermissionSettings withSandbox(SandboxMode m) {
        return new PermissionSettings(approval, m, networkAccess, writableRoots);
    }

    public PermissionSettings withNetworkAccess(boolean on) {
        return new PermissionSettings(approval, sandbox, on, writableRoots);
    }

    public PermissionSettings withWritableRoots(List<String> roots) {
        return new PermissionSettings(approval, sandbox, networkAccess, roots);
    }

    /** 网络实际通不通（danger-full-access 总是通）。 */
    public boolean networkEnabled() {
        return sandbox == SandboxMode.DANGER_FULL_ACCESS || networkAccess;
    }

    /** 对应的预设；不是任何一个预设 ⇒ null。 */
    public ApprovalPreset preset() {
        for (ApprovalPreset p : ApprovalPreset.values())
            if (p.approval().equals(approval) && p.sandbox() == sandbox && !networkAccess && writableRoots.isEmpty()) return p;
        return null;
    }

    /** 落到具体工作根上的沙盒策略（子进程沙盒与进程内文件工具共用这一份）。 */
    public SandboxPolicy policy(Path root) {
        return switch (sandbox) {
            case DANGER_FULL_ACCESS -> SandboxPolicy.dangerFullAccess();
            case READ_ONLY -> SandboxPolicy.readOnly(networkAccess).withHostMetadataNames(List.of(HOST_METADATA_DIR));
            case WORKSPACE_WRITE -> {
                List<String> roots = new ArrayList<>();
                for (String r : writableRoots) {
                    Path p = Path.of(r);
                    roots.add((p.isAbsolute() ? p : root.resolve(p)).toAbsolutePath().normalize().toString());
                }
                yield SandboxPolicy.workspaceWrite(roots, new SandboxPolicy.WorkspaceWriteOptions(networkAccess, false, false))
                        .withHostMetadataNames(List.of(HOST_METADATA_DIR));
            }
        };
    }

    /** 一行给人看的描述，如 {@code on-request · workspace-write（断网）}。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(approval.wire()).append(" · ").append(sandbox.wire());
        if (sandbox != SandboxMode.DANGER_FULL_ACCESS) sb.append(networkAccess ? "（可联网）" : "（断网）");
        if (!writableRoots.isEmpty()) sb.append(" + 可写 ").append(String.join("、", writableRoots));
        ApprovalPreset p = preset();
        if (p != null) sb.append(" [").append(p.id()).append(']');
        return sb.toString();
    }
}
