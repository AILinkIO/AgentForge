package io.ailink.agentforge.agent.mcp;

/**
 * 一台服务器的状态，只有这五种。
 *
 * <ul>
 *   <li>{@code DISABLED} —— 用户停用了（登记着，不起进程，工具不给模型）</li>
 *   <li>{@code CONNECTING} —— 正在起 / 正在握手</li>
 *   <li>{@code CONNECTED} —— 握手过了、目录拿到了</li>
 *   <li>{@code FAILED} —— 起不来 / 握手超时 / 进程死了（工具照给，调用回一句读得懂的失败）</li>
 *   <li>{@code NEEDS_AUTH} —— 对面回了 401/403。与 FAILED 分开：用户的下一步是"设 token"而不是"重连"</li>
 * </ul>
 */
public enum McpStatus { DISABLED, CONNECTING, CONNECTED, FAILED, NEEDS_AUTH }
