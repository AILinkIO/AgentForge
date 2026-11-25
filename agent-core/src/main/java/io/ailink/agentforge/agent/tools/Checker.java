package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.List;

/**
 * 语法检查器端口（对应 coxswain {@code agent-tools-checker}；原版插 tree-sitter）。
 *
 * <p>返回 {@code List.of()} 与返回 {@code null} 是两件事：前者"看过了，没问题"，后者"看不了"（不认识这种文件）。
 * 预览那句"✓ 改完之后语法没问题"只对前者说。
 *
 * <p>宿主 {@code ws.put(Checker.class, …)} 换实现；没装时用 {@link BalanceChecker}。{@link #NONE} 表示不检查。
 */
@FunctionalInterface
public interface Checker {

    /** 问题列表（每条一句带位置的话），或 null = 看不了。 */
    List<String> problems(String path, String text);

    Checker NONE = (path, text) -> null;

    static Checker of(Workspace ws) { return ws.component(Checker.class, BalanceChecker::new); }
}
