package io.ailink.agentforge.agent.tools.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 宽松的小 HTML 解析器：够 web-fetch 把网页变成 markdown / 纯文本用，不是 HTML5 规范实现。
 *
 * <p>（coxswain 用 {@code keel html parser}；这里不额外引 jsoup，自己写一份。）认：注释、doctype、
 * void 元素、自闭合、script/style 等原文元素、属性（带引号 / 不带）、命名与数字实体、
 * 常见的隐式闭合（p 遇块级、li / tr / td / dt / dd / option 遇同级）。不认识的关闭标签忽略。
 */
public final class Html {
    private Html() { }

    public sealed interface Node permits Element, Text { }

    public record Text(String text) implements Node { }

    public static final class Element implements Node {
        public final String name;
        public final Map<String, String> attrs;
        public final List<Node> children = new ArrayList<>();
        final Element parent;

        Element(String name, Map<String, String> attrs, Element parent) {
            this.name = name;
            this.attrs = attrs;
            this.parent = parent;
        }

        public String attr(String k) { return attrs.get(k); }
    }

    static final Set<String> VOID = Set.of("area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "param", "source", "track", "wbr", "keygen");
    static final Set<String> RAW = Set.of("script", "style", "textarea", "title", "xmp", "noscript", "template");
    static final Set<String> CLOSES_P = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "dl",
            "table", "pre", "blockquote", "section", "article", "header", "footer", "nav", "aside", "main",
            "hr", "form", "figure", "address", "fieldset");

