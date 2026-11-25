package io.ailink.agentforge.agent.route;

/**
 * 路由表的一行：这类活 → 哪条模型 → 为什么。
 *
 * @param fallback true = **能力兜底**选出来的（可能比主模型贵）。界面要与省钱那几条分开显示 —— 一条"更贵了"
 *                 和一条"更便宜了"的决定摆在一起而不加区分，是在误导
 */
public record RouteEntry(TaskKind kind, String model, String why, boolean fallback) { }
