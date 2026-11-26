package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.approval.ApprovalChoice;
import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.PermissionSettings;
import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.mcp.McpServerInfo;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.SessionEvent;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.todo.Todo;
import io.ailink.agentforge.agent.todo.TodoStore;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.agent.transcript.SubStep;
import io.ailink.agentforge.agent.transcript.Transcript;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 给前端的 JSON 投影（都是 Map / List / 标量，Jackson 原样写出）。字段名用 camelCase；Entry.meta 的键保持 agent-core
 * 原样（kebab-case，见 {@link Entry}），前端按同一份约定读。
 */
final class Views {
    private Views() { }

    /** 小号的有序 Map 构造器：null 值不写（前端拿 undefined 当"没有"）。 */
    static final class M {
        private final Map<String, Object> m = new LinkedHashMap<>();

        M put(String k, Object v) {
            if (v != null) m.put(k, v);
            return this;
        }

        Map<String, Object> done() { return m; }
    }

    static M m() { return new M(); }

    // ── 会话 ───────────────────────────────────────────────────────────

    /** 侧栏 / 列表里的一行。 */
    static Map<String, Object> sessionSummary(AgentRuntime rt, AgentSession s) {
        String first = null;
        for (Entry e : s.transcript()) if (e.role() == Entry.Role.USER) { first = e.text(); break; }
        return m().put("id", s.id())
                .put("parentId", s.parentId())
                .put("title", s.title() != null ? s.title() : first != null ? clip(first, 60) : null)
                .put("status", s.status().name())
                .put("busy", s.busy())
                .put("model", s.model() != null ? s.model() : rt.currentModel())
                .put("project", rt.projectOf(s))
                .put("root", s.workspace().root().toString())
                .put("agent", s.agent())
                .put("conversation", rt.conversationOf(s))
                .put("usage", usage(s.usage()))
                .put("cost", s.cost())
                .put("pendingApproval", s.pendingApproval() != null)
                .put("pendingQuestion", s.pendingQuestion() != null)
                .put("runningSubagents", s.subLog().stream().filter(SubRun::running).count())
                .done();
    }

    /** 整个会话的快照：前端收到事件后（除了流式增量）就重取这一份。 */
    static Map<String, Object> snapshot(AgentRuntime rt, AgentSession s) {
        List<Entry> entries = new ArrayList<>(Transcript.spliceSubagents(s.transcript(), s.subLog()));
        boolean busy = s.busy();
        if (busy) entries.addAll(Transcript.spliceSubagents(s.turnLog(), s.subLog()));
        List<Map<String, Object>> es = new ArrayList<>(entries.size());
        for (Entry e : entries) es.add(entry(e));
        String streaming = busy ? s.streamingText() : "";
        List<Map<String, Object>> children = new ArrayList<>();
        for (AgentSession c : s.children()) children.add(sessionSummary(rt, c));
        return m().put("session", sessionSummary(rt, s))
                .put("root", s.workspace().root().toString())
                .put("thinking", s.thinking())
                .put("thinkingLevels", s.thinkingLevels())
                .put("entries", es)
                .put("streaming", m().put("kind", s.streamingKind()).put("text", streaming).put("epoch", s.streamingEpoch()).done())
                .put("approval", s.pendingApproval() == null ? null : approval(s.pendingApproval()))
                .put("question", s.pendingQuestion() == null ? null : question(s.pendingQuestion()))
                .put("usage", usageBlock(s))
                .put("todos", todos(s))
                .put("subRuns", s.subLog().stream().map(Views::subRun).toList())
                .put("children", children)
                .put("queue", s.queue())
                .put("draft", s.draft().isEmpty() ? null : s.draft())
                .put("error", s.errorLine())
                .put("resultStatus", s.resultStatus() == null ? null : s.resultStatus().name())
                .put("turnMs", s.turnDurationMs())
                .put("compactions", s.compactions().size())
                .put("attachments", s.attachments())
                .done();
    }

    static Map<String, Object> entry(Entry e) {
        return m().put("role", e.role().name()).put("text", e.text()).put("meta", e.meta()).done();
    }

