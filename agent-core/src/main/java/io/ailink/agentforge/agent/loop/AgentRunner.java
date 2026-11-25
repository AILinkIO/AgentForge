package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 跑一趟 agent：问模型 → 跑工具 → 再问，直到答完 / 按停 / 用满轮数（coxswain {@code make-chat-client}
 * + {@code chat-client-call}）。
 *
 * <p>⚠️ 这一层**不做任何"应该怎样"的决定**：压多少历史、审不审批、给哪些工具、怎么重试，全是会话层
 * 经过滤器装进来的。循环本身只管机制：顺序、并发、按停、把结果配对成 tool_result。
 *
 * <p>工具在 {@link ToolEnv} 里跑；{@code envFactory} 按每次调用的 tool_use id 造一份
 * （会话侧服务、工作区都从那里来）。
 */
public final class AgentRunner {
    private final ModelCaller caller;
    private final String system;
    private final RequestOptions options;
    private final List<Filter> filters;
    private final ReactLoop loop;
    private final Function<String, ToolEnv> envFactory;
    private final DeltaListener onDelta;

    private static final ExecutorService TOOL_POOL = Executors.newVirtualThreadPerTaskExecutor();

    public AgentRunner(ModelCaller caller, String system, RequestOptions options, List<Filter> filters,
                       ReactLoop loop, Function<String, ToolEnv> envFactory, DeltaListener onDelta) {
        this.caller = caller;
        this.system = system;
        this.options = options;
        this.filters = filters == null ? List.of() : List.copyOf(filters);
        this.loop = loop;
        this.envFactory = envFactory;
        this.onDelta = onDelta == null ? DeltaListener.NONE : onDelta;
    }

    public AgentResult run(List<Message> initial) {
        List<Message> history = new ArrayList<>(initial);
        List<Step> steps = new ArrayList<>();
        Usage usage = Usage.ZERO;
        FilterCtx ctx = new FilterCtx(loop.tools());
        CancelToken cancel = loop.cancel();
        ModelTurn last = null;
        int round = 0;
        boolean nudged = false;
        try {
            while (true) {
                if (cancel.isCancelled()) throw new CancelToken.CancelledException();
                // stop-when 在**下一次问模型之前**看（第一轮不看）：放在回答之后看的话，带着 tool_use 的那条回答会
                // 在工具还没跑时就收口，历史末尾是一条没有结果的 tool_use —— 接着跑（压缩后续跑）就是 400。
                if (round > 0) {
                    for (ReactLoop.StopWhen sw : loop.stopWhen()) {
                        String why = sw.check(last, history);
                        if (why != null) return new AgentResult(AgentStatus.STOP_WHEN, history, last, usage, steps, round, why);
                    }
                }
                round++;
                ctx.setRound(round);
                history.addAll(ctx.drainPushed());
                boolean finalRound = nudged;
                List<AgentTool> tools = finalRound ? List.of() : loop.tools();
                ChatRequest req = new ChatRequest(system, history, tools, options, ctx);
                last = chat(req, cancel);
                usage = usage.plus(last.usage());
                history.add(last.message());

                List<Block.ToolUse> uses = last.message().toolUses();
                if (uses.isEmpty() || finalRound) {
                    AgentStatus st = finalRound ? AgentStatus.MAX_ITERATIONS : AgentStatus.COMPLETED;
                    if (!ctx.drainPushedPeek().isEmpty() && !finalRound) {
                        // 答完的那一刻恰好有排队的话被推进来：再跑一轮让模型读到
                        continue;
                    }
                    return new AgentResult(st, history, last, usage, steps, round, null);
                }

                List<ToolResult> results = runTools(uses, ctx, cancel, steps);
                List<Block> blocks = new ArrayList<>();
                boolean stopped = false;
                boolean direct = false;
                for (int i = 0; i < uses.size(); i++) {
                    ToolResult r = results.get(i);
                    blocks.add(new Block.ToolResult(uses.get(i).id(), r.output().content(), r.error()));
                    stopped |= r.stopped();
                    direct |= "return-direct".equals(r.kind());
                }
                if (!stopped && !direct && loop.roundNudge() != null) {
                    String n = loop.roundNudge().nudge(round, loop.maxIterations(), history, List.copyOf(steps));
                    if (n != null && !n.isBlank()) blocks.add(new Block.Text(n));
                }
                history.add(new Message(Role.USER, blocks));
                if (stopped) return new AgentResult(AgentStatus.STOPPED, history, last, usage, steps, round, null);
                if (direct) return new AgentResult(AgentStatus.RETURN_DIRECT, history, last, usage, steps, round, null);

                if (round >= loop.maxIterations()) {
                    if (loop.finalNudge() == null) {
                        return new AgentResult(AgentStatus.MAX_ITERATIONS, history, last, usage, steps, round, null);
                    }
                    // 用满了：补一句叫它收尾，最后一轮不给工具
                    ctx.pushMessage(Message.user(loop.finalNudge()).withMeta("nudge", "final"));
                    nudged = true;
                }
            }
        } catch (CancelToken.CancelledException e) {
            throw new AgentPartialException(history, usage, e, true);
        } catch (AgentPartialException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AgentPartialException(history, usage, e, cancel.isCancelled());
        }
    }

