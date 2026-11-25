package io.ailink.agentforge.agent.tools.file;

import io.ailink.agentforge.agent.tool.AgentTool;
import io.ailink.agentforge.agent.tool.Args;
import io.ailink.agentforge.agent.tool.ToolEnv;
import io.ailink.agentforge.agent.tool.ToolException;
import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.Check;
import io.ailink.agentforge.agent.tools.Diff;
import io.ailink.agentforge.agent.tools.FileText;
import io.ailink.agentforge.agent.tools.Limit;
import io.ailink.agentforge.agent.tools.Seen;
import io.ailink.agentforge.agent.tools.Tags;
import io.ailink.agentforge.agent.tools.ToolText;
import io.ailink.agentforge.agent.tools.Vfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * apply-patch：OpenAI V4A 补丁格式，一次改几个文件（对应 coxswain {@code tools/patch.ss}，对齐 opencode apply_patch）。
 *
 * <p>先把整份补丁全算完（解析 → 每个文件定位 → 校验 seen / 语法），任何一段对不上就一个文件都不改；全过了才写。
 * 定位走四趟（逐字 → 去尾空白 → 去两端空白 → Unicode 标点归 ASCII），与 opencode 同款。
 */
public final class PatchTool {
    private PatchTool() { }

    public static List<AgentTool> tools() { return List.of(applyPatch()); }

    public enum HunkType { ADD, DELETE, UPDATE }

    /** 一段改动：old / new 行、可选的 @@ 上下文行、是否钉在文件尾。 */
    public record Chunk(List<String> oldLines, List<String> newLines, String context, boolean eof) { }

    /** 一个 hunk：ADD 有 content；UPDATE 有 move（可空）与 chunks。 */
    public record Hunk(HunkType type, String path, String move, String content, List<Chunk> chunks) { }

    private static final String BEGIN = "*** Begin Patch";
    private static final String END = "*** End Patch";

    private static final class PatchError extends RuntimeException {
        PatchError(String m) { super(m); }
    }

    // ── 解析 ──────────────────────────────────────────────────────────

    /** {@code cat <<'EOF' … EOF} / {@code <<EOF … EOF} 剥壳；不是那个形状就原样。 */
    public static String stripHeredoc(String text) {
        List<String> ls = ToolText.splitLines(text);
        if (ls.isEmpty()) return text;
        String m = heredocMarker(ls.getFirst().strip());
        if (m == null || ls.size() < 2) return text;
        List<String> body = ls.subList(1, ls.size());
        int last = body.size() - 1;
        while (last >= 0 && body.get(last).strip().isEmpty()) last--;
        if (last < 0 || !body.get(last).strip().equals(m)) return text;
        return String.join("\n", body.subList(0, last));
    }

    private static String heredocMarker(String line) {
        String s = line.startsWith("cat ") ? line.substring(4).strip() : line;
        if (!s.startsWith("<<")) return null;
        String rest = s.substring(2).strip();
        if (!rest.isEmpty() && (rest.charAt(0) == '\'' || rest.charAt(0) == '"')) rest = rest.substring(1);
        int n = 0;
        while (n < rest.length() && (Character.isLetterOrDigit(rest.charAt(n)) || rest.charAt(n) == '_')) n++;
        return n > 0 ? rest.substring(0, n) : null;
    }

    private static String crlfToLf(String s) { return s.replace("\r\n", "\n").replace('\r', '\n'); }

