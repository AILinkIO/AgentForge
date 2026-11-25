package io.ailink.agentforge.agent.store;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import io.ailink.agentforge.agent.tool.AgentTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 消息 ↔ JSON（存盘用的中立序列化）。
 *
 * <p>⚠️ **存块，不存文本**：thinking 的签名、tool_use 的 id 一个不少。只存文本的话读回来的对话**不能接着聊** ——
 * 签名丢了服务端会拒、id 丢了工具结果对不上，而且两件事都要到"恢复之后的第二轮"才报错。
 *
 * <p>形状：{@code {"role":"user|assistant","blocks":[…],"meta":{…}}}；块：
 * {@code text{text}} / {@code image{media_type,data}} / {@code document{media_type,data}} /
 * {@code thinking{thinking,signature?,redacted?}} / {@code tool_use{id,name,input}} /
 * {@code tool_result{tool_use_id,content[],is_error?}}。meta 只存字符串 / 数字 / 布尔值（别的类型只活在进程里）。
 */
public final class MessageCodec {
    private MessageCodec() { }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static ObjectNode encode(Message m) {
        ObjectNode o = F.objectNode();
        o.put("role", m.role().wire());
        ArrayNode bs = o.putArray("blocks");
        for (Block b : m.blocks()) bs.add(encodeBlock(b));
        if (!m.meta().isEmpty()) {
            ObjectNode meta = F.objectNode();
            for (Map.Entry<String, Object> e : m.meta().entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) meta.put(e.getKey(), s);
                else if (v instanceof Boolean b) meta.put(e.getKey(), b);
                else if (v instanceof Integer n) meta.put(e.getKey(), n);
                else if (v instanceof Long n) meta.put(e.getKey(), n);
                else if (v instanceof Double n) meta.put(e.getKey(), n);
            }
            if (!meta.isEmpty()) o.set("meta", meta);
        }
        return o;
    }

    public static ObjectNode encodeBlock(Block b) {
        return switch (b) {
            case Block.Text t -> F.objectNode().put("type", "text").put("text", t.text());
            case Block.Image i -> F.objectNode().put("type", "image").put("media_type", i.mediaType()).put("data", i.data());
            case Block.Document d -> F.objectNode().put("type", "document").put("media_type", d.mediaType()).put("data", d.data());
            case Block.Thinking th -> {
                ObjectNode n = F.objectNode().put("type", "thinking").put("thinking", th.text());
                if (th.signature() != null) n.put("signature", th.signature());
                if (th.redacted()) n.put("redacted", true);
                yield n;
            }
            case Block.ToolUse u -> {
                ObjectNode n = F.objectNode().put("type", "tool_use").put("id", u.id()).put("name", u.name());
                n.set("input", u.input() == null ? F.objectNode() : u.input());
                yield n;
            }
            case Block.ToolResult r -> {
                ObjectNode n = F.objectNode().put("type", "tool_result").put("tool_use_id", r.toolUseId());
                ArrayNode c = n.putArray("content");
                for (Block x : r.content()) c.add(encodeBlock(x));
                if (r.error()) n.put("is_error", true);
                yield n;
            }
        };
    }

    public static Message decode(JsonNode o) {
        Role role = "assistant".equals(o.path("role").asString("")) ? Role.ASSISTANT : Role.USER;
        List<Block> blocks = new ArrayList<>();
        for (JsonNode b : o.path("blocks")) {
            Block x = decodeBlock(b);
            if (x != null) blocks.add(x);
        }
        Map<String, Object> meta = new java.util.HashMap<>();
        JsonNode mn = o.get("meta");
        if (mn != null && mn.isObject()) {
            for (var e : mn.properties()) {
                JsonNode v = e.getValue();
                if (v.isString()) meta.put(e.getKey(), v.asString());
                else if (v.isBoolean()) meta.put(e.getKey(), v.asBoolean());
                else if (v.isIntegralNumber()) meta.put(e.getKey(), v.asLong());
                else if (v.isNumber()) meta.put(e.getKey(), v.asDouble());
            }
        }
        return new Message(role, blocks, meta);
    }

    /** 认不出的块类型 → null（丢掉，而不是让整段对话读不回来）。 */
    public static Block decodeBlock(JsonNode b) {
        return switch (b.path("type").asString("")) {
            case "text" -> new Block.Text(b.path("text").asString(""));
            case "image" -> new Block.Image(b.path("media_type").asString(""), b.path("data").asString(""));
            case "document" -> new Block.Document(b.path("media_type").asString(""), b.path("data").asString(""));
            case "thinking" -> new Block.Thinking(b.path("thinking").asString(""),
                    b.has("signature") ? b.get("signature").asString() : null, b.path("redacted").asBoolean(false));
            case "tool_use" -> new Block.ToolUse(b.path("id").asString(""), b.path("name").asString(""),
                    b.get("input") instanceof ObjectNode in ? in : F.objectNode());
            case "tool_result" -> {
                List<Block> c = new ArrayList<>();
                for (JsonNode x : b.path("content")) {
                    Block y = decodeBlock(x);
                    if (y != null) c.add(y);
                }
                yield new Block.ToolResult(b.path("tool_use_id").asString(""), c, b.path("is_error").asBoolean(false));
            }
            default -> null;
        };
    }

    public static String toJson(Message m) { return encode(m).toString(); }

    public static Message fromJson(String s) { return decode(AgentTool.JSON.readTree(s)); }

    public static String toJson(List<Message> ms) {
        ArrayNode a = F.arrayNode();
        for (Message m : ms) a.add(encode(m));
        return a.toString();
    }

    public static List<Message> listFromJson(String s) {
        List<Message> out = new ArrayList<>();
        for (JsonNode n : AgentTool.JSON.readTree(s)) out.add(decode(n));
        return out;
    }
}
