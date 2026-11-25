package io.ailink.agentforge.tui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 键表 = 键名 ⇒ 绑定。绑定是命令名（String）、命令对象（{@link Command}，匿名命令）或下一层 Keymap（前缀键）。
 * 前缀可以带名字（which-key 显示 "+名字"）。可以有父表（Emacs 的 set-keymap-parent）：自己没绑的键去父表找。
 * 移植自 coxswain {@code coxn.keymap}；键名写法照 Emacs kbd："C-x C-f"、"C-h k"、"&lt;up&gt;"、"S-&lt;left&gt;"。
 */
public final class Keymap {
    private final Map<String, Object> map = new LinkedHashMap<>();
    private String name;
    private Keymap parent;

    public Keymap() {}
    public Keymap(String name) { this.name = name; }

    public String name() { return name; }
    public Keymap setName(String n) { name = n; return this; }
    public Keymap parent() { return parent; }
    public Keymap setParent(Keymap p) { parent = p; return this; }

    // ── 键名 ──────────────────────────────────────────────────────────

    private static final Set<String> BARE_SPECIALS = Set.of("left", "right", "up", "down", "home", "end", "prior",
            "next", "insert", "delete", "backtab", "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12");
    private static final Pattern MODS = Pattern.compile("((?:[CMSs]-)*)(.+)");

    /** "C-x" 原样；"left"/"S-left" ⇒ "&lt;left&gt;"/"S-&lt;left&gt;"；"pageup" ⇒ "&lt;prior&gt;"；修饰按 C- M- S- 排好。 */
    public static String normalizeKey(String k) {
        Matcher m = MODS.matcher(k);
        if (!m.matches() || k.length() == 1) return k;
        String mods = m.group(1), base = m.group(2);
        base = switch (base) {
            case "pageup" -> "prior"; case "pagedown" -> "next"; case "escape" -> "ESC"; case "return" -> "RET";
            case "tab" -> "TAB"; case "backspace" -> "DEL"; case "space" -> "SPC";
            default -> base;
        };
        if (BARE_SPECIALS.contains(base)) base = "<" + base + ">";
        return (mods.contains("C-") ? "C-" : "") + (mods.contains("M-") ? "M-" : "") + (mods.contains("S-") ? "S-" : "")
                + (mods.contains("s-") ? "s-" : "") + base;
    }

    /** "C-x C-f" ⇒ ["C-x", "C-f"]。 */
    public static List<String> kbd(String s) {
        List<String> out = new ArrayList<>();
        for (String k : s.trim().split("\\s+")) if (!k.isEmpty()) out.add(normalizeKey(k));
        return out;
    }

    public static String keyDesc(List<String> keys) { return String.join(" ", keys); }

    /** 裸字符（插进正文的那种）。 */
    public static boolean printable(String k) {
        if (k.equals("SPC")) return true;
        if (k.startsWith("<") || k.matches("^[CMSs]-.+") || Set.of("RET", "TAB", "DEL", "ESC").contains(k)) return false;
        return k.codePointCount(0, k.length()) == 1;
    }

    public static String keyChar(String k) { return k.equals("SPC") ? " " : k; }

    // ── 绑定 ──────────────────────────────────────────────────────────

    /** keys 绑到命令名。中间缺的前缀自动建。 */
    public Keymap define(String keys, String command) { return bind(kbd(keys), command); }

    /** keys 绑到匿名命令。 */
    public Keymap define(String keys, Command command) { return bind(kbd(keys), command); }

    /** keys 绑到一张子表（整棵前缀树挂上来）。 */
    public Keymap define(String keys, Keymap sub) { return bind(kbd(keys), sub); }

    /** 解绑。 */
    public Keymap undefine(String keys) {
        List<String> ks = kbd(keys);
        Keymap m = this;
        for (int i = 0; i < ks.size() - 1; i++) {
            if (!(m.map.get(ks.get(i)) instanceof Keymap sub)) return this;
            m = sub;
        }
        m.map.remove(ks.getLast());
        return this;
    }

    private Keymap bind(List<String> ks, Object v) {
        Keymap m = this;
        for (int i = 0; i < ks.size() - 1; i++) {
            m = m.child(ks.get(i));
        }
        m.map.put(ks.getLast(), v);
        return this;
    }

    private Keymap child(String k) {
        if (map.get(k) instanceof Keymap sub) return sub;
        Keymap sub = new Keymap();
        map.put(k, sub);
        return sub;
    }

    /** 给前缀起名字（which-key 显示 +名字）。 */
    public Keymap prefixName(String keys, String n) {
        List<String> ks = kbd(keys);
        Keymap m = this;
        for (String k : ks) {
            m = m.child(k);
        }
        m.name = n;
        return this;
    }

