package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;
import io.ailink.agentforge.agent.tools.file.EditTool;
import io.ailink.agentforge.agent.tools.file.PatchTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 审批预览：这次调用批准之后会发生什么（对应 coxswain {@code tools/preview.ss}）。
 *
 * <p>{@link Kind} 不是为了好看：run-command 里一行 {@code -rf /tmp/x} 按 diff 规则会被画成红色"删除行"，
 * 而它是命令的一部分。edit-file 的预览与工具体走同一个 {@link EditTool#editApply}，语法那句与工具体走同一个
 * {@link Check} —— 屏幕上的 diff 与真正会发生的事不许是两份实现。
 */
public final class Preview {
    private Preview() { }

    public enum Kind { DIFF, TEXT }

    /** @param kind DIFF = unified diff（按加减行上色）；TEXT = 原样显示，不许按 diff 上色 */
    public record Result(Kind kind, String text) { }

    /**
     * 会话层在审批闸上调：null 表示"这件工具没有可预览的东西"（不是出错），调用方退回一行参数预览。
     * 永不抛。
     */
    public static Result toolChangePreview(Workspace ws, String name, ObjectNode input) {
        try {
            return switch (name) {
                case "write-file" -> diff(writePreview(ws, input, true));
                case "edit-file" -> diff(editPreview(ws, input, true));
                case "apply-patch" -> {
                    String t = str(input, "patchText");
                    yield t == null ? null : diff(PatchTool.preview(ws, t));
                }
                case "run-command" -> {
                    String cmd = str(input, "command");
                    yield cmd == null ? null : new Result(Kind.TEXT, "run-command\n在 " + ws.root() + " 下执行：\n" + cmd);
                }
                case "execute" -> {
                    String c = str(input, "code");
                    yield c == null ? null : new Result(Kind.TEXT, "execute（脚本）\n" + c);
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Result diff(String s) { return s == null ? null : new Result(Kind.DIFF, s); }

    /**
     * 改文件的调用（write-file / edit-file / apply-patch）<b>动手之前</b>的改动 diff（格式同审批预览，不跑语法检查）：
     * 没过审批的调用落地后也要画真 diff、真行号，就得趁文件还没改时把 before 记下来。别的工具 / 算不出 ⇒ null；永不抛。
     */
    public static String changeDiff(Workspace ws, String name, ObjectNode input) {
        try {
            return switch (name) {
                case "write-file" -> writePreview(ws, input, false);
                case "edit-file" -> editPreview(ws, input, false);
                case "apply-patch" -> {
                    String t = str(input, "patchText");
                    yield t == null ? null : PatchTool.preview(ws, t);
                }
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(ObjectNode in, String k) {
        if (in == null) return null;
        JsonNode n = in.get(k);
        return n != null && n.isString() ? n.asString() : null;
    }

    private static String current(Workspace ws, String full) {
        return Vfs.exists(ws, full) ? Vfs.read(ws, full).text() : "";
    }

    /** 标题一行 + diff；没有变化要说出来；语法那句放在 diff 之后（先看改了什么，再看后果）。 */
    private static String unified(Workspace ws, String title, String path, String before, String after, boolean check) {
        String d = Diff.unified(before, after, "现在", "批准之后", 3);
        String note = check ? Check.previewNote(ws, path, before, after) : null;
        String body = d.isEmpty() ? title + "\n（这次调用不会改变文件内容）" : title + "\n" + d;
        return note != null ? body + "\n" + note : body;
    }

    private static String writePreview(Workspace ws, ObjectNode in, boolean check) {
        String path = str(in, "path"), content = str(in, "content");
        if (path == null || content == null) return null;
        String full = Limit.resolveInRoot(ws, path);
        boolean exists = Vfs.exists(ws, full);
        return unified(ws, "write-file " + (exists ? "覆盖" : "新建") + " " + full, path, current(ws, full), content, check);
    }

    private static String editPreview(Workspace ws, ObjectNode in, boolean check) {
        String path = str(in, "path"), old = str(in, "old");
        String nu = in.has("new") ? str(in, "new") : "";
        boolean all = in.has("all") && in.get("all").asBoolean();
        if (path == null || old == null || nu == null) return null;
        String full = Limit.resolveInRoot(ws, path);
        // "文件不存在"与"原文没匹配上"分开说：用户的下一步不一样
        if (!Vfs.exists(ws, full)) return "edit-file " + path + "\n（这个文件不存在，这次调用会失败）";
        FileText ft = Vfs.read(ws, full);
        String before = ft.text();
        String after = EditTool.editApply(before, eol(ft, old), eol(ft, nu), all);
        if (after == null) return "edit-file " + path + "\n（找不到要替换的原文，这次调用会失败）";
        return unified(ws, "edit-file " + path, path, before, after, check);
    }

    private static String eol(FileText ft, String s) {
        return ft.eol() == FileText.Eol.CRLF && s.contains("\r\n") ? FileText.normalizeCrlf(s) : s;
    }
}
