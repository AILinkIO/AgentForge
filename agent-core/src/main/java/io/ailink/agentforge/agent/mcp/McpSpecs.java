package io.ailink.agentforge.agent.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户在配置里登记的那几台（coxswain 里 {@code init.ss} 的 {@code mcp-server-define!} 那张表）。
 *
 * <p>只答"配置里写了什么"：界面上加出来的那几台在 {@link McpState} 里，不进这张表；合并规则在
 * {@link McpManager#syncSpecs()}。同名后来居上，<b>位置保住</b>。
 */
public final class McpSpecs {
    private final List<McpSpec> specs = new ArrayList<>();

    /** 登记一台（校验失败抛 {@link IllegalArgumentException}）。 */
    public synchronized String define(String name, Map<String, ?> opts) {
        return define(McpSpec.of(name, opts));
    }

    public synchronized String define(McpSpec spec) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).name().equals(spec.name())) {
                specs.set(i, spec);
                return spec.name();
            }
        }
        specs.add(spec);
        return spec.name();
    }

    public synchronized void undefine(String name) { specs.removeIf(s -> s.name().equals(name)); }

    public synchronized void clear() { specs.clear(); }

    public synchronized List<McpSpec> all() { return List.copyOf(specs); }

    public synchronized Optional<McpSpec> ref(String name) {
        return specs.stream().filter(s -> s.name().equals(name)).findFirst();
    }
}