    /** 补丁正文 → hunk 表；格式不对抛 {@link ToolException}。 */
    public static List<Hunk> parse(String text) {
        String cleaned = stripHeredoc(crlfToLf(text).strip());
        List<String> ls = ToolText.splitLines(cleaned);
        int n = ls.size();
        int b = -1, e = -1;
        for (int i = 0; i < n; i++) if (b < 0 && ls.get(i).strip().equals(BEGIN)) b = i;
        for (int i = 0; i < n; i++) if (e < 0 && ls.get(i).strip().equals(END)) e = i;
        if (b < 0 || e < 0 || b >= e) throw new ToolException("补丁格式不对：缺 *** Begin Patch / *** End Patch 那一对");
        List<Hunk> acc = new ArrayList<>();
        int i = b + 1;
        while (i < e) {
            String line = ls.get(i);
            if (line.startsWith("*** Add File:")) {
                List<String> content = new ArrayList<>();
                int j = i + 1;
                for (; j < e && !ls.get(j).startsWith("***"); j++) {
                    if (ls.get(j).startsWith("+")) content.add(ls.get(j).substring(1));
                }
                acc.add(new Hunk(HunkType.ADD, headerPath(line, "*** Add File:"), null, String.join("\n", content), List.of()));
                i = j;
            } else if (line.startsWith("*** Delete File:")) {
                acc.add(new Hunk(HunkType.DELETE, headerPath(line, "*** Delete File:"), null, null, List.of()));
                i++;
            } else if (line.startsWith("*** Update File:")) {
                String path = headerPath(line, "*** Update File:");
                int j = i + 1;
                String move = null;
                if (j < e && ls.get(j).startsWith("*** Move to:")) {
                    move = headerPath(ls.get(j), "*** Move to:");
                    j++;
                }
                List<Chunk> chunks = new ArrayList<>();
                j = updateChunks(ls, j, e, chunks);
                acc.add(new Hunk(HunkType.UPDATE, path, move, null, chunks));
                i = j;
            } else i++;
        }
        return acc;
    }

    private static String headerPath(String line, String prefix) {
        String p = line.substring(prefix.length()).strip();
        if (p.isEmpty()) throw new ToolException("这一行少了路径：" + line);
        return p;
    }

    private static int updateChunks(List<String> ls, int i, int e, List<Chunk> acc) {
        while (i < e && !ls.get(i).startsWith("***")) {
            if (!ls.get(i).startsWith("@@")) { i++; continue; }
            String ctx = ls.get(i).substring(2).strip();
            List<String> old = new ArrayList<>(), nu = new ArrayList<>();
            int j = i + 1;
            boolean eof = false;
            while (true) {
                String l = j < e ? ls.get(j) : null;
                if (l == null || l.startsWith("@@") || l.startsWith("***")) {
                    if (l != null && l.equals("*** End of File")) { eof = true; j++; }
                    break;
                }
                if (l.startsWith(" ")) { old.add(l.substring(1)); nu.add(l.substring(1)); }
                else if (l.startsWith("-")) old.add(l.substring(1));
                else if (l.startsWith("+")) nu.add(l.substring(1));
                else if (l.isEmpty()) { old.add(""); nu.add(""); }   // 模型常把 ` ` 前缀省掉
                j++;
            }
            acc.add(new Chunk(old, nu, ctx.isEmpty() ? null : ctx, eof));
            i = j;
        }
        return i;
    }

    // ── 套用 ──────────────────────────────────────────────────────────

    private record Rep(int start, int oldLen, List<String> newLines) { }

    /** 原文 + chunks → 新文本（以 \n 结尾）。找不到抛错误，带那几行。 */
    public static String applyChunks(String shown, String text, List<Chunk> chunks) {
        List<String> lines = ToolText.splitLines(text);
        if (!lines.isEmpty() && lines.getLast().isEmpty()) lines.removeLast();
        List<Rep> reps = new ArrayList<>();
        int at = 0;
        for (Chunk c : chunks) {
            if (c.context() != null) {
                int k = seek(lines, List.of(c.context()), at, false);
                if (k < 0) throw new ToolException("在 " + q(shown) + " 里没找到上下文 '" + c.context() + "'");
                at = k + 1;
            }
            List<String> old = c.oldLines();
            if (old.isEmpty()) {   // 纯加：追加到文件尾
                reps.add(new Rep(lines.size(), 0, c.newLines()));
                continue;
            }
            int k = seek(lines, old, at, c.eof());
            if (k >= 0) {
                reps.add(new Rep(k, old.size(), c.newLines()));
                at = k + old.size();
                continue;
            }
            // 尾上一行空的去掉再试一次（old / new 一起去）
            List<String> old2 = old.getLast().isEmpty() ? old.subList(0, old.size() - 1) : null;
            List<String> nu = c.newLines();
            List<String> new2 = old2 == null ? null : (!nu.isEmpty() && nu.getLast().isEmpty() ? nu.subList(0, nu.size() - 1) : nu);
            int k2 = old2 != null && !old2.isEmpty() ? seek(lines, old2, at, c.eof()) : -1;
            if (k2 < 0) throw new ToolException("在 " + q(shown) + " 里没找到这几行（照文件里的原样抄，含缩进）：\n" + String.join("\n", old));
            reps.add(new Rep(k2, old2.size(), new2));
            at = k2 + old2.size();
        }
        reps.sort(Comparator.comparingInt(Rep::start));
        List<String> out = new ArrayList<>(lines);
        for (int r = reps.size() - 1; r >= 0; r--) {   // 倒着套，下标不漂
            Rep rep = reps.get(r);
            List<String> next = new ArrayList<>(out.subList(0, rep.start()));
            next.addAll(rep.newLines());
            next.addAll(out.subList(Math.min(out.size(), rep.start() + rep.oldLen()), out.size()));
            out = next;
        }
        return String.join("\n", out) + "\n";
    }

