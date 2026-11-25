package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Condition;
import io.ailink.agentforge.agent.codemode.Datum.LimitError;
import io.ailink.agentforge.agent.codemode.Datum.SchemeError;
import io.ailink.agentforge.agent.codemode.Datum.Sym;
import io.ailink.agentforge.agent.codemode.Datum.Void;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.tool.ToolException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 受限环境里跑一段模型写的 Scheme 脚本，脚本只能调 MCP 工具（对齐 opencode 的 code-mode）。
 * 对应 coxswain {@code (coxn agent codemode)}。
 *
 * <p>它解决的是：十次 MCP 往返变一次 —— 循环、分支、把上一步的结果喂给下一步，都在脚本里，
 * 不用每一步回模型一趟。coxswain 是 {@code eval} 进一个只有纯函数的 Chez 环境；Java 这边是一台
 * 自己写的小解释器（{@link Interp}），名字与 coxswain 的 api 逐字相同，描述 / 提示词照旧有效。
 *
 * <h2>围栏（三层）</h2>
 * <ol>
 *   <li><b>环境</b>：只有 {@link Builtins} 里的纯函数 + {@code call} / {@code log!}；没有文件、网络、进程、
 *       反射、{@code eval}。解释器只在自己的数据上走，不会碰到任何 Java 对象。每次一份新的全局帧。</li>
 *   <li><b>读入</b>：{@code #%name} / {@code #2%name}（Chez 的原语读法）出现即整段拒掉 —— 这边本来就读不了，
 *       留着这道闸是为了报错说得准。</li>
 *   <li><b>限额</b>：墙钟（默认 30 s）、调用数（50）、输出（64 KB）、步数、分配单元；外加按停。
 *       撞线算出错，已经调过的工具名照给（宿主审计用）。</li>
 * </ol>
 * ⚠️ 这不是"安全沙箱"的证明，是"够挡住模型手滑"的围栏 —— 脚本能调的本来就是 MCP 那几件，
 * 审批在 execute 那件工具上（目录里有会写的就 sensitive）。
 */
public final class CodeMode {
    private CodeMode() { }

    /**
     * @param maxSteps 求值步数上限（防死循环在墙钟之外的确定性兜底）
     * @param maxCells 累计分配单元上限（序对 / 向量槽 / 每 8 个字符），防脚本把内存吃光
     */
    public record Limits(long timeoutMs, int maxCalls, int maxOutput, long maxSteps, long maxCells) {
        public static final Limits DEFAULT = new Limits(30_000, 50, 65_536, 500_000_000, 8_000_000);

        public Limits withTimeoutMs(long v) { return new Limits(v, maxCalls, maxOutput, maxSteps, maxCells); }

        public Limits withMaxCalls(int v) { return new Limits(timeoutMs, v, maxOutput, maxSteps, maxCells); }

        public Limits withMaxOutput(int v) { return new Limits(timeoutMs, maxCalls, v, maxSteps, maxCells); }

        public Limits withMaxSteps(long v) { return new Limits(timeoutMs, maxCalls, maxOutput, v, maxCells); }
    }

    /**
     * 脚本里 {@code (call "server.tool" '(("k" . v) …))} 落到这里。
     *
     * <p>{@code args} 是 alist 转成的 JSON 对象。返回值转成脚本值：{@code StringNode} → 串，对象 → alist，
     * 数组 → 向量。抛 {@link ToolException} = 工具失败（脚本里 {@code guard} 接得住）；
     * 按停的 {@link CancelToken.CancelledException} 原样往外传。
     */
    @FunctionalInterface
    public interface Caller {
        JsonNode call(String name, JsonNode args, CancelToken cancel) throws Exception;
    }

    /** {@code #%foo} / {@code #3%foo}：读法级的原语引用。出现即拒。 */
    public static boolean forbidden(String text) {
        int n = text.length();
        for (int i = 0; i < n; i++) {
            if (text.charAt(i) != '#') continue;
            int j = i + 1;
            while (j < n && Character.isDigit(text.charAt(j))) j++;
            if (j < n && text.charAt(j) == '%') return true;
        }
        return false;
    }

    public static CodeModeResult run(String code, Caller caller) {
        return run(code, caller, Limits.DEFAULT, null);
    }

    /**
     * 跑一段脚本。脚本在一条自己的线程上跑（栈开大，深递归不至于爆）；当前线程等到它结束或撞墙钟。
     *
     * @param cancel 按停令牌；翻了则抛 {@link CancelToken.CancelledException}（不是结果）
     */
    public static CodeModeResult run(String code, Caller caller, Limits limits, CancelToken cancel) {
        List<String> logs = Collections.synchronizedList(new ArrayList<>());
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        if (code == null || forbidden(code)) {
            return fail(logs, calls, "脚本里有 #% 那种读法（直接引用系统原语），不许");
        }
        List<Object> forms;
        try {
            forms = Reader.readAll(code);
        } catch (Reader.ReadError e) {
            return fail(logs, calls, "读不了：" + e.getMessage());
        }
        if (forms.isEmpty()) return fail(logs, calls, "脚本是空的");

        CancelToken outer = cancel == null ? new CancelToken() : cancel;
        // 给工具调用用的子令牌：墙钟到了就翻，正在飞的 MCP 请求一起掐掉
        CancelToken callToken = new CancelToken();
        Runnable unhook = outer.onCancel(callToken::cancel);
        long deadline = System.nanoTime() + limits.timeoutMs() * 1_000_000L;
        int[] nCalls = {0};
        int[] logBytes = {0};
        Interp.Host host = new Interp.Host() {
            @Override
            public Object call(Interp in, String name, Object args) {
                if (++nCalls[0] > limits.maxCalls()) throw new LimitError("调用超过 " + limits.maxCalls() + " 次上限");
                calls.add(name);
                JsonNode argNode = SJson.toNode(args);
                JsonNode r;
                try {
                    if (caller == null) throw new ToolException("没有工具可调");
                    r = caller.call(name, argNode, callToken);
                } catch (CancelToken.CancelledException e) {
                    throw e;
                } catch (ToolException e) {
                    throw new SchemeError(new Condition("error", Sym.of("call"), e.getMessage(), List.of()), false);
                } catch (Exception e) {
                    String m = e.getMessage();
                    throw new SchemeError(new Condition("error", Sym.of("call"),
                            e.getClass().getSimpleName() + (m == null ? "" : ": " + m), List.of()), false);
                }
                in.checkClock();
                return SJson.fromNode(in, r);
            }

            @Override
            public void log(String line) {
                if (logBytes[0] > limits.maxOutput()) return;
                logBytes[0] += line.length();
                logs.add(logBytes[0] > limits.maxOutput() ? "…（log 截断：超过 " + limits.maxOutput() + " 字节）" : line);
            }
        };
        Interp in = new Interp(host, limits.maxSteps(), limits.maxCells(), deadline, outer);

        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread t = new Thread(null, () -> {
            try {
                Object last = Void.VOID;
                for (Object f : forms) last = in.eval(f, in.global);
                outcome.set(new Ok(last));
            } catch (Throwable e) {
                outcome.set(e);
            }
        }, "codemode", 256L * 1024 * 1024);
        t.setDaemon(true);
        // 墙钟到了：翻工具调用的令牌（正在飞的请求掐掉）
        Thread timer = Thread.ofVirtual().start(() -> {
            try {
                long ms = (deadline - System.nanoTime()) / 1_000_000L;
                if (ms > 0) Thread.sleep(ms);
                callToken.cancel();
            } catch (InterruptedException ignored) {
                // 脚本先跑完了
            }
        });
        t.start();
        try {
            t.join(limits.timeoutMs() + 2_000);
            if (t.isAlive()) {
                in.abort = true;
                t.join(2_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            in.abort = true;
            throw new CancelToken.CancelledException();
        } finally {
            timer.interrupt();
            unhook.run();
        }

        Object o = outcome.get();
        if (outer.isCancelled()) throw new CancelToken.CancelledException();
        if (o == null) return fail(logs, calls, "超时（" + limits.timeoutMs() + " ms）");
        if (o instanceof Ok ok) {
            return new CodeModeResult(true, clip(render(ok.value()), limits.maxOutput()), List.copyOf(logs),
                    List.copyOf(calls), null);
        }
        if (o instanceof CancelToken.CancelledException) {
            // 外层没翻 ⇒ 是墙钟翻了工具调用那一枚
            return fail(logs, calls, "超时（" + limits.timeoutMs() + " ms）");
        }
        if (o instanceof LimitError le) {
            String m = le.getMessage();
            return fail(logs, calls, m.equals("超时") ? "超时（" + limits.timeoutMs() + " ms）" : m);
        }
        if (o instanceof SchemeError se) return fail(logs, calls, Printer.conditionText(se.payload));
        if (o instanceof Datum.ContinuationThrow) return fail(logs, calls, "续延在它的范围之外被调用了");
        if (o instanceof StackOverflowError) return fail(logs, calls, "递归太深（栈用完了）");
        if (o instanceof OutOfMemoryError) return fail(logs, calls, "内存不够了");
        Throwable th = (Throwable) o;
        return fail(logs, calls, "解释器内部错误：" + th.getClass().getSimpleName() + (th.getMessage() == null ? "" : ": " + th.getMessage()));
    }

    private record Ok(Object value) { }

    private static CodeModeResult fail(List<String> logs, List<String> calls, String why) {
        return new CodeModeResult(false, null, List.copyOf(logs), List.copyOf(calls), why);
    }

    /** 值 → 给模型看的串：字符串原样，void → 空串，其余 write（表 / 向量 / 数都是模型认得的形）。 */
    static String render(Object v) {
        if (v instanceof String s) return s;
        if (v == Void.VOID) return "";
        return Printer.write(v);
    }

    static String clip(String s, int limit) {
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "\n…（截断：超过 " + limit + " 字节）";
    }
}
