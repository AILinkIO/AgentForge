package io.ailink.agentforge.agent.registry;

import io.ailink.agentforge.agent.prompt.Prompts;
import io.ailink.agentforge.agent.route.TaskKind;
import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static io.ailink.agentforge.agent.prompt.ToolNames.*;

/**
 * 业务 agent 注册表（对应 coxswain {@code registry.ss}，含 M97 的 agents manager：定义 / 覆盖 / 屏蔽 / 默认）。
 *
 * <p>⚠️ **按注册顺序**，不是哈希表：{@code subagent_type} 的枚举顺序要稳定 —— 模型对枚举顺序敏感（第一个是默认的
 * 那种倾向），每次重排的枚举会让"它今天挑了 junior、明天挑了 deep"变成一个查不出来的现象。
 *
 * <p>⚠️ 这一族的校验都是**当场的**（与模型那边故意相反）：内置 agent 在构造时就装好了，配置跑起来时它们一定在 ——
 * 覆盖 / 屏蔽一个不存在的 agent 当场报，名字打错了立刻知道。
 */
public final class AgentRegistry {

    private static volatile AgentRegistry instance;

    public static AgentRegistry instance() {
        AgentRegistry r = instance;
        if (r == null) {
            synchronized (AgentRegistry.class) {
                if (instance == null) instance = new AgentRegistry();
                r = instance;
            }
        }
        return r;
    }

    public static void setInstance(AgentRegistry r) { instance = r; }

    private final Prompts.Host host;
    private final Map<String, AgentDef> defs = new LinkedHashMap<>();
    private final List<String> blocked = new ArrayList<>();
    private String defaultName;
    private volatile ToolCatalog catalog = ToolCatalog.EMPTY;

    public AgentRegistry() { this(Prompts.Host.CLI); }

    /** @param host 内置 agent 的提示词按哪个宿主说（编辑器宿主多一句"读的是编辑器里那一份"） */
    public AgentRegistry(Prompts.Host host) {
        this.host = Objects.requireNonNull(host);
        installBuiltins();
    }

    /** 宿主装工具目录（**先装再读配置 / 建会话**）。 */
    public void toolCatalog(ToolCatalog c) { this.catalog = c == null ? ToolCatalog.EMPTY : c; }

    public ToolCatalog toolCatalog() { return catalog; }

    // ─── 查表 ────────────────────────────────────────────────────────────

    public synchronized List<AgentDef> defs() { return new ArrayList<>(defs.values()); }

    /** 按名字查；认不出 → null。 */
    public synchronized AgentDef def(String name) { return name == null ? null : defs.get(name); }

    /** 内部用（内置那几条）：同名覆盖，位置不变（覆盖而不是并存：两个同名的在枚举里长得一模一样）。 */
    public synchronized AgentDef put(AgentDef d) {
        Objects.requireNonNull(d);
        defs.put(d.name(), d);
        return d;
    }

    // ─── 工具：预设 + 按名字增删 ─────────────────────────────────────────

    public List<String> toolNames() { return catalog.names(); }

    private AgentTool toolByName(String who, String n) {
        AgentTool t = catalog.byName(n);
        if (t == null) throw new IllegalArgumentException(who + "：认不出这件工具：" + n + "（有这些：" + catalog.names() + "）");
        return t;
    }

    private List<AgentTool> resolveTools(String who, AgentConfig c) {
        List<AgentTool> start;
        if (c.toolNames != null) {
            start = new ArrayList<>();
            for (String n : c.toolNames) start.add(toolByName(who, n));
        } else {
            AgentConfig.ToolPreset p = c.preset != null ? c.preset : AgentConfig.ToolPreset.READ_ONLY;
            start = new ArrayList<>(switch (p) {
                case READ_ONLY -> catalog.readOnly();
                case DEFAULT -> catalog.defaults();
                case NONE -> List.<AgentTool>of();
            });
        }
        // ⚠️ 先减后加：同名时加赢（反过来结果取决于实现顺序，而那是从配置里读不出来的）
        if (c.toolsMinus != null) {
            for (String n : c.toolsMinus) toolByName(who, n);
            start.removeIf(t -> c.toolsMinus.contains(t.name()));
        }
        if (c.toolsPlus != null) {
            for (String n : c.toolsPlus) {
                AgentTool t = toolByName(who, n);
                if (!start.contains(t)) start.add(t);
            }
        }
        return List.copyOf(start);
    }

