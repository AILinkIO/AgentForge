package io.ailink.agentforge.web;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.approval.ReviewDecision;
import io.ailink.agentforge.agent.clarify.ClarifyService;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.mcp.McpServerInfo;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.ModelRef;
import io.ailink.agentforge.agent.models.Models;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.agent.tool.AgentTool;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 网页端的 REST 面。读：快照 / 列表；写：发话、审批、答题、按停、压缩、改设置。实时变化走 {@code GET /api/events}（SSE）。
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final SessionHub hub;
    private final AgentRuntime rt;
    private final RuntimeConfig.StartupNotes notes;

    public ApiController(SessionHub hub, RuntimeConfig.StartupNotes notes) {
        this.hub = hub;
        this.rt = hub.runtime();
        this.notes = notes;
    }

    // ── 请求体 ─────────────────────────────────────────────────────────

    /** 新会话：{@code project} 给了就开在那个项目里（{@code folder} 选它的哪个源文件夹，缺省第一个）。 */
    public record NewSession(String agent, String project, String folder) { }

    public record SendBody(String text) { }

    public record ApprovalBody(String id, String decision, String feedback) { }

    public record AnswerBody(List<List<String>> answers, Boolean dismissed) { }

    public record SettingsBody(String model, String thinking, String agent, String title) { }

    public record AttachBody(String name, String mediaType, String data) { }

    public record RefBody(String ref) { }

    public record PresetBody(String preset) { }

    public record KeyBody(String key) { }

    // ── 总览 ───────────────────────────────────────────────────────────

    @GetMapping("/info")
    public Map<String, Object> info() {
        List<Map<String, Object>> agents = new ArrayList<>();
        Set<String> subs = new HashSet<>();
        for (AgentDef d : rt.registry().subagentDefs()) subs.add(d.name());
        for (AgentDef d : rt.registry().defs())
            if (!d.hidden()) agents.add(Views.m().put("name", d.name()).put("description", d.description())
                    .put("subagent", subs.contains(d.name())).done());
        io.ailink.agentforge.agent.models.Problem problem = rt.problem();
        return Views.m().put("version", "0.2.0")
                .put("root", rt.workspaceRoot().toString())
                .put("configDir", rt.configDir().toString())
                .put("model", rt.currentModel())
                .put("host", hostName())
                .put("home", System.getProperty("user.home"))
                .put("modelProblem", problem == null ? null : problem.message())
                .put("sandboxStatus", rt.sandboxStatus())
                .put("permissions", rt.permissionSettings().describe())
                .put("notes", notes.notes())
                .put("storeWarning", rt.storeWarning())
                .put("subagents", rt.subagentsEnabled())
                .put("agents", agents)
                .done();
    }

    @GetMapping(path = "/events", produces = "text/event-stream")
    public SseEmitter events() {
        return hub.subscribe();
    }

    // ── 会话 ───────────────────────────────────────────────────────────

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return hub.sessions().stream().map(s -> Views.sessionSummary(rt, s)).toList();
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) NewSession body) {
        AgentSession s;
        if (body != null && body.project() != null && !body.project().isBlank()) {
            var p = rt.projects().get(body.project()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这个项目：" + body.project()));
            java.nio.file.Path dir = p.folderOrDefault(body.folder());
            if (!java.nio.file.Files.isDirectory(dir)) throw bad("源文件夹不在了：" + dir);
            s = hub.create(body.agent(), p, body.folder());
        } else {
            s = hub.create(body == null ? null : body.agent());
        }
        return Views.sessionSummary(rt, s);
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> snapshot(@PathVariable String id) {
        return Views.snapshot(rt, session(id));
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> closeSession(@PathVariable String id) {
        hub.close(session(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sessions/{id}/messages")
    public Map<String, Object> send(@PathVariable String id, @RequestBody SendBody body) {
        if (body == null || body.text() == null || body.text().isBlank()) throw bad("话是空的");
        AgentSession s = session(id);
        if (s.parent() != null) throw bad("子 agent 的会话不能直接发话");
        if (s.status() == io.ailink.agentforge.agent.session.SessionStatus.ERROR) s.clearError();
        AgentSession.SendResult r = s.send(body.text());
        return Views.m().put("result", r.name()).done();
    }

    @PostMapping("/sessions/{id}/interrupt")
    public Map<String, Object> interrupt(@PathVariable String id) {
        session(id).interrupt();
        return Views.m().put("ok", true).done();
    }

    @PostMapping("/sessions/{id}/compact")
    public Map<String, Object> compact(@PathVariable String id) {
        AgentSession s = session(id);
        if (s.busy()) throw bad("正忙着，等这一轮做完再压");
        if (!s.canCompact()) throw new ResponseStatusException(HttpStatus.CONFLICT, "对话还短，没有值得压的");
        // 压要问一次模型（几秒到几十秒）：异步跑；成了会话自己发 Compacted，没成由这里补一个 compact-failed
        Thread.ofVirtual().name("agentforge-compact").start(() -> {
            String why = null;
            try {
                if (!s.compact()) why = "没拿到摘要，历史没动";
            } catch (RuntimeException e) {
                why = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            if (why != null) hub.publish(Views.m().put("type", "compact-failed").put("session", s.id()).put("message", why).done());
        });
        return Views.m().put("ok", true).done();
    }

    @PostMapping("/sessions/{id}/approval")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody ApprovalBody body) {
        AgentSession s = session(id);
        ApprovalRequest pending = s.pendingApproval();
        if (pending == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有等着的审批");
        if (body.id() != null && !body.id().equals(pending.id()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这道审批已经换了一道");
        ReviewDecision.Kind k;
        try {
            k = ReviewDecision.Kind.valueOf(body.decision());
        } catch (RuntimeException e) {
            throw bad("不认识的决定：" + body.decision());
        }
        if (!pending.allows(k)) throw bad("这道审批没有这一项：" + k);
        boolean ok = s.resolveApproval(new ReviewDecision(k, body.feedback()));
        return Views.m().put("ok", ok).done();
    }

    @PostMapping("/sessions/{id}/answer")
    public Map<String, Object> answer(@PathVariable String id, @RequestBody AnswerBody body) {
        AgentSession s = session(id);
        if (s.pendingQuestion() == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "没有等着回答的问题");
        ClarifyService.Answer a = Boolean.TRUE.equals(body.dismissed()) || body.answers() == null
                ? new ClarifyService.Dismissed() : new ClarifyService.Answered(body.answers());
        return Views.m().put("ok", s.answerQuestion(a)).done();
    }

    @PatchMapping("/sessions/{id}")
    public Map<String, Object> settings(@PathVariable String id, @RequestBody SettingsBody body) {
        AgentSession s = session(id);
        if (body.model() != null) {
            String ref = ModelRef.parse(body.model()).toString();
            if (!rt.modelRefs().contains(ref)) throw bad("没有这个模型：" + ref);
            s.setModel(ref);
        }
        if (body.thinking() != null) s.setThinking(body.thinking().isBlank() ? null : body.thinking());
        if (body.agent() != null) {
            if (s.busy()) throw bad("正忙着，等这一轮做完再换 agent");
            rt.selectAgent(s, body.agent());
        }
        if (body.title() != null) {
            s.setTitle(body.title());
            Long conv = rt.conversationOf(s);
            if (conv != null) rt.setTitle(conv, body.title());
        }
        hub.sessionsChanged();
        return Views.snapshot(rt, s);
    }

    @PostMapping("/sessions/{id}/attachments")
    public Map<String, Object> attach(@PathVariable String id, @RequestBody AttachBody body) {
        if (body.mediaType() == null || !body.mediaType().startsWith("image/")) throw bad("只收图片");
        try {
            Base64.getDecoder().decode(body.data());
        } catch (RuntimeException e) {
            throw bad("图片数据不是 base64");
        }
        AgentSession s = session(id);
        int n = s.attach(body.name() == null ? "image" : body.name(), new Block.Image(body.mediaType(), body.data()));
        return Views.m().put("count", n).put("attachments", s.attachments()).done();
    }

    @DeleteMapping("/sessions/{id}/attachments")
    public Map<String, Object> clearAttachments(@PathVariable String id) {
        session(id).clearAttachments();
        return Views.m().put("count", 0).done();
    }

    @GetMapping("/sessions/{id}/tools/{toolUseId}")
    public Map<String, Object> toolDetail(@PathVariable String id, @PathVariable String toolUseId) {
        Map<String, Object> d = Views.toolDetail(session(id), toolUseId);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这次工具调用");
        return d;
    }

    // ── 存着的对话 ──────────────────────────────────────────────────────

    @GetMapping("/conversations")
    public List<Map<String, Object>> conversations(@RequestParam(defaultValue = "100") int limit,
                                                   @RequestParam(required = false) String project) {
        int n = Math.max(1, Math.min(limit, 1000));
        if (project == null) return rt.conversations(n).stream().map(Views::conversation).toList();
        // project=none ⇒ 不在任何（现存）项目里的
        java.util.Set<String> known = new java.util.HashSet<>();
        for (var p : rt.projects().list()) known.add(p.id());
        return rt.conversations(0).stream()
                .filter(c -> project.equals("none") ? c.project() == null || !known.contains(c.project()) : project.equals(c.project()))
                .limit(n).map(Views::conversation).toList();
    }

    @GetMapping("/conversations/search")
    public List<Map<String, Object>> search(@RequestParam String q, @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionStore.SearchHit h : rt.search(q, Math.max(1, Math.min(limit, 200))))
            out.add(Views.m().put("conversation", h.conversation()).put("seq", h.seq()).put("role", h.role())
                    .put("snippet", h.snippet()).put("title", h.title()).done());
        return out;
    }

    @PostMapping("/conversations/{conv}/open")
    public Map<String, Object> open(@PathVariable long conv) {
        return Views.sessionSummary(rt, hub.open(conv));
    }

    @DeleteMapping("/conversations/{conv}")
    public ResponseEntity<Void> deleteConversation(@PathVariable long conv) {
        for (AgentSession s : rt.sessions()) {
            Long c = rt.conversationOf(s);
            if (c != null && c == conv) throw bad("这段对话还开着，先关掉");
        }
        rt.deleteConversation(conv);
        return ResponseEntity.noContent().build();
    }

    // ── 模型 / 权限 / 工具 / MCP ────────────────────────────────────────

    @GetMapping("/models")
    public Map<String, Object> models() {
        List<Map<String, Object>> ms = new ArrayList<>();
        for (ModelMeta mm : rt.modelMetas()) ms.add(Views.model(rt, mm));
        List<Map<String, Object>> providers = new ArrayList<>();
        for (Models.ProviderCandidate c : rt.providerCandidates())
            providers.add(Views.m().put("id", c.id()).put("label", c.label()).put("enabled", c.enabled()).put("hasKey", c.hasKey()).done());
        io.ailink.agentforge.agent.models.Problem problem = rt.problem();
        return Views.m().put("current", rt.currentModel()).put("models", ms).put("providers", providers)
                .put("problem", problem == null ? null : problem.message()).done();
    }

    @PutMapping("/models/default")
    public Map<String, Object> selectModel(@RequestBody RefBody body) {
        String ref = ModelRef.parse(body.ref()).toString();
        if (!rt.modelRefs().contains(ref)) throw bad("没有这个模型：" + ref);
        rt.setDefaultModel(ref);
        return models();
    }

    /** 存一家 provider 的 key（加密存进配置目录的凭据库；回包里不带 key）。 */
    @PutMapping("/providers/{id}/key")
    public Map<String, Object> setKey(@PathVariable String id, @RequestBody KeyBody body) {
        if (body.key() == null || body.key().isBlank()) throw bad("key 是空的");
        rt.models().setKey(id, body.key().strip());
        rt.refreshModels();
        return models();
    }

    @DeleteMapping("/providers/{id}/key")
    public Map<String, Object> removeKey(@PathVariable String id) {
        rt.models().removeKey(id);
        rt.refreshModels();
        return models();
    }

    @GetMapping("/permissions")
    public Map<String, Object> permissions() {
        return Views.permissions(rt);
    }

    @PutMapping("/permissions")
    public Map<String, Object> setPermissions(@RequestBody PresetBody body) {
        rt.applyPreset(ApprovalPreset.parse(body.preset()));
        return Views.permissions(rt);
    }

    @GetMapping("/tools")
    public List<Map<String, Object>> tools(@RequestParam(required = false) String session) {
        List<AgentTool> ts;
        AgentSession s = session == null ? null : hub.find(session);
        if (s == null && !hub.sessions().isEmpty()) s = hub.sessions().getFirst();
        if (s != null) ts = s.tools();
        else {
            ts = new ArrayList<>(rt.toolset().defaultTools(rt.workspace()));
            ts.addAll(rt.toolset().mcpTools());
        }
        return ts.stream().map(t -> Views.tool(t, rt)).toList();
    }

    @GetMapping("/mcp")
    public List<Map<String, Object>> mcp() {
        McpManager m = rt.mcp();
        if (m == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpServerInfo i : m.servers()) out.add(Views.mcpServer(i));
        return out;
    }

    @PostMapping("/mcp/{name}/reconnect")
    public List<Map<String, Object>> mcpReconnect(@PathVariable String name) {
        McpManager m = rt.mcp();
        if (m == null) throw bad("MCP 没开");
        m.reconnect(name);
        return mcp();
    }

    // ── 工具函数 ───────────────────────────────────────────────────────

    static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException e) {
            return "服务器";
        }
    }

    private AgentSession session(String id) {
        AgentSession s = hub.find(id);
        if (s == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "没有这段会话：" + id);
        return s;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatus(ResponseStatusException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(m);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBad(IllegalArgumentException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.getMessage());
        return ResponseEntity.badRequest().body(m);
    }
}
