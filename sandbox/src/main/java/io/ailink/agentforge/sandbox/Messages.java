package io.ailink.agentforge.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * 沙盒文案表（资源 {@code io/ailink/agentforge/sandbox/strings*.properties}，UTF-8）。中文是基表，其他语言回英文。
 * 占位符 {@code {0} {1}} 按位置替换（⛔ MessageFormat：英文文案里有单引号，MessageFormat 会把它当转义吞掉）。
 * 语言缺省取 {@link Locale#getDefault()}；{@link #withLocale} 可在当前线程临时切换（测试用）。
 */
public final class Messages {
    private Messages() {
    }

    private static final Properties ZH = load("strings.properties");
    private static final Properties EN = load("strings_en.properties");
    private static final ThreadLocal<Locale> OVERRIDE = new ThreadLocal<>();

    private static Properties load(String name) {
        Properties p = new Properties();
        try (InputStream in = Messages.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("missing resource " + name);
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** 当前线程生效的语言。 */
    public static Locale locale() {
        Locale l = OVERRIDE.get();
        return l != null ? l : Locale.getDefault();
    }

    /** 在当前线程用 {@code locale} 求值 {@code body}。 */
    public static <T> T withLocale(Locale locale, Supplier<T> body) {
        Locale old = OVERRIDE.get();
        OVERRIDE.set(locale);
        try {
            return body.get();
        } finally {
            if (old == null) OVERRIDE.remove();
            else OVERRIDE.set(old);
        }
    }

    /** 某语言的原始文案表（只读用；测试校验两份表一致）。 */
    public static Properties table(boolean english) {
        Properties copy = new Properties();
        copy.putAll(english ? EN : ZH);
        return copy;
    }

    /** 渲染文案；没有这个码 ⇒ 文案就是码本身（加参数），不抛。 */
    public static Message message(String code, Object... args) {
        Properties table = "zh".equals(locale().getLanguage()) ? ZH : EN;
        String template = table.getProperty(code);
        if (template == null) template = EN.getProperty(code);
        if (template == null) {
            StringBuilder sb = new StringBuilder(code);
            for (Object a : args) sb.append(' ').append(a);
            return new Message(code, sb.toString());
        }
        return new Message(code, format(template, args));
    }

    static String format(String template, Object[] args) {
        StringBuilder sb = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i + 1);
                if (close > i + 1) {
                    String inner = template.substring(i + 1, close);
                    int colon = inner.indexOf(':');
                    String num = colon >= 0 ? inner.substring(0, colon) : inner;
                    if (num.chars().allMatch(Character::isDigit)) {
                        int n = Integer.parseInt(num);
                        sb.append(n < args.length ? String.valueOf(args[n]) : "{" + inner + "}");
                        i = close + 1;
                        continue;
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
