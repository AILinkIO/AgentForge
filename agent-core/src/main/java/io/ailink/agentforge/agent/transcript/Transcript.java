package io.ailink.agentforge.agent.transcript;

import io.ailink.agentforge.agent.context.ContextMath;
import io.ailink.agentforge.agent.context.Reminder;
import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从模型历史投影出记录区（coxswain {@code transcript.ss}）：每次工具调用一条、每个结果一条、人话一条、
 * assistant 正文一条；再把这一轮的思考段、子 agent 那几趟、出错 / 按停封存的那几条按位置拼回去。
 *
 * <p>⚠️ 纯函数：UI（TUI / Compose / Web）拿同一套投影各自画。
 */
public final class Transcript {
    private Transcript() { }

    public static final int SUMMARY_LIMIT = 60;
    public static final int RESULT_SNIP_LIMIT = 200;
    static final List<String> LEAD_KEYS = List.of("path", "command", "cmd", "name");

    // ── 参数单行 ─────────────────────────────────────────────────────────

    /**
     * {@code path=a.txt limit=20}：path / command / cmd / name 排前面（一眼要看的是"对哪个文件、跑什么"），
     * 换行画成 ⏎，超长截断加 {@code ...}。⚠️ 审批规则匹配的就是这一串，改格式等于改规则语义。
     */
    public static String argsOneLine(ObjectNode input, int limit) {
        String s = argsRaw(input).replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎');
        return s.length() > limit ? s.substring(0, Math.max(0, limit - 3)) + "..." : s;
    }

    static String argsRaw(ObjectNode input) {
        if (input == null) return "";
        List<String> parts = new ArrayList<>();
        for (String k : LEAD_KEYS) if (input.has(k)) parts.add(k + "=" + plain(input.get(k)));
        for (var e : input.properties()) {
            if (!LEAD_KEYS.contains(e.getKey())) parts.add(e.getKey() + "=" + plain(e.getValue()));
        }
        return String.join(" ", parts);
    }

    private static String plain(JsonNode n) { return n == null ? "" : n.isString() ? n.asString() : n.toString(); }

    /** 结果第一行，超过 60 字截断。 */
    public static String resultSummary(List<Block> content) {
        String c = ContextMath.toolResultText(content);
        int eol = c.indexOf('\n');
        String head = eol < 0 ? c : c.substring(0, eol);
        return head.length() > SUMMARY_LIMIT ? head.substring(0, SUMMARY_LIMIT - 1) + "…" : head;
    }

    // ── 条目 ─────────────────────────────────────────────────────────────

    /** 一次工具调用；{@code result} 非 null 时带上结果状态与摘要（已经有结果的那种）。 */
    public static Entry toolCall(String name, String id, ObjectNode input, Block.ToolResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool-name", name);
        m.put("kind", "call");
        m.put("tool-use-id", id);
        m.put("args-preview", argsOneLine(input, SUMMARY_LIMIT));
        if (input != null) m.put("args", input);
        if (result != null) {
            m.put("tool-error", result.error());
            m.put("summary", resultSummary(result.content()));
        }
        return new Entry(Entry.Role.TOOL, "[" + name + "] 调用", m);
    }

