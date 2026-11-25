package io.ailink.agentforge.tui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 增量搜索（C-s / C-r / C-M-s，coxswain {@code coxn.commands.search}）：模态收键器。只读的 transcript 里也能用。
 * 打字延长搜索串、C-s/C-r 找下一个 / 上一个（撞墙后再按一次绕回）、DEL 退一步、RET 停在这里（原位置记进 mark）、
 * C-g 回原处（失败时先去掉没匹配上的那截）、C-w 把 point 后面的词加进来、C-y 加 kill ring 顶上的。别的键：退出再照常派发。
 * 全小写不分大小写（智能大小写）。
 */
public final class Isearch implements KeyInterceptor {
    private record St(String string, int[] match, boolean failing, boolean wrapped, int dir, int point) {}

    private int dir;
    private String string = "";
    private final int origin;
    private final Deque<St> stack = new ArrayDeque<>();
    private int[] match;
    private boolean failing, wrapped;
    private final boolean regexp;
    private static String lastString;
    private static boolean lastRegexp;

    Isearch(Editor ed, int dir, boolean regexp) {
        this.dir = dir;
        this.regexp = regexp;
        this.origin = ed.point();
    }

    /** 开始搜索（命令 isearch-forward 等的底层）。 */
    public static void start(Editor ed, int dir, boolean regexp) {
        Isearch is = new Isearch(ed, dir, regexp);
        ed.isearch = is;
        ed.pushKeyInterceptor(is);
    }

    public String string() { return string; }
    public int[] match() { return match; }
    public boolean failing() { return failing; }

    public String prompt() {
        return (failing ? I18n.t("Failing ") : "") + (wrapped ? I18n.t("Wrapped ") : "")
                + I18n.t(regexp ? "Regexp I-search" : "I-search") + (dir < 0 ? I18n.t(" backward") : "") + ": " + string;
    }

    /** string ⇒ Pattern；全小写时不分大小写。正则写错 ⇒ null。 */
    static Pattern compile(String s, boolean regexp) {
        try {
            int flags = regexp ? Pattern.MULTILINE : Pattern.LITERAL;
            if (s.equals(s.toLowerCase())) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            return Pattern.compile(s, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    static int[] findForward(CharSequence cs, Pattern p, int from) {
        if (p == null || from > cs.length()) return null;
        java.util.regex.Matcher m = p.matcher(cs);
        return m.find(Math.max(0, from)) ? new int[]{m.start(), m.end()} : null;
    }

    /** 开始位置 &lt; from 的最后一个匹配（从 from 往回按块找，块逐次翻倍）。 */
    static int[] findBackward(CharSequence cs, Pattern p, int from) {
        if (p == null) return null;
        int len = cs.length();
        from = Math.min(from, len);
        int right = Math.min(len, from + 4096 + p.pattern().length());
        for (int chunk = 65536; ; chunk *= 2) {
            int left = Math.max(0, from - chunk);
            java.util.regex.Matcher m = p.matcher(cs);
            m.region(left, right);
            m.useAnchoringBounds(false);
            m.useTransparentBounds(true);
            int[] last = null;
            while (m.find() && m.start() < from) last = new int[]{m.start(), m.end()};
            if (last != null) return last;
            if (left == 0) return null;
        }
    }

    /** [a, b) 里的全部匹配（lazy highlight），最多 500 个。 */
    List<int[]> lazyMatches(Buffer b, int a, int z) {
        List<int[]> out = new ArrayList<>();
        if (string.isEmpty()) return out;
        Pattern p = compile(string, regexp);
        if (p == null) return out;
        java.util.regex.Matcher m = p.matcher(b.text);
        m.region(Math.max(0, a), Math.min(z, b.length()));
        m.useAnchoringBounds(false);
        m.useTransparentBounds(true);
        while (out.size() < 500 && m.find()) {
            if (m.start() == m.end()) break;
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    private void search(Editor ed, int from) {
        Pattern p = compile(string, regexp);
        CharSequence cs = ed.currentBuffer().text;
        int[] m = dir > 0 ? findForward(cs, p, from) : findBackward(cs, p, from);
        if (m != null) {
            match = m;
            failing = false;
            ed.goTo(dir > 0 ? m[1] : m[0]);
        } else failing = true;
    }

    private void push(Editor ed) { stack.push(new St(string, match, failing, wrapped, dir, ed.point())); }

    void addString(Editor ed, String s) {
        push(ed);
        string += s;
        // 原地延长：正向从当前匹配开头找，反向从当前匹配末尾往回
        int from = match != null ? (dir > 0 ? match[0] : match[1] + s.length()) : ed.point();
        search(ed, from);
    }

    private void repeat(Editor ed, int d) {
        if (string.isEmpty()) {
            if (lastString != null) { dir = d; string = lastString; search(ed, ed.point()); }
            else dir = d;
        } else if (d != dir) {
            push(ed);
            dir = d;
            if (match != null) ed.goTo(d > 0 ? match[1] : match[0]);
        } else if (failing) {
            push(ed);
            wrapped = true;
            search(ed, d > 0 ? 0 : ed.currentBuffer().length());
        } else {
            push(ed);
            int from = d > 0 ? (match != null && match[0] == match[1] ? match[1] + 1 : match != null ? match[1] : ed.point())
                    : (match != null ? match[0] : ed.point());
            search(ed, from);
        }
    }

    private void pop(Editor ed) {
        St s = stack.poll();
        if (s == null) return;
        string = s.string;
        match = s.match;
        failing = s.failing;
        wrapped = s.wrapped;
        dir = s.dir;
        ed.goTo(s.point);
    }

    private void exit(Editor ed) {
        ed.isearch = null;
        ed.popKeyInterceptor(this);
        if (!string.isEmpty()) { lastString = string; lastRegexp = regexp; }
        if (origin != ed.point()) {
            ed.currentBuffer().setMark(origin, false);
            ed.message("Mark saved where search started");
        }
    }

    private void quit(Editor ed) {
        if (failing && !stack.isEmpty()) {
            while (failing && !stack.isEmpty()) pop(ed);
            return;
        }
        ed.isearch = null;
        ed.popKeyInterceptor(this);
        ed.goTo(origin);
        ed.message("Quit");
    }

    @Override
    public boolean handleKey(Editor ed, String k) {
        switch (k) {
            case "C-s", "C-M-s" -> repeat(ed, 1);
            case "C-r", "C-M-r" -> repeat(ed, -1);
            case "DEL" -> pop(ed);
            case "RET" -> exit(ed);
            case "C-g" -> quit(ed);
            case "C-w" -> {
                Buffer b = ed.currentBuffer();
                int p = ed.point(), q = p;
                while (q < b.length() && !CompletionSource.isWord(b.charAt(q))) q++;
                while (q < b.length() && CompletionSource.isWord(b.charAt(q))) q++;
                if (q > p) addString(ed, b.substring(p, q));
            }
            case "C-y" -> { String s = ed.currentKill(); if (s != null) addString(ed, s); }
            default -> {
                if (Keymap.printable(k)) addString(ed, Keymap.keyChar(k));
                else { exit(ed); return false; }
            }
        }
        return true;
    }
}
