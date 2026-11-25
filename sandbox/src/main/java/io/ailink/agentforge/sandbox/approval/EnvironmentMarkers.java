package io.ailink.agentforge.sandbox.approval;

import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.exec.ExecRequest;
import io.ailink.agentforge.sandbox.policy.NetworkMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给沙盒里的子进程加的环境标记（参照 Codex spawn.rs 的 CODEX_SANDBOX / CODEX_SANDBOX_NETWORK_DISABLED）；变量名由宿主给，库不写死。
 *
 * @param sandboxVariable         值 = 后端名；{@code null} / 空 ⇒ 不加
 * @param networkDisabledVariable 网络不是 ENABLED 时值为 {@code "1"}；{@code null} / 空 ⇒ 不加
 */
public record EnvironmentMarkers(String sandboxVariable, String networkDisabledVariable) {

    public ExecRequest apply(ExecRequest request, SandboxCapabilities sandbox, NetworkMode network) {
        Map<String, String> env = new LinkedHashMap<>();
        if (sandboxVariable != null && !sandboxVariable.isEmpty()) env.put(sandboxVariable, sandbox.name());
        if (networkDisabledVariable != null && !networkDisabledVariable.isEmpty() && network != NetworkMode.ENABLED)
            env.put(networkDisabledVariable, "1");
        return env.isEmpty() ? request : request.mergeEnvironment(env);
    }
}
