package io.ailink.agentforge.sandbox.orchestrator;

/** 第一次尝试的沙盒取舍（Codex SandboxOverride）。 */
public enum FirstAttemptOverride {
    /** 照常。 */
    NO_OVERRIDE,
    /** 第一次就不带沙盒（规则明确放行或显式升级）。 */
    BYPASS_SANDBOX_FIRST_ATTEMPT,
    /** 有 deny-read：显式升级且已批准 ⇒ 宽写（根可写）但仍留在沙盒里。 */
    ESCALATED_SANDBOX_WITH_RESTRICTIONS
}
