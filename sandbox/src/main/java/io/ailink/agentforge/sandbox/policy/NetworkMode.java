package io.ailink.agentforge.sandbox.policy;

/** 网络模式：断网 / 放行 / 只经本地代理（域名策略，见 {@link io.ailink.agentforge.sandbox.proxy}）。 */
public enum NetworkMode {
    RESTRICTED("restricted"), ENABLED("enabled"), PROXIED("proxied");

    private final String jsonName;

    NetworkMode(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
