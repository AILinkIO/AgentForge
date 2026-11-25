package io.ailink.agentforge.agent.route;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务类型：这类活对模型有什么要求（对应 coxswain {@code task.ss}）。
 *
 * <p>一个类型带两样性质完全不同的东西：
 * <ol>
 *   <li>**硬门槛**（会不会调工具、窗口、推理、看图）—— "能不能做"，不满足时**必须换**（哪怕更贵）；</li>
 *   <li>**档位** —— "要不要省钱"，只有 {@link Tier#CHEAP} 会往便宜里换。</li>
 * </ol>
 *
 * <p>⚠️ 名字与内置 agent **同名**（用户定的）：路由表上的一行就是那条 agent 派活时用的模型。没有 {@code standard}：
 * 认不出 / 没说的一律按 {@link #SENIOR}（用主模型、不降级 —— 一个拼错的单词不该决定花多少钱）。
 *
 * <p>⚠️ {@link #doc()} 进的是 {@code task} 工具的参数说明，也就是**模型读得到的那份**，措辞要照实（比如看图那条
 * 不提 PDF：两家的 PDF 各走另一种块，附件层是拒收的）。
 */
public enum TaskKind {
    JUNIOR(Tier.CHEAP, null, false, false,
            "轻活：翻找、罗列、核对，照着做就行的活。可以换一条更便宜的模型"),
    // ⚠️ 探索**不降级**（用户定的）：每一步都在决定下一步翻哪儿，翻错方向整趟白跑 —— 那正是弱模型最容易出问题的地方。
    //    门槛压在窗口上：20 万约等于"读得下几十个文件加一轮搜索结果"，低于它会在半路被压缩吃掉前面读到的东西。
    DEEP(Tier.MAIN, 200_000L, false, false,
            "探索：在仓库里大范围翻找、逐层下钻（deepagent 那种活）。要大窗口，默认用主模型"),
    // ⚠️ 看图是硬门槛：看不了图的模型不是"做得差一点"，是一个字都答不上来。
    VISION(Tier.MAIN, null, false, true,
            "看图：读出图片/截图里的内容，或看着界面截图判断排版、配色、有没有错位"),
    // ⚠️ 默认就是主模型；门槛加"会推理"：用户把它钉到不会推理的模型上时拦下来，主模型自己不会推理时走能力兜底。
    SENIOR(Tier.MAIN, null, true, false,
            "重活：跨多处推断、要判断取舍。默认用主模型，不降级。没说是哪类的都算这个");

    /** CHEAP = 可以往便宜里换；MAIN = 用主模型。留成枚举而不是布尔：可以降到多低是个还会长的问题。 */
    public enum Tier { CHEAP, MAIN }

    /** 认不出 / 没说 → 落到哪一档。方向必须是"用主模型、不降级"。 */
    public static final TaskKind DEFAULT = SENIOR;

    private final Tier tier;
    private final Long minContext;
    private final boolean needReasoning;
    private final boolean needAttachment;
    private final String doc;

    TaskKind(Tier tier, Long minContext, boolean needReasoning, boolean needAttachment, String doc) {
        this.tier = tier;
        this.minContext = minContext;
        this.needReasoning = needReasoning;
        this.needAttachment = needAttachment;
        this.doc = doc;
    }

    /** 小写名（{@code "junior"}），模型与配置里写的就是它。 */
    public String id() { return name().toLowerCase(); }

    public Tier tier() { return tier; }

    /** 窗口门槛；null = 不限。 */
    public Long minContext() { return minContext; }

    public boolean needReasoning() { return needReasoning; }

    public boolean needAttachment() { return needAttachment; }

    public String doc() { return doc; }

    /** 模型交上来的字符串 → 类型；**认不出一律 {@link #DEFAULT}**。 */
    public static TaskKind parse(String s) {
        if (s == null) return DEFAULT;
        for (TaskKind k : values()) if (k.id().equals(s.trim().toLowerCase())) return k;
        return DEFAULT;
    }

    /** 严格版：认不出 → null（配置校验用，拼错要当场报）。 */
    public static TaskKind parseStrict(String s) {
        if (s == null) return null;
        for (TaskKind k : values()) if (k.id().equals(s.trim().toLowerCase())) return k;
        return null;
    }

    /** 给工具 schema 那段参数说明：从表生成，不另写一份（两份一定会漂，漂的方向是模型读到的那份变旧）。 */
    public static String descriptions() {
        List<String> parts = new ArrayList<>();
        for (TaskKind k : values()) parts.add(k.id() + "＝" + k.doc);
        return String.join("；", parts);
    }

    public static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (TaskKind k : values()) out.add(k.id());
        return out;
    }
}
