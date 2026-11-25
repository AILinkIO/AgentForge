package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.session.GalleryImage;
import io.ailink.agentforge.agent.todo.Todo;
import io.ailink.agentforge.agent.tools.Diff;
import io.ailink.agentforge.agent.tools.Preview;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.agent.transcript.SubStep;
import io.ailink.agentforge.tui.Editor;
import io.ailink.agentforge.tui.Seg;
import io.ailink.agentforge.tui.text.Width;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记录区条目 ⇒ {@link Chunk}。样子照 OpenAI Codex 的 history cell：一条一格，格与格之间空一行。
 * <ul>
 *   <li>你说的话：{@code › } 打头，整块铺一层很淡的衬底（上下各垫一行）；</li>
 *   <li>回答：{@code • } 打头的 markdown（{@link Markdown}），续行垫两格；</li>
 *   <li>思考：暗色斜体，落地后折成一行 {@code • 思考了 3.2s}（TAB 展开）；正在想的那段不折；</li>
 *   <li>命令：{@code • Running cmd} / {@code • Ran cmd}（成功绿点、失败红点），输出用 {@code └} 收在下面，多了只露头尾
 *       （{@code … +N 行}，TAB 展开）；</li>
 *   <li>连着的只读工具（读文件、列目录、搜索…）并成一格 {@code • Explored}，每件一行 {@code Read a, b} / {@code Search x}；</li>
 *   <li>改文件：{@code • Edited path (+a −b)} + 带行号的 diff；待办：{@code • Updated Plan} + ✔ / □ 清单；
 *       网络：{@code • Searched} / {@code • Fetched}；其他工具 / MCP：{@code • Called name(args)}；</li>
 *   <li>子 agent：{@code • 子 agent [who · 模型] 描述 (N 步 · 8.4s)}，步骤用树线收在下面（RET 走进去）；</li>
 *   <li>错误 / 停下：{@code ■} 打头；审批结果：{@code ✔ 你批准了…} / {@code ✗ 你拒绝了…}；</li>
 *   <li>宿主拼的虚条目（{@link Live}）：会话头卡片、提示、提议的改动（审批中）、正在想的思考。</li>
 * </ul>
 * 每行软折行时续行都按这一格的缩进对齐（覆盖层的续行前缀）。排版纯函数：只读 {@link Ctx}，不碰编辑器状态。
 */
final class Blocks {
    private Blocks() { }

    /** 排版要从宿主拿的东西。 */
    interface Ctx {
        /** 某次工具调用的完整结果文本（记录区的条目只留前 200 字）；不知道 ⇒ null。 */
        String fullResult(String toolUseId);

        /** sub-run（子 agent 那一趟）；不知道 ⇒ null。 */
        SubRun subRun(String id);

        /** 挂在某处的图片（owner = {@code user:k} / {@code tool:<id>}）。 */
        List<GalleryImage> images(String owner);

        /** 走进那一趟子会话（组头上 RET）。 */
        void enterChild(Editor ed, String runId);

        /** 打开一张图（图片占位行上 RET）。 */
        void openImage(Editor ed, GalleryImage g);

        /** 记录区正文宽（列）：表格、分隔线、会话头卡片按它排。 */
        default int width() { return 80; }

        /** 打开链接（链接上 RET / 单击）。 */
        default void openLink(Editor ed, String url) { }

        /** 某次工具调用花了多久（毫秒）；不知道 ⇒ null。 */
        default Long toolMs(String toolUseId) { return null; }

        /** 这次改文件的调用审批时给看过的预览（真 diff，带真行号）；没审批过 ⇒ null。 */
        default String patchPreview(String toolUseId) { return null; }

        /** 路径写成相对工作区根的样子（在根外面就原样）。 */
        default String rel(String path) { return path; }
    }

    /** 宿主拼的虚条目（活区、合并后的工具格）用的 meta 键。 */
    static final class Live {
        private Live() { }

        static final String KEY = "tui-live";

        static Entry note(String text, String face) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (face != null) m.put("face", face);
            return virtual("note", text, m);
        }

        /** 现成排好的几行（会话头卡片之类）：每行一串段。 */
        static Entry lines(List<List<Seg>> rows, String raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", List.copyOf(rows));
            return virtual("lines", raw, m);
        }

