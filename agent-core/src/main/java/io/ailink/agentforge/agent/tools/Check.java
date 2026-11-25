package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.List;

/**
 * "别把文件改得更糟"（对应 coxswain {@code tools/check.ss}）。
 *
 * <p>判据是**问题变多才拒**，不是"不合法就拒"：后者会卡死在一个本来就坏、正在被一步步修好的文件上。
 * 所以 before / after 两边都要过检查器。{@link #notWorse} 给模型、{@link #previewNote} 给审批界面，
 * 两者共用同一次判断 —— 最伤信任的是"预览说没问题、按了 y 之后被拒"。
 */
public final class Check {
    private Check() { }

    private record Worse(int before, int after, List<String> problems) { }

    private static List<String> problems(Workspace ws, String path, String text) {
        Checker c = Checker.of(ws);
        if (c == null) return null;
        try {
            return c.problems(path, text);
        } catch (RuntimeException e) {
            return null;   // 检查器出事 → 当作看不了，更不能把异常漏给模型
        }
    }

    private static Worse worse(Workspace ws, String path, String before, String after) {
        List<String> b = problems(ws, path, before);
        if (b == null) return null;
        List<String> a = problems(ws, path, after);
        if (a == null || a.size() <= b.size()) return null;
        return new Worse(b.size(), a.size(), a);
    }

    private static String headline(int nb, int na) {
        return nb == 0 ? "这次改动让文件解析不了了。" : "这次改动让文件的语法问题从 " + nb + " 处变成了 " + na + " 处。";
    }

    /** null = 可以写；否则是拒绝的理由（给模型）。 */
    public static String notWorse(Workspace ws, String path, String before, String after) {
        Worse w = worse(ws, path, before, after);
        if (w == null) return null;
        StringBuilder sb = new StringBuilder(headline(w.before, w.after)).append("**没有写入**。\n");
        for (String p : w.problems) sb.append("  ").append(p).append('\n');
        sb.append("\n下一步：先 read-file 看一眼现在的样子，然后**一次改完整**")
          .append("（这次改动的中间状态不合法的话，把它合并成一次编辑，或者用 write-file 整篇写一遍）。");
        return sb.toString();
    }

    /** 给审批预览的一句话；null = 没什么要说的。 */
    public static String previewNote(Workspace ws, String path, String before, String after) {
        Worse w = worse(ws, path, before, after);
        if (w != null) return "⚠️ 批准之后这次调用**会被拒**：" + headline(w.before, w.after);
        List<String> a = problems(ws, path, after);
        if (a == null) return null;
        if (a.isEmpty()) return "✓ 改完之后语法没问题";
        return "⚠️ 改完之后这个文件仍然有 " + a.size() + " 处语法问题（不是这次改动造成的，所以照常执行）";
    }
}
