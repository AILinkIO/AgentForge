package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.loop.ChatRequest;
import io.ailink.agentforge.agent.loop.DeltaListener;
import io.ailink.agentforge.agent.loop.ModelCallException;
import io.ailink.agentforge.agent.loop.ModelCaller;
import io.ailink.agentforge.agent.loop.ModelTurn;
import io.ailink.agentforge.agent.model.CancelToken;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 问模型失败时的自动重试（coxswain {@code session/retry.ss}）。重试策略只在这一处。
 *
 * <table>
 *   <caption>重不重试</caption>
 *   <tr><td>建连 / IO 断开（kind io）</td><td>✅</td><td>那一刻多半一个字节都没发出去</td></tr>
 *   <tr><td>5xx（含 529）</td><td>✅</td><td>状态码在流开始之前就判得出来</td></tr>
 *   <tr><td>429</td><td>⛔</td><td>可能是额度用完了 —— 自动再试等于替用户决定"再花一次"</td></tr>
 *   <tr><td>超时</td><td>⛔</td><td>多半是对面慢，再等一遍只是再慢一遍</td></tr>
 *   <tr><td>已经流出字之后失败</td><td>⛔</td><td>重试会把屏幕上那半段从头覆盖，而对面多半已经扣费</td></tr>
 *   <tr><td>按了停</td><td>⛔</td><td>按停的意思就是别再跑了</td></tr>
 * </table>
 * 退让：服务端给了 Retry-After 就听它的，没给就指数退避；都封顶在 {@link SessionConfig#retryCapMs()}。
 * ⛔ 不静默重试：每次都发 {@link Hook}（用户有权知道钱花在了哪儿、慢在了哪儿）。
 */
public final class Retry {
    private Retry() { }

    @FunctionalInterface
    public interface Hook { void retrying(int attempt, int max, long waitMs, ModelCallException e); }

    public static boolean worthy(Throwable e, CancelToken cancel) {
        if (cancel != null && cancel.isCancelled()) return false;
        if (!(e instanceof ModelCallException m)) return false;
        if (m.status() == 429) return false;
        return switch (m.kind()) {
            case "io" -> true;
            case "http" -> m.status() >= 500;
            default -> false;
        };
    }

    public static ModelCaller wrap(ModelCaller inner, int max, long initialMs, long capMs, Hook hook) {
        return (req, onDelta, cancel) -> call(inner, req, onDelta, cancel, max, initialMs, capMs, hook);
    }

    static ModelTurn call(ModelCaller inner, ChatRequest req, DeltaListener onDelta, CancelToken cancel,
                          int max, long initialMs, long capMs, Hook hook) {
        int attempt = 0;
        while (true) {
            boolean[] streamed = {false};
            DeltaListener d = (k, t) -> { streamed[0] = true; onDelta.onDelta(k, t); };
            try {
                return inner.call(req, d, cancel);
            } catch (ModelCallException e) {
                if (streamed[0] || attempt >= max || !worthy(e, cancel)) throw e;
                attempt++;
                long wait = e.retryAfterMs() >= 0 ? e.retryAfterMs() : initialMs * (1L << (attempt - 1));
                wait = Math.min(wait, capMs);
                if (hook != null) hook.retrying(attempt, max, wait, e);
                sleep(wait, cancel);
            }
        }
    }

    /** 可被按停打断的等待。 */
    static void sleep(long ms, CancelToken cancel) {
        if (ms <= 0) { cancel.throwIfCancelled(); return; }
        CountDownLatch l = new CountDownLatch(1);
        Runnable undo = cancel.onCancel(l::countDown);
        try {
            l.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelToken.CancelledException();
        } finally {
            undo.run();
        }
        cancel.throwIfCancelled();
    }

    /** 把错压成说得出病灶的一行（所有往屏幕上写错误的地方都走它）。 */
    public static String errorLine(Throwable e) {
        if (e == null) return "（说不出是什么错）";
        Throwable t = e instanceof io.ailink.agentforge.agent.loop.AgentPartialException && e.getCause() != null ? e.getCause() : e;
        String m = t.getMessage();
        if (m == null || m.isBlank()) m = t.getClass().getSimpleName();
        if (t instanceof ModelCallException mc && !"http".equals(mc.kind()) && !"other".equals(mc.kind())) m = m + " [" + mc.kind() + "]";
        return m;
    }
}
