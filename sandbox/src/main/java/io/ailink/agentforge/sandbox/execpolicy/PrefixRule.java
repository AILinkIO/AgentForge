package io.ailink.agentforge.sandbox.execpolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 前缀规则：命令的前若干个词依次匹配 {@code first} + {@code rest}（Codex PrefixRule / PrefixPattern）。
 *
 * @param first         程序名（规则按它分桶；模式首位的备选会展开成多条规则）
 * @param rest          其余位置
 * @param decision      判决
 * @param justification 理由（可空；审批提示 / 拒绝消息里给人看）
 */
public record PrefixRule(String first, List<PatternToken> rest, Decision decision, String justification) {
    public PrefixRule {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(decision, "decision");
        rest = List.copyOf(rest);
    }

    /** 匹配上 ⇒ 命中的那段前缀；否则 {@code null}。 */
    public List<String> matchesPrefix(List<String> cmd) {
        int len = rest.size() + 1;
        if (cmd.size() < len || !cmd.getFirst().equals(first)) return null;
        for (int i = 0; i < rest.size(); i++) if (!rest.get(i).matches(cmd.get(i + 1))) return null;
        return List.copyOf(cmd.subList(0, len));
    }

    /** {@code [first, rest…]}（备选渲染成 {@code [a|b]}）。 */
    public List<String> renderedPrefix() {
        List<String> out = new ArrayList<>();
        out.add(first);
        for (PatternToken t : rest) out.add(t.render());
        return out;
    }
}
