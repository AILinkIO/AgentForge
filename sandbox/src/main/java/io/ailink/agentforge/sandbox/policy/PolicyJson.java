package io.ailink.agentforge.sandbox.policy;

import io.ailink.agentforge.sandbox.ErrorKind;
import io.ailink.agentforge.sandbox.SandboxException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 策略 JSON（与 C# / keelson 版逐字节兼容）：字段 snake_case、不缩进、字段次序固定；串的转义照 System.Text.Json 的缺省编码器
 * （非 ASCII 与 {@code " & ' + < > `} 写成 {@code \}{@code uXXXX}）—— 写出来的串跨实现一致，可做缓存键 / 比对。
 * 读：未知字段忽略，缺的字段取缺省；枚举名不分大小写，也收整数序号。格式错 ⇒ {@link ErrorKind#JSON}（码 {@code sandbox/invalid-policy-json}）。
 */
public final class PolicyJson {
    private PolicyJson() {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    // ── 写 ───────────────────────────────────────────────────────────

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7E || "\"&'+<>`".indexOf(c) >= 0) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static void path(StringBuilder sb, PolicyPath p) {
        switch (p) {
            case PolicyPath.Literal l -> {
                sb.append("{\"type\":\"path\",\"path\":");
                str(sb, l.path());
            }
            case PolicyPath.GlobPattern g -> {
                sb.append("{\"type\":\"glob_pattern\",\"pattern\":");
                str(sb, g.pattern());
            }
            case PolicyPath.Special s -> {
                sb.append("{\"type\":\"special\",\"kind\":");
                str(sb, s.kind().jsonName());
                if (s.subpath() != null) {
                    sb.append(",\"subpath\":");
                    str(sb, s.subpath());
                }
            }
        }
        sb.append('}');
    }

    private static void strings(StringBuilder sb, List<String> xs) {
        sb.append('[');
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(',');
            str(sb, xs.get(i));
        }
        sb.append(']');
    }

    /** 策略 ⇒ JSON 串（字段次序同 C#）。 */
    public static String write(SandboxPolicy pol) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"file_system\":");
        str(sb, pol.fileSystem().jsonName());
        sb.append(",\"entries\":[");
        for (int i = 0; i < pol.entries().size(); i++) {
            PolicyEntry e = pol.entries().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"path\":");
            path(sb, e.path());
            sb.append(",\"access\":");
            str(sb, e.access().jsonName());
            sb.append('}');
        }
        sb.append("],\"network\":");
        str(sb, pol.network().jsonName());
        sb.append(",\"protected_names\":");
        strings(sb, pol.protectedNames());
        sb.append(",\"host_metadata_names\":");
        strings(sb, pol.hostMetadataNames());
        sb.append('}');
        return sb.toString();
    }

    // ── 读 ───────────────────────────────────────────────────────────

    private static SandboxException invalid(String detail) {
        return SandboxException.of(ErrorKind.JSON, "sandbox/invalid-policy-json", detail);
    }

    private static String describe(JsonNode v) {
        if (v == null || v.isNull()) return "null";
        return v.isString() ? "\"" + v.stringValue() + "\"" : v.toString();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode v, E[] values, java.util.function.Function<E, String> name, String where) {
        if (v != null && v.isString()) {
            for (E e : values) if (name.apply(e).equalsIgnoreCase(v.stringValue())) return e;
        } else if (v != null && v.isIntegralNumber() && v.canConvertToInt()) {
            int i = v.intValue();
            if (i >= 0 && i < values.length) return values[i];
        }
        throw invalid(where + ": invalid value " + describe(v));
    }

    private static JsonNode object(JsonNode v, String where) {
        if (v == null || !v.isObject()) throw invalid(where + ": expected an object");
        return v;
    }

    private static JsonNode array(JsonNode v, String where) {
        if (v == null || !v.isArray()) throw invalid(where + ": expected an array");
        return v;
    }

    private static String string(JsonNode v, String where) {
        if (v == null || !v.isString()) throw invalid(where + ": expected a string");
        return v.stringValue();
    }

    private static String requiredString(JsonNode o, String k) {
        JsonNode v = o.get(k);
        if (v == null || v.isNull()) throw invalid("$.entries[].path." + k + ": required");
        return string(v, "$.entries[].path." + k);
    }

    private static PolicyPath readPath(JsonNode v) {
        JsonNode o = object(v, "$.entries[].path");
        JsonNode t = o.get("type");
        if (t == null || !t.isString()) throw invalid("$.entries[].path.type: missing type discriminator");
        return switch (t.stringValue()) {
            case "path" -> PolicyPath.literal(requiredString(o, "path"));
            case "glob_pattern" -> PolicyPath.glob(requiredString(o, "pattern"));
            case "special" -> {
                SpecialKind k = o.has("kind") ? enumValue(o.get("kind"), SpecialKind.values(), SpecialKind::jsonName, "$.entries[].path.kind")
                        : SpecialKind.ROOT;
                JsonNode sub = o.get("subpath");
                yield PolicyPath.special(k, sub == null || sub.isNull() ? null : string(sub, "$.entries[].path.subpath"));
            }
            default -> throw invalid("$.entries[].path.type: unknown type discriminator '" + t.stringValue() + "'");
        };
    }

    private static PolicyEntry readEntry(JsonNode v) {
        JsonNode o = object(v, "$.entries[]");
        if (!o.has("path")) throw invalid("$.entries[].path: required");
        PolicyPath path = readPath(o.get("path"));
        AccessMode access = o.has("access") ? enumValue(o.get("access"), AccessMode.values(), AccessMode::jsonName, "$.entries[].access")
                : AccessMode.READ;
        return new PolicyEntry(path, access);
    }

    private static List<String> stringList(JsonNode v, String where) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : array(v, where)) out.add(string(x, where + "[]"));
        return out;
    }

    /** JSON 串 ⇒ 策略；缺的字段取缺省（{@link SandboxPolicy#DEFAULT}）。 */
    public static SandboxPolicy read(String json) {
        Objects.requireNonNull(json, "json");
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new SandboxException(ErrorKind.JSON, io.ailink.agentforge.sandbox.Messages.message("sandbox/invalid-policy-json",
                    e.getOriginalMessage()), e);
        }
        if (root == null || root.isNull() || root.isMissingNode()) throw invalid("null");
        JsonNode o = object(root, "$");
        SandboxPolicy pol = SandboxPolicy.DEFAULT;
        for (Map.Entry<String, JsonNode> f : o.properties()) {
            JsonNode v = f.getValue();
            boolean isNull = v == null || v.isNull();
            switch (f.getKey()) {
                case "file_system" -> pol = new SandboxPolicy(enumValue(v, FileSystemMode.values(), FileSystemMode::jsonName, "$.file_system"),
                        pol.entries(), pol.network(), pol.protectedNames(), pol.hostMetadataNames());
                case "network" -> pol = pol.withNetwork(enumValue(v, NetworkMode.values(), NetworkMode::jsonName, "$.network"));
                case "entries" -> {
                    if (!isNull) {
                        List<PolicyEntry> es = new ArrayList<>();
                        for (JsonNode e : array(v, "$.entries")) es.add(readEntry(e));
                        pol = pol.withEntries(es);
                    }
                }
                case "protected_names" -> {
                    if (!isNull) pol = pol.withProtectedNames(stringList(v, "$.protected_names"));
                }
                case "host_metadata_names" -> {
                    if (!isNull) pol = pol.withHostMetadataNames(stringList(v, "$.host_metadata_names"));
                }
                default -> {
                    // 未知字段忽略
                }
            }
        }
        return pol;
    }
}
