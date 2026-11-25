package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.codemode.Datum.Hashtable;
import io.ailink.agentforge.agent.codemode.Datum.Nil;
import io.ailink.agentforge.agent.codemode.Datum.Pair;
import io.ailink.agentforge.agent.codemode.Datum.SChar;
import io.ailink.agentforge.agent.codemode.Datum.Sym;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON ↔ 脚本值（keel 的约定）：对象 = alist {@code (("k" . v) …)}，数组 = 向量，{@code null} = 符号 {@code null}，
 * true/false = {@code #t}/{@code #f}。反方向时 {@code '()} 当空对象（与工具参数 {@code '()} 同义），
 * 不是 alist 的真表当数组（宽容）。
 */
final class SJson {
    private SJson() { }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final Sym NULL = Sym.of("null");

    static Object parse(Interp in, String text) {
        JsonNode n;
        try {
            n = JSON.readTree(text);
        } catch (JacksonException e) {
            throw Interp.err("json-parse", "不是合法的 JSON：" + e.getOriginalMessage());
        }
        if (n == null || n.isMissingNode()) throw Interp.err("json-parse", "不是合法的 JSON：空的");
        return fromNode(in, n);
    }

    static Object fromNode(Interp in, JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return NULL;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isIntegralNumber()) return n.canConvertToLong() ? (Object) n.asLong() : (Object) n.asDouble();
        if (n.isNumber()) return n.asDouble();
        if (n.isString()) return n.asString();
        if (n.isArray()) {
            Object[] v = new Object[n.size()];
            if (in != null) in.alloc(v.length);
            for (int k = 0; k < v.length; k++) v[k] = fromNode(in, n.get(k));
            return v;
        }
        List<Object> pairs = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : n.properties()) pairs.add(new Pair(e.getKey(), fromNode(in, e.getValue())));
        if (in != null) in.alloc(pairs.size() * 2L);
        return Datum.fromList(pairs);
    }

    static String stringify(Object v) { return toNode(v).toString(); }

    static JsonNode toNode(Object v) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (v instanceof Boolean b) return f.booleanNode(b);
        if (v instanceof Long l) return f.numberNode(l);
        if (v instanceof Double d) return d.isNaN() || d.isInfinite() ? f.nullNode() : f.numberNode(d);
        if (v instanceof String s) return f.stringNode(s);
        if (v == NULL || v == Datum.Void.VOID) return f.nullNode();
        if (v instanceof Sym s) return f.stringNode(s.name);
        if (v instanceof SChar c) return f.stringNode(new String(Character.toChars(c.cp())));
        if (v == Nil.NIL) return f.objectNode();
        if (v instanceof Object[] a) {
            ArrayNode arr = f.arrayNode();
            for (Object x : a) arr.add(toNode(x));
            return arr;
        }
        if (v instanceof Hashtable h) {
            ObjectNode o = f.objectNode();
            for (Object[] e : h.map.values()) o.set(key(e[0]), toNode(e[1]));
            return o;
        }
        if (v instanceof Pair) {
            List<Object> l = Datum.toList(v);
            if (l == null) throw Interp.err("json-stringify", Printer.write(v) + " 不是真表，转不成 JSON");
            boolean alist = l.stream().allMatch(x -> x instanceof Pair p && (p.car instanceof String || p.car instanceof Sym));
            if (alist) {
                ObjectNode o = f.objectNode();
                for (Object x : l) o.set(key(((Pair) x).car), toNode(((Pair) x).cdr));
                return o;
            }
            ArrayNode arr = f.arrayNode();
            for (Object x : l) arr.add(toNode(x));
            return arr;
        }
        throw Interp.err("json-stringify", Printer.write(v) + " 转不成 JSON");
    }

    private static String key(Object k) {
        if (k instanceof String s) return s;
        if (k instanceof Sym s) return s.name;
        return Printer.display(k);
    }
}
