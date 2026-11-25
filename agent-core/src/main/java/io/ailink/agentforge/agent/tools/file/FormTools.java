package io.ailink.agentforge.agent.tools.file;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Check;
import io.ailink.agentforge.agent.tools.FileText;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tools.Seen;
import io.ailink.agentforge.agent.tools.Tags;
import io.ailink.agentforge.agent.tools.ToolText;
import io.ailink.agentforge.agent.tools.Vfs;
import io.ailink.agentforge.agent.tools.file.SchemeForms.Result;
import io.ailink.agentforge.agent.tools.file.SchemeForms.TopForm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * list-forms / read-form / replace-form：按顶层形式读写 Scheme 文件（对应 coxswain {@code tools/form.ss}）。
 *
 * <p>为什么有这三件：整个重写一个函数时，抄原文 + 数最外层括号是模型最容易出错的地方；按名字取一个
 * 一定括号平衡的形式、整个换掉，就不用抄、不用数。两件读也印 {@code [路径#TAG]}，印了就要登记 seen ——
 * 这道闸守的是新鲜度，不是"逐字读过"，否则 list-forms → replace-form 这条不读全文的路就断了。
 */
public final class FormTools {
    private FormTools() { }

    public static List<AgentTool> tools() { return List.of(listForms(), readForm(), replaceForm()); }

    private static final List<String> SUFFIXES = List.of(".ss", ".scm", ".sls", ".sps", ".sch");

    static boolean scheme(String path) {
        String l = path.toLowerCase(Locale.ROOT);   // Windows 上 FOO.SS 是同一个文件
        for (String s : SUFFIXES) if (l.length() > s.length() && l.endsWith(s)) return true;
        return false;
    }

    private static void requireScheme(String path) {
        if (!scheme(path)) {
            throw new ToolException(q(path) + " 不是 Scheme 源文件，这两件工具只认 Scheme（.ss/.scm/.sls/.sps）。用 read-file / edit-file 改它");
        }
    }

    private record Loaded(String full, FileText ft) { String text() { return ft.text(); } }

    private static Loaded load(Workspace ws, String path) {
        String full = Limit.mustExist(ws, path, Limit.resolveInRoot(ws, path), Limit.Want.FILE);
        FileText ft = Vfs.read(ws, full);
        if (ft.notText()) throw new ToolException(q(path) + " 看起来不是 UTF-8 文本。");
        return new Loaded(full, ft);
    }

    private static String names(List<TopForm> forms) {
        List<String> ns = new ArrayList<>();
        for (TopForm f : forms) if (f.name() != null) ns.add(f.name());
        return String.join("、", ns);
    }

    /** 找唯一那个；不唯一就拒（与 edit-file 同一条纪律）。 */
    private static TopForm find(List<TopForm> forms, String name, String path, Integer stop) {
        List<TopForm> hits = new ArrayList<>();
        for (TopForm f : forms) if (name.equals(f.name())) hits.add(f);
        if (hits.isEmpty()) {
            throw new ToolException(q(path) + " 里没有叫 " + q(name) + " 的顶层形式。"
                    + (stop != null ? "（注意：这个文件从某处起解析不了，下面只是解析得动的那部分）" : "") + "有这些：" + names(forms));
        }
        if (hits.size() > 1) {
            throw new ToolException(q(path) + " 里有 " + hits.size() + " 个顶层形式都叫 " + q(name) + "，不知道该改哪一个。用 edit-file 多带几行上下文来区分");
        }
        return hits.getFirst();
    }

    private static int[] lineStarts(String text) {
        List<Integer> acc = new ArrayList<>();
        acc.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') acc.add(i + 1);
        int[] out = new int[acc.size()];
        for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
        return out;
    }

    /** 偏移落在第几行（1 起），二分。 */
    private static int lineOf(int[] starts, int off) {
        int lo = 0, hi = starts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (starts[mid] <= off) lo = mid; else hi = mid - 1;
        }
        return lo + 1;
    }

    public static AgentTool listForms() {
        return AgentTool.builder("list-forms")
                .description("列出一个 Scheme 文件里的顶层形式：名字与行号范围。"
                        + "想知道一个文件有什么、又不想把整篇读进来时用它 —— "
                        + "拿到名字之后用 read-form 只读那一个。返回的第一行是 [路径#TAG]。")
                .required("path", "string", "文件路径")
                .readOnly().concurrencySafe()
                .handler(FormTools::runListForms)
                .build();
    }

    static Object runListForms(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        Loaded l = load(ws, path);
        requireScheme(path);
        String text = l.text();
        Result r = SchemeForms.scan(text);
        int[] starts = lineStarts(text);
        Seen.of(ws).note(l.full(), text);
        StringBuilder sb = new StringBuilder("[" + path + "#" + Tags.contentTag(text) + "]\n");
        if (r.forms().isEmpty()) {
            sb.append("（没有顶层形式）");
        } else {
            List<String> lines = new ArrayList<>();
            for (TopForm f : r.forms()) {
                // end 不含，所以问 end-1 那个字符在第几行
                lines.add(lineOf(starts, f.start()) + "-" + lineOf(starts, f.end() - 1) + "  "
                        + (f.name() != null ? f.name() : "（匿名，用 edit-file 改）"));
            }
            sb.append(Limit.truncateNote(String.join("\n", lines), Limit.OUTPUT_LIMIT, q(path) + " 的顶层形式"));
        }
        if (r.stop() != null) sb.append("\n⚠️ 从第 ").append(lineOf(starts, r.stop())).append(" 行起解析不了，上面只是解析得动的那部分");
        return sb.toString();
    }

    public static AgentTool readForm() {
        return AgentTool.builder("read-form")
                .description("按名字读出一个**完整的**顶层形式（Scheme 文件专用）。取到的一定是括号平衡的，"
                        + "适合整个重写一个函数 —— 比 read-file 全文加 edit-file 抄原文可靠。"
                        + "返回值第一行是 [路径#TAG]，改它的时候把 TAG 传给 replace-form。"
                        + "⚠️ 取到的范围**包含这个形式上面的独立注释**；replace-form 换掉的是同一段，"
                        + "所以要保留那些注释就把它们一起写进 new。")
                .required("path", "string", "文件路径")
                .required("name", "string", "顶层形式的名字，例如 foo（对应 (define (foo …) …)）")
                .readOnly().concurrencySafe()
                .handler(FormTools::runReadForm)
                .build();
    }

    static Object runReadForm(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        String name = a.requireStr("name");
        Loaded l = load(ws, path);
        requireScheme(path);
        String text = l.text();
        Result r = SchemeForms.scan(text);
        TopForm f = find(r.forms(), name, path, r.stop());
        // 登记整篇的 tag：写文件那道闸比的是整篇
        Seen.of(ws).note(l.full(), text);
        return "[" + path + "#" + Tags.contentTag(text) + "]\n" + text.substring(f.start(), f.end());
    }

    public static AgentTool replaceForm() {
        return AgentTool.builder("replace-form")
                .description("把一个顶层形式**整个**换成新的（Scheme 文件专用）。new 必须恰好是一个完整的"
                        + "形式（括号自己平衡）—— 不用抄原文，也不用数最外层括号。"
                        + "改之前先用 read-form / list-forms / read-file 看一眼 —— 没读过的文件改不了。"
                        + "⚠️ 换掉的范围与 read-form 给你的**完全一致**，含那个形式上面的独立注释 ——"
                        + "要保留它们就把它们一起写进 new（也可以顺手把过期的注释改掉）。")
                .required("path", "string", "文件路径")
                .optional("tag", "string", "可省：这个文件最近一次读出来时的 TAG。传了会一起校验", "")
                .required("name", "string", "要替换的顶层形式的名字")
                .required("new", "string", "新的完整形式，例如 (define (foo x) …)")
                .sensitive().stageable()
                .handler(FormTools::runReplaceForm)
                .build();
    }

    static Object runReplaceForm(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        String tag = a.str("tag", "");
        String name = a.requireStr("name");
        String nu = a.requireStr("new");
        Loaded l = load(ws, path);
        requireScheme(path);
        String text = l.text();
        String why = Seen.of(ws).check(path, l.full(), text, tag);
        if (why != null) throw new ToolException(why);
        // new 必须恰好是一个形式：半个或两个原样塞进去，正是这件工具要消灭的东西
        Result nr = SchemeForms.scan(nu);
        if (nr.stop() != null) {
            throw new ToolException("new 解析不了（第 " + nr.stop() + " 个字符处读不下去）。它必须是一个**完整**的形式，括号要自己平衡");
        }
        if (nr.forms().isEmpty()) throw new ToolException("new 是空的。要删掉一个形式，用 edit-file");
        if (nr.forms().size() > 1) {
            throw new ToolException("new 里有 " + nr.forms().size() + " 个顶层形式，只能给一个。要一次改多处，分几次调用");
        }
        Result r = SchemeForms.scan(text);
        TopForm f = find(r.forms(), name, path, r.stop());
        String out = text.substring(0, f.start()) + nu + text.substring(f.end());
        String worse = Check.notWorse(ws, path, text, out);
        if (worse != null) throw new ToolException(worse);
        FileText ft = l.ft();
        Vfs.write(ws, l.full(), out, ft.eol(), ft.coding(), ft.bom());
        Seen.of(ws).note(l.full(), out);
        return q(path) + " 的 " + q(name) + " 已整个替换（" + ToolText.lineCount(text.substring(f.start(), f.end())) + " 行 → "
                + ToolText.lineCount(nu) + " 行）。新的 TAG 是 " + Tags.contentTag(out);
    }
}
