package io.ailink.agentforge.tui;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 界面文案 i18n（照 coxswain {@code coxn.i18n}：msgid = 英文原文；查不到记进漏翻登记、原样返回，永不抛）。
 * 用户定界面用中文：默认语言 zh；环境变量 {@code AGENTFORGE_LANG=en} 回到英文原文。
 * 译文里的格式指令（%s / %d）个数必须与原文一致 —— 否则那句话真显示的一刻 format 才炸。
 */
public final class I18n {
    private I18n() {}

    private static final Map<String, Map<String, String>> CATALOGS = new ConcurrentHashMap<>();
    private static final Set<String> MISSING = java.util.Collections.synchronizedSet(new TreeSet<>());
    private static volatile String lang;

    static {
        CATALOGS.put("zh", load("/io/ailink/agentforge/tui/messages_zh.properties"));
        String env = System.getenv("AGENTFORGE_LANG");
        lang = env != null && !env.isBlank() ? env.trim().toLowerCase().split("[._]")[0] : "zh";
    }

    private static Map<String, String> load(String res) {
        Map<String, String> m = new HashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream(res)) {
            if (in == null) return m;
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            p.forEach((k, v) -> m.put((String) k, (String) v));
        } catch (IOException ignored) { }
        return m;
    }

    public static String language() { return lang; }

    /** 切换语言（en 永远可用 —— 它就是原文自己）。 */
    public static void setLanguage(String l) { lang = l; }

    /** 往某语言里并一张表（上层模块加自己的文案用这条）。 */
    public static void merge(String l, Map<String, String> table) {
        CATALOGS.computeIfAbsent(l, k -> new ConcurrentHashMap<>()).putAll(table);
    }

    /** 查当前语言的译文。 */
    public static String t(String msgid) {
        if (msgid == null || "en".equals(lang)) return msgid;
        Map<String, String> c = CATALOGS.get(lang);
        String v = c == null ? null : c.get(msgid);
        if (v == null) { MISSING.add(msgid); return msgid; }
        return v;
    }

    /** t 再 format。 */
    public static String format(String fmt, Object... args) {
        String f = t(fmt);
        return args.length == 0 ? f : String.format(f, args);
    }

    /** 漏翻登记（当前语言查不到的 msgid）。 */
    public static Set<String> missing() { return Set.copyOf(MISSING); }
}
