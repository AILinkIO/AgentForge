package io.ailink.agentforge.tui.term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 终端字符流 ⇒ 按键事件。移植自 coxswain {@code coxn.term.input}：CSI/SS3 序列、修饰位 ;2..;8、
 * {@code ESC x} ⇒ {@code M-x}、kitty（CSI u）/ modifyOtherKeys（CSI 27;m;c ~）、bracketed paste、SGR 鼠标、焦点事件。
 */
public final class InputDecoder {
    /** 读一个字符码：timeout 毫秒，0 = 一直等。⇒ 字符码，-2 = 超时，-1 = EOF。 */
    @FunctionalInterface
    public interface CharReader { int read(long timeoutMs) throws IOException; }

    /**
     * 转义序列各字节之间最多等多久。ESC 本身照 Emacs 当 Meta 前缀（命令循环里），
     * 所以放宽没有副作用；30ms 在机器很忙 / JIT 时会把 ESC &gt; 拆开（coxswain 实测）。
     */
    public static final long ESC_TIMEOUT_MS = 100;

    private final CharReader rd;

    public InputDecoder(CharReader rd) { this.rd = rd; }

    private static final Map<Integer, String> TILDE = Map.ofEntries(
            Map.entry(1, "<home>"), Map.entry(2, "<insert>"), Map.entry(3, "<delete>"), Map.entry(4, "<end>"),
            Map.entry(5, "<prior>"), Map.entry(6, "<next>"), Map.entry(7, "<home>"), Map.entry(8, "<end>"),
            Map.entry(11, "<f1>"), Map.entry(12, "<f2>"), Map.entry(13, "<f3>"), Map.entry(14, "<f4>"),
            Map.entry(15, "<f5>"), Map.entry(17, "<f6>"), Map.entry(18, "<f7>"), Map.entry(19, "<f8>"),
            Map.entry(20, "<f9>"), Map.entry(21, "<f10>"), Map.entry(23, "<f11>"), Map.entry(24, "<f12>"));

    private static String letterKey(char c) {
        return switch (c) {
            case 'A' -> "<up>"; case 'B' -> "<down>"; case 'C' -> "<right>"; case 'D' -> "<left>";
            case 'H' -> "<home>"; case 'F' -> "<end>";
            case 'P' -> "<f1>"; case 'Q' -> "<f2>"; case 'R' -> "<f3>"; case 'S' -> "<f4>";
            default -> null;
        };
    }

    // Emacs 里这几个本来就是同一个键（C-i 就是 TAB …）：kitty 分得出来，但照 Emacs 合回去
    private static final Map<String, String> EMACS_EQUIV = Map.of(
            "C-i", "TAB", "C-m", "RET", "C-[", "ESC", "C-M-i", "M-TAB", "C-M-m", "M-RET", "C-M-[", "M-ESC",
            "S-TAB", "<backtab>");

    private static final int SHIFT = 1, META = 2, CTRL = 4, SUPER = 8;

    private static int mods(int param) { return Math.max(0, param - 1) & 15; }

    private static String withMods(String k, int m) {
        boolean special = k.startsWith("<") || k.equals("RET") || k.equals("TAB") || k.equals("DEL")
                || k.equals("SPC") || k.equals("ESC");
        return ((m & CTRL) != 0 ? "C-" : "") + ((m & META) != 0 ? "M-" : "")
                + ((m & SHIFT) != 0 && special ? "S-" : "") + ((m & SUPER) != 0 ? "s-" : "") + k;
    }

    /** 单个字符码（非 ESC）⇒ 键名。 */
    public static String ctrlCharKey(int c) {
        return switch (c) {
            case 0 -> "C-SPC";
            case 9 -> "TAB";
            case 13 -> "RET";
            case 10 -> "C-j";
            case 32 -> "SPC";
            case 127 -> "DEL";
            case 28 -> "C-\\";
            case 29 -> "C-]";
            case 30 -> "C-^";
            case 31 -> "C-_";
            default -> c >= 1 && c <= 26 ? "C-" + (char) (c + 96) : new String(Character.toChars(c));
        };
    }

    /** "x" ⇒ "M-x"，"C-x" ⇒ "C-M-x"。 */
    public static String addMeta(String k) {
        if (k.contains("M-")) return k;
        if (k.startsWith("C-")) return "C-M-" + k.substring(2);
        return "M-" + k;
    }