    /**
     * ⚠️ 子 agent 默认不拿会改东西的工具，**在配置期就拦**（否则一条配错了的会安安静静待在枚举里，直到某次派活当场炸）。
     * 判据是工具自己声明的 {@code sensitive}，不是另抄一份名单（抄的话两处会分岔，方向永远是"少问一次"）。
     * {@code allowSensitive} 是这条闸唯一的口子。
     */
    private static void checkSensitive(String who, String name, List<AgentTool> tools, boolean allow) {
        if (allow) return;
        for (AgentTool t : tools) {
            if (t.sensitive()) throw new IllegalArgumentException(who + " " + name + "：这条 agent 带了会改东西的工具：" + t.name()
                    + "。派出去时每一笔敏感调用都要过审批闸（审批 UI 一次只接得住一趟），所以要显式写 allowSensitive(true) —— 或者把这件工具去掉");
        }
    }

    private static void checkModels(String who, List<String> models) {
        if (models == null) return;
        if (models.isEmpty() || models.stream().anyMatch(m -> m == null || m.isEmpty()))
            throw new IllegalArgumentException(who + "：model 要是一条模型（\"provider/model\"）或一张按优先顺序排的非空模型表");
    }

    private static void checkName(String who, String name) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException(who + "：agent 的名字要是非空字符串");
    }

    // ─── agents manager ─────────────────────────────────────────────────

    /**
     * 定义一条（配置形状），同名覆盖。工具在**定义时就解析好**：名字写错要在写下那一行时报，而不是派活时。
     * ⚠️ 用户不能设 hidden：一条既不在选择器里、也不在派活枚举里的 agent 等于没定义。
     * ⚠️ 每一条自定义 agent 都进派活枚举（只要它自足）；不想让模型派的用 {@link #block}。
     */
    public synchronized AgentDef define(String name, AgentConfig c) {
        String who = "define";
        checkName(who, name);
        AgentConfig cfg = c != null ? c : AgentConfig.of();
        if (cfg.hidden != null) throw new IllegalArgumentException(
                "hidden 是内置内部 agent 专用的：一条既不在选择器里、也不在派活枚举里的 agent 等于没定义");
        checkModels(who, cfg.models);
        boolean allow = Boolean.TRUE.equals(cfg.allowSensitive);
        List<AgentTool> tools = resolveTools(who, cfg);
        checkSensitive(who, name, tools, allow);
        return put(new AgentDef(name, cfg.description, cfg.prompt, () -> tools, cfg.taskKind, cfg.models, false, allow));
    }

    /**
     * 逐字段覆盖已有的一条：没写的字段沿用。⚠️ 不许改 hidden（它决定一条在不在派活枚举里，是内置的设计决定）。
     * ⚠️ 没碰工具就**原样传那个 supplier**：main 的工具是宿主现装的，在这里调一次会把"当时那一批"冻住。
     */
    public synchronized AgentDef override(String name, AgentConfig c) {
        String who = "override";
        checkName(who, name);
        AgentDef d = defs.get(name);
        if (d == null) throw new IllegalArgumentException("没有叫 \"" + name + "\" 的 agent（有这些：" + defs.keySet() + "）");
        AgentConfig cfg = c != null ? c : AgentConfig.of();
        if (cfg.hidden != null) throw new IllegalArgumentException(
                "不许改 hidden：它决定一条 agent 在不在派活枚举里，而那是内置的设计决定");
        checkModels(who, cfg.models);
        boolean allow = cfg.allowSensitive != null ? cfg.allowSensitive : d.allowSensitive();
        Supplier<List<AgentTool>> tools = d.tools();
        if (cfg.toolsTouched()) {
            List<AgentTool> resolved = resolveTools(who, cfg);
            checkSensitive(who, name, resolved, allow);
            tools = () -> resolved;
        }
        return put(new AgentDef(name,
                cfg.description != null ? cfg.description : d.description(),
                cfg.prompt != null ? cfg.prompt : d.prompt(),
                tools,
                cfg.taskKindSet ? cfg.taskKind : d.taskKind(),
                cfg.models != null ? cfg.models : d.models(),
                d.hidden(),
                allow));
    }

    /**
     * 屏蔽：从选择器与派活枚举里拿掉（**枚举那一处才是屏蔽真正生效的地方** —— 模型挑人时只读得到它）。
     * ⚠️ 只许屏蔽看得见的：hidden 的是内部用途，屏蔽掉的后果是那个功能静默消失。
     * ⚠️ 屏蔽是**记名字**，不当场删表（重置测试表时屏蔽名单不能丢 / 不能漏）。屏蔽掉当前默认是允许的。
     */
    public synchronized void block(String name) {
        checkName("block", name);
        AgentDef d = defs.get(name);
        if (d == null) throw new IllegalArgumentException("没有叫 \"" + name + "\" 的 agent（有这些：" + defs.keySet() + "）");
        if (d.hidden()) throw new IllegalArgumentException("\"" + name
                + "\" 是系统内部用途的 agent（压缩那一类），屏蔽它会让那个功能静默消失。它本来就不出现在选择器与派活枚举里，不需要屏蔽");
        if (!blocked.contains(name)) blocked.add(name);
    }

    public synchronized void unblock(String name) { blocked.remove(name); }

    public synchronized boolean isBlocked(String name) { return blocked.contains(name); }

    public synchronized List<String> blocked() { return List.copyOf(blocked); }

    /** 定默认那一条：看得见的哪一条都行，只拦 hidden。 */
    public synchronized void setDefault(String name) {
        checkName("setDefault", name);
        AgentDef d = defs.get(name);
        if (d == null) throw new IllegalArgumentException("没有叫 \"" + name + "\" 的 agent（有这些：" + defs.keySet() + "）");
        if (d.hidden()) throw new IllegalArgumentException("\"" + name + "\" 是系统内部用途的 agent，不能当默认：它不在选择器里，切进去的人也没有");
        defaultName = name;
    }

    /**
     * 默认：配置里设的 → {@code main}（没被屏蔽的话）→ 第一条看得见的 → null。
     * ⚠️ 逐级回落：屏蔽掉 main 是允许的，而那时"默认是谁"必须还有个答案。
     */
    public synchronized String defaultAgent() {
        if (defaultName != null && !blocked.contains(defaultName)) return defaultName;
        if (defs.containsKey("main") && !blocked.contains("main")) return "main";
        List<AgentDef> v = visibleDefs();
        return v.isEmpty() ? null : v.get(0).name();
    }

    /** 看得见的那些（非 hidden、没被屏蔽）：**选择器列的就是这张表**。 */
    public synchronized List<AgentDef> visibleDefs() {
        List<AgentDef> out = new ArrayList<>();
        for (AgentDef d : defs.values()) if (!d.hidden() && !blocked.contains(d.name())) out.add(d);
        return out;
    }

    /** 派得出去的那些：看得见的里**自足的**。喂 {@code subagent_type} 的枚举与那段工具描述。 */
    public synchronized List<AgentDef> subagentDefs() {
        List<AgentDef> out = new ArrayList<>();
        for (AgentDef d : visibleDefs()) if (d.dispatchable()) out.add(d);
        return out;
    }

    public List<String> subagentNames() {
        List<String> out = new ArrayList<>();
        for (AgentDef d : subagentDefs()) out.add(d.name());
        return out;
    }

    /**
     * {@code - 名字：什么时候派它}，一行一条 —— 进的是 {@code task} 工具的描述。没写说明的那条要说清"等用户点名"
     * （留空的话模型会按名字猜，而名字是给我们看的）。
     */
    public String subagentDescriptions() {
        List<String> lines = new ArrayList<>();
        for (AgentDef d : subagentDefs()) {
            lines.add("- " + d.name() + "：" + (d.description().isEmpty() ? "（没写说明的活别派给它，等用户点名）" : d.description()));
        }
        return String.join("\n", lines);
    }

    /** 清空并装回内置那几条；屏蔽名单与默认也清（不清的话"单跑绿、一起跑红"）。测试用。 */
    public synchronized void reset() {
        defs.clear();
        blocked.clear();
        defaultName = null;
        installBuiltins();
    }

    // ─── 内置那几条 ──────────────────────────────────────────────────────
    // ⚠️ 注册顺序即枚举顺序：junior 接"拿不准派谁就派它"的位置，deep 在它后面。task-kind 与 agent 同名。

    private List<AgentTool> named(String... names) {
        List<AgentTool> out = new ArrayList<>();
        for (String n : names) {
            AgentTool t = catalog.byName(n);
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    private void installBuiltins() {
        // main 也在表里：它是默认那一档，与别的 agent 是同一种东西。提示词按目录现算（null）、工具由宿主装配（空）
        put(new AgentDef("main", "主 agent：跟用户说话的那一个。", null, List::of, null, List.of(), false, false));

        // junior：唯一带写工具的子 agent（凭 allowSensitive）。⚠️ 不给 delete-path：删除是不可逆那一类
        put(new AgentDef("junior",
                "什么都能交的通用子 agent，专做**轻的、动手的活**：按指定的样子改文件、"
                        + "跑个命令看结果、顺手查点东西。拿不准派谁的时候派它。"
                        + "⚠️ 它带会改东西的工具，每一笔改动都会弹给用户点头 —— 把一件活攒成"
                        + "一次说得清的改，别拆成十次细碎的写。",
                root -> Prompts.juniorSystemPrompt(host),
                () -> {
                    List<AgentTool> out = new ArrayList<>(catalog.readOnly());
                    for (AgentTool t : named(WRITE_FILE, EDIT_FILE, REPLACE_FORM, RUN_COMMAND)) if (!out.contains(t)) out.add(t);
                    return out;
                },
                TaskKind.JUNIOR, List.of(), false, true));

        // deep：只有搜与读（显式列 —— 写成"只读那套减几件"的话那套加一件新工具时会悄悄把它也给进来）
        put(new AgentDef("deep",
                "专做「在整个仓库里找东西」的子 agent：按名字找文件、按内容搜代码、"
                        + "回答「X 是在哪儿实现的」。它只有搜和读，不翻笔记也不看语法树 —— "
                        + "要翻很多地方但结论只有几句话的活派它最划算。",
                root -> Prompts.DEEP_SYSTEM_PROMPT,
                () -> named(SEARCH_TEXT, FIND_FILES, READ_FILE, LIST_DIR),
                TaskKind.DEEP, List.of(), false, false));

        // senior：最贵的一档 —— 说明里那句"卡住了才派它"不是客套
        put(new AgentDef("senior",
                "专做**想不清楚的难题**的子 agent：查不出成因的 bug、该怎么改的架构、"
                        + "两条路选哪条。它只读，回来的是一段判断（结论 + 几步怎么做 + 工作量）。"
                        + "⚠️ 它用的是最贵的一档模型 —— 自己想一轮还是卡住了才派它，"
                        + "一眼能答的问题别派。",
                root -> Prompts.SENIOR_SYSTEM_PROMPT,
                () -> catalog.readOnly(),
                TaskKind.SENIOR, List.of(), false, false));

        // vision：一件工具都没有（图随消息附上；给 read-file 反而有害 —— 它会去按路径读那张图）
        put(new AgentDef("vision",
                "专做**看图**的子 agent，两种活都接：把截图/照片/架构图里的内容读出来"
                        + "（报错原文、界面上的字、图里画的是谁到谁），或者看着界面截图挑毛病"
                        + "（排版对不对齐、配色能不能看、有没有错位或被截断的字，每条给一句具体怎么改）。"
                        + "派它时说清要的是哪一种。"
                        + "⚠️ 派它必须在 `images` 里给出图片路径 —— 它一件工具都没有，"
                        + "只看得见你附上去的那几张图。只收 PNG / JPEG / GIF / WebP。",
                root -> Prompts.VISION_SYSTEM_PROMPT,
                List::of,
                TaskKind.VISION, List.of(), false, false));

        // compaction：hidden 的第一条。工具是空的：这一趟要的是一段话，给了工具它就会去"核实一下"
        put(new AgentDef("compaction",
                "系统自己用的：把前半段对话压成一份交接材料。不是能派的活。",
                root -> Prompts.COMPACTION_PROMPT,
                List::of,
                null, List.of(), true, false));
    }
}
