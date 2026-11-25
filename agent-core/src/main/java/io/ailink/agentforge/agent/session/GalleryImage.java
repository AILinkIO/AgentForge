package io.ailink.agentforge.agent.session;

/**
 * 这段对话里出现过的一张图（从历史里摘掉之后 UI 仍要能回看）。
 *
 * @param owner 谁带进来的：{@code user:<第几句>} 或 {@code tool:<tool_use_id>}
 */
public record GalleryImage(int id, String name, String mediaType, String data, long bytes, String owner) { }
