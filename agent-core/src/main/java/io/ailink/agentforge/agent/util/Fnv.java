package io.ailink.agentforge.agent.util;

import java.nio.charset.StandardCharsets;

/**
 * 32 位 FNV-1a，喂 UTF-8 字节，出 8 位大写十六进制（coxswain {@code content-tag} 同一个算法）。
 * memory 目录名的路径指纹用它 —— 与 coxswain 算出来逐位相同，旧笔记目录能直接认。
 */
public final class Fnv {
    private Fnv() { }

    public static int hash(String text) {
        int h = 0x811C9DC5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= 16777619;
        }
        return h;
    }

    public static String hex8(String text) {
        return String.format("%08X", hash(text));
    }
}
