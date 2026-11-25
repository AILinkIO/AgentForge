package io.ailink.agentforge.tui.theme;

import io.ailink.agentforge.tui.term.Face;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主题注册表与「调色板 ⇒ face」映射。移植自 coxswain {@code coxn.theme}（那边又逐条对过 spacemacs 的 face）。
 * 内置 14 套调色板在资源 {@code themes.txt}（由 coxswain 的 themes.edn 转换）；另有 {@code terminal}：不刷正文底色、跟随终端。
 * 线程：注册表是全局的，只在启动 / UI 线程上改。
 */
public final class Themes {
    private Themes() {}

    private static final Map<String, Theme> REGISTRY = new LinkedHashMap<>();
    private static List<String> slots = List.of();
    private static String defaultTheme = "everforest-dark";

    static {
        load();
        REGISTRY.put("terminal", Theme.ofFaces("terminal", "跟随终端底色（不刷正文底色）", terminalFaces(), "none"));
    }

    private static void load() {
        try (InputStream in = Themes.class.getResourceAsStream("/io/ailink/agentforge/tui/themes.txt")) {
            if (in == null) throw new IllegalStateException("themes.txt missing");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String name = null, label = null, source = null;
            Map<String, int[]> pal = null;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#") || line.isBlank()) continue;
                int eq = line.indexOf('=');
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                switch (k) {
                    case "default" -> defaultTheme = v;
                    case "slots" -> slots = List.of(v.split(" "));
                    case "theme" -> {
                        if (name != null) REGISTRY.put(name, Theme.ofPalette(name, label, source, checkPalette(pal)));
                        name = v; pal = new LinkedHashMap<>(); label = v; source = null;
                    }
                    case "label" -> label = v;
                    case "source" -> source = v;
                    default -> {
                        String[] cs = v.split(" ");
                        int tc = Face.hex(cs[0]);
                        pal.put(k, new int[]{tc, cs.length > 1 ? Face.hex(cs[1]) : tc});
                    }
                }
            }
            if (name != null) REGISTRY.put(name, Theme.ofPalette(name, label, source, checkPalette(pal)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 全部槽名（照 spacemacs-theme）。 */
    public static List<String> slots() { return slots; }

    public static String defaultTheme() { return defaultTheme; }

    /** 注册顺序的主题名（选择器里的顺序）。 */
    public static synchronized List<String> names() { return new ArrayList<>(REGISTRY.keySet()); }

    public static synchronized Theme find(String name) { return REGISTRY.get(name); }

    /** 注册（同名原位替换）。调色板在这里校验：缺槽、多槽当场报。 */
    public static synchronized void register(Theme t) {
        if (t.isPalette()) checkPalette(t.palette());
        REGISTRY.put(t.name(), t);
    }

    /** 在现成的调色板上改几个槽 ⇒ 新调色板（值 "#rrggbb"）。 */
    public static Map<String, int[]> paletteWith(String base, Map<String, String> changes) {
        Theme t = find(base);
        if (t == null || !t.isPalette()) throw new IllegalArgumentException("no palette theme named " + base);
        Map<String, int[]> p = new LinkedHashMap<>(t.palette());
        changes.forEach((k, v) -> p.put(k, new int[]{Face.hex(v), Face.hex(v)}));
        return checkPalette(p);
    }

    private static Map<String, int[]> checkPalette(Map<String, int[]> p) {
        Set<String> missing = new HashSet<>(slots);
        missing.removeAll(p.keySet());
        Set<String> extra = new HashSet<>(p.keySet());
        extra.removeAll(slots);
        if (!missing.isEmpty()) throw new IllegalArgumentException("palette is missing slots: " + missing);
        if (!extra.isEmpty()) throw new IllegalArgumentException("unknown palette slots: " + extra);
        return p;
    }

    /** 主题的全部 face（调色板式按色深档推；差异式压在 base 上）。 */
    public static Map<String, Face> faces(String name, boolean trueColor) { return faces(name, trueColor, new HashSet<>()); }

    private static Map<String, Face> faces(String name, boolean trueColor, Set<String> seen) {
        Theme t = find(name);
        if (t == null) throw new IllegalArgumentException("unknown theme: " + name);
        if (t.isPalette()) return paletteToFaces(t.palette(), trueColor);
        Map<String, Face> out = new HashMap<>();
        String base = t.base() == null ? defaultTheme : t.base();
        seen.add(name);
        if (!base.equals("none") && !seen.contains(base)) out.putAll(faces(base, trueColor, seen));
        out.putAll(t.faces());
        return out;
    }

    /** 调色板 ⇒ 全部 face。改这里等于同时改所有调色板主题（映射照 coxswain palette-&gt;faces）。 */
    public static Map<String, Face> paletteToFaces(Map<String, int[]> pal, boolean trueColor) {
        Map<String, Face> m = new HashMap<>();
        java.util.function.Function<String, Integer> c = s -> {
            int[] v = pal.get(s);
            if (v == null) throw new IllegalArgumentException("no such palette slot: " + s);
            return trueColor ? v[0] : v[1];
        };
        java.util.function.Function<String, Face> fg = s -> Face.fg(c.apply(s));
        java.util.function.BiFunction<String, String, Face> on = (f, b) -> Face.of(c.apply(f), c.apply(b));
        Integer commentBg = c.apply("comment-bg").equals(c.apply("bg1")) ? null : c.apply("comment-bg");

        // 正文：主题说了算，不跟随终端底色（Emacs 的 default face 带 :background）
        m.put("default", on.apply("base", "bg1"));
        m.put("region", Face.bg(c.apply("highlight")));             // 只用底色，前景交给底下的 face
        m.put("mode-line", on.apply("base", "act1"));
        m.put("mode-line-buffer-id", on.apply("base", "act1").withBold(true));
        m.put("mode-line-inactive", on.apply("base-dim", "bg2"));
        m.put("mode-line-emphasis", on.apply("green", "act1"));
        m.put("header-line", on.apply("base", "bg2"));
        m.put("isearch", on.apply("bg1", "mat").withBold(true));   // 亮底上必须换前景
        m.put("query-replace", on.apply("bg1", "mat").withBold(true));
        m.put("lazy-highlight", Face.bg(c.apply("highlight-dim")));
        m.put("match", on.apply("head1", "head1-bg"));
        m.put("fringe", fg.apply("base-dim"));
        m.put("vertical-border", fg.apply("border"));
        m.put("minibuffer-prompt", fg.apply("keyword").withBold(true));
        m.put("shadow", fg.apply("base-dim"));
        m.put("error", fg.apply("err"));
        m.put("warning", fg.apply("war"));
        m.put("success", fg.apply("suc"));
        m.put("line-number", fg.apply("lnum"));
        m.put("line-number-current-line", fg.apply("base").withBold(true));
        m.put("show-paren-match", on.apply("bg1", "mat").withBold(true));
        // 文档 buffer（agent transcript）：按钮 / 链接、折叠块的摘要行、块标题
        m.put("button", fg.apply("keyword").withUnderline(true));
        m.put("link", fg.apply("keyword").withUnderline(true));
        m.put("fold-summary", fg.apply("base-dim").withItalic(true));
        m.put("block-title", fg.apply("head1").withBold(true));
        m.put("cursor", Face.bg(c.apply("cursor")));
        // minibuffer 的列表（helm 族）
        m.put("completion", Face.EMPTY);
        m.put("completion-selected", on.apply("base", "highlight").withBold(true));
        m.put("completion-dir", fg.apply("blue"));
        m.put("helm-source-header", on.apply("head1", "head1-bg").withBold(true));
        m.put("helm-selection", on.apply("base", "highlight"));
        m.put("helm-match", fg.apply("head1").withBold(true));
        m.put("helm-match-selected", on.apply("head1", "highlight").withBold(true));
        m.put("shadow-mode-line", on.apply("base-dim", "act1"));
        // 浮框：company / corfu 族（ttip / ttip-sl / ttip-bg）
        m.put("popup", on.apply("ttip", "ttip-bg"));
        m.put("popup-border", on.apply("border", "ttip-bg"));
        m.put("popup-scrollbar", on.apply("act2", "ttip-bg"));
        m.put("popup-selected", on.apply("base", "ttip-sl").withBold(true));
        m.put("popup-match", on.apply("keyword", "ttip-bg").withBold(true));
        m.put("popup-match-selected", on.apply("keyword", "ttip-sl").withBold(true));
        m.put("popup-detail", on.apply("type", "ttip-bg"));
        m.put("popup-kind", on.apply("func", "ttip-bg"));
        m.put("popup-doc", on.apply("base", "ttip-bg"));
        m.put("popup-prompt", on.apply("keyword", "ttip-bg").withBold(true));
        m.put("popup-dir", on.apply("blue", "ttip-bg"));
        // which-key
        m.put("which-key-key", fg.apply("func").withBold(true));
        m.put("which-key-prefix", fg.apply("keyword"));
        m.put("which-key-cmd", fg.apply("base"));
        // diff
        m.put("diff-added", on.apply("green", "green-bg"));
        m.put("diff-removed", on.apply("red", "red-bg"));
        m.put("diff-hunk-header", on.apply("blue", "bg2"));
        m.put("diff-file-header", fg.apply("base").withBold(true));
        // Markdown（transcript 渲染用；标题照 spacemacs 的 head1～head4 轮换，代码用 cblk）
        m.put("markdown-header-face-1", fg.apply("head1").withBold(true));
        m.put("markdown-header-face-2", fg.apply("head2").withBold(true));
        m.put("markdown-header-face-3", fg.apply("head3").withBold(true));
        m.put("markdown-header-face-4", fg.apply("head4").withBold(true));
        m.put("markdown-bold", Face.EMPTY.withBold(true));
        m.put("markdown-italic", Face.EMPTY.withItalic(true));
        m.put("markdown-inline-code", on.apply("cblk", "cblk-bg"));
        m.put("markdown-code-face", on.apply("cblk", "cblk-bg"));
        m.put("markdown-link", fg.apply("keyword").withUnderline(true));
        m.put("markdown-list-face", fg.apply("keyword"));
        m.put("markdown-blockquote-face", fg.apply("base-dim").withItalic(true));
        m.put("markdown-table-face", fg.apply("base-dim"));
        m.put("markdown-hr-face", fg.apply("base-dim"));
        // 语法（palette->syntax；代码块高亮以后用得上）
        m.put("font-lock-keyword-face", fg.apply("keyword").withBold(true));
        m.put("font-lock-builtin-face", fg.apply("keyword"));
        m.put("font-lock-preprocessor-face", fg.apply("func"));
        m.put("font-lock-type-face", fg.apply("type").withBold(true));
        m.put("font-lock-function-name-face", fg.apply("func").withBold(true));
        m.put("font-lock-function-call-face", fg.apply("func"));
        m.put("font-lock-string-face", fg.apply("str"));
        m.put("font-lock-escape-face", fg.apply("cyan"));
        m.put("font-lock-number-face", fg.apply("const"));
        m.put("font-lock-constant-face", fg.apply("const"));
        m.put("font-lock-variable-name-face", fg.apply("var"));
        m.put("font-lock-property-name-face", fg.apply("var"));
        m.put("font-lock-comment-face", commentBg == null ? fg.apply("comment") : Face.of(c.apply("comment"), commentBg));
        m.put("font-lock-doc-face", fg.apply("meta"));
        agentFaces(m, new Ink(c.apply("bg1"), c.apply("base"), c.apply("base-dim"), c.apply("blue"), c.apply("func"),
                c.apply("suc"), c.apply("war"), c.apply("err"), c.apply("green"), c.apply("red"), c.apply("blue"),
                c.apply("head1"), c.apply("head2"), c.apply("type"), c.apply("border")), trueColor);
        return m;
    }

    /** 推 agent 工作台 / markdown 那批 face 要的几种墨色。 */
    record Ink(int bg, int base, int dim, int accent, int func, int ok, int warn, int err, int green, int red, int blue,
               int head1, int head2, int type, int border) { }

    /**
     * agent 工作台与 markdown 渲染的 face（各主题共用一套推法）：衬底（你说的话、输入框、代码块、diff）都从正文底色
     * 往某个墨色染一点得来 —— 256 色下也保证和正文底色分得开（{@link io.ailink.agentforge.tui.term.Colors#tint}）。
     */
    static void agentFaces(Map<String, Face> m, Ink k, boolean tc) {
        int userBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.accent, 0.09, tc);
        int codeBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.base, 0.055, tc);
        int addBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.green, 0.16, tc);
        int delBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.red, 0.16, tc);
        int warnBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.warn, 0.10, tc);
        int sideBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.base, 0.03, tc);
        // 你说的话 / 输入框：竖条 + 衬底
        m.put("agent-user", Face.of(k.base, userBg).withBold(true));
        m.put("agent-user-bar", Face.of(k.accent, userBg).withBold(true));
        m.put("agent-input", Face.of(k.base, userBg));
        m.put("agent-input-bar", Face.of(k.accent, userBg).withBold(true));
        m.put("agent-input-dim", Face.of(k.dim, userBg));
        m.put("agent-input-busy-bar", Face.of(k.warn, userBg).withBold(true));
        // 记录区
        m.put("agent-dim", Face.fg(k.dim));
        m.put("agent-tool-icon", Face.fg(k.accent));
        m.put("agent-tool-name", Face.fg(k.base).withBold(true));
        m.put("agent-tool-args", Face.fg(k.dim));
        m.put("agent-ok", Face.fg(k.ok));
        m.put("agent-err", Face.fg(k.err));
        m.put("agent-err-bold", Face.fg(k.err).withBold(true));
        m.put("agent-warn", Face.fg(k.warn));
        m.put("agent-running", Face.fg(k.warn));
        m.put("agent-spinner", Face.fg(k.accent).withBold(true));
        m.put("agent-thinking", Face.fg(k.dim).withItalic(true));
        m.put("agent-tree", Face.fg(k.border));
        m.put("agent-group-tag", Face.fg(k.type));
        m.put("agent-todo-done", Face.fg(k.dim).withStrike(true));
        m.put("agent-todo-doing", Face.fg(k.base).withBold(true));
        m.put("agent-todo-mark-done", Face.fg(k.ok));
        m.put("agent-todo-mark-doing", Face.fg(k.warn).withBold(true));
        m.put("agent-todo-pending", Face.fg(k.base));
        m.put("agent-banner", Face.fg(k.accent).withBold(true));
        m.put("agent-banner-key", Face.fg(k.func).withBold(true));
        m.put("agent-approval", Face.fg(k.warn).withBold(true));
        m.put("agent-approval-bg", Face.of(k.base, warnBg));
        m.put("agent-approval-bar", Face.of(k.warn, warnBg).withBold(true));
        m.put("agent-approval-dim", Face.of(k.dim, warnBg));
        m.put("agent-approval-title", Face.of(k.warn, warnBg).withBold(true));
        m.put("agent-approval-sel", Face.of(k.base, io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.warn, 0.24, tc)).withBold(true));
        m.put("agent-input-sel", Face.of(k.base, io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.accent, 0.24, tc)).withBold(true));
        m.put("agent-input-title", Face.of(k.accent, userBg).withBold(true));
        m.put("agent-key", Face.fg(k.base).withBold(true));
        m.put("agent-side", Face.of(k.base, sideBg));
        m.put("agent-side-title", Face.of(k.head2, sideBg).withBold(true));
        m.put("agent-side-dim", Face.of(k.dim, sideBg));
        m.put("agent-side-warn", Face.of(k.warn, sideBg));
        m.put("agent-side-err", Face.of(k.err, sideBg));
        m.put("agent-side-ok", Face.of(k.ok, sideBg));
        m.put("agent-side-strike", Face.of(k.dim, sideBg).withStrike(true));
        m.put("agent-side-bold", Face.of(k.base, sideBg).withBold(true));
        m.put("agent-ctx-ok", Face.fg(k.ok));
        m.put("agent-ctx-warn", Face.fg(k.warn).withBold(true));
        m.put("agent-ctx-err", Face.fg(k.err).withBold(true));
        // diff
        m.put("diff-added", Face.of(k.green, addBg));
        m.put("diff-removed", Face.of(k.red, delBg));
        m.put("diff-hunk-header", Face.fg(k.blue).withItalic(true));
        m.put("diff-file-header", Face.fg(k.base).withBold(true));
        // markdown
        m.put("markdown-code-block", Face.of(k.base, codeBg));
        m.put("markdown-code-lang", Face.of(k.dim, codeBg).withItalic(true));
        m.put("markdown-strike", Face.fg(k.dim).withStrike(true));
        m.put("markdown-link-url", Face.fg(k.dim));
        m.put("markdown-table-border", Face.fg(k.dim));
        m.put("markdown-table-header", Face.fg(k.head2).withBold(true));
        m.put("markdown-quote-bar", Face.fg(k.accent));
        m.put("markdown-list-face", Face.fg(k.accent));
        m.put("markdown-blockquote-face", Face.fg(k.dim).withItalic(true));
        m.put("markdown-hr-face", Face.fg(k.border));
        m.put("markdown-header-rule", Face.fg(k.border));
        m.put("markdown-task-done", Face.fg(k.ok).withBold(true));
        m.put("markdown-task-todo", Face.fg(k.dim));
        m.put("markdown-image", Face.fg(k.type));
        // ── Codex 式外观（tui-agent）：你说的话 / 输入框只铺一层很淡的衬底，前缀 ›；记录里一条一个 • ──
        int histBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.base, 0.06, tc);
        int composerBg = io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.base, 0.10, tc);
        if (composerBg == histBg) composerBg = io.ailink.agentforge.tui.term.Colors.tint(histBg, k.base, 0.06, tc);
        m.put("agent-user", Face.of(k.base, histBg));
        m.put("agent-user-bar", Face.of(k.dim, histBg).withBold(true));
        m.put("agent-input", Face.of(k.base, composerBg));
        m.put("agent-input-bar", Face.of(k.base, composerBg).withBold(true));
        m.put("agent-input-busy-bar", Face.of(k.dim, composerBg).withBold(true));
        m.put("agent-input-dim", Face.of(k.dim, composerBg));
        m.put("agent-placeholder", Face.fg(k.dim));
        m.put("agent-bullet", Face.fg(k.dim));
        m.put("agent-bullet-ok", Face.fg(k.ok).withBold(true));
        m.put("agent-bullet-err", Face.fg(k.err).withBold(true));
        m.put("agent-bullet-run", Face.fg(k.accent).withBold(true));
        m.put("agent-title", Face.fg(k.base).withBold(true));
        m.put("agent-text", Face.fg(k.base).withBold(false).withItalic(false));
        m.put("agent-accent", Face.fg(k.accent));
        m.put("agent-accent-bold", Face.fg(k.accent).withBold(true));
        m.put("agent-gutter", Face.fg(k.dim));
        m.put("agent-box", Face.fg(k.border));
        m.put("agent-sel", Face.fg(k.accent).withBold(true));
        m.put("agent-magenta", Face.fg(k.type).withBold(true));
        m.put("agent-reasoning", Face.fg(k.dim).withItalic(true));
        m.put("agent-footer", Face.fg(k.dim));
        m.put("agent-footer-key", Face.fg(k.base));
        // 状态行的流光：从暗字一路亮到正文色（Codex shimmer），5 档
        for (int i = 0; i < 5; i++) {
            int col = io.ailink.agentforge.tui.term.Colors.blend(k.dim, k.base, i / 4.0);
            m.put("agent-shimmer-" + i, Face.fg(tc ? col : io.ailink.agentforge.tui.term.Colors.from256(io.ailink.agentforge.tui.term.Colors.rgbTo256(col))).withBold(true));
        }
        // 贴在输入框上方的补全列表（Codex 的 / 菜单）
        m.put("completion-list", Face.of(k.base, k.bg));
        m.put("completion-list-selected", Face.of(k.base, io.ailink.agentforge.tui.term.Colors.tint(k.bg, k.accent, 0.22, tc)).withBold(true));
        m.put("completion-list-detail", Face.fg(k.dim));
        m.put("echo-message", Face.fg(k.accent).withBold(true));
        // 每一趟子 agent 的颜色（Codex thread_color：从主题的强调色里取；按序号轮换，相邻两趟不同色）
        int[] thread = {k.accent, k.warn, k.type, k.head2, k.ok, k.func};
        for (int i = 0; i < thread.length; i++) m.put("agent-thread-" + i, Face.fg(thread[i]).withBold(true));
    }

    private static Face rgb(int r, int g, int b) { return Face.fg(r << 16 | g << 8 | b); }
    private static int c(int r, int g, int b) { return r << 16 | g << 8 | b; }

    /** :terminal 主题（跟随终端底色，modus-vivendi 风格的前景；照 coxswain display.clj 的 default-faces）。 */
    private static Map<String, Face> terminalFaces() {
        Map<String, Face> m = new HashMap<>();
        m.put("default", Face.EMPTY);
        m.put("region", Face.bg(c(58, 68, 96)));
        m.put("mode-line", Face.of(c(235, 235, 240), c(74, 78, 105)));
        m.put("mode-line-buffer-id", Face.of(c(255, 255, 255), c(74, 78, 105)).withBold(true));
        m.put("mode-line-inactive", Face.of(c(150, 150, 160), c(42, 42, 52)));
        m.put("mode-line-emphasis", Face.of(c(170, 210, 170), c(74, 78, 105)));
        m.put("header-line", Face.of(c(215, 215, 225), c(42, 42, 52)));
        m.put("isearch", Face.of(c(20, 20, 20), c(214, 128, 46)));
        m.put("query-replace", Face.of(c(20, 20, 20), c(214, 128, 46)));
        m.put("lazy-highlight", Face.bg(c(92, 86, 40)));
        m.put("match", Face.of(c(255, 240, 200), c(110, 95, 30)));
        m.put("fringe", rgb(100, 100, 130));
        m.put("vertical-border", rgb(80, 80, 104));
        m.put("minibuffer-prompt", rgb(110, 175, 255).withBold(true));
        m.put("shadow", rgb(128, 128, 138));
        m.put("error", rgb(255, 110, 110));
        m.put("warning", rgb(230, 180, 60));
        m.put("success", rgb(120, 210, 120));
        m.put("line-number", rgb(100, 100, 120));
        m.put("line-number-current-line", rgb(220, 220, 230).withBold(true));
        m.put("show-paren-match", Face.bg(c(70, 110, 160)).withBold(true));
        m.put("button", rgb(110, 175, 255).withUnderline(true));
        m.put("link", rgb(110, 175, 255).withUnderline(true));
        m.put("fold-summary", rgb(128, 128, 138).withItalic(true));
        m.put("block-title", rgb(240, 200, 100).withBold(true));
        m.put("completion", Face.EMPTY);
        m.put("completion-selected", Face.bg(c(62, 74, 118)).withBold(true));
        m.put("completion-dir", rgb(110, 175, 255));
        Face pop = Face.of(c(215, 215, 225), c(34, 36, 50));
        m.put("popup", pop);
        m.put("popup-border", pop.withFg(c(92, 98, 135)));
        m.put("popup-scrollbar", pop.withFg(c(150, 160, 210)));
        m.put("popup-selected", Face.of(c(255, 255, 255), c(62, 74, 118)).withBold(true));
        m.put("popup-match", pop.withFg(c(110, 175, 255)).withBold(true));
        m.put("popup-match-selected", Face.of(c(150, 205, 255), c(62, 74, 118)).withBold(true));
        m.put("popup-detail", pop.withFg(c(130, 132, 150)));
        m.put("popup-kind", pop.withFg(c(200, 160, 255)));
        m.put("popup-doc", pop.withFg(c(175, 178, 195)).withItalic(true));
        m.put("popup-prompt", pop.withFg(c(110, 175, 255)).withBold(true));
        m.put("popup-dir", pop.withFg(c(110, 175, 255)));
        m.put("helm-source-header", Face.of(c(240, 200, 100), c(52, 56, 74)).withBold(true));
        m.put("helm-selection", Face.of(c(255, 255, 255), c(62, 74, 118)));
        m.put("helm-match", rgb(110, 175, 255).withBold(true));
        m.put("helm-match-selected", Face.of(c(150, 205, 255), c(62, 74, 118)).withBold(true));
        m.put("shadow-mode-line", Face.of(c(160, 160, 175), c(74, 78, 105)));
        m.put("which-key-key", rgb(240, 200, 100).withBold(true));
        m.put("which-key-prefix", rgb(120, 200, 255));
        m.put("which-key-cmd", Face.EMPTY);
        m.put("diff-added", rgb(120, 210, 120));
        m.put("diff-removed", rgb(235, 110, 110));
        m.put("diff-hunk-header", Face.of(c(110, 175, 255), c(36, 40, 56)));
        m.put("diff-file-header", rgb(220, 220, 230).withBold(true));
        m.put("markdown-header-face-1", rgb(240, 200, 100).withBold(true));
        m.put("markdown-header-face-2", rgb(110, 175, 255).withBold(true));
        m.put("markdown-header-face-3", rgb(120, 210, 180).withBold(true));
        m.put("markdown-header-face-4", rgb(200, 160, 255).withBold(true));
        m.put("markdown-bold", Face.EMPTY.withBold(true));
        m.put("markdown-italic", Face.EMPTY.withItalic(true));
        m.put("markdown-inline-code", Face.of(c(255, 200, 140), c(44, 46, 60)));
        m.put("markdown-code-face", Face.of(c(215, 215, 225), c(36, 38, 50)));
        m.put("markdown-link", rgb(110, 175, 255).withUnderline(true));
        m.put("markdown-list-face", rgb(184, 148, 255));
        m.put("markdown-blockquote-face", rgb(160, 160, 175).withItalic(true));
        m.put("markdown-table-face", rgb(100, 100, 130));
        m.put("markdown-hr-face", rgb(100, 100, 130));
        m.put("font-lock-keyword-face", rgb(184, 148, 255).withBold(true));
        m.put("font-lock-builtin-face", rgb(255, 166, 87));
        m.put("font-lock-preprocessor-face", rgb(220, 160, 120));
        m.put("font-lock-type-face", rgb(86, 214, 214));
        m.put("font-lock-function-name-face", rgb(96, 175, 255));
        m.put("font-lock-function-call-face", rgb(150, 195, 255));
        m.put("font-lock-string-face", rgb(121, 213, 128));
        m.put("font-lock-escape-face", rgb(255, 200, 100));
        m.put("font-lock-number-face", rgb(247, 140, 108));
        m.put("font-lock-constant-face", rgb(230, 170, 255));
        m.put("font-lock-variable-name-face", rgb(255, 210, 140));
        m.put("font-lock-property-name-face", rgb(180, 200, 225));
        m.put("font-lock-comment-face", rgb(128, 128, 148).withItalic(true));
        m.put("font-lock-doc-face", rgb(150, 182, 140).withItalic(true));
        agentFaces(m, new Ink(c(30, 30, 34), c(215, 215, 225), c(128, 128, 138), c(110, 175, 255), c(96, 175, 255),
                c(120, 210, 120), c(230, 180, 60), c(255, 110, 110), c(120, 210, 120), c(235, 110, 110), c(110, 175, 255),
                c(240, 200, 100), c(110, 175, 255), c(86, 214, 214), c(80, 80, 104)), true);
        return m;
    }
}
