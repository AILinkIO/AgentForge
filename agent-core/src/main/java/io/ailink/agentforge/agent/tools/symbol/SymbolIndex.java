package io.ailink.agentforge.agent.tools.symbol;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.List;

/**
 * find-symbol / symbol-refs / impact 背后的符号索引（coxswain 的 {@code codegraph-*-proc} 注入点）。
 *
 * <p>宿主有真索引（tree-sitter / LSP / codegraph）就 {@code Workspace.put(SymbolIndex.class, …)}；
 * 没有就用 {@link RegexSymbolIndex}（按语言的正则抽定义、词边界 grep 当引用）—— 工具照样能用，
 * 只是精度低一档。
 */
public interface SymbolIndex {

    /**
     * 一处定义。行列 0 起；{@code endRow} 未知为 -1（那时 source=true 只给位置）。
     *
     * @param kind function / method / class / type / variable / macro / module …
     */
    record Def(String file, int row, int col, String name, String kind, int endRow) { }

    /**
     * 一处引用（未解析：叫这个名字的引用点）。
     *
     * @param kind call / ref / import …（{@code import} 给 impact 用）
     * @param in   落在哪个定义体内；不知道为 null
     */
    record Ref(String file, int row, int col, String kind, String in) { }

    /** 引用边的状态：开着 / 关着 / 开了但没打开。 */
    enum RefsMode { ON, OFF, FAILED }

    /**
     * 给"查不到"时的解释用。各字段 null = 这个实现不报这一项。
     *
     * @param syntax    有没有语法支持（false = 抽不出任何符号）
     * @param opened    这个项目建过索引没有
     * @param indexing  正在建
     * @param readOnly  只读快照（可能不含最新改动）
     */
    record Status(Boolean enabled, Boolean syntax, Boolean opened, boolean indexing, int progressDone,
                  int progressTotal, List<String> languages, boolean readOnly, RefsMode refs) {
        public static Status simple(List<String> languages) {
            return new Status(true, true, true, false, 0, 0, languages, false, RefsMode.ON);
        }
    }

    /** false 时 find-symbol 不出现在工具表里。 */
    boolean enabled();

    Status status();

    /** 按名字（或一部分）查定义，最多 limit 条。 */
    List<Def> find(String name, int limit);

    /** 按**精确**名字查引用。 */
    List<Ref> refs(String name);

    /** 工作区上的那份（没装就用正则版）。 */
    static SymbolIndex of(Workspace ws) {
        return ws.component(SymbolIndex.class, () -> new RegexSymbolIndex(ws));
    }
}
