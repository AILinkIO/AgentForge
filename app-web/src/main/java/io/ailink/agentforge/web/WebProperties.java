package io.ailink.agentforge.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code agentforge.web.*}：与命令行 {@code agentforge agent} 的开关一一对应（没给的看 prefs）。
 *
 * @param root       可选：启动时把这个目录登记成一个项目（项目平时在网页上加；不给也能用）
 * @param model      启动时选的模型 ref（会记成默认）
 * @param agent      新会话用哪个 agent（缺省 main）
 * @param preset     审批 × 沙盒预设：read-only / auto / full-access
 * @param approval   审批策略：untrusted / on-failure / on-request / never / granular[:…]
 * @param sandbox    沙盒：read-only / workspace-write / danger-full-access
 * @param network    沙盒里能否出网
 * @param addDirs    workspace-write 下另加的可写目录
 * @param mcp        连不连 MCP 服务器（缺省连）
 * @param token      访问令牌；空 ⇒ 只听回环地址时不要令牌，听外网时随机生成
 */
@ConfigurationProperties("agentforge.web")
public record WebProperties(String root, String model, String agent, String preset, String approval, String sandbox,
                            Boolean network, List<String> addDirs, Boolean mcp, String token) {
    public boolean mcpEnabled() { return mcp == null || mcp; }
}
