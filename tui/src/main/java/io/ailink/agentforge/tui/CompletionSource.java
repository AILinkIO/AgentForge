package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 补全来源（corfu 的 completion-at-point-functions，coxswain {@code coxn.complete}）。登记：{@link Editor#addCompletionSource}。
 * 命令 completion-at-point（M-TAB / C-M-i）或自动弹出时：按 order 问每个 applies 的来源，第一个给出 start ≥ 0 的来源负责：
 * [start, point) 是正在补的那段（含触发字符，如 "/he"、"@src/Ma"），{@link #candidates} 给候选（可以异步 ——
 * 返回的 future 在别的线程完成也行，结果投回 UI 线程），框开着时打字只在本地重新过滤。
 */
public interface CompletionSource {
    /** 当前 buffer 用不用这个来源（比如只在 prompt 输入框里）。 */
    default boolean applies(Editor ed) { return true; }

    /** point 处要补的那段的起点；这个来源不管 ⇒ -1。 */
    int start(Editor ed);

    /** 候选（prefix = [start, point) 的文字）。之后会按 prefix 再过滤排序。 */
    CompletableFuture<List<CompletionItem>> candidates(Editor ed, String prefix);

    /** 接受：默认把 [start, point) 换成 insertText。 */
    default void accept(Editor ed, CompletionItem item, int start) {
        ed.replaceRegion(start, ed.point(), item.insertText());
    }

    /** 打了这些字就自动弹（如 "/" "@"）。 */
    default String triggerChars() { return ""; }

    /** 小的先问。 */
    default int order() { return 50; }

    /** 兜底来源（主来源没有和前缀对得上的候选才用）。 */
    default boolean fallback() { return false; }

    // ── 现成的来源 ─────────────────────────────────────────────────────

    /**
     * 以 trigger 字符开头的词（"/" 命令、"@" 文件提及）：point 前面那个不含空白的词以 trigger 开头才管；
     * atLineStart 为 true 时还要求这个词在行首（斜杠命令）。items 拿到去掉 trigger 的前缀，给出完整 label（含 trigger）。
     */
    static CompletionSource triggered(char trigger, boolean atLineStart, Predicate<Editor> applies,
                                      Function<String, List<CompletionItem>> items) {
        return new CompletionSource() {
            @Override public boolean applies(Editor ed) { return applies == null || applies.test(ed); }
            @Override public int start(Editor ed) {
                Buffer b = ed.currentBuffer();
                int p = ed.point(), s = p;
                while (s > 0 && !Character.isWhitespace(b.charAt(s - 1))) s--;
                if (s >= p || b.charAt(s) != trigger) return -1;
                if (atLineStart && s > 0 && b.charAt(s - 1) != '\n') return -1;
                return s;
            }
            @Override public CompletableFuture<List<CompletionItem>> candidates(Editor ed, String prefix) {
                return CompletableFuture.completedFuture(items.apply(prefix.substring(1)));
            }
            @Override public String triggerChars() { return String.valueOf(trigger); }
            @Override public int order() { return 10; }
        };
    }

    /** buffer 里的词（company-dabbrev 那样的兜底）：当前 buffer + 给定的其他 buffer。 */
    static CompletionSource words(Predicate<Editor> applies) {
        return new CompletionSource() {
            @Override public boolean applies(Editor ed) { return applies == null || applies.test(ed); }
            @Override public int start(Editor ed) {
                Buffer b = ed.currentBuffer();
                int p = ed.point(), s = p;
                while (s > 0 && isWord(b.charAt(s - 1))) s--;
                return s < p ? s : -1;
            }
            @Override public CompletableFuture<List<CompletionItem>> candidates(Editor ed, String prefix) {
                Set<String> words = new LinkedHashSet<>();
                for (Buffer b : ed.buffers()) {
                    String t = b.contents();
                    int i = 0;
                    while (i < t.length()) {
                        while (i < t.length() && !isWord(t.charAt(i))) i++;
                        int j = i;
                        while (j < t.length() && isWord(t.charAt(j))) j++;
                        if (j - i >= 3) words.add(t.substring(i, j));
                        i = j;
                    }
                }
                words.remove(prefix);
                List<CompletionItem> out = new ArrayList<>();
                for (String w : words) out.add(new CompletionItem(w, null, null, "w", null, null));
                return CompletableFuture.completedFuture(out);
            }
            @Override public int order() { return 100; }
            @Override public boolean fallback() { return true; }
        };
    }

    static boolean isWord(char c) { return Character.isLetterOrDigit(c) || c == '_' || c == '-'; }
}
