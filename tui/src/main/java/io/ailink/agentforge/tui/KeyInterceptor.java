package io.ailink.agentforge.tui;

/**
 * 模态收键（isearch、补全浮框、y-or-n、菜单浮框 …）：开着时先于键表拿到每个键。
 * 返回 true = 吃掉了；false = 不要这个键 —— 拦截器应当已把自己撤掉（{@link Editor#popKeyInterceptor}），
 * 键照常进键表（照 coxswain：isearch 遇到别的键先退出再派发）。
 */
@FunctionalInterface
public interface KeyInterceptor {
    boolean handleKey(Editor ed, String key);
}
