/**
 * 智能体集成的框架无关部分（≙ keelson clj-sandbox-agent）：
 * <ul>
 * <li>{@link io.ailink.agentforge.sandbox.agent.SandboxCommandRunner}：「执行命令」工具的门面 —— 每次调用给 argv，回 {@link io.ailink.agentforge.sandbox.agent.CommandResult}；</li>
 * <li>{@link io.ailink.agentforge.sandbox.agent.AgentApprover}：把宿主的审批 UI 接成编排器的审批者；</li>
 * <li>{@link io.ailink.agentforge.sandbox.agent.PathGuard}：进程内文件工具的路径守卫（与子进程同一份策略）。</li>
 * </ul>
 * <h2>一个 agent「run-command」工具要做的事</h2>
 * <ol>
 * <li>会话开始时：定策略（如 {@code SandboxPolicy.workspaceWrite(...)}，或宿主配置里的 JSON 经 {@code PolicyJson.read}）与
 *     {@code PathContext.forCwd(workspaceRoot)}；造 {@code OrchestrationRequest}（审批策略、缺省时限）；
 *     {@code SandboxCommandRunner.create(request, approver, platformOptions, OrchestratorOptions.DEFAULT.withApprovalCache(ApprovalCache.inMemory()))}。
 *     宿主启动时可调一次 {@code ProcessHardening.hardenCurrentProcess()}；Windows 上先 {@code RestrictedTokenSandbox.recoverLedger(stateDir)}。</li>
 * <li>工具 schema：{@code command}（argv 数组；要 shell 语义就让模型给 {@code ["bash","-lc",script]}）、{@code workdir}、{@code timeout_ms}、
 *     可选 {@code sandbox_permissions}（{@code require_escalated} ⇒ {@code CommandRequest.escalated}，仍走审批）、{@code justification}、{@code prefix_rule}；审批要求先经 {@code execpolicy.ExecApprovals} 求值再交给编排器。</li>
 * <li>审批者：把 {@code ApprovalRequest}（{@code reason} / {@code retryReason} / {@code denial} 的输出）展示给用户，回
 *     APPROVE / APPROVE_FOR_SESSION / DENY / ABORT；阻塞调用，可能不在 UI 线程上。</li>
 * <li>结果回模型：{@code exitCode}、{@code outputText()}（已按上限截断）、{@code timedOut}；{@code rejected} 或 {@code status == DENIED} 时附上
 *     {@code reason().text()}，让模型知道是被沙盒 / 审批挡了而不是命令本身失败。</li>
 * <li>取消 = 中断执行 {@code run} 的线程（进程树随之被杀）。</li>
 * <li>文件类工具（读 / 写 / 打补丁）碰路径前问 {@link io.ailink.agentforge.sandbox.agent.PathGuard}，理由原样回模型。</li>
 * </ol>
 * 不做：伪终端 / 交互式会话、流式输出回调（需要的话直接用 {@code ExecBackend.start} + {@code ExecProcess.nextOutput}）。
 */
package io.ailink.agentforge.sandbox.agent;