    private static String trimRight(String s) { return s.stripTrailing(); }

    private static int seek(List<String> lv, List<String> pat, int at, boolean eof) {
        List<BiPredicate<String, String>> passes = List.of(
                String::equals,
                (a, b) -> trimRight(a).equals(trimRight(b)),
                (a, b) -> a.strip().equals(b.strip()),
                (a, b) -> ascii(a.strip()).equals(ascii(b.strip())));
        for (BiPredicate<String, String> same : passes) {
            int k = tryMatch(lv, pat, at, same, eof);
            if (k >= 0) return k;
        }
        return -1;
    }

    private static int tryMatch(List<String> lv, List<String> pat, int at, BiPredicate<String, String> same, boolean eof) {
        int n = lv.size(), m = pat.size();
        if (eof && n - m >= at && n - m >= 0 && matchAt(lv, pat, n - m, same)) return n - m;
        for (int i = at; i + m <= n; i++) if (matchAt(lv, pat, i, same)) return i;
        return -1;
    }

    private static boolean matchAt(List<String> lv, List<String> pat, int i, BiPredicate<String, String> same) {
        for (int j = 0; j < pat.size(); j++) if (!same.test(lv.get(i + j), pat.get(j))) return false;
        return true;
    }

    /** Unicode 标点归 ASCII（同 opencode normalizeUnicode）。 */
    static String ascii(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '‘', '’', '‚', '‛' -> out.append('\'');
                case '“', '”', '„', '‟' -> out.append('"');
                case '‐', '‑', '‒', '–', '—', '―' -> out.append('-');
                case '…' -> out.append("...");
                case ' ' -> out.append(' ');
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    // ── 计划 ──────────────────────────────────────────────────────────

    enum Kind { ADD, DELETE, UPDATE, MOVE }

    record Plan(Kind kind, String shown, String full, String to, String before, String after, FileText ft) { }

    private static String textOf(Workspace ws, String full) {
        FileText ft = Vfs.read(ws, full);
        return ft.notText() ? "" : ft.text();
    }

    private static void mustSeen(Workspace ws, String shown, String full, String text) {
        String why = Seen.of(ws).check(shown, full, text, "");
        if (why != null) throw new ToolException(why);
    }

    static List<Plan> plan(Workspace ws, List<Hunk> hunks) {
        List<Plan> out = new ArrayList<>();
        for (Hunk h : hunks) {
            String shown = h.path();
            String full = Limit.resolveInRoot(ws, shown);
            switch (h.type()) {
                case ADD -> {
                    String c = h.content();
                    String content = c.isEmpty() || c.endsWith("\n") ? c : c + "\n";
                    if (!Vfs.isBufferAddress(full) && Files.isDirectory(Path.of(full))) {
                        throw new ToolException(q(shown) + " 是一个目录，不能当文件建");
                    }
                    out.add(new Plan(Kind.ADD, shown, full, null, Vfs.exists(ws, full) ? textOf(ws, full) : null, content, null));
                }
                case DELETE -> {
                    String f = Limit.mustExist(ws, shown, full, Limit.Want.FILE);
                    String text = textOf(ws, f);
                    mustSeen(ws, shown, f, text);
                    out.add(new Plan(Kind.DELETE, shown, f, null, text, "", null));
                }
                case UPDATE -> {
                    String f = Limit.mustExist(ws, shown, full, Limit.Want.FILE);
                    FileText ft = Vfs.read(ws, f);
                    if (ft.notText()) throw new ToolException(q(shown) + " 看起来不是 UTF-8 文本，没有改");
                    String text = ft.text();
                    mustSeen(ws, shown, f, text);
                    String nu = applyChunks(shown, text, h.chunks());
                    String move = h.move();
                    String why = Check.notWorse(ws, move != null ? move : shown, text, nu);
                    if (why != null) throw new ToolException(why);
                    out.add(new Plan(move != null ? Kind.MOVE : Kind.UPDATE, shown, f,
                            move != null ? Limit.resolveInRoot(ws, move) : null, text, nu, ft));
                }
            }
        }
        return out;
    }

    /** 给审批预览：每个文件一段 unified diff 合起来（不写盘）；应用不了时说明原因而不是抛。 */
    public static String preview(Workspace ws, String patchText) {
        try {
            List<String> parts = new ArrayList<>();
            for (Plan p : plan(ws, parse(patchText))) {
                String head = switch (p.kind()) {
                    case ADD -> "A " + p.shown();
                    case DELETE -> "D " + p.shown();
                    case MOVE -> "M " + p.shown() + " → " + (p.to() != null ? p.to() : "?");
                    case UPDATE -> "M " + p.shown();
                };
                parts.add(head + "\n" + Diff.unified(p.before() == null ? "" : p.before(), p.after(), "现在", "批准之后", 3));
            }
            return String.join("\n", parts);
        } catch (RuntimeException e) {
            return "apply-patch\n（这份补丁应用不了：" + e.getMessage() + "）";
        }
    }

    public static AgentTool applyPatch() {
        return AgentTool.builder("apply-patch")
                .description("用一份补丁一次改几个文件（新建 / 删除 / 修改 / 改名）。格式：\n"
                        + "*** Begin Patch\n"
                        + "*** Add File: 路径        （之后每行以 + 开头，是新文件内容）\n"
                        + "*** Update File: 路径     （可跟一行 *** Move to: 新路径）\n"
                        + "@@ 一行上下文（可省）\n"
                        + " 保留的行 / -删掉的行 / +加上的行\n"
                        + "*** Delete File: 路径\n"
                        + "*** End Patch\n"
                        + "改一处用 edit-file 更省；改好几个文件、或一个文件里改好几处时用这个。"
                        + "要改 / 删的文件必须先 read-file 看过。任何一段对不上原文，整份补丁一个文件都不改。")
                .required("patchText", "string", "整份补丁文本（含 *** Begin Patch / *** End Patch）")
                .sensitive().stageable()
                .handler(PatchTool::run)
                .build();
    }

    static Object run(ToolEnv env, Args a) throws IOException {
        Workspace ws = env.workspace();
        String patchText = a.str("patchText", "");
        if (patchText.strip().isEmpty()) throw new ToolException("patchText 是空的");
        List<Hunk> hunks = parse(patchText);
        if (hunks.isEmpty()) throw new ToolException("补丁里一个文件都没有（要 *** Add File: / *** Update File: / *** Delete File: 那种头）");
        List<Plan> plan = plan(ws, hunks);
        // 全算完了，现在才写。删除 / 改名的删旧路径直接落盘（与原版一致：删除攒不住）
        Seen seen = Seen.of(ws);
        for (Plan p : plan) {
            switch (p.kind()) {
                case ADD -> {
                    Vfs.write(ws, p.full(), p.after(), FileText.platformEol(), FileText.Coding.UTF_8, false);
                    seen.note(p.full(), p.after());
                }
                case UPDATE -> {
                    Vfs.write(ws, p.full(), p.after(), p.ft().eol(), p.ft().coding(), p.ft().bom());
                    seen.note(p.full(), p.after());
                }
                case MOVE -> {
                    Vfs.write(ws, p.to(), p.after(), p.ft().eol(), p.ft().coding(), p.ft().bom());
                    seen.note(p.to(), p.after());
                    Files.deleteIfExists(Path.of(p.full()));
                }
                case DELETE -> Files.deleteIfExists(Path.of(p.full()));
            }
        }
        List<String> lines = new ArrayList<>();
        for (Plan p : plan) {
            lines.add(switch (p.kind()) {
                case ADD -> "A " + p.shown();
                case DELETE -> "D " + p.shown();
                case MOVE -> "M " + p.shown() + " → " + p.to();
                case UPDATE -> "M " + p.shown() + "（现在 " + ToolText.lineCountNoTrailing(p.after()) + " 行，TAG " + Tags.contentTag(p.after()) + "）";
            });
        }
        return "Success. Updated the following files:\n" + String.join("\n", lines);
    }
}
