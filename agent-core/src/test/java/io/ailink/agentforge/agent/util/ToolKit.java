package io.ailink.agentforge.agent.util;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tool.Workspace;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 测试小工具：跑一件工具、写文件。 */
public final class ToolKit {
    private ToolKit() { }

    public static ObjectNode args(Object... kv) {
        ObjectNode o = AgentTool.JSON.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) o.set((String) kv[i], AgentTool.JSON.valueToTree(kv[i + 1]));
        return o;
    }

    public static ToolOutput call(AgentTool t, ToolEnv env, Object... kv) { return t.execute(env, args(kv)); }

    public static ToolOutput call(AgentTool t, Path root, Object... kv) { return call(t, ToolEnv.of(new Workspace(root)), kv); }

    public static Path put(Path p, String text) throws IOException {
        Files.createDirectories(p.toAbsolutePath().getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    public static boolean windows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
