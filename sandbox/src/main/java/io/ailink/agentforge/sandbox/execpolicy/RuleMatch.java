package io.ailink.agentforge.sandbox.execpolicy;

import java.util.List;

/** 一次命中：规则命中（带命中的前缀）或「没有规则，按启发式判」。 */
public sealed interface RuleMatch {
    Decision decision();

    /**
     * @param matchedPrefix   命中的前缀
     * @param resolvedProgram 绝对路径程序经 basename 回退命中时的原路径（否则 {@code null}）
     */
    record PrefixRuleMatch(List<String> matchedPrefix, Decision decision, String resolvedProgram, String justification) implements RuleMatch {
        public PrefixRuleMatch {
            matchedPrefix = List.copyOf(matchedPrefix);
        }
    }

    record HeuristicsRuleMatch(List<String> command, Decision decision) implements RuleMatch {
        public HeuristicsRuleMatch {
            command = List.copyOf(command);
        }
    }

    default boolean isPolicyMatch() {
        return this instanceof PrefixRuleMatch;
    }
}
