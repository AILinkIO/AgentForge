package io.ailink.agentforge.agent.subagent;

import io.ailink.agentforge.agent.attach.Attach;
import io.ailink.agentforge.agent.loop.AgentResult;
import io.ailink.agentforge.agent.loop.AgentStatus;
import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.Retry;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.session.SessionListener;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.transcript.SubRun;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code task} 工具：把一件活派给子 agent（coxswain {@code subagent.ss} + {@code subagent/child.ss} + {@code schema.ss}）。
 *
 * <p>子 agent 是一段**子会话**（{@link AgentSession#parent()} 认爹）：它自己说的话、推理、每件工具的完整输出都留在那段
 * 会话里，父会话只拿到一段结论 + 一条 sub-run 记录（步骤名、耗时、派给谁、跑在哪条模型上）。用量算进父会话。
 *
 * <ul>
 *   <li>深度闸：第 N 层能不能再派由 {@link SubagentHost#maxDepth()} 定（默认 1 = 子 agent 手里没有 task）；</li>
 *   <li>{@code task_id}：接着上一趟**已经停下**的子会话往下跑（比重新派便宜得多 —— 它查到的东西不用再查）；</li>
 *   <li>{@code background}：放到后台跑，结论跑完后作为一条唤醒消息送回父会话（没有轮询接口）；</li>
 *   <li>升档：降级跑砸了（没做完）换回主模型在同一段子会话里接着做一次。</li>
 * </ul>
 * 父会话从 {@code ToolEnv.service(AgentSession.class)} 取 —— 这件工具本身是全局一件，谁调它就派到谁底下。
 */
public final class TaskTool {
    private TaskTool() { }

    public static final String NAME = "task";

    static final String DESCRIPTION =
            "派一件小活给子 agent：它自己去查、去读（junior 还能按吩咐改），还给你一段结论。"
                    + "适合「在整个仓库里找出所有 X」「读完这几个文件回答 Y」这种"
                    + "要翻很多东西、但结果只有几句话的活 —— 那些中间过程不必占用"
                    + "你自己的上下文。"
                    + "⚠️ 它看不见你们之前说过什么，所以 prompt 要写成一件自足的任务。";

    public static AgentTool create(SubagentHost host) {
        List<AgentDef> defs = host.defs();
        AtomicReference<AgentTool> self = new AtomicReference<>();
        AgentTool t = AgentTool.builder(NAME)
                .description(DESCRIPTION)
                .rawSchema(schema(defs))
                .concurrencySafe()     // 只读那几趟并行；要审批的调用在会话的问答槽上排队
                .handler((env, a) -> run(host, defs, self.get(), env, a))
                .build();
        self.set(t);
        return t;
    }

    // ── schema ───────────────────────────────────────────────────────────

    static ObjectNode schema(List<AgentDef> defs) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode s = f.objectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("description").put("type", "string")
                .put("description", "一句话说明这件活，给用户看（例如「找出所有调用 foo 的地方」）");
        p.putObject("prompt").put("type", "string")
                .put("description", "交给子 agent 的完整任务描述。它看不见上下文，所以要自足：说清在哪儿找、要什么、结论怎么给");
        ObjectNode who = p.putObject("subagent_type").put("type", "string");
        ArrayNode e = who.putArray("enum");
        List<String> lines = new ArrayList<>();
        for (AgentDef d : defs) {
            e.add(d.name());
            lines.add("- " + d.name() + "：" + (d.description().isEmpty() ? "（没写说明的活别派给它，等用户点名）" : d.description()));
        }
        who.put("description", "派给哪一条子 agent。\n" + String.join("\n", lines));
        ObjectNode imgs = p.putObject("images").put("type", "array");
        imgs.putObject("items").put("type", "string");
        imgs.put("description", "要给它看的图片路径（相对工作根目录）。只有派给看得了图的那几条子 agent 时才填，别的一律留空。"
                + "只收 PNG / JPEG / GIF / WebP，单张不超过约 3.6 MB。⚠️ 图只进那一段子会话，不进你自己的上下文 —— 这正是这条路存在的理由。");
        p.putObject("task_id").put("type", "string")
                .put("description", "接着**上一趟**子 agent 往下跑。填上一趟结论末尾那个「任务 id」。"
                        + "它没做完（轮数用完 / 被叫停）而你还想让它接着查时用这个，⚠️ 比重新派一遍便宜得多 —— 它已经查到的东西不用再查一次。"
                        + "接着派时 `prompt` 写**接下来做什么**，不用重复原来那件活。");
        p.putObject("background").put("type", "boolean")
                .put("description", "后台跑：立刻返回一个任务 id，这一趟在后台继续。适合「查着这个的同时我先干别的」——"
                        + "⚠️ 它的结论会**自己**在稍后送到你面前，你不用去取、也取不到（没有轮询接口）。"
                        + "⚠️ 要**马上**用它的结论就别填这个：那种情况下等它跑完更省。");
        ObjectNode task = p.putObject("task").put("type", "string");
        ArrayNode te = task.putArray("enum");
        for (String k : TaskKind.ids()) te.add(k);
        task.put("description", "这是哪一类活。" + TaskKind.descriptions()
                + "。⚠️ 声明 junior 可能会换一条更便宜的模型去跑；声明 vision 会保证换到一条看得了图的。拿不准就别填。");
        ArrayNode req = s.putArray("required");
        req.add("description").add("prompt");
        if (!defs.isEmpty()) req.add("subagent_type");
        return s;
    }

    // ── 派活 ─────────────────────────────────────────────────────────────

    static Object run(SubagentHost host, List<AgentDef> defs, AgentTool self, ToolEnv env, Args a) {
        AgentSession parent = env.service(AgentSession.class);
        if (parent == null) throw new ToolException("这个环境里派不了子 agent（宿主没接会话）");
        if (!host.enabled()) throw new ToolException("派活给子 agent 这个功能被关掉了，这件事你自己做");
        int here = parent.depth(), limit = host.maxDepth();
        if (here + 1 > limit) {
            throw new ToolException("已经是第 " + here + " 层子 agent 了，再派就是第 " + (here + 1) + " 层，而上限是 " + limit
                    + " —— 这件活你自己做。");
        }
        String desc = a.str("description", "");
        String prompt = a.str("prompt", "");
        AgentDef def = who(defs, a.str("subagent_type", ""));
        String taskId = a.str("task_id", "");
        boolean bg = a.bool("background", false);
        List<String> paths = a.strings("images");
        paths.removeIf(String::isEmpty);

        AgentSession child;
        String runId;
        if (!taskId.isEmpty()) {
            child = parent.child(taskId);
            if (child == null) throw new ToolException("认不出任务 id \"" + taskId + "\" —— 接不上。要么它不是上一趟结论末尾那个 id，"
                    + "要么那一趟没有留下记录。重新派一趟（别填 task_id）。");
            if (child.busy()) throw new ToolException("任务 \"" + taskId + "\" 还在跑着 —— 接不上（要接的是一趟**已经停下**的活）。"
                    + "它跑完之后结论会自己送到你面前，等那条消息。");
            if (child.agent() != null && !child.agent().equals(def.name())) {
                throw new ToolException("那一趟派的是 " + child.agent() + "，接着派必须还是它（你填的是 " + def.name()
                        + "）。要换一条 agent 就重新派一趟，别填 task_id。");
            }
            runId = taskId;
        } else {
            runId = env.toolUseId();
            child = host.newChild(parent, def, desc, runId);
            parent.addChild(child);
        }
        if (here + 2 <= limit && !child.tools().contains(self)) {
            List<AgentTool> ts = new ArrayList<>(child.tools());
            ts.add(self);
            child.setTools(ts);
        }
        Object payload = payload(defs, def, prompt, paths, env);
        SubRun run = parent.subBegin(desc, def.name(), env.toolUseId());
        if (bg) {
            run.background(true);
            final AgentSession c = child;
            Thread.ofVirtual().name("subagent-" + runId).start(() -> {
                try {
                    String text = go(host, parent, c, def, run, payload, null, runId);
                    parent.queueWake("后台任务 " + runId + "（" + desc + "）跑完了，这是它的结论：\n" + text);
                } catch (CancelToken.CancelledException e) {
                    // 父会话按了停：sub-run 已封为 stopped，不再送结论
                } catch (RuntimeException e) {
                    parent.queueWake("后台任务 " + runId + "（" + desc + "）出错了：" + e.getMessage()
                            + "。要结论就重新派一趟（别填 task_id）。");
                }
            });
            return bgStartedText(runId);
        }
        return go(host, parent, child, def, run, payload, env.cancel(), runId);
    }

    static AgentDef who(List<AgentDef> defs, String name) {
        for (AgentDef d : defs) if (d.name().equals(name)) return d;
        List<String> names = new ArrayList<>();
        for (AgentDef d : defs) names.add(d.name());
        throw new ToolException("没有叫 \"" + name + "\" 的子 agent。可选：" + String.join(" / ", names));
    }

    static boolean looks(AgentDef d) { return d.taskKind() != null && d.taskKind().needAttachment(); }

    static Object payload(List<AgentDef> defs, AgentDef def, String prompt, List<String> paths, ToolEnv env) {
        boolean wants = looks(def);
        if (wants && paths.isEmpty()) {
            throw new ToolException("派给 " + def.name() + " 必须在 images 里给出图片路径：它一件工具都没有，看不见你没附上的东西。");
        }
        if (paths.isEmpty()) return prompt;
        if (!wants) {
            List<String> lookers = new ArrayList<>();
            for (AgentDef d : defs) if (looks(d)) lookers.add(d.name());
            throw new ToolException(def.name() + " 不看图，images 不该给它。要看图请派给："
                    + (lookers.isEmpty() ? "（这一批里一条看得了图的子 agent 都没有）" : String.join(" / ", lookers)));
        }
        List<Block> bs = new ArrayList<>();
        bs.add(new Block.Text(Prompts.attachmentNote(paths) + prompt));
        for (String p : paths) {
            try {
                bs.add(Attach.attachImage(env.workspace(), p));
            } catch (RuntimeException e) {
                throw new ToolException(e.getMessage() == null ? p + " 附不上" : e.getMessage());
            }
        }
        return bs;
    }

    /** 跑一趟（可能升档再跑一次），回交给模型的那段话。 */
    static String go(SubagentHost host, AgentSession parent, AgentSession child, AgentDef def, SubRun run,
                     Object payload, CancelToken cancel, String runId) {
        TaskKind kind = def.taskKind() != null ? def.taskKind() : TaskKind.DEFAULT;
        SubagentHost.Choice choice = host.chooseModel(parent, def, kind);
        String used = choice == null || choice.model() == null ? parent.model() : choice.model();
        SessionListener tap = (s, e) -> {
            if (e instanceof SessionEvent.ToolCall tc) parent.subStep(run, tc.name(), tc.input());
        };
        child.addListener(tap);
        try {
            SubagentResult res = runOn(parent, child, run, used, payload, cancel, host.maxIterations());
            boolean userStop = cancel != null && cancel.isCancelled();
            if (!res.completed() && !userStop) {
                String up = host.escalate(parent, used);
                if (up != null && !up.equals(used)) {
                    res = runOn(parent, child, run, up, Prompts.ESCALATE_NUDGE_PROMPT, cancel, host.maxIterations());
                }
            }
            parent.subEnd(run, res.status() == null ? "error" : res.status().wire(), res.steps(),
                    res.completed() ? null : res.status() == null ? null : res.status().wire());
            String text = withTaskId(res.text(), runId);
            if (res.status() == AgentStatus.STOPPED) throw new ToolException(text);
            return text;
        } finally {
            child.removeListener(tap);
        }
    }

    static SubagentResult runOn(AgentSession parent, AgentSession child, SubRun run, String model, Object payload,
                                CancelToken cancel, Integer maxIt) {
        if (model != null) {
            child.setModel(model);
            if (!model.equals(parent.model())) run.model(model);
        }
        if (maxIt != null) child.config().maxIterations(maxIt);
        Usage before = child.usage();
        try {
            AgentResult r = child.runTurn(payload, cancel);
            return new SubagentResult(render(r, maxIt), r.status(), r.usage(), r.steps(), r.messages(), model);
        } catch (CancelToken.CancelledException e) {
            parent.subEnd(run, "stopped", List.of(), "被停下");
            throw e;
        } catch (RuntimeException e) {
            parent.subEnd(run, "error", List.of(), Retry.errorLine(e));
            throw new ToolException("子 agent 没跑完就出了错：" + Retry.errorLine(e) + "\n任务 id：" + run.id()
                    + " —— 要接着这一趟再查，把它填进 task_id。");
        } finally {
            Usage after = child.usage();
            Usage delta = new Usage(after.input() - before.input(), after.output() - before.output(),
                    after.cacheRead() - before.cacheRead(), after.cacheWrite() - before.cacheWrite(),
                    after.reasoning() - before.reasoning());
            if (!delta.isZero()) parent.addUsage(delta, model != null ? model : parent.model());
        }
    }

    static String render(AgentResult r, Integer maxIt) {
        String text = r.text();
        if (text != null && text.isEmpty()) text = null;
        String mi = maxIt == null ? "全部" : String.valueOf(maxIt);
        return switch (r.status()) {
            case COMPLETED, RETURN_DIRECT -> {
                String body = text != null ? text : "（子 agent 跑完了，但一个字都没说。）";
                yield r.response() != null && r.response().truncated()
                        ? "⚠️ 子 agent 的回答被输出上限截断（stop_reason max_tokens），下面这段**不完整**，别当成完整结论读：\n" + body
                        : body;
            }
            case MAX_ITERATIONS -> text != null
                    ? "⚠️ 子 agent 用完了 " + mi + " 轮，这件活没做完。它自己的收尾：\n" + text
                    : "⚠️ 子 agent 用完了 " + mi + " 轮还没有给出结论，这件活没做完。\n它做了这些：\n" + stepsNote(r.steps())
                    + "\n把活拆小一点再派，或者自己做。";
            case STOPPED -> "⚠️ 这一趟被用户中断了（不是它自己失败，也不是轮数用完）。\n中断前它做了这些：\n" + stepsNote(r.steps())
                    + (text != null ? "\n中断前它说：\n" + text : "")
                    + "\n⚠️ **不要自动重新派一遍** —— 用户按停的意思是别再跑了。";
            case STOP_WHEN -> "⚠️ 子 agent 停在半路（" + r.stopReason() + "），这件活没做完。\n它做了这些：\n" + stepsNote(r.steps())
                    + (text != null ? "\n它最后说：\n" + text : "");
        };
    }

    static String stepsNote(List<Step> ss) {
        if (ss.isEmpty()) return "  （一件工具都没调）";
        List<String> out = new ArrayList<>();
        for (Step s : ss) out.add("  " + (s.error() ? "✗" : "·") + " " + s.name());
        return String.join("\n", out);
    }

    static String withTaskId(String text, String id) {
        return id == null || text == null ? text
                : text + "\n\n（任务 id：" + id + " —— 要接着这一趟再查，把它填进 task_id，别重新派一遍）";
    }

    static String bgStartedText(String runId) {
        return "已经放到后台跑了（任务 id：" + runId + "）。\n"
                + "⚠️ 这里**还没有结论** —— 它跑完之后结论会自己送到你面前，"
                + "你不用去取，也没有地方可取（这条路没有轮询接口）。\n"
                + "现在接着做别的；要用到它的结论时，等那条消息到了再说。"
                + "⚠️ 别拿这个 id 去填 `task_id`：那是接一趟**已经停下**的活，而这一趟还在跑。";
    }
}