    private ModelTurn chat(ChatRequest req, CancelToken cancel) {
        Filter.ChatChain chain = r -> caller.call(r, onDelta, cancel);
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter f = filters.get(i);
            Filter.ChatChain next = chain;
            chain = r -> f.chat(r, next);
        }
        return chain.next(req);
    }

    private ToolResult invokeTool(ToolRequest req) {
        Filter.ToolChain chain = r -> {
            if (r.tool() == null) {
                return ToolResult.error("没有叫 " + r.toolUse().name() + " 的工具（或者这一趟不给它）。可用的有："
                        + String.join("、", r.ctx().allowedTools().stream().map(AgentTool::name).toList()), "unknown-tool");
            }
            r.env().cancel().throwIfCancelled();
            ToolOutput out = r.tool().execute(r.env(), r.toolUse().input());
            return ToolResult.of(out);
        };
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter f = filters.get(i);
            Filter.ToolChain next = chain;
            chain = r -> f.tool(r, next);
        }
        return chain.next(req);
    }

    private ToolRequest request(Block.ToolUse u, FilterCtx ctx, CancelToken cancel) {
        ToolEnv env = envFactory.apply(u.id()).withCall(u.id(), cancel);
        return new ToolRequest(u, ctx.findTool(u.name()), env, ctx);
    }

    /** 顺序跑；相邻的 concurrency-safe 一组并发。某件按了停，后面的都不跑。 */
    private List<ToolResult> runTools(List<Block.ToolUse> uses, FilterCtx ctx, CancelToken cancel, List<Step> steps) {
        List<ToolResult> out = new ArrayList<>();
        int i = 0;
        boolean stopped = false;
        while (i < uses.size()) {
            if (stopped || cancel.isCancelled()) {
                if (cancel.isCancelled() && !stopped) throw new CancelToken.CancelledException();
                out.add(ToolResult.error("没有执行：这一轮已经停下了。", "stopped"));
                i++;
                continue;
            }
            int j = i;
            if (loop.parallel()) {
                while (j < uses.size() && safe(ctx, uses.get(j))) j++;
            }
            if (j - i >= 2) {
                List<Future<ToolResult>> fs = new ArrayList<>();
                List<Long> starts = new ArrayList<>();
                for (int k = i; k < j; k++) {
                    ToolRequest r = request(uses.get(k), ctx, cancel);
                    starts.add(System.currentTimeMillis());
                    fs.add(TOOL_POOL.submit(() -> invokeTool(r)));
                }
                for (int k = i; k < j; k++) {
                    ToolResult r = await(fs.get(k - i));
                    record(steps, uses.get(k), r, starts.get(k - i));
                    out.add(r);
                }
                i = j;
            } else {
                long t0 = System.currentTimeMillis();
                ToolResult r = invokeTool(request(uses.get(i), ctx, cancel));
                record(steps, uses.get(i), r, t0);
                out.add(r);
                stopped = r.stopped();
                i++;
            }
        }
        return out;
    }

    private static boolean safe(FilterCtx ctx, Block.ToolUse u) {
        AgentTool t = ctx.findTool(u.name());
        return t != null && t.concurrencySafe();
    }

    private static ToolResult await(Future<ToolResult> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelToken.CancelledException();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            return ToolResult.error(String.valueOf(c), "error");
        }
    }

    private static void record(List<Step> steps, Block.ToolUse u, ToolResult r, long t0) {
        synchronized (steps) {
            steps.add(new Step(u.id(), u.name(), u.input(), r.output().content(), r.error(), r.kind(),
                    System.currentTimeMillis() - t0));
        }
    }
}
