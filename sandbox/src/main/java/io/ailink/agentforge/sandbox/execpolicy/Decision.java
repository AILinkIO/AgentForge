package io.ailink.agentforge.sandbox.execpolicy;

/** 规则的判决；声明顺序即严重程度（{@code forbidden > prompt > allow}，多条匹配取最严的）。 */
public enum Decision {
    /** 免审批直接跑。 */
    ALLOW,
    /** 要用户点头；审批策略是 never 时直接拒。 */
    PROMPT,
    /** 直接拒。 */
    FORBIDDEN;

    /** {@code allow / prompt / forbidden}。 */
    public static Decision parse(String raw) {
        return switch (raw == null ? "" : raw) {
            case "allow" -> ALLOW;
            case "prompt" -> PROMPT;
            case "forbidden" -> FORBIDDEN;
            default -> throw new ExecPolicyException("decision 只能是 allow / prompt / forbidden：" + raw);
        };
    }

    public String wire() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
