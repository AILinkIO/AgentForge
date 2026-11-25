package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.agent.cost.Cost;
import io.ailink.agentforge.agent.model.Usage;
import io.ailink.agentforge.tui.Seg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用量读数（底栏右边；coxswain {@code designs/agent-usage-badge.md}，上下文那段照 Codex 写成「还剩几成」）：
 * {@code 53% 上下文剩余  I/O 103.4k/4.1k  C 38%  $ ~0.0341}。
 * <ul>
 *   <li>上下文：最近一次请求（含缓存读回的）占窗口的几成，写成还剩多少；用到 80% 起警告色、92% 起错误色；不知道窗口 / 没发过 ⇒ 不画；</li>
 *   <li>{@code I/O}：这段对话累计的输入 / 输出（一次都没发过 ⇒ 整段不画）；</li>
 *   <li>{@code C}：累计输入里从缓存读回的比例（Anthropic 口径：读 / (读 + 写 + 未命中)），永远成功色；没命中 ⇒ 不画；</li>
 *   <li>{@code $}：估算（{@code ~}），末尾 {@code +} = 至少这么多（有一笔没有单价）；没单价 ⇒ 不画。</li>
 * </ul>
 * 窄了逐级丢：先 C，再 I/O，再 $，M 最后。纯函数。
 */
final class UsageReadout {
    private UsageReadout() { }

    record Parts(Seg io, Seg m, Seg c, Seg cost) {
        List<Seg> fit(int width) {
            List<List<Seg>> tries = new ArrayList<>();
            tries.add(join(m, io, c, cost));
            tries.add(join(m, io, null, cost));
            tries.add(join(m, null, null, cost));
            tries.add(join(m, null, null, null));
            for (List<Seg> t : tries) if (Seg.width(t) <= width) return t;
            return List.of();
        }

        private static List<Seg> join(Seg... ss) {
            List<Seg> out = new ArrayList<>();
            for (Seg s : ss) {
                if (s == null) continue;
                if (!out.isEmpty()) out.add(Seg.of(" · "));
                out.add(s);
            }
            return out;
        }
    }

    static Parts parts(Usage total, Long lastInput, Double contextRatio, Double cost, boolean partial) {
        Seg io = null, m = null, c = null, $ = null;
        if (total != null && !total.isZero()) io = Seg.of("I/O " + human(total.totalInput()) + "/" + human(total.output()));
        if (lastInput != null && contextRatio != null) {
            long pct = Math.round(contextRatio * 100);
            String face = contextRatio >= 0.92 ? "error" : contextRatio >= 0.8 ? "warning" : null;
            m = Seg.of(Msg.f("%d%% context left", Math.max(0, 100 - pct)), face);
        }
        if (total != null && total.cacheRead() > 0 && total.totalInput() > 0) {
            long pct = Math.round(100.0 * total.cacheRead() / total.totalInput());
            c = Seg.of("C " + pct + "%", "success");
        }
        String f = Cost.format(cost);
        if (f != null) $ = Seg.of("$ " + f.replace("≈$", "~").replace("<$", "<") + (partial ? "+" : ""));
        return new Parts(io, m, c, $);
    }

    static String human(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        if (n >= 1000) return String.format(Locale.ROOT, "%.1fk", n / 1e3);
        return Long.toString(n);
    }
}
