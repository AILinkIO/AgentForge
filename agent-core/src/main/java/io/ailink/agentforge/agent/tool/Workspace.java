package io.ailink.agentforge.agent.tool;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 工具共享的一份"工作区"状态：工作根 + 一组按类型索引的组件。
 *
 * <p>对应 coxswain 那组 thread parameter（{@code agent-tools-root} / {@code agent-tools-vfs} /
 * {@code agent-tools-seen} / {@code agent-tools-checker} …）。各子系统用
 * {@link #component(Class, Supplier)} 取自己的那件（第一次取时建），宿主可以用
 * {@link #put(Class, Object)} 预先换掉实现 —— 与"宿主 parameterize 进来"同一个用法。
 */
public final class Workspace {
    private volatile Path root;
    private final ConcurrentHashMap<Class<?>, Object> components = new ConcurrentHashMap<>();

    public Workspace(Path root) { this.root = root.toAbsolutePath().normalize(); }

    public Path root() { return root; }

    /** 换项目（worktree-enter）。各组件自己决定要不要跟着重置（例如 seen 表）。 */
    public void setRoot(Path p) { this.root = p.toAbsolutePath().normalize(); }

    public <T> void put(Class<T> type, T value) { components.put(type, value); }

    @SuppressWarnings("unchecked")
    public <T> T component(Class<T> type, Supplier<? extends T> init) {
        return (T) components.computeIfAbsent(type, k -> init.get());
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) { return (T) components.get(type); }

    /**
     * 派生一份视图：同一个根、同一批组件（vfs / staging / 宿主装的服务照旧共享），只有 {@code fresh} 列出的那几类
     * 不带过去（下次取时各自重建）。
     *
     * <p>为什么要有它：会话与子 agent 共用一个 Workspace 时，seen 表、删除确认槽这类"按一段对话算"的状态会互相串 ——
     * 子 agent 读过的文件让主 agent 不读就能改，那正是 seen 校验要拦的。之后在派生视图上 {@link #setRoot} 不影响原件。
     */
    public Workspace fork(Class<?>... fresh) {
        Workspace w = new Workspace(root);
        w.components.putAll(components);
        for (Class<?> c : fresh) w.components.remove(c);
        return w;
    }
}
