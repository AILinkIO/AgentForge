package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 常用过滤器（coxswain：gate-filter / tool-tap-filter / chat-tap-filter）。 */
public final class Filters {
    private Filters() { }

    /** 审批闸：决定这次调用放不放行。 */
    @FunctionalInterface
    public interface Gate {
        GateDecision decide(Block.ToolUse call, AgentTool tool, ToolRequest req);
    }

    public static Filter gate(Gate gate) {
        return new Filter() {
            @Override public String name() { return "gate"; }

            @Override public ToolResult tool(ToolRequest req, ToolChain chain) {
                if (req.tool() == null) return chain.next(req);
                GateDecision d = gate.decide(req.toolUse(), req.tool(), req);
                return switch (d) {
                    case GateDecision.Allow a -> a.input() == null ? chain.next(req)
                            : chain.next(req.withToolUse(new Block.ToolUse(req.toolUse().id(), req.toolUse().name(), a.input())));
                    case GateDecision.Deny dn -> ToolResult.error(
                            dn.reason() == null || dn.reason().isBlank() ? "用户拒绝了这次调用。换个做法，或者先问问用户。" : dn.reason(), "denied");
                    case GateDecision.Stop s -> ToolResult.error("用户停掉了这一轮。", "stopped");
                };
            }
        };
    }

    public static Filter toolBefore(Consumer<ToolRequest> f) {
        return new Filter() {
            @Override public String name() { return "tool-tap-before"; }

            @Override public ToolResult tool(ToolRequest req, ToolChain chain) { f.accept(req); return chain.next(req); }
        };
    }

    public static Filter toolAfter(BiConsumer<ToolRequest, ToolResult> f) {
        return new Filter() {
            @Override public String name() { return "tool-tap-after"; }

            @Override public ToolResult tool(ToolRequest req, ToolChain chain) {
                ToolResult r = chain.next(req);
                f.accept(req, r);
                return r;
            }
        };
    }

    public static Filter chatAfter(BiConsumer<ChatRequest, ModelTurn> f) {
        return new Filter() {
            @Override public String name() { return "chat-tap-after"; }

            @Override public ModelTurn chat(ChatRequest req, ChatChain chain) {
                ModelTurn t = chain.next(req);
                f.accept(req, t);
                return t;
            }
        };
    }

    /** 改写请求（压缩 / 裁剪 / 注入提醒用）。 */
    public static Filter chatRewrite(String name, java.util.function.UnaryOperator<ChatRequest> f) {
        return new Filter() {
            @Override public String name() { return name; }

            @Override public ModelTurn chat(ChatRequest req, ChatChain chain) { return chain.next(f.apply(req)); }
        };
    }

    /** 包一层工具调用（例如在调用期间设置线程上下文）。 */
    public static Filter toolAround(String name, BiFunction<ToolRequest, Filter.ToolChain, ToolResult> f) {
        return new Filter() {
            @Override public String name() { return name; }

            @Override public ToolResult tool(ToolRequest req, ToolChain chain) { return f.apply(req, chain); }
        };
    }
}
