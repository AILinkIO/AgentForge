package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.CancelToken;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * 用任意 Spring AI {@link ChatModel} 问模型：把 L1 消息模型编成 Spring AI 的 {@link Prompt}，
 * 流式收回来，再拼回一条 assistant {@link Message}。
 *
 * <p>选项（模型、key、端点、思考、cache、工具声明）由 {@code optionsFactory} 给 —— 那是"这条模型
 * 怎么调"的知识，在 models 包里。⚠️ 工具**执行**不在 Spring AI 里：选项必须关掉
 * internalToolExecutionEnabled，循环自己跑工具。
 *
 * <p>已知限制：Spring AI 2.0 不把 thinking 块回传给 Anthropic；开思考 + 工具循环时由会话层
 * 改用原生 caller（见 models 包）。
 */
public final class SpringAiModelCaller implements ModelCaller {
    private final ChatModel model;
    private final BiFunction<ChatRequest, List<ToolCallback>, ChatOptions> optionsFactory;
    private final boolean stream;

    public SpringAiModelCaller(ChatModel model, BiFunction<ChatRequest, List<ToolCallback>, ChatOptions> optionsFactory,
                               boolean stream) {
        this.model = model;
        this.optionsFactory = optionsFactory;
        this.stream = stream;
    }

    @Override
    public ModelTurn call(ChatRequest req, DeltaListener onDelta, CancelToken cancel) {
        cancel.throwIfCancelled();
        List<ToolCallback> cbs = new ArrayList<>(req.tools());
        Prompt prompt = new Prompt(toSpring(req.system(), req.messages()), optionsFactory.apply(req, cbs));
        try {
            return stream ? streamCall(prompt, onDelta, cancel) : blockingCall(prompt, onDelta);
        } catch (CancelToken.CancelledException | ModelCallException e) {
            throw e;
        } catch (RuntimeException e) {
            if (cancel.isCancelled()) throw new CancelToken.CancelledException();
            throw classify(e);
        }
    }

    // ── 发出去 ───────────────────────────────────────────────────────────