        static Entry approval(ApprovalRequest r, String who) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request", r);
            if (who != null) m.put("who", who);
            return virtual("approval", r.id(), m);
        }

        static Entry thinking(String text) { return virtual("thinking", text, Map.of()); }

        /** 一次工具调用连同它的结果（结果还没回来 ⇒ result 缺省）。 */
        static Entry cell(Entry call, Entry result) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("call", call);
            if (result != null) m.put("result", result);
            return virtual("cell", "", m);
        }

        /** 连着的几件只读工具（Explored 格）：calls 按顺序，results 按 tool-use-id。 */
        static Entry explore(List<Entry> calls, Map<String, Entry> results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", List.copyOf(calls));
            m.put("results", new LinkedHashMap<>(results));
            return virtual("explore", "", m);
        }

        private static Entry virtual(String kind, String text, Map<String, Object> extra) {
            Map<String, Object> m = new LinkedHashMap<>(extra);
            m.put(KEY, kind);
            return new Entry(Entry.Role.SYSTEM, text, m);
        }

        static String kind(Entry e) { return e.meta(KEY) instanceof String s ? s : null; }
    }

    /** 续行 / 树线下面那层的缩进。 */
    static final String INDENT = "  ";
    static final String TREE = "  └ ";
    static final String TREE_REST = "    ";

    static final int RESULT_MAX_LINES = 400;
    /** 命令 / 工具输出露几行（多了只露头 2 尾 2，中间折起来；Codex 是 5）。 */
    static final int OUTPUT_PREVIEW = 5;
    /** diff 露几行（多了后面折起来）。 */
    static final int DIFF_PREVIEW = 16;

    /** 只读、可以并进 Explored 格的工具。 */
    static final Set<String> EXPLORE_TOOLS = Set.of("read-file", "list-dir", "search-text", "find-files", "find-symbol",
            "symbol-refs", "read-form", "list-forms", "memory-list", "memory-read", "impact", "worktree-list");

    /**
     * 排一条。{@code prev} 是上一条（定前面要不要空一行），{@code userNo} 是这条之前（含）有几句人话（图片 owner 用），
     * {@code index} 是它在列表里的位置（思考段的折叠键）。
     */
    static Chunk render(Entry e, Entry prev, int userNo, int index, Ctx ctx) {
        Chunk c = new Chunk();
        String live = Live.kind(e);
        if (live != null) { renderLive(c, e, prev, live, index, ctx); return c; }
        switch (e.role()) {
            case USER -> renderUser(c, e, prev, userNo, ctx);
            case ASSISTANT -> {
                if (e.flag("thinking")) renderThinking(c, e, prev, index, false);
                else renderAssistant(c, e, prev, ctx);
            }
            case TOOL -> {
                if (e.flag("sub-step")) renderSubStep(c, e);
                else if (e.isCall() && e.meta("group") != null) renderGroup(c, e, prev, ctx);
                else if (e.isCall()) renderCell(c, e, null, prev, ctx);
                else renderOrphanResult(c, e, ctx);
            }
            case SYSTEM -> renderSystem(c, e, prev);
        }
        return c;
    }

    /**
     * 显示前把扁平的条目并成格：调用 + 紧跟着的结果 ⇒ 一格（{@link Live#cell}）；连着的只读工具 ⇒ 一格 Explored。
     * 输入要先过 {@link AgentWorkbench#pairResults}（结果已挪到自己的调用下面）。
     */
    static List<Entry> cells(List<Entry> in) {
        List<Entry> out = new ArrayList<>(in.size());
        List<Entry> calls = new ArrayList<>();
        Map<String, Entry> results = new LinkedHashMap<>();
        for (int i = 0; i < in.size(); i++) {
            Entry e = in.get(i);
            boolean plainCall = e.isCall() && e.meta("group") == null && !e.flag("sub-step") && Live.kind(e) == null;
            if (!plainCall) {
                flushExplore(out, calls, results);
                out.add(e);
                continue;
            }
            Entry r = null;
            if (i + 1 < in.size()) {
                Entry n = in.get(i + 1);
                if (n.role() == Entry.Role.TOOL && !n.isCall() && !n.flag("sub-step") && n.toolUseId() != null && n.toolUseId().equals(e.toolUseId())) {
                    r = n;
                    i++;
                }
            }
            if (EXPLORE_TOOLS.contains(str(e.meta("tool-name")))) {
                calls.add(e);
                if (r != null) results.put(e.toolUseId(), r);
            } else {
                flushExplore(out, calls, results);
                out.add(Live.cell(e, r));
            }
        }
        flushExplore(out, calls, results);
        return out;
    }

    private static void flushExplore(List<Entry> out, List<Entry> calls, Map<String, Entry> results) {
        if (calls.isEmpty()) return;
        out.add(Live.explore(calls, results));
        calls.clear();
        results.clear();
    }

    // ── 基本排法 ─────────────────────────────────────────────────────────

    private static void gap(Chunk c, Entry prev) {
        if (prev != null) c.add("\n");
    }

    /** 一行：先垫 lead（lead 的 face），再写段；续行垫 cont（cont 的 face）。 */
    private static void row(Chunk c, String lead, String leadFace, List<Seg> segs, String cont, String contFace) {
        int a = c.pos();
        c.add(lead, leadFace);
        for (Seg s : segs) c.add(s.text(), s.face());
        if (cont != null) c.prefix(a, c.pos(), List.of(Seg.of(cont, contFace)));
        c.add("\n");
    }

    /** 格的第一行：{@code • } + 段（续行垫两格）。 */
    private static void head(Chunk c, String bulletFace, List<Seg> segs) {
        row(c, "• ", bulletFace, segs, INDENT, null);
    }

    /** 树线收着的几行：第一行 {@code   └ }，之后 {@code     }。 */
    private static void tree(Chunk c, List<List<Seg>> lines) {
        for (int i = 0; i < lines.size(); i++)
            row(c, i == 0 ? TREE : TREE_REST, "agent-tree", lines.get(i), TREE_REST, null);
    }

    /**
     * 输出（命令 / 工具结果）：不超过 {@link #OUTPUT_PREVIEW} 行全露；多了露头 2 尾 2，中间折成 {@code … +N 行}（TAB 展开）。
     * 第一行垫 {@code first}，之后垫 {@code rest}。
     */
    private static void output(Chunk c, List<String> ls, String face, String first, String rest, String foldKey, List<String> faces) {
        int n = Math.min(ls.size(), RESULT_MAX_LINES);
        int foldA = -1;
        boolean folding = n > OUTPUT_PREVIEW;
        for (int i = 0; i < n; i++) {
            if (folding && i == 2) foldA = c.pos();
            String f = faces != null && i < faces.size() && faces.get(i) != null ? faces.get(i) : face;
            row(c, i == 0 ? first : rest, "agent-tree", List.of(Seg.of(ls.get(i), f)), rest, null);
            if (folding && i == n - 3) {
                c.fold(foldA, c.pos(), List.of(Seg.of(rest, "agent-tree"), Seg.of(Msg.f("… +%d lines (TAB to expand)", n - 4), "agent-dim")),
                        foldKey, true, true);
            }
        }
        if (ls.size() > n) c.add(rest + Msg.f("… %d more lines", ls.size() - n) + "\n", "agent-dim");
    }

    // ── 你说的话 / 回答 / 思考 ───────────────────────────────────────────

    private static void renderUser(Chunk c, Entry e, Entry prev, int userNo, Ctx ctx) {
        gap(c, prev);
        int a = c.pos();
        c.add("\n");
        List<String> ls = lines(e.text());
        for (int i = 0; i < ls.size(); i++) row(c, i == 0 ? "› " : INDENT, "agent-user-bar", List.of(Seg.of(ls.get(i), "agent-user")), INDENT, null);
        c.add("\n");
        c.ext(a, c.pos(), "agent-user");
        c.raw = e.text();
        c.blockId = "user";
        images(c, ctx, "user:" + userNo);
    }

    private static void renderAssistant(Chunk c, Entry e, Entry prev, Ctx ctx) {
        if (e.text().isBlank()) return;
        gap(c, prev);
        Markdown.render(c, e.text(), ctx.width(), "• ", INDENT, "agent-bullet", ctx::openLink);
        c.raw = e.text();
        c.blockId = "assistant";
    }

    private static void renderThinking(Chunk c, Entry e, Entry prev, int index, boolean live) {
        if (e.text().isBlank()) return;
        gap(c, prev);
        Object ms = e.meta("think-ms");
        String head = live ? Msg.t("Thinking…") : ms instanceof Number n ? Msg.f("Thought for %s", secs(n.longValue())) : Msg.t("Thought");
        int a = c.pos();
        head(c, "agent-bullet", List.of(Seg.of(head, "agent-reasoning")));
        List<String> ls = lines(e.text());
        for (String line : ls) row(c, INDENT, null, List.of(Seg.of(line, "agent-reasoning")), INDENT, null);
        if (!live) {
            c.fold(a, c.pos(), List.of(Seg.of("• ", "agent-bullet"), Seg.of(head, "agent-reasoning"),
                    Seg.of("  " + Msg.f("(%d lines, TAB to expand)", ls.size()), "agent-dim")), "think:" + index, true, true);
        }
        c.raw = e.text();
        c.blockId = "thinking";
    }

    // ── 工具格 ───────────────────────────────────────────────────────────

    /** 工具行上的参数：只有一个参数就只写值（路径、命令），多个照预览的 k=v。 */
    static String argsShown(Entry e) {
        if (e.meta("args") instanceof JsonNode n && n.isObject()) {
            for (String k : List.of("command", "cmd")) if (scalar(n, k) != null) return clip(scalar(n, k), 200);
            String pat = scalar(n, "pattern") != null ? scalar(n, "pattern") : scalar(n, "query");
            if (pat != null) {
                String where = scalar(n, "path") != null ? scalar(n, "path") : scalar(n, "glob");
                return clip(pat + (where != null && !where.isEmpty() ? " " + Msg.f("in %s", where) : ""), 200);
            }
            for (String k : List.of("path", "file", "url", "description", "name")) if (scalar(n, k) != null) return clip(scalar(n, k), 200);
            if (n.size() == 1) {
                JsonNode v = n.properties().iterator().next().getValue();
                if (v.isValueNode()) return clip(v.asString(), 160);
            }
        }
        return str(e.meta("args-preview"));
    }

    private static String scalar(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v != null && v.isValueNode() && !v.isNull() ? v.asString() : null;
    }

    private static String arg(Entry e, String k) { return e.meta("args") instanceof JsonNode n ? scalar(n, k) : null; }

    private static String resultText(Entry call, Entry result, Ctx ctx) {
        String id = call.toolUseId();
        String full = id == null ? null : ctx.fullResult(id);
        if (full != null) return full.stripTrailing();
        return result == null ? "" : result.text().stripTrailing();
    }

    private static void renderCell(Chunk c, Entry call, Entry result, Entry prev, Ctx ctx) {
        gap(c, prev);
        String name = str(call.meta("tool-name"));
        switch (name) {
            case "run-command", "execute" -> renderExec(c, call, result, ctx);
            case "edit-file", "write-file", "apply-patch", "replace-form", "delete-path" -> renderPatch(c, call, result, ctx);
            case "todo-write" -> renderPlan(c, call, result, ctx);
            case "web-search", "web-fetch" -> renderWeb(c, call, result, ctx);
            case "clarify" -> renderClarify(c, call, result, ctx);
            default -> renderCall(c, call, result, ctx);
        }
        c.raw = name + " " + str(call.meta("args-preview")) + (result == null ? "" : "\n" + resultText(call, result, ctx));
        c.blockId = "tool";
        if (call.toolUseId() != null) images(c, ctx, "tool:" + call.toolUseId());
    }

    private static boolean done(Entry call) { return call.meta("summary") != null; }

    private static String bullet(Entry call, boolean failed) {
        return !done(call) ? "agent-bullet-run" : failed ? "agent-bullet-err" : "agent-bullet-ok";
    }

    private static Seg took(Entry call, Ctx ctx) {
        Long ms = ctx.toolMs(call.toolUseId());
        return ms == null || ms < 100 ? null : Seg.of(" · " + secs(ms), "agent-dim");
    }

    /** run-command：{@code • Running cmd} / {@code • Ran cmd}；输出（stdout + stderr）收在 └ 下面。 */
    private static void renderExec(Chunk c, Entry call, Entry result, Ctx ctx) {
        String cmd = arg(call, "command");
        if (cmd == null) cmd = arg(call, "code");
        if (cmd == null) cmd = str(call.meta("args-preview"));
        String text = resultText(call, result, ctx);
        Exec x = Exec.parse(text);
        boolean failed = call.flag("tool-error") || x.code != null && x.code != 0 || x.timeout;
        List<String> cl = lines(cmd);
        List<Seg> h = new ArrayList<>();
        h.add(Seg.of(done(call) ? Msg.t("Ran") : Msg.t("Running"), "agent-title"));
        h.add(Seg.of(" " + (cl.isEmpty() ? "" : cl.getFirst())));
        if (x.timeout) h.add(Seg.of("  " + Msg.t("(timed out)"), "agent-err"));
        else if (x.code != null && x.code != 0) h.add(Seg.of("  " + Msg.f("(exit %d)", x.code), "agent-err"));
        Seg t = took(call, ctx);
        if (t != null) h.add(t);
        head(c, bullet(call, failed), h);
        for (int i = 1; i < cl.size() && i < 4; i++) row(c, "  │ ", "agent-tree", List.of(Seg.of(cl.get(i))), "  │ ", "agent-tree");
        if (cl.size() > 4) row(c, "  │ ", "agent-tree", List.of(Seg.of(Msg.f("… +%d lines", cl.size() - 4), "agent-dim")), "  │ ", "agent-tree");
        if (!done(call)) return;
        List<String> out = new ArrayList<>();
        List<String> faces = new ArrayList<>();
        if (x.parsed) {
            for (String l : x.stdout) { out.add(l); faces.add(null); }
            for (String l : x.stderr) { out.add(l); faces.add("agent-err"); }
            if (x.note != null) { out.addFirst(x.note); faces.addFirst("agent-warn"); }
        } else if (!text.isEmpty()) {
            for (String l : lines(text)) { out.add(l); faces.add(failed ? "agent-err" : null); }
        }
        if (out.isEmpty()) tree(c, List.of(List.of(Seg.of(Msg.t("(no output)"), "agent-dim"))));
        else output(c, out, "agent-dim", TREE, TREE_REST, "res:" + call.toolUseId(), faces);
    }

    /** run-command 结果的结构（{@code 退出码 N / ── stdout ── / ── stderr ──}）。 */
    record Exec(boolean parsed, Integer code, boolean timeout, String note, List<String> stdout, List<String> stderr) {
        static Exec parse(String text) {
            List<String> ls = lines(text);
            int so = ls.indexOf("── stdout ──"), se = ls.indexOf("── stderr ──");
            if (so < 0 || se < so) return new Exec(false, null, false, null, List.of(), List.of());
            Integer code = null;
            boolean timeout = false;
            String note = null;
            if (!ls.isEmpty()) {
                Matcher m = Pattern.compile("^退出码 (-?\\d+)").matcher(ls.getFirst());
                if (m.find()) code = Integer.parseInt(m.group(1));
                else if (ls.getFirst().startsWith("超时")) { timeout = true; note = ls.getFirst(); }
            }
            List<String> out = new ArrayList<>(ls.subList(so + 1, se));
            List<String> err = new ArrayList<>(ls.subList(se + 1, ls.size()));
            if (out.size() == 1 && out.getFirst().equals("（没有输出）")) out.clear();
            if (err.size() == 1 && err.getFirst().equals("（没有输出）")) err.clear();
            while (!out.isEmpty() && out.getLast().isBlank()) out.removeLast();
            while (!err.isEmpty() && err.getLast().isBlank()) err.removeLast();
            return new Exec(true, code, timeout, note, out, err);
        }
    }

    /** Explored 格：连着的只读工具，每件一行（连着读的文件并成一行），TAB 展开某行看它的结果。 */
    private static void renderExplore(Chunk c, Entry e, Entry prev, Ctx ctx) {
        gap(c, prev);
        @SuppressWarnings("unchecked") List<Entry> calls = (List<Entry>) e.meta("calls");
        @SuppressWarnings("unchecked") Map<String, Entry> results = (Map<String, Entry>) e.meta("results");
        boolean running = calls.stream().anyMatch(x -> !done(x));
        head(c, running ? "agent-bullet-run" : "agent-bullet", List.of(Seg.of(running ? Msg.t("Exploring") : Msg.t("Explored"), "agent-title")));
        StringBuilder raw = new StringBuilder();
        int lineNo = 0;
        for (int i = 0; i < calls.size(); i++) {
            Entry call = calls.get(i);
            String name = str(call.meta("tool-name"));
            List<Entry> group = new ArrayList<>(List.of(call));
            // 连着读的几个文件并成一行（Codex：Read a.rs, b.rs）
            if (isRead(name) && !call.flag("tool-error")) {
                while (i + 1 < calls.size() && isRead(str(calls.get(i + 1).meta("tool-name"))) && !calls.get(i + 1).flag("tool-error")) group.add(calls.get(++i));
            }
            List<Seg> segs = new ArrayList<>();
            boolean err = group.stream().anyMatch(x -> x.flag("tool-error"));
            segs.add(Seg.of(exploreVerb(name), err ? "agent-err" : "agent-accent"));
            segs.add(Seg.of(" "));
            if (isRead(name)) {
                List<String> names = new ArrayList<>();
                for (Entry g : group) {
                    String p = arg(g, "path") != null ? arg(g, "path") : arg(g, "file") != null ? arg(g, "file") : str(g.meta("args-preview"));
                    String base = p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
                    if (!names.contains(base)) names.add(base);
                }
                for (int k = 0; k < names.size(); k++) {
                    if (k > 0) segs.add(Seg.of(", ", "agent-dim"));
                    segs.add(Seg.of(names.get(k)));
                }
            } else {
                String pat = arg(call, "pattern") != null ? arg(call, "pattern") : arg(call, "query") != null ? arg(call, "query") : arg(call, "name");
                String where = arg(call, "path") != null ? arg(call, "path") : arg(call, "glob");
                if (pat != null) {
                    segs.add(Seg.of(pat));
                    if (where != null && !where.isEmpty()) { segs.add(Seg.of(" " + Msg.t("in") + " ", "agent-dim")); segs.add(Seg.of(where)); }
                } else segs.add(Seg.of(argsShown(call)));
            }
            if (group.stream().anyMatch(x -> !done(x))) segs.add(Seg.of(" …", "agent-dim"));
            // 这一行 + 它的结果（折起来，摘要 = 这一行本身）
            int a = c.pos();
            String lead = lineNo == 0 ? TREE : TREE_REST;
            row(c, lead, "agent-tree", segs, TREE_REST + "  ", null);
            boolean any = false;
            for (Entry g : group) {
                Entry r = results.get(g.toolUseId());
                if (r == null) continue;
                String text = resultText(g, r, ctx);
                List<String> ls = text.isEmpty() ? List.of(Msg.t("(empty)")) : lines(text);
                int n = Math.min(ls.size(), 60);
                for (int k = 0; k < n; k++) row(c, TREE_REST + "  ", null, List.of(Seg.of(ls.get(k), g.flag("tool-error") ? "agent-err" : "agent-dim")), TREE_REST + "  ", null);
                if (ls.size() > n) row(c, TREE_REST + "  ", null, List.of(Seg.of(Msg.f("… %d more lines", ls.size() - n), "agent-dim")), null, null);
                raw.append(name).append(' ').append(argsShown(g)).append('\n').append(text).append('\n');
                any = true;
            }
            if (any) {
                List<Seg> sum = new ArrayList<>();
                sum.add(Seg.of(lead, "agent-tree"));
                sum.addAll(segs);
                if (err) {
                    Entry r = results.get(group.getFirst().toolUseId());
                    if (r != null) sum.add(Seg.of("  " + clip(lines(resultText(group.getFirst(), r, ctx)).stream().findFirst().orElse(""), 80), "agent-err"));
                }
                c.fold(a, c.pos(), sum, "explore:" + call.toolUseId(), true, true);
            }
            lineNo++;
        }
        c.raw = raw.toString().stripTrailing();
        c.blockId = "tool";
    }

    private static boolean isRead(String name) { return name.equals("read-file") || name.equals("read-form") || name.equals("memory-read"); }

    private static String exploreVerb(String name) {
        return switch (name) {
            case "read-file", "read-form", "memory-read" -> Msg.t("Read");
            case "list-dir", "list-forms", "memory-list", "worktree-list" -> Msg.t("List");
            case "search-text" -> Msg.t("Search");
            case "find-files", "find-symbol" -> Msg.t("Find");
            case "symbol-refs", "impact" -> Msg.t("Refs");
            default -> name;
        };
    }

    /** 改文件：{@code • Edited path (+a −b)} + 带行号的 diff（多了折起来）。 */
    private static void renderPatch(Chunk c, Entry call, Entry result, Ctx ctx) {
        String name = str(call.meta("tool-name"));
        boolean err = call.flag("tool-error");
        String path = ctx.rel(arg(call, "path") != null ? arg(call, "path") : name.equals("apply-patch") ? "apply-patch" : argsShown(call));
        String text = resultText(call, result, ctx);
        String pv = ctx.patchPreview(call.toolUseId());
        PreviewDiff pd = pv == null ? null : fromPreview(pv, ctx);
        List<FileDiff> files = pd != null && !pd.empty() ? pd.files() : switch (name) {
            case "edit-file" -> {
                String old = arg(call, "old"), nu = arg(call, "new");
                if (old == null) old = arg(call, "old_string");
                if (nu == null) nu = arg(call, "new_string");
                Matcher m = Pattern.compile("第 (\\d+) 行").matcher(text);
                int at = m.find() ? Integer.parseInt(m.group(1)) : 1;
                yield old == null || nu == null ? List.of() : List.of(FileDiff.ofUnified(path, Diff.unified(old, nu, "a", "b", 3), at));
            }
            case "write-file" -> {
                String content = arg(call, "content");
                yield content == null ? List.of() : List.of(FileDiff.added(path, content));
            }
            case "apply-patch" -> FileDiff.ofPatch(arg(call, "patchText") != null ? arg(call, "patchText") : arg(call, "patch"));
            default -> List.of();
        };
        int plus = 0, minus = 0;
        for (FileDiff f : files) { plus += f.plus; minus += f.minus; }
        String verb = switch (name) {
            case "write-file" -> done(call) ? Msg.t("Wrote") : Msg.t("Writing");
            case "delete-path" -> done(call) ? Msg.t("Deleted") : Msg.t("Deleting");
            default -> done(call) ? Msg.t("Edited") : Msg.t("Editing");
        };
        if (err) verb = Msg.t("Edit failed");
        List<Seg> h = new ArrayList<>();
        h.add(Seg.of(verb, "agent-title"));
        String target = files.size() > 1 ? Msg.f("%d files", files.size()) : files.size() == 1 ? files.getFirst().path : path;
        h.add(Seg.of(" " + target));
        if (!files.isEmpty()) {
            h.add(Seg.of(" (", "agent-dim"));
            h.add(Seg.of("+" + plus, "agent-ok"));
            h.add(Seg.of(" ", "agent-dim"));
            h.add(Seg.of("−" + minus, "agent-err"));
            h.add(Seg.of(")", "agent-dim"));
        }
        head(c, bullet(call, err), h);
        if (err) {
            tree(c, lines(text.isEmpty() ? Msg.t("(no detail)") : text).stream().limit(6).map(l -> List.of(Seg.of(l, "agent-err"))).toList());
            return;
        }
        if (!done(call)) return;      // 还没落地（审批中）：diff 在下面「提议的改动」那格里
        if (files.isEmpty()) {
            if (done(call) && !text.isEmpty()) tree(c, List.of(List.of(Seg.of(clip(lines(text).getFirst(), 200), "agent-dim"))));
            return;
        }
        for (FileDiff f : files) {
            if (files.size() > 1) {
                row(c, TREE, "agent-tree", List.of(Seg.of(f.path), Seg.of(" (", "agent-dim"), Seg.of("+" + f.plus, "agent-ok"),
                        Seg.of(" ", "agent-dim"), Seg.of("−" + f.minus, "agent-err"), Seg.of(")", "agent-dim")), TREE_REST, null);
            }
            diffLines(c, f, "diff:" + call.toolUseId() + ":" + f.path);
        }
    }

    /** 一份文件的 diff 行：{@code     12 +text}（行号暗、加减行铺色到行尾）；多了折起来。 */
    static void diffLines(Chunk c, FileDiff f, String foldKey) {
        int digits = 1;
        for (DiffLine l : f.lines) if (l.no > 0) digits = Math.max(digits, String.valueOf(l.no).length());
        String pad = "    ";
        int foldA = -1;
        for (int i = 0; i < f.lines.size(); i++) {
            if (i == DIFF_PREVIEW) foldA = c.pos();
            DiffLine l = f.lines.get(i);
            int a = c.pos();
            if (l.sign == '⋮') {
                c.add(pad + " ".repeat(digits) + " ⋮", "agent-gutter").add("\n");
                continue;
            }
            String num = l.no > 0 ? String.valueOf(l.no) : "";
            c.add(pad + " ".repeat(digits - num.length()) + num + " ", "agent-gutter");
            int t = c.pos();
            c.add(l.sign + l.text);
            String df = l.sign == '+' ? "diff-added" : l.sign == '-' ? "diff-removed" : null;
            c.prefix(a, c.pos(), List.of(Seg.of(pad + " ".repeat(digits + 2))));
            c.add("\n");
            if (df != null) c.ext(t, c.pos(), df);
        }
        if (foldA >= 0) c.fold(foldA, c.pos(), List.of(Seg.of(pad, "agent-gutter"),
                Seg.of(Msg.f("… +%d lines (TAB to expand)", f.lines.size() - DIFF_PREVIEW), "agent-dim")), foldKey, true, true);
    }

    record DiffLine(char sign, int no, String text) { }

    /** 一份文件的改动：路径、加减行数、要画的行。 */
    record FileDiff(String path, int plus, int minus, List<DiffLine> lines) {

        static FileDiff added(String path, String content) {
            List<DiffLine> ls = new ArrayList<>();
            int no = 0;
            for (String l : Blocks.lines(content)) ls.add(new DiffLine('+', ++no, l));
            return new FileDiff(path, ls.size(), 0, ls);
        }

        /** 标准 unified diff（{@code @@ -a,b +c,d @@}）⇒ 带行号的行；offset = 片段在文件里从第几行开始。 */
        static FileDiff ofUnified(String path, String unified, int offset) {
            List<DiffLine> ls = new ArrayList<>();
            int oldNo = 0, newNo = 0, plus = 0, minus = 0;
            boolean first = true;
            Pattern hunk = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");
            for (String l : Blocks.lines(unified)) {
                if (l.startsWith("--- ") || l.startsWith("+++ ")) continue;
                Matcher m = hunk.matcher(l);
                if (m.find()) {
                    if (!first) ls.add(new DiffLine('⋮', 0, ""));
                    first = false;
                    oldNo = Integer.parseInt(m.group(1)) + offset - 1;
                    newNo = Integer.parseInt(m.group(2)) + offset - 1;
                    continue;
                }
                if (l.startsWith("+")) { ls.add(new DiffLine('+', newNo++, l.substring(1))); plus++; }
                else if (l.startsWith("-")) { ls.add(new DiffLine('-', oldNo++, l.substring(1))); minus++; }
                else if (l.startsWith(" ") || l.isEmpty()) { ls.add(new DiffLine(' ', newNo, l.isEmpty() ? "" : l.substring(1))); oldNo++; newNo++; }
            }
            return new FileDiff(path, plus, minus, ls);
        }

        /** apply-patch 的补丁文本（{@code *** Update File: p} / {@code *** Add File:} / {@code *** Delete File:}）⇒ 各文件（没有行号）。 */
        static List<FileDiff> ofPatch(String patch) {
            List<FileDiff> out = new ArrayList<>();
            if (patch == null) return out;
            String path = null;
            List<DiffLine> ls = new ArrayList<>();
            int[] pm = new int[2];
            for (String l : Blocks.lines(patch)) {
                Matcher m = Pattern.compile("^\\*\\*\\* (?:Update|Add|Delete) File: (.+)$").matcher(l);
                if (m.find()) {
                    if (path != null) out.add(new FileDiff(path, pm[0], pm[1], List.copyOf(ls)));
                    path = m.group(1).strip();
                    ls.clear();
                    pm[0] = pm[1] = 0;
                    continue;
                }
                if (path == null || l.startsWith("***")) continue;
                if (l.startsWith("@@")) { if (!ls.isEmpty()) ls.add(new DiffLine('⋮', 0, "")); continue; }
                if (l.startsWith("+")) { ls.add(new DiffLine('+', 0, l.substring(1))); pm[0]++; }
                else if (l.startsWith("-")) { ls.add(new DiffLine('-', 0, l.substring(1))); pm[1]++; }
                else ls.add(new DiffLine(' ', 0, l.startsWith(" ") ? l.substring(1) : l));
            }
            if (path != null) out.add(new FileDiff(path, pm[0], pm[1], List.copyOf(ls)));
            return out;
        }
    }

    /** todo-write：{@code • Updated Plan} + ✔ / □ 清单。 */
    private static void renderPlan(Chunk c, Entry call, Entry result, Ctx ctx) {
        String items = arg(call, "items");
        List<Todo.Item> ts;
        try { ts = items == null ? List.of() : Todo.parse(items); } catch (RuntimeException e) { ts = List.of(); }
        long done = ts.stream().filter(t -> t.status() == Todo.Status.DONE).count();
        List<Seg> h = new ArrayList<>(List.of(Seg.of(Msg.t("Updated Plan"), "agent-title")));
        if (!ts.isEmpty()) h.add(Seg.of(" · " + Msg.f("%d/%d done", done, ts.size()), "agent-dim"));
        head(c, call.flag("tool-error") ? "agent-bullet-err" : "agent-bullet", h);
        List<List<Seg>> rows = new ArrayList<>();
        for (Todo.Item t : ts) {
            String[] lk = todoLook(t.status());
            rows.add(List.of(Seg.of(lk[0], lk[1]), Seg.of(t.text(), lk[2])));
        }
        if (rows.isEmpty() && result != null) rows.add(List.of(Seg.of(clip(resultText(call, result, ctx), 200), "agent-dim")));
        if (!rows.isEmpty()) {
            for (int i = 0; i < rows.size(); i++) row(c, i == 0 ? TREE : TREE_REST, "agent-tree", rows.get(i), TREE_REST + "  ", null);
        }
    }

    /** 待办的样子（Codex 计划）：做完 ✔ 暗 + 删除线，正在做 □ 强调色加粗，没做 □ 暗。⇒ {记号, 记号 face, 文字 face}。 */
    static String[] todoLook(Todo.Status st) {
        return switch (st) {
            case DONE -> new String[]{"✔ ", "agent-todo-mark-done", "agent-todo-done"};
            case DOING -> new String[]{"□ ", "agent-accent-bold", "agent-accent-bold"};
            case PENDING -> new String[]{"□ ", "agent-dim", "agent-todo-pending"};
        };
    }

    /** web-search / web-fetch：{@code • Searched q} / {@code • Fetched url} + 结果头一行（多的折起来）。 */
    private static void renderWeb(Chunk c, Entry call, Entry result, Ctx ctx) {
        boolean search = str(call.meta("tool-name")).equals("web-search");
        boolean err = call.flag("tool-error");
        String what = search ? (arg(call, "query") != null ? arg(call, "query") : argsShown(call)) : (arg(call, "url") != null ? arg(call, "url") : argsShown(call));
        String verb = search ? (done(call) ? Msg.t("Searched") : Msg.t("Searching")) : (done(call) ? Msg.t("Fetched") : Msg.t("Fetching"));
        List<Seg> h = new ArrayList<>(List.of(Seg.of(verb, "agent-title"), Seg.of(" " + what)));
        Seg t = took(call, ctx);
        if (t != null) h.add(t);
        head(c, bullet(call, err), h);
        if (!done(call)) return;
        String text = resultText(call, result, ctx);
        if (text.isEmpty()) return;
        output(c, lines(text), err ? "agent-err" : "agent-dim", TREE, TREE_REST, "res:" + call.toolUseId(), null);
    }

    /** clarify：{@code • 问了你 问题} + {@code └ 你的回答}（题目在底栏的提问面板里答）。 */
    private static void renderClarify(Chunk c, Entry call, Entry result, Ctx ctx) {
        String q = "";
        if (call.meta("args") instanceof JsonNode n && n.get("questions") instanceof JsonNode qs && qs.isArray() && !qs.isEmpty()) {
            q = scalar(qs.get(0), "question");
            if (qs.size() > 1) q = q + "  " + Msg.f("(+%d more)", qs.size() - 1);
        }
        head(c, bullet(call, call.flag("tool-error")), List.of(Seg.of(done(call) ? Msg.t("Asked you") : Msg.t("Asking you"), "agent-title"), Seg.of(" " + q)));
        if (!done(call)) return;
        String text = resultText(call, result, ctx);
        if (!text.isEmpty()) output(c, lines(text), "agent-dim", TREE, TREE_REST, "res:" + call.toolUseId(), null);
    }

    /** 其他工具 / MCP：{@code • Called name(args)} / {@code • Calling …}；结果收在 └ 下面。 */
    private static void renderCall(Chunk c, Entry call, Entry result, Ctx ctx) {
        String name = str(call.meta("tool-name"));
        boolean err = call.flag("tool-error");
        String args = str(call.meta("args-preview"));
        List<Seg> h = new ArrayList<>();
        h.add(Seg.of(done(call) ? Msg.t("Called") : Msg.t("Calling"), "agent-title"));
        h.add(Seg.of(" "));
        h.add(Seg.of(name, "agent-accent"));
        h.add(Seg.of("(" + args + ")", "agent-dim"));
        Seg t = took(call, ctx);
        if (t != null) h.add(t);
        head(c, bullet(call, err), h);
        if (!done(call)) return;
        String text = resultText(call, result, ctx);
        if (text.isEmpty()) { tree(c, List.of(List.of(Seg.of(Msg.t("(empty)"), "agent-dim")))); return; }
        List<String> ls = lines(text);
        if (err) ls.set(0, Msg.f("Error: %s", ls.getFirst()));
        output(c, ls, err ? "agent-err" : "agent-dim", TREE, TREE_REST, "res:" + call.toolUseId(), null);
    }

    /** 没配上调用的结果（子 agent 那组的回话、旧对话里的残片）：{@code └ 头一行}，其余折起来（TAB 展开）。 */
    private static void renderOrphanResult(Chunk c, Entry e, Ctx ctx) {
        String id = e.toolUseId();
        String full = id == null ? null : ctx.fullResult(id);
        String text = (full != null ? full : e.text()).strip();
        List<String> ls = text.isEmpty() ? List.of(Msg.t("(empty)")) : lines(text);
        String face = e.flag("tool-error") ? "agent-err" : "agent-dim";
        row(c, TREE, "agent-tree", List.of(Seg.of(ls.getFirst(), face)), TREE_REST, null);
        if (ls.size() > 1) {
            int a = c.pos();
            int n = Math.min(ls.size(), RESULT_MAX_LINES);
            for (int i = 1; i < n; i++) row(c, TREE_REST, null, List.of(Seg.of(ls.get(i), face)), TREE_REST, null);
            c.fold(a, c.pos(), List.of(Seg.of(TREE_REST), Seg.of(Msg.f("… +%d lines (TAB to expand)", n - 1), "agent-dim")), "res:" + id, true, true);
        }
        c.raw = text;
        c.blockId = "result";
    }

    // ── 子 agent ─────────────────────────────────────────────────────────

    private static boolean ok(String status) {
        return status != null && ("completed".equals(status) || "return-direct".equals(status) || "done".equals(status));
    }

    static String icon(String tool) {
        String t = tool == null ? "" : tool.toLowerCase(Locale.ROOT);
        if (t.equals("task")) return "⊛";
        if (t.contains("todo")) return "≡";
        if (t.contains("search") || t.contains("grep") || t.contains("find") || t.contains("glob")) return "⌕";
        if (t.contains("edit") || t.contains("write") || t.contains("patch") || t.contains("form")) return "✎";
        if (t.contains("delete")) return "✂";
        if (t.contains("run") || t.contains("shell") || t.contains("command") || t.contains("exec")) return "$";
        if (t.contains("web") || t.contains("fetch")) return "⇣";
        if (t.contains("read") || t.contains("list") || t.contains("view")) return "»";
        return "·";
    }

    private static void renderGroup(Chunk c, Entry e, Entry prev, Ctx ctx) {
        gap(c, prev);
        String id = str(e.meta("group"));
        SubRun run = ctx.subRun(id);
        String status = e.meta("sub-status") instanceof String s ? s : run != null ? run.status() : null;
        boolean running = status == null;
        boolean good = ok(status);
        boolean failed = status != null && !good;
        int a = c.pos();
        c.add("• ", running ? "agent-bullet-run" : failed ? "agent-bullet-err" : "agent-bullet-ok");
        c.add(Msg.t("Sub-agent"), "agent-title");
        List<String> tags = new ArrayList<>();
        if (e.flag("sub-bg")) tags.add(Msg.t("background"));
        if (e.meta("sub-who") instanceof String w) tags.add(w);
        if (e.meta("sub-model") instanceof String m) tags.add(AgentWorkbench.shortModel(m));
        if (!tags.isEmpty()) c.add(" [" + String.join(" · ", tags) + "]", "agent-magenta");
        String desc = str(e.meta("args-preview"));
        if (!desc.isEmpty()) c.add(" " + desc);
        int steps = e.meta("sub-steps") instanceof Number n ? n.intValue() : run != null ? run.allSteps().size() : 0;
        StringBuilder rd = new StringBuilder(" (").append(Msg.f("%d steps", steps));
        if (e.meta("sub-ms") instanceof Number ms) rd.append(" · ").append(secs(ms.longValue()));
        if (running) rd.append(" · ").append(Msg.t("running"));
        if (failed) {
            rd.append(" · ").append(switch (status) {
                case "stopped" -> Msg.t("stopped");
                case "max-iterations" -> Msg.t("ran out of iterations");
                default -> Msg.t("error");
            });
            if (e.meta("sub-why") instanceof String why) rd.append(": ").append(clip(why, 120));
        }
        rd.append(")");
        c.add(rd.toString(), failed ? "agent-err" : "agent-dim");
        int headEnd = c.pos();
        c.prefix(a, headEnd, List.of(Seg.of(INDENT)));
        c.add("\n");
        c.act(a + 2, headEnd, ed -> ctx.enterChild(ed, id));
        if (run != null) {
            List<SubStep> ss = run.allSteps();
            int b = c.pos();
            for (int i = 0; i < ss.size(); i++) {
                SubStep s = ss.get(i);
                List<Seg> segs = new ArrayList<>();
                segs.add(Seg.of(s.name(), s.error() ? "agent-err" : "agent-accent"));
                if (s.args() != null && !s.args().isEmpty()) segs.add(Seg.of(" " + s.args()));
                if (s.summary() != null && !s.summary().isEmpty()) segs.add(Seg.of("  " + s.summary(), s.error() ? "agent-err" : "agent-dim"));
                row(c, "  │ ", "agent-tree", segs, "  │   ", "agent-tree");
            }
            if (!ss.isEmpty()) c.fold(b, c.pos(), List.of(Seg.of("  │ ", "agent-tree"),
                    Seg.of(Msg.f("%d steps (TAB to expand, RET to enter)", ss.size()), "agent-dim")), "group:" + id, good, true);
        }
        c.raw = "task " + desc;
        c.blockId = "group";
    }

    /** 旧对话里那一趟子 agent 的步骤（会话对象没留下，只剩一行行摘要）：每步一行 {@code • name args  摘要}。 */
    private static void renderSubStep(Chunk c, Entry e) {
        boolean err = e.flag("tool-error");
        String name = str(e.meta("tool-name"));
        List<Seg> segs = new ArrayList<>();
        segs.add(Seg.of(name, err ? "agent-err" : "agent-accent"));
        String args = str(e.meta("args-preview"));
        if (!args.isEmpty()) segs.add(Seg.of(" " + args));
        if (e.meta("summary") instanceof String s && !s.isEmpty()) segs.add(Seg.of("  " + s, err ? "agent-err" : "agent-dim"));
        head(c, e.meta("summary") == null ? "agent-bullet-run" : err ? "agent-bullet-err" : "agent-bullet-ok", segs);
        c.blockId = "step";
    }

    // ── 系统条 ───────────────────────────────────────────────────────────

    private static void renderSystem(Chunk c, Entry e, Entry prev) {
        gap(c, prev);
        boolean err = e.flag("error");
        String t = e.text();
        boolean stop = t.startsWith("⏹");
        if (stop) t = t.substring(1).strip();
        List<String> ls = lines(t);
        for (int i = 0; i < ls.size(); i++) {
            String line = ls.get(i);
            if (err || stop) {
                String face = err ? "agent-err" : "agent-warn";
                row(c, i == 0 ? "■ " : INDENT, err ? "agent-err-bold" : "agent-warn", List.of(Seg.of(line, face)), INDENT, null);
            } else row(c, i == 0 ? "• " : INDENT, "agent-bullet", List.of(Seg.of(line, "agent-dim")), INDENT, null);
        }
        c.raw = e.text();
        c.blockId = err ? "error" : "system";
    }

    // ── 活区 ─────────────────────────────────────────────────────────────

    private static void renderLive(Chunk c, Entry e, Entry prev, String kind, int index, Ctx ctx) {
        switch (kind) {
            case "cell" -> renderCell(c, (Entry) e.meta("call"), (Entry) e.meta("result"), prev, ctx);
            case "explore" -> renderExplore(c, e, prev, ctx);
            case "note" -> {
                // 连着的几条提示（重试、记下了…）挤在一起，不各空一行
                if (prev != null && !"note".equals(Live.kind(prev))) c.add("\n");
                String face = e.meta("face") instanceof String f ? liveFace(f) : "agent-dim";
                String t = e.text();
                String lead = "• ", leadFace = "agent-bullet";
                if ("agent-warn".equals(face)) { lead = "⚠ "; leadFace = "agent-warn"; if (t.startsWith("⚠")) t = t.substring(1).strip(); }
                else if ("agent-err".equals(face)) { lead = "■ "; leadFace = "agent-err-bold"; if (t.startsWith("✗")) t = t.substring(1).strip(); }
                else if (t.startsWith("✔ ") || t.startsWith("✗ ")) { lead = t.substring(0, 2); leadFace = t.startsWith("✔") ? "agent-ok" : "agent-err"; t = t.substring(2); face = null; }
                List<String> ls = lines(t);
                for (int i = 0; i < ls.size(); i++)
                    row(c, i == 0 ? lead : INDENT, leadFace, List.of(Seg.of(i == 0 ? ls.get(i) : ls.get(i).strip(), i == 0 ? face : "agent-dim")), INDENT, null);
                c.raw = e.text();
                c.blockId = "note";
            }
            case "lines" -> {
                gap(c, prev);
                @SuppressWarnings("unchecked")
                List<List<Seg>> rows = (List<List<Seg>>) e.meta("rows");
                for (List<Seg> r : rows) row(c, "", null, r, INDENT, null);
                c.raw = e.text();
                c.blockId = "note";
            }
            case "thinking" -> renderThinking(c, new Entry(Entry.Role.ASSISTANT, e.text(), Map.of("thinking", true)), prev, -1, true);
            case "approval" -> renderApproval(c, e, prev, ctx);
            default -> c.add(e.text()).add("\n");
        }
    }

    /** 旧的 face 名（宿主传进来的）⇒ 记录区用的。 */
    private static String liveFace(String f) {
        return switch (f) {
            case "warning" -> "agent-warn";
            case "error" -> "agent-err";
            case "success" -> "agent-ok";
            default -> f;
        };
    }

    static String approvalHead(ApprovalRequest r) {
        return switch (r.kind()) {
            case EXEC -> Msg.t("Run this command?");
            case EXEC_RETRY -> Msg.t("The sandbox blocked this command. Run it again outside the sandbox?");
            case PATCH -> Msg.t("Apply these file changes?");
            case NETWORK -> Msg.t("Allow network access?");
            case TOOL -> Msg.t("Call this tool?");
            case SECRET -> Msg.t("Touch a secret file? (once it is in the context it cannot be taken back)");
            case REVIEW -> Msg.t("Keep the changes staged in this turn?");
        };
    }

    /**
     * 审批摆着时记录区底部的那格：有 diff 预览（改文件）就画成 {@code • Proposed Change path (+a −b)} + 带行号的 diff
     * （Codex 的提议改动格）；别的种类详情都在下面的审批面板里，这里只留一行。
     */
    /** 审批预览拆开的样子：各文件的 diff + diff 外面的那几句（语法检查、应用不了的原因…）。 */
    record PreviewDiff(List<FileDiff> files, List<String> notes) {
        int plus() { return files.stream().mapToInt(FileDiff::plus).sum(); }
        int minus() { return files.stream().mapToInt(FileDiff::minus).sum(); }
        boolean empty() { return files.stream().allMatch(f -> f.lines().isEmpty()); }
    }

    /**
     * 审批预览 ⇒ 带行号的各文件 diff。格式（agent-core {@code Preview} / {@code PatchTool.preview}）：每个文件一行标题
     * （{@code write-file 覆盖 /p}、{@code edit-file /p}、{@code A p} / {@code M p} / {@code D p}）+ unified diff；之外的行当附注。
     */
    private static boolean previewHeader(List<String> ls, int i) {
        String l = ls.get(i);
        return i + 1 < ls.size() && ls.get(i + 1).startsWith("--- ") && !l.isEmpty() && "+- @".indexOf(l.charAt(0)) < 0;
    }

    static PreviewDiff fromPreview(String text, Ctx ctx) {
        List<String> ls = lines(text.stripTrailing());
        List<FileDiff> files = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (!ls.isEmpty() && (ls.getFirst().startsWith("--- ") || ls.getFirst().startsWith("@@"))) {
            // 没有标题行的裸 diff：路径取 +++ 那行
            String path = ls.stream().filter(l -> l.startsWith("+++ ")).findFirst().map(l -> l.substring(4).replaceFirst("^b/", "")).orElse("");
            files.add(FileDiff.ofUnified(ctx.rel(path), String.join("\n", ls), 1));
            return new PreviewDiff(files, notes);
        }
        int i = 0;
        while (i < ls.size()) {
            String l = ls.get(i);
            if (!previewHeader(ls, i)) { if (!l.isBlank()) notes.add(l); i++; continue; }
            String path = l.matches("^[AMD] .+") ? l.substring(2) : l.contains(" ") ? l.substring(l.lastIndexOf(' ') + 1) : l;
            int j = i + 1;
            List<String> body = new ArrayList<>();
            for (; j < ls.size() && !previewHeader(ls, j); j++) {
                String b = ls.get(j);
                if (b.isEmpty() || "+- @".indexOf(b.charAt(0)) >= 0) body.add(b); else notes.add(b);
            }
            files.add(FileDiff.ofUnified(ctx.rel(path), String.join("\n", body), 1));
            i = j;
        }
        return new PreviewDiff(files, notes);
    }

    private static void renderApproval(Chunk c, Entry e, Entry prev, Ctx ctx) {
        ApprovalRequest r = (ApprovalRequest) e.meta("request");
        gap(c, prev);
        Preview.Result pv = r.preview();
        String who = e.meta("who") instanceof String w ? "  " + Msg.f("(sub-agent %s)", w) : "";
        if (pv != null && pv.kind() == Preview.Kind.DIFF && pv.text() != null && !pv.text().isBlank()) {
            PreviewDiff pd = fromPreview(pv.text(), ctx);
            String target = pd.files().size() == 1 ? pd.files().getFirst().path() : pd.files().isEmpty() ? String.valueOf(r.toolName()) : Msg.f("%d files", pd.files().size());
            List<Seg> h = new ArrayList<>(List.of(Seg.of(Msg.t("Proposed Change"), "agent-title"), Seg.of(" " + target)));
            if (!pd.empty()) h.addAll(List.of(Seg.of(" (", "agent-dim"), Seg.of("+" + pd.plus(), "agent-ok"), Seg.of(" ", "agent-dim"),
                    Seg.of("−" + pd.minus(), "agent-err"), Seg.of(")", "agent-dim")));
            h.add(Seg.of(who, "agent-dim"));
            head(c, "agent-bullet-run", h);
            for (FileDiff f : pd.files()) {
                if (pd.files().size() > 1) row(c, TREE, "agent-tree", List.of(Seg.of(f.path()), Seg.of(" (", "agent-dim"), Seg.of("+" + f.plus(), "agent-ok"),
                        Seg.of(" ", "agent-dim"), Seg.of("−" + f.minus(), "agent-err"), Seg.of(")", "agent-dim")), TREE_REST, null);
                diffLines(c, f, "approval:" + r.id() + ":" + f.path());
            }
            for (String n : pd.notes()) row(c, INDENT, null, List.of(Seg.of(n, pd.empty() ? "agent-warn" : "agent-dim")), INDENT, null);
        } else {
            head(c, "agent-bullet-run", List.of(Seg.of(Msg.t("Waiting for your approval (panel below)"), "agent-warn"), Seg.of(who, "agent-dim")));
        }
        c.blockId = "approval";
        c.raw = r.title();
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private static void images(Chunk c, Ctx ctx, String owner) {
        List<GalleryImage> gs = ctx.images(owner);
        if (gs == null) return;
        for (GalleryImage g : gs) {
            c.add(TREE_REST);
            int a = c.pos();
            c.add("⧉ " + Msg.f("image %d · %s · %s", g.id(), g.mediaType(), size(g.bytes())), "link");
            c.act(a, c.pos(), ed -> ctx.openImage(ed, g));
            c.add("\n");
        }
    }

    static boolean looksLikeDiff(String s) {
        return s.startsWith("--- ") || s.startsWith("diff ") || s.startsWith("@@") || s.contains("\n@@ ") || s.contains("\n+++ ");
    }

    static List<String> lines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        String t = s.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
        for (String l : t.split("\n", -1)) out.add(l.replace("\t", "    "));
        return out;
    }

    static String secs(long ms) { return String.format(Locale.ROOT, "%.1fs", ms / 1000.0); }

    static String size(long bytes) {
        if (bytes >= 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        String one = s.replace('\n', ' ');
        return one.length() > n ? one.substring(0, n - 1) + "…" : one;
    }

    /** 按显示宽度把一段字折成几行（中文算两格；优先断在空白处）。 */
    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        width = Math.max(8, width);
        for (String line : lines(s)) {
            String rest = line;
            while (Width.stringWidth(rest) > width) {
                String head = Width.truncate(rest, width);
                int sp = head.lastIndexOf(' ');
                if (sp > width / 3) head = head.substring(0, sp);
                out.add(head.stripTrailing());
                rest = rest.substring(head.length()).stripLeading();
            }
            out.add(rest);
        }
        return out;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
