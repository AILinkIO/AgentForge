package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.loop.ReactLoop;
import io.ailink.agentforge.agent.loop.Step;
import io.ailink.agentforge.agent.prompt.Prompts;

import java.util.List;

/**
 * 轮中提醒：同一件工具**连着**业务失败 N 次 → 在这一轮的工具结果后面并一句"别再原样重试"（coxswain {@code nudge.ss}）。
 *
 * <p>⚠️ 只数**业务失败**（工具自己报错，kind {@code error}）：审批拒绝、参数不合法、未知工具、按停都不算 ——
 * 那些有各自的那句话，再叠一句只会让模型困惑。中间夹一次成功或换了工具就重新数。
 */
public final class Nudge {
    private Nudge() { }

    public static final int REPEAT_THRESHOLD = 3;

    /** 末尾那一串连续失败：(工具名, 次数)，没有 → null。 */
    public record Run(String name, int count) { }

    public static Run consecutiveFailures(List<Step> steps) {
        String name = null;
        int n = 0;
        for (Step s : steps) {
            if (!businessFailure(s)) { name = null; n = 0; }
            else if (name != null && name.equals(s.name())) n++;
            else { name = s.name(); n = 1; }
        }
        return name == null ? null : new Run(name, n);
    }

    static boolean businessFailure(Step s) { return s.error() && "error".equals(s.kind()); }

    public static String roundNudge(List<Step> steps) {
        Run r = consecutiveFailures(steps);
        return r != null && r.count() >= REPEAT_THRESHOLD ? Prompts.repeatNudgePrompt(r.name(), r.count()) : null;
    }

    /** 给 {@link ReactLoop} 的那个回调。 */
    public static final ReactLoop.RoundNudge ROUND_NUDGE = (round, max, history, steps) -> roundNudge(steps);
}
