package io.ailink.agentforge.tui.agent;

import io.ailink.agentforge.tui.I18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作台文案（照 tui 的约定：msgid = 英文原文，中文译文在 {@code messages_zh.txt}，启动时并进 {@link I18n}）。
 * 文件一行一条：{@code 英文 => 中文}；{@code #} 开头是注释。缺译文时原样显示英文（漏翻登记见 {@link I18n#missing()}）。
 */
public final class Msg {
    private Msg() { }

    private static volatile boolean loaded;

    /** 把本模块的中文表并进 I18n（幂等）。 */
    public static void install() {
        if (loaded) return;
        synchronized (Msg.class) {
            if (loaded) return;
            I18n.merge("zh", catalog());
            loaded = true;
        }
    }

    /** 读中文表（测试也用：检查占位符个数一致）。 */
    public static Map<String, String> catalog() {
        Map<String, String> m = new LinkedHashMap<>();
        try (InputStream in = Msg.class.getResourceAsStream("messages_zh.txt")) {
            if (in == null) return m;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int i = line.indexOf(" => ");
                if (i < 0) continue;
                m.put(line.substring(0, i), line.substring(i + 4));
            }
        } catch (IOException ignored) { }
        return m;
    }

    /** 查译文。 */
    public static String t(String msgid) { install(); return I18n.t(msgid); }

    /** 查译文再 format。 */
    public static String f(String fmt, Object... args) { install(); return I18n.format(fmt, args); }
}
