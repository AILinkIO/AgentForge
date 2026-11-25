package io.ailink.agentforge.agent.route;

import io.ailink.agentforge.agent.cost.Candidate;
import io.ailink.agentforge.agent.cost.Cost;
import io.ailink.agentforge.agent.models.ModelFacts;
import io.ailink.agentforge.agent.models.ModelMeta;
import io.ailink.agentforge.agent.models.Models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模型路由表：哪类活派给哪条模型（对应 coxswain {@code route.ss}；论证在 designs/subagent-model-routing.md）。
 *
 * <h2>业务逻辑（用户定的四条）</h2>
 * <ol>
 *   <li>开关**默认关**：关着时省钱那条路一步都不走 —— 但**能力兜底照走**（主模型根本做不了的活：看不了图、
 *       窗口不够、不会推理，换到能做的里面最便宜的一条并说出来）；</li>
 *   <li>打开之后按任务类型 + 成本填一张全局路由表；</li>
 *   <li>定不下来就用主模型；</li>
 *   <li>每一类都要有一行，能回答"有没有哪类没着落"（{@link #problems()}）。</li>
 * </ol>
 *
 * <h2>两条路径，性质完全不同</h2>
 * **省钱路由**：主模型能做、有更便宜的也能做 → 换；⚠️ **只往便宜走，绝不往贵走**（严格小于）。
 * **能力兜底**：主模型做不了 → 换到能做的里面最便宜的，哪怕更贵（那时没有"不换"这个选项）。
 * 兜底只在**硬门槛不满足**时触发，判据是目录里查得到的事实，不是"那条更聪明"的印象。
 *
 * <p>⚠️ 表会过期：池子变了（换主模型、加 provider、存 key、刷目录、改黑名单）就得重建 —— 宿主在那几个点上显式调
 * {@link #build}，这一层不认识"什么时候变了"。
 */
public final class Router {

    /** 查表结果。 */
    public record Decision(String model, String why) { }

    private volatile boolean enabled;
    private final Map<TaskKind, String> pins = Collections.synchronizedMap(new EnumMap<>(TaskKind.class));
    private volatile List<RouteEntry> table = List.of();

    /** 省钱路由开没开（默认关；打开是用户的一个动作）。 */
    public boolean enabled() { return enabled; }

    public void enabled(boolean on) { this.enabled = on; }

    // ─── 用户为某类活钉死的模型 ──────────────────────────────────────────

    /**
     * 某类活钉死用哪条。⚠️ **可以比主模型贵**（用户自己写下的决定，不需要我们替他省钱），但**仍然要过硬门槛**
     * （把看图的活派给看不了图的模型，结果是一个莫名其妙的空回答 —— 拦下来并说清楚）。钉的那条属于路由这个功能，
     * 开关关着时不看。
     */
    public void pin(TaskKind kind, String ref) {
        Objects.requireNonNull(kind, "kind");
        if (ref == null || ref.isEmpty()) throw new IllegalArgumentException("模型要写成非空字符串（\"provider/模型名\"）");
        pins.put(kind, ref);
    }

    public void unpin(TaskKind kind) { pins.remove(kind); }

    public void unpinAll() { pins.clear(); }

    public String pinned(TaskKind kind) { return pins.get(kind); }

    public Map<TaskKind, String> pins() {
        synchronized (pins) { return Map.copyOf(pins); }
    }

    // ─── 硬门槛：全是过或不过，缺信息一律算**不过**（证明不了够用，就别把活交给它）─────────

    static boolean passes(ModelFacts f, TaskKind k) {
        if (f == null) return false;
        // ⚠️ 会调工具在 Models 那边筛过一遍，这里再筛：这一层不该假设调用方给的池子筛干净了
        if (!f.callsTools()) return false;
        if (k.minContext() != null && (f.context() == null || f.context() < k.minContext())) return false;
        if (k.needReasoning() && !f.reasons()) return false;
        return !k.needAttachment() || f.takesAttachment();
    }

    /** 过了这类活硬门槛的候选（界面用：回答"我那条 XX 怎么没被选上"）。 */
    public static List<Candidate> eligible(TaskKind kind, List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) if (passes(c.facts(), kind)) out.add(c);
        return out;
    }

    // ─── 建表 ────────────────────────────────────────────────────────────

    /**
     * 建表。{@code main == null} → 空表。关着时也建（每行只有"主模型 / 能力兜底"两种来历）：第 4 条要的
     * "哪几类没着落"关着时同样要答得出，而 {@link #model} 只查表。
     */
    public List<RouteEntry> build(Candidate main, List<Candidate> candidates) {
        if (main == null || main.ref() == null) {
            table = List.of();
            return table;
        }
        List<RouteEntry> out = new ArrayList<>();
        for (TaskKind k : TaskKind.values()) {
            out.add(enabled ? decide(k, main, candidates) : decideOff(k, main, candidates));
        }
        table = List.copyOf(out);
        return table;
    }

    /** 从模型表建：主模型 = 当前默认，候选 = 全部可用模型。主模型不可用时空表。 */
    public List<RouteEntry> build(Models models) {
        List<Candidate> pool = candidates(models);
        String def = models.defaultModel();
        Candidate main = null;
        for (Candidate c : pool) if (c.ref().equals(def)) main = c;
        return build(main, pool);
    }

    /** 模型表 → 候选（手写模型没有目录事实时用窗口 / 上限拼一份）。 */
    public static List<Candidate> candidates(Models models) {
        List<Candidate> out = new ArrayList<>();
        for (ModelMeta m : models.modelMetas()) out.add(new Candidate(m.ref().toString(), m.factsOrMinimal()));
        return out;
    }

    public List<RouteEntry> table() { return table; }

    public void clear() { table = List.of(); }

    private RouteEntry decideOff(TaskKind k, Candidate main, List<Candidate> cs) {
        return passes(main.facts(), k)
                ? new RouteEntry(k, main.ref(), "路由没开，用主模型", false)
                : fallback(k, cs, main.ref());
    }

    private RouteEntry decide(TaskKind k, Candidate main, List<Candidate> cs) {
        String pinned = pins.get(k);
        if (pinned != null) return pinned(k, pinned, cs, main.ref());
        if (!passes(main.facts(), k)) return fallback(k, cs, main.ref());
        // ⚠️ 只有 cheap 档会换：重任务 / deepagent 默认就是主模型
        if (k.tier() == TaskKind.Tier.CHEAP) return cheaper(k, cs, main);
        return new RouteEntry(k, main.ref(), "默认用主模型", false);
    }

    private RouteEntry pinned(TaskKind k, String pinned, List<Candidate> cs, String mainRef) {
        Candidate hit = null;
        for (Candidate c : cs) if (c.ref().equals(pinned)) { hit = c; break; }
        // ⚠️ 钉的那条不在池子里也要说 —— 静默回落的话用户会一直以为他那行配置生效了
        if (hit == null)
            return new RouteEntry(k, mainRef, "配置钉的 \"" + pinned + "\" 用不了（拼错了？没 key？被屏蔽了？）→ 主模型", false);
        if (!passes(hit.facts(), k))
            return new RouteEntry(k, mainRef, "配置钉的 \"" + pinned + "\" 干不了这类活（要" + why(k) + "）→ 主模型", false);
        return new RouteEntry(k, pinned, "配置指定", false);
    }

    // ⚠️ 兜底也挑**能做的里面最便宜的** —— 破的是"不往贵走"，不是"随便挑"
    private RouteEntry fallback(TaskKind k, List<Candidate> cs, String mainRef) {
        Cost.Ranking r = Cost.rankByCost(Cost.standardProfile(), without(mainRef, eligible(k, cs)));
        if (!r.ranked().isEmpty())
            return new RouteEntry(k, r.ranked().get(0).ref(), "主模型干不了（要" + why(k) + "）→ 能做的里面最便宜的", true);
        // ⚠️ 有能做的但没单价：照样用 —— 这条路上"不换"= 做不成，而"不知道多少钱"远好过"做不成"
        if (!r.unknown().isEmpty())
            return new RouteEntry(k, r.unknown().get(0), "主模型干不了（要" + why(k) + "）→ 这条能做（目录没给它单价）", true);
        // ⚠️ 第 4 条的缺口：仍用主模型，但 why 里带着 ⚠️，problems() 据此挑出来 —— 不是静默地用主模型
        return new RouteEntry(k, mainRef, "⚠️ 没有能干这活的模型（要" + why(k) + "）—— 仍用主模型，很可能做不成", false);
    }

    private RouteEntry cheaper(TaskKind k, List<Candidate> cs, Candidate main) {
        Double mainPrice = Cost.modelPrice(main.facts());
        // ⚠️ 主模型没单价 → 不换："证明不了更便宜"与"更便宜"是两件事
        if (mainPrice == null) return new RouteEntry(k, main.ref(), "主模型没有单价，不换", false);
        List<Candidate> ok = new ArrayList<>();
        for (Candidate c : without(main.ref(), eligible(k, cs))) {
            Double p = Cost.modelPrice(c.facts());
            // ⚠️ 严格小于：等价的模型换过去一分钱都不省，却换来了另一种脾气
            if (p != null && p < mainPrice) ok.add(c);
        }
        Cost.Ranking r = Cost.rankByCost(Cost.standardProfile(), ok);
        if (r.ranked().isEmpty()) return new RouteEntry(k, main.ref(), "没有更便宜又够用的模型", false);
        Cost.Priced win = r.ranked().get(0);
        return new RouteEntry(k, win.ref(), howMuchCheaper(win.price() / mainPrice), false);
    }

    private static List<Candidate> without(String ref, List<Candidate> cs) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : cs) if (!c.ref().equals(ref)) out.add(c);
        return out;
    }

    // ─── 第 4 条：哪类活没着落 ───────────────────────────────────────────

    /** 一条问题：哪类、为什么。 */
    public record RouteProblem(TaskKind kind, String why) { }

    /**
     * 判据是 why 里那个 ⚠️（"我们知道这条派不出去"）。其余落到主模型的行是**正常结果** —— 都报出来的话这张清单会
     * 长期非空，而长期非空的警告等于没有警告。
     */
    public List<RouteProblem> problems() {
        List<RouteProblem> out = new ArrayList<>();
        for (RouteEntry e : table) if (e.why().contains("⚠️")) out.add(new RouteProblem(e.kind(), e.why()));
        return out;
    }

    // ─── 查表 ────────────────────────────────────────────────────────────

    /**
     * 这类活派给哪条。表里没有（还没建、认不出）→ 主模型。⚠️ 开关关着**不在这里短路**：看图那类要从表里拿到兜底那条。
     */
    public Decision model(TaskKind kind, String mainRef) {
        if (mainRef == null) return new Decision(null, "没有主模型");
        for (RouteEntry e : table) if (e.kind() == kind) return new Decision(e.model(), e.why());
        return new Decision(mainRef, "路由表里没有这一类，用主模型");
    }

    // ─── 话 ──────────────────────────────────────────────────────────────

    // 这类活到底要什么：只说"干不了"等于让用户自己去猜缺哪一样，而那正是他下一步要改的东西
    static String why(TaskKind k) {
        List<String> xs = new ArrayList<>();
        if (k.needAttachment()) xs.add("看得了图");
        if (k.needReasoning()) xs.add("会推理");
        if (k.minContext() != null) xs.add("窗口 ≥ " + k.minContext());
        return xs.isEmpty() ? "会调工具" : String.join("、", xs);
    }

    // 报**成数**不报百分比：估价带着已知偏差，"约两成价钱"经得起，"19.4%" 经不起。一成以下单说"不到一成"
    static String howMuchCheaper(double ratio) {
        if (ratio >= 0.95) return "换成它：价钱差不多";
        if (ratio < 0.1) return "换成它：不到一成价钱";
        return "换成它：约 " + (long) Math.rint(ratio * 10) + " 成价钱";
    }
}
