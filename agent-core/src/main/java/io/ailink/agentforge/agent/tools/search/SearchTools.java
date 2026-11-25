package io.ailink.agentforge.agent.tools.search;

import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.util.Fmt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * search-text / find-files：进程内搜内容、找文件（coxswain {@code tools/search.ss}）。
 *
 * <p>为什么不让模型 {@code run-command grep}：Windows 上没有 grep；搜索只读、不该每次等审批；
 * 而且进程内才能**到量就停** —— 一个 {@code pattern="."} 在大仓上能产出 GB 级输出，"跑完再截"
 * 99.999% 的功夫是白花的。
 *
 * <ul>
 *   <li>默认只搜 git 会看的文件（{@link FileWalker#candidates}）；{@code all=true} 连忽略的一起。</li>
 *   <li>按**条数**截（head-limit/offset），不按字符劈半行；字符闸 {@link Limit#OUTPUT_LIMIT} 仍兜底。</li>
 *   <li>零命中不是错误 —— 判成错误的话模型会去修搜索命令，而不是接受"不存在"。</li>
 *   <li>返回值第一行是摘要（UI 的工具行显示首行）。</li>
 * </ul>
 */
public final class SearchTools {
    private SearchTools() { }

    /** 收集到这么多字符就中止（远大于 OUTPUT_LIMIT，留出截断余量）。 */
    static final int CMD_OUTPUT_CAP = 200_000;
    static final int DEFAULT_HEAD_LIMIT = 250;
    /** 单行上正则的 charAt 预算 —— Java 正则没有步数上限，病态回溯靠它报错而不是卡死。 */
    static final long STEP_BUDGET_PER_LINE = 20_000_000L;
    /** 比这大的文件不搜（多半是数据 / 产物）。 */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    public static List<AgentTool> tools() { return List.of(searchText(), findFiles()); }

    // ── search-text ────────────────────────────────────────────────────

    public static AgentTool searchText() {
        return AgentTool.builder("search-text")
                .description("在文件内容里搜索（自动递归子目录），返回 文件:行号:该行内容。"
                        + "pattern 默认按**正则**解释 —— 要搜的是普通文本（含括号、点号、"
                        + "星号等）时请把 literal 设为 true，否则会报语法错。"
                        + "正则是 PCRE2 兼容的（与 grep/Perl 一致）：\\b 词边界、\\d\\w\\s、"
                        + "字符类、惰性量词（*?）、捕获组、(?i) 内联标志、lookaround "
                        + "（(?=…)(?<=…) 等）、反向引用（\\1）都支持。极少数极端复杂的模式"
                        + "可能因步数上限被拒（报错，不会静默给错结果）。"
                        + "要一次搜几个关键字就用分支：pattern=\"foo|bar|baz\" —— **一次遍历搜完**，"
                        + "比调三次便宜得多（literal=true 时整串当普通文本，那时分支不生效）。"
                        + "默认只搜这个 git 仓库跟踪的文件（含还没 add 的新文件），"
                        + "不搜 .gitignore 忽略掉的构建产物与依赖树 —— 真要连它们一起搜就传 all=true。"
                        + "用 glob 把范围收到某一类文件上，例如 *.ss 或 *.{c,h}。"
                        + "要来回搜好几轮才说得清的问题（「注册流程在哪儿实现的」这种），"
                        + "别在这里一轮轮试 —— 用 task 派个子 agent 去，它回来给你一段结论。")
                .required("pattern", "string", "正则表达式，或 literal=true 时的普通文本；用 a|b 一次搜多个")
                .optional("path", "string", "从哪里开始搜，相对于工作根目录", ".")
                .optional("glob", "string", "只搜文件名匹配它的文件，例如 *.ss、*.{c,h}、coxn/agent/*.ss", "")
                .optional("literal", "boolean", "把 pattern 当普通文本，不当正则", false)
                .optional("ignore-case", "boolean", "忽略大小写", false)
                .optional("output", "string", "content=命中的行（默认）/ files=只列文件名 / count=每个文件几处", "content")
                .optional("context", "integer", "每条命中的前后各多带几行（只对 output=content 有效）", 0L)
                .optional("head-limit", "integer", "最多给几条，默认 250；传 0 用默认", 0L)
                .optional("offset", "integer", "跳过前几条（配合 head-limit 翻页）", 0L)
                .optional("all", "boolean", "连 .gitignore 忽略的文件一起搜（构建产物、依赖树）", false)
                .readOnly().concurrencySafe()
                .handler(SearchTools::runSearch)
                .build();
    }

    private enum Mode { CONTENT, FILES, COUNT }

    static String runSearch(ToolEnv env, Args a) {
        Path root = env.workspace().root();
        String pattern = a.requireStr("pattern");
        String path = a.str("path", ".");
        String glob = a.str("glob", "");
        boolean literal = a.bool("literal", false);
        boolean icase = a.bool("ignore-case", false);
        String output = a.str("output", "content");
        int context = Math.max(0, a.intValue("context", 0));
        int headLimit = a.intValue("head-limit", 0);
        int offset = Math.max(0, a.intValue("offset", 0));
        boolean all = a.bool("all", false);

        Path full = Limit.resolveUnder(root, path);
        String startRel = Limit.rel(root, full);
        Mode mode = switch (output) {
            case "files" -> Mode.FILES;
            case "count" -> Mode.COUNT;
            case "content" -> Mode.CONTENT;
            default -> throw new ToolException("output 只能是 \"content\" / \"files\" / \"count\"，收到 " + Fmt.q(output));
        };
        int limit = headLimit > 0 ? headLimit : DEFAULT_HEAD_LIMIT;
        List<String> files = all ? null : FileWalker.candidates(root, startRel);
        Predicate<String> globHit = glob.isEmpty() ? null : Glob.matcher(Glob.expand(glob));
        if (files != null && globHit != null) files = files.stream().filter(globHit).toList();
        if (files != null && files.isEmpty()) {
            return "没有可搜的文件：" + Fmt.q(path) + " 底下" + (glob.isEmpty() ? "" : "匹配 " + Fmt.q(glob) + " 的")
                    + "没有 git 跟踪的文件。（构建产物与依赖树默认不搜，要搜传 all=true）";
        }
        Pattern re;
        try {
            int flags = icase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
            re = Pattern.compile(literal ? Pattern.quote(pattern) : pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new ToolException("正则语法错：" + e.getDescription() + "（位置 " + e.getIndex() + "）；"
                    + "如果你搜的是普通文本而不是正则，把 literal 设为 true。");
        }
        List<String> targets = files;
        if (targets == null) {
            targets = FileWalker.walkAll(root, startRel, FileWalker.Kind.FILE);
            if (globHit != null) targets = targets.stream().filter(globHit).toList();
        }
        Grep g = new Grep(re, mode, context);
        for (String rel : targets) {
            env.cancel().throwIfCancelled();
            if (!g.file(root, rel)) break;
        }
        if (g.matchedFiles == 0) return "没有命中：" + Fmt.q(pattern) + " 在 " + Fmt.q(path) + " 下面找不到。";

        List<String> lines = Fmt.lines(g.out.toString());
        int total = lines.size();
        List<String> after = offset >= total ? List.of() : lines.subList(offset, total);
        List<String> kept = after.size() > limit ? after.subList(0, limit) : after;
        int shown = kept.size();
        StringBuilder r = new StringBuilder();
        r.append(summary(mode, total, shown, offset, files != null));
        r.append(Limit.truncateNote(Fmt.joinLines(kept), Limit.OUTPUT_LIMIT, "搜索结果"));
        if (offset + shown < total) {
            r.append("\n\n[只给了这些。要下一页：offset=").append(offset + shown)
                    .append("；或者把 pattern 写得更具体、用 glob 收窄。]");
        }
        if (g.over) {
            r.append("\n\n[命中太多，搜索在 ").append(CMD_OUTPUT_CAP)
                    .append(" 个字符处中止了。请把 pattern 写得更具体，或者把 path 缩到某个子目录。]");
        }
        return r.toString();
    }

    private static String summary(Mode mode, int total, int shown, int offset, boolean fromGit) {
        String scope = fromGit ? "" : "（连忽略的文件一起搜的）";
        return switch (mode) {
            case FILES -> total + " 个文件有命中" + scope + (shown < total ? "，这里给 " + shown + " 个" : "") + "\n";
            case COUNT -> total + " 个文件的计数" + scope + "\n";
            case CONTENT -> "找到 " + total + " 处命中" + scope
                    + (offset > 0 || shown < total ? "，这里给第 " + (offset + 1) + "–" + (offset + shown) + " 条" : "") + "\n";
        };
    }

    /** 进程内的 grep：输出形状照 {@code grep -n [-C N] / -l / -c}，总带文件名前缀。 */
    static final class Grep {
        final Pattern re;
        final Mode mode;
        final int context;
        final StringBuilder out = new StringBuilder();
        int matchedFiles;
        boolean over;
        boolean anyGroup;

        Grep(Pattern re, Mode mode, int context) { this.re = re; this.mode = mode; this.context = context; }

        /** 搜一个文件；回 false = 撞上限，别再搜了。 */
        boolean file(Path root, String rel) {
            Path p = root.resolve(rel);
            String text = readText(p);
            if (text == null) return true;
            List<String> lines = Fmt.lines(text);
            List<Integer> hits = new ArrayList<>();
            Matcher m = re.matcher("");
            for (int i = 0; i < lines.size(); i++) {
                StepLimited cs = new StepLimited(lines.get(i), STEP_BUDGET_PER_LINE);
                boolean found;
                try {
                    found = m.reset(cs).find();
                } catch (StepLimited.Exceeded e) {
                    throw new ToolException("正则在 " + rel + " 第 " + (i + 1) + " 行上回溯步数超限（模式太复杂）。"
                            + "换个更具体的写法，或者把 literal 设为 true。");
                }
                if (found) {
                    hits.add(i);
                    if (mode == Mode.FILES) break;
                }
            }
            if (hits.isEmpty()) return true;
            matchedFiles++;
            switch (mode) {
                case FILES -> out.append(rel).append('\n');
                case COUNT -> out.append(rel).append(':').append(hits.size()).append('\n');
                case CONTENT -> {
                    if (context == 0) {
                        for (int i : hits) {
                            out.append(rel).append(':').append(i + 1).append(':').append(lines.get(i)).append('\n');
                            if (out.length() > CMD_OUTPUT_CAP) { over = true; return false; }
                        }
                    } else {
                        int k = 0;
                        while (k < hits.size()) {
                            int from = Math.max(0, hits.get(k) - context);
                            int to = Math.min(lines.size() - 1, hits.get(k) + context);
                            int j = k + 1;
                            while (j < hits.size() && hits.get(j) - context <= to + 1) {
                                to = Math.min(lines.size() - 1, hits.get(j) + context);
                                j++;
                            }
                            if (anyGroup) out.append("--\n");
                            anyGroup = true;
                            java.util.Set<Integer> hs = new java.util.HashSet<>(hits.subList(k, j));
                            for (int i = from; i <= to; i++) {
                                char sep = hs.contains(i) ? ':' : '-';
                                out.append(rel).append(sep).append(i + 1).append(sep).append(lines.get(i)).append('\n');
                            }
                            if (out.length() > CMD_OUTPUT_CAP) { over = true; return false; }
                            k = j;
                        }
                    }
                }
            }
            if (out.length() > CMD_OUTPUT_CAP) { over = true; return false; }
            return true;
        }
    }

    /** 读成文本；软链 / 读不动 / 太大 / 二进制（前 8K 里有 NUL）回 null。 */
    static String readText(Path p) {
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null;
            if (Files.size(p) > MAX_FILE_BYTES) return null;
            byte[] bytes;
            try (InputStream in = Files.newInputStream(p)) { bytes = in.readAllBytes(); }
            int probe = Math.min(bytes.length, 8000);
            for (int i = 0; i < probe; i++) if (bytes[i] == 0) return null;
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** 数 charAt 次数的 CharSequence：超预算抛 {@link Exceeded}。 */
    static final class StepLimited implements CharSequence {
        static final class Exceeded extends RuntimeException {
            Exceeded() { super(null, null, false, false); }
        }

        private final String s;
        private final int off, len;
        private final long[] budget;

        StepLimited(String s, long budget) { this(s, 0, s.length(), new long[]{budget}); }

        private StepLimited(String s, int off, int len, long[] budget) {
            this.s = s; this.off = off; this.len = len; this.budget = budget;
        }

        @Override public int length() { return len; }

        @Override public char charAt(int i) {
            if (--budget[0] < 0) throw new Exceeded();
            return s.charAt(off + i);
        }

        @Override public CharSequence subSequence(int start, int end) {
            return new StepLimited(s, off + start, end - start, budget);
        }

        @Override public String toString() { return s.substring(off, off + len); }
    }

    // ── find-files ─────────────────────────────────────────────────────

    public static AgentTool findFiles() {
        return AgentTool.builder("find-files")
                .description("按文件名找文件（自动递归子目录）。name 是 shell 通配符，例如 *.c 或 test_*；"
                        + "或者传 fuzzy=true 用**模糊关键字**（子序列匹配，与编辑器里 `@` 的补全同一套"
                        + "打分）：fuzzy 下 name 写 agtool 也找得到 coxn/agent/tools/file.ss。"
                        + "默认只看这个 git 仓库跟踪的文件（含还没 add 的新文件），"
                        + "不看 .gitignore 忽略掉的构建产物与依赖树 —— 要连它们一起找就传 all=true。")
                .required("name", "string", "文件名通配符（例如 *.ss），或 fuzzy=true 时的模糊关键字")
                .optional("path", "string", "从哪里开始找，相对于工作根目录", ".")
                .optional("type", "string", "f 只要文件，d 只要目录，留空则两者都要", "")
                .optional("fuzzy", "boolean", "把 name 当模糊关键字（子序列匹配 + 打分排序），不当通配符", false)
                .optional("head-limit", "integer", "最多给几条，默认 250；传 0 用默认", 0L)
                .optional("all", "boolean", "连 .gitignore 忽略的文件一起找（构建产物、依赖树）", false)
                .readOnly().concurrencySafe()
                .handler(SearchTools::runFind)
                .build();
    }

    static String runFind(ToolEnv env, Args a) {
        Path root = env.workspace().root();
        String name = a.requireStr("name");
        String path = a.str("path", ".");
        String type = a.str("type", "");
        boolean fuzzy = a.bool("fuzzy", false);
        int headLimit = a.intValue("head-limit", 0);
        boolean all = a.bool("all", false);
        Path full = Limit.resolveUnder(root, path);
        String startRel = Limit.rel(root, full);
        int limit = headLimit > 0 ? headLimit : DEFAULT_HEAD_LIMIT;
        FileWalker.Kind kind = switch (type) {
            case "f" -> FileWalker.Kind.FILE;
            case "d" -> FileWalker.Kind.DIR;
            case "" -> FileWalker.Kind.ANY;
            default -> throw new ToolException("type 只能是 \"f\"、\"d\" 或留空，收到 " + Fmt.q(type));
        };
        // git 候选只有文件，没有目录 —— type=d 必须走老路，不能回一张空表冒充"没有这个目录"
        List<String> files = kind == FileWalker.Kind.DIR || all ? null : FileWalker.candidates(root, startRel);
        if (files != null) return findInCandidates(files, name, fuzzy, limit);
        List<String> walked = FileWalker.walkAll(root, startRel, kind);
        if (fuzzy) {
            if (walked.isEmpty()) return "没有找到模糊匹配 " + Fmt.q(name) + " 的文件。";
            return findInCandidates(walked, name, true, limit);
        }
        Predicate<String> m = Glob.matcher(Glob.expand(name).stream().map(Glob::basename).toList());
        List<String> hits = walked.stream().filter(r -> m.test(Glob.basename(r))).toList();
        if (hits.isEmpty()) return "没有找到匹配 " + Fmt.q(name) + " 的文件。";
        List<String> kept = hits.size() > limit ? hits.subList(0, limit) : hits;
        return "找到 " + hits.size() + " 个" + (kept.size() < hits.size() ? "，这里给前 " + kept.size() + " 个" : "") + "\n"
                + Limit.truncateNote(Fmt.joinLines(kept), Limit.OUTPUT_LIMIT, "文件列表");
    }

    private static String findInCandidates(List<String> files, String name, boolean fuzzy, int limit) {
        List<String> hits = fuzzy ? Fuzzy.filter(files, name) : files.stream().filter(Glob.matcher(Glob.expand(name))).toList();
        int total = hits.size();
        if (total == 0) {
            return "没有找到" + (fuzzy ? "模糊匹配" : "匹配") + " " + Fmt.q(name) + " 的文件。（构建产物与依赖树默认不看，要看传 all=true）";
        }
        List<String> kept = total > limit ? hits.subList(0, limit) : hits;
        return "找到 " + total + " 个" + (kept.size() < total ? "，这里给前 " + kept.size() + " 个" : "") + "\n"
                + Limit.truncateNote(Fmt.joinLines(kept), Limit.OUTPUT_LIMIT, "文件列表");
    }
}
