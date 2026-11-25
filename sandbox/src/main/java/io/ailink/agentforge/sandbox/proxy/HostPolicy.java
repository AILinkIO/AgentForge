package io.ailink.agentforge.sandbox.proxy;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 域名策略（Codex network-proxy/src/domain_matcher.rs 的可移植子集）：精确名、{@code *.example.com}（不含 apex）、
 * {@code **.example.com}（含 apex）、{@code *}（只在 allow 里，放行全部）；deny 优先于 allow。主机先做大小写与尾点归一（字面 IP 规范成文本）。
 *
 * @param allowedHosts 放行的模式
 * @param deniedHosts  拒绝的模式（优先）
 * @param mode         {@link Mode#LIMITED} 只放 GET / HEAD / OPTIONS（且只有 HTTP 协议）
 * @param fallback     都不匹配时：拒 / 问审批者
 */
public record HostPolicy(List<String> allowedHosts, List<String> deniedHosts, Mode mode, Fallback fallback) {
    /** 方法范围。 */
    public enum Mode { FULL, LIMITED }

    /** 不匹配时的退路。 */
    public enum Fallback { DENY, ASK }

    /** 决策。 */
    public enum Decision { ALLOW, DENY, ASK }

    public static final HostPolicy DEFAULT = new HostPolicy(List.of(), List.of(), Mode.FULL, Fallback.DENY);

    public HostPolicy {
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        deniedHosts = deniedHosts == null ? List.of() : List.copyOf(deniedHosts);
        if (mode == null) mode = Mode.FULL;
        if (fallback == null) fallback = Fallback.DENY;
    }

    public static HostPolicy allow(String... hosts) {
        return new HostPolicy(List.of(hosts), List.of(), Mode.FULL, Fallback.DENY);
    }

    /** 去端口 / 方括号、字面 IP 规范成文本形式、去尾点、转小写；空 ⇒ {@code ""}。 */
    public static String normalizeHost(String h) {
        if (h == null || h.isBlank()) return "";
        String value = h.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']', 1);
            if (end <= 0) return "";
            value = value.substring(1, end);
        } else {
            // 只有一个冒号 ⇒ host:port；多个冒号 ⇒ 不带方括号的 IPv6 字面（照原版：取第一个冒号前）
            int colon = value.indexOf(':');
            if (colon >= 0) value = value.substring(0, colon);
        }
        InetAddress ip = HostLiterals.parseLiteral(value);
        if (ip != null) return HostLiterals.format(ip);
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') end--;
        return value.substring(0, end).toLowerCase(Locale.ROOT);
    }

    private static boolean matches(List<String> patterns, String h, boolean allowGlobal) {
        for (String raw : patterns) {
            String pattern = raw == null ? "" : raw.trim();
            if (pattern.isEmpty()) continue;
            if (pattern.equals("*")) {
                if (allowGlobal) return true;
                continue;
            }
            String pat;
            boolean apex;
            boolean sub;
            if (pattern.startsWith("**.")) {
                pat = pattern.substring(3);
                apex = true;
                sub = true;
            } else if (pattern.startsWith("*.")) {
                pat = pattern.substring(2);
                apex = false;
                sub = true;
            } else {
                pat = pattern;
                apex = true;
                sub = false;
            }
            pat = normalizeHost(pat);
            if (pat.isEmpty()) continue;
            if (h.equals(pat)) {
                if (apex) return true;
                continue;
            }
            if (sub && h.endsWith("." + pat)) return true;
        }
        return false;
    }

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** 主机（及方法）的决策；{@code method} 可空。 */
    public Decision decide(String host, String method) {
        String n = normalizeHost(host);
        if (n.isEmpty()) return Decision.DENY;
        if (mode == Mode.LIMITED && !READ_METHODS.contains(String.valueOf(method).toUpperCase(Locale.ROOT))) return Decision.DENY;
        if (matches(deniedHosts, n, false)) return Decision.DENY;
        if (matches(allowedHosts, n, true)) return Decision.ALLOW;
        return fallback == Fallback.ASK ? Decision.ASK : Decision.DENY;
    }

    public Decision decide(String host) {
        return decide(host, null);
    }
}
