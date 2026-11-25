package io.ailink.agentforge.agent.sandbox;

import java.util.Locale;

/**
 * 沙盒策略的三档（Codex SandboxPolicy 的名字）。
 * <ul>
 *   <li>{@link #READ_ONLY}：全盘只读；写文件、出网都要审批（或被拒）；</li>
 *   <li>{@link #WORKSPACE_WRITE}（默认）：全盘可读，工作根 + 临时目录 + 额外可写根可写（{@code .git} 等受保护名仍只读），网络按开关；</li>
 *   <li>{@link #DANGER_FULL_ACCESS}：不上沙盒，不限文件系统与网络（{@code off} 是它的别名）。</li>
 * </ul>
 */
public enum SandboxMode {
    READ_ONLY("read-only"),
    WORKSPACE_WRITE("workspace-write"),
    DANGER_FULL_ACCESS("danger-full-access");

    private final String wire;

    SandboxMode(String wire) { this.wire = wire; }

    /** 配置 / 命令行里写的名字。 */
    public String wire() { return wire; }

    /** 认 {@code read-only / workspace-write / danger-full-access / off}（大小写、下划线不拘）。 */
    public static SandboxMode parse(String s) {
        String k = s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (k) {
            case "workspace-write", "workspace" -> WORKSPACE_WRITE;
            case "read-only", "readonly" -> READ_ONLY;
            case "danger-full-access", "off", "none", "full" -> DANGER_FULL_ACCESS;
            default -> throw new IllegalArgumentException("沙盒策略只能是 read-only / workspace-write / danger-full-access（或 off）：" + s);
        };
    }
}
