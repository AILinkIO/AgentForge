package io.ailink.agentforge.agent.tools.web;

import io.ailink.agentforge.agent.tools.web.Html.Element;
import io.ailink.agentforge.agent.tools.web.Html.Node;
import io.ailink.agentforge.agent.tools.web.Html.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * DOM → markdown / 纯文本（coxswain {@code keel html markdown} 与 fetch.ss 的 {@code %html->text}）。
 *
 * <p>markdown 那条：标题 {@code #}、粗斜体、行内代码、{@code pre} 围栏、链接 / 图片、嵌套列表、
 * 引用、表格（管道表）。script / style / head 等整棵丢掉。
 *
 * <p>实现要点：先渲染再规整（去行首尾空白、压空行）。为了让规整不吃掉缩进与代码块，缩进用
 * {@code \u0001} 占位、{@code pre} 内容先存起来留一个 {@code \u0002N\u0002} 记号，最后一起换回。
 */
public final class HtmlRender {
    private HtmlRender() { }

    static final Set<String> SKIP = Set.of("script", "style", "noscript", "iframe", "object", "embed", "head",
            "template", "svg", "canvas", "select", "button");
    static final Set<String> BLOCK = Set.of("p", "div", "section", "article", "main", "header", "footer", "nav",
            "aside", "figure", "figcaption", "address", "form", "fieldset", "details", "summary", "body", "html", "center");

    private static final char INDENT = '\u0001';
    private static final char PRE = '\u0002';

    // ── markdown ───────────────────────────────────────────────────────

    public static String markdown(Element doc) {
        Md m = new Md();
        String s = m.block(doc);
        s = normalize(s);
        // 换回代码块（按所在行的前缀给每一行补同样的前缀）
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            int a = line.indexOf(PRE);
            if (a >= 0) {
                int b = line.indexOf(PRE, a + 1);
                if (b > a) {
                    String prefix = line.substring(0, a);
                    String code = m.pres.get(Integer.parseInt(line.substring(a + 1, b)));
                    out.append(prefix).append("```\n");
                    for (String cl : code.split("\n", -1)) out.append(prefix).append(cl).append('\n');
                    out.append(prefix).append("```\n");
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString().replace(INDENT, ' ').strip();
    }

    /** 每行去两头空白（占位符不算空白）、连续空行压成一行、整体去两头空行。 */
    static String normalize(String s) {
        StringBuilder out = new StringBuilder();
        int blank = 0;
        boolean any = false;
        for (String raw : s.split("\n", -1)) {
            String line = stripSpaces(raw);
            if (line.isEmpty()) { blank++; continue; }
            if (any && blank > 0) out.append('\n');
            out.append(line).append('\n');
            any = true;
            blank = 0;
        }
        return out.toString();
    }

    private static String stripSpaces(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isSpace(char c) { return c == ' ' || c == '\t' || c == '\r' || c == ' ' || c == '\f'; }

    private static final class Md {
        final List<String> pres = new ArrayList<>();

        String block(Node n) {
            StringBuilder b = new StringBuilder();
            render(n, b, false);
            return b.toString();
        }

        void children(Element e, StringBuilder b, boolean inPre) {
            for (Node c : e.children) render(c, b, inPre);
        }

        String inner(Element e) {
            StringBuilder b = new StringBuilder();
            children(e, b, false);
            return b.toString();
        }

        void render(Node n, StringBuilder b, boolean inPre) {
            if (n instanceof Text t) {
                b.append(collapse(t.text()));
                return;
            }
            Element e = (Element) n;
            String name = e.name;
            if (SKIP.contains(name)) return;
            switch (name) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    String t = oneLine(inner(e));
                    if (!t.isEmpty()) b.append("\n\n").append("#".repeat(name.charAt(1) - '0')).append(' ').append(t).append("\n\n");
                }
                case "br" -> b.append('\n');
                case "hr" -> b.append("\n\n---\n\n");
                case "strong", "b" -> wrap(b, inner(e), "**");
                case "em", "i" -> wrap(b, inner(e), "*");
                case "del", "s", "strike" -> wrap(b, inner(e), "~~");
                case "code", "kbd", "samp", "tt" -> {
                    String t = textOf(e);
                    if (!t.isBlank()) {
                        String fence = t.contains("`") ? "``" : "`";
                        b.append(fence).append(t.replace('\n', ' ')).append(fence);
                    }
                }
                case "pre" -> {
                    String code = textOf(e);
                    while (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
                    while (code.startsWith("\n")) code = code.substring(1);
                    pres.add(code);
                    b.append("\n\n").append(PRE).append(pres.size() - 1).append(PRE).append("\n\n");
                }
                case "a" -> {
                    String t = oneLine(inner(e));
                    String href = e.attr("href");
                    if (t.isEmpty()) return;
                    if (href == null || href.isBlank() || href.startsWith("javascript:") || href.startsWith("#")) b.append(t);
                    else b.append('[').append(t).append("](").append(href.strip().replace(" ", "%20")).append(')');
                }
                case "img" -> {
                    String src = e.attr("src");
                    if (src == null || src.isBlank() || src.startsWith("data:")) return;
                    String alt = e.attr("alt");
                    b.append("![").append(alt == null ? "" : oneLine(alt)).append("](").append(src.strip()).append(')');
                }
                case "ul", "ol", "menu" -> list(e, b, name.equals("ol"));
                case "blockquote" -> {
                    String body = normalize(inner(e));
                    b.append("\n\n");
                    for (String l : body.split("\n")) b.append("> ").append(l).append('\n');
                    b.append('\n');
                }
                case "table" -> table(e, b);
                case "dt" -> { String t = oneLine(inner(e)); if (!t.isEmpty()) b.append("\n\n**").append(t).append("**\n"); }
                case "dd" -> b.append('\n').append(inner(e)).append('\n');
                case "li" -> { b.append("\n- ").append(inner(e)).append('\n'); }
                case "tr" -> { b.append('\n').append(inner(e)).append('\n'); }
                case "td", "th" -> b.append(' ').append(inner(e)).append(' ');
                default -> {
                    if (BLOCK.contains(name) || name.equals("dl")) {
                        b.append("\n\n");
                        children(e, b, inPre);
                        b.append("\n\n");
                    } else {
                        children(e, b, inPre);
                    }
                }
            }
        }

        void list(Element e, StringBuilder b, boolean ordered) {
            int start = 1;
            if (ordered) {
                try { start = Integer.parseInt(e.attr("start") == null ? "1" : e.attr("start").strip()); } catch (NumberFormatException ignored) { }
            }
            b.append("\n\n");
            int k = start;
            for (Node c : e.children) {
                if (!(c instanceof Element li) || !li.name.equals("li")) {
                    if (c instanceof Element other && !SKIP.contains(other.name)) b.append(inner(other));
                    continue;
                }
                String marker = ordered ? (k++) + ". " : "- ";
                String body = normalize(inner(li));
                while (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
                String[] lines = body.isEmpty() ? new String[]{""} : body.split("\n", -1);
                String pad = String.valueOf(INDENT).repeat(marker.length());
                boolean first = true;
                for (String l : lines) {
                    if (first) { b.append(marker).append(l).append('\n'); first = false; }
                    else if (l.isEmpty()) b.append('\n');
                    else b.append(pad).append(l).append('\n');
                }
            }
            b.append('\n');
        }

        void table(Element t, StringBuilder b) {
            List<List<String>> rows = new ArrayList<>();
            collectRows(t, rows);
            if (rows.isEmpty()) return;
            int cols = 0;
            for (List<String> r : rows) cols = Math.max(cols, r.size());
            b.append("\n\n");
            for (int i = 0; i < rows.size(); i++) {
                List<String> r = rows.get(i);
                b.append('|');
                for (int c = 0; c < cols; c++) b.append(' ').append(c < r.size() ? r.get(c) : "").append(" |");
                b.append('\n');
                if (i == 0) {
                    b.append('|');
                    for (int c = 0; c < cols; c++) b.append(" --- |");
                    b.append('\n');
                }
            }
            b.append('\n');
        }

        void collectRows(Element e, List<List<String>> rows) {
            for (Node c : e.children) {
                if (!(c instanceof Element x)) continue;
                if (x.name.equals("tr")) {
                    List<String> cells = new ArrayList<>();
                    for (Node cc : x.children) {
                        if (cc instanceof Element td && (td.name.equals("td") || td.name.equals("th"))) {
                            cells.add(oneLine(inner(td)).replace("|", "\\|"));
                        }
                    }
                    rows.add(cells);
                } else if (!x.name.equals("table")) {
                    collectRows(x, rows);
                }
            }
        }

        static void wrap(StringBuilder b, String t, String mark) {
            String s = oneLine(t);
            if (!s.isEmpty()) b.append(mark).append(s).append(mark);
        }
    }

    /** 文本节点里的空白（含换行）压成一个空格。 */
    static String collapse(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f') {
                if (!ws) b.append(' ');
                ws = true;
            } else {
                b.append(c);
                ws = false;
            }
        }
        return b.toString();
    }

    static String oneLine(String s) {
        return collapse(s.replace(INDENT, ' ')).replace(' ', ' ').strip();
    }

    /** 元素里的原文（不压空白；pre / code 用）。 */
    static String textOf(Element e) {
        StringBuilder b = new StringBuilder();
        rawText(e, b);
        return b.toString();
    }

    private static void rawText(Element e, StringBuilder b) {
        for (Node c : e.children) {
            if (c instanceof Text t) b.append(t.text());
            else if (c instanceof Element x) {
                if (x.name.equals("br")) b.append('\n');
                else if (!SKIP.contains(x.name)) rawText(x, b);
            }
        }
    }

    // ── 纯文本 ─────────────────────────────────────────────────────────

    static final Set<String> TEXT_SKIP = Set.of("script", "style", "noscript", "iframe", "object", "embed", "head", "template");
    static final Set<String> TEXT_BLOCK = Set.of("p", "div", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6",
            "tr", "table", "section", "article", "header", "footer", "pre", "blockquote", "hr", "nav", "aside",
            "main", "dd", "dt", "dl");

    /** 只留正文文字：块级元素前后换行，行首空白去掉，连续空行最多留一个。 */
    public static String text(Element doc) {
        StringBuilder b = new StringBuilder();
        walkText(doc, b);
        return squeeze(b.toString());
    }

    private static void walkText(Node n, StringBuilder b) {
        if (n instanceof Text t) { b.append(t.text()); return; }
        Element e = (Element) n;
        if (TEXT_SKIP.contains(e.name)) return;
        boolean block = TEXT_BLOCK.contains(e.name);
        if (block) b.append('\n');
        for (Node c : e.children) walkText(c, b);
        if (block) b.append('\n');
    }

    static String squeeze(String s) {
        StringBuilder out = new StringBuilder();
        int nl = 0;
        boolean lineStart = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                if (nl < 2) out.append('\n');
                nl++;
                lineStart = true;
            } else if (lineStart && (c == ' ' || c == '\t' || c == '\r' || c == ' ')) {
                // 行首空白丢掉
            } else {
                out.append(c);
                nl = 0;
                lineStart = false;
            }
        }
        return out.toString().strip();
    }
}
