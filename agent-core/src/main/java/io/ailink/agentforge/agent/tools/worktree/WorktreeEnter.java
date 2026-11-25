package io.ailink.agentforge.agent.tools.worktree;

import java.nio.file.Path;

/**
 * worktree-enter 的宿主回调（coxswain {@code worktree-enter-proc}）：切项目是宿主的事。
 *
 * <p>语义是**排队**：工具调用时 agent 一定是忙的，宿主应把切换排到这一轮跑完之后
 * （通常：换 {@code Workspace.setRoot}、重建会话侧状态）。
 *
 * <p>接法：{@code ToolEnv.withService(WorktreeEnter.class, …)}，或 {@code Workspace.put(WorktreeEnter.class, …)}。
 * 都没接 → 工具报"进不了"，不假装切了。
 */
@FunctionalInterface
public interface WorktreeEnter {
    void requestEnter(Path worktreeDir);
}
