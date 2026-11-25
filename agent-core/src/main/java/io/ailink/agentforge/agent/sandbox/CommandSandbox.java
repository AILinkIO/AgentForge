package io.ailink.agentforge.agent.sandbox;

import io.ailink.agentforge.agent.config.AgentPaths;
import io.ailink.agentforge.agent.session.AgentSession;
import io.ailink.agentforge.agent.tools.shell.CommandRunner;
import io.ailink.agentforge.sandbox.Platform;
import io.ailink.agentforge.sandbox.Sandbox;
import io.ailink.agentforge.sandbox.SandboxCapabilities;
import io.ailink.agentforge.sandbox.SandboxPlatform;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时这一级的平台沙盒：本机探测出来的后端（探一次，各会话共用）+ 一句给人看的状态。策略（三档、网络、可写根）不在这里——
 * 那是每段会话的 {@link io.ailink.agentforge.agent.approval.PermissionSettings}，随时可改。
 * 没有可用后端 ⇒ 受限策略下未知命令逐条审批后裸跑，{@link #status()} 说清原因 —— 宁可明说"没关进去"，也不能让人以为命令在沙盒里。
 */
public final class CommandSandbox {
    private final Sandbox sandbox;
    private final String status;

    private CommandSandbox(Sandbox sandbox, String status) {
        this.sandbox = sandbox;
        this.status = status;
    }

    /** 探测本机。{@code options} 可空（Windows 上缺省把账本放到 {@code state/sandbox}）。 */
    public static CommandSandbox select(SandboxPlatform.Options options) {
        SandboxPlatform.Options o = options;
        if (o == null && Platform.isWindows()) {
            o = new SandboxPlatform.Options(null, null, AgentPaths.stateDir().resolve("sandbox").toString(), List.of(), false);
        }
        Sandbox s;
        List<String> why = new ArrayList<>();
        try {
            s = SandboxPlatform.select(o);
            if (s == null) {
                for (SandboxCapabilities c : SandboxPlatform.diagnose(o)) {
                    why.add(c.name() + "：" + (c.unavailableReason() == null ? "不可用" : c.unavailableReason().text()));
                }
            }
        } catch (RuntimeException e) {
            s = null;
            why.add(e.getMessage() == null ? e.toString() : e.getMessage());
        }
        if (s == null) {
            String reason = why.isEmpty() ? "本平台没有沙盒后端" : String.join("；", why);
            return new CommandSandbox(null, "没有可用的沙盒后端：受限策略下未知命令要逐条审批，批准后直接在本机执行（" + reason + "）");
        }
        return new CommandSandbox(s, "沙盒后端：" + s.probe().name());
    }

    /** 不探测、明确不用平台沙盒（测试 / 宿主自己管隔离时）。 */
    public static CommandSandbox none(String why) {
        return new CommandSandbox(null, why == null ? "没有沙盒后端" : why);
    }

    /** 本机有可用的平台沙盒。 */
    public boolean active() { return sandbox != null; }

    /** 平台沙盒；没有 ⇒ null。 */
    public Sandbox sandbox() { return sandbox; }

    /** 给人看的一句话（启动时打出来）。 */
    public String status() { return status; }

    /** 这段会话的执行器：审批摆到这段会话上（子 agent 共用父会话的）。 */
    public CommandRunner runnerFor(AgentSession s) {
        return new SandboxedCommandRunner(sandbox, () -> s.workspace().root(), s.permissions(), s.approvalHost());
    }
}
