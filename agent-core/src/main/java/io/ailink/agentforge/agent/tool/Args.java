package io.ailink.agentforge.agent.tool;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型交来的参数（已经按声明补过默认值）。取值失败抛 {@link ToolException}，文字写给模型看。
 */
public final class Args {
    private final ObjectNode raw;

    public Args(ObjectNode raw) { this.raw = raw; }

    public ObjectNode raw() { return raw; }

    public boolean has(String k) { return raw.has(k) && !raw.get(k).isNull(); }

    public JsonNode node(String k) { return raw.get(k); }

    public String str(String k) {
        JsonNode n = raw.get(k);
        if (n == null || n.isNull()) return null;
        return n.isString() ? n.asString() : n.toString();
    }

    public String str(String k, String dflt) { String s = str(k); return s == null ? dflt : s; }

    public String requireStr(String k) {
        String s = str(k);
        if (s == null) throw new ToolException("缺少参数 " + k);
        return s;
    }

    public long integer(String k, long dflt) {
        JsonNode n = raw.get(k);
        if (n == null || n.isNull()) return dflt;
        if (n.isNumber()) return n.asLong();
        if (n.isString()) {
            try { return Long.parseLong(n.asString().trim()); } catch (NumberFormatException e) {
                throw new ToolException("参数 " + k + " 应当是整数，收到的是 " + n);
            }
        }
        throw new ToolException("参数 " + k + " 应当是整数，收到的是 " + n);
    }

    public int intValue(String k, int dflt) { return (int) integer(k, dflt); }

    public double number(String k, double dflt) {
        JsonNode n = raw.get(k);
        if (n == null || n.isNull()) return dflt;
        if (n.isNumber()) return n.asDouble();
        try { return Double.parseDouble(n.asString().trim()); } catch (NumberFormatException e) {
            throw new ToolException("参数 " + k + " 应当是数字，收到的是 " + n);
        }
    }

    public boolean bool(String k, boolean dflt) {
        JsonNode n = raw.get(k);
        if (n == null || n.isNull()) return dflt;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isString()) return Boolean.parseBoolean(n.asString().trim());
        return dflt;
    }

    public List<String> strings(String k) {
        JsonNode n = raw.get(k);
        List<String> out = new ArrayList<>();
        if (n == null || n.isNull()) return out;
        if (n.isArray()) for (JsonNode e : n) out.add(e.isString() ? e.asString() : e.toString());
        else out.add(n.isString() ? n.asString() : n.toString());
        return out;
    }
}
