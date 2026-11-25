package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;

/**
 * 系统提醒块（coxswain {@code reminder.ss}）：包在 {@code <system-reminder>} 里的文本块 —— 发给模型，但不算
 * "用户说的话"：记录投影、全文检索、压缩时摊平对话都跳过它（压缩回灌的文件正文就是这一类）。
 */
public final class Reminder {
    private Reminder() { }

    static final String OPEN = "<system-reminder>\n";
    static final String CLOSE = "\n</system-reminder>";

    public static Block.Text block(String text) { return new Block.Text(OPEN + text + CLOSE); }

    public static boolean isReminder(Block b) { return b instanceof Block.Text t && t.text().startsWith(OPEN); }

    /** 消息里给人看的那部分文本：文本块拼起来，跳过提醒块。 */
    public static String shownText(Message m) {
        StringBuilder sb = new StringBuilder();
        for (Block b : m.blocks()) if (b instanceof Block.Text t && !isReminder(b)) sb.append(t.text());
        return sb.toString();
    }
}
