package io.ailink.agentforge.agent.loop;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;

/** 一次工具调用请求；{@code tool} 为 null 表示模型叫了一件不存在 / 这一趟不给的工具。 */
public record ToolRequest(Block.ToolUse toolUse, AgentTool tool, ToolEnv env, FilterCtx ctx) {
    public ToolRequest withToolUse(Block.ToolUse tu) { return new ToolRequest(tu, tool, env, ctx); }
}
