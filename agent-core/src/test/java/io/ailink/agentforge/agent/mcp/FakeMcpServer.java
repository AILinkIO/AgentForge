package io.ailink.agentforge.agent.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 测试用的假 MCP 服务器（子进程，NDJSON）。对应 coxswain {@code tests/agent-mcp/fake-server.ss}。
 *
 * <p>用法：{@code java -cp <测试类路径> …FakeMcpServer <模式> [pid 文件]}。
 * ⚠️ 一个字都不许往 stdout 写（除了协议帧）。
 *
 * <p>三件基本工具，三种 annotations 形状：{@code peek} readOnlyHint=true、{@code poke} 缺席、
 * {@code tap} 显式 false（客户端不得把"写了 false"读成别的意思）。
 *
 * <p>模式：
 * <ul>
 *   <li>{@code normal} —— 三件工具，tools/call 回 "pong"；</li>
 *   <li>{@code rich} —— 再加 echo（回参数 JSON）、shot（图片）、fail（isError）、die（stderr 留话后退出 3）、
 *       {@code bad.name}（名字要消毒）、{@code arr}（schema 不是 object，要被拒收），initialize 带 instructions；</li>
 *   <li>{@code stubborn} —— 从不应答；</li>
 *   <li>{@code reject} —— initialize 回 JSON-RPC error。</li>
 * </ul>
 */
public final class FakeMcpServer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "normal";
        if (args.length > 1) Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);   // 任何意外的 println 都别进协议流
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (mode.equals("stubborn")) {
            while (in.readLine() != null) { /* 从不应答 */ }
            return;
        }
        String line;
        while ((line = in.readLine()) != null) {
            JsonNode msg = JSON.readTree(line);
            JsonNode id = msg.get("id");
            String method = msg.path("method").asString("");
            if (id == null || id.isNull()) continue;   // 通知不应答
            switch (method) {
                case "initialize" -> {
                    if (mode.equals("reject")) {
                        error(out, id, -32602, "unsupported protocolVersion 1999-01-01");
                        break;
                    }
                    ObjectNode r = F.objectNode();
                    r.put("protocolVersion", "2025-06-18");
                    r.putObject("capabilities").putObject("tools");
                    r.putObject("serverInfo").put("name", "fake").put("version", "1");
                    if (mode.equals("rich")) r.put("instructions", "先调 peek 再调 poke。");
                    result(out, id, r);
                }
                case "tools/list" -> result(out, id, toolsList(mode));
                case "tools/call" -> {
                    String name = msg.path("params").path("name").asString("");
                    JsonNode a = msg.path("params").path("arguments");
                    switch (name) {
                        case "echo" -> result(out, id, text(a.toString(), false));
                        case "fail" -> result(out, id, text("出事了", true));
                        case "shot" -> {
                            ObjectNode r = F.objectNode();
                            ArrayNode c = r.putArray("content");
                            c.addObject().put("type", "text").put("text", "截图如下");
                            c.addObject().put("type", "image").put("mimeType", "image/png").put("data", "iVBORw0KGgo=");
                            result(out, id, r);
                        }
                        case "die" -> {
                            System.err.println("我要走了：数据库连不上");
                            System.err.flush();
                            System.exit(3);
                        }
                        default -> result(out, id, text("pong", false));
                    }
                }
                case "ping" -> result(out, id, F.objectNode());
                default -> error(out, id, -32601, "method not found: " + method);
            }
        }
    }

    private static ObjectNode toolsList(String mode) {
        ObjectNode r = F.objectNode();
        ArrayNode ts = r.putArray("tools");
        tool(ts, "peek", "只读的", Boolean.TRUE);
        tool(ts, "poke", "会写的", null);
        tool(ts, "tap", "也写的（明说 false）", Boolean.FALSE);
        if (mode.equals("rich")) {
            ObjectNode echo = tool(ts, "echo", "回声", Boolean.TRUE);
            ObjectNode props = (ObjectNode) echo.get("inputSchema").get("properties");
            props.putObject("q").put("type", "string");
            props.putObject("n").put("type", "integer");
            ((ObjectNode) echo.get("inputSchema")).putArray("required").add("q");
            tool(ts, "shot", "截图", Boolean.TRUE);
            tool(ts, "fail", "总是失败", Boolean.TRUE);
            tool(ts, "die", "让服务器退出", null);
            tool(ts, "bad.name", "名字里有点", Boolean.TRUE);
            ObjectNode arr = ts.addObject();
            arr.put("name", "arr");
            arr.put("description", "schema 不是 object");
            arr.putObject("inputSchema").put("type", "array");
        }
        return r;
    }

    private static ObjectNode tool(ArrayNode ts, String name, String desc, Boolean readOnly) {
        ObjectNode t = ts.addObject();
        t.put("name", name);
        t.put("description", desc);
        ObjectNode s = t.putObject("inputSchema");
        s.put("type", "object");
        s.putObject("properties");
        if (readOnly != null) t.putObject("annotations").put("readOnlyHint", readOnly);
        return t;
    }

    private static ObjectNode text(String s, boolean isError) {
        ObjectNode r = F.objectNode();
        r.putArray("content").addObject().put("type", "text").put("text", s);
        if (isError) r.put("isError", true);
        return r;
    }

    private static void result(PrintStream out, JsonNode id, JsonNode result) {
        ObjectNode m = F.objectNode();
        m.put("jsonrpc", "2.0");
        m.set("id", id);
        m.set("result", result);
        out.println(m);
        out.flush();
    }

    private static void error(PrintStream out, JsonNode id, int code, String message) {
        ObjectNode m = F.objectNode();
        m.put("jsonrpc", "2.0");
        m.set("id", id);
        m.putObject("error").put("code", code).put("message", message);
        out.println(m);
        out.flush();
    }
}
