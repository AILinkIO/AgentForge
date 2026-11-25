package io.ailink.agentforge.agent.session;

import io.ailink.agentforge.agent.context.Compaction;
import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.prompt.Prompts;

import java.util.ArrayList;
import java.util.List;

/**
 * 图在历史里留多久（coxswain {@code session/images.ss}）。
 *
 * <p>一张图约 1600 token，留在历史里就**每一轮重发一次**。默认（TURN）只随那一轮发一次，落地后摘掉、换成一句说明 ——
 * 说明要写清**为什么**看不到了，否则模型会以为出了错去重试或道歉。PRUNE 档留到 prune 边界再摘。
 * ⚠️ PDF 块与图同等对待（同样贵）。
 */
final class Images {
    private Images() { }

    static boolean media(Block b) { return b instanceof Block.Image || b instanceof Block.Document; }

    static boolean hasMedia(List<Block> bs) {
        for (Block b : bs) {
            if (media(b)) return true;
            if (b instanceof Block.ToolResult r && hasMedia(r.content())) return true;
        }
        return false;
    }

    static boolean hasMedia(Message m) { return hasMedia(m.blocks()); }

    static Message drop(Message m, boolean pruned) {
        if (!hasMedia(m)) return m;
        List<Block> out = new ArrayList<>();
        boolean droppedHere = false;
        for (Block b : m.blocks()) {
            if (media(b)) { droppedHere = true; continue; }
            if (b instanceof Block.ToolResult r && hasMedia(r.content())) {
                List<Block> c = new ArrayList<>();
                for (Block x : r.content()) if (!media(x)) c.add(x);
                c.add(new Block.Text(pruned ? Prompts.TOOL_IMAGE_GONE_NOTE_PRUNED : Prompts.TOOL_IMAGE_GONE_NOTE));
                out.add(new Block.ToolResult(r.toolUseId(), c, r.error()));
                continue;
            }
            out.add(b);
        }
        if (droppedHere) out.add(new Block.Text(pruned ? Prompts.IMAGES_GONE_NOTE_PRUNED : Prompts.IMAGES_GONE_NOTE));
        return m.withBlocks(out);
    }

    static List<Message> dropAll(List<Message> ms, boolean pruned) {
        List<Message> out = new ArrayList<>(ms.size());
        for (Message m : ms) out.add(drop(m, pruned));
        return out;
    }

    /** 下标 0..b（含）的消息摘图；b &lt; 0 不动。 */
    static List<Message> dropThrough(int b, List<Message> ms) {
        if (b < 0) return ms;
        List<Message> out = new ArrayList<>(ms);
        for (int i = 0; i <= b && i < out.size(); i++) out.set(i, drop(out.get(i), true));
        return out;
    }

    static List<Message> forget(List<Message> ms, SessionConfig.ImageRetention r) {
        if (r == SessionConfig.ImageRetention.PRUNE) {
            List<Integer> ts = Compaction.pruneTargets(ms);
            return dropThrough(ts.isEmpty() ? -1 : ts.getLast(), ms);
        }
        return dropAll(ms, false);
    }

    /** 估 base64 解出来多少字节（画廊显示大小用）。 */
    static long bytes(String b64) {
        int n = b64.length();
        int pad = n >= 2 && b64.charAt(n - 1) == '=' ? (b64.charAt(n - 2) == '=' ? 2 : 1) : 0;
        return (long) n * 3 / 4 - pad;
    }
}
