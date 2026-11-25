package io.ailink.agentforge.agent.model;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 消息里的一块内容（L1 消息模型）。
 *
 * <p>与 coxswain {@code (keel agent-framework message)} 同构：文本、图片、文档、思考、
 * 工具调用、工具结果六种。provider 编解码只认这六种。
 */
public sealed interface Block permits Block.Text, Block.Image, Block.Document, Block.Thinking,
        Block.ToolUse, Block.ToolResult {

    record Text(String text) implements Block {
        public Text { text = text == null ? "" : text; }
    }

    /** base64 编码的图片。 */
    record Image(String mediaType, String data) implements Block { }

    /** base64 编码的文档（目前只有 application/pdf）。 */
    record Document(String mediaType, String data) implements Block { }

    /**
     * 思考段。{@code signature} 是 Anthropic 回传校验用的；{@code redacted} 为真时
     * {@code text} 存的是加密数据。
     */
    record Thinking(String text, String signature, boolean redacted) implements Block {
        public Thinking(String text) { this(text, null, false); }
    }

    record ToolUse(String id, String name, ObjectNode input) implements Block { }

    /** content 是块表（文本 / 图片 / 文档）。 */
    record ToolResult(String toolUseId, List<Block> content, boolean error) implements Block {
        public ToolResult { content = content == null ? List.of() : List.copyOf(content); }

        public static ToolResult text(String id, String text, boolean error) {
            return new ToolResult(id, List.of(new Text(text)), error);
        }
    }

    static Text text(String s) { return new Text(s); }

    /** 块表里所有文本块拼起来（tool_result 的内容当字符串用时只有这一个答案）。 */
    static String joinText(List<Block> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof Text t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** 粗略字符数（用于 token 估算）：图片/文档按固定量计。 */
    static long chars(Block b) {
        return switch (b) {
            case Text t -> t.text().length();
            case Image i -> 6000;           // ≈1600 token
            case Document d -> 12000;
            case Thinking th -> th.redacted() ? 0 : th.text().length();
            case ToolUse u -> u.name().length() + (u.input() == null ? 0 : u.input().toString().length());
            case ToolResult r -> r.content().stream().mapToLong(Block::chars).sum();
        };
    }
}
