package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalPreset;
import io.ailink.agentforge.agent.attach.Attach;
import io.ailink.agentforge.agent.mcp.McpManager;
import io.ailink.agentforge.agent.mcp.McpServerInfo;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.models.Problem;
import io.ailink.agentforge.agent.registry.AgentDef;
import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.agent.sandbox.SandboxMode;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.store.SessionStore;
import io.ailink.agentforge.sandbox.approval.ApprovalPolicy;
import io.ailink.agentforge.sandbox.execpolicy.ExecPolicyManager;
import io.ailink.agentforge.tui.CompletionItem;
import io.ailink.agentforge.tui.theme.Themes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 输入框里的 {@code /} 命令（与行模式 REPL 同一套：/help /model /models /agents /new /sessions /open /search /compact
 * /permissions /approvals /sandbox /network /rules /thinking /mcp /quit，外加 /theme /attach /title /steer /stop /copy /side）。
 * 键位精简之后，原来那些 {@code C-c} 键的事都在这里。输出写成记录区里压暗的一格（只在界面上，不进对话历史）；
 * 能用选择器的（/model /agents /sessions /permissions /thinking /theme 不带参数时）弹底栏的选择器（{@link ListPanel}）。
 */
final class SlashCommands {
    record Cmd(String name, String args, String doc) { }

    static final List<Cmd> COMMANDS = List.of(
            new Cmd("/help", null, "this help"),
            new Cmd("/model", "[ref]", "show / switch the model (no argument: picker)"),
            new Cmd("/models", null, "list the available models"),
            new Cmd("/agents", "[name]", "agents overview: main + every sub-agent run (with a name: switch the main agent)"),
            new Cmd("/new", null, "start a new conversation"),
            new Cmd("/sessions", null, "recent conversations (picker)"),
            new Cmd("/open", "<id>", "open a stored conversation and continue it"),
            new Cmd("/search", "<words>", "full-text search over history (3+ chars)"),
            new Cmd("/compact", null, "compact the context now"),
            new Cmd("/steer", "[text]", "interrupt this turn and send the queued messages plus the input now"),
            new Cmd("/stop", null, "stop the running turn"),
            new Cmd("/copy", null, "copy the last answer"),
            new Cmd("/side", null, "toggle the side panel (todo / sub-agents / MCP / permissions)"),
            new Cmd("/permissions", "[preset]", "show permissions / switch preset (read-only / auto / full-access)"),
            new Cmd("/approvals", "<policy>", "approval policy: untrusted / on-failure / on-request / never / granular"),
            new Cmd("/sandbox", "<mode>", "sandbox: read-only / workspace-write / danger-full-access"),
            new Cmd("/network", "on|off", "network access inside the sandbox"),
            new Cmd("/rules", "[reload]", "exec rule files (.rules) / reload them"),
            new Cmd("/thinking", "[level]", "show / set the thinking level (no argument: picker)"),
            new Cmd("/mcp", null, "MCP server status"),
            new Cmd("/theme", "[name]", "switch the colour theme (no argument: picker)"),
            new Cmd("/attach", "<path>", "attach an image / PDF to the next message"),
            new Cmd("/title", "<text>", "rename this conversation"),
            new Cmd("/quit", null, "quit"));

    private final AgentWorkbench wb;
    private final AgentRuntime rt;

    SlashCommands(AgentWorkbench wb) {
        this.wb = wb;
        this.rt = wb.rt;
    }

    /** 补全候选（/ 表）。 */
    List<CompletionItem> items() {
        List<CompletionItem> out = new ArrayList<>();
        for (Cmd c : COMMANDS) {
            String detail = (c.args() == null ? "" : c.args() + "  ") + Msg.t(c.doc());
            out.add(new CompletionItem(c.name(), c.args() == null ? c.name() : c.name() + " ", detail, "/", null, c));
        }
        return out;
    }

    private void say(String s) { wb.note(s, null); }

    private void warn(String s) { wb.note(s, "warning"); }

    private void err(String s) { wb.note("✗ " + s, "error"); }

