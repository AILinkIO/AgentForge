package io.ailink.agentforge.sandbox.approval;

import java.util.Locale;

/**
 * 审批模式（Codex AskForApproval；配置里写 {@link #wire()} 那个名字）。
 * <ul>
 * <li>{@link #UNLESS_TRUSTED}（untrusted）：只有「已知安全的只读命令」与规则明确放行的命令免问，其余都问；沙盒拒了可问「不带沙盒重跑」；</li>
 * <li>{@link #ON_FAILURE}（on-failure）：一律先在沙盒里跑、不事先问；沙盒拒了再问「不带沙盒重跑」（Codex 旧版语义，上游已并入 on-request）；</li>
 * <li>{@link #ON_REQUEST}（on-request，缺省）：受限文件系统下由模型显式申请出沙盒时才问；沙盒拒了不再问；</li>
 * <li>{@link #GRANULAR}：同 on-request，但「沙盒审批」「规则审批」两类提示可分别关掉（关掉 = 自动拒）；</li>
 * <li>{@link #NEVER}（never）：从不问；要问的一律按拒处理。</li>
 * </ul>
 */
public enum ApprovalMode {
    UNLESS_TRUSTED("untrusted"),
    ON_FAILURE("on-failure"),
    ON_REQUEST("on-request"),
    GRANULAR("granular"),
    NEVER("never");

    private final String wire;

    ApprovalMode(String wire) {
        this.wire = wire;
    }

    /** 配置 / 命令行里的名字。 */
    public String wire() {
        return wire;
    }

    /** 认 wire 名（大小写、下划线不拘；{@code unless-trusted} 是 untrusted 的别名）。认不出 ⇒ {@link IllegalArgumentException}。 */
    public static ApprovalMode parse(String s) {
        String k = s == null ? "" : s.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (k) {
            case "untrusted", "unless-trusted" -> UNLESS_TRUSTED;
            case "on-failure" -> ON_FAILURE;
            case "on-request" -> ON_REQUEST;
            case "granular" -> GRANULAR;
            case "never" -> NEVER;
            default -> throw new IllegalArgumentException("审批策略只能是 untrusted / on-failure / on-request / granular / never：" + s);
        };
    }
}
