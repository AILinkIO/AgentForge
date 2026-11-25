/**
 * AgentForge 沙盒：keelson clj-sandbox（其上溯 C# Keelson.Sandbox / Codex CLI 的沙盒）的纯 Java 重写。
 * <ul>
 * <li>{@code policy}：条目式策略（最具体条目定级别、受保护名只读、deny glob）、策略 JSON、路径守卫；</li>
 * <li>{@code exec}：执行后端缝（本机后端、改写式包装、带时限的跑完即收）；</li>
 * <li>{@code linux}：bubblewrap 后端（主力）与 Landlock + seccomp 后端（要宿主提供原生件）；</li>
 * <li>{@code windows}：AppContainer / 受限令牌后端（⚠ 只在 Linux 上编译过、跑过平台无关部分）；</li>
 * <li>{@code proxy}：只听回环的 HTTP / SOCKS5 代理（域名策略）与 netns 代理桥；</li>
 * <li>{@code approval} / {@code orchestrator}：审批要求、拒判、「沙盒拒了 ⇒ 审批 ⇒ 不带沙盒重跑」的编排；</li>
 * <li>{@code agent}：给智能体工具用的门面。</li>
 * </ul>
 * 入口：{@link io.ailink.agentforge.sandbox.SandboxPlatform#select()} 选后端，{@link io.ailink.agentforge.sandbox.orchestrator.SandboxOrchestrator} 编排。
 */
package io.ailink.agentforge.sandbox;
