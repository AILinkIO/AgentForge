package io.ailink.agentforge.agent.tools;

import io.ailink.agentforge.agent.tool.Workspace;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型读过什么：完整路径 → 读到那一刻的内容 TAG（对应 coxswain {@code tools/seen.ss}）。
 *
 * <p>为什么判据是我们自己记的表、而不是模型回传的 tag：tag 由模型保管时，它会拿一份过期的印象去改，
 * 把别人的改动覆盖掉。三件写文件的工具（write / edit / replace-form，加 apply-patch）共用 {@link #check}。
 *
 * <p>登记点：凡是把 {@code [路径#TAG]} 交给模型的读（read-file / list-forms / read-form / 读图），以及写成功之后。
 * 换项目时宿主调 {@link #reset()}；子 agent 用自己的一张（往子工作区里 put 一个新的）。
 */
public final class Seen {
    private final Map<String, String> table = new ConcurrentHashMap<>();

    public static Seen of(Workspace ws) { return ws.component(Seen.class, Seen::new); }

    public void note(String path, String text) { table.put(path, Tags.contentTag(text)); }

    public String tag(String path) { return table.get(path); }

    public void forget(String path) { table.remove(path); }

    public void reset() { table.clear(); }

    public int size() { return table.size(); }

    /**
     * 这次写得不得：null = 可以；否则是一句给模型的话（要把下一步说出来）。
     *
     * @param shown   模型给的路径（报错用）
     * @param claimed 模型自述的 TAG，可空
     */
    public String check(String shown, String path, String text, String claimed) {
        String now = Tags.contentTag(text);
        String saw = tag(path);
        if (saw == null) {
            return ToolText.q(shown) + " 你还没有读过 —— 先 read-file 看一眼再改。（改一个没看过的文件，等于对着一份猜出来的内容动手；"
                    + "这条不是给你添麻烦，是上一次它真的把别人的改动覆盖掉了。）";
        }
        if (!Tags.matches(saw, now)) {
            return ToolText.q(shown) + " 在你读完之后变了（现在是 " + now + "，你上次读到的是 " + saw + "）。先 read-file 重新读一遍，再照新内容改";
        }
        if (claimed != null && !claimed.isEmpty() && !Tags.matches(claimed, now)) {
            return "你给的 TAG 是 " + Tags.normalize(claimed) + "，而 " + ToolText.q(shown) + " 现在是 " + now + "。先 read-file 重新读一遍再改";
        }
        return null;
    }
}
