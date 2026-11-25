package io.ailink.agentforge.agent.tools.symbol;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.util.Fmt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * find-symbol / symbol-refs / impact（coxswain {@code tools/symbol.ss}）。索引从
 * {@link SymbolIndex#of(Workspace)} 取：宿主装了真索引就用它，否则正则版。
 *
 * <ul>
 *   <li>find-symbol 只回**定义**，带 kind 与行列；同名全部列出，让模型自己挑。</li>
 *   <li>symbol-refs 是**未解析**引用：叫这个名字的地方，不是指向某个定义的地方。</li>
 *   <li>impact 顺着 {@code import} 边往外走几跳（谁 import 了它的传递闭包），可只回测试文件。</li>
 * </ul>
 * 查不到时的话按索引状态解释（没开 / 没建 / 正在建 / 名字太短 / 这门语言不覆盖），并指 search-text 兜底。
 */
public final class SymbolTools {
    private SymbolTools() { }

    static final int SOURCE_MAX_LINES = 80;

    public static List<AgentTool> tools() { return List.of(findSymbol(), symbolRefs(), impact()); }

    static SymbolIndex index(Workspace ws) { return SymbolIndex.of(ws); }

    static boolean on(Workspace ws) {
        try { return index(ws).enabled(); } catch (RuntimeException e) { return false; }
    }

    static SymbolIndex.Status status(Workspace ws) {
        try { return index(ws).status(); } catch (RuntimeException e) { return null; }
    }

    static boolean refsOn(Workspace ws) {
        SymbolIndex.Status s = status(ws);
        return s != null && s.refs() != null && s.refs() != SymbolIndex.RefsMode.OFF;
    }

    static String staleNote(Workspace ws) {
        SymbolIndex.Status s = status(ws);
        return s != null && s.readOnly() ? "\n⚠️ 索引是只读快照（另一个进程正开着它），可能不含最新改动。" : "";
    }

    // ── find-symbol ────────────────────────────────────────────────────

    public static AgentTool findSymbol() {
        return AgentTool.builder("find-symbol")
                .description("按名字查**定义**（函数 / 类型 / 变量 / 宏）。与 search-text 不同：它只回"
                        + "定义处，不回引用与注释里的同名字符串，并且带 kind 与行列。"
                        + "同名的多个定义会**全部**列出来 —— 请自己按文件/语言判断要哪一个，不要"
                        + "默认第一条就是对的。"
                        + "⭐ 传 source=true 可以连**定义的源码**一起拿到，省掉紧跟一次 read-file；"
                        + "⚠️ 同名命中很多时别开（一次可能是上万 token）。函数与方法有源码，"
                        + "类型 / 变量 / 宏今天只有位置。"
                        + "索引只覆盖部分语言；查不到时用 search-text 兜底。")
                .required("name", "string", "符号名，或它的一部分")
                .optional("limit", "integer", "最多回多少条", 20L)
                .optional("source", "boolean", "连定义的源码一起回（省掉紧跟一次 read-file；同名很多时别开）", false)
                .readOnly()
                .enabled(SymbolTools::on)
                .handler(SymbolTools::runFind)
                .build();
    }

    static String runFind(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String name = a.requireStr("name");
        long limit = a.integer("limit", 20);
        boolean source = a.bool("source", false);
        int lim = limit > 0 ? (int) Math.min(limit, 200) : 20;
        List<SymbolIndex.Def> hits;
        try { hits = index(ws).find(name, lim); } catch (RuntimeException e) { hits = List.of(); }
        if (hits.isEmpty()) return emptyMessage(ws, name);
        StringBuilder b = new StringBuilder("找到 ").append(hits.size()).append(" 处定义\n");
        for (SymbolIndex.Def d : hits) {
            b.append(line(d)).append('\n');
            if (source) b.append(sourceOf(ws.root(), d));
        }
        return b.append(staleNote(ws)).toString();
    }

    static String line(SymbolIndex.Def d) {
        return d.file() + ":" + (d.row() + 1) + ":" + (d.col() + 1) + "  " + d.name() + "  [" + d.kind() + "]";
    }

    /** 定义的源码，带行号；最多 80 行，多了说一声。读不到 / 不知道结束行 → ""。 */
    static String sourceOf(Path root, SymbolIndex.Def d) {
        try {
            int row = d.row(), end = d.endRow();
            if (end < row) return "";
            Path full = Limit.resolveUnder(root, d.file());
            List<String> lines = Fmt.lines(Files.readString(full, StandardCharsets.UTF_8));
            int capped = Math.min(end, row + SOURCE_MAX_LINES - 1);
            StringBuilder b = new StringBuilder();
            for (int i = row; i <= capped && i < lines.size(); i++) b.append(i + 1).append("| ").append(lines.get(i)).append('\n');
            if (capped < end) b.append("…（共 ").append(end - row + 1).append(" 行，只印了前 ").append(SOURCE_MAX_LINES).append(" 行）\n");
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String emptyMessage(Workspace ws, String name) {
        SymbolIndex.Status s = status(ws);
        if (s != null) {
            if (Boolean.FALSE.equals(s.enabled())) return "符号索引没有开（可以用 search-text 代替）。";
            if (Boolean.FALSE.equals(s.syntax())) return "这个构建没有语法解析支持，索引抽不出任何符号。用 search-text。";
            if (Boolean.FALSE.equals(s.opened())) return "符号索引还没有为这个项目建索引（用 search-text 代替）。";
            if (s.indexing()) {
                return "索引还在建（" + s.progressDone() + "/" + s.progressTotal() + "），这个名字暂时查不到。稍后再试，或者先用 search-text。";
            }
        }
        if (name.length() < 2) {
            return Fmt.q(name) + " 太短：索引按 ngram 查（最少两个字符），单字符 / 半截子词召不回。给完整的子词，或者用 search-text。";
        }
        String langs = s == null || s.languages() == null ? "()" : "(" + String.join(" ", s.languages()) + ")";
        return "没有找到名为 " + Fmt.q(name) + " 的定义。注意索引只覆盖 " + langs + "；别的语言请用 search-text。"
                + (s != null && s.readOnly() ? " ⚠️ 索引现在是只读快照（另一个进程正开着它），可能不含最新改动。" : "");
    }

    // ── symbol-refs ────────────────────────────────────────────────────

    public static AgentTool symbolRefs() {
        return AgentTool.builder("symbol-refs")
                .description("查**谁提到了这个名字**（调用点、类型引用等），带文件、行列、以及它落在"
                        + "哪个定义体内。"
                        + "⚠️ 这是**未解析**的引用：回的是「叫这个名字的引用点」，**不是**「指向"
                        + "某一个特定定义的引用点」。同名的东西会一起回来 —— 要确认是不是你想的"
                        + "那一个，得自己看文件与上下文，或者配合 find-symbol 先看有几个同名定义。"
                        + "⚠️ 它不覆盖所有语言，查不到时用 search-text 兜底。")
                .required("name", "string", "符号名（**精确**名字，不是子串）")
                .optional("limit", "integer", "最多回多少条", 50L)
                .readOnly()
                .enabled(ws -> on(ws) && refsOn(ws))
                .handler(SymbolTools::runRefs)
                .build();
    }

    static String runRefs(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String name = a.requireStr("name");
        long limit = a.integer("limit", 50);
        int lim = limit > 0 ? (int) Math.min(limit, 500) : 50;
        List<SymbolIndex.Ref> all;
        try { all = index(ws).refs(name); } catch (RuntimeException e) { all = List.of(); }
        if (all.isEmpty()) return refsEmptyMessage(ws, name);
        int n = all.size();
        List<SymbolIndex.Ref> hits = n > lim ? all.subList(0, lim) : all;
        StringBuilder b = new StringBuilder(n > lim ? n + " 处引用（只列前 " + lim + " 条）\n" : n + " 处引用\n");
        for (SymbolIndex.Ref r : hits) {
            b.append(r.file()).append(':').append(r.row() + 1).append(':').append(r.col() + 1)
                    .append("  [").append(r.kind()).append(']').append(r.in() != null ? "  in " + r.in() : "").append('\n');
        }
        return b.append(staleNote(ws)).toString();
    }

    static String refsEmptyMessage(Workspace ws, String name) {
        SymbolIndex.Status s = status(ws);
        if (s != null) {
            if (s.refs() == SymbolIndex.RefsMode.OFF) return "引用边没有开。现在请用 search-text。";
            if (s.refs() == SymbolIndex.RefsMode.FAILED) return "引用边开着，但它那个库这次没打开（多半是另一个进程正开着这个项目）。现在请用 search-text。";
            if (Boolean.FALSE.equals(s.opened())) return "符号索引还没有为这个项目建索引（用 search-text 代替）。";
            if (s.indexing()) {
                return "索引还在建（" + s.progressDone() + "/" + s.progressTotal() + "），这个名字的引用暂时查不全。稍后再试，或者先用 search-text。";
            }
        }
        return "没有查到 " + Fmt.q(name) + " 的引用。可能是这个名字确实没人引用，也可能是这门语言还没有引用 pattern —— 拿不准就用 search-text 兜一下。";
    }

    // ── impact ─────────────────────────────────────────────────────────

    public static AgentTool impact() {
        return AgentTool.builder("impact")
                .description("改这个模块会影响谁 —— 顺着 **import 依赖边**一跳一跳往外走。"
                        + "收一个**模块名**（Scheme 库名，形如 `(coxn codegraph store)`）或者一个"
                        + "**文件路径**（会按约定推出它的模块名，推不出来就直说）。"
                        + "⚠️ 回的是「谁 import 了它」的传递闭包，**不是**「谁调用了它的某个函数」"
                        + "—— 后者用 symbol-refs。"
                        + "⚠️ 它只覆盖有 import pattern 的语言（Scheme 与 C/C++ 的本地 #include）。"
                        + "⭐ 传 tests-only=true 只回受影响的**测试文件**（改完该跑哪些）。")
                .required("name", "string", "模块名（如 `(coxn codegraph store)`）或文件路径")
                .optional("depth", "integer", "往外走几跳", 2L)
                .optional("tests-only", "boolean", "只回受影响的测试文件", false)
                .readOnly()
                .enabled(ws -> on(ws) && refsOn(ws))
                .handler(SymbolTools::runImpact)
                .build();
    }

    static String runImpact(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String name = a.requireStr("name");
        long depth = a.integer("depth", 2);
        boolean testsOnly = a.bool("tests-only", false);
        int d = depth > 0 ? (int) Math.min(depth, 8) : 2;
        SymbolIndex idx = index(ws);
        boolean looksModule = !name.isEmpty() && name.charAt(0) == '(';
        List<String> firstHop = looksModule ? importersOf(idx, name) : importersOfFile(idx, name);
        String mod = looksModule ? name : moduleOfFile(idx, name);
        if (mod == null && firstHop.isEmpty()) {
            return "从路径 " + Fmt.q(name) + " 推不出模块名（推的是 `" + name.replace('/', ' ') + "`，而索引里没有这个模块定义）。\n"
                    + "要么直接传模块名（形如 `(coxn codegraph store)`），"
                    + "要么这个文件不是一个库 —— 那时它没有 import 边意义上的下游。";
        }
        List<List<String>> levels = levels(idx, firstHop, d);
        List<String> all = new ArrayList<>();
        for (List<String> lv : levels) all.addAll(lv);
        List<String> shown = testsOnly ? all.stream().filter(SymbolTools::testFile).toList() : all;
        String who = mod != null ? mod : name;
        if (shown.isEmpty()) {
            if (testsOnly) return who + "：" + all.size() + " 个文件受影响，其中**没有**测试文件。" + staleNote(ws);
            return "没有别的模块 import " + who + "。" + staleNote(ws) + "\n⚠️ 也可能是引用边刚建、还没跑完。";
        }
        StringBuilder b = new StringBuilder();
        if (testsOnly) {
            b.append(shown.size()).append(" 个受影响的测试文件（共 ").append(all.size()).append(" 个文件受影响）\n");
            for (String f : shown) b.append(f).append('\n');
        } else {
            b.append(all.size()).append(" 个文件受影响（").append(d).append(" 跳以内）\n");
            for (int i = 0; i < levels.size(); i++) {
                List<String> lv = levels.get(i);
                b.append("── 第 ").append(i + 1).append(" 跳（").append(lv.size()).append(" 个）\n");
                for (String f : lv) b.append("  ").append(f).append('\n');
            }
        }
        return b.append(staleNote(ws)).toString();
    }

    /** 引用里 kind=import 的那些文件（去重、保序）。 */
    static List<String> importersOf(SymbolIndex idx, String name) {
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (SymbolIndex.Ref r : idx.refs(name)) if ("import".equals(r.kind())) seen.add(r.file());
        } catch (RuntimeException ignored) { }
        return new ArrayList<>(seen);
    }

    /** 路径 → 按约定推出的模块名（{@code a/b/c.ss} → {@code (a b c)}），索引里确有这个模块定义才算。 */
    static String moduleOfFile(SymbolIndex idx, String rel) {
        try {
            int slash = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\'));
            int dot = rel.lastIndexOf('.');
            String stem = dot > slash ? rel.substring(0, dot) : rel;
            String name = "(" + stem.replace('/', ' ').replace('\\', ' ') + ")";
            for (SymbolIndex.Def d : idx.find(name, 20)) {
                if (d.name().equals(name) && "module".equals(d.kind())) return name;
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** 一个文件被谁 import：模块名 + {@code "基名"} + {@code "相对路径"}（C 的本地 #include）。 */
    static List<String> importersOfFile(SymbolIndex idx, String rel) {
        List<String> keys = new ArrayList<>();
        String m = moduleOfFile(idx, rel);
        if (m != null) keys.add(m);
        int slash = Math.max(rel.lastIndexOf('/'), rel.lastIndexOf('\\'));
        String base = rel.substring(slash + 1);
        keys.add("\"" + base + "\"");
        if (!base.equals(rel)) keys.add("\"" + rel + "\"");
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            for (String f : importersOf(idx, k)) if (!f.equals(rel)) out.add(f);
        }
        return new ArrayList<>(out);
    }

    static List<List<String>> levels(SymbolIndex idx, List<String> firstHop, int depth) {
        List<List<String>> acc = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(firstHop);
        List<String> front = firstHop;
        for (int d = 1; d <= depth && !front.isEmpty(); d++) {
            acc.add(front);
            if (d >= depth) break;
            Set<String> next = new LinkedHashSet<>();
            for (String f : front) for (String imp : importersOfFile(idx, f)) if (!seen.contains(imp)) next.add(imp);
            seen.addAll(next);
            front = new ArrayList<>(next);
        }
        return acc;
    }

    /** 测试文件的约定：tests/ 底下、-test.ss，以及几门常见语言的命名。 */
    static boolean testFile(String rel) {
        String base = rel.substring(rel.lastIndexOf('/') + 1);
        return rel.startsWith("tests/") || rel.startsWith("test/") || rel.contains("/src/test/") || rel.startsWith("src/test/")
                || rel.endsWith("-test.ss") || base.endsWith("Test.java") || base.endsWith("Test.kt")
                || base.endsWith("_test.go") || base.endsWith("_test.py") || base.startsWith("test_")
                || base.matches(".*\\.(test|spec)\\.[jt]sx?$") || base.endsWith("_test.clj") || base.endsWith("_test.rs");
    }
}
