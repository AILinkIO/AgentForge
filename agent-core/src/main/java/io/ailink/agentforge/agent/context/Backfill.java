package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.todo.Todo;
import io.ailink.agentforge.agent.todo.TodoStore;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolOutput;
import io.ailink.agentforge.agent.tools.file.FileTools;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩后回灌（coxswain {@code backfill.ss}）：被压掉的那段里最近读过 / 改过的文件，按**现在**的内容重读一遍，
 * 连同当前待办，作为提醒块挂在压缩通告上。
 *
 * <p>为什么：摘要里写着"改过 foo.c"，但模型接着要改它时手上没有内容、也没有 TAG —— 第一件事就是再读一遍。回灌
 * 省掉那一轮，而且 read-file 会顺手登记 seen，照着改就行。尾巴里已经读过的不重复给。
 */
public final class Backfill {
    private Backfill() { }

    public static final int MAX_FILES = 5;
    public static final int MAX_LINES = 300;
    public static final int MAX_CHARS = 40000;
    static final List<String> TOUCHING = List.of("read-file", "read-form", "edit-file", "write-file", "replace-form");
    static final List<String> READING = List.of("read-file", "read-form");

    static String inputPath(Block.ToolUse u) {
        JsonNode p = u.input() == null ? null : u.input().get("path");
        return p != null && p.isString() && !p.asString().isEmpty() ? p.asString() : null;
    }

    /** 从新到旧、去重。 */
    static List<String> pathsOf(List<Message> msgs, List<String> names) {
        List<String> acc = new ArrayList<>();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            List<Block.ToolUse> us = msgs.get(i).toolUses();
            for (int j = us.size() - 1; j >= 0; j--) {
                Block.ToolUse u = us.get(j);
                String p = names.contains(u.name()) ? inputPath(u) : null;
                if (p != null && !acc.contains(p)) acc.add(p);
            }
        }
        return acc;
    }

    public static List<String> paths(List<Message> head, List<Message> tail) {
        List<String> seenInTail = pathsOf(tail, READING);
        List<String> out = new ArrayList<>();
        for (String p : pathsOf(head, TOUCHING)) if (!seenInTail.contains(p)) out.add(p);
        return out;
    }

    /** 回灌文本；什么都没有 → null。{@code env} 用来跑 read-file（登记 seen）与取待办（按 sessionId）。 */
    public static String compactionBackfill(List<Message> head, List<Message> tail, ToolEnv env) {
        AgentTool read = FileTools.readFile();
        List<String> files = new ArrayList<>(), skipped = new ArrayList<>();
        int used = 0;
        for (String p : paths(head, tail)) {
            if (files.size() >= MAX_FILES) break;
            String text = readNow(read, env, p);
            if (text == null) continue;
            if (used + text.length() > MAX_CHARS) { skipped.add(p); continue; }
            used += text.length();
            files.add("### " + p + "\n" + text);
        }
        String todo = todoSection(env);
        if (files.isEmpty() && todo == null) return null;
        List<String> parts = new ArrayList<>();
        if (!files.isEmpty()) {
            parts.add("上面的对话压缩掉了。下面是压掉的那段里你最近读过或改过的文件**现在**的内容"
                    + "（由系统重新读取；TAG 已登记，照着改就行。只给了前 " + MAX_LINES + " 行的，要看后面用 read-file 分页）：");
            parts.addAll(files);
        }
        if (!skipped.isEmpty()) parts.add("（这几个也碰过，放不下没给，要用就 read-file：" + String.join("、", skipped) + "）");
        if (todo != null) parts.add(todo);
        return String.join("\n\n", parts);
    }

    private static String readNow(AgentTool read, ToolEnv env, String path) {
        try {
            ObjectNode in = AgentTool.JSON.createObjectNode().put("path", path).put("limit", MAX_LINES);
            ToolOutput o = read.execute(env, in);
            if (o.error()) return null;
            for (Block b : o.content()) if (!(b instanceof Block.Text)) return null;   // 图 / PDF 不回灌
            return o.text();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String todoSection(ToolEnv env) {
        List<Todo.Item> items = TodoStore.of(env.workspace()).items(env.sessionId());
        if (items.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        lines.add("### 当前待办（你最后一次 todo-write 的那份，进度照旧）");
        for (Todo.Item it : items) lines.add("- [" + mark(it) + "] " + it.text());
        return String.join("\n", lines);
    }

    private static String mark(Todo.Item it) {
        return switch (it.status()) { case DONE -> "x"; case DOING -> ">"; case PENDING -> " "; };
    }
}
