package io.ailink.agentforge.agent.models;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考档位：**厂商自己那几个名字，原样**（coxswain M89）。
 *
 * <p>我们没有档位，厂商才有。曾经"挤进我们四档（low/medium/high）"的映射在真实数据上是在说假话：
 * {@code none low medium high} 那批里"low"发出去的是 {@code none}（等于关掉推理）。所以：
 *
 * <ul>
 *   <li>不会推理 → **空表**（UI 整条命令都不出现）；</li>
 *   <li>有 effort → 它给的那几个名字原样；标了 toggle 时前面加 {@code off}；</li>
 *   <li>只吃 token 预算（且这一家会发 {@code budget_tokens}，即 Anthropic 形）→ 档位名**就是那个数**
 *       （{@code "16384"}），屏幕上写的与线上发的是同一个值；</li>
 *   <li>只有 toggle → {@code off / on}；</li>
 *   <li>说会推理、没说怎么控制 → 空表。</li>
 * </ul>
 *
 * <p>⚠️ {@code off} 只在厂商标了 toggle 时才有：adaptive-only 的模型关不掉，列一个 off 又是一句假话。
 * "什么都不发、按服务端默认"是选择器里的**默认**那一项（thinking = null），不占档位表。
 * ⚠️ 档位名不过 i18n：它是要原样发到线上的值。
 */
public final class ThinkingLevels {

    /** Anthropic 的下限，比它小的预算发过去是 400。 */
    public static final long BUDGET_FLOOR = 1024;
    /** ×4：×2 会给出六七档，一屏全是数字。 */
    public static final long BUDGET_STEP = 4;
    /** 给正文留的那一截：预算是从 max_tokens 里切出来的，max_tokens 又不能超过输出上限。 */
    public static final long ANSWER_HEADROOM = 4096;

    private ThinkingLevels() { }

    /**
     * @param budgetShaped 这一家的格式会不会发 {@code budget_tokens}（Anthropic 形才会；别家 OpenAI 兼容
     *                     端点各有写法，我们一个都没验过 —— 列出来就是"屏幕上说了、线上没发生"）
     */
    public static List<String> levels(ModelFacts f, boolean budgetShaped) {
        if (f == null || !f.reasons()) return List.of();
        List<String> effort = f.reasoningEffort();
        if (effort != null && !effort.isEmpty()) {
            if (!f.reasoningToggle()) return effort;
            List<String> out = new ArrayList<>();
            out.add("off");
            out.addAll(effort);
            return List.copyOf(out);
        }
        // ⚠️ 预算排在 toggle 前面：两个都有时一个数比 "on" 更有信息量；off 照给（toggle 就是"可以关"）
        if (budgetShaped && f.reasoningBudget()) {
            List<String> ladder = budgetLadder(f);
            if (ladder.isEmpty()) return f.reasoningToggle() ? List.of("off", "on") : List.of();
            if (!f.reasoningToggle()) return ladder;
            List<String> out = new ArrayList<>();
            out.add("off");
            out.addAll(ladder);
            return List.copyOf(out);
        }
        return f.reasoningToggle() ? List.of("off", "on") : List.of();
    }

    /**
     * 台阶：起点 = max(目录 min, 1024)；终点 = min(目录 max, 输出上限 − 正文余量)；每级 ×4；终点不在台阶上
     * 就补成一档（"能给多少给多少"只有这一档说得出来）。上限还不到起点 → 空表（装不下一次像样的推理）。
     */
    static List<String> budgetLadder(ModelFacts f) {
        long min = f.reasoningBudgetMin() != null && f.reasoningBudgetMin() > BUDGET_FLOOR ? f.reasoningBudgetMin() : BUDGET_FLOOR;
        Long capOut = f.output() != null ? f.output() - ANSWER_HEADROOM : null;
        Long capCat = f.reasoningBudgetMax() != null && f.reasoningBudgetMax() > 0 ? f.reasoningBudgetMax() : null;
        Long cap = capOut != null && capCat != null ? Long.valueOf(Math.min(capOut, capCat)) : (capOut != null ? capOut : capCat);
        if (cap == null || cap < min) return List.of();
        List<String> out = new ArrayList<>();
        long v = min;
        while (true) {
            out.add(Long.toString(v));
            if (v * BUDGET_STEP <= cap) v *= BUDGET_STEP;
            else break;
        }
        if (v != cap) out.add(Long.toString(cap));
        return List.copyOf(out);
    }

    /** 档位名是不是一个 token 预算（纯数字）。 */
    public static boolean isBudget(String level) {
        if (level == null || level.isEmpty()) return false;
        for (char c : level.toCharArray()) if (c < '0' || c > '9') return false;
        return true;
    }
}
