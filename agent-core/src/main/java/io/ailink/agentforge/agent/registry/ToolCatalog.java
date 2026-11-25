package io.ailink.agentforge.agent.registry;

import io.ailink.agentforge.agent.tool.AgentTool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 有哪些工具存在（对应 coxswain {@code toolset-all} / {@code read-only-tools} / {@code default-tools}）。
 *
 * <p>注册表**不 import 具体工具**（它们在别的包、由宿主装配，有的还要等 MCP 连上）：宿主把目录装进
 * {@link AgentRegistry#toolCatalog(ToolCatalog)}，内置 agent 的工具在**派活那一刻**按名字现取。
 *
 * <p>⚠️ 目录**不过 enabled**：它答的是"这个名字指的是哪件工具"。关掉的工具仍然认得出名字 —— 否则用户配了它、
 * 又恰好关着，得到的会是"打错了名字"，而他没打错。给不给是会话装配那一步的事。
 * ⚠️ 宿主得**先装目录再建会话 / 读配置**：没装的话按名字挑工具那条路认不出任何名字。
 */
public interface ToolCatalog {

    /** 全部登记过的工具（内置 + 宿主自己的 + MCP）。 */
    List<AgentTool> all();

    /** 只读那一套（子 agent 的默认）。 */
    default List<AgentTool> readOnly() {
        List<AgentTool> out = new ArrayList<>();
        for (AgentTool t : all()) if (t.readOnly()) out.add(t);
        return out;
    }

    /** 主 agent 默认那一套。 */
    default List<AgentTool> defaults() { return all(); }

    /** 按名字找；没有 → null。 */
    default AgentTool byName(String name) {
        for (AgentTool t : all()) if (t.name().equals(name)) return t;
        return null;
    }

    default List<String> names() {
        List<String> out = new ArrayList<>();
        for (AgentTool t : all()) out.add(t.name());
        return out;
    }

    ToolCatalog EMPTY = List::of;

    /** 每次现取（工具表是运行期算出来的）。 */
    static ToolCatalog of(Supplier<List<AgentTool>> all) { return all::get; }
}
