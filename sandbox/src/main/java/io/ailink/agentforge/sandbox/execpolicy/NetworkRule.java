package io.ailink.agentforge.sandbox.execpolicy;

import java.util.Locale;

/**
 * 网络规则（Codex network_rule）：某主机在某协议上放行 / 问 / 拒。
 *
 * @param host          规范化的主机名或 IP 字面（小写、无端口、无尾点；不许通配）
 * @param protocol      {@code http / https / socks5_tcp / socks5_udp}
 * @param decision      判决（文件里写 {@code deny} 等于 {@code forbidden}）
 * @param justification 理由（可空）
 */
public record NetworkRule(String host, String protocol, Decision decision, String justification) {

    /** 协议名规范化（{@code https_connect / http-connect} 是 https 的别名）。 */
    public static String normalizeProtocol(String raw) {
        return switch (raw == null ? "" : raw) {
            case "http" -> "http";
            case "https", "https_connect", "http-connect" -> "https";
            case "socks5_tcp" -> "socks5_tcp";
            case "socks5_udp" -> "socks5_udp";
            default -> throw new ExecPolicyException("network_rule protocol must be one of http, https, socks5_tcp, socks5_udp (got " + raw + ")");
        };
    }

    /** 主机名规范化（Codex normalize_network_rule_host）：去端口 / 方括号 / 尾点，转小写；带协议 / 路径 / 通配 / 空白 ⇒ 抛。 */
    public static String normalizeHost(String raw) {
        String host = raw == null ? "" : raw.strip();
        if (host.isEmpty()) throw new ExecPolicyException("network_rule host cannot be empty");
        if (host.contains("://") || host.contains("/") || host.contains("?") || host.contains("#"))
            throw new ExecPolicyException("network_rule host must be a hostname or IP literal (without scheme or path)");
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) throw new ExecPolicyException("network_rule host has an invalid bracketed IPv6 literal");
            String rest = host.substring(close + 1);
            boolean portOk = rest.startsWith(":") && rest.length() > 1 && rest.substring(1).chars().allMatch(Character::isDigit);
            if (!rest.isEmpty() && !portOk) throw new ExecPolicyException("network_rule host contains an unsupported suffix: " + raw);
            host = host.substring(1, close);
        } else if (host.chars().filter(c -> c == ':').count() == 1) {
            int colon = host.lastIndexOf(':');
            String port = host.substring(colon + 1);
            if (colon > 0 && !port.isEmpty() && port.chars().allMatch(Character::isDigit)) host = host.substring(0, colon);
        }
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') end--;
        String n = host.substring(0, end).strip().toLowerCase(Locale.ROOT);
        if (n.isEmpty()) throw new ExecPolicyException("network_rule host cannot be empty");
        if (n.contains("*")) throw new ExecPolicyException("network_rule host must be a specific host; wildcards are not allowed");
        if (n.chars().anyMatch(Character::isWhitespace)) throw new ExecPolicyException("network_rule host cannot contain whitespace");
        return n;
    }
}