    public static Element parse(String html) {
        Element doc = new Element("#document", Map.of(), null);
        Element cur = doc;
        int n = html.length(), i = 0;
        StringBuilder text = new StringBuilder();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<' || i + 1 >= n) { text.append(c); i++; continue; }
            char d = html.charAt(i + 1);
            if (html.startsWith("<!--", i)) {
                flush(cur, text);
                int e = html.indexOf("-->", i + 4);
                i = e < 0 ? n : e + 3;
            } else if (d == '!' || d == '?') {
                flush(cur, text);
                int e = html.indexOf('>', i);
                i = e < 0 ? n : e + 1;
            } else if (d == '/') {
                int e = html.indexOf('>', i);
                if (e < 0) { text.append(c); i++; continue; }
                flush(cur, text);
                String name = tagName(html, i + 2, e);
                i = e + 1;
                for (Element x = cur; x != null && x != doc; x = x.parent) {
                    if (x.name.equals(name)) { cur = x.parent; break; }
                }
            } else if (Character.isLetter(d)) {
                int e = tagEnd(html, i + 1);
                if (e < 0) { text.append(c); i++; continue; }
                flush(cur, text);
                String inner = html.substring(i + 1, e);
                boolean selfClose = inner.endsWith("/");
                if (selfClose) inner = inner.substring(0, inner.length() - 1);
                int sp = 0;
                while (sp < inner.length() && !Character.isWhitespace(inner.charAt(sp)) && inner.charAt(sp) != '/') sp++;
                String name = inner.substring(0, sp).toLowerCase(Locale.ROOT);
                Map<String, String> attrs = attrs(inner.substring(sp));
                i = e + 1;
                cur = implicitClose(cur, doc, name);
                Element el = new Element(name, attrs, cur);
                cur.children.add(el);
                if (RAW.contains(name)) {
                    int close = indexOfIgnoreCase(html, "</" + name, i);
                    String raw = html.substring(i, close < 0 ? n : close);
                    if (!raw.isEmpty()) {
                        boolean decode = name.equals("textarea") || name.equals("title");
                        el.children.add(new Text(decode ? decodeEntities(raw) : raw));
                    }
                    if (close < 0) i = n;
                    else {
                        int gt = html.indexOf('>', close);
                        i = gt < 0 ? n : gt + 1;
                    }
                } else if (!selfClose && !VOID.contains(name)) {
                    cur = el;
                }
            } else {
                text.append(c);
                i++;
            }
        }
        flush(cur, text);
        return doc;
    }

    private static Element implicitClose(Element cur, Element doc, String name) {
        if (CLOSES_P.contains(name) && cur.name.equals("p")) cur = cur.parent;
        switch (name) {
            case "li" -> cur = closeThrough(cur, doc, Set.of("li"), Set.of("ul", "ol", "menu"));
            case "dt", "dd" -> cur = closeThrough(cur, doc, Set.of("dt", "dd"), Set.of("dl"));
            case "tr" -> cur = closeThrough(cur, doc, Set.of("tr"), Set.of("table", "thead", "tbody", "tfoot"));
            case "td", "th" -> cur = closeThrough(cur, doc, Set.of("td", "th"), Set.of("tr", "table"));
            case "option" -> cur = closeThrough(cur, doc, Set.of("option"), Set.of("select", "datalist"));
            case "p" -> cur = closeThrough(cur, doc, Set.of("p"), Set.of("div", "section", "article", "td", "li", "blockquote", "body"));
            default -> { }
        }
        return cur;
    }

    /** 往上找 targets 里的一个（遇 stops 就停）；找到就关到它的父。 */
    private static Element closeThrough(Element cur, Element doc, Set<String> targets, Set<String> stops) {
        for (Element x = cur; x != null && x != doc; x = x.parent) {
            if (targets.contains(x.name)) return x.parent;
            if (stops.contains(x.name)) break;
        }
        return cur;
    }

    private static void flush(Element cur, StringBuilder text) {
        if (text.isEmpty()) return;
        cur.children.add(new Text(decodeEntities(text.toString())));
        text.setLength(0);
    }

    private static String tagName(String s, int from, int to) {
        int a = from;
        while (a < to && Character.isWhitespace(s.charAt(a))) a++;
        int b = a;
        while (b < to && !Character.isWhitespace(s.charAt(b)) && s.charAt(b) != '/') b++;
        return s.substring(a, b).toLowerCase(Locale.ROOT);
    }

    /** 开标签的 {@code >}：跳过引号里的。 */
    private static int tagEnd(String s, int from) {
        char q = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; }
            else if (c == '"' || c == '\'') q = c;
            else if (c == '>') return i;
            else if (c == '<') return -1;
        }
        return -1;
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int m = needle.length();
        for (int i = from; i + m <= s.length(); i++) {
            if (s.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }

    static Map<String, String> attrs(String s) {
        Map<String, String> m = new HashMap<>();
        int i = 0, n = s.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '/')) i++;
            int a = i;
            while (i < n && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '=' && s.charAt(i) != '/') i++;
            if (a == i) { i++; continue; }
            String k = s.substring(a, i).toLowerCase(Locale.ROOT);
            while (i < n && Character.isWhitespace(s.charAt(i))) i++;
            String v = "";
            if (i < n && s.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                if (i < n && (s.charAt(i) == '"' || s.charAt(i) == '\'')) {
                    char q = s.charAt(i++);
                    int e = s.indexOf(q, i);
                    if (e < 0) e = n;
                    v = s.substring(i, e);
                    i = Math.min(n, e + 1);
                } else {
                    int b = i;
                    while (i < n && !Character.isWhitespace(s.charAt(i))) i++;
                    v = s.substring(b, i);
                }
            }
            m.putIfAbsent(k, decodeEntities(v));
        }
        return m;
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("copy", "©"), Map.entry("reg", "®"),
            Map.entry("trade", "™"), Map.entry("hellip", "…"), Map.entry("mdash", "—"), Map.entry("ndash", "–"),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("middot", "·"), Map.entry("bull", "•"),
            Map.entry("times", "×"), Map.entry("divide", "÷"), Map.entry("euro", "€"), Map.entry("pound", "£"),
            Map.entry("yen", "¥"), Map.entry("cent", "¢"), Map.entry("sect", "§"), Map.entry("para", "¶"),
            Map.entry("deg", "°"), Map.entry("plusmn", "±"), Map.entry("larr", "←"), Map.entry("rarr", "→"),
            Map.entry("uarr", "↑"), Map.entry("darr", "↓"), Map.entry("shy", ""), Map.entry("zwj", "‍"),
            Map.entry("zwnj", "‌"), Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("thinsp", " "));

    public static String decodeEntities(String s) {
        int amp = s.indexOf('&');
        if (amp < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '&') { b.append(c); i++; continue; }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 12) { b.append(c); i++; continue; }
            String ent = s.substring(i + 1, semi);
            String rep = null;
            if (ent.startsWith("#")) {
                try {
                    int cp = ent.length() > 1 && (ent.charAt(1) == 'x' || ent.charAt(1) == 'X')
                            ? Integer.parseInt(ent.substring(2), 16) : Integer.parseInt(ent.substring(1));
                    if (Character.isValidCodePoint(cp) && cp != 0) rep = new String(Character.toChars(cp));
                } catch (NumberFormatException ignored) { }
            } else {
                rep = ENTITIES.get(ent);
            }
            if (rep == null) { b.append(c); i++; continue; }
            b.append(rep);
            i = semi + 1;
        }
        return b.toString();
    }
}
