package io.ailink.agentforge.sandbox.exec;

/**
 * 一段输出（⛔ 解码 —— 编码交给上层）。
 *
 * @param stream 来自哪条流
 * @param data   原始字节
 */
public record OutputChunk(Stream stream, byte[] data) {
    /** 输出流。 */
    public enum Stream { STDOUT, STDERR }
}
