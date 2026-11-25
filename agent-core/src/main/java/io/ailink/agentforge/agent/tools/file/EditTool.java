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

import java.util.List;

import static io.ailink.agentforge.agent.tools.ToolText.q;

/**
 * edit-file：精确匹配替换（对应 coxswain {@code tools/edit.ss}）。
 *
 * <p>不许模糊匹配 —— 一旦允许，"改错地方"就又回来了。空白归一化、行号前缀只用来**诊断**为什么没找到。
 * 不唯一就拒：默默改第一处，模型无从察觉。
 */
public final class EditTool {
    private EditTool() { }

    public static List<AgentTool> tools() { return List.of(editFile()); }

    /** 子串出现次数，不重叠地数（"aa" 在 "aaa" 里算一次）。 */
    public static int countOccurrences(String hay, String needle) {
        if (needle.isEmpty()) return 0;
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    /**
     * 改完之后的整篇；old 一次都没出现时回 null。工具体与审批预览共用这一份 ——
     * 屏幕上那份 diff 与真正会发生的事必须是同一个实现。"出现多次"是策略，归工具那一层。
     */
    public static String editApply(String text, String old, String nu, boolean all) {
        if (old.isEmpty() || countOccurrences(text, old) == 0) return null;
        if (all) return text.replace(old, nu);
        int i = text.indexOf(old);
        return text.substring(0, i) + nu + text.substring(i + old.length());
    }

    private static String squeeze(String s) {
        StringBuilder out = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!sp) out.append(' ');
                sp = true;
            } else {
                out.append(c);
                sp = false;
            }
        }
        return out.toString();
    }

    /** old 的每一行都以 "数字:" 开头（允许前导空白）—— 抄了 read-file 的行号前缀。只诊断。 */
    static boolean lineNumbered(String s) {
        if (s.isEmpty()) return false;
        for (String line : s.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int j = 0;
            while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j++;
            int k = j;
            while (k < line.length() && Character.isDigit(line.charAt(k)) && line.charAt(k) < 128) k++;
            if (k == j || k >= line.length() || line.charAt(k) != ':') return false;
        }
        return true;
    }

    /** CRLF 文件：old / new 里的 \r\n 归成 \n 再匹配；只对 CRLF 文件做（LF 文件里零星的 \r 是内容）。 */
    static String eolNormalize(FileText ft, String s) {
        return ft.eol() == FileText.Eol.CRLF && s.contains("\r\n") ? FileText.normalizeCrlf(s) : s;
    }

    public static AgentTool editFile() {
        return AgentTool.builder("edit-file")
                .description("把文件里的一段原文替换成另一段。old 必须与文件内容**一字不差**"
                        + "（含缩进与换行），而且必须在文件里只出现一次 —— 出现多次时请多带几行"
                        + "上下文让它唯一，或者传 all=true 表示全部替换。改一行不要用 write-file"
                        + "重写整个文件。改之前先用 read-file 看一眼 —— 没读过的文件改不了。")
                .required("path", "string", "文件路径")
                .optional("tag", "string", "可省：这个文件最近一次读出来时的 TAG。传了会一起校验", "")
                .required("old", "string", "要被替换掉的原文，必须与文件内容完全一致（不要带 read-file 输出里的行号前缀）")
                .required("new", "string", "替换成什么。留空表示删掉这段")
                .optional("all", "boolean", "把所有出现的地方都替换掉", false)
                .sensitive().stageable()
                .handler(EditTool::run)
                .build();
    }

    static Object run(ToolEnv env, Args a) {
        Workspace ws = env.workspace();
        String path = a.requireStr("path");
        String tag = a.str("tag", "");
        boolean all = a.bool("all", false);
        String full = Limit.mustExist(ws, path, Limit.resolveInRoot(ws, path), Limit.Want.FILE);
        FileText ft = Vfs.read(ws, full);
        String text = ft.text();
        String old = eolNormalize(ft, a.requireStr("old"));
        String nu = eolNormalize(ft, a.str("new", ""));
        if (ft.notText()) return q(path) + " 看起来不是 UTF-8 文本，没有改。";
        // 新鲜度校验在匹配之前：建立在过期内容上的编辑不该先匹配成功再被拒
        String why = Seen.of(ws).check(path, full, text, tag);
        if (why != null) throw new ToolException(why);
        if (old.isEmpty()) throw new ToolException("old 不能为空。要插入内容，请把插入点附近的原文一起带上");
        if (old.equals(nu)) throw new ToolException("old 与 new 一样，这次编辑没有任何效果");
        int n = countOccurrences(text, old);
        if (n == 0) {
            if (lineNumbered(old)) {
                throw new ToolException("在 " + q(path) + " 里没找到那段原文；看起来你把 read-file 输出里的行号前缀（像 \"12:\"）也抄进 old 了 —— old 要的是文件里的原文，不含行号");
            }
            if (countOccurrences(squeeze(text), squeeze(old)) > 0) {
                throw new ToolException("在 " + q(path) + " 里没找到那段原文；但有一处**只在空白上不同** —— 请照文件里的原样抄（缩进、tab、换行）");
            }
            throw new ToolException("在 " + q(path) + " 里没找到那段原文。先 read-file 看一眼实际内容再改");
        }
        if (n > 1 && !all) {
            throw new ToolException("那段原文在 " + q(path) + " 里出现了 " + n + " 次，不知道该改哪一处。请多带几行上下文让它唯一，或者传 all=true 全部替换");
        }
        int line = ToolText.lineOf(text, text.indexOf(old));
        String out = editApply(text, old, nu, all);
        // 校验在写之前：这个工具的全部价值是"要么对，要么什么都没发生"
        String worse = Check.notWorse(ws, path, text, out);
        if (worse != null) throw new ToolException(worse);
        Vfs.write(ws, full, out, ft.eol(), ft.coding(), ft.bom());
        Seen.of(ws).note(full, out);
        return q(path) + " 已改：替换 " + (all ? n : 1) + " 处，首处在第 " + line + " 行。文件现在 "
                + ToolText.lineCountNoTrailing(out) + " 行。新的 TAG 是 " + Tags.contentTag(out);
    }
}
