package io.ailink.agentforge.cli.agent;

import io.ailink.agentforge.agent.runtime.AgentRuntime;
import io.ailink.agentforge.tui.agent.AgentWorkbench;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * {@code agentforge}（不带子命令）/ {@code agentforge tui}：全屏 agent 工作台（tui-agent 模块）。
 * 开关与 {@code agent} 行模式完全相同（沙盒、审批、预设、模型、agent、MCP …），只是界面换成全屏的。
 *
 * <p>⚠️ 与 {@code agent} 一样不碰 Spring：入口在 {@code AgentForgeApplication.main} 里先认出来直接跑这里。
 */
@Command(name = "tui", mixinStandardHelpOptions = true, description = "全屏 agent 工作台（不带子命令时的默认入口；开关同 agent）")
public class TuiCommand extends AgentCommand {

    @Override
    int launch(AgentRuntime rt, List<String> notes) throws Exception {
        if (System.console() == null && System.getenv("TERM") == null) {
            System.err.println("全屏工作台要一个真终端；管道 / 脚本里请用 agentforge agent（行模式）");
            return 2;
        }
        AgentWorkbench.run(rt, new AgentWorkbench.Options().agent(agent).notes(notes));
        return 0;
    }
}
