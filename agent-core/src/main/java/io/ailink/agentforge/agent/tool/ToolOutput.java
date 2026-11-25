package io.ailink.agentforge.agent.tool;

import io.ailink.agentforge.agent.model.Block;

import java.util.List;

/** 工具结果：块表（文本 / 图片 / 文档）+ 是否出错。 */
public record ToolOutput(List<Block> content, boolean error) {

    public ToolOutput {
        content = content == null ? List.of() : List.copyOf(content);
    }

    public static ToolOutput text(String s) { return new ToolOutput(List.of(new Block.Text(s)), false); }

    public static ToolOutput error(String s) { return new ToolOutput(List.of(new Block.Text(s)), true); }

    public static ToolOutput blocks(List<Block> blocks) { return new ToolOutput(blocks, false); }

    public String text() { return Block.joinText(content); }

    /** handler 可以返回 String / List&lt;Block&gt; / ToolOutput，这里统一成 ToolOutput。 */
    @SuppressWarnings("unchecked")
    public static ToolOutput of(Object o) {
        if (o == null) return text("");
        if (o instanceof ToolOutput t) return t;
        if (o instanceof String s) return text(s);
        if (o instanceof Block b) return blocks(List.of(b));
        if (o instanceof List<?> l) return blocks((List<Block>) l);
        return text(String.valueOf(o));
    }
}