    static Map<String, Object> usage(Usage u) {
        if (u == null) return null;
        return m().put("input", u.input()).put("output", u.output()).put("cacheRead", u.cacheRead())
                .put("cacheWrite", u.cacheWrite()).put("reasoning", u.reasoning()).done();
    }

    static Map<String, Object> usageBlock(AgentSession s) {
        return m().put("total", usage(s.usage()))
                .put("lastInput", s.lastInput())
                .put("lastOutput", s.lastOutput())
                .put("cost", s.cost())
                .put("costPartial", s.costPartial())
                .put("contextRatio", s.contextRatio())
                .put("contextLimit", s.contextLimit())
                .done();
    }

    static List<Map<String, Object>> todos(AgentSession s) {
        List<Todo.Item> items;
        try { items = TodoStore.of(s.workspace()).items(s.id()); } catch (RuntimeException e) { items = List.of(); }
        return items.stream().map(t -> m().put("status", t.status().name()).put("text", t.text()).done()).toList();
    }

    static Map<String, Object> subRun(SubRun r) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (SubStep st : r.allSteps())
            steps.add(m().put("name", st.name()).put("args", st.args()).put("error", st.error()).put("summary", st.summary()).done());
        return m().put("id", r.id()).put("desc", r.desc()).put("status", r.status()).put("running", r.running())
                .put("ok", r.ok()).put("ms", r.ms()).put("startedAt", r.startedAt()).put("background", r.background())
                .put("who", r.who()).put("model", r.model()).put("why", r.why()).put("steps", steps).done();
    }

    // ── 审批 / 提问 ─────────────────────────────────────────────────────

    static Map<String, Object> approval(ApprovalRequest r) {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (ApprovalChoice c : r.choices())
            choices.add(m().put("decision", c.decision().name()).put("key", c.key()).put("label", c.label()).done());
        return m().put("id", r.id()).put("kind", r.kind().name()).put("session", r.session()).put("toolUseId", r.toolUseId())
                .put("toolName", r.toolName()).put("title", r.title()).put("command", r.command()).put("cwd", r.cwd())
                .put("preview", r.preview() == null ? null : m().put("kind", r.preview().kind().name()).put("text", r.preview().text()).done())
                .put("reason", r.reason()).put("retryReason", r.retryReason()).put("host", r.host())
                .put("amendment", r.amendment() == null ? null : r.amendment().describe())
                .put("choices", choices).done();
    }

    static Map<String, Object> question(ClarifyService.Request q) {
        List<Map<String, Object>> qs = new ArrayList<>();
        for (ClarifyService.Question x : q.questions()) {
            List<Map<String, Object>> opts = x.options() == null ? List.of()
                    : x.options().stream().map(o -> m().put("label", o.label()).put("description", o.description()).done()).toList();
            qs.add(m().put("question", x.question()).put("header", x.header()).put("options", opts).put("multiple", x.multiple()).done());
        }
        return m().put("toolUseId", q.toolUseId()).put("questions", qs).done();
    }

    // ── 工具结果全文 ────────────────────────────────────────────────────

    /** 某次工具调用的完整结果（记录区里只有摘要）+ 动手前记下的 diff。找不到 ⇒ null。 */
    static Map<String, Object> toolDetail(AgentSession s, String toolUseId) {
        Block.ToolResult hit = null;
        Block.ToolUse use = null;
        for (Message msg : s.history()) {
            if (msg.isAssistant()) for (Block.ToolUse u : msg.toolUses()) if (toolUseId.equals(u.id())) use = u;
            if (msg.isUser()) for (Block.ToolResult r : msg.toolResults()) if (toolUseId.equals(r.toolUseId())) hit = r;
        }
        String diff = s.changeDiff(toolUseId);
        if (hit == null && use == null && diff == null) return null;
        int images = 0;
        if (hit != null) for (Block b : hit.content()) if (b instanceof Block.Image) images++;
        return m().put("toolUseId", toolUseId)
                .put("name", use == null ? null : use.name())
                .put("input", use == null ? null : use.input())
                .put("text", hit == null ? null : Block.joinText(hit.content()))
                .put("error", hit == null ? null : hit.error())
                .put("images", images == 0 ? null : images)
                .put("diff", diff)
                .done();
    }

    // ── 事件 ───────────────────────────────────────────────────────────

    /** 推给前端的事件：{@code type} + 会话 id + 少量字段（多数事件前端只拿来触发重取快照）。 */
    static Map<String, Object> event(AgentSession s, SessionEvent ev) {
        M o = m().put("session", s.id()).put("parent", s.parentId());
        switch (ev) {
            case SessionEvent.StatusChanged e -> o.put("type", "status").put("status", e.status().name());
            case SessionEvent.Started e -> o.put("type", "started").put("text", e.text());
            // epoch + 结尾偏移：前端据此判断这段增量接不接得上自己手里那份（接不上就重取快照）
            case SessionEvent.Delta e -> o.put("type", "delta").put("kind", e.kind()).put("text", e.text())
                    .put("epoch", s.streamingEpoch()).put("at", s.streamingText().length());
            case SessionEvent.Assistant e -> o.put("type", "assistant").put("toolCalls", e.toolCalls());
            case SessionEvent.ToolCall e -> o.put("type", "tool-call").put("toolUseId", e.toolUseId()).put("name", e.name());
            case SessionEvent.ToolResult e -> o.put("type", "tool-result").put("toolUseId", e.toolUseId()).put("name", e.name())
                    .put("error", e.error()).put("kind", e.kind());
            case SessionEvent.ApprovalRequested e -> o.put("type", "approval").put("request", approval(e.request()));
            case SessionEvent.ApprovalResolved e -> o.put("type", "approval-resolved").put("decision", e.decision().kind().name());
            case SessionEvent.AutoApproval e -> o.put("type", "auto-approval").put("tool", e.toolName()).put("summary", e.summary())
                    .put("allowed", e.allowed()).put("why", e.why());
            case SessionEvent.PolicyAmended e -> o.put("type", "policy-amended").put("text", e.description());
            case SessionEvent.Review e -> o.put("type", "review").put("decision", e.decision().kind().name()).put("files", e.files());
            case SessionEvent.Question e -> o.put("type", "question").put("request", question(e.request()));
            case SessionEvent.QuestionAnswered e -> o.put("type", "question-answered");
            case SessionEvent.Retry e -> o.put("type", "retry").put("attempt", e.attempt()).put("max", e.max())
                    .put("waitMs", e.waitMs()).put("message", e.message());
            case SessionEvent.UsageUpdated e -> o.put("type", "usage").put("last", usage(e.last())).put("total", usage(e.total()))
                    .put("cost", e.cost()).put("costPartial", e.costPartial()).put("contextRatio", e.contextRatio());
            case SessionEvent.Queued e -> o.put("type", "queued").put("text", e.text());
            case SessionEvent.Steered e -> o.put("type", "steered").put("text", e.text());
            case SessionEvent.Requeued e -> o.put("type", "requeued").put("text", e.text());
            case SessionEvent.SubStart e -> o.put("type", "sub-start").put("run", subRun(e.run()));
            case SessionEvent.SubStep e -> o.put("type", "sub-step").put("runId", e.run().id()).put("tool", e.toolName());
            case SessionEvent.SubFinish e -> o.put("type", "sub-finish").put("run", subRun(e.run()));
            case SessionEvent.Trimmed e -> o.put("type", "trimmed").put("dropped", e.dropped()).put("total", e.total());
            case SessionEvent.Compacting e -> o.put("type", "compacting");
            case SessionEvent.Compacted e -> o.put("type", "compacted").put("headMessages", e.headMessages()).put("summaryChars", e.summaryChars());
            case SessionEvent.CacheInactive e -> o.put("type", "cache-inactive").put("rounds", e.rounds());
            case SessionEvent.Finished e -> o.put("type", "finished").put("status", e.status() == null ? null : e.status().name());
            case SessionEvent.Incomplete e -> o.put("type", "incomplete").put("reason", e.reason()).put("limit", e.limit());
            case SessionEvent.Stopped e -> o.put("type", "stopped");
            case SessionEvent.Error e -> o.put("type", "error").put("line", e.line());
            case SessionEvent.RootChanged e -> o.put("type", "root-changed").put("root", e.root().toString());
        }
        return o.done();
    }

    // ── 配置面 ─────────────────────────────────────────────────────────

    static Map<String, Object> model(AgentRuntime rt, ModelMeta mm) {
        String provider = mm.ref().providerId();
        var info = rt.models().provider(provider);
        return m().put("ref", mm.ref().toString()).put("provider", provider).put("id", mm.ref().modelId())
                .put("label", mm.label().isEmpty() ? null : mm.label())
                .put("contextWindow", mm.contextWindow()).put("maxOutput", mm.maxOutput())
                .put("thinkingLevels", mm.thinkingLevels()).put("builtin", mm.builtin())
                .put("reasons", mm.reasons()).put("attachments", mm.takesAttachment())
                .put("providerEnabled", info != null)
                .put("source", mm.builtin() ? "models.dev" : "config")
                .put("providerLabel", info == null ? null : info.label())
                .done();
    }

    static Map<String, Object> tool(AgentTool t, AgentRuntime rt) {
        return m().put("name", t.name()).put("description", t.description()).put("readOnly", t.readOnly())
                .put("sensitive", t.sensitive()).put("enabled", t.enabled(rt.workspace()))
                .put("params", t.params().stream().map(p -> m().put("name", p.name()).put("type", p.type())
                        .put("required", p.required()).put("description", p.description()).done()).toList())
                .done();
    }

    static Map<String, Object> mcpServer(McpServerInfo i) {
        return m().put("name", i.name()).put("status", i.status().name()).put("error", i.error())
                .put("tools", i.tools() == null ? List.of() : i.tools().stream().map(AgentTool::name).toList())
                .put("instructions", i.instructions()).put("since", i.since() == null ? null : i.since().toString())
                .put("fromConfig", i.fromConfig()).put("stderrTail", i.stderrTail()).done();
    }

    static Map<String, Object> permissions(AgentRuntime rt) {
        PermissionSettings ps = rt.permissionSettings();
        List<Map<String, Object>> presets = new ArrayList<>();
        for (ApprovalPreset p : ApprovalPreset.values())
            presets.add(m().put("id", p.id()).put("label", p.label()).put("description", p.description())
                    .put("approval", p.approval().wire()).put("sandbox", p.sandbox().wire()).done());
        return m().put("preset", ps.preset() == null ? null : ps.preset().id())
                .put("approval", ps.approval().wire())
                .put("sandbox", ps.sandbox().wire())
                .put("network", ps.networkEnabled())
                .put("writableRoots", ps.writableRoots())
                .put("describe", ps.describe())
                .put("sandboxStatus", rt.sandboxStatus())
                .put("presets", presets)
                .done();
    }

    static Map<String, Object> conversation(SessionStore.Conversation c) {
        return m().put("id", c.id()).put("title", c.title()).put("created", c.created()).put("updated", c.updated())
                .put("parent", c.parent()).put("messages", c.messages()).put("project", c.project()).put("root", c.root()).done();
    }

    static Map<String, Object> project(io.ailink.agentforge.agent.project.Project p, int conversations) {
        return m().put("id", p.id()).put("name", p.name()).put("folders", p.folders()).put("instructions", p.instructions())
                .put("model", p.model()).put("preset", p.preset()).put("created", p.created()).put("updated", p.updated())
                .put("conversations", conversations)
                .put("missing", p.folders().stream().filter(f -> !java.nio.file.Files.isDirectory(java.nio.file.Path.of(f))).toList())
                .done();
    }

    static String clip(String s, int n) {
        String one = s.strip().replaceAll("\\s+", " ");
        return one.length() > n ? one.substring(0, n - 1) + "…" : one;
    }
}
