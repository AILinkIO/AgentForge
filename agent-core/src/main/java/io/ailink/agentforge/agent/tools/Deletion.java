package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 删除之前给路径分级 + 一次性确认格（对应 coxswain {@code agent/deletion.ss}）。
 *
 * <p>这一层是分类器，不是闸：它不删、也不决定让不让删。路径限定的字符串那一半看不见软链指向哪儿，
 * 而删除不可撤销，所以 delete-path 遇到 {@link Risk} 不为 null 的路径会拒绝，等审批闸把风险摆给用户。
 *
 * <p>会话层（审批闸）的接法：
 * <ol>
 *   <li>delete-path 调用进闸时，用 {@link #risk(Workspace, String)} 分级；null = 普通删除，照常问/放行。</li>
 *   <li>用户点头后、执行工具之前，按用户选的那一项放确认格 {@link #confirm(Workspace, String, String)}：
 *     <ul>
 *       <li>SYMLINK + "只删链接" → {@code confirm(ws, path, path)}（target = 原输入）</li>
 *       <li>SYMLINK + "连内容一起删" → {@code confirm(ws, path, risk.real())}</li>
 *       <li>ESCAPE → {@code confirm(ws, path, risk.real())}（用户看见的那个 realpath；不存在时没有可确认的目标）</li>
 *       <li>UNRESOLVABLE → {@code confirm(ws, path, risk.path())}（删链接文件本身）</li>
 *     </ul></li>
 *   <li>工具执行时取走（{@link #take}），一次确认只对一次删除有效；拒绝 / 取消时 {@link #clear}。</li>
 * </ol>
 * {@code path} 必须与工具收到的 {@code path} 参数逐字相同。
 */
public final class Deletion {

    public enum Kind { SYMLINK, ESCAPE, UNRESOLVABLE }

    /**
     * @param path 报给用户的路径（SYMLINK / UNRESOLVABLE：链接本身；ESCAPE：完整路径或原输入）
     * @param real 解到的真实目标；ESCAPE 且不存在 / UNRESOLVABLE 时为 null
     */
    public record Risk(Kind kind, String path, String real) { }

    /** 用户确认过的一次删除：原输入路径 + 确认的目标。 */
    public record Confirmed(String origPath, String target) { }

    private final AtomicReference<Confirmed> slot = new AtomicReference<>();

    public static Deletion of(Workspace ws) { return ws.component(Deletion.class, Deletion::new); }

    /** 拼出碰盘用的完整路径（相对路径按根拼，中间的 .. 照拼，在不在根下是 pathContains 的事）。 */
    public static String fullPath(String root, String path) {
        return Limit.absolute(path) ? path : root + "/" + path;
    }

    /** 分级。永远不抛：出了任何事都回落到 UNRESOLVABLE（保守那一边）。 */
    public static Risk risk(Workspace ws, String path) {
        try {
            return classify(ws.root().toString(), path);
        } catch (RuntimeException e) {
            return new Risk(Kind.UNRESOLVABLE, path, null);
        }
    }

    private static Risk classify(String root, String path) {
        String full = fullPath(root, path);
        Path fp = Path.of(full);
        // 末段是软链必须先问：exists 跟着链接走，一条指向根外目录的链接在那边只是"一个存在的目录"
        if (Files.isSymbolicLink(fp)) {
            String real = Limit.realpath(fp);
            return real != null ? new Risk(Kind.SYMLINK, full, real) : new Risk(Kind.UNRESOLVABLE, full, null);
        }
        if (!Limit.pathContains(root, path)) {
            if (Files.exists(fp)) {
                String real = Limit.realpath(fp);
                return real != null ? new Risk(Kind.ESCAPE, full, real) : new Risk(Kind.UNRESOLVABLE, full, null);
            }
            return new Risk(Kind.ESCAPE, path, null);
        }
        if (Files.exists(fp)) {
            String real = Limit.realpath(fp);
            Path rp = Path.of(root);
            String realRoot = Files.exists(rp) ? Limit.realpath(rp) : root;
            if (real == null) return new Risk(Kind.UNRESOLVABLE, full, null);
            if (realRoot != null && !Limit.pathContains(realRoot, real)) return new Risk(Kind.ESCAPE, full, real);
            return null;
        }
        // 悬空链会让 exists 回 false，上面末段软链那支已经接住；剩下的是还没长出来的路径
        return null;
    }

    public void confirm(String origPath, String target) { slot.set(new Confirmed(origPath, target)); }

    public static void confirm(Workspace ws, String origPath, String target) { of(ws).confirm(origPath, target); }

    /** 取一次就没了（不自动清空的话，下一次删除会拿着上一次的目标放行）。 */
    public Confirmed take() { return slot.getAndSet(null); }

    public void clear() { slot.set(null); }
}