    /** 跑一条 / 命令（整行）。 */
    void run(String line) {
        String[] parts = line.strip().split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].strip() : "";
        AgentSession s = wb.root;
        try {
            switch (cmd) {
                case "/quit", "/exit", "/q" -> wb.quit();
                case "/help", "/?" -> say(help());
                case "/model" -> {
                    if (arg.isEmpty()) wb.pickModel(); else wb.selectModel(arg);
                }
                case "/models" -> {
                    List<String> refs = rt.modelRefs();
                    StringBuilder sb = new StringBuilder();
                    if (refs.isEmpty()) {
                        Problem p = rt.problem();
                        sb.append(Msg.t("no models available")).append(p == null ? "" : ": " + p.message());
                    }
                    for (String r : refs) sb.append(r.equals(s.model()) ? "* " : "  ").append(r).append('\n');
                    say(sb.toString().stripTrailing());
                }
                case "/agents" -> {
                    if (arg.isEmpty()) wb.openOverview(); else wb.selectAgent(arg);
                }
                case "/steer" -> {
                    if (!arg.isEmpty()) wb.prompt.setContents(arg);
                    wb.steerNow();
                }
                case "/stop" -> wb.stop();
                case "/copy" -> {
                    String raw = wb.view.lastAssistantRaw();
                    if (raw == null) { warn(Msg.t("no answer to copy yet")); return; }
                    wb.ed.killNew(raw);
                    wb.ed.message(Msg.f("copied %d chars", raw.length()));
                }
                case "/side" -> wb.toggleSide();
                case "/new", "/clear" -> wb.newConversation();
                case "/sessions" -> wb.pickSession();
                case "/open" -> {
                    if (arg.isEmpty()) { wb.pickSession(); return; }
                    wb.openConversation(Long.parseLong(arg.replace("#", "")));
                }
                case "/search" -> {
                    List<SessionStore.SearchHit> hs = rt.search(arg, 20);
                    StringBuilder sb = new StringBuilder(hs.isEmpty() ? Msg.t("no hits") : Msg.f("%d hits — /open <id> to open", hs.size()));
                    for (SessionStore.SearchHit h : hs)
                        sb.append('\n').append(String.format("%5d#%-4d %s  %s  — %s", h.conversation(), h.seq(), h.role(), h.snippet(), h.title()));
                    say(sb.toString());
                }
                case "/compact" -> {
                    if (s.busy()) { warn(Msg.t("this turn is still running")); return; }
                    say(Msg.t("compacting…"));
                    Thread.ofVirtual().start(() -> {
                        boolean ok = s.compact();
                        if (ok) rt.save(s);
                        wb.ed.post(() -> say(ok ? Msg.t("compacted") : Msg.t("nothing worth compacting (or no summary came back); history unchanged")));
                    });
                }
                case "/permissions" -> {
                    if (arg.isEmpty()) { wb.pickPreset(); return; }
                    rt.applyPreset(ApprovalPreset.parse(arg));
                    say(Msg.f("permissions: %s", s.permissions().settings().describe()));
                }
                case "/approvals" -> {
                    if (!arg.isEmpty()) rt.setPermissionSettings(rt.permissionSettings().withApproval(ApprovalPolicy.parse(arg)));
                    say(Msg.f("permissions: %s", s.permissions().settings().describe()));
                }
                case "/sandbox" -> {
                    if (!arg.isEmpty()) rt.setPermissionSettings(rt.permissionSettings().withSandbox(SandboxMode.parse(arg)));
                    say(Msg.f("permissions: %s", s.permissions().settings().describe()) + "\n" + rt.sandboxStatus());
                }
                case "/network" -> {
                    switch (arg) {
                        case "on" -> rt.setPermissionSettings(rt.permissionSettings().withNetworkAccess(true));
                        case "off" -> rt.setPermissionSettings(rt.permissionSettings().withNetworkAccess(false));
                        case "" -> { }
                        default -> { warn(Msg.t("usage: /network on|off")); return; }
                    }
                    say(Msg.f("network: %s", s.permissions().settings().networkEnabled() ? Msg.t("on") : Msg.t("off"))
                            + Msg.t(" (danger-full-access always has network)"));
                }
                case "/rules" -> {
                    StringBuilder sb = new StringBuilder();
                    if (arg.equals("reload")) {
                        for (String w : rt.reloadRules()) sb.append("⚠ ").append(w).append('\n');
                        sb.append(Msg.t("rules reloaded")).append('\n');
                    }
                    ExecPolicyManager m = s.permissions().execPolicy();
                    sb.append(Msg.f("rule dirs: %s (user)  %s (project)", rt.userRulesDir(), AgentRuntime.projectRulesDir(s.workspace().root())));
                    for (Path f : m.files()) sb.append("\n  ").append(f);
                    if (m.files().isEmpty()) sb.append('\n').append(Msg.t("  (no rule files yet; built-in rules still apply)"));
                    sb.append('\n').append(Msg.f("\"always allow\" is written to %s", m.amendmentFile()));
                    for (String w : m.warnings()) sb.append("\n⚠ ").append(w);
                    for (List<String> p : m.current().allowedPrefixes()) sb.append("\n  allow ").append(String.join(" ", p));
                    say(sb.toString());
                }
                case "/thinking" -> {
                    if (arg.isEmpty()) { wb.pickThinking(); return; }
                    wb.setThinking(arg.equals("default") || arg.equals("off") ? null : arg);
                }
                case "/mcp" -> {
                    McpManager m = rt.mcp();
                    if (m == null) { say(Msg.t("MCP is off (--no-mcp)")); return; }
                    List<McpServerInfo> ss = m.servers();
                    StringBuilder sb = new StringBuilder(ss.isEmpty() ? Msg.t("no MCP servers configured") : "MCP");
                    for (McpServerInfo i : ss) {
                        sb.append('\n').append(i.dot()).append(' ').append(i.name()).append("  ").append(i.status().name().toLowerCase(Locale.ROOT))
                                .append("  ").append(Msg.f("%d tools", i.toolCount())).append(i.error() == null ? "" : "  " + i.error());
                    }
                    say(sb.toString());
                }
                case "/theme" -> {
                    if (arg.isEmpty()) wb.pickTheme();
                    else if (Themes.find(arg) == null) warn(Msg.f("unknown theme: %s", arg));
                    else { wb.ed.loadTheme(arg); wb.ed.message(Msg.f("theme: %s", arg)); }
                }
                case "/attach" -> {
                    if (arg.isEmpty()) { warn(Msg.t("usage: /attach <path>")); return; }
                    Block.Image img = Attach.attachImage(s.workspace(), arg);
                    int n = s.attach(arg, img);
                    say(Msg.f("attached %s (%d pending, sent with the next message)", arg, n));
                }
                case "/title" -> {
                    Long conv = rt.conversationOf(s);
                    s.setTitle(arg);
                    if (conv != null) rt.setTitle(conv, arg);
                    say(Msg.f("title: %s", arg));
                }
                default -> warn(Msg.f("unknown command %s (/help lists them)", cmd));
            }
        } catch (RuntimeException e) {
            err(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    String help() {
        StringBuilder sb = new StringBuilder(Msg.t("Commands")).append('\n');
        for (Cmd c : COMMANDS) {
            String head = c.name() + (c.args() == null ? "" : " " + c.args());
            sb.append("  ").append(io.ailink.agentforge.tui.text.Width.padRight(head, 26)).append(' ').append(Msg.t(c.doc())).append('\n');
        }
        sb.append(Msg.t("Keys")).append('\n');
        for (String[] k : KEYS) sb.append("  ").append(io.ailink.agentforge.tui.text.Width.padRight(k[0], 26)).append(' ').append(Msg.t(k[1])).append('\n');
        List<String> agents = new ArrayList<>();
        for (AgentDef d : rt.registry().visibleDefs()) agents.add(d.name());
        sb.append(Msg.f("agents: %s", String.join(", ", agents)));
        return sb.toString();
    }

    static final String[][] KEYS = {
            {"RET / C-j (M-RET)", "send / newline"},
            {"↑ ↓ / M-p M-n", "move by line; at the edge, history"},
            {"Esc", "idle: clear input · running: twice to stop · approval: deny · question: dismiss"},
            {"C-c C-c", "stop the running turn (idle: how to quit)"},
            {"C-c m / k / p", "pick model / thinking level / permission preset"},
            {"C-c l", "conversations (first item = new conversation)"},
            {"C-c a", "agents overview (main + every sub-agent; RET opens one)"},
            {"C-x o", "transcript ↔ input"},
            {"C-x C-c", "quit"},
            {"PgUp / PgDn", "scroll the transcript"},
            {"/  @  M-x", "command menu / mention a file / all commands"},
            {"记录区里 TAB n p RET q", "fold / blocks / enter or open / back to input"},
            {"M-w", "copy the block (in the input: the last answer)"},
            {"C-s / C-r", "search the transcript"},
            {"子 agent 里 ← →", "siblings (input empty); /agents goes back"},
            {"审批面板里 y p a d b n 1-9", "answer inside the panel"},
            {"C-h k / C-h b", "describe a key / list all keys"},
    };
}
