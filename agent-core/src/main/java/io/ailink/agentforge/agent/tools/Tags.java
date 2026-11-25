package io.ailink.agentforge.agent.tools;

import java.util.Locale;

/**
 * 内容标识：{@code [路径#TAG]} 里的 TAG = 内容 UTF-8 字节的 32 位 FNV-1a，8 位大写十六进制
 * （对应 coxswain {@code tools/tag.ss}）。
 *
 * <p>按真的 UTF-8 字节喂，才是 FNV-1a 本身 —— {@code "hello"} 必须是 {@code 4F9F2CAB}（公开测试向量）。
 * 就地按码点算，不另分配一份字节串。
 */
public final class Tags {
    private Tags() { }

    private static final int OFFSET = 0x811C9DC5;
    private static final int PRIME = 0x01000193;

    private static int mix(int h, int b) { return (h ^ b) * PRIME; }

    public static String contentTag(String text) {
        int h = OFFSET;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c < 0x80) h = mix(h, c);
            else if (c < 0x800) {
                h = mix(h, 0xC0 | (c >>> 6));
                h = mix(h, 0x80 | (c & 0x3F));
            } else if (c < 0x10000) {
                h = mix(h, 0xE0 | (c >>> 12));
                h = mix(h, 0x80 | ((c >>> 6) & 0x3F));
                h = mix(h, 0x80 | (c & 0x3F));
            } else {
                h = mix(h, 0xF0 | (c >>> 18));
                h = mix(h, 0x80 | ((c >>> 12) & 0x3F));
                h = mix(h, 0x80 | ((c >>> 6) & 0x3F));
                h = mix(h, 0x80 | (c & 0x3F));
            }
        }
        return String.format("%08X", h);
    }

    private static boolean junk(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '[' || c == ']'; }

    /** {@code [foo.c#TAG]} / {@code #TAG} / {@code TAG} 都归成 {@code TAG}（大写）。 */
    public static String normalize(String s) {
        int n = s.length();
        int start = s.lastIndexOf('#') + 1;
        int a = start;
        while (a < n && junk(s.charAt(a))) a++;
        int b = n;
        while (b > a && junk(s.charAt(b - 1))) b--;
        return s.substring(a, b).toUpperCase(Locale.ROOT);
    }

    public static boolean matches(String given, String actual) {
        return normalize(given).equals(actual.toUpperCase(Locale.ROOT));
    }
}