    public static Entry toolResult(List<Block> content, boolean error, String id) {
        String c = ContextMath.toolResultText(content);
        String snip = c.length() > RESULT_SNIP_LIMIT ? c.substring(0, RESULT_SNIP_LIMIT - 3) + "..." : c;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "result");
        m.put("tool-error", error);
        m.put("tool-use-id", id);
        m.put("summary", resultSummary(content));
        return new Entry(Entry.Role.TOOL, snip, m);
    }

    // ── 投影 ─────────────────────────────────────────────────────────────

    public static List<Entry> project(List<Message> history) {
        Map<String, Block.ToolResult> results = new HashMap<>();
        for (Message m : history) if (m.isUser()) for (Block.ToolResult r : m.toolResults()) results.put(r.toolUseId(), r);
        List<Entry> out = new ArrayList<>();
        for (Message m : history) {
            if (m.isUser() && !ContextMath.human(m)) {
                for (Block.ToolResult r : m.toolResults()) out.add(toolResult(r.content(), r.error(), r.toolUseId()));
            } else if (ContextMath.human(m)) {
                // 压缩留下的那两条带上 compaction 标记（notice / summary），界面画成分隔线与"前情提要"
                out.add(m.meta("compaction") instanceof String c
                        ? new Entry(Entry.Role.USER, Reminder.shownText(m), Map.of("compaction", c))
                        : Entry.user(Reminder.shownText(m)));
            } else if (m.isAssistant()) {
                if (!m.text().isEmpty()) out.add(m.meta("compaction") instanceof String c
                        ? new Entry(Entry.Role.ASSISTANT, m.text(), Map.of("compaction", c))
                        : Entry.assistant(m.text()));
                for (Block.ToolUse u : m.toolUses()) out.add(toolCall(u.name(), u.id(), u.input(), results.get(u.id())));
            }
        }
        return out;
    }

    /**
     * 新投影里与旧列表**相等**的条目沿用旧对象（UI 用对象身份做排版缓存：每轮重投影一次而整屏重排，长对话会卡）。
     */
    public static List<Entry> graft(List<Entry> old, List<Entry> fresh) {
        if (old.isEmpty()) return fresh;
        Map<Entry, ArrayDeque<Entry>> t = new HashMap<>();
        for (Entry e : old) t.computeIfAbsent(e, k -> new ArrayDeque<>()).add(e);
        List<Entry> out = new ArrayList<>();
        for (Entry e : fresh) {
            ArrayDeque<Entry> q = t.get(e);
            out.add(q == null || q.isEmpty() ? e : q.poll());
        }
        return out;
    }

    /** 这一轮想完的那几段插到最后一句人话后面（想完就消失的话用户连回头看一眼的机会都没有）。 */
    public static List<Entry> spliceThinking(List<Entry> entries, List<Entry> thinking) {
        if (thinking.isEmpty()) return entries;
        int k = -1;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).role() == Entry.Role.USER) k = i;
        List<Entry> out = new ArrayList<>(entries.subList(0, k + 1));
        out.addAll(thinking);
        out.addAll(entries.subList(k + 1, entries.size()));
        return out;
    }

    /** task 那条调用换成带子 agent 读数的组头（步数、耗时、派给谁、跑在哪条模型上、状态）。 */
    public static List<Entry> spliceSubagents(List<Entry> entries, List<SubRun> runs) {
        if (runs.isEmpty()) return entries;
        Map<String, SubRun> t = new HashMap<>();
        for (SubRun r : runs) if (r.id() != null) t.put(r.id(), r);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            SubRun r = e.isCall() ? t.get(e.toolUseId()) : null;
            out.add(r == null ? e : subHead(e, r));
        }
        return out;
    }

    public static Entry subHead(Entry e, SubRun r) {
        Map<String, Object> m = new LinkedHashMap<>(e.meta());
        if (!r.desc().isEmpty()) m.put("args-preview", r.desc());
        m.put("group", r.id());
        m.put("sub-steps", r.allSteps().size());
        if (r.ms() != null) m.put("sub-ms", r.ms());
        if (r.background()) m.put("sub-bg", true);
        if (r.who() != null) m.put("sub-who", r.who());
        if (r.model() != null) m.put("sub-model", r.model());
        if (r.status() != null) m.put("sub-status", r.status());
        if (r.status() != null && r.why() != null && !r.why().isEmpty()) m.put("sub-why", r.why());
        return new Entry(e.role(), e.text(), m);
    }

    /** 子 agent 的每一步展开成一条（走进组里看时用）。 */
    public static List<Entry> subSteps(SubRun r) {
        List<SubStep> ss = r.allSteps();
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < ss.size(); i++) {
            SubStep s = ss.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sub-step", true);
            m.put("group", r.id());
            m.put("tool-name", s.name());
            m.put("args-preview", s.args());
            m.put("sub-last", i == ss.size() - 1);
            if (s.summary() != null) { m.put("tool-error", s.error()); m.put("summary", s.summary()); }
            out.add(new Entry(Entry.Role.TOOL, s.name(), m));
        }
        return out;
    }

    public static SubStep subStep(Step st) {
        return new SubStep(st.name(), argsOneLine(st.input(), SUMMARY_LIMIT), st.error(), resultSummary(st.content()));
    }

    /** 出错 / 按停封存的那几块：{@code (锚, 条目)} —— 锚是"第几句人话之后"，按它插回原处（再聊多少轮都不漂）。 */
    public record Sealed(int anchor, List<Entry> entries) { }

    public static List<Entry> spliceFailures(List<Entry> entries, List<Sealed> fails) {
        if (fails.isEmpty()) return entries;
        List<Sealed> pending = new ArrayList<>(fails);
        List<Entry> out = new ArrayList<>();
        int i = 0;
        for (Entry e : entries) {
            if (e.role() == Entry.Role.USER) {
                final int at = i;
                for (var it = pending.iterator(); it.hasNext(); ) {
                    Sealed s = it.next();
                    if (s.anchor() == at) { out.addAll(s.entries()); it.remove(); }
                }
                i++;
            }
            out.add(e);
        }
        for (Sealed s : pending) out.addAll(s.entries());
        return out;
    }
}
