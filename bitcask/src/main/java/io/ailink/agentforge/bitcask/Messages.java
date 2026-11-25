package io.ailink.agentforge.bitcask;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 错误文案（资源 {@code messages[_语言].properties}，UTF-8；中性 = 中文）。语言：{@link #withLocale} 的线程内覆盖 ⇒
 * {@link #setLocale} 设的全局值 ⇒ JVM 显示语言。缺译的键、没有资源的语言都回落中文（⛔ 不回落到 JVM 缺省语言的资源）。
 * 占位符 {@code {0}} {@code {1}} …（同 .NET {@code string.Format} 的最简形式）。键缺失 ⇒ 回键名本身（不抛）。
 */
public final class Messages {
    private static final String BASE = "/io/ailink/agentforge/bitcask/messages";
    private static final Map<String, Map<String, String>> TABLES = new ConcurrentHashMap<>();
    private static final Pattern ARG = Pattern.compile("\\{(\\d+)}");
    private static final ThreadLocal<Locale> SCOPED = new ThreadLocal<>();
    private static volatile Locale global;

    private Messages() {
    }

    /** 宿主覆盖的界面语言（null = 跟 JVM 显示语言）。 */
    public static void setLocale(Locale locale) {
        global = locale;
    }

    /** 这一刻渲染文案用的语言。 */
    public static Locale locale() {
        Locale l = SCOPED.get();
        if (l != null) return l;
        l = global;
        return l != null ? l : Locale.getDefault(Locale.Category.DISPLAY);
    }

    /** 在 {@code locale} 下求值（当前线程；测试用）。 */
    public static <T> T withLocale(Locale locale, Supplier<T> body) {
        Locale old = SCOPED.get();
        SCOPED.set(locale);
        try {
            return body.get();
        } finally {
            if (old == null) SCOPED.remove();
            else SCOPED.set(old);
        }
    }

    /** 当前语言下的模板；没有 ⇒ null。 */
    public static String template(String code) {
        for (String suffix : candidates(locale())) {
            String t = table(suffix).get(code);
            if (t != null) return t;
        }
        return null;
    }

    /** 渲染 {@code code} 的文案。 */
    public static String text(String code, Object... args) {
        String t = template(code);
        if (t == null) return code;
        Matcher m = ARG.matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int i = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(i < args.length ? String.valueOf(args[i]) : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 全部键（中性表；测试用）。 */
    static Map<String, String> neutralTable() {
        return table("");
    }

    static Map<String, String> table(String suffix) {
        return TABLES.computeIfAbsent(suffix, s -> {
            Properties p = new Properties();
            try (InputStream in = Messages.class.getResourceAsStream(BASE + s + ".properties")) {
                if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 资源读不了 ⇒ 当作没有这一语言
            }
            Map<String, String> m = new ConcurrentHashMap<>();
            for (String k : p.stringPropertyNames()) m.put(k, p.getProperty(k));
            return m;
        });
    }

    /** 语言 ⇒ 资源后缀的回落链：_zh_CN ⇒ _zh ⇒ 中性。 */
    private static List<String> candidates(Locale l) {
        List<String> out = new ArrayList<>(3);
        String lang = l.getLanguage(), country = l.getCountry();
        if (!lang.isEmpty() && !country.isEmpty()) out.add("_" + lang + "_" + country);
        if (!lang.isEmpty()) out.add("_" + lang);
        out.add("");
        return out;
    }
}
