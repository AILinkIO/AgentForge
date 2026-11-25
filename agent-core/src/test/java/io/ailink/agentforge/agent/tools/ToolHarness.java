package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** 测试夹具：一个工作区 + 按名字调工具。 */
final class ToolHarness {
    final Path root;
    final Workspace ws;
    ToolEnv env;
    private final Map<String, AgentTool> tools = new HashMap<>();

    ToolHarness(Path root) {
        this.root = root;
        this.ws = new Workspace(root);
        this.env = ToolEnv.of(ws);
        for (AgentTool t : FileToolset.tools()) tools.put(t.name(), t);
    }

    AgentTool tool(String name) { return tools.get(name); }

    /** 参数按 k, v, k, v… 给。 */
    ToolOutput call(String name, Object... kv) {
        ObjectNode in = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < kv.length; i += 2) {
            String k = (String) kv[i];
            Object v = kv[i + 1];
            if (v instanceof String s) in.put(k, s);
            else if (v instanceof Boolean b) in.put(k, b);
            else if (v instanceof Integer n) in.put(k, n);
            else if (v instanceof Long n) in.put(k, n);
        }
        return tools.get(name).execute(env, in);
    }

    String ok(String name, Object... kv) {
        ToolOutput o = call(name, kv);
        if (o.error()) throw new AssertionError(name + " 失败：" + o.text());
        return o.text();
    }

    String err(String name, Object... kv) {
        ToolOutput o = call(name, kv);
        if (!o.error()) throw new AssertionError(name + " 本该失败：" + o.text());
        return o.text();
    }

    Path put(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    Path putBytes(String rel, byte[] bytes) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, bytes);
        return p;
    }

    String slurp(String rel) throws IOException { return Files.readString(root.resolve(rel), StandardCharsets.UTF_8); }

    String full(String rel) { return root.resolve(rel).toString(); }
}