    /**
     * kitty（CSI u）/ modifyOtherKeys 报上来的码点 + 修饰 ⇒ 键名。
     * Shift+字母：只有 Shift 时写成大写（"A"），同时有 Ctrl/Meta 时写成 C-S-a（Emacs 的写法）。
     */
    private static String codepointKey(int cp, int m) {
        String base = switch (cp) {
            case 9 -> "TAB"; case 13 -> "RET"; case 27 -> "ESC"; case 32 -> "SPC"; case 127 -> "DEL";
            default -> new String(Character.toChars(cp));
        };
        boolean letter = base.codePointCount(0, base.length()) == 1 && Character.isLetter(base.codePointAt(0));
        boolean cm = (m & (CTRL | META)) != 0;
        if (letter && (m & SHIFT) != 0 && !cm) base = base.toUpperCase();
        else if (letter && cm) base = base.toLowerCase();
        boolean shift = (m & SHIFT) != 0 && (base.length() > 1 && !letter || letter && cm);
        String k = ((m & CTRL) != 0 ? "C-" : "") + ((m & META) != 0 ? "M-" : "") + (shift ? "S-" : "")
                + ((m & SUPER) != 0 ? "s-" : "") + base;
        return EMACS_EQUIV.getOrDefault(k, k);
    }

    private String readPaste() throws IOException {
        StringBuilder sb = new StringBuilder();
        String end = "\u001b[201~";
        while (true) {
            int c = rd.read(0);
            if (c < 0) return sb.toString();
            sb.append((char) c);
            int n = sb.length();
            if (n >= 6 && sb.substring(n - 6).equals(end)) return sb.substring(0, n - 6);
        }
    }

    private InputEvent parseCsi() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = rd.read(ESC_TIMEOUT_MS);
            if (c < 0) return new InputEvent.Key("M-[");
            if (c >= 0x40 && c <= 0x7E) {
                char fin = (char) c;
                String params = sb.toString();
                boolean mouse = params.startsWith("<");
                List<Integer> ps = new ArrayList<>();
                for (String f : (mouse ? params.substring(1) : params).split(";", -1)) {
                    String head = f.split(":", -1)[0];         // kitty 的参数可以带子字段（97:65;6u）：只取冒号前面那个
                    int v = 1;
                    if (!head.isBlank()) try { v = Integer.parseInt(head.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { v = 1; }
                    ps.add(v);
                }
                int p0 = ps.get(0), p1 = ps.size() > 1 ? ps.get(1) : 1;
                if (mouse && ps.size() >= 3) return new InputEvent.Mouse(p0, ps.get(1) - 1, ps.get(2) - 1, fin == 'm');
                if (fin == '~' && p0 == 200) return new InputEvent.Paste(readPaste());
                if (fin == '~' && p0 == 27 && ps.size() == 3) return new InputEvent.Key(codepointKey(ps.get(2), mods(p1)));
                if (fin == '~') {
                    String k = TILDE.get(p0);
                    return k != null ? new InputEvent.Key(withMods(k, mods(p1))) : new InputEvent.Unknown("CSI " + params + fin);
                }
                if (fin == 'u') return new InputEvent.Key(codepointKey(p0, mods(p1)));
                if (fin == 'Z') return new InputEvent.Key("<backtab>");
                if (fin == 'I') return new InputEvent.Focus(true);
                if (fin == 'O') return new InputEvent.Focus(false);
                String lk = letterKey(fin);
                if (lk != null) return new InputEvent.Key(withMods(lk, mods(p1)));
                return new InputEvent.Unknown("CSI " + params + fin);
            }
            sb.append((char) c);
        }
    }

    /** 读一个（可能是代理对的）字符，已知第一个码 c。 */
    private String readCharKey(int c) throws IOException {
        if (Character.isHighSurrogate((char) c)) {
            int lo = rd.read(ESC_TIMEOUT_MS);
            return lo < 0 ? String.valueOf((char) c) : "" + (char) c + (char) lo;
        }
        return ctrlCharKey(c);
    }

    /** 阻塞读一个事件；EOF 返回 null。 */
    public InputEvent read() throws IOException {
        while (true) {
            int c = rd.read(0);
            if (c == -1) return null;
            if (c == -2) continue;
            if (c == 27) {
                int c2 = rd.read(ESC_TIMEOUT_MS);
                if (c2 < 0) return new InputEvent.Key("ESC");
                if (c2 == '[') return parseCsi();
                if (c2 == 'O') {
                    int c3 = rd.read(ESC_TIMEOUT_MS);
                    String k = c3 < 0 ? null : letterKey((char) c3);
                    return new InputEvent.Key(k != null ? k : "M-O");
                }
                if (c2 == 27) return new InputEvent.Key("M-ESC");   // ESC ESC = M-ESC（照 Emacs）
                return new InputEvent.Key(addMeta(readCharKey(c2)));
            }
            return new InputEvent.Key(readCharKey(c));
        }
    }

    /** 测试用：把字符串当终端输入。 */
    public static CharReader stringReader(String s) {
        int[] i = {0};
        return t -> i[0] < s.length() ? s.charAt(i[0]++) : -1;
    }

    /** 测试用：整个字符串解码成事件列表。 */
    public static List<InputEvent> decodeAll(String s) {
        InputDecoder d = new InputDecoder(stringReader(s));
        List<InputEvent> out = new ArrayList<>();
        try {
            for (InputEvent e; (e = d.read()) != null; ) out.add(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out;
    }
}
