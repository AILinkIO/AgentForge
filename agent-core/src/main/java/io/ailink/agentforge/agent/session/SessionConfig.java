package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.models.PromptCache;

/**
 * 会话的可调项（coxswain 那批 parameter：{@code agent-max-iterations} / {@code agent-compaction-enabled?} /
 * {@code agent-prompt-cache} / {@code agent-image-retention} / 重试常数）。每一轮开始时读一次。
 */
public final class SessionConfig {

    /** 图在历史里留多久：{@link #TURN} 只随那一轮发一次（默认）；{@link #PRUNE} 留到 prune 边界。 */
    public enum ImageRetention { TURN, PRUNE }

    private volatile Integer maxIterations;             // null = 不限（照 opencode；用满时补一问叫它收尾）
    private volatile boolean compaction = true;
    private volatile PromptCache promptCache = PromptCache.FIVE_MINUTES;
    private volatile ImageRetention imageRetention = ImageRetention.TURN;
    private volatile int retryMax = 3;
    private volatile long retryInitialDelayMs = 500;
    private volatile long retryCapMs = 10_000;
    private volatile boolean staging;
    private volatile boolean gatePreview = true;
    private volatile long toolsSettleMs = 3000;

    public static SessionConfig defaults() { return new SessionConfig(); }

    public SessionConfig copy() {
        SessionConfig c = new SessionConfig();
        c.maxIterations = maxIterations;
        c.compaction = compaction;
        c.promptCache = promptCache;
        c.imageRetention = imageRetention;
        c.retryMax = retryMax;
        c.retryInitialDelayMs = retryInitialDelayMs;
        c.retryCapMs = retryCapMs;
        c.staging = staging;
        c.gatePreview = gatePreview;
        c.toolsSettleMs = toolsSettleMs;
        return c;
    }

    public Integer maxIterations() { return maxIterations; }

    /** 主会话的轮数上限；null = 不限。⚠️ 非正数当 null（打错的值回到默认那一边）。 */
    public SessionConfig maxIterations(Integer n) { this.maxIterations = n == null || n <= 0 ? null : n; return this; }

    public boolean compaction() { return compaction; }

    /** 自动压缩（默认开）。手动 {@link AgentSession#compact()} 不受它影响。 */
    public SessionConfig compaction(boolean on) { this.compaction = on; return this; }

    public PromptCache promptCache() { return promptCache; }

    public SessionConfig promptCache(PromptCache p) { this.promptCache = p == null ? PromptCache.OFF : p; return this; }

    public ImageRetention imageRetention() { return imageRetention; }

    public SessionConfig imageRetention(ImageRetention r) { this.imageRetention = r == null ? ImageRetention.TURN : r; return this; }

    public int retryMax() { return retryMax; }

    public SessionConfig retryMax(int n) { this.retryMax = Math.max(0, n); return this; }

    public long retryInitialDelayMs() { return retryInitialDelayMs; }

    public SessionConfig retryInitialDelayMs(long ms) { this.retryInitialDelayMs = Math.max(0, ms); return this; }

    public long retryCapMs() { return retryCapMs; }

    /** 退避封顶（界面上转圈超过十秒会被读成死了）。 */
    public SessionConfig retryCapMs(long ms) { this.retryCapMs = Math.max(0, ms); return this; }

    public boolean staging() { return staging; }

    /** 写盘先攒着、整轮结束一次过目（批量审阅）。开着时本该逐次审批的可暂存写入改为攒着、整轮一次过目。 */
    public SessionConfig staging(boolean on) { this.staging = on; return this; }

    public boolean gatePreview() { return gatePreview; }

    /** 审批请求上给不给"批准之后会变成什么样"的预览（diff / 命令原文）。 */
    public SessionConfig gatePreview(boolean on) { this.gatePreview = on; return this; }

    public long toolsSettleMs() { return toolsSettleMs; }

    /** 第一轮最多等这么久让还在连的工具（MCP）到齐。 */
    public SessionConfig toolsSettleMs(long ms) { this.toolsSettleMs = Math.max(0, ms); return this; }
}
