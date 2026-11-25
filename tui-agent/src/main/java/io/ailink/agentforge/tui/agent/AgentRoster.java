package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.approval.ApprovalRequest;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.session.SessionStatus;
import io.ailink.agentforge.agent.transcript.Entry;
import io.ailink.agentforge.agent.transcript.SubRun;
import io.ailink.agentforge.agent.transcript.SubStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 这段对话里的全部 agent（主会话 + 每一趟子 agent，含后台的、子 agent 再派的）：一份给总览、状态区、记录区共用的读数。
 * 照 Codex 的多 agent 界面：每一趟有个稳定的称呼（{@code junior #1}）和一种稳定的颜色（{@code agent-thread-N}），
 * 记录区、状态行、总览、审批面板里用的都是同一套。
 *
 * <p>称呼的序号按第一次看到的顺序发（只增不改），颜色按序号轮换 —— 相邻两趟颜色一定不同（Codex 按线程 id 取哈希，允许撞色）。
 * 只在 UI 线程上用。
 */
final class AgentRoster {
    /** 颜色有几种（{@code agent-thread-0} … {@code agent-thread-5}，主题里推出来的）。 */
    static final int COLORS = 6;

    /** 一趟的状态（总览的筛选按它分）。 */
    enum State {
        NEEDS_YOU("Needs you"), WORKING("Working"), READY("Idle"), DONE("Done"), FAILED("Failed");

        final String word;

        State(String word) { this.word = word; }

        String label() { return Msg.t(word); }
    }

    /**
     * 一行读数。id = 那趟的 tool-use-id（主会话是 null）；depth = 第几层（主会话 0、它派的 1 …）；session = 活着的会话
     * （旧对话里的那趟没有 ⇒ null）；run = sub-run（主会话 ⇒ null）。
     */
    record Row(String id, int depth, String label, String face, State state, String type, String model, String desc,
               long elapsedMs, int steps, boolean background, String activity, String attention, String lastMessage,
               String prompt, List<SubStep> recent, AgentSession session, SubRun run) {
        boolean main() { return id == null; }

        String key() { return id == null ? "main" : id; }
    }

    private final AgentWorkbench wb;
    private final Map<String, Integer> ordinals = new LinkedHashMap<>();

    AgentRoster(AgentWorkbench wb) { this.wb = wb; }

    /** 换会话：称呼重新从 1 发。 */
    void reset() { ordinals.clear(); }

    /** 先占一个序号（task 调用刚出现、子 agent 还没登记时）：称呼的序号跟记录区里调用的先后一致。 */
    void reserve(String id) { ordinal(id); }

    private int ordinal(String id) { return ordinals.computeIfAbsent(id, k -> ordinals.size() + 1); }

    /** 这一趟的称呼：{@code junior #1}（没写派给谁 ⇒ {@code agent #1}）。 */
    String label(String runId) {
        if (runId == null) return Msg.t("main");
        SubRun r = find(runId);
        return label(runId, r == null ? null : r.who());
    }

    private String label(String runId, String who) {
        return (who == null || who.isBlank() ? "agent" : who) + " #" + ordinal(runId);
    }

    /** 这一趟的颜色 face（主会话 ⇒ 正文色）。 */
    String face(String runId) {
        if (runId == null) return "agent-title";
        return "agent-thread-" + (ordinal(runId) - 1) % COLORS;
    }

    /** 按 id 找一趟（主会话的、子 agent 派的都找）。 */
    SubRun find(String id) {
        if (id == null) return null;
        for (Row r : walk(false)) if (id.equals(r.id())) return r.run();
        return null;
    }

    /** 这一趟的活会话（没有 ⇒ null）。 */
    AgentSession session(String id) {
        if (id == null) return wb.root;
        for (Row r : walk(false)) if (id.equals(r.id())) return r.session();
        return null;
    }

    /** 主会话 + 全部子 agent（深度优先，按派出的顺序）。 */
    List<Row> rows() { return walk(true); }

    /** 还在跑的子 agent（不含主会话）。 */
    List<Row> running() {
        List<Row> out = new ArrayList<>();
        for (Row r : walk(true)) if (!r.main() && (r.state() == State.WORKING || r.state() == State.NEEDS_YOU)) out.add(r);
        return out;
    }

    boolean anyRunning() {
        for (Row r : walk(false)) if (r.run() != null && r.run().running()) return true;
        return false;
    }

    private List<Row> walk(boolean full) {
        List<Row> out = new ArrayList<>();
        AgentSession root = wb.root;
        if (root == null) return out;
        out.add(full ? mainRow(root) : new Row(null, 0, null, null, null, null, null, null, 0, 0, false, null, null, null, null, List.of(), root, null));
        collect(root, 1, out, full, 0);
        return out;
    }

    private void collect(AgentSession parent, int depth, List<Row> out, boolean full, int guard) {
        if (guard > 8) return;
        for (SubRun r : parent.subLog()) {
            if (r.id() == null) continue;
            AgentSession child = parent.child(r.id());
            out.add(full ? runRow(r, child, depth) : new Row(r.id(), depth, null, null, null, null, null, null, 0, 0, false, null, null, null, null, List.of(), child, r));
            if (child != null) collect(child, depth + 1, out, full, guard + 1);
        }
    }

    private Row mainRow(AgentSession root) {
        ApprovalRequest pa = root.pendingApproval();
        boolean mine = pa != null && (pa.session() == null || pa.session().equals(root.id()));
        State st = mine || root.status() == SessionStatus.AWAITING_QUESTION ? State.NEEDS_YOU : root.busy() ? State.WORKING : State.READY;
        long el = wb.turnStartedAt > 0 ? Math.max(0, System.currentTimeMillis() - wb.turnStartedAt) : 0;
        String attention = mine ? approvalLine(pa) : root.status() == SessionStatus.AWAITING_QUESTION ? Msg.t("Waiting for your answer") : null;
        String act = root.busy() ? wb.activity(root) : null;
        String prompt = null;
        for (Entry e : root.transcript()) if (e.role() == Entry.Role.USER) prompt = e.text();
        String model = root.model() == null ? null : AgentWorkbench.shortModel(root.model());
        String type = root.agent() == null ? "main" : root.agent();
        String title = root.title() != null && !root.title().isBlank() ? root.title() : prompt != null ? prompt.lines().findFirst().orElse("") : "";
        return new Row(null, 0, Msg.t("main"), "agent-title", st, type, model, title.isBlank() ? Msg.t("Main conversation") : title, el, 0, false, act, attention,
                lastMessage(root), prompt, List.of(), root, null);
    }

    private Row runRow(SubRun r, AgentSession child, int depth) {
        AgentSession root = wb.root;
        ApprovalRequest pa = root.pendingApproval();
        boolean asks = pa != null && pa.session() != null && pa.session().equals(r.id());
        boolean question = child != null && child.status() == SessionStatus.AWAITING_QUESTION;
        State st = asks || question ? State.NEEDS_YOU : r.running() ? State.WORKING : r.ok() ? State.DONE : State.FAILED;
        long el = r.ms() != null ? r.ms() : Math.max(0, System.currentTimeMillis() - r.startedAt());
        List<SubStep> steps = r.allSteps();
        String act = null;
        if (r.running()) {
            if (child != null && child.busy()) act = runningActivity(child);
            else if (!steps.isEmpty() && steps.getLast().summary() == null) act = Msg.f("Running %s", stepLine(steps.getLast()));
            else act = Msg.t("Thinking");
        }
        String attention = asks ? approvalLine(pa) : question ? Msg.t("Waiting for your answer") : null;
        String model = child != null && child.model() != null ? child.model() : r.model() != null ? r.model() : root.model();
        String prompt = null;
        if (child != null) {
            for (Entry e : child.transcript()) if (e.role() == Entry.Role.USER) { prompt = e.text(); break; }
        }
        List<SubStep> recent = steps.size() <= 4 ? steps : steps.subList(steps.size() - 4, steps.size());
        String last = child != null ? lastMessage(child) : null;
        if (last == null && !r.running() && r.why() != null) last = r.why();
        return new Row(r.id(), depth, label(r.id(), r.who()), face(r.id()), st, r.who(), model == null ? null : AgentWorkbench.shortModel(model),
                r.desc(), el, steps.size(), r.background(), act, attention, last, prompt, List.copyOf(recent), child, r);
    }

    /** 一个会话这会儿在干什么（跑哪件工具 + 参数 / 写 / 想）：比主状态行多带参数，子 agent 的状态行要看得出「在查什么」。 */
    String runningActivity(AgentSession s) {
        if (s.status() == SessionStatus.AWAITING_QUESTION) return Msg.t("Waiting for your answer");
        if (s.status() == SessionStatus.COMPACTING) return Msg.t("Compacting the context");
        List<Entry> running = new ArrayList<>();
        for (Entry e : s.turnLog()) if (e.isCall() && e.meta("summary") == null) running.add(e);
        if (running.size() == 1) {
            Entry e = running.getFirst();
            String args = e.meta("args-preview") instanceof String a ? a : "";
            return Msg.f("Running %s", (e.meta("tool-name") + " " + args).strip());
        }
        if (running.size() > 1) return Msg.f("Waiting for %d tools", running.size());
        if (s.status() == SessionStatus.STREAMING && "text".equals(s.streamingKind())) return Msg.t("Writing");
        return Msg.t("Thinking");
    }

    static String stepLine(SubStep s) {
        return (s.name() + (s.args() == null || s.args().isEmpty() ? "" : " " + s.args())).strip();
    }

    private static String approvalLine(ApprovalRequest r) {
        String what = r.title() != null ? r.title() : r.toolName() != null ? r.toolName() : "";
        return what.isEmpty() ? Msg.t("Waiting for your approval") : Msg.f("Waiting for your approval: %s", Blocks.clip(what.replace('\n', ' '), 80));
    }

    /** 最后一句话：正在写的那段，否则这一轮 / 历史里最后一段回答。 */
    static String lastMessage(AgentSession s) {
        if (s.busy() && "text".equals(s.streamingKind())) {
            String t = s.streamingText();
            if (!t.isBlank()) return t;
        }
        if (s.busy()) {
            List<Entry> tl = s.turnLog();
            for (int i = tl.size() - 1; i >= 0; i--) {
                Entry e = tl.get(i);
                if (e.role() == Entry.Role.ASSISTANT && !e.flag("thinking") && !e.text().isBlank()) return e.text();
            }
        }
        return s.lastAssistantText();
    }
}
