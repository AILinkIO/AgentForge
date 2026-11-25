package io.ailink.agentforge.agent.loop;

/**
 * 问模型失败。{@code status} 是 HTTP 状态（拿不到时 0），{@code retryAfterMs} 是服务端建议的
 * 等待（没有时 -1），{@code kind}：{@code http} / {@code io} / {@code timeout} / {@code context-overflow} / {@code other}。
 */
public class ModelCallException extends RuntimeException {
    private final int status;
    private final long retryAfterMs;
    private final String kind;

    public ModelCallException(String kind, int status, long retryAfterMs, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    public int status() { return status; }

    public long retryAfterMs() { return retryAfterMs; }

    public String kind() { return kind; }

    public boolean contextOverflow() { return "context-overflow".equals(kind); }
}
