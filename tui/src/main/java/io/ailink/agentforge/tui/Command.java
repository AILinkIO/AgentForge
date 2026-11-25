package io.ailink.agentforge.tui;

/**
 * 命令：在 UI 线程上跑，直接改编辑器状态。抛 {@link UserError} 给用户看错误。
 * 注册：{@link Editor#defineCommand(String, String, Command)}；之后按名字绑键、在 M-x 里出现。
 * 命令里用 {@link Editor#prefixArg()} / {@link Editor#countArg()} 取前缀参数，{@link Editor#thisKey()} 取触发它的键。
 */
@FunctionalInterface
public interface Command {
    void run(Editor ed);
}
