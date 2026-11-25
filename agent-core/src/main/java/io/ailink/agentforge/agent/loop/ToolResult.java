package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.tool.ToolOutput;

/**
 * 工具调用的结果 + 种类：{@code ok} / {@code error} / {@code denied}（审批拒绝）/ {@code bad-args} /
 * {@code unknown-tool} / {@code stopped}（按停，循环随即结束）/ {@code return-direct}。
 */
public record ToolResult(ToolOutput output, String kind) {
    public static ToolResult of(ToolOutput o) { return new ToolResult(o, o.error() ? "error" : "ok"); }

    public static ToolResult error(String text, String kind) { return new ToolResult(ToolOutput.error(text), kind); }

    public boolean error() { return output.error(); }

    public boolean stopped() { return "stopped".equals(kind); }
}
