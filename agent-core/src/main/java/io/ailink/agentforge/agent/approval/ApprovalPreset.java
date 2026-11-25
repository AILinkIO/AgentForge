package io.ailink.agentforge.agent.approval;

import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;

import java.util.Locale;

/** 审批 × 沙盒的内建预设（照 Codex utils/approval-presets）。 */
public enum ApprovalPreset {
    READ_ONLY("read-only", "只读", "能读工作区里的文件；改文件、出网都要先问。", ApprovalPolicy.ON_REQUEST, SandboxMode.READ_ONLY),
    AUTO("auto", "默认", "能读写工作区、在沙盒里跑命令；改工作区之外的东西、出网要先问。", ApprovalPolicy.ON_REQUEST, SandboxMode.WORKSPACE_WRITE),
    FULL_ACCESS("full-access", "完全放开", "不进沙盒，改哪儿、连哪儿都不问。务必小心。", ApprovalPolicy.NEVER, SandboxMode.DANGER_FULL_ACCESS);

    private final String id;
    private final String label;
    private final String description;
    private final ApprovalPolicy approval;
    private final SandboxMode sandbox;

    ApprovalPreset(String id, String label, String description, ApprovalPolicy approval, SandboxMode sandbox) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.approval = approval;
        this.sandbox = sandbox;
    }

    public String id() { return id; }

    public String label() { return label; }

    public String description() { return description; }

    public ApprovalPolicy approval() { return approval; }

    public SandboxMode sandbox() { return sandbox; }

    public PermissionSettings settings() { return PermissionSettings.of(approval, sandbox); }

    /** 认 id（大小写不拘）。认不出 ⇒ {@link IllegalArgumentException}。 */
    public static ApprovalPreset parse(String s) {
        String k = s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ApprovalPreset p : values()) if (p.id.equals(k)) return p;
        throw new IllegalArgumentException("预设只能是 read-only / auto / full-access：" + s);
    }
}
