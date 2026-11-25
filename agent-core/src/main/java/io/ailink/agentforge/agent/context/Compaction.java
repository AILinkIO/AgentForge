package io.ailink.agentforge.agent.context;

import io.ailink.agentforge.agent.model.Block;
import io.ailink.agentforge.agent.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩的纯函数部分（coxswain {@code session/context.ss}）：指针重建、prune、切分点、摊平。
 *
 * <h2>压缩只追加，不重写</h2>
 * 压一次 = 在历史末尾追加两条（"系统压缩了上面的对话"通告 + 摘要），再记一条指针 {@code (at, tail)}：at 是那两条的
 * 下标，tail 是保留的尾巴从哪条开始。发请求时按**最后一条**指针重建：{@code h[at..at+2) + h[tail..at) + h[at+2..]}。
 *
 * <p>⚠️ 为什么不重写历史：存盘的契约是"只追加"（{@code SessionStore#sync} 在历史变短时报错）—— 重写过的历史长回去之后
 * 会把新消息接在压缩前的前缀上，库里那段对话**静默变成拼接品**。只增不减，下标就稳定，指针存两个整数就够。
 */
public final class Compaction {
    private Compaction() { }

    /** 一条指针。 */
    public record Pointer(int at, int tail) { }

    /** 最近这么多 token 的工具输出保住不动。 */
    public static final int PRUNE_PROTECT = 40000;
    /** 省不出这么多就不动手（省得白改一遍前缀、毁掉 prompt cache）。 */
    public static final int PRUNE_MINIMUM = 20000;
    /** 按这个步长量化（边界每 2 万 token 才挪一次，前缀在两次之间保持不变）。 */
    public static final int PRUNE_STEP = 20000;
    public static final String PRUNE_PLACEHOLDER = "[旧的工具结果已清理]";
    /** 压缩时保留最近几轮。 */
    public static final int KEEP_TURNS = 2;
    public static final int PRESERVE_MIN = 2000;
    public static final int PRESERVE_MAX = 8000;
    /** 头太小不值得压（压出来的摘要不比原文短）。 */
    public static final int MIN_HEAD = 1000;
    public static final int TOOL_OUTPUT_MAX = 2000;
    /** 上下文满了之后最多自动压几次接着跑。 */
    public static final int RESUMES = 3;

    // ── 指针 ─────────────────────────────────────────────────────────────

    public static List<Message> apply(List<Message> h, List<Pointer> cs) {
        return applyIndexed(h, cs).messages();
    }

    /** 重建结果 + 每条在原历史里的下标（压缩时要把"尾巴从哪条开始"换算回原历史）。 */
    public record Indexed(List<Message> messages, List<Integer> index) { }

    public static Indexed applyIndexed(List<Message> h, List<Pointer> cs) {
        int n = h.size();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < n; i++) idx.add(i);
        if (cs == null || cs.isEmpty()) return new Indexed(h, idx);
        Pointer p = cs.getLast();
        int at = p.at(), tail = p.tail();
        if (!(tail >= 0 && tail <= at && at + 2 <= n)) return new Indexed(h, idx);
        List<Message> out = new ArrayList<>(h.subList(at, at + 2));
        out.addAll(h.subList(tail, at));
        out.addAll(h.subList(at + 2, n));
        List<Integer> oi = new ArrayList<>(idx.subList(at, at + 2));
        oi.addAll(idx.subList(tail, at));
        oi.addAll(idx.subList(at + 2, n));
        return new Indexed(out, oi);
    }

    // ── prune：清老工具结果 ──────────────────────────────────────────────

    /**
     * 要清哪几条（下标，升序）：只看最后一句人话**之前**的；从头往后数工具输出的 token，保住最近 {@link #PRUNE_PROTECT}，
     * 超出部分按 {@link #PRUNE_STEP} 量化，省不到 {@link #PRUNE_MINIMUM} 就不清。
     */
    public static List<Integer> pruneTargets(List<Message> h) {
        int n = h.size();
        int cur = 0;
        for (int k = n - 1; k >= 0; k--) if (ContextMath.human(h.get(k))) { cur = k; break; }
        int limit = cur > 0 ? cur : n;
        long[] toks = new long[limit];
        long total = 0;
        for (int k = 0; k < limit; k++) {
            long c = ContextMath.toolResultChars(h.get(k));
            toks[k] = c == 0 ? 0 : (long) Math.floor(ContextMath.DEFAULT_TOKENS_PER_CHAR * c);
            total += toks[k];
        }
        long over = total - PRUNE_PROTECT;
        long q = over <= 0 ? 0 : PRUNE_STEP * (over / PRUNE_STEP);
        List<Integer> acc = new ArrayList<>();
        long c = 0, saved = 0;
        for (int k = 0; k < limit; k++) {
            if (c + toks[k] > q) break;
            c += toks[k];
            saved += toks[k];
            if (toks[k] > 0) acc.add(k);
        }
        return saved >= PRUNE_MINIMUM ? acc : List.of();
    }

    public static Message pruneMessage(Message m) {
        List<Block> bs = new ArrayList<>();
        for (Block b : m.blocks()) {
            if (b instanceof Block.ToolResult r) bs.add(Block.ToolResult.text(r.toolUseId(), PRUNE_PLACEHOLDER, r.error()));
            else bs.add(b);
        }
        return m.withBlocks(bs);
    }

    /** prune 之后的那份；{@code dropImagesThrough} 收"清到哪条为止"，顺手把那以前的图摘掉（prune 档的图保留规则）。 */
    public static List<Message> prune(List<Message> h, java.util.function.BiFunction<Integer, List<Message>, List<Message>> dropImagesThrough) {
        List<Integer> ts = pruneTargets(h);
        if (ts.isEmpty()) return h;
        List<Message> out = new ArrayList<>(h);
        for (int i : ts) out.set(i, pruneMessage(out.get(i)));
        return dropImagesThrough == null ? out : dropImagesThrough.apply(ts.getLast(), out);
    }

    // ── 切分 ─────────────────────────────────────────────────────────────

    /** 头（要压的）+ 尾（原样留的）。 */
    public record Split(List<Message> head, List<Message> tail) { }

    /** 保留尾巴的 token 预算：可用输入的四分之一，夹在 [2000, 8000]。 */
    public static long preserveBudget(Long inputBudget) {
        if (inputBudget == null) return PRESERVE_MIN;
        return Math.min(PRESERVE_MAX, Math.max(PRESERVE_MIN, inputBudget / 4));
    }

    /**
     * 从尾往前攒，攒到预算为止；最近 {@link #KEEP_TURNS} 轮必留（切点不早于它们的开头）。切点只能落在**不含工具结果**
     * 的消息上（否则尾巴第一条是孤儿 tool_result）。头不到 {@link #MIN_HEAD} token → 不值得压（null）。
     */
    public static Split split(List<Message> h, long budget) {
        int n = h.size();
        if (n <= 1 || budget <= 0) return null;
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < n; i++) if (ContextMath.human(h.get(i))) starts.add(i);
        int floorK = starts.size() > KEEP_TURNS ? starts.get(starts.size() - KEEP_TURNS) : 0;
        long used = 0;
        Integer best = null;
        for (int k = n - 1; k >= Math.max(floorK, 1); k--) {
            used += ContextMath.tokens(h.get(k));
            if (used > budget) break;
            if (!h.get(k).hasToolResults()) best = k;
        }
        if (best == null || best <= 0) return null;
        List<Message> head = h.subList(0, best);
        long ht = 0;
        for (Message m : head) ht += ContextMath.tokens(m);
        if (ht < MIN_HEAD) return null;
        return new Split(List.copyOf(head), List.copyOf(h.subList(best, n)));
    }

    // ── 摊平（老路那一问用）─────────────────────────────────────────────

    public static String serialize(List<Message> h) {
        List<String> lines = new ArrayList<>();
        for (Message m : h) {
            String line;
            if (ContextMath.human(m)) line = "[用户]：" + Reminder.shownText(m);
            else if (m.hasToolResults()) {
                List<String> rs = new ArrayList<>();
                for (Block.ToolResult r : m.toolResults()) {
                    rs.add("[工具结果" + (r.error() ? "（报错）" : "") + "]：" + clip(Block.joinText(r.content()), TOOL_OUTPUT_MAX));
                }
                line = String.join("\n", rs);
            } else if (m.isAssistant()) {
                List<String> parts = new ArrayList<>();
                if (!m.text().isEmpty()) parts.add("[助手]：" + m.text());
                for (Block.ToolUse u : m.toolUses()) parts.add("[助手调用工具]：" + u.name());
                line = String.join("\n", parts);
            } else line = "";
            if (!line.isEmpty()) lines.add(line);
        }
        return String.join("\n", lines);
    }

    public static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "\n[以下截断]" : s;
    }

    /** 原地压缩那一问的锚：尾巴第一条的开头 60 个字（模型数不了"第几条消息"）。 */
    public static String tailAnchor(List<Message> tail) {
        if (tail == null || tail.isEmpty()) return null;
        Message m = tail.getFirst();
        String t = Reminder.shownText(m);
        if (t.isEmpty() && !m.toolUses().isEmpty()) t = "调用 " + m.toolUses().getFirst().name();
        t = t.replace('\n', ' ').replace('\r', ' ');
        if (t.isEmpty()) return null;
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
