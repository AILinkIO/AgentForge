package io.ailink.agentforge.sandbox.execpolicy;

import java.util.List;

/** 模式里的一个位置：一个固定词，或几个备选词之一。 */
public record PatternToken(List<String> alternatives) {
    public PatternToken {
        alternatives = List.copyOf(alternatives);
        if (alternatives.isEmpty()) throw new ExecPolicyException("pattern alternatives cannot be empty");
    }

    public static PatternToken single(String s) {
        return new PatternToken(List.of(s));
    }

    public boolean matches(String token) {
        return alternatives.contains(token);
    }

    /** 渲染：单个原样，备选 {@code [a|b]}。 */
    public String render() {
        return alternatives.size() == 1 ? alternatives.getFirst() : "[" + String.join("|", alternatives) + "]";
    }
}
