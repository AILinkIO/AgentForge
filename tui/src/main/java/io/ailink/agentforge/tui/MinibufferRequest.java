package io.ailink.agentforge.tui;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 开 minibuffer 的参数（Emacs 的 read-from-minibuffer / completing-read）。用法：
 * <pre>{@code
 * ed.readFromMinibuffer(new MinibufferRequest("模型: ")
 *     .candidates(List.of("opus", "sonnet"))       // 有候选 ⇒ helm 样式的候选列表（模糊过滤、前缀优先）
 *     .annotate((ed, item) -> "说明")              // 候选右边的注释
 *     .requireMatch(true).history("model")
 *     .onDone(s -> ...));
 * }</pre>
 * prompt 过 i18n（{@link I18n#format}）。
 */
public final class MinibufferRequest {
    String prompt;
    String initial = "";
    Function<String, List<String>> completer;
    BiFunction<Editor, String, String> annotate;
    Consumer<String> onDone = s -> {};
    Runnable onAbort;
    String history;
    boolean requireMatch;
    String source;

    public MinibufferRequest(String prompt, Object... promptArgs) { this.prompt = I18n.format(prompt, promptArgs); }

    public MinibufferRequest initial(String s) { initial = s == null ? "" : s; return this; }
    /** 固定一张候选表。 */
    public MinibufferRequest candidates(List<String> items) { completer = in -> items; return this; }
    /** 按输入动态给候选（之后照样过 {@link Matcher} 过滤排序）。 */
    public MinibufferRequest completer(Function<String, List<String>> f) { completer = f; return this; }
    public MinibufferRequest annotate(BiFunction<Editor, String, String> f) { annotate = f; return this; }
    public MinibufferRequest onDone(Consumer<String> f) { onDone = f; return this; }
    /** C-g 退出时调。 */
    public MinibufferRequest onAbort(Runnable r) { onAbort = r; return this; }
    /** 历史表的名字（M-p / M-n 翻）。 */
    public MinibufferRequest history(String h) { history = h; return this; }
    /** 必须选中一个候选才能提交。 */
    public MinibufferRequest requireMatch(boolean r) { requireMatch = r; return this; }
    /** helm 的来源标题（默认按提示猜）。 */
    public MinibufferRequest source(String s) { source = s; return this; }

    public String prompt() { return prompt; }
    public boolean isRequireMatch() { return requireMatch; }
}
