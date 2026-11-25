package io.ailink.agentforge.sandbox.execpolicy;

import java.util.List;

/** 求值结果：最严的判决 + 全部命中。 */
public record Evaluation(Decision decision, List<RuleMatch> matchedRules) {
    public Evaluation {
        matchedRules = List.copyOf(matchedRules);
    }

    /** 有真正的规则命中（不只是启发式）。 */
    public boolean isMatch() {
        return matchedRules.stream().anyMatch(RuleMatch::isPolicyMatch);
    }

    /** {@code matchedRules} 非空由调用方保证。 */
    static Evaluation fromMatches(List<RuleMatch> matches) {
        Decision d = null;
        for (RuleMatch m : matches) if (d == null || m.decision().compareTo(d) > 0) d = m.decision();
        if (d == null) throw new IllegalStateException("matched_rules must be non-empty");
        return new Evaluation(d, matches);
    }
}
