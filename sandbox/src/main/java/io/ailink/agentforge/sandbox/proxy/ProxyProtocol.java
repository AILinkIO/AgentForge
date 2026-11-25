package io.ailink.agentforge.sandbox.proxy;

/** 代理协议（策略名 {@code http} / {@code https_connect} / {@code socks5_tcp}）。 */
public enum ProxyProtocol {
    HTTP("http"), HTTPS_CONNECT("https_connect"), SOCKS5_TCP("socks5_tcp");

    private final String policyName;

    ProxyProtocol(String policyName) {
        this.policyName = policyName;
    }

    public String policyName() {
        return policyName;
    }
}
