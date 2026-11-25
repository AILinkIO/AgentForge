package io.ailink.agentforge.sandbox.execpolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 执行策略（移植自 Codex execpolicy/src/policy.rs，Apache-2.0）：按程序名分桶的前缀规则 + 网络规则 + host_executable 元数据。不可变；
 * 改动（{@link #withPrefixRule} 等）回新对象。
 * <p>匹配：先按命令首词精确找桶；没有命中且开了 {@code resolveHostExecutables} 时，绝对路径的程序回退到它的 basename 那一桶
 * （有 host_executable 声明时只认列出的路径）。多条命中取最严的判决。
 */
public final class Policy {
    private final Map<String, List<PrefixRule>> rulesByProgram;
    private final List<NetworkRule> networkRules;
    private final Map<String, List<String>> hostExecutables;

    private static final Policy EMPTY = new Policy(Map.of(), List.of(), Map.of());

    Policy(Map<String, List<PrefixRule>> rulesByProgram, List<NetworkRule> networkRules, Map<String, List<String>> hostExecutables) {
        Map<String, List<PrefixRule>> m = new LinkedHashMap<>();
        rulesByProgram.forEach((k, v) -> m.put(k, List.copyOf(v)));
        this.rulesByProgram = java.util.Collections.unmodifiableMap(m);
        this.networkRules = List.copyOf(networkRules);
        Map<String, List<String>> h = new LinkedHashMap<>();
        hostExecutables.forEach((k, v) -> h.put(k, List.copyOf(v)));
        this.hostExecutables = java.util.Collections.unmodifiableMap(h);
    }

    public static Policy empty() {
        return EMPTY;
    }

    public Map<String, List<PrefixRule>> rules() {
        return rulesByProgram;
    }

    public List<NetworkRule> networkRules() {
        return networkRules;
    }

    public Map<String, List<String>> hostExecutables() {
        return hostExecutables;
    }

    /** 全部 allow 前缀（渲染后排序去重；Codex get_allowed_prefixes）。 */
    public List<List<String>> allowedPrefixes() {
        List<List<String>> out = new ArrayList<>();
        for (List<PrefixRule> rs : rulesByProgram.values())
            for (PrefixRule r : rs) if (r.decision() == Decision.ALLOW && !out.contains(r.renderedPrefix())) out.add(r.renderedPrefix());
        out.sort((a, b) -> String.join("\u0000", a).compareTo(String.join("\u0000", b)));
        return out;
    }

    /** 加一条固定前缀规则（Codex add_prefix_rule）。 */
    public Policy withPrefixRule(List<String> prefix, Decision decision) {
        if (prefix == null || prefix.isEmpty()) throw new ExecPolicyException("prefix cannot be empty");
        Map<String, List<PrefixRule>> m = copyRules();
        m.computeIfAbsent(prefix.getFirst(), k -> new ArrayList<>())
                .add(new PrefixRule(prefix.getFirst(), prefix.subList(1, prefix.size()).stream().map(PatternToken::single).toList(), decision, null));
        return new Policy(m, networkRules, hostExecutables);
    }

    /** 加一条网络规则（主机先规范化；Codex add_network_rule）。 */
    public Policy withNetworkRule(String host, String protocol, Decision decision, String justification) {
        if (justification != null && justification.isBlank()) throw new ExecPolicyException("justification cannot be empty");
        List<NetworkRule> n = new ArrayList<>(networkRules);
        n.add(new NetworkRule(NetworkRule.normalizeHost(host), NetworkRule.normalizeProtocol(protocol), decision, justification));
        return new Policy(rulesByProgram, n, hostExecutables);
    }

    /** 叠一层（overlay 的规则追加在后；host_executable 同名覆盖；Codex merge_overlay）。 */
    public Policy mergeOverlay(Policy overlay) {
        Map<String, List<PrefixRule>> m = copyRules();
        overlay.rulesByProgram.forEach((k, v) -> m.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
        List<NetworkRule> n = new ArrayList<>(networkRules);
        n.addAll(overlay.networkRules);
        Map<String, List<String>> h = new LinkedHashMap<>(hostExecutables);
        h.putAll(overlay.hostExecutables);
        return new Policy(m, n, h);
    }

    /** 去掉所有放行（allow）的前缀规则与网络规则：只留让判决更严的那些（不受信的规则来源用）。 */
    public Policy withoutAllowRules() {
        Map<String, List<PrefixRule>> m = new LinkedHashMap<>();
        rulesByProgram.forEach((k, v) -> {
            List<PrefixRule> keep = v.stream().filter(r -> r.decision() != Decision.ALLOW).toList();
            if (!keep.isEmpty()) m.put(k, keep);
        });
        return new Policy(m, networkRules.stream().filter(r -> r.decision() != Decision.ALLOW).toList(), hostExecutables);
    }

    /** 放行规则的条数（前缀 + 网络）。 */
    public int allowRuleCount() {
        int n = 0;
        for (List<PrefixRule> v : rulesByProgram.values()) for (PrefixRule r : v) if (r.decision() == Decision.ALLOW) n++;
        for (NetworkRule r : networkRules) if (r.decision() == Decision.ALLOW) n++;
        return n;
    }

    private Map<String, List<PrefixRule>> copyRules() {
        Map<String, List<PrefixRule>> m = new LinkedHashMap<>();
        rulesByProgram.forEach((k, v) -> m.put(k, new ArrayList<>(v)));
        return m;
    }

    /** 网络规则编译成 (放行, 拒绝) 两张域名表：后写的盖前写的；prompt 不进表（Codex compiled_network_domains）。 */
    public List<List<String>> compiledNetworkDomains() {
        List<String> allowed = new ArrayList<>(), denied = new ArrayList<>();
        for (NetworkRule r : networkRules) {
            switch (r.decision()) {
                case ALLOW -> {
                    denied.remove(r.host());
                    allowed.remove(r.host());
                    allowed.add(r.host());
                }
                case FORBIDDEN -> {
                    allowed.remove(r.host());
                    denied.remove(r.host());
                    denied.add(r.host());
                }
                case PROMPT -> { }
            }
        }
        return List.of(allowed, denied);
    }

    // ── 求值 ───────────────────────────────────────────────────────────

    /** 单条命令求值；没有规则命中时用 {@code fallback} 判（Codex check）。 */
    public Evaluation check(List<String> cmd, Function<List<String>, Decision> fallback, boolean resolveHostExecutables) {
        return Evaluation.fromMatches(matchesForCommand(cmd, fallback, resolveHostExecutables));
    }

    /** 多条命令合并求值（Codex check_multiple）。 */
    public Evaluation checkMultiple(List<List<String>> commands, Function<List<String>, Decision> fallback, boolean resolveHostExecutables) {
        List<RuleMatch> all = new ArrayList<>();
        for (List<String> c : commands) all.addAll(matchesForCommand(c, fallback, resolveHostExecutables));
        return Evaluation.fromMatches(all);
    }

    /**
     * 命中的规则；一条都没有且给了 {@code fallback} ⇒ 回一条启发式命中（于是给了 fallback 时结果必非空）。
     */
    public List<RuleMatch> matchesForCommand(List<String> cmd, Function<List<String>, Decision> fallback, boolean resolveHostExecutables) {
        List<RuleMatch> m = matchExact(cmd);
        if (m.isEmpty() && resolveHostExecutables) m = matchHostExecutable(cmd);
        if (m.isEmpty() && fallback != null) return List.of(new RuleMatch.HeuristicsRuleMatch(cmd, fallback.apply(cmd)));
        return m;
    }

    private List<RuleMatch> matchExact(List<String> cmd) {
        if (cmd.isEmpty()) return List.of();
        List<RuleMatch> out = new ArrayList<>();
        for (PrefixRule r : rulesByProgram.getOrDefault(cmd.getFirst(), List.of())) {
            List<String> p = r.matchesPrefix(cmd);
            if (p != null) out.add(new RuleMatch.PrefixRuleMatch(p, r.decision(), null, r.justification()));
        }
        return out;
    }

    private List<RuleMatch> matchHostExecutable(List<String> cmd) {
        if (cmd.isEmpty()) return List.of();
        String program = cmd.getFirst();
        if (!program.startsWith("/")) return List.of();
        int slash = program.lastIndexOf('/');
        String base = program.substring(slash + 1);
        if (base.isEmpty()) return List.of();
        List<PrefixRule> rules = rulesByProgram.get(base);
        if (rules == null) return List.of();
        List<String> paths = hostExecutables.get(base);
        if (paths != null && !paths.contains(program)) return List.of();
        List<String> baseCmd = new ArrayList<>(cmd);
        baseCmd.set(0, base);
        List<RuleMatch> out = new ArrayList<>();
        for (PrefixRule r : rules) {
            List<String> p = r.matchesPrefix(baseCmd);
            if (p != null) out.add(new RuleMatch.PrefixRuleMatch(p, r.decision(), program, r.justification()));
        }
        return out;
    }

    @Override
    public String toString() {
        return "Policy" + rulesByProgram + networkRules;
    }
}
