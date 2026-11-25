package io.ailink.agentforge.agent.runtime;

import io.ailink.agentforge.agent.codemode.ExecuteTool;
import io.ailink.agentforge.agent.memory.MemoryTools;
import io.ailink.agentforge.agent.registry.ToolCatalog;
import io.ailink.agentforge.agent.skill.SkillTool;
import io.ailink.agentforge.agent.todo.TodoTool;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.file.FileToolset;
import io.ailink.agentforge.agent.tools.search.SearchTools;
import io.ailink.agentforge.agent.tools.shell.ShellTools;
import io.ailink.agentforge.agent.tools.symbol.SymbolTools;
import io.ailink.agentforge.agent.tools.web.WebTools;
import io.ailink.agentforge.agent.tools.worktree.WorktreeTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 工具总表（coxswain {@code %builtin} + {@code toolset-add!} + 编辑器的 {@code %all-tools}）：一处登记，顺序固定。
 *
 * <p>⚠️ **顺序是给模型看的**：工具表的顺序影响模型挑哪件（排前面的更常被选），而且每次重排都会毁掉 prompt cache 的前缀。
 * 所以内置工具按 {@link #ORDER} 排，宿主加的接在后面，再后面是 clarify（只给主 agent）、task、MCP 工具与 execute。
 *
 * <p>宿主工具同名后来居上（重复登记不报错，换实现时用）。MCP 工具由 supplier 现取（目录会变）。
 * 线程安全：读写都同步在实例上。
 */
public final class Toolset {

    /** 内置工具的全局顺序（coxswain tools.ss）。 */
    public static final List<String> ORDER = List.of(
            "read-file", "list-dir", "search-text", "find-files", "find-symbol", "symbol-refs", "impact", "worktree-list",
            "write-file", "edit-file", "delete-path", "list-forms", "read-form", "replace-form", "run-command", "apply-patch",
            "web-fetch", "web-search", "memory-list", "memory-read", "memory-write", "skill", "todo-write",
            "worktree-create", "worktree-remove", "worktree-reset", "worktree-enter");

    private final Map<String, AgentTool> builtins = new LinkedHashMap<>();
    private final Map<String, AgentTool> host = new LinkedHashMap<>();
    private volatile Supplier<List<AgentTool>> mcp = List::of;
    private volatile List<AgentTool> mcpCache = List.of();
    private volatile List<AgentTool> mcpWithExecute = List.of();

    /** 空表（测试 / 自己装配）。 */
    public Toolset() { }

    /** 全部内置工具，按 {@link #ORDER}。 */
    public static Toolset standard() {
        Toolset ts = new Toolset();
        List<AgentTool> all = new ArrayList<>();
        all.addAll(FileToolset.tools());
        all.addAll(SearchTools.tools());
        all.addAll(SymbolTools.tools());
        all.addAll(WorktreeTools.tools());
        all.addAll(ShellTools.tools());
        all.addAll(WebTools.tools());
        all.addAll(MemoryTools.tools());
        all.addAll(SkillTool.tools());
        all.addAll(TodoTool.tools());
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool t : all) byName.put(t.name(), t);
        for (String n : ORDER) {
            AgentTool t = byName.remove(n);
            if (t != null) ts.builtins.put(n, t);
        }
        ts.builtins.putAll(byName);   // ORDER 里没写到的新工具：接在内置的末尾（别静默丢掉）
        return ts;
    }

    /** 换掉 / 加一件内置工具（按名字；新名字接在末尾）。 */
    public synchronized Toolset putBuiltin(AgentTool t) { builtins.put(t.name(), t); return this; }

    /** 去掉一件内置工具（宿主不想给的，例如无网环境去掉 web-*）。 */
    public synchronized Toolset removeBuiltin(String name) { builtins.remove(name); return this; }

    /** 宿主加的工具（编辑器的 current-buffer 一类）；同名后来居上。 */
    public synchronized Toolset add(AgentTool t) { host.remove(t.name()); host.put(t.name(), t); return this; }

    public synchronized Toolset remove(String name) { host.remove(name); return this; }

    /** MCP 工具的来源（{@code McpManager::toolsSupplier}）。 */
    public Toolset mcp(Supplier<List<AgentTool>> s) { this.mcp = s == null ? List::of : s; refreshMcp(); return this; }

    /** MCP 目录变了（CATALOG 事件）：重取一次，execute 跟着重建。 */
    public synchronized void refreshMcp() {
        List<AgentTool> ts;
        try { ts = List.copyOf(mcp.get()); } catch (RuntimeException e) { ts = List.of(); }
        mcpCache = ts;
        mcpWithExecute = List.copyOf(ExecuteTool.withExecute(ts));
    }

    public synchronized List<AgentTool> builtins() { return List.copyOf(builtins.values()); }

    public synchronized List<AgentTool> hostTools() { return List.copyOf(host.values()); }

    /** MCP 工具 + （有的话）execute。 */
    public List<AgentTool> mcpTools() { return mcpWithExecute; }

    /** 内置 + 宿主，过 {@code enabled(ws)}（主 agent 默认那一套，不含 clarify / task / MCP）。 */
    public synchronized List<AgentTool> defaultTools(Workspace ws) {
        List<AgentTool> out = new ArrayList<>();
        for (AgentTool t : builtins.values()) if (ws == null || t.enabled(ws)) out.add(t);
        for (AgentTool t : host.values()) if (ws == null || t.enabled(ws)) out.add(t);
        return out;
    }

    /** 只读那一套（子 agent 的默认）。 */
    public List<AgentTool> readOnlyTools(Workspace ws) {
        List<AgentTool> out = new ArrayList<>();
        for (AgentTool t : defaultTools(ws)) if (t.readOnly()) out.add(t);
        return out;
    }

    /**
     * 一段会话的整张工具表：默认那套 + clarify（null = 不给）+ task（null = 不给）+ MCP 工具 + execute。
     */
    public List<AgentTool> forSession(Workspace ws, AgentTool clarify, AgentTool task) {
        List<AgentTool> out = new ArrayList<>(defaultTools(ws));
        if (clarify != null) out.add(clarify);
        if (task != null) out.add(task);
        for (AgentTool t : mcpWithExecute) if (out.stream().noneMatch(x -> x.name().equals(t.name()))) out.add(t);
        return out;
    }

    public AgentTool byName(String name) {
        for (AgentTool t : catalogList()) if (t.name().equals(name)) return t;
        return null;
    }

    private synchronized List<AgentTool> catalogList() {
        List<AgentTool> out = new ArrayList<>(builtins.values());
        out.addAll(host.values());
        out.addAll(mcpWithExecute);
        return out;
    }

    /**
     * 给 {@code AgentRegistry} 的目录：按名字认得出所有登记过的工具（**不过 enabled**：关掉的工具名字仍然认得 ——
     * 否则用户配了它、它恰好关着，得到的是"打错了名字"）。每次现取。
     */
    public ToolCatalog catalog() { return this::catalogList; }
}