    static List<org.springframework.ai.chat.messages.Message> toSpring(String system, List<Message> msgs) {
        List<org.springframework.ai.chat.messages.Message> out = new ArrayList<>();
        if (system != null && !system.isBlank()) out.add(new SystemMessage(system));
        Map<String, String> toolNames = new HashMap<>();
        for (Message m : msgs) {
            if (m.role() == Role.ASSISTANT) {
                StringBuilder text = new StringBuilder();
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                for (Block b : m.blocks()) {
                    if (b instanceof Block.Text t) text.append(t.text());
                    else if (b instanceof Block.ToolUse u) {
                        toolNames.put(u.id(), u.name());
                        calls.add(new AssistantMessage.ToolCall(u.id(), "function", u.name(),
                                u.input() == null ? "{}" : u.input().toString()));
                    }
                }
                out.add(AssistantMessage.builder().content(text.toString()).toolCalls(calls).build());
                continue;
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            List<Media> resultMedia = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            List<Media> media = new ArrayList<>();
            for (Block b : m.blocks()) {
                switch (b) {
                    case Block.ToolResult r -> {
                        String body = Block.joinText(r.content());
                        if (r.error() && !body.startsWith("错误")) body = "错误：" + body;
                        responses.add(new ToolResponseMessage.ToolResponse(r.toolUseId(),
                                toolNames.getOrDefault(r.toolUseId(), "tool"), body));
                        for (Block c : r.content()) {
                            Media md = media(c);
                            if (md != null) resultMedia.add(md);
                        }
                    }
                    case Block.Text t -> { if (!text.isEmpty()) text.append("\n"); text.append(t.text()); }
                    case Block.Image i -> media.add(media(i));
                    case Block.Document d -> media.add(media(d));
                    default -> { }
                }
            }
            if (!responses.isEmpty()) out.add(ToolResponseMessage.builder().responses(responses).build());
            // 工具结果里的图：多数 provider 的 tool 消息只收文本，于是跟一条 user 消息把图带上
            if (!resultMedia.isEmpty()) {
                out.add(UserMessage.builder().text("（上面工具结果里附带的图片 / 文档）").media(resultMedia).build());
            }
            if (!text.isEmpty() || !media.isEmpty()) {
                out.add(UserMessage.builder().text(text.isEmpty() ? " " : text.toString()).media(media).build());
            }
        }
        return out;
    }

    private static Media media(Block b) {
        if (b instanceof Block.Image i) {
            return new Media(MimeType.valueOf(i.mediaType()), new ByteArrayResource(Base64.getDecoder().decode(i.data())));
        }
        if (b instanceof Block.Document d) {
            return new Media(MimeType.valueOf(d.mediaType()), new ByteArrayResource(Base64.getDecoder().decode(d.data())));
        }
        return null;
    }

    // ── 收回来 ───────────────────────────────────────────────────────────

    private ModelTurn blockingCall(Prompt prompt, DeltaListener onDelta) {
        ChatResponse r = model.call(prompt);
        Acc acc = new Acc();   // 非流式也走同一套累计
        acc.add(r, onDelta);
        return acc.finish();
    }

    private ModelTurn streamCall(Prompt prompt, DeltaListener onDelta, CancelToken cancel) {
        Acc acc = new Acc();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Disposable d = model.stream(prompt).subscribe(
                r -> acc.add(r, onDelta),
                e -> { err.set(e); done.countDown(); },
                done::countDown);
        Runnable undo = cancel.onCancel(() -> { d.dispose(); done.countDown(); });
        try {
            done.await();
        } catch (InterruptedException e) {
            d.dispose();
            Thread.currentThread().interrupt();
            throw new CancelToken.CancelledException();
        } finally {
            undo.run();
        }
        if (cancel.isCancelled()) throw new CancelToken.CancelledException();
        if (err.get() != null) {
            Throwable e = err.get();
            throw e instanceof RuntimeException re ? classify(re) : classify(new RuntimeException(e));
        }
        return acc.finish();
    }

    /** 把流式的一块块拼成一次回答。 */
    private static final class Acc {
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        String signature;
        final LinkedHashMap<String, String[]> calls = new LinkedHashMap<>(); // id → {name, args}
        String lastCallId;
        String finish;
        String modelName;
        // 各字段取所有流式块里的最大值：Anthropic 的缓存读写只在 message_start 那块里，
        // 收尾的 message_delta 只带 output —— 只留"最后一块"会把缓存那两笔丢掉。
        long prompt, completion, cacheRead, cacheWrite;
        boolean openAiShape;

        void add(ChatResponse r, DeltaListener onDelta) {
            if (r == null) return;
            ChatResponseMetadata md = r.getMetadata();
            if (md != null) {
                if (md.getModel() != null && !md.getModel().isBlank()) modelName = md.getModel();
                var u = md.getUsage();
                if (u != null) {
                    // ⚠️ Spring AI 2.0 处理 message_delta 时输入数沿用 message_start 的，delta 自带的 input_tokens 被丢掉；
                    //    而网关转换（如 LiteGate 把 OpenAI 上游转成 Anthropic 流）时 message_start 里输入是 0、真值只在
                    //    message_delta。只读 promptTokens 会让输入记成 0：缓存比例冲到近 100%，上下文占用与花费都偏低。
                    prompt = Math.max(prompt, Math.max(nz(u.getPromptTokens()), nativeLong(u.getNativeUsage(), "inputTokens")));
                    completion = Math.max(completion, nz(u.getCompletionTokens()));
                    cacheRead = Math.max(cacheRead, Math.max(nz(u.getCacheReadInputTokens()), nativeLong(u.getNativeUsage(), "cacheReadInputTokens")));
                    cacheWrite = Math.max(cacheWrite, Math.max(nz(u.getCacheWriteInputTokens()), nativeLong(u.getNativeUsage(), "cacheCreationInputTokens")));
                    Object nat = u.getNativeUsage();
                    if (nat != null && nat.getClass().getName().contains("openai")) openAiShape = true;
                }
            }
            for (Generation g : r.getResults()) {
                AssistantMessage out = g.getOutput();
                if (g.getMetadata() != null && g.getMetadata().getFinishReason() != null
                        && !g.getMetadata().getFinishReason().isBlank()) {
                    finish = g.getMetadata().getFinishReason();
                }
                if (out == null) continue;
                Map<String, Object> props = out.getMetadata();
                boolean isThinking = props != null && Boolean.TRUE.equals(props.get("thinking"));
                String t = out.getText();
                if (props != null && props.get("signature") instanceof String sg && !sg.isBlank()) signature = sg;
                if (props != null && props.get("reasoningContent") instanceof String rc && !rc.isEmpty()) {
                    thinking.append(rc);
                    onDelta.onDelta("thinking", rc);
                }
                if (t != null && !t.isEmpty()) {
                    if (isThinking) { thinking.append(t); onDelta.onDelta("thinking", t); }
                    else { text.append(t); onDelta.onDelta("text", t); }
                }
                for (AssistantMessage.ToolCall c : out.getToolCalls()) {
                    String id = c.id();
                    String args = c.arguments() == null ? "" : c.arguments();
                    if (id != null && !id.isBlank()) {
                        String[] prev = calls.get(id);
                        if (prev == null) calls.put(id, new String[]{c.name(), args});
                        else {
                            if (c.name() != null && !c.name().isBlank()) prev[0] = c.name();
                            // 有的实现每块给全量，有的给增量：全量以已有为前缀
                            prev[1] = args.startsWith(prev[1]) ? args : prev[1] + args;
                        }
                        lastCallId = id;
                    } else if (lastCallId != null) {
                        String[] prev = calls.get(lastCallId);
                        prev[1] = prev[1] + args;
                    }
                }
            }
        }

        ModelTurn finish() {
            List<Block> blocks = new ArrayList<>();
            if (!thinking.isEmpty()) blocks.add(new Block.Thinking(thinking.toString(), signature, false));
            if (!text.isEmpty()) blocks.add(new Block.Text(text.toString()));
            for (var e : calls.entrySet()) {
                blocks.add(new Block.ToolUse(e.getKey(), e.getValue()[0], parseArgs(e.getValue()[1])));
            }
            String stop = normalizeStop(finish, !calls.isEmpty());
            long input = openAiShape ? Math.max(0, prompt - cacheRead) : prompt;
            return new ModelTurn(new Message(Role.ASSISTANT, blocks), new Usage(input, completion, cacheRead, cacheWrite, 0), stop, modelName);
        }
    }

    static ObjectNode parseArgs(String s) {
        if (s == null || s.isBlank()) return JsonNodeFactory.instance.objectNode();
        try {
            JsonNode n = AgentTool.JSON.readTree(s);
            if (n instanceof ObjectNode o) return o;
        } catch (RuntimeException ignored) { }
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("_raw", s);   // 模型交来的不是合法 JSON：原样留着，参数校验那道闸会说清楚
        return o;
    }

    static String normalizeStop(String finish, boolean hasCalls) {
        if (finish == null) return hasCalls ? "tool_use" : "end_turn";
        String f = finish.toLowerCase();
        return switch (f) {
            case "length", "max_tokens" -> "max_tokens";
            case "tool_calls", "tool_use", "function_call" -> "tool_use";
            case "stop", "end_turn", "stop_sequence" -> hasCalls ? "tool_use" : "end_turn";
            default -> f;
        };
    }

    private static long nz(Number n) { return n == null ? 0 : n.longValue(); }

    /** SDK 原生 usage 上的字段（anthropic-java 是 {@code Optional<Long> cacheReadInputTokens()} 这种形状）；拿不到回 0。 */
    static long nativeLong(Object nat, String method) {
        if (nat == null) return 0;
        try {
            Object v = nat.getClass().getMethod(method).invoke(nat);
            if (v instanceof java.util.Optional<?> o) v = o.orElse(null);
            return v instanceof Number n ? n.longValue() : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** 统一成 Anthropic 口径：input 只是没命中缓存的那部分。 */
    static Usage toUsage(org.springframework.ai.chat.metadata.Usage u) {
        if (u == null) return Usage.ZERO;
        long prompt = nz(u.getPromptTokens());
        long out = nz(u.getCompletionTokens());
        long cr = nz(u.getCacheReadInputTokens());
        long cw = nz(u.getCacheWriteInputTokens());
        Object nat = u.getNativeUsage();
        boolean openAiShape = nat != null && nat.getClass().getName().contains("openai");
        long input = openAiShape ? Math.max(0, prompt - cr) : prompt;
        return new Usage(input, out, cr, cw, 0);
    }

    // ── 出错分类（给会话层的重试判据）─────────────────────────────────────

    static ModelCallException classify(RuntimeException e) {
        int status = 0;
        long retryAfter = -1;
        String kind = "other";
        for (Throwable t = e; t != null; t = t.getCause()) {
            Integer s = statusOf(t);
            if (s != null && s > 0) { status = s; kind = "http"; break; }
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException
                    || t.getClass().getSimpleName().contains("Timeout")) { kind = "timeout"; break; }
            if (t instanceof java.io.IOException) { kind = "io"; }
        }
        String msg = String.valueOf(e.getMessage());
        String low = msg.toLowerCase();
        if (low.contains("prompt is too long") || low.contains("context_length_exceeded")
                || low.contains("maximum context length") || low.contains("context window")) {
            kind = "context-overflow";
        }
        return new ModelCallException(kind, status, retryAfter, msg, e);
    }

    private static Integer statusOf(Throwable t) {
        try {
            var m = t.getClass().getMethod("statusCode");
            Object v = m.invoke(t);
            if (v instanceof Number n) return n.intValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        try {
            var m = t.getClass().getMethod("getStatusCode");
            Object v = m.invoke(t);
            if (v instanceof Number n) return n.intValue();
            if (v != null) {
                var vm = v.getClass().getMethod("value");
                Object x = vm.invoke(v);
                if (x instanceof Number n) return n.intValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }
}
