package io.ailink.agentforge.agent.clarify;

import io.ailink.agentforge.agent.clarify.ClarifyService.Option;
import io.ailink.agentforge.agent.clarify.ClarifyService.Question;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.ToolException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code clarify}：让模型把选择题摆到用户面前（coxswain {@code (coxn agent clarify)}，参照 opencode
 * {@code question} 与 Claude Code {@code AskUserQuestion}）。
 *
 * <p>省掉"正文里写 1. 2. 3. → 结束这一轮 → 等用户打字 → 下一轮"那条慢路：同一轮里拿到答案。
 *
 * <ul>
 *   <li>工具体不认识界面，只调 {@link ClarifyService}。**没有 service 就不造这件工具**
 *       （{@link #tool} 断言）：无界面会话的工具表里没有它，模型就不会调。</li>
 *   <li>子 agent 拿不到它（宿主不放进子 agent 的工具表）。</li>
 *   <li>1–4 题、每题 2–4 项，超了当场报错（静默截断模型学不会）；不收"其他"——面板自动带「自己填」。</li>
 *   <li>用户关掉 → 报错（模型按自己判断继续，别再问）；按停 → 报错，循环在下一个检查点收摊。</li>
 *   <li>read-only，不并发安全。</li>
 * </ul>
 */
public final class Clarify {
    private Clarify() { }

    public static final String NAME = "clarify";
    public static final int MAX_QUESTIONS = 4;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 4;
    /** 交回文本的第一行（记录据此认出答完的那条）。 */
    public static final String ANSWERED_HEAD = "用户回答了你的问题：";

    static final String DESCRIPTION = "需要**用户**做决定时用它：偏好、二选一的取舍、一句含糊指令的两种读法。"
            + "面板会把选项摆到用户面前，用户按一个键就答了，你在同一轮里拿到答案接着做。\n"
            + "什么时候**不**问：能自己查出来的（读文件、搜代码）不问；有惯例默认的不问"
            + "——直接按默认做并说一句。\n"
            + "怎么问：每题 2–4 个选项，label 1–5 个词，description 说清选它意味着什么；"
            + "推荐哪个就把它放第一项、label 末尾加「（推荐）」。**不要**加「其他 / 都不是」"
            + "这类兜底项——面板自动带「自己填」。一次最多 4 题；`multiple` 才能多选。\n"
            + "答案按题顺序回来，每题是选中的 label（自己填的就是那串字）。用户没答（关掉了）"
            + "这件工具会报错——那就按你自己的判断继续，别再问同一件事。";

    public static ObjectNode schema() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode s = f.objectNode().put("type", "object");
        ObjectNode qs = s.putObject("properties").putObject("questions");
        qs.put("type", "array").put("description", "要问的题（1–" + MAX_QUESTIONS + " 道）");
        ObjectNode item = qs.putObject("items").put("type", "object");
        ObjectNode props = item.putObject("properties");
        props.putObject("question").put("type", "string").put("description", "完整的问题，一句话");
        props.putObject("header").put("type", "string").put("description", "很短的标签（≤ 12 字），多题时当页签名");
        ObjectNode opts = props.putObject("options").put("type", "array")
                .put("description", "可选项（" + MIN_OPTIONS + "–" + MAX_OPTIONS + " 项）；不要加「其他」——面板自动带「自己填」");
        ObjectNode oi = opts.putObject("items").put("type", "object");
        ObjectNode op = oi.putObject("properties");
        op.putObject("label").put("type", "string").put("description", "1–5 个词");
        op.putObject("description").put("type", "string").put("description", "选它意味着什么");
        oi.putArray("required").add("label").add("description");
        props.putObject("multiple").put("type", "boolean").put("description", "允许多选（默认单选）");
        item.putArray("required").add("question").add("header").add("options");
        s.putArray("required").add("questions");
        return s;
    }

    /** 造这件工具；service 必需。 */
    public static AgentTool tool(ClarifyService service) {
        Objects.requireNonNull(service, "ClarifyService 是必需的：没有人能答的宿主不该造这件工具（工具表里没有它，模型就看不见）");
        return AgentTool.builder(NAME)
                .description(DESCRIPTION)
                .rawSchema(schema())
                .readOnly()
                .handler((env, a) -> {
                    List<Question> items = parseQuestions(a.raw());
                    ClarifyService.Answer ans = service.ask(new ClarifyService.Request(env.toolUseId(), items));
                    return switch (ans) {
                        case ClarifyService.Dismissed d -> throw new ToolException("用户没有回答这些问题（关掉了面板）。按你自己的判断继续，别再问同一件事。");
                        case ClarifyService.Stopped s -> throw new ToolException("用户停掉了这一轮。");
                        case ClarifyService.Answered x -> answersToText(items, x.answers());
                    };
                })
                .build();
    }

    // ── 参数 → 题表 ────────────────────────────────────────────────────

    public static List<Question> parseQuestions(JsonNode args) {
        JsonNode qs = args == null ? null : args.get("questions");
        if (qs == null || !qs.isArray()) throw bad("缺 questions（一个数组）");
        int n = qs.size();
        if (n < 1 || n > MAX_QUESTIONS) throw bad("一次问 " + n + " 题，上限 " + MAX_QUESTIONS + "——挑最要紧的几道");
        List<Question> out = new ArrayList<>();
        int i = 1;
        for (JsonNode q : qs) out.add(parseItem(q, i++));
        return out;
    }

    private static Question parseItem(JsonNode q, int i) {
        String text = str(q, "question");
        String header = str(q, "header");
        JsonNode opts = q == null ? null : q.get("options");
        boolean mult = false;
        JsonNode m = q == null ? null : q.get("multiple");
        if (m != null) mult = m.isBoolean() ? m.asBoolean() : m.isString() && (m.asString().equals("true") || m.asString().equals("True"));
        if (!nonBlank(text)) throw bad("第 " + i + " 题没有 question");
        if (opts == null || !opts.isArray()) throw bad("第 " + i + " 题的 options 不是数组");
        int n = opts.size();
        if (n < MIN_OPTIONS || n > MAX_OPTIONS) {
            throw bad("第 " + i + " 题有 " + n + " 个选项，要 " + MIN_OPTIONS + "–" + MAX_OPTIONS + " 个（不用加「其他」，面板自动带「自己填」）");
        }
        List<Option> os = new ArrayList<>();
        int j = 1;
        for (JsonNode o : opts) {
            String label = str(o, "label");
            String desc = str(o, "description");
            if (!nonBlank(label)) throw bad("第 " + i + " 题第 " + j + " 项没有 label");
            os.add(new Option(label, desc == null ? "" : desc));
            j++;
        }
        return new Question(text, nonBlank(header) ? header : shortOf(text), List.copyOf(os), mult);
    }

    /** header 没给时用问题的前 12 个字（页签要短）。 */
    static String shortOf(String s) { return s.length() > 12 ? s.substring(0, 12) + "…" : s; }

    private static String str(JsonNode o, String k) {
        if (o == null || !o.isObject()) return null;
        JsonNode v = o.get(k);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean nonBlank(String s) { return s != null && !s.isBlank(); }

    private static ToolException bad(String m) { return new ToolException(m); }

    // ── 答案 → 交回模型的那段话 ───────────────────────────────────────

    /** 一题一行 {@code 「问」→ 答}，多选用 {@code 、} 连；没答的题印「（没答）」。 */
    public static String answersToText(List<Question> items, List<List<String>> answers) {
        StringBuilder b = new StringBuilder(ANSWERED_HEAD).append('\n');
        for (int i = 0; i < items.size(); i++) {
            List<String> a = answers != null && i < answers.size() ? answers.get(i) : null;
            String shown = a == null || a.isEmpty() ? "（没答）" : String.join("、", a.stream().filter(Objects::nonNull).toList());
            if (i > 0) b.append('\n');
            b.append('「').append(items.get(i).question()).append("」→ ").append(shown);
        }
        return b.append("\n照着这些答案继续。").toString();
    }
}