    /** 这一层（含父表）里 key 的绑定。 */
    public Object get(String key) {
        Object v = map.get(key);
        if (v == null && parent != null) return parent.get(key);
        // 自己和父表都是前缀：合起来（子表优先）
        if (v instanceof Keymap sub && parent != null && parent.get(key) instanceof Keymap psub && sub.parent == null)
            return merged(List.of(sub, psub));
        return v;
    }

    /** 沿键序列往下走：命令名 / Command / 前缀 Keymap / null。 */
    public Object lookup(List<String> keys) {
        Object cur = this;
        for (String k : keys) {
            if (!(cur instanceof Keymap m)) return null;
            cur = m.get(k);
            if (cur == null) return null;
        }
        return cur;
    }

    /**
     * 在一串键表里查键序列（前面的优先）：⇒ 命令名 / Command / 前缀（合并后的 Keymap）/ null。
     * 前缀跨表合并：同一前缀在多张表里都是 Keymap 时合起来（which-key 看到全部）。
     */
    public static Object lookup(List<Keymap> maps, List<String> keys) {
        List<Object> hits = new ArrayList<>();
        for (Keymap m : maps) {
            Object h = m.lookup(keys);
            if (h != null) hits.add(h);
        }
        if (hits.isEmpty()) return null;
        if (!(hits.getFirst() instanceof Keymap)) return hits.getFirst();
        List<Keymap> prefixes = new ArrayList<>();
        for (Object h : hits) {
            if (!(h instanceof Keymap km)) break;
            prefixes.add(km);
        }
        return prefixes.size() == 1 ? prefixes.getFirst() : merged(prefixes);
    }

    private static Keymap merged(List<Keymap> ms) {
        Keymap out = new Keymap();
        for (int i = ms.size() - 1; i >= 0; i--) {
            Keymap m = ms.get(i);
            for (String k : m.keys()) {
                Object v = m.get(k);
                Object old = out.map.get(k);
                if (v instanceof Keymap a && old instanceof Keymap b) out.map.put(k, merged(List.of(a, b)));
                else out.map.put(k, v);
            }
            if (m.name != null) out.name = m.name;
        }
        return out;
    }

    /** 这一层的键（含父表的），注册顺序。 */
    public Set<String> keys() {
        Set<String> ks = new LinkedHashSet<>(map.keySet());
        if (parent != null) ks.addAll(parent.keys());
        return ks;
    }

    /** 前缀下的直接子项：[[键, 命令名或 +名字]]，按键排序（短键在前）。which-key 用。 */
    public List<String[]> bindingsUnder() {
        List<String[]> out = new ArrayList<>();
        for (String k : keys()) {
            Object v = get(k);
            String d = v instanceof Keymap km ? "+" + (km.name != null ? km.name : "prefix")
                    : v instanceof String s ? s : "λ";
            out.add(new String[]{k, d});
        }
        out.sort(Comparator.<String[]>comparingInt(a -> a[0].length()).thenComparing(a -> a[0]));
        return out;
    }

    /** 命令在键表里的第一个键序列（短的优先）或 null。 */
    public String whereIs(String command) {
        List<String> p = walk(this, command, new ArrayList<>(), new LinkedHashSet<>());
        return p == null ? null : keyDesc(p);
    }

    private static List<String> walk(Keymap m, String cmd, List<String> path, Set<Keymap> seen) {
        if (!seen.add(m)) return null;
        List<String> ks = new ArrayList<>(m.keys());
        ks.sort(Comparator.comparingInt(String::length));
        for (String k : ks) if (cmd.equals(m.get(k))) { path.add(k); return path; }
        for (String k : ks) {
            if (m.get(k) instanceof Keymap sub) {
                List<String> p = new ArrayList<>(path);
                p.add(k);
                List<String> r = walk(sub, cmd, p, seen);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** 全部 [键序列, 绑定描述]（describe-bindings 用）。 */
    public List<String[]> flatten() {
        List<String[]> out = new ArrayList<>();
        flatten(this, new ArrayList<>(), out, new LinkedHashSet<>());
        return out;
    }

    private static void flatten(Keymap m, List<String> prefix, List<String[]> out, Set<Keymap> seen) {
        if (!seen.add(m)) return;
        for (String k : m.keys()) {
            Object v = m.get(k);
            List<String> p = new ArrayList<>(prefix);
            p.add(k);
            if (v instanceof Keymap sub) flatten(sub, p, out, seen);
            else out.add(new String[]{keyDesc(p), v instanceof String s ? s : "λ"});
        }
    }

    /** 绑到的全部命令名。 */
    public Set<String> commandNames() {
        Set<String> out = new LinkedHashSet<>();
        for (String[] b : flatten()) out.add(b[1]);
        return out;
    }
}
