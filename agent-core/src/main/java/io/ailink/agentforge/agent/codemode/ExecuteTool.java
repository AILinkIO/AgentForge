package io.ailink.agentforge.agent.codemode;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Param;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.ToolOutput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code execute}：一段脚本调几件 MCP 工具。对应 coxswain {@code (coxn agent tools execute)}。
 *
 * <ul>
 *   <li><b>目录进描述</b>：每件 {@code server.tool  {参数: 类型 …}  一句说明} 一行。描述是构造期定的，
 *       所以 MCP 目录一变宿主就要重造这件工具（{@link #create} 每次拼工具表时调一次即可）。</li>
 *   <li><b>只在有 MCP 工具时才造</b>（空表回 null；opencode 同）：没有可调的工具，它就是一段能跑的
 *       Scheme —— 那不是它存在的理由。</li>
 *   <li><b>sensitive 当且仅当目录里有会写的工具</b>：脚本一次能调几十件，审批只问一次，预览就是脚本正文。
 *       它本身永远不是 readOnly（它能调目录里的任何一件）。</li>
 *   <li>名字：{@code server.tool}（opencode 的写法）或 {@code server_tool} 都认 —— 第一个 {@code .} 换成
 *       {@code _} 再查。</li>
 *   <li>结果：最后一个表达式的值 + logs + 调过哪些；出错（脚本错、超时、超次数）给一行诊断，
 *       作为 is_error 的工具结果。</li>
 * </ul>
 */
public final class ExecuteTool {
    private ExecuteTool() { }

    public static final String NAME = "execute";
    static final int MAX_DESC = 120;

    /** MCP 工具 + （有的话）execute。宿主拼工具表时的便捷写法。 */
    public static List<AgentTool> withExecute(List<AgentTool> mcpTools) {
        List<AgentTool> out = new ArrayList<>(mcpTools);
        AgentTool x = create(mcpTools);
        if (x != null) out.add(x);
        return out;
    }

    /** @return execute 工具；{@code tools} 为空时 null */
    public static AgentTool create(List<AgentTool> tools) { return create(tools, CodeMode.Limits.DEFAULT); }

    public static AgentTool create(List<AgentTool> tools, CodeMode.Limits limits) {
        if (tools == null || tools.isEmpty()) return null;
        List<AgentTool> catalogTools = List.copyOf(tools);
        boolean sensitive = catalogTools.stream().anyMatch(t -> !t.readOnly());
        String description = "跑一段 Scheme 脚本，脚本里用 (call \"server.tool\" '((\"参数\" . 值) …)) 调下面这些 MCP 工具——"
                + "要连着调好几次、按上一步的结果决定下一步、或者对一批东西循环时用它，一次代替十次往返。\n"
                + "环境只有 rnrs 的纯函数 + call / log! / json-parse / json-stringify / format / "
                + "string-split / string-join / string-trim / string-contains? / string-prefix? / string-suffix? / ref（alist 取值）；"
                + "没有文件、网络、进程。最后一个表达式的值就是结果（字符串原样，别的 write 出来）；"
                + "(log! \"…\") 记的行也一起给你。call 的结果：JSON 能解就是 alist（用 ref 取）/ 向量，否则是串；"
                + "工具失败会抛错，guard 接得住。限：" + limits.timeoutMs() / 1000 + " 秒、" + limits.maxCalls()
                + " 次调用、" + limits.maxOutput() / 1024 + " KB 输出。\n"
                + "能调的工具（server.tool  {参数} 说明）：\n"
                + catalog(catalogTools);
        return AgentTool.builder(NAME)
                .description(description)
                .param(Param.required("code", "string", "脚本正文（Scheme，一个或多个表达式）"))
                .readOnly(false)
                .concurrencySafe(false)
                .sensitive(sensitive)
                .handler((env, args) -> run(catalogTools, limits, env, args.str("code")))
                .build();
    }

    private static String run(List<AgentTool> tools, CodeMode.Limits limits, ToolEnv env, String code) {
        if (code == null || code.isEmpty()) throw new ToolException("code 是空的");
        CodeMode.Caller caller = (name, cargs, cancel) -> {
            AgentTool t = resolveName(tools, name);
            if (t == null) throw new ToolException("没有叫 \"" + name + "\" 的工具（看 execute 描述里的目录）");
            ObjectNode input = cargs instanceof ObjectNode o ? o : JsonNodeFactory.instance.objectNode();
            ToolOutput out = t.execute(env.withCall(env.toolUseId(), cancel), input);
            if (out.error()) throw new ToolException(name + " 失败：" + joinBlocks(out.content()));
            return resultValue(joinBlocks(out.content()));
        };
        CodeModeResult r = CodeMode.run(code, caller, limits, env.cancel());
        StringBuilder tail = new StringBuilder();
        if (!r.logs().isEmpty()) tail.append("\n--- log ---\n").append(String.join("\n", r.logs()));
        if (!r.calls().isEmpty()) {
            tail.append("\n--- 调了 ").append(r.calls().size()).append(" 次：").append(String.join(", ", r.calls()));
        }
        if (r.ok()) return (r.value().isEmpty() ? "（没有返回值）" : r.value()) + tail;
        throw new ToolException("脚本出错：" + r.error() + tail);
    }

    /** 块表 → 文本：文本块拼起来，非文本块写一个占位。 */
    private static String joinBlocks(List<Block> blocks) {
        List<String> parts = new ArrayList<>();
        for (Block b : blocks) parts.add(b instanceof Block.Text t ? t.text() : "[非文本块]");
        return String.join("\n", parts);
    }

    /**
     * 工具结果文本 → 脚本看到的值：长得像 JSON（{@code {} / {@code [} 开头）且解得开就解，否则原串。
     * ⚠️ 只在长得像时才解：{@code "pong"} 这种不该变成别的东西。
     */
    static JsonNode resultValue(String text) {
        String t = text.strip();
        if (!t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[')) {
            try {
                JsonNode n = AgentTool.JSON.readTree(t);
                if (n != null && (n.isObject() || n.isArray())) return n;
            } catch (RuntimeException ignored) {
                // 不是 JSON，按串给
            }
        }
        return JsonNodeFactory.instance.stringNode(text);
    }

    // ── 目录 ─────────────────────────────────────────────────────────────

    /** 工具表 → 目录文本（一件一行）：{@code server.tool  {a: string, b?: integer}  说明}。 */
    public static String catalog(List<AgentTool> tools) {
        List<String> lines = new ArrayList<>();
        for (AgentTool t : tools) lines.add(dotted(t.name()) + "  " + paramsLine(t.schema()) + "  " + oneLine(t.description()));
        return String.join("\n", lines);
    }

    /** {@code server_tool} → {@code server.tool}（第一个 _ 换成 .；没有 _ 的原样）。 */
    static String dotted(String name) {
        int i = name.indexOf('_');
        return i < 0 ? name : name.substring(0, i) + "." + name.substring(i + 1);
    }

    static String paramsLine(ObjectNode schema) {
        JsonNode props = schema == null ? null : schema.get("properties");
        if (props == null || !props.isObject()) return "{}";
        List<String> req = new ArrayList<>();
        JsonNode r = schema.get("required");
        if (r != null && r.isArray()) for (JsonNode x : r) if (x.isString()) req.add(x.asString());
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : props.properties()) {
            JsonNode ty = e.getValue().get("type");
            parts.add(e.getKey() + (req.contains(e.getKey()) ? "" : "?") + ": " + (ty != null && ty.isString() ? ty.asString() : "any"));
        }
        return "{" + String.join(", ", parts) + "}";
    }

    static String oneLine(String s) {
        String x = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ');
        return x.length() > MAX_DESC ? x.substring(0, MAX_DESC) + "…" : x;
    }

    /** 脚本里写的名字 → 表里的工具；认不出回 null。 */
    public static AgentTool resolveName(List<AgentTool> tools, String name) {
        AgentTool t = find(tools, name);
        if (t != null) return t;
        int i = name.indexOf('.');
        return i < 0 ? null : find(tools, name.substring(0, i) + "_" + name.substring(i + 1));
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        for (AgentTool t : tools) if (t.name().equals(name)) return t;
        return null;
    }
}
