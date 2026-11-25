package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.ToolOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 远端工具描述 → 本地条目，以及 tools/call 结果 → {@link ToolOutput}。纯函数，离线可测。
 *
 * <p>对应 keel 的 {@code mcp-fetch-tools}：名字消毒（{@code [^A-Za-z0-9_-] → _}）+ 前缀 + 64 字符封顶
 * + 同名加 {@code _2}；schema 不是 object 的<b>拒收</b>并记下理由（界面上要看得见 —— 一件模型死活
 * 调不到的工具，用户唯一的线索就是那里）。
 */
public final class McpTools {
    private McpTools() { }

    public static final int MAX_NAME = 64;
    /** resource blob 只收这么大（base64 前约 10 MB，opencode 同）。 */
    static final int MAX_BLOB_CHARS = 14_000_000;

    /**
     * 一件远端工具。{@code readOnly} = {@code annotations.readOnlyHint} 为 true（缺席 / false 都当会写）。
     *
     * <p>record 的 equals 就是"目录身份"：名字 + 远端名 + 说明 + schema + annotations。⚠️ schema 与
     * annotations 都算数 —— 服务器升级后参数变了、或一件工具从只读改成会写，都必须重造 stub。
     */
    public record Entry(String localName, String remoteName, String description, ObjectNode schema,
                        JsonNode annotations, boolean readOnly) { }

    public record Rejected(String remoteName, String reason) { }

    public record Catalog(List<Entry> entries, List<Rejected> rejected) { }

    public static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    ? c : '_');
        }
        return b.toString();
    }

    public static Catalog catalog(String prefix, List<JsonNode> descriptors) {
        List<Entry> out = new ArrayList<>();
        List<Rejected> bad = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (JsonNode d : descriptors) {
            JsonNode n = d.get("name");
            if (n == null || !n.isString() || n.asString().isEmpty()) {
                bad.add(new Rejected(String.valueOf(n), "没有名字"));
                continue;
            }
            String remote = n.asString();
            JsonNode s = d.get("inputSchema");
            if (s != null && !s.isNull() && !s.isObject()) {
                bad.add(new Rejected(remote, "inputSchema 不是一个 JSON 对象"));
                continue;
            }
            ObjectNode schema = s instanceof ObjectNode o ? o.deepCopy() : JsonNodeFactory.instance.objectNode();
            JsonNode type = schema.get("type");
            if (type != null && !(type.isString() && "object".equals(type.asString()))) {
                bad.add(new Rejected(remote, "inputSchema 的 type 是 " + type + "，各家 API 只收 object"));
                continue;
            }
            if (type == null) schema.put("type", "object");
            if (!schema.has("properties")) schema.putObject("properties");
            String local = uniqueName(prefix + sanitize(remote), used);
            used.add(local);
            JsonNode desc = d.get("description");
            JsonNode ann = d.get("annotations");
            JsonNode roh = ann == null ? null : ann.get("readOnlyHint");
            boolean ro = roh != null && roh.isBoolean() && roh.asBoolean();
            out.add(new Entry(local, remote, desc != null && desc.isString() ? desc.asString() : "", schema,
                    ann == null || ann.isNull() ? null : ann.deepCopy(), ro));
        }
        return new Catalog(List.copyOf(out), List.copyOf(bad));
    }

    private static String uniqueName(String base, Set<String> used) {
        String b = base.length() > MAX_NAME ? base.substring(0, MAX_NAME) : base;
        if (!used.contains(b)) return b;
        for (int i = 2; ; i++) {
            String suffix = "_" + i;
            String head = b.length() + suffix.length() > MAX_NAME ? b.substring(0, MAX_NAME - suffix.length()) : b;
            if (!used.contains(head + suffix)) return head + suffix;
        }
    }

    /**
     * tools/call 的结果 → 工具结果块表：{@code text} 原样；{@code image} → 图片块（进多模态通道）；
     * {@code resource} 有 text 就是文本、blob 是 PDF → 文档块、图片 → 图片块，其余写一行占位；
     * {@code resource_link} / {@code audio} 写一行说明。没有 content 但有 structuredContent 时给它的 JSON。
     */
    public static ToolOutput toOutput(JsonNode result) {
        List<Block> blocks = new ArrayList<>();
        JsonNode content = result == null ? null : result.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode c : content) blocks.add(block(c));
        }
        if (blocks.isEmpty() && result != null) {
            JsonNode sc = result.get("structuredContent");
            if (sc != null && !sc.isNull()) blocks.add(new Block.Text(sc.toString()));
        }
        JsonNode err = result == null ? null : result.get("isError");
        return new ToolOutput(blocks, err != null && err.asBoolean(false));
    }

    private static Block block(JsonNode c) {
        String type = text(c, "type");
        switch (type == null ? "" : type) {
            case "text" -> {
                return new Block.Text(text(c, "text"));
            }
            case "image" -> {
                String data = text(c, "data");
                String mime = text(c, "mimeType");
                if (data != null && mime != null && data.length() <= MAX_BLOB_CHARS) return new Block.Image(mime, data);
                return new Block.Text("[图片（" + mime + "），太大或缺数据，没有附上]");
            }
            case "audio" -> {
                return new Block.Text("[音频（" + text(c, "mimeType") + "），没有转写]");
            }
            case "resource" -> {
                JsonNode r = c.get("resource");
                if (r == null) return new Block.Text("[空资源]");
                String uri = text(r, "uri");
                String mime = text(r, "mimeType");
                String t = text(r, "text");
                if (t != null) return new Block.Text(t);
                String blob = text(r, "blob");
                if (blob != null && blob.length() <= MAX_BLOB_CHARS && mime != null) {
                    if (mime.equals("application/pdf")) return new Block.Document(mime, blob);
                    if (mime.matches("image/(png|jpeg|gif|webp)")) return new Block.Image(mime, blob);
                }
                return new Block.Text("[资源 " + uri + "（" + mime + "），二进制内容没有附上]");
            }
            case "resource_link" -> {
                return new Block.Text("[资源链接] " + (text(c, "name") == null ? "" : text(c, "name") + " ")
                        + text(c, "uri"));
            }
            default -> {
                return new Block.Text(c.toString());
            }
        }
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v != null && v.isString() ? v.asString() : null;
    }
}
