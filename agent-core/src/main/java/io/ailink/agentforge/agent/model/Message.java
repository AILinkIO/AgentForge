package io.ailink.agentforge.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一条消息：角色 + 块表 + 不发给模型的元数据。
 *
 * <p>系统提示不在消息表里，单独随请求走。工具结果是一条 user 消息里的若干
 * {@link Block.ToolResult}。{@code meta} 只在本进程里用（例如标记"压缩通告"、
 * "提醒"），编解码时忽略。
 */
public record Message(Role role, List<Block> blocks, Map<String, Object> meta) {

    public Message {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    public Message(Role role, List<Block> blocks) { this(role, blocks, Map.of()); }

    public static Message user(String text) { return new Message(Role.USER, List.of(new Block.Text(text))); }

    public static Message user(List<Block> blocks) { return new Message(Role.USER, blocks); }

    public static Message assistant(String text) { return new Message(Role.ASSISTANT, List.of(new Block.Text(text))); }

    public Message withMeta(String k, Object v) {
        var m = new java.util.HashMap<>(meta);
        m.put(k, v);
        return new Message(role, blocks, m);
    }

    public Message withBlocks(List<Block> bs) { return new Message(role, bs, meta); }

    public Object meta(String k) { return meta.get(k); }

    public boolean isUser() { return role == Role.USER; }

    public boolean isAssistant() { return role == Role.ASSISTANT; }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) if (b instanceof Block.Text t) sb.append(t.text());
        return sb.toString();
    }

    public List<Block.ToolUse> toolUses() {
        List<Block.ToolUse> out = new ArrayList<>();
        for (Block b : blocks) if (b instanceof Block.ToolUse u) out.add(u);
        return out;
    }

    public List<Block.ToolResult> toolResults() {
        List<Block.ToolResult> out = new ArrayList<>();
        for (Block b : blocks) if (b instanceof Block.ToolResult r) out.add(r);
        return out;
    }

    public boolean hasToolResults() { return blocks.stream().anyMatch(b -> b instanceof Block.ToolResult); }

    public long chars() { return blocks.stream().mapToLong(Block::chars).sum(); }
}
