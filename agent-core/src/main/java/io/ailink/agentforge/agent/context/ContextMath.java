package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;
import io.ailink.agentforge.agent.model.Role;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史的字符 / token 估算与分组（coxswain {@code context.ss} 的 history-groups / message-chars / trim-history）。
 *
 * <p>⚠️ 估算只为"该不该裁 / 压"：token 按字符数 × 系数估（默认 0.5，按上一次真实 input 校准）。
 * ⚠️ 分组是不变量：一条带 tool_use 的 assistant 与紧跟的 tool_result 消息是**一组**，裁剪只能整组丢 ——
 * 拆开就是一条孤儿 tool_result，服务端 400。
 */
public final class ContextMath {
    private ContextMath() { }

    public static final double DEFAULT_TOKENS_PER_CHAR = 0.5;
    public static final int IMAGE_CHARS = 6000;
    public static final int DOCUMENT_CHARS = 12000;
    /** PDF 按页估：一页约这么多字符。 */
    public static final int CHARS_PER_PAGE = 4000;

    /** 人说的话：user 角色、且不是工具结果消息（meta {@code human} 为真时也算）。 */
    public static boolean human(Message m) {
        return m.role() == Role.USER && (Boolean.TRUE.equals(m.meta("human")) || !m.hasToolResults());
    }

    public static long messageChars(Message m) {
        long n = 8;   // role 与结构本身的开销，粗算
        for (Block b : m.blocks()) n += blockChars(b);
        return n;
    }

    public static long blockChars(Block b) {
        return switch (b) {
            case Block.Text t -> t.text().length();
            case Block.Thinking th -> th.text().length() + (th.signature() == null ? 0 : th.signature().length());
            case Block.ToolUse u -> u.name().length() + datumChars(u.input());
            case Block.ToolResult r -> { long s = 0; for (Block c : r.content()) s += blockChars(c); yield s; }
            case Block.Image i -> IMAGE_CHARS;
            case Block.Document d -> documentChars(d);
        };
    }

    /** PDF 数 {@code /Type /Page} 对象估页数；数不出来按固定量。 */
    public static long documentChars(Block.Document d) {
        if (!"application/pdf".equals(d.mediaType()) || d.data() == null) return DOCUMENT_CHARS;
        try {
            byte[] bv = java.util.Base64.getDecoder().decode(d.data());
            int pages = countPages(bv);
            return pages > 0 ? (long) pages * CHARS_PER_PAGE : DOCUMENT_CHARS;
        } catch (RuntimeException e) {
            return DOCUMENT_CHARS;
        }
    }

    private static int countPages(byte[] bv) {
        int k = 0;
        byte[] type = "/Type".getBytes(), page = "/Page".getBytes();
        for (int i = 0; i + 5 <= bv.length; i++) {
            if (!at(bv, i, type)) continue;
            int j = i + 5;
            while (j < bv.length && (bv[j] == ' ' || bv[j] == '\t' || bv[j] == '\n' || bv[j] == '\r')) j++;
            if (at(bv, j, page)) {
                int e = j + 5;
                if (e >= bv.length || !Character.isLetter(bv[e])) { k++; i = j + 4; }
            }
        }
        return k;
    }

    private static boolean at(byte[] bv, int i, byte[] pat) {
        if (i + pat.length > bv.length) return false;
        for (int j = 0; j < pat.length; j++) if (bv[i + j] != pat[j]) return false;
        return true;
    }

    private static long datumChars(JsonNode n) {
        if (n == null) return 0;
        if (n.isString()) return n.asString().length();
        if (n.isObject()) {
            long s = 0;
            for (var e : n.properties()) s += e.getKey().length() + datumChars(e.getValue());
            return s;
        }
        if (n.isArray()) { long s = 0; for (JsonNode x : n) s += datumChars(x); return s; }
        return 4;
    }

    public static long historyChars(List<Message> h) {
        long n = 0;
        for (Message m : h) n += messageChars(m);
        return n;
    }

    public static long tokens(Message m) { return (long) Math.floor(DEFAULT_TOKENS_PER_CHAR * messageChars(m)); }

    /** 工具结果的字符数（prune 只算这一部分）。 */
    public static long toolResultChars(Message m) {
        long n = 0;
        for (Block b : m.blocks()) if (b instanceof Block.ToolResult) n += blockChars(b);
        return n;
    }

    /** 分组：tool_use + 紧跟的 tool_result 一组，其余一条一组。 */
    public static List<List<Message>> groups(List<Message> history) {
        List<List<Message>> out = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            if (!m.toolUses().isEmpty() && i + 1 < history.size() && history.get(i + 1).hasToolResults()) {
                out.add(List.of(m, history.get(i + 1)));
                i++;
            } else out.add(List.of(m));
        }
        return out;
    }

    /** 裁剪结果：发出去的那份 + 丢了几条。 */
    public record Trimmed(List<Message> kept, int dropped) { }

    /**
     * 按 token 预算从尾往前留整组，保住开头 {@code keepHead} 组（第一句话往往是任务本身）。最后一组单独就超预算时
     * 也留着它（宁可超一点，不发一个空的尾巴）。
     */
    public static Trimmed trim(List<Message> history, Long maxTokens, double tpc, int keepHead) {
        if (maxTokens == null || tpc * historyChars(history) <= maxTokens) return new Trimmed(history, 0);
        List<List<Message>> groups = groups(history);
        int n = groups.size();
        List<List<Message>> head = keepHead < n ? groups.subList(0, keepHead) : groups;
        List<List<Message>> rest = keepHead < n ? groups.subList(keepHead, n) : List.of();
        double budget = maxTokens - tpc * historyChars(flat(head));
        List<List<Message>> acc = new ArrayList<>();
        double used = 0;
        for (int i = rest.size() - 1; i >= 0; i--) {
            double t = tpc * historyChars(rest.get(i));
            if (used + t > budget) {
                if (acc.isEmpty()) acc.add(0, rest.get(i));
                break;
            }
            acc.add(0, rest.get(i));
            used += t;
        }
        List<Message> kept = new ArrayList<>(flat(head));
        kept.addAll(flat(acc));
        return new Trimmed(kept, history.size() - kept.size());
    }

    static List<Message> flat(List<List<Message>> gs) {
        List<Message> out = new ArrayList<>();
        for (List<Message> g : gs) out.addAll(g);
        return out;
    }

    /** 工具结果的纯文本（媒体块不算）。 */
    public static String toolResultText(List<Block> content) { return Block.joinText(content); }
}
