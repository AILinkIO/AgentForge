package io.ailink.agentforge.agent.mcp;

import io.ailink.agentforge.agent.tool.AgentTool;

import java.time.Instant;
import java.util.List;

/**
 * 一台服务器此刻的快照（给界面：列表一行、详情报告）。
 *
 * @param error        出错那句话（给用户看的；FAILED / NEEDS_AUTH 时有）
 * @param tools        模型看到的那几件（本地名）
 * @param rejected     接不下的远端工具与理由
 * @param instructions initialize 应答里的 instructions（没有为 null）
 * @param since        最近一次连上的时刻
 * @param stderrTail   本地子进程 stderr 的尾巴（≤ 4 KB，排查用）
 * @param fromConfig   true = 配置里定义的（界面上删不掉，只能停用）；false = 界面上加的
 * @param hasToken     存着认证 token（只说有没有）
 */
public record McpServerInfo(String name, McpSpec spec, McpStatus status, String error, List<AgentTool> tools,
                            List<McpTools.Rejected> rejected, String instructions, Instant since,
                            String stderrTail, boolean fromConfig, boolean hasToken) {

    public int toolCount() { return tools.size(); }

    /** 状态点：用形状而不只用颜色（色盲用户分不出红绿）。 */
    public String dot() {
        return switch (status) {
            case CONNECTED -> "●";
            case CONNECTING -> "◐";
            case DISABLED -> "○";
            case NEEDS_AUTH -> "🔒";
            case FAILED -> "✗";
        };
    }

    public String statusWord() {
        return switch (status) {
            case CONNECTED -> "已连接";
            case CONNECTING -> "连接中…";
            case DISABLED -> "已停用";
            case NEEDS_AUTH -> "要认证";
            case FAILED -> "连不上";
        };
    }

    /** 列表一行：{@code ● playwright  已连接  21 件工具}。 */
    public String line() {
        return dot() + " " + name + "  " + statusWord() + (tools.isEmpty() ? "" : "  " + tools.size() + " 件工具");
    }
}
